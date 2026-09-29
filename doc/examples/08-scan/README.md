# 08 — the layout taken off a directory tree (`--scan`)

Five repositories in a directory, and the layout written nowhere: `--scan` reads it off the disk.
Shows what the walk finds, what it deliberately does not, what a name two findings share cannot do,
and how an argument corrects a finding rather than adding a second input.

## Input

```text
input/platform/                              repo, and the base of the scan   README.md
input/platform/libs/backend/                 repo                             src/Main.kt
input/platform/libs/core/                    repo                             core.kt
input/platform/tools/core/                   repo                             tool.sh
input/platform/apps/webui.git                repo, bare                       index.html
input/platform/.cache/mirror/                repo — skipped, dot-name
input/platform/libs/backend/vendor/inner/    repo — skipped, inside a repo
```

Timestamps: `p1@10`, `i1@15` (skipped), `a1@20`, `c1@25`, `t1@30`, `b1@35`, `x1@5` (skipped).
`input/staging/webui` is a scratch working repository the bare one was pushed from; it sits outside
the scanned tree and takes no part in the run.

## Refused as it stands

<!-- wide block: the refusal is quoted as the program prints it, on one line -->
```console
$ ./git-timebraid -o doc/examples/08-scan/output-unnamed --no-bare \
    --scan doc/examples/08-scan/input/platform --ref 'core::refs/heads/main'

Usage: git-timebraid [<options>] [<repo>]...

Error: --ref 'core::refs/heads/main' is for input 'core', and two inputs are called that: doc/examples/08-scan/input/platform/libs/core and doc/examples/08-scan/input/platform/tools/core -- give one of them another name, as 'doc/examples/08-scan/input/platform/libs/core::=<name>' or 'doc/examples/08-scan/input/platform/tools/core::=<name>'
```

`libs/core` and `tools/core` derive the same name. A name is a label, and two inputs may share one:
where each lands is what tells them apart, so the scan alone would braid both, their commit subjects
both reading `core: `. What refers to an input by its name — a pattern's scope, `--root-repo` —
cannot say which of the two it means, so it is refused and says how to give one another name.

## Command

```console
$ ./git-timebraid -o doc/examples/08-scan/output --no-bare \
    --scan doc/examples/08-scan/input/platform \
    --plan-out doc/examples/08-scan/plan.txt \
    doc/examples/08-scan/input/platform/tools/core::=tools-core
repositories:
  platform -> <root>
  webui -> apps/webui/
  backend -> libs/backend/
  core -> libs/core/
  tools-core -> tools/core/
```

The `<repo>` argument names a directory the scan already found, so it is a **correction to that
finding** rather than a sixth input. It gives a name and no destination, which is exactly what a
reference to either `core` needs.

## Result

```console
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
7fc1594 2023-11-16 09:13:20 +0000 webui: b1
ef5bc09 2023-11-16 04:13:20 +0000 tools-core: t1
25e9367 2023-11-15 23:13:20 +0000 core: c1
1abcf59 2023-11-15 18:13:20 +0000 backend: a1
5fd1221 2023-11-15 08:13:20 +0000 platform: p1

$ git -C output ls-tree -r --name-only HEAD
README.md
apps/webui/index.html
libs/backend/src/Main.kt
libs/core/core.kt
tools/core/tool.sh
```

Everything landed where it sits on disk, and four separate rules can be read straight off that:

- **`platform` is at the output root**, because the base of the scan is itself a repository. That is
  `--root-repo` reached another way, not a second concept — and, being the root, it is spliced
  without `--splice`, which is why the four destinations inside it are not a collision.
- **The bare repository lost its `.git`**: `apps/webui.git` on disk, `apps/webui` in the output. A
  bare repository is found the same way any other is.
- **The renamed repository did not move.** `tools-core` is its name — `::=tools-core` gives a name
  and nothing else — but its destination is still `tools/core`, where the scan put it. An argument
  that gives no destination leaves the finding's alone.
- **`tools/core` and `libs/core` are two ordinary destinations**, and the commit subjects come from
  the names rather than from those (`--subject-prefix` defaults to `{repo}: `), which is why the two
  read `tools-core: ` and `core: ` rather than alike.

### What the walk left out

```console
$ git -C output ls-tree -r --name-only HEAD | grep -E 'cache|inner'
(no output)
```

- `.cache/mirror` — a dot-name is never walked, so a cache or a mirrored directory cannot turn into
  an input by accident.
- `libs/backend/vendor/inner` — a repository is not descended into. What is nested inside one is
  that repository's own business, and pulling it in is a decision rather than a default. The base
  directory is the exception, or a scan would stop at it and find nothing.

Either can still be merged: give it as an ordinary `<repo>` argument, which is also how a repository
from outside the tree entirely is added.

Try it yourself: `git -C doc/examples/08-scan/output log --stat --first-parent` from the repo root.
