# 04 — a child commit timestamped before its own parent

The simplest possible ancestry-vs-time conflict: `a2` is a real child of `a1` (a genuine parent edge)
but is timestamped *earlier* — the way a rebase, or a plain clock skew, leaves a repository. Shows
that the braid puts ancestry first, never time.

## Input

```
A: a1@50 <- a2@10
B: b1@20 <- b2@40
```

`build-inputs.sh` creates `a1` then `a2` in that order (so `a2`'s parent really is `a1`), but stamps
`a1`'s committer date at offset 50 and `a2`'s at offset 10 — `a2` is the child, yet claims to be
older.

## Command

```
mvn -q exec:java -Dexec.args="-o doc/examples/04-clock-skew-in-repo/output --no-bare \
    --order-by committer --plan-out doc/examples/04-clock-skew-in-repo/plan.txt \
    doc/examples/04-clock-skew-in-repo/input/A doc/examples/04-clock-skew-in-repo/input/B"
```

## Result

```
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s %p"
f133712 2023-11-15 08:13:20 +0000 A: a2 d917178
d917178 2023-11-17 00:13:20 +0000 A: a1 e2b86db
e2b86db 2023-11-16 14:13:20 +0000 B: b2 1611e1b
1611e1b 2023-11-15 18:13:20 +0000 B: b1
```

Oldest to newest: `b1, b2, a1, a2`. Even though `a2`'s own raw timestamp (10) is the *earliest* of
every `A` commit, it still lands **last** in the braid, because its real parent `a1` (timestamp 50)
must be emitted first — ancestry wins over the recorded time, exactly as the tool's core guarantee
requires. `b1`/`b2` interleave on their own merits (20 and 40) around wherever `a1`'s own time (50)
falls.

A whole-graph interleave agrees here too (not included as a separate output): both track
first-parent ancestry within one repository *by construction*. `BraidInterleave` only ever pops from
the front of `A`'s own queue, so `a1` and `a2` can never swap regardless of what their timestamps say;
Kahn's algorithm over the whole graph gets the same result from the other direction, by never emitting
a commit before its parents. A comparator sort over the whole graph that compares a commit only
against its *direct* parent, and lets time decide everything else, can get a case like this wrong and
place a commit before its own parent: such a comparator is not transitive, so the sort need
never compare `a2` with `a1` at all. Neither of the two is built that way.
