# How the braid is built

git-timebraid recreates every commit of every input repository in one output repository, each input
under its own subdirectory, and adds artificial parent edges that chain commits *across* repositories
in chronological order. The resulting first-parent chain is called **the braid**.

This document is the specification of that construction: which parents each commit ends up with,
which tree, what happens to branches, and where the "state of the world at that moment" guarantee
stops holding. For what the tool is for and how to run it, see the [README](../README.md).

Vocabulary used throughout:

- **input repository** — one of the repositories being merged; contributes one subdirectory to the
  output, at a path that may be nested
- **mainline** — the branch treated as each input's main line of development (`--mainline-branch`)
- **ordering timestamp** — the timestamp the interleaving compares: the committer date, or the author
  date with `--order-by author`. It is settled once when the input is read.

---

## The parent rule

How the sequence is built:

- The braid is built from the **first-parent chain** of each input repository's mainline branch.
- Those chains are merged into one sequence by always taking whichever chain's next commit is oldest
  — a k-way merge, the same idea as merging sorted lists.
- A chain's own order never changes regardless of what its timestamps say, so ancestry on the
  mainline holds by construction; across repositories, the earlier ordering timestamp comes first.

How parents are rewritten. For every commit `c`, writing `pred` for the commit preceding it in that
sequence:

```
c is not on the braid          →  parents'(c) = parents(c)             unchanged
pred is already a parent of c  →  parents'(c) = parents(c)             unchanged
otherwise                      →  parents'(c) = [pred] + parents(c)    braided edge prepended
```

That is the whole rule. Note what it does **not** do:

- It never removes a parent — every original edge stays exactly where it was.
- The braided edge is *prepended*, so it becomes the first parent. That is what makes
  `git log --first-parent` walk the braid.

The arithmetic follows:

| commit in its original repo | predecessor on the braid            | parents in the braid                         |
|-----------------------------|-------------------------------------|----------------------------------------------|
| ordinary commit             | same repo (i.e. already its parent) | **1** — unchanged                            |
| ordinary commit             | a different repo                    | **2** — braided edge + original parent       |
| merge commit                | a different repo                    | **3** — braided edge + both original parents |

### Why three parents

The three-parent case is not a curiosity, it is the price of the guarantee. Suppose `webui` merged a
feature branch, and the commit immediately preceding that merge in time came from `backend`:

```
original:   parents(b3)  = [b2, f1]           b3 merges the feature branch f1 into b2
braid:      parents'(b3) = [a4, b2, f1]
                            │   └── b2 and f1: every original edge, untouched
                            └─────── a4: the braided edge, i.e. the commit
                                    preceding b3 in time
```

Drop `b2` here and you would have a tidier graph and a false history: `git merge-base`,
`git log --first-parent webui-side`, and every "when did this diverge" question would start lying.
So it keeps all three.

---

## The tree rule

A commit's tree in the braid is derived from its first parent:

