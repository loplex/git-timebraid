# Ordering-algorithm examples

Worked examples for [how-it-works.md](../how-it-works.md), which states the rules these demonstrate.

Real, inspectable git repositories demonstrating how `git-timebraid` interleaves commits from several
repositories into one braid, and the one case where interleaving over the mainline chains alone gives
a different — better — answer than interleaving over the whole graph.

The interleave is `src/main/kotlin/cz/loplex/timebraid/plan/BraidInterleave.kt`, and it takes a
*scope*: the mainline first-parent chains, plus the ancestry of any ref opted in with
`--interleave-ref`. With nothing opted in — the default — the chains are all there is, and the
pass reduces to a k-way merge of one queue per repository, always
taking the queue whose front carries the earliest timestamp. Opt in a ref and a mainline merge that
merges it in waits for it, so the merge can land later than its own timestamp; opt in every ref and you
get a pass over the whole graph. These examples contrast the two ends.

**Only the recipe is tracked**: this README, `build-inputs.sh`, each example's README and its
`plan.txt`. The `input/`, `output/` and `output-whole-graph/` directories are generated — they are
git-ignored, absent from a fresh clone, and rebuilt by the commands below. Nothing is lost by
deleting them.

Every timestamp, name and address the generator uses is pinned, and it reads neither your global
nor your system git configuration, so a rebuild writes the same commits, hash for hash: the commit
hashes quoted throughout these examples can be checked against your own run.

## Layout, per example

- `input/<repo>/` — real, non-bare git repositories with controlled commit timestamps.
- `output/` — the actual output of the real CLI (`git-timebraid`), unmodified.
- `output-whole-graph/` — in examples 02 and 03: what the same input produces under a whole-graph
  pass, from the same CLI with every ref opted in — see below. In 02 the two interleaves differ; in
  03 they agree hash for hash, which is what that example is there to show.
- `plan.txt` — the deterministic plan dump (`--plan-out`), one line per commit, with parents and the
  accumulated content map spelled out.
- `README.md` — what this example demonstrates and the exact commands used.

## How the inputs were built

`build-inputs.sh` in this directory. Fixture notation `<name>@<n>` (as used in the unit tests, e.g.
`a1@10 <- a2@30`) maps to real commit timestamps as `BASE + n hours`, so relative order and gaps
survive and remain readable in `git log`. Rerun with `bash doc/examples/build-inputs.sh` (from the
repo root) to regenerate all four examples' inputs from scratch. It leaves the `output/` and
`output-whole-graph/` directories alone, and a run refuses to write into one that is not empty, so
delete them before replaying an example's commands, or add `--force` to each.

## How the `output` directories were generated

The real CLI, e.g. for example 01:

```
mvn -q compile exec:java -Dexec.args="-o doc/examples/01-two-linear-repos/output --no-bare \
    --order-by committer --plan-out doc/examples/01-two-linear-repos/plan.txt \
    doc/examples/01-two-linear-repos/input/A doc/examples/01-two-linear-repos/input/B"
```

`--no-bare` so the output has a working tree, browsable directly; `--order-by committer` is already
the default, made explicit here. Each example's own README gives its exact invocation (they only
differ in the paths).

## How `output-whole-graph` was generated

The same CLI, with every ref opted into the interleave:

```
mvn -q compile exec:java -Dexec.args="-o doc/examples/02-merge-with-late-branch/output-whole-graph --no-bare \
    --order-by committer --interleave-ref * \
    doc/examples/02-merge-with-late-branch/input/A doc/examples/02-merge-with-late-branch/input/B"
```

`--interleave-ref` puts a matched ref's ancestry in scope, so a mainline merge that merges it in waits
for it; a bare star matches every ref and therefore reproduces a pass over the whole graph. That is the
far end of one mechanism rather than a second algorithm — with nothing opted in, the same code reduces
to a k-way merge of the mainline chains, which is the default.

Neither output needs code of its own, and that is the same seam twice: the braid is a parameter, and
the write order is derived from the braided graph rather than supplied alongside it.

## The examples

| #                                                                | What it shows                                                                   | Do the two interleaves differ?                                                                    |
|------------------------------------------------------------------|---------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|
| [01-two-linear-repos](01-two-linear-repos/README.md)             | Basic time-interleaving, no merges                                              | No (nothing to differ on)                                                                         |
| [02-merge-with-late-branch](02-merge-with-late-branch/README.md) | A mainline merge whose merged-in branch is timestamped *after* the merge itself | **Yes** — the one real divergence, and the reason the production interleave works the way it does |
| [03-long-lived-side-branch](03-long-lived-side-branch/README.md) | A branch that forks near the very start and merges at the very end              | No — a long branch lifetime alone changes nothing                                                 |
| [04-clock-skew-in-repo](04-clock-skew-in-repo/README.md)         | A child commit timestamped *before* its own parent                              | No — both put ancestry first                                                                      |
