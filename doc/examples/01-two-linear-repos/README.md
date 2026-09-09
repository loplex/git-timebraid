# 01 — two linear repositories

The baseline case: no merges, no branches, nothing off the mainline. Just two repositories'
commits interleaved by committer time.

## Input

```
A: a1@10 <- a2@30 <- a3@50
B: b1@20 <- b2@40
```

Built by `build-inputs.sh`; both repos have a single `main` branch.

## Command

```
./git-timebraid -o doc/examples/01-two-linear-repos/output --no-bare \
    --order-by committer --plan-out doc/examples/01-two-linear-repos/plan.txt \
    doc/examples/01-two-linear-repos/input/A doc/examples/01-two-linear-repos/input/B
```

Mainline branch auto-detected as `main` (present in both inputs).

## Result

```
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
9845818 2023-11-17 00:13:20 +0000 A: a3
0f0b34c 2023-11-16 14:13:20 +0000 B: b2
89ee7b9 2023-11-16 04:13:20 +0000 A: a2
ead4c4b 2023-11-15 18:13:20 +0000 B: b1
aa48ad1 2023-11-15 08:13:20 +0000 A: a1
```

Oldest to newest: `a1(10), b1(20), a2(30), b2(40), a3(50)` — exactly the two chains merged by time,
nothing more. A whole-graph interleave produces the identical sequence here — there is no merge for
the two to disagree about — so no separate `output-whole-graph` is included for this example.

Try it yourself: `git -C doc/examples/01-two-linear-repos/output log --graph --all` from
the repo root.