> **tree'(c)** = the tree of `parents'(c)[0]`, with the entry at `c`'s own destination replaced by
> `c`'s original tree.

For the very first commit on the braid there is no parent, so the tree is just that one destination.

Because the first parent is the time predecessor, the map of *subdirectory → content* accumulates as
you walk forward:

- Each subdirectory holds whatever its repository last committed at or before this point.
- A repository that did not exist yet simply is not there.

This is the mechanism behind the whole promise: "the state of every repository at that moment" is not
computed on demand, it is simply what the commit's tree contains.

A subdirectory entry *is* the input's own root tree object, so nothing is recursed into and no blob is
copied: the output shares its content with the inputs, and each braided commit costs one small tree.

### Nested destinations

A destination may be a path rather than a single name (`repo.git::=libs/backend`). An entry name cannot
hold a `/`, so the segments above the last one are trees the braid builds itself, and inputs sharing
a prefix share the tree for it:

```
$ git ls-tree -r --name-only HEAD | head
apps/webui/index.html
libs/backend/src/Main.kt
libs/codegen/gen.py
```

What follows from that is the whole of the rule:

- A commit costs one tree per *changed* prefix rather than one, and no more than that: an untouched
  prefix keeps the tree object the previous commit used, which is what content addressing gives for
  free.
- **No destination may contain another, unless `--splice` says so.** The entry written at a
  destination is the input's own tree object, and there is no room beside it, so `libs` and
  `libs/backend` cannot both hold a repository the cheap way. The planner refuses the pair by
  default.
- **`--splice` opens the containing repository's tree instead.** Its content at `libs/` is read into
  entries and the input placed inside it goes in beside them, so the directory ends up holding both.
  This is the one place the braid descends into an input's tree, and it descends only along the
  prefixes some destination names.
- **The repository at the output root is spliced without the flag.** Every destination lies inside
  it, which is what `--root-repo` asked for; the flag exists for the containments that are implicit
  in the paths, where a typo and an intention look alike.
- **A splice is not a merge of two repositories' files.** What the containing repository already
  holds at the inner destination is a collision, and so is a prefix segment that is not a directory
  there. Both depend on the tree at that commit, which the planner has never seen, so both are
  answered ahead of the write pass instead — one walk over the plan, examining each containing
  repository's tree once per *distinct* tree rather than once per commit — and named against the
  commit they happen at.
- **`--dissolve-submodules` excepts one entry: a gitlink at the destination itself.** There the
  containing repository was already saying another repository belongs at that exact path, so the
  input landing there replaces the gitlink instead of colliding with it. See
  [dissolving a submodule](#dissolving-a-submodule) below, which is where the `.gitmodules` side of
  it is worked out.

### The one exception: `.gitmodules`

`.gitmodules` is the only file whose *location* is part of its meaning — git reads it from the
repository root and nowhere else. Carried along inside a subdirectory it would become text nothing
reads, describing paths that no longer say where the gitlink it names actually sits. So it is the one
file the braid writes for itself:

> **`.gitmodules`** at the root of `tree'(c)` = the `[submodule]` sections of every input that has
> content at `c`, with each section's `path` — and its name — prefixed by that input's destination.

The gitlink entries need no help; they ride along in their input's tree like any other entry, and the
commit a gitlink names is fetched from the submodule's own url rather than from this repository.

```
$ git ls-tree -r HEAD
100644 blob fa14b89…    .gitmodules          <- written by the braid
100644 blob c70678b…    backend/.gitmodules  <- the input's own, carried along, now inert
100644 blob 7898192…    backend/a.txt
160000 commit 4196d3c…  backend/vendor/lib   <- the gitlink, untouched

$ git show HEAD:.gitmodules
[submodule "backend/vendor/lib"]
	path = backend/vendor/lib
	url = https://example.com/lib.git
```

Two consequences worth knowing:

- The input's own `<subdir>/.gitmodules` stays where the tree rule put it. Rewriting it would mean
  recursing into that subtree and copying it, which is the cost the tree rule exists to avoid — and
  git ignores a `.gitmodules` outside the root anyway, so it is inert rather than wrong.
- A **relative** `url` (`../lib.git`) resolves against the superproject's own remote. The output's
  remote is not the input's, so a relative url points somewhere else after the merge. Making those
  absolute in the inputs, before merging, is the fix.

### Dissolving a submodule

A superproject and the repository its `vendor/lib` gitlink points at are two inputs of the same
merge often enough to be worth naming. Placed at `vendor/lib`, the second one lands exactly where the
first keeps its gitlink, which is a collision under the rule above — the tool cannot tell from the
paths whether that is the point or an accident.

`--dissolve-submodules` says it is the point, and two things follow:

> At a commit where an input's content occupies a path, the **gitlink** there gives way to that
> input's tree, and any `[submodule]` section whose `path` is that path is left out of the root
> `.gitmodules`.

```
$ git ls-tree -r HEAD                      # without the flag: refused before anything is written
$ git ls-tree -r HEAD                      # with it
100644 blob 7898192…    a.txt
100644 blob 41f2e91…    vendor/lib/src/lib.kt   <- the library's own tree, and its own commits
```

The section has to go with the gitlink because the two describe each other: a `path` naming a
directory git finds no gitlink at is a mapping `git submodule` reports as broken. When that was the
only section, no root `.gitmodules` is written at all — and the containing repository's own copy is
taken out of the tree with it, since leaving it would publish the input's text describing a submodule
the output no longer has.

Both halves are decided per commit rather than per run, for the same reason every other tree question
is: an input that has no content yet occupies nothing, and the gitlink standing in for it is still
the truth at that point of the braid.

What the flag is **not** is a faithful expansion of the submodule. A gitlink names one commit of the
submodule — the one the superproject pinned — and what takes its place is whatever that input had
reached at that point of the braid, which the interleave decides. The output's `vendor/lib` therefore
moves with the braid, not with the pin. That is the reason it is opt-in rather than inferred from the
paths, and the reason a gitlink at a segment *above* a destination stays an error: nothing is placed
at that path, so there is no content that could stand in for the submodule.

