# Using git-timebraid

*What to type, and what the output holds when the run finishes.*

- [Examples](#examples) — whole invocations, before any of the rules behind them.
- [Writing an input](#writing-an-input) — the `<repo>` argument, and the two
  characters its suffix may not hold.
- [Naming and placement](#naming-and-placement) — what an input is called, and where its content
  lands.
- [Taking the layout off a directory tree](#taking-the-layout-off-a-directory-tree) — `--scan`.
- [Dissolving a submodule into its content](#dissolving-a-submodule-into-its-content) —
  `--dissolve-submodules`.
- [Which history is read, and how it interleaves](#which-history-is-read-and-how-it-interleaves) —
  mainlines that do not agree, and narrowing what may weigh on the interleave.
- [What ends up in the output](#what-ends-up-in-the-output) — what the output repository holds when
  the run finishes: its refs, its commits, and the messages on them.
- [What a run prints](#what-a-run-prints) — the headings, the lines a bar leaves behind, and what
  decides whether one is drawn at all.
- [The plan](#the-plan) — what `--plan-out` writes.
- [Options](#options) — every option, as `--help` prints them.

Elsewhere in the doc set:

- [install.md](install.md) — getting a runnable `git-timebraid`, and picking the JVM it runs on.
- [README](../README.md) — what the tool is, and why the braid is shaped the way it is.
- [how-it-works.md](how-it-works.md) — the parent rule, the tree rule, and the caveats that follow
  from them.
- [examples/](examples/README.md) — small histories you can build and walk yourself.

---

## Examples

Merge three local bare clones, each into its own subdirectory:

```bash
git-timebraid -o /tmp/merged \
    --mainline-branch develop \
    ~/repos/backend.git ~/repos/webui.git ~/repos/codegen.git
```

Group the inputs under a layout of your own, two of them sharing `libs/`:

```bash
git-timebraid -o /tmp/merged \
    ~/repos/backend.git::libs/backend \
    ~/repos/codegen.git::libs/codegen \
    ~/repos/webui.git::apps/webui
```

Put the backend at the repository root and place `webui` in `ui/`, keeping its own name (so its
tags stay `webui/v1.2`):

```bash
git-timebraid -o /tmp/merged \
    --root-repo backend --mainline-branch main \
    ~/repos/backend.git ~/repos/webui.git::ui=webui ~/repos/codegen.git
```

Carry over two branches and the tags of one release series, and inspect the plan without writing
the output repository:

```bash
git-timebraid \
    --mainline-branch main \
    -b main -b release/2.2 --ref 'refs/tags/v2.*' \
    --dry-run --plan-out /tmp/plan.txt \
    ~/repos/backend.git ~/repos/webui.git
```

Without that `--ref`, this run would carry no tags at all: naming any ref leaves out the rest.
[Example 09](examples/09-ref-selection/README.md) runs five selections over one pair of repositories
and shows what each output ends up holding, down to the commit that only a tag reaches.

---

## Writing an input

```text
<path-or-url>[::[<subdir>][=<name>]]
```

Everything before the **last `::`** is the location, taken verbatim; everything after it is the
subdirectory and the name. That is the whole rule — nothing is guessed, and an argument that cannot
be read this way is refused rather than quietly reread.

The subdirectory comes first because placing an input is what most arguments do, and the name
follows from it: `::apps/webui` both places the input and calls it `webui`. The `=` is there for
when the two have to differ.

Three rules follow — two about the location, one about what comes after:

- **The location is never escaped**, `=` and `::` included: `~/repos/a=b` is simply a path, and a
  `\` in it is just a backslash.
- **A location that holds a `::` of its own** ends with a bare `::`, which says where it stops:
  `~/repos/odd::name::` is that whole path with nothing said after it. That settles the location,
  not the name: a name derived from a last segment git would not take in a ref is refused, so the
  working spelling here is `~/repos/odd::name::=oddname`. An IPv6 URL needs the bare `::` and
  nothing more — `https://[fe80::1]/repo.git::` derives `repo`, which is a legal name. Every
  refusal that comes out of a suffix suggests the `::`.
- **A subdirectory or a name written after the `::` holds no `:` and no `=`.** Neither is escaped;
  both are refused. That is what guarantees a suffix can never hold a `::` of its own, so the last
  `::` in the argument is always the separator. A name derived from the location may hold a `=`,
  which git takes in a ref.

```bash
~/repos/webui.git                     # subdirectory and name both 'webui'
~/repos/webui.git::apps/webui         # subdirectory 'apps/webui', name 'webui'
~/repos/webui.git::frontend           # subdirectory and name both 'frontend'
~/repos/webui.git::apps/webui=ui      # subdirectory 'apps/webui', name 'ui'
~/repos/webui.git::=ui                # name 'ui', and with no subdirectory that is where it lands
~/repos/a=b/c                         # a location holding a '='
~/repos/odd::name::=oddname           # a location holding a '::', so the name is given
```

Refusing the colon rather than escaping it costs nothing, because a `:` is illegal in a git ref name
and the name becomes a tag prefix. A name that needed one could never have been used.

A **remote input**, one whose location is a URL (`file://` included) or git's scp-like
`user@host:path`, is cloned before anything is read: into `.timebraid-clones/<name>.git`, in the
directory that holds the output, and a later run over the same URL whose output sits in the same
directory refreshes that clone rather than downloading it again. A clone that another URL left under
that name is refused, naming both. A `--dry-run` without `-o` clones into a temporary directory
instead, and removes it when the run ends.

Beyond the `:` and the `=` it may not be written with, a name cannot hold a `/`, nor anything else
git refuses in a ref name.
The `/` is no gap: the name is a directory name for the clone of a remote input and a segment of a
tag name, so one segment is what it means.

## Naming and placement

Each input lands at its own **destination** in the output. By default that is a top-level directory
named after the input, and the input's name defaults to the last segment of its path.

Placement and naming travel together, and can be separated.

**`repo.git::subdir` says where the content lands**, and names the input after it.

- It may be a nested path (`repo.git::libs/backend`), and inputs sharing a prefix share the
  directory for it — so `backend.git::libs/backend webui.git::apps/webui` gives the output a
  `libs/` and an `apps/`. Both are named after the last segment.
- The name is the input's identity: the tag prefix, the branch prefix, the provenance label, the
  commit subject prefix, what `--root-repo` matches, and what has to be unique — it is how two
  inputs whose directories happen to share a name are told apart.
- **`repo.git::subdir=name` sets the two apart**, and `repo.git::=name` names an input without
  placing it. Naming a repository found by [`--scan`](#taking-the-layout-off-a-directory-tree) is
  what the second one is mostly for: the scan already decided where it goes.
- Two inputs may not contain each other — `libs` and `libs/backend` — unless **`--splice`** says so.
- `--splice` never buys a merge of two repositories' files: anything the containing repository
  already holds at the inner destination is a collision, with or without the flag. The one entry
  excepted is a gitlink, under `--dissolve-submodules`.

**One repository may be placed at the root instead**, with `--root-repo <name>`. That is the same
splice, and the one that needs no flag — every other destination is inside it, which is what the
option asked for. Its own entries at a path and the inputs placed inside it end up in one directory.

Why a containment has to be spliced at all, and when the check runs, is worked out under
[nested destinations](how-it-works.md#nested-destinations).

Worked through on repositories you can build and walk:

- [nested destinations and a shared prefix](examples/05-nested-layout/README.md)
- [`--splice`](examples/06-splice/README.md) — which also shows the pair being refused without the
  flag, and the collision the flag does not excuse.

## Taking the layout off a directory tree

`--scan <dir>` reads the layout instead of having it written out: every repository under `<dir>`
becomes an input, placed in the output where it sits on disk. A repository at `<dir>/libs/backend`
lands at `libs/backend`, a bare `<dir>/libs/backend.git` lands there too, and `<dir>` itself lands
at the output root when it is a repository — which is `--root-repo` reached another way, not a
second concept.

Two rules decide what is looked at, and both keep the scan predictable rather than clever:

- **A repository is not descended into.** What is nested inside one — a submodule, a vendored
  checkout, a linked worktree — is its own business, and pulling those in is a decision rather than
  a default. The base directory is the exception; a scan that stopped at it would find nothing else.
- **Dot-names and symlinks are skipped**, so a `.cache` or a mirrored directory is never walked.

The run's own `-o` is never a finding either, however it is spelled, so rerunning into a
`merged.git` beside the inputs does not braid it into itself; an argument naming the output, or an
`-o` naming the `.git` of a working tree the scan finds, is refused.

A `<repo>` argument may be given alongside, and one whose location is a directory the scan found, or
that directory's `.git`, is a **correction to that finding** rather than a second input.

- The name it gives wins, and the subdirectory too when it gives one.
- An argument that gives none leaves the finding where the scan put it.
- That is what settles two findings that derive the same name — `libs/core` and `tools/core` —
  which is refused until one of them is named:

```bash
git-timebraid -o out.git --scan ~/repos ~/repos/tools/core::=tools-core
```

Anything the scan skipped, or a repository from outside the tree entirely, is added the same way:
as an ordinary argument.

[Example 08](examples/08-scan/README.md) scans a tree holding all of these cases at once — a bare
repository, a dot-name, a repository nested inside another, and the two findings that derive the
same name.

## Dissolving a submodule into its content

Where an input lands on exactly the path the repository around it keeps a **gitlink**, that
repository was already saying another repository belongs there — and the input is that repository,
arriving with its history rather than as one pinned sha.

`--dissolve-submodules` lets it take the gitlink's place instead of colliding with it, and drops the
submodule's section from the output's `.gitmodules` so nothing is left claiming a path that now
holds real content.

```bash
git-timebraid -o out.git --root-repo super --dissolve-submodules \
    super.git lib.git::vendor/lib
```

The run names the submodule that gave way, and at how many commits of the output its content stood
where the gitlink had been.
[Example 07](examples/07-dissolve-submodule/README.md) carries this out on a superproject with two
submodules, one dissolved and one left alone, quotes what it printed, and walks the output's trees
from before either gitlink existed to the root `.gitmodules` at the tip.

It is opt-in because the substitution is not a faithful expansion. A gitlink names one commit of the
submodule; what lands in its place is whatever that input had reached at each point of the braid, so
the output's `vendor/lib` moves with the braid rather than with the superproject's pin.

Two things it deliberately does not do:

- A gitlink at a segment *above* a destination stays an error (nothing is placed at that path, so
  there is no content to put in the submodule's stead).
- An ordinary file or directory in the way stays a collision.

Which `[submodule]` sections are dropped, and when no root `.gitmodules` is written at all, is
worked out under [dissolving a submodule](how-it-works.md#dissolving-a-submodule).

## Which history is read, and how it interleaves

`--order-by` picks the timestamp the strands interleave by, the committer's unless it says the
author's; which suits which question is under
["that instant" is the mainline](how-it-works.md#that-instant-is-the-mainline-not-the-deployment).

### Mainlines that do not agree

`--mainline-branch` takes the `<input>::` scope a ref pattern takes ([saying it for one input
only](#saying-it-for-one-input-only)), with a branch's short name rather than a glob. It is how two
inputs that never standardised on one name are merged at all:

```bash
--mainline-branch 'backend::main' --mainline-branch 'webui::master'
--mainline-branch 'backend::main webui::master'
```

An unscoped value is the default for every input that has no scoped one, **and** names the branch
the output carries. With no unscoped value the output takes the branch of the first input — the
first on the command line, or under `--scan` the first the scan found — `main` above, if `backend`
is given first. To choose it outright, give both:

```bash
--mainline-branch trunk --mainline-branch 'backend::main' --mainline-branch 'webui::master'
```

Where nothing is named, the mainline is detected as the first of `main`/`master`/`develop` present
in **every** input still awaiting one. It is not done per input, which would quietly pick `main` for
one repository and `master` for another wherever both exist.

### Narrowing what may weigh

`--interleave-ref` only adds, so a [subtraction](#taking-refs-back-out) is how a run says *broadly,
except these*:

```bash
--interleave-ref 'refs/heads/* ^refs/heads/main'
```

That one is worth knowing by name: *every side branch*. A pattern reaching the mainline tips opts
them in when the selection carries them, as it does by default, and the scope is the ancestry of
everything opted in — so a mainline tip reaches every commit that mainline ever merged, which is
close to the widest scope there is. Subtracting the mainline refs leaves the side branches. An input
whose mainline is named otherwise needs its own subtraction:
`'refs/heads/* ^refs/heads/main webui::^refs/heads/master'`.

A bare star is not the same thing. It opts every tag in as well, and a tag on a mainline reaches
what that mainline had merged by then, so `'* ^refs/heads/main'` still brings a merged branch into
scope through any release tag made after the merge.

**It subtracts the ref, not the commits behind it.** They stay in scope if some other opted-in ref
reaches them, so subtracting a branch a mainline already merged does nothing at all under a star.

That is a limit worth reading twice, because it is not an implementation shortcut: a branch merged
into a mainline **is** that mainline's ancestry, so asking for it to be out of scope while the
mainline is in scope asks for a contradiction. What subtracts is a pattern that leaves the mainline
out — the example above, or a narrow one over a release series.

---

## What ends up in the output

### Branches

**All of them**, recreated at the corresponding new commits (narrow with `-b` or `--ref`):

- The mainline branch collapses into one: every input contributed its own to the same braid, so the
  output has a single branch at the braid's tip, named by the unscoped `--mainline-branch` or else
  after the first input's mainline ([mainlines that do not agree](#mainlines-that-do-not-agree)).
- Any other branch is prefixed with the repository name (`wip` from `webui` becomes `webui/wip`), so
  branches from different repositories cannot collide. The prefix is `--branch-prefix`.

Prefixing every other branch is a change in 0.2.0. The qualifier used to go on a branch only where
two inputs had used the name, which made the name depend on what the other inputs called theirs:
adding an input that had a branch of the same name renamed this one's, out of an argument that says
nothing about naming.

Why a side branch needs no special handling, and why a branch from one input still gives you a
checkout of the whole system, is under [branches](how-it-works.md#branches).

### Tags

**All of them**, prefixed the same way (`v1.2` from `webui` becomes `webui/v1.2`). The prefix is
`--tag-prefix`.

An annotated tag stays annotated, keeping its tagger and its message — dropping them would lose text
no other object holds, and a merge should not do that quietly.

`--lightweight-tags` asks for exactly that loss: every tag becomes a plain ref at the same commit
and no tag object is written. It is the case where a run wants the ref names without carrying the
name, address and date of whoever cut each of forty releases.

### Turning the prefix off

Both prefixes are templates, and emptying one asks for the plain names:

```bash
--branch-prefix '' --tag-prefix ''
```

What the prefix buys is that an output ref name follows from the input it came from and nothing
else. Without it two inputs can meet on one name, and a run that would write two different commits
to one ref is **refused** rather than resolved — naming both inputs and the ref they collided on.
Even with it, an input can meet the braid's own branch, which takes no prefix: under the default,
input `release`'s branch `x` becomes `release/x`, which is the output's branch where the mainline is
called `release/x`. That is refused the same way, naming the braid.

### Commit notes

Notes are not something a selection can name, a pattern under `refs/notes/` being refused, and they
are not carried over unless a run asks:

```bash
--notes
```

Every `refs/notes/*` of every input is then read and **rekeyed**: a note is filed under the sha of
the object it annotates, a merge gives every commit a new sha, so a note carried over unchanged
would be attached to nothing. This is the provenance trailer's move from the other side — the
trailer records where a commit came from, the rekeying moves what was said about it.

- Notes refs are prefixed like everything else, `--notes-prefix`, default `{repo}/`. An input with
  notes usually has `refs/notes/commits`, which `git notes` writes by default, so without
  the qualifier two inputs meeting on that one name is refused, naming both, rather than resolved.
- A note on an object this run did not write — one on a commit an unselected ref reached, or on a
  blob or a tree — is **skipped**, and the run says how many, in the closing report as well, which
  `-q` still prints (on its `refs:` line, or on a line of its own on a dry run).
- Only the notes themselves come over, not the history of the notes ref. Every tree behind it is
  keyed by shas the output never wrote under those names, so carrying that chain would carry notes
  attached to nothing. The output's notes ref is one commit, keeping the input's author, committer
  and message.
- Read them the way you read any notes ref: `git log --notes=backend/commits`.

`--ref` does not reach here. A pattern aimed at `refs/notes/` is **refused**, naming this flag: a
note contributes no commit and reaches no ancestry, so it cannot be part of the selection that
decides what is read.

### Writing a ref pattern

A ref pattern is `[<input>::][^]<refspec>`: an optional scope, ended by `::`, the separator a
[`<repo>`](#writing-an-input) puts between its location and its suffix, and after it git's refspec.
The first `::` is the separator, since an input's name holds no `:`. What the scope does is under
[saying it for one input only](#saying-it-for-one-input-only).

| written                           | input    | pattern            | destination  |
|-----------------------------------|----------|--------------------|--------------|
| `refs/heads/*`                    | every    | `refs/heads/*`     | its own      |
| `backend::refs/heads/*`           | backend  | `refs/heads/*`     | its own      |
| `backend::refs/heads/*:refs/tags/`| backend  | `refs/heads/*`     | `refs/tags/` |
| `refs/heads/*:refs/tags/`         | every    | `refs/heads/*`     | `refs/tags/` |

**A value git takes as a refspec means the same here.** The differences are few, and each is on
purpose: a destination may name a namespace, `refs/tags/`, left to that namespace's prefix, and may
hold `{repo}`; a pattern with stars may go without a destination, which `git fetch` allows only in a
negative refspec, or name one ref as its destination; and there is no `+`, no empty pattern or
destination, and no short name — a pattern matches full ref names. git resolves a single short name,
tags before branches, and never a short pattern; `-b` is the short form here, and says which of the
two it means.

What may stand in the destination is under [saying where a ref lands](#saying-where-a-ref-lands).

**One quoted argument may hold several values, separated by spaces**: a space cannot occur in a ref
name, as a `:` and a `^` cannot, so the split can never cut a pattern in half. These two are the
same run:

```bash
--ref 'backend::refs/heads/main' --ref 'webui::refs/heads/release/*'
--ref 'backend::refs/heads/main webui::refs/heads/release/*'
```

It holds for `-b`, `--label-ref`, `--interleave-ref` and `--mainline-branch` alike.
What does *not* work is leaving the quotes off: an option takes one argument, and the rest would be
read as input repositories.

### Choosing which refs are carried over

`-b` and `--ref` are one selection rather than two: `-b main` *is* `--ref refs/heads/main`, and
naming any ref at all, other than with a `^`, leaves out every ref not named. The rest of the
pattern grammar goes around the short name rather than inside it, so `-b backend::^wip` is
`--ref backend::^refs/heads/wip`.

So a run narrowed to a branch carries no tags unless it says so — write both halves out to keep
them:

```bash
--ref 'refs/heads/main' --ref 'refs/tags/*'
```

- `*` is the only metacharacter and it spans path separators, so `refs/heads/release/*` reaches a
  nested branch name however deep.
- Matching is against the *full* ref name because a short one cannot say whether `v1.0` is a branch
  or a tag.
- The mainline is loaded whatever the patterns say — the braid is built along it — and the output's
  mainline branch comes from the braid's tip.

- A pattern may also **subtract** rather than select, written with a leading `^` — see [taking refs
  back out](#taking-refs-back-out).

The selection also decides which commits are read at all, and `--interleave-ref` reads its empty
case the other way round. Both are worked out under
[which refs are carried over](how-it-works.md#which-refs-are-carried-over).

### Naming a ref without letting it weigh

`--label-ref` recreates every ref it matches whose target the run already holds, and does nothing
else. It reads nothing extra, and it can never delay a merge.

That is what makes *these branches, and the tags that sit on them* expressible:

```bash
--ref 'refs/heads/main' --label-ref 'refs/tags/*'
```

Writing the second half as `--ref 'refs/tags/*'` instead looks equivalent and is not. It carries
every tag in the repository, including tags whose only path to the root runs through a branch the
selection left out — so the commits only those tags reach come back in, which is what naming the
branch was meant to keep out.

- A match whose target was not loaded is skipped rather than refused, and the run says how many
  were, in the closing report as well, which `-q` still prints (on its `refs:` line, or on a line
  of its own on a dry run): a label names what is already there, so asking for a superset of it is
  ordinary use.
- A ref matched by both flags is selected. That is the wider of the two meanings — it is read from
  as well as recreated.
- An input's mainline is never a label: the braid's own branch stands for it.
- **Adding a label cannot change a single commit the run writes.** A selection can, which is why
  the two are separate flags; [which refs are carried
  over](how-it-works.md#which-refs-are-carried-over) works out how.

### Saying where a ref lands

A ref pattern is a git **refspec**, and its right half is the destination — what git writes after
the `:` in `refs/heads/*:refs/remotes/origin/*`, where the left half says what is read and the right
half what it is called:

```bash
--ref 'legacy::refs/heads/*:refs/tags/'
--ref 'backend::refs/changes/*:refs/changes/{repo}/*'
--ref 'clone::refs/remotes/origin/*:refs/heads/{repo}/*'
```

The prefix rules (`--branch-prefix`, `--tag-prefix`) are the default naming. **A destination
overrides exactly the part of the name it writes out** — nothing, the namespace, or all of it:

| destination           | `refs/heads/wip` from `backend` becomes | who named it                               |
|-----------------------|-----------------------------------------|--------------------------------------------|
| *(none)*              | `refs/heads/backend/wip`                | the prefix rule                            |
| `refs/tags/`          | `refs/tags/backend/wip`                 | you the namespace, `--tag-prefix` the rest |
| `refs/tags/archive-*` | `refs/tags/archive-wip`                 | you, and no prefix applies                 |

- A `*` in the destination is **substituted** with whatever the pattern's own `*` matched, so the
  pattern needs exactly one. It need not be a whole path segment:
  `refs/legacy/*:refs/archived_*` turns `refs/legacy/alpha` into `refs/archived_alpha`. As in a
  git refspec, what the `*` matched may hold a slash, and carries it into the destination.
- A destination **ending in `/` with no `*`** is a namespace, handed back to that namespace's prefix
  rule. Only `refs/heads/` and `refs/tags/` have one, so only those two can be written that way.
- `{repo}` is substituted, which is what makes an unscoped pattern safe: a destination naming a ref
  outright, with neither `{repo}` nor a `*`, gives every input's match the same name, and the run
  would be refused for the collision.
- Where you spell the name out, **unique names are yours to arrange**. The collision check still
  refuses two inputs meeting on one ref, naming both, and under `--keep-remotes` it refuses any
  destination under an input's `refs/remotes/<name>/`, where the mirrors are, meeting one or not.
  It refuses any under `refs/timebraid-fetch/` as well, where the run parks the refs it fetches and
  which it empties once the braid is written.
- Where two patterns match one ref, **a scoped pattern beats an unscoped one**, which is what makes
  *this input's branches as tags, everything else as it stands* writable. Between two of the same
  scope the one written first decides, however specific the other is, and a `-b` counts as written
  before every `--ref`.
- The mainline is never redirected. It is loaded whatever the patterns say, so a pattern reaching it
  reaches a ref the run never asked to carry over.

That is how a dead branch is archived — kept for the record, not kept as a branch, and without
dropping the commits only it reaches:

```bash
--ref 'legacy::refs/heads/*:refs/tags/'
```

A branch has no annotation, so it arrives as a **lightweight** tag. Going the other way, an
annotated tag written into `refs/heads/` loses its annotation: a branch points at a commit and
nothing else.

### Namespaces beyond branches and tags

`refs/heads/` and `refs/tags/` are read by default. Any other namespace — a Gerrit `refs/changes/`,
a forge's `refs/pull/`, the branches an ordinary clone keeps under `refs/remotes/origin/` — is read
**when a pattern names it**, and then the destination is required:

```bash
--ref 'refs/changes/*:refs/changes/{repo}/*'
```

- A bare `*` and a pattern under `refs/` still mean *every branch and every tag*. Naming no foreign
  namespace reads none, so nothing a forge or a clone left lying about arrives unasked.
- The destination is required there because nothing else could name the result: `--branch-prefix`
  and `--tag-prefix` speak for their own two namespaces and no third rule exists.
- A symbolic ref is skipped. `refs/remotes/origin/HEAD` points at another ref rather than being one,
  and carrying it over would write the same commit twice.
- `refs/notes/` is refused whatever the destination. A notes ref points at a tree keyed by shas
  rather than at history anyone braids — see [commit notes](#commit-notes).

**An ordinary clone is the case worth knowing about.** `git clone` keeps its branches under
`refs/remotes/origin/`, and only the checked-out one under `refs/heads/` as well, so a clone given
as an input contributes that one branch unless you ask for the rest. Naming refs for it narrows it
as it narrows any input, its tags included, so ask for those too:

```bash
--ref 'clone::refs/remotes/origin/*:refs/heads/{repo}/* clone::refs/tags/*'
```

The checked-out branch then arrives twice: as the input's mainline, in the braid, and as
`refs/heads/<repo>/main` from its copy under `refs/remotes/origin/`.

### Saying it for one input only

Every ref pattern takes an optional `<input>::` scope, naming which input it speaks for. Without one
it speaks for all of them, so nothing written before this existed changes meaning:

```bash
--ref 'backend::refs/heads/main' \
--ref 'webui::refs/heads/release/*' \
--ref 'refs/tags/v*'
```

An `<input>::` naming something that is not an input is refused, so a typo is not a pattern that
quietly matches nothing; so is an empty one, `::refs/heads/main`, the unscoped form already saying
it.

**The empty case stays per input.** An input no pattern names keeps that option's default — every
branch and tag for `--ref`, none for `--label-ref` and `--interleave-ref`. So patterns scoped to
`backend` and `webui` alone narrow those two while a third input still carries everything, which
is *naming any ref leaves out every ref not named* read one input at a time; an unscoped one, like
`refs/tags/v*` above, names every input.

Narrowing one input leaves every other input's ref **names** alone: the qualifier goes on wherever a
pattern has not spelled a destination out, so narrowing decides which refs exist and not what the
surviving ones are called. That was not true before 0.2.0 — see [branches](#branches).

### Taking refs back out

A `^` in front of the pattern **subtracts** instead of selecting. It is git's own spelling for a
negative refspec, unchanged since git 2.29:

```bash
git fetch origin 'refs/heads/*:refs/remotes/origin/*' '^refs/heads/wip/*'
```

```bash
--ref '^refs/heads/wip/*'
```

It is the same mark in the same place, in front of the refspec, and it is decidable rather than a
convention: `git check-ref-format` refuses a `^` anywhere in a ref name, exactly as it refuses the
`:` a refspec is divided on.

**The mark goes on the refspec, not on the value.** `backend::^refs/heads/wip` subtracts in one
input; `^backend::refs/heads/wip` would read as *not backend*, which is a meaning this never has, so
it is refused and says where the `^` belongs.

**A subtraction carries no destination.** Nothing lands from it, so there is nothing for a
destination to [name](#saying-where-a-ref-lands) — and git refuses the same spelling outright,
`^refs/heads/x:refs/remotes/y` being an invalid refspec rather than a negation with a destination.

#### What a run holding only subtractions means

A subtraction is applied after the patterns that select, over whatever they left — and with no
pattern selecting, that is [the option's own empty case](#saying-it-for-one-input-only), per input:

| option             | empty case           | `^` alone                                          |
|--------------------|----------------------|----------------------------------------------------|
| `--ref`            | every branch and tag | every branch and tag except those — the useful one |
| `--label-ref`      | no ref               | refused                                            |
| `--interleave-ref` | no ref               | refused                                            |

So dropping two branches out of forty is two patterns rather than thirty-eight, and a forty-first
branch needs no edit. Where the empty case is *no ref*, there is nothing to take back out, and a run
holding nothing but subtractions is refused rather than quietly resolving to nothing.

**This is where subtractions alone part company with git.** Git's command line drops the
configured refspec as soon as it names one, so `git fetch origin '^refs/heads/wip/*'` fetches
nothing at all — silently. The rule is the same either way, a subtraction taking refs out of what
was selected; only the empty case underneath it differs.

**It subtracts a ref, not the commits behind it.** A commit two refs name is kept by whichever of
them survives. That is not a shortcut — see [narrowing what may weigh](#narrowing-what-may-weigh),
where the limit bites hardest.

### The inputs' original commits

They are in the output too, with their own shas intact, next to the rewritten ones.

- The output is filled by fetching into it everything the refs that were read reach — that is what
  puts the inputs' trees and blobs there, which the braid then reuses — and a fetch cannot leave the
  commits out.
- Nothing points at them by default, so they are invisible to `git log`, and `git gc --prune=now`
  reclaims them.
- `--keep-remotes` points `refs/remotes/<name>/*` at every ref the run carried over instead, and at
  each input's mainline whether the selection took it or not — a branch at its own name, everything
  else under the tail of its namespace, so a tag lands under `tags/` — which reaches all of them, so
  the originals stay one `git log` away. Notes are the exception: they are written under
  `refs/notes/`, and a notes ref names a notes commit, which is not in the braid and so has no
  original to point at.
- Each remote is given the fetch refspec `+refs/heads/*:refs/remotes/<name>/*`, so a pruning fetch
  — `git fetch --prune <repo>`, or any fetch under `fetch.prune` — deletes every mirror under
  `refs/remotes/<name>/` that names no branch of the input: the tag mirrors among them, and what
  only they reached is unreferenced again. It would delete a destination written there too, which is
  why one is refused under `--keep-remotes`.
- Either way the fetch covers the refs that were read, so narrowing the selection narrows what
  arrives: a commit only an unselected ref could reach is not merely unreferenced in the output,
  its objects are not there.

### Commit subjects

**Every one carries the repository's name**, so `fix the date picker` from `webui` reads
`webui: fix the date picker`.

The prefix is `--subject-prefix`, substituting `{repo}` and `{subdir}` — the name and the
destination. The default is `{repo}: ` and not `{subdir}: ` deliberately: a name is one segment,
while a destination can be arbitrarily deep. An input placed at the root has no destination, and
`{subdir}` gives its name there too.
[Example 05](examples/05-nested-layout/README.md) places `backend` at `libs/backend`, and its
subjects still read `backend: `.

### The provenance trailer

On every commit message, unless `--no-provenance` turns it off:

```text
webui: fix the date picker on the summary page

[timebraid: repo="webui" commit=5c1a9f2… parents=b2c91f4…,a0d3e11…]
```

This is what makes the braid's promise checkable rather than merely claimed, the promise that
[every original edge survives](how-it-works.md#the-parent-rule): the original identity and the
original parents of every commit are recorded, so a script can verify that no edge went missing.

`--provenance-trailer` sets the line — the one above is its default — substituting `{repo}`,
`{commit}` and `{parents}`. A template that leaves out `{commit}` or `{parents}` keeps the
trailer and gives up that check, since it is those two that a script reads.

---

## What a run prints

Two streams, and the split is the point: **stdout carries the result** — the plan summary, and
nothing else — so `git-timebraid --dry-run … > summary.txt` gives a file holding the plan summary
and a terminal still showing what happened. **stderr carries the narration**, which is everything
below.

A run is reported one phase at a time. Each opens with a ruled heading saying what is about to
happen, and closes with indented lines saying what came of it:

```text
-- fetching the inputs into the output ----------------------------------------
  [backend] 214 refs
  [backend] remote: Counting objects, 2.5s
  [backend] remote: Finding sources, 64536 in 0.3s
  [backend] remote: Getting sizes, 25821 in 0.1s
  [backend] Receiving objects, 64536 in 0.9s
  [backend] Resolving deltas, 31954 in 1.2s

-- writing the braid ----------------------------------------------------------
  commits written, 6732 in 2.8s
  publishing the refs, 0.8s
  dropping the refs the transfer parked, 0.4s
```

The steps a transfer reports are git's and JGit's own, so which of them appear is theirs to decide
and not this program's.

An indented line is what a bar leaves behind: what it was, how far it got, and how long that took.
The count is what was *reached*, so a phase that failed half way through says so rather than
reporting the total it never made. A `[name]` at the front says which input it was for.

While a phase runs it draws a bar over that line — how far, how fast, how long is left — or a
spinner where there is nothing to count, as when a pack is being flushed. The bar goes when the
phase ends and the line is what stays.

Nothing is drawn unless stderr is a terminal somebody is watching. Redirected output — a log, a CI
transcript, a pipe — gets the same headings and the same lines with the animation left out, and not
a single carriage return.

A heading is ruled to the width of the terminal, or to `COLUMNS` where that is set — the same
variable that lays `--help` out, so it decides how wide a merge prints and not only the help.
Redirected output without `COLUMNS` has no width to ask for and gets a fixed one.

Once the last phase is done, the closing report says what the run came to: the output's mainline,
what was fetched into it, what was written on top, and the refs it ends with. On stderr:

```text
mainline branch: main
fetched 3 refs from 2 repositories into out.git
wrote 6 commits and 6 trees on top
refs: 2 branches, 0 tags, HEAD -> main
```

A splice `--splice` enabled and a dissolved submodule each add a line under the first, and
`--plan-out` one saying where the plan went. An option that carries refs of a kind of its own adds
their count to the `refs:` line, as `--keep-remotes` does its remote-tracking refs. A dry run
fetches and writes nothing, so its report has no `fetched`, `wrote` or `refs:` line. The plan
summary printed in the middle of it goes to stdout.

Three options decide whether anything is drawn, rather than leaving it to stderr:

| option          | effect                                                                  |
|-----------------|-------------------------------------------------------------------------|
| `--progress`    | draw even when stderr is not a terminal, for watching a log as it fills |
| `--no-progress` | draw nothing even when it is                                            |
| `-q`, `--quiet` | say nothing at all bar the closing report, animation included           |

`-v`/`--verbose` adds a line under each phase for every `git` command the run shells out to, and the
equivalent of the transfer and of every ref written. See [Options](#options) below.

### The bar falls back to ASCII on a console that cannot encode it

Everything this program writes itself is ASCII, for a reason that is worth knowing here: a JVM on
Windows encodes a console in a code page which lacks most of what is outside it, and an encoder
substitutes a `?` for a character its charset has no room for.

The bar and the spinner are the exception, being drawn by a library rather than written here. So
each is checked against the charset stderr reports, and each falls back to ASCII — `#` and `>` for
the bar, `|/-\` for the spinner — when that charset cannot carry what it would otherwise use.

Separately, because the answers differ. A cp932 console, which is the default on a Japanese Windows,
carries the character the bar is drawn with and has no braille at all, so one answer for both would
either strand the spinner or give up a bar that would have drawn.

Two options override that, for a console the check reads wrong in either direction:

| option       | effect                                       |
|--------------|----------------------------------------------|
| `--ascii`    | draw with ASCII whatever the console reports |
| `--no-ascii` | draw with the full set whatever it reports   |

Colour is not part of this and does not change: a console short of characters is not short of
colour.

## The plan

Every run prints the plan's summary on stdout, `--dry-run` included: where each input's content
lands, how many commits there are and how many of them are on the braid, and how many commits end up
with each number of parents. `--plan-out <path>` writes that summary to a file, followed by one line
per commit in the order the commits are written:

```text
000003 * backend/d83bd47… @1700108000 parents=[webui/08cdbc9…, backend/c28627b…] content=[…]
```

- Its position in that order, and `*` where the commit is on the braid.
- The input it comes from and its original sha, which is how every commit is named on the line.
- Its ordering timestamp, in seconds since the epoch: the committer date, or the author date under
  `--order-by author`.
- Its parents in the output, a braided edge first.
- For each input with content at that point, the original commit whose tree it carries there.

The file is deterministic, so two runs that should plan the same thing can be diffed.

---

## Options

This block is `git-timebraid --help` as the program prints it, generated from the built jar by
`.github/scripts/check-help.py --write` rather than written out here, and CI fails when it falls
behind the tool.

It is rendered at 100 columns, the width this page is written to. What you see is laid out to your
own terminal instead, and `COLUMNS` overrides that.

Each entry gives what the option does on its first line, then one qualifier or default per line
after it — enough to recognise an option you already know exists. Where an entry is not all there
is to an option, what it *means* is a section above it on this page, or is in
[how-it-works.md](how-it-works.md):
[which timestamp to order by](how-it-works.md#that-instant-is-the-mainline-not-the-deployment) for
`--order-by`, and for `--interleave-ref` the
[guarantee it gives up](how-it-works.md#trading-the-guarantee-away-on-purpose).

Syntax that several options in a group share is stated once, in the paragraph under that group's
heading in the list below, rather than in every entry that takes it. `<ref-pattern>` is defined
there and then only named.

<!-- BEGIN --help -->
```text
Usage: git-timebraid [<options>] [<repo>]...

  Merge several independent git repositories into one, braided together along the time axis.

  Each <repo> is written <path-or-url>[::[<subdir>][=<name>]].

Where the result is written:
  -o, --output=<path>  Output repository.
                       Must not exist, or must be an empty directory; --force also takes a non-empty
                       one.
  --force              Write into a non-empty output directory instead of refusing it.
                       Whatever it already holds may be written over.

Finding the inputs, and placing their content:
  --scan=<path>          Take the layout from this directory.
                         Every repository under it becomes an input, placed in the output where it
                         sits on disk.
  --root-repo=<text>     Name of the repository whose content lands at the output root.
  --splice               Allow one input's subdirectory to lie inside another's, splicing the two
                         into one directory.
                         Without it, such a pair is refused.
  --dissolve-submodules  Where an input lands exactly on a gitlink, replace that submodule with the
                         input's own content.
                         Its .gitmodules section is dropped with it.

Which history is read, and how it interleaves:
  --mainline-branch=<branch>     Branch treated as the mainline in every input (repeatable).
                                 Prefix with <input>:: to give one input its own; one quoted
                                 argument may hold several, separated by spaces.
                                 An unscoped value covers the rest and names the output's branch.
                                 Default: the first of main/master/develop present in all.
  --order-by=(author|committer)  Timestamp used to interleave the strands.
                                 Default: committer.

Which refs a pattern speaks for:

  A <ref-pattern> is [<input>::][^]<refspec>, matched against full ref names. <input>:: narrows it
  to one input, and a ^ in front subtracts instead of selecting. With nothing selected, -b and --ref
  subtract from every branch and tag, while --label-ref and --interleave-ref, which take no ref
  unless asked, refuse a ^ alone.

  The <refspec> is git's, <glob>[:<destination>]: -b, --ref and --label-ref take the destination,
  saying where the matches land, as 'refs/heads/*:refs/tags/' does.

  Every option here is repeatable, and one quoted argument may hold several patterns, separated by
  spaces.

  -b, --branch=<branch>           Carry over these branches, by short name, or with ^ leave them
                                  out.
                                  Shorthand for --ref refs/heads/<branch>, so naming one without a ^
                                  leaves out every ref not named, tags included.
  --ref=<ref-pattern>             Carry over the refs matching this pattern, branches and tags
                                  alike, or with ^ leave them out.
                                  Default: every branch and tag, each in the namespace it came from.
  --label-ref=<ref-pattern>       Also recreate the refs matching this pattern whose target the run
                                  already holds.
                                  Adding one cannot change a commit the run writes; a selection can.
                                  Default: none.
  --interleave-ref=<ref-pattern>  Let this ref's commits delay a mainline merge that merges them in.
                                  'refs/heads/* ^refs/heads/main' is every side branch; a bare '*'
                                  opts in every tag too.
                                  Default: none.

What the output repository holds:

  --tag-prefix, --branch-prefix and --notes-prefix qualify a ref name of the output: {repo} is
  substituted, an empty value qualifies nothing, and two inputs then meeting on one name is refused
  rather than resolved.

  --bare / --no-bare              Write a bare output repository.
                                  --no-bare checks out a working tree instead.
                                  Default: bare.
  --keep-remotes                  Add each input as a remote.
                                  Every ref it carried over lands under refs/remotes/<name>/*, at
                                  the original commits, and so does each input's mainline whether
                                  the selection took it or not. Notes are written under refs/notes/
                                  and are not mirrored.
  --tag-prefix=<text>             Prefix prepended to every recreated tag.
                                  Default: "{repo}/"
  --branch-prefix=<text>          Prefix prepended to every recreated branch.
                                  Default: "{repo}/"
  --subject-prefix=<text>         Prefix prepended to every commit subject.
                                  {repo} and {subdir} are substituted.
                                  Default: "{repo}: "
  --notes                         Carry over every input's refs/notes/, rekeyed onto the commits
                                  this run writes.
                                  Default: notes are not read.
  --notes-prefix=<text>           Prefix prepended to every recreated notes ref, below refs/notes/.
                                  Default: "{repo}/"
  --lightweight-tags              Recreate every annotated tag as a lightweight one, dropping its
                                  tagger, date and message.
                                  Default: an annotated tag stays annotated.
  --provenance / --no-provenance  Record each commit's original sha and parents in a trailer.
                                  Default: on.
  --provenance-trailer=<text>     The trailer --provenance writes, as its own paragraph.
                                  {repo}, {commit} and {parents} are substituted.
                                  Default: "[timebraid: repo="{repo}" commit={commit}
                                  parents={parents}]"

Inspecting a run:
  --dry-run          Compute and summarize the plan, write no output.
  --plan-out=<path>  Dump the deterministic plan as text to this file.
  -q, --quiet        Say nothing but the closing report and any error.
  -v, --verbose      Print the git command behind each step.
                     Every git command it shells out to, and the equivalent of the transfer and of
                     every ref written.
                     Writing the commits is not one command; --plan-out dumps that.
  --progress         Draw progress even when stderr is not a terminal.
                     For watching a log of a run that is taking too long.
  --no-progress      Draw no progress even when stderr is a terminal.
                     Default: drawn when stderr is a terminal, and not otherwise.
  --ascii            Draw progress with ASCII characters only.
                     For a console that shows the bar as question marks.
  --no-ascii         Draw progress with the full character set.
                     Default: whichever of the two the console can encode.

Options:
  --version   Show the version and exit
  -h, --help  Show this message and exit

Arguments:
  <repo>  <path-or-url>[::[<subdir>][=<name>]]

          Everything before the last '::' is the location, used verbatim -- never escaped.
          Append a bare '::' when the location itself holds one, and '=<name>' too when its last
          segment cannot be a ref name.

          <subdir> is where its content lands, and may be nested (::libs/backend).
          Defaults to <name>.

          <name> is the repository's identity: the tag prefix, the branch prefix, the provenance
          label, and what --root-repo matches.
          Defaults to the last segment of <subdir>, or of the location.
          Neither may be written with a ':' or a '='.

More on each option, and what the output holds:
https://github.com/loplex/git-timebraid/blob/main/doc/usage.md
```
<!-- END --help -->
