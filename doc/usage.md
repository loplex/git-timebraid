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
- [What ends up in the output](#what-ends-up-in-the-output) — what the output repository holds when
  the run finishes: its refs, its commits, and the messages on them.
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
[Example 09](examples/09-ref-selection/README.md) runs four selections over one pair of repositories
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
- The name is the input's identity: the tag prefix, the provenance label, the commit subject prefix,
  what `--root-repo` matches, and what has to be unique — it is how two inputs whose directories
  happen to share a name are told apart.
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

---

## What ends up in the output

### Branches

**All of them**, recreated at the corresponding new commits (narrow with `-b` or `--ref`):

- The mainline branch collapses into one: every input contributed its own to the same braid, so the
  output has a single branch of that name, at the braid's tip.
- Any other branch keeps its own name — unless two inputs happen to have used that name, in which
  case both are qualified as `<repo>/<branch>`. The qualifier is `--branch-prefix`, and a branch
  only one input has never sees it.

Why a side branch needs no special handling, and why a branch from one input still gives you a
checkout of the whole system, is under [branches](how-it-works.md#branches).

### Tags

**All of them**, prefixed with the repository name by default (`v1.2` from `webui` becomes
`webui/v1.2`), so tags from different repositories cannot collide. The prefix is `--tag-prefix`.

An annotated tag stays annotated, keeping its tagger and its message.

### Choosing which refs are carried over

`-b` and `--ref` are one selection rather than two: `-b main` *is* `--ref refs/heads/main`, and
naming any ref at all leaves out every ref not named.

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
  branch of that name comes from the braid's tip.

The selection also decides which commits are read at all, and `--interleave-ref` reads its empty
case the other way round. Both are worked out under
[which refs are carried over](how-it-works.md#which-refs-are-carried-over).

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
  the originals stay one `git log` away.
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
  --mainline-branch=<text>       Branch treated as the mainline in every input.
                                 Default: the first of main/master/develop present in all.
  --order-by=(author|committer)  Timestamp used to interleave the strands.
                                 Default: committer.
  -b, --branch=<text>            Carry over only these branches, by short name (repeatable).
                                 Shorthand for --ref refs/heads/<name>, so naming one leaves out
                                 every ref not named, tags included.
  --ref=<text>                   Carry over only the refs matching this glob, branches and tags
                                 alike (repeatable).
                                 Patterns are matched against full ref names.
                                 Default: every ref.
  --interleave-ref=<text>        Let this ref's commits delay a mainline merge that merges them in
                                 (repeatable).
                                 Default: none.

What the output repository holds:
  --bare / --no-bare              Write a bare output repository.
                                  --no-bare checks out a working tree instead.
                                  Default: bare.
  --keep-remotes                  Add each input as a remote.
                                  Every ref it carried over lands under refs/remotes/<name>/*, at
                                  the original commits, and so does each input's mainline whether
                                  the selection took it or not.
  --tag-prefix=<text>             Prefix prepended to every recreated tag.
                                  {repo} is substituted.
                                  Default: "{repo}/"
  --branch-prefix=<text>          Prefix prepended to a branch two inputs both have.
                                  A branch only one of them has keeps its own name.
                                  {repo} is substituted.
                                  Default: "{repo}/"
  --subject-prefix=<text>         Prefix prepended to every commit subject.
                                  {repo} and {subdir} are substituted.
                                  Default: "{repo}: "
  --provenance / --no-provenance  Record each commit's original sha and parents in a trailer.
                                  Default: on.
  --provenance-trailer=<text>     The trailer --provenance writes, as its own paragraph.
                                  {repo}, {commit} and {parents} are substituted.
                                  Dropping {commit} or {parents} gives up what makes the output
                                  checkable.
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

Options:
  --version   Show the version and exit
  -h, --help  Show this message and exit

Arguments:
  <repo>  Everything before the last '::' is the location, used verbatim -- never escaped.
          Append a bare '::' when the location itself holds one, and '=<name>' too when its last
          segment cannot be a ref name.

          <subdir> is where its content lands, and may be nested (::libs/backend).
          Defaults to <name>.

          <name> is the repository's identity: the tag prefix, the provenance label, and what
          --root-repo matches.
          Defaults to the last segment of <subdir>, or of the location.
          Neither may be written with a ':' or a '='.

More on each option, and what the output holds:
https://github.com/loplex/git-timebraid/blob/main/doc/usage.md
```
<!-- END --help -->
