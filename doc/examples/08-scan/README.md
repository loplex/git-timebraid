# 08 — the layout taken off a directory tree (`--scan`)

Five repositories in a directory, and the layout written nowhere: `--scan` reads it off the disk.
Shows what the walk finds, what it deliberately does not, the one thing it refuses, and how an
argument corrects a finding rather than adding a second input.

## Input

```
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

<!-- wide block: the refusal is quoted as the program prints it, one line naming both locations -->
```
$ ./git-timebraid -o doc/examples/08-scan/output-unnamed --no-bare \
    --scan doc/examples/08-scan/input/platform

Usage: git-timebraid [<options>] [<repo>]...

Error: two inputs resolve to the same repository name 'core': doc/examples/08-scan/input/platform/libs/core and doc/examples/08-scan/input/platform/tools/core -- name a scanned repository by giving its directory as an argument, e.g. '<base>/libs/core::libs-core'
```

`libs/core` and `tools/core` derive the same name, and a name has to be unique — it is the tag
prefix, the provenance label and what `--root-repo` matches. The scan cannot invent a distinction it
was not given, so it refuses and says how to give one.

## Command

```
$ ./git-timebraid -o doc/examples/08-scan/output --no-bare \
    --scan doc/examples/08-scan/input/platform \
    --plan-out doc/examples/08-scan/plan.txt \
    doc/examples/08-scan/input/platform/tools/core::tools-core
repositories:
  platform -> <root>
  webui -> apps/webui/
  backend -> libs/backend/
  core -> libs/core/
  tools-core -> tools/core/
```

The `<repo>` argument names a directory the scan already found, so it is a **correction to that
finding** rather than a sixth input. It gives a name and no destination, which is exactly what was
needed here.

## Result

```
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
05da709 2023-11-16 09:13:20 +0000 apps/webui: b1
00e6cca 2023-11-16 04:13:20 +0000 tools/core: t1
0954aa8 2023-11-15 23:13:20 +0000 libs/core: c1
9e0c748 2023-11-15 18:13:20 +0000 libs/backend: a1
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
- **The renamed repository did not move.** `tools-core` is its name — the argument said so — but its
  destination is still `tools/core`, where the scan put it. An argument that gives no destination
  leaves the finding's alone.
- **`tools/core` and `libs/core` are two ordinary destinations**, and the commit subjects come from
  those (`--subject-prefix` defaults to `{subdir}: `), which is why they read alike even though the
  names now differ.

### What the walk left out

```
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
