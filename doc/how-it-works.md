# How the braid is built

git-timebraid recreates every commit of every input repository in one output repository, each input
under its own subdirectory, and adds artificial parent edges that chain commits *across* repositories
in chronological order. The resulting first-parent chain is called **the braid**.

This document is the specification of that construction: which parents each commit ends up with,
which tree, what happens to branches, and where the "state of the world at that moment" guarantee
stops holding. For what the tool is for and how to run it, see the [README](../README.md).

Vocabulary used throughout:

- **input repository** — one of the repositories being merged; contributes one subdirectory to the
  output
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

> **tree'(c)** = the tree of `parents'(c)[0]`, with the entry for `c`'s own subdirectory replaced by
> `c`'s original tree.

For the very first commit on the braid there is no parent, so the tree is just that one subdirectory.

Because the first parent is the time predecessor, the map of *subdirectory → content* accumulates as
you walk forward:

- Each subdirectory holds whatever its repository last committed at or before this point.
- A repository that did not exist yet simply is not there.

This is the mechanism behind the whole promise: "the state of every repository at that moment" is not
computed on demand, it is simply what the commit's tree contains.

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
