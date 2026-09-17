# 02 — a mainline merge whose merged-in branch is timestamped late

**The one real divergence between the two interleaves**, and the reason the production one interleaves
over mainline chains alone. The two produce a different braid on this input — not just a different
write order, an actually different interleaving with the other repository.

## Input

```
A: a1@10 <- a2@20 ; f(a1)@90 <- m(a2,f)@30
B: b1@25 <- b2@35
```

`f` is a side branch of `A`, forked off `a1`, timestamped *after* `m` itself (a slow review, or clock
skew, can produce exactly this). `m` merges `f` back into `A`'s mainline.

```
$ git -C input/A log --all --graph --date=iso --pretty="format:%h %ad %d %s"
*   ebb3dc6 2023-11-16 04:13:20 +0000  (HEAD -> main) m
|\
| * f4eb697 2023-11-18 16:13:20 +0000  (feature) f
* | bbd42ca 2023-11-15 18:13:20 +0000  a2
|/
* 10a7e3a 2023-11-15 08:13:20 +0000  a1
```

## Why this diverges

- **`BraidInterleave`** (`src/main/kotlin/cz/loplex/timebraid/plan/BraidInterleave.kt`, what the CLI
  does): queues hold first-parent
  chains only. It never looks at `f` at all, so `m` is scheduled purely by its own timestamp (30)
  against `B`'s queue — landing *before* `b2` (35), and after `b1` (25).
- **A whole-graph pass** (`--interleave-ref` naming every ref): a commit only joins the ready set
  once *every* parent in scope
  has been emitted. `m` has two parents (`a2`, `f`), so with `f` in scope it cannot be emitted until
  `f` (timestamp 90) has drained — regardless of `m`'s own timestamp (30). That holds `m` back past
  `B`'s entire history.

## Commands

Production (the real CLI):

```
mvn -q compile exec:java -Dexec.args="-o doc/examples/02-merge-with-late-branch/output --no-bare \
    --order-by committer --plan-out doc/examples/02-merge-with-late-branch/plan.txt \
    doc/examples/02-merge-with-late-branch/input/A doc/examples/02-merge-with-late-branch/input/B"
```

The whole-graph contrast, from the same CLI with every ref opted in:

```
mvn -q compile exec:java -Dexec.args="-o doc/examples/02-merge-with-late-branch/output-whole-graph --no-bare \
    --order-by committer --interleave-ref * \
    doc/examples/02-merge-with-late-branch/input/A doc/examples/02-merge-with-late-branch/input/B"
```

## Result

```
$ git -C output log --first-parent main --pretty="format:%h %s %p"
5910c21 B: b2 7e7e370 b1c756f
7e7e370 A: m b1c756f 80015c9 e55db99
b1c756f B: b1 80015c9
80015c9 A: a2 aa48ad1
aa48ad1 A: a1

$ git -C output-whole-graph log --first-parent main --pretty="format:%h %s %p"
e9a085d A: m 4ef4490 80015c9 e55db99
4ef4490 B: b2 b1c756f
b1c756f B: b1 80015c9
80015c9 A: a2 aa48ad1
aa48ad1 A: a1
```

Braids (oldest to newest):

```
BraidInterleave (production):   a1, a2, b1, m,  b2      <- m by its own time (30), ignoring f
whole-graph pass:               a1, a2, b1, b2, m       <- m pushed past b2, waiting for f
```

`m`'s own parents confirm exactly what changes and what does not:

```
output              m's parents: b1(braid pred), a2(original), f(original)
output-whole-graph  m's parents: b2(braid pred), a2(original), f(original)
```

**Both original edges (`a2`, `f`) survive unchanged in both outputs** — `m` gets three parents either
way. Only the braid predecessor (the prepended, artificial time edge) differs, because that is the
only thing an interleave decides. Pinned as a permanent test in `BraidInterleaveTest`
(`still produces a 3-parent commit when reparented, whichever braid it came from`).

## Why the production answer is the better one

The braid edge is the one edge this tool invents, and under the whole-graph pass it can point into the
future:

```
$ git -C output-whole-graph ls-tree -r e9a085d -- B      # checkout of m, recorded 2023-11-16 04:13
100644 blob c9c6af7f…  B/b1.txt
100644 blob e6bfff5c…  B/b2.txt                          # b2 is dated 2023-11-16 09:13 -- 5h later

$ git -C output ls-tree -r 7e7e370 -- B                   # same commit, production output
100644 blob c9c6af7f…  B/b1.txt                          # b1, 2023-11-15 23:13 -- before m
```

Checking out `m` in the whole-graph output shows `B/` as it stood *after* `m`'s own recorded moment.
The default interleave cannot do that: with only the mainline chains in scope the pass reduces to a
k-way merge, which only ever takes the current global minimum across the chains' fronts, so a
cross-repository braid predecessor is never timestamped later than the commit it precedes.
`BraidInterleaveTest` proves that over 300 seeded histories with deliberate timestamp inversions —
and `--interleave-ref` is how you give that up on purpose, which is exactly what this second output
shows.

What neither can fix — and what
[doc/how-it-works.md's caveat](../../how-it-works.md#a-merge-can-carry--and-pass-on--a-repositorys-future)
on a merge carrying a repository's future is about — is content arriving from the future
through a merge that is already in the *input*: `m`'s own tree contains `f`'s work from 2023-11-18, so
`m` shows it in both outputs, and so does every later commit inheriting `A/` forward — in `output`,
`B: b2`. That is a property of the input history, not of any interleave.
