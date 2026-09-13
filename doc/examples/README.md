# Worked examples

Worked examples for [how-it-works.md](../how-it-works.md), which states the rules these demonstrate.

Real, inspectable git repositories, in two families that answer two different questions:

- **How the braid is built** (01–04) — in what order the commits of several repositories end up on
  the braid, and the one case where interleaving over the mainline chains alone gives a different —
  better — answer than interleaving over the whole graph.
- **What ends up in the output** (05–09) — where each input's content lands in the output tree, and
  which refs come with it. Their graphs are deliberately dull; the trees and the refs are the
  variable.

The two are independent: nothing in 05–09 changes the order, and nothing in 01–04 changes the trees.

**Only the recipe is tracked**: this README, `build-inputs.sh`, each example's README and its
`plan.txt`. The `input/` and `output…/` directories are generated — they are git-ignored, absent
from a fresh clone, and rebuilt by the commands below. Nothing is lost by deleting them.

Every timestamp, name and address the generator uses is pinned, and it reads neither your global
nor your system git configuration, so a rebuild writes the same commits, hash for hash: the commit
hashes quoted throughout these examples can be checked against your own run.

## Layout, per example

- `input/<repo>/` — real git repositories with controlled commit timestamps. Non-bare, except the
  one bare repository example 08 needs to show that a scan finds those too.
- `output/` — the actual output of the real CLI (`git-timebraid`), unmodified.
- `output-<something>/` — a second run of the same CLI over the same input, for the examples that
  are about a contrast rather than a single result: what a flag changes, or what a different
  selection carries over. Each example's README names its own.
- `plan.txt` — the deterministic plan dump (`--plan-out`), one line per commit, with parents and the
  accumulated content map spelled out.
- `README.md` — what this example demonstrates and the exact commands used.

## How the inputs were built

`build-inputs.sh` in this directory. Fixture notation `<name>@<n>` (as used in the unit tests, e.g.
`a1@10 <- a2@30`) maps to real commit timestamps as `BASE + n hours`, so relative order and gaps
survive and remain readable in `git log`. Rerun with `bash doc/examples/build-inputs.sh` (from the
repo root) to regenerate every example's inputs from scratch. It leaves the `output…/` directories
alone, and a run refuses to write into one that is not empty, so delete them before replaying an
example's commands, or add `--force` to each.

01–04 name their repositories `A` and `B`, since only the shape of their graphs matters. 05–09 give
them names of their own — `backend`, `platform` and the rest — because a repository's name is what a
destination, a tag prefix and `--root-repo` are all written in terms of.

## How the `output` directories were generated

The real CLI, e.g. for example 01. `./git-timebraid` is the wrapper in the repo root: it runs
`target/git-timebraid.jar`, so a line below can be copied and run from there once the jar is built.
The `$ git -C output…` and `$ git -C input/…` lines in each example are written from the example's
own directory instead, which is where those paths lead.

An unpacked release archive carries these documents and `build-inputs.sh` but no clone: run the
invocations from the archive's root instead, with `bin/git-timebraid`, or `git-timebraid` on
`PATH`, in place of `./git-timebraid`.

```bash
./git-timebraid -o doc/examples/01-two-linear-repos/output --no-bare \
    --order-by committer --plan-out doc/examples/01-two-linear-repos/plan.txt \
    doc/examples/01-two-linear-repos/input/A doc/examples/01-two-linear-repos/input/B
```

`--no-bare` so the output has a working tree, browsable directly; `--order-by committer` is already
the default, made explicit in 01–04 because the ordering timestamp is what those examples are about.
Each example's own README gives its exact invocation.

## How `output-whole-graph` was generated

The same CLI, with every ref opted into the interleave:

```bash
./git-timebraid -o doc/examples/02-merge-with-late-branch/output-whole-graph --no-bare \
    --order-by committer --interleave-ref '*' \
    doc/examples/02-merge-with-late-branch/input/A doc/examples/02-merge-with-late-branch/input/B
```

`--interleave-ref` puts a matched ref's ancestry in scope, so a mainline merge that merges it in
waits for it; a bare star matches every branch and tag and therefore reproduces a pass over the
whole graph they reach. That is the far end of one mechanism rather than a second algorithm — with
nothing opted in, the same code reduces to a k-way merge of the mainline chains, which is the
default.

Neither output needs code of its own, and that is the same seam twice: the braid is a parameter, and
the write order is derived from the braided graph rather than supplied alongside it.

## What keeps these honest

