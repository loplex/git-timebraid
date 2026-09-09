# 05 — nested destinations, and a shared prefix

The baseline of the layout axis: three repositories placed at paths rather than at plain names, two
of them sharing `libs/`. Shows what the output tree looks like, and that a prefix no commit touched
costs nothing.

## Input

```
backend: a1@10 <- a2@30      src/Main.kt, then src/Db.kt
codegen: c1@20               gen.py
webui:   b1@25 <- b2@40      index.html, then style.css
```

Built by `build-inputs.sh`; each has a single `main` branch and no merges. The graphs are dull on
purpose — the trees are what this example is about.

## Command

```
mvn -q compile exec:java -Dexec.args="-o doc/examples/05-nested-layout/output --no-bare \
    --plan-out doc/examples/05-nested-layout/plan.txt \
    doc/examples/05-nested-layout/input/backend::=libs/backend \
    doc/examples/05-nested-layout/input/codegen::=libs/codegen \
    doc/examples/05-nested-layout/input/webui::=apps/webui"
```

`::=` gives only the destination, so each name stays the one derived from the directory — `backend`,
`codegen`, `webui`. That is what the commit subjects below distinguish the repositories by, and it is
deliberately *not* the destination: `--subject-prefix` defaults to `{subdir}: `, which is why they
read `libs/backend: ` and not `backend: `.

## Result

```
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
cf6b66a 2023-11-16 14:13:20 +0000 apps/webui: b2
887f81f 2023-11-16 04:13:20 +0000 libs/backend: a2
6e67ac1 2023-11-15 23:13:20 +0000 apps/webui: b1
eec3bbb 2023-11-15 18:13:20 +0000 libs/codegen: c1
06b847f 2023-11-15 08:13:20 +0000 libs/backend: a1

$ git -C output ls-tree -r --name-only HEAD
apps/webui/index.html
apps/webui/style.css
libs/backend/src/Db.kt
libs/backend/src/Main.kt
libs/codegen/gen.py
```

`a1(10), c1(20), b1(25), a2(30), b2(40)` — the ordinary interleave, unaffected by the destinations.
`libs/` and `apps/` exist in no input; they are trees the braid builds because an entry name cannot
hold a `/`, and `backend` and `codegen` share the one for `libs`.

### A prefix nobody touched keeps its tree object

The tip commit is `webui`'s, so nothing under `libs/` changed at it — and the `libs` tree it points
at is not a new object but the previous commit's, byte for byte:

```
$ git -C output rev-parse HEAD:libs HEAD~1:libs
5b445c2066a6ad21e434f8074f629d3bcd3095c1
5b445c2066a6ad21e434f8074f629d3bcd3095c1

$ git -C output rev-parse HEAD^{tree} HEAD~1^{tree}
3d894292baaa69d6b465f0ce1fc60dfdd4858123
38f396f33ef5537384d95e95f707b7542b6dfd50
```

The root trees differ, as they must — `apps/` moved. Everything below an untouched prefix is reused,
so a commit costs its root tree plus one new tree per *changed* prefix, and no more. `wrote 5
commits and 10 trees on top` in the run's report is exactly that: 5 root trees, plus one prefix tree
per commit — `libs` for the three `backend`/`codegen` commits, `apps` for the two `webui` ones. The
`libs/backend` and `apps/webui` entries themselves are never written at all; they are the inputs'
own tree objects, which the fetch already put in the output.

### A repository that did not exist yet occupies nothing

```
$ git -C output ls-tree -r --name-only HEAD~4
libs/backend/src/Main.kt
```

At the first commit of the braid neither `libs/codegen` nor `apps/webui` is present — not empty,
absent. `plan.txt` says the same thing in the planner's own terms; its `content=[...]` column names
only the repositories that have content at that point.

Try it yourself: `git -C doc/examples/05-nested-layout/output log --stat --first-parent` from the
repo root.
