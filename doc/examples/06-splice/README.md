# 06 — one repository placed inside another (`--splice`)

`libs` and `libs/backend` are both destinations, so one repository's content has to land *inside*
another's. Shows what the flag opens up, what it refuses without it, and the one thing it still
refuses with it.

## Input

```
platform: p1@10 <- p2@40     notes.md, then docs/setup.md
backend:  a1@20 <- a2@30     src/Main.kt, then src/Db.kt
```

Plus a third repository, `platform-collide`, used only by the last section: the same `p1`, and then
a commit that adds an entry of its own at `backend/`.

```
platform-collide: p1@10 <- p3@50     notes.md, then backend/README.md
```

## Refused by default

<!-- wide block: the refusal is quoted as the program prints it, on one line -->
```
$ mvn -q compile exec:java -Dexec.args="-o doc/examples/06-splice/output-no-splice --no-bare \
    doc/examples/06-splice/input/platform::=libs \
    doc/examples/06-splice/input/backend::=libs/backend"

'libs' and 'libs/backend' cannot both hold a repository -- one contains the other, and content placed as a single tree object leaves no room beside it. Pass --splice to open the containing repository's tree and place the other inside it.
```

That is not a safety rail bolted on: the entry written at a destination *is* the input's own tree
object, and a tree object has no room beside it. Placing something inside `libs/` means reading
`platform`'s tree there into entries and rebuilding it — which the braid does nowhere else, and
which a typo and an intention look identical from.

## Command

```
$ mvn -q compile exec:java -Dexec.args="-o doc/examples/06-splice/output --no-bare --splice \
    --plan-out doc/examples/06-splice/plan.txt \
    doc/examples/06-splice/input/platform::=libs \
    doc/examples/06-splice/input/backend::=libs/backend"
spliced: libs/backend inside libs at 3 commits, no collision
repositories:
  platform -> libs/
  backend -> libs/backend/
```

## Result

```
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
52732ce 2023-11-16 14:13:20 +0000 libs: p2
561d9da 2023-11-16 04:13:20 +0000 libs/backend: a2
4e349af 2023-11-15 18:13:20 +0000 libs/backend: a1
1c52e1b 2023-11-15 08:13:20 +0000 libs: p1

$ git -C output ls-tree HEAD:libs
040000 tree 8f7fed5afd3e2c1d5678e2877c333d548cd75399	backend
040000 tree 62243dd63ccb91d39d2902a2cbf380d1bd4e9013	docs
100644 blob 171d04eb4ab9c67ae99bc4ed0bf23aa9869c4989	notes.md
```

One directory holding both: `notes.md` and `docs/` are `platform`'s own, `backend/` is the other
input's tree object placed beside them. `platform`'s content is not moved or renamed to make room —
it stays exactly where the tree rule put it.

"at 3 commits" counts the commits where both repositories have content, which is where a splice is
something the writer actually has to do. At the first commit of the braid (`p1@10`) `backend` has
nothing yet, so there is nothing to splice in and `libs/` is just `platform`'s tree.

## Still refused with the flag: an entry already at that name

A splice is not a merge of two repositories' files. Where `platform` itself holds something called
`backend`, the flag does not help — and because that depends on the tree at each commit rather than
on the paths, it is answered per commit:

<!-- wide block: the collision is quoted as the program prints it, object id and all -->
```
$ mvn -q compile exec:java -Dexec.args="-o doc/examples/06-splice/output-collision \
    --no-bare --splice \
    'doc/examples/06-splice/input/platform-collide::platform=libs' \
    doc/examples/06-splice/input/backend::=libs/backend"

one repository cannot be placed inside another where it is:
  - subdirectory 'libs/backend' collides with an entry of the same name in platform at platform/b2f08e9e981466ace65aa1feacc5b442f000c54c
  give each repository placed there another subdirectory with <repo>::=<subdir>
```

Two things to read off that message:

- It names the **commit** the collision happens at (`p3@50`, the one that adds `backend/README.md`),
  not the run. `p1@10` is fine and would have been written; the failure arrives 40 hours of fixture
  time later.
- It arrives **before anything is written into the output**. The check is a pass over the plan ahead
  of the write pass — one that examines each containing repository's tree once per *distinct* tree
  rather than once per commit — so a run that would break halfway leaves no half-written output.
  `--dry-run` reports the same thing.

The `::platform=libs` spelling is what keeps the two runs comparable: it gives the input the name
`platform` rather than the `platform-collide` its directory would derive, so the error message reads
the way it would for the real repository.

Try it yourself: `git -C doc/examples/06-splice/output log --stat --first-parent` from the repo
root.
