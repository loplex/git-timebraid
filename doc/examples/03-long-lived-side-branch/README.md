# 03 — a long-lived side branch does not, by itself, change anything

Answers a specific question: if a side branch forks off right at the start of a repository's history
and only merges back at the very end, does that block the *other* repository's commits from
interleaving during that whole span? **No.** They interleave throughout, exactly as normal, and
(unlike example 02) interleaving over the mainline chains and over the whole graph agree completely
here.

## Input

```text
A: a1@0 <- a2@100 ; f1(a1)@10 <- f2@60 <- f3@150 <- m(a2,f3)@200
B: b1@30 <- b2@80 <- b3@130 <- b4@180
```

`f1 -> f2 -> f3` forks off `a1` (the very first commit of `A`) and only merges back at `m`, the very
last commit — spanning almost `A`'s entire history. Unlike example 02, the branch's last commit
(`f3@150`) is timestamped *before* the merge (`m@200`), so there is no clock-skew anomaly here.

## Commands

```bash
./git-timebraid -o doc/examples/03-long-lived-side-branch/output --no-bare \
    --order-by committer --plan-out doc/examples/03-long-lived-side-branch/plan.txt \
    doc/examples/03-long-lived-side-branch/input/A doc/examples/03-long-lived-side-branch/input/B
```

The whole-graph contrast, from the same CLI with every ref opted in:

```bash
./git-timebraid -o doc/examples/03-long-lived-side-branch/output-whole-graph --no-bare \
    --order-by committer --interleave-ref '*' \
    doc/examples/03-long-lived-side-branch/input/A doc/examples/03-long-lived-side-branch/input/B
```

## Result

```console
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
cac126f 2023-11-23 06:13:20 +0000 A: m
01660f1 2023-11-22 10:13:20 +0000 B: b4
8392de0 2023-11-20 08:13:20 +0000 B: b3
831972e 2023-11-19 02:13:20 +0000 A: a2
356ea9e 2023-11-18 06:13:20 +0000 B: b2
727f9b5 2023-11-16 04:13:20 +0000 B: b1
131d744 2023-11-14 22:13:20 +0000 A: a1

$ git -C output-whole-graph log --first-parent main --date=iso --pretty="format:%h %ad %s"
cac126f 2023-11-23 06:13:20 +0000 A: m
01660f1 2023-11-22 10:13:20 +0000 B: b4
8392de0 2023-11-20 08:13:20 +0000 B: b3
831972e 2023-11-19 02:13:20 +0000 A: a2
356ea9e 2023-11-18 06:13:20 +0000 B: b2
727f9b5 2023-11-16 04:13:20 +0000 B: b1
131d744 2023-11-14 22:13:20 +0000 A: a1
```

**Byte-identical commit hashes.** `B`'s commits (`b1..b4`) interleave among `A`'s mainline commits
(`a1`, `a2`) throughout the branch's whole lifetime — `b1`+`b2` land between `a1` and `a2`, `b3`+`b4`
land between `a2` and `m` — exactly as if the side branch were not delaying anything, because it
isn't: `A`'s ordinary mainline commits have at most one parent (`a1` none, `a2` one), so under a
whole-graph pass their readiness never depends on the side branch at all, and the production
interleave never looks at that branch to begin with. Only `m` itself has two parents, and here its own
timestamp (200) already is later than everything feeding into it, so a whole-graph pass has nothing to
hold it back for. Compare with example 02, where that same wait *did* move something, because there
the merge's own timestamp was **not** the latest of what fed into it.