---

## Branches

Branches need no special handling, which is worth explaining because it looks like they should.

- Only commits on the braid get reparented.
- Everything else keeps its original parents, and branches are just refs pointing at the recreated
  commits.
- So a side branch forks off wherever its base commit landed — and if that base is on the braid, it
  already carries the accumulated content of every repository.

The consequence is that a branch which exists in **one** input repository still gives you a working
checkout of the whole system:

```
$ git ls-tree esbuild-experiment      # a branch that only ever existed in webui
040000 tree a11ce09…    codegen
040000 tree 7f3d2b8…    backend
040000 tree e90b7a3…    webui
```

`codegen/` and `backend/` are frozen at whatever they were when the branch was cut, and `webui/`
follows the branch. Which is exactly what you want, and it costs no configuration.

### Which refs are carried over

Which refs a run carries over is not only a question about the output's refs: it decides which
commits are read at all. Each input is read from the refs that were selected, so a ref left out
contributes no commits, and the side branch it pointed at is absent from the graph rather than
merely unnamed.

- The selection is a glob over **full** ref names — `refs/heads/*`, `refs/tags/v1.*` — and applies
  to branches and tags alike (`--ref`, repeatable). A short name cannot say whether `v1.0` is a
  branch or a tag, so the patterns are matched against the full name and a `*` spans path
  separators.
- No selection means **every** ref. `-b`/`--branch` is shorthand for one pattern,
  `--ref refs/heads/<name>`; since a branch name cannot contain a `*`, the short form desugars
  exactly — and it therefore selects that branch *and no tags*.
- The resolved mainline is loaded whatever the patterns say, because the braid is built along it.
- Tags are recreated under a prefix, `{repo}/` by default (`--tag-prefix`), so two inputs that both
  tagged `v1.0` do not collide.

`--interleave-ref` uses the same matcher and reads its empty case the other way round: no selection
is every ref, no interleave pattern is none of them. It is also matched against what the selection
already admitted, so widening the interleave cannot widen what is read.

---

## Caveats

Two things limit how literally "the state of the world at this moment" can be read.

### "That instant" is the mainline, not the deployment

- It means *the mainline branches at that instant*, as dated by the ordering timestamp — not what was
  deployed.
- If work is authored long before it is merged, author dates and integration order diverge.
- `--order-by committer` is usually the better choice for "what did the system look like" questions,
  `--order-by author` for "what was being written".

### A merge can carry — and pass on — a repository's future

This one is sharper:

- A merge commit's tree already reflects everything it merged in, including a branch whose last
  commit is timestamped *after* the merge itself.
- Nothing about braiding changes that tree, and every later commit inherits it forward — the ordinary
  accumulation rule, not a choice this tool makes — so those commits show that same "future" content
  too.
- This is a fact about the input history — a branch merged back in later than it was last committed
  to, the everyday case — not something any interleaving of the braid can undo.

The braid itself adds nothing to this. Every braid edge — the artificial one this tool inserts — runs
from a commit back to one **no younger than itself**:

- A braid predecessor from another repository won a direct comparison of timestamps against it.
- A predecessor from the commit's *own* repository is already its first parent, so no edge is added
  there at all.

So suppose `backend`'s `m` merges in a long-lived feature branch whose last commit is timestamped
after `m` itself. Checking out `m` shows `webui/` as of a moment at or before `m`'s own — the braid
places nothing later beside it. What you see from the future is only what `m`'s own tree already
carried, and what any later commit inherits from it.

"The state of the world at this moment" is exact precisely when every commit's timestamp is
consistent with all of its parents', not only its first one.

### Trading the guarantee away on purpose

That guarantee is the default's, and `--interleave-ref` is how you give it up deliberately:

- Name a ref and its commits may delay a mainline merge that merges them in.
- The merge then lands by *their* time rather than by its own — arguably the more honest position for
  a merge whose content reaches later than its own date, and which does let the merge acquire a braid
  predecessor younger than itself.
- Off by default, because a branch nobody considers significant should not get to move where two
  other repositories meet.

[Example 02](examples/02-merge-with-late-branch/README.md) is this whole argument on a five-commit
history you can build and inspect: one input, braided both ways, with the merge landing before the
other repository's last commit by default and after it once every ref is opted in.