`.github/scripts/check-examples.py`, run by the `examples reproduce` job in CI: it clears the
generated directories and the tracked `plan.txt` files, reruns `build-inputs.sh`, replays every
invocation these READMEs document, and compares what came back against every `git` output they
quote, plus `git diff --exit-code` over the plans, where one no invocation wrote again shows as
deleted. It prints how many of each it checked; the numbers are not repeated here, where nothing
would hold them to the run. Run it locally the same way, after a `mvn -DskipTests package`.

**Which means the format below is load-bearing.** Editing an example means keeping to it:

| In a fenced block     | What it means                                                                                                      |
|-----------------------|--------------------------------------------------------------------------------------------------------------------|
| `./git-timebraid …`   | an invocation that must succeed; its output is not compared                                                        |
| `$ ./git-timebraid …` | an invocation whose output must contain the lines below it — its closing report, or the refusal being demonstrated |
| `$ git …`             | run in the example's own directory; its output must match the lines below it exactly, with nothing on stderr       |

The fences carry a language tag as well — `bash` for the first form, `console` for the other two —
but that is for rendering: what the script reads is the `$`.

Lines belong to the command above them and nowhere else, so a block with no command in it is never
read as output — which is what leaves the `<name>@<n>` input notation and example 02's summary of
the two orderings free to be what they are. Four liberties are taken when comparing: an object id
written with a trailing `…` matches any id starting that way (written out in full, it has to match
in full), runs of whitespace collapse, an inline `# …` annotation is stripped, and a line reading
`(no output)` stands for no output at all — from a command that wrote nothing to stderr either,
since a command that failed prints nothing on stdout.

## How the braid is built (examples 01–04)

The interleave is `src/main/kotlin/cz/loplex/timebraid/plan/BraidInterleave.kt`, and it takes a
*scope*: the mainline first-parent chains, plus the ancestry of any ref opted in with
`--interleave-ref`. With nothing opted in — the default — the chains are all there is, and the pass
reduces to a k-way merge of one queue per repository, always taking the queue whose front carries
the earliest timestamp. Opt in a ref and a mainline merge that merges it in waits for it, so the
merge can land later than its own timestamp; opt in every ref and you get a pass over the whole
graph, whenever the selection carries the mainline branches. These examples contrast the two ends.

| #                                                                | What it shows                                                                   | Do the two interleaves differ?                                                                    |
|------------------------------------------------------------------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| [01-two-linear-repos](01-two-linear-repos/README.md)             | Basic time-interleaving, no merges                                              | No (nothing to differ on)                                                                         |
| [02-merge-with-late-branch](02-merge-with-late-branch/README.md) | A mainline merge whose merged-in branch is timestamped *after* the merge itself | **Yes** — the one real divergence, and the reason the production interleave works the way it does |
| [03-long-lived-side-branch](03-long-lived-side-branch/README.md) | A branch that forks near the very start and merges at the very end              | No — a long branch lifetime alone changes nothing                                                 |
| [04-clock-skew-in-repo](04-clock-skew-in-repo/README.md)         | A child commit timestamped *before* its own parent                              | No — both put ancestry first                                                                      |

## What ends up in the output (examples 05–09)

Where the content goes is the tree rule
(`src/main/kotlin/cz/loplex/timebraid/git/TreeAssembler.kt`): a commit's tree is its first parent's
with its own destination swapped in, so an input's content is one tree object placed at a path.
Almost everything below follows from *one tree object at a path* — the nesting is free, two
destinations cannot contain each other for free, and a gitlink at a destination is an entry in the
way like any other. Which refs come with it is a separate selection, and 09 is about that.

Three of these examples show the refusal as well as the result, because for them half the rule is
what the tool declines to guess.

| #                                                                  | What it shows                                                          | The refusal it also shows                                    |
|--------------------------------------------------------------------|------------------------------------------------------------------------|--------------------------------------------------------------|
| [05-nested-layout](05-nested-layout/README.md)                     | Destinations that are paths, two inputs sharing a prefix, tree reuse   | —                                                            |
| [06-splice](06-splice/README.md)                                   | `--splice`: one input's destination inside another's                   | The pair without the flag, and a real collision with it      |
| [07-dissolve-submodule](07-dissolve-submodule/README.md)           | `--dissolve-submodules`: a gitlink giving way to the input's history   | The gitlink as an ordinary collision without the flag        |
| [08-scan](08-scan/README.md)                                       | `--scan`: the layout read off a directory tree, and a finding renamed  | Two findings deriving the same name                          |
| [09-ref-selection](09-ref-selection/README.md)                     | `-b`/`--ref`: four selections, and what each output ends up holding    | — (nothing is refused; what is dropped is the point)         |
