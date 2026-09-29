#!/usr/bin/env bash
# Builds the input repositories for every example in this directory -- the ordering-algorithm
# examples (01-04) and the layout ones (05-09).
#
# Timestamps map directly to the "@n" notation the example READMEs use for a commit's ordering
# timestamp: offset n -> BASE + n hours, so relative order and gaps are preserved and stay
# readable in `git log`. Every date, name and address is pinned, so the repositories this builds
# hold the same commits, hash for hash, on every machine -- which is why the commit hashes quoted
# in those READMEs can be checked against a fresh run. That pinning is also what lets 07's README
# quote a submodule's commit by sha: the sha is a property of the fixture, not of the machine.
#
# The repositories it writes are generated output and are not tracked by git; rerun freely.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE=1700000000 # 2023-11-14 22:13:20 UTC, arbitrary fixed reference
HOUR=3600

# Pinning the dates and the idents is not enough while the machine's own configuration applies: a
# commit-msg hook under a global core.hooksPath rewrites every message, commit.gpgsign signs every
# commit, and either changes every hash. So neither the global nor the system configuration is
# read (GIT_CONFIG_GLOBAL needs git 2.32 or later), and each repository sets its own ident below.
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1

# The environment can bring the same settings back, and a different repository with them:
# GIT_CONFIG_PARAMETERS, which `git -c` hands down, and GIT_CONFIG_COUNT carry configuration,
# GIT_DIR sends every write into the repository it names, and GIT_OBJECT_DIRECTORY every object.
# `git rev-parse --local-env-vars` lists every variable git treats as local to a repository. Two
# that `git init` reads besides can change every hash as well: GIT_TEMPLATE_DIR, for the hooks it
# copies in, and GIT_DEFAULT_HASH, for the object format.
unset $(git rev-parse --local-env-vars) GIT_TEMPLATE_DIR GIT_DEFAULT_HASH

# The environment outranks any configuration, and GIT_AUTHOR_NAME or GIT_COMMITTER_EMAIL exported
# by the caller would change every commit too, so the same ident is set there as well.
export GIT_AUTHOR_NAME="Timebraid Example" GIT_AUTHOR_EMAIL="example@timebraid.test"
export GIT_COMMITTER_NAME="Timebraid Example" GIT_COMMITTER_EMAIL="example@timebraid.test"

ts() { echo $((BASE + $1 * HOUR)); }

new_repo() {
    local dir=$1
    rm -rf "$dir"
    mkdir -p "$dir"
    git init -q -b main "$dir"
    git -C "$dir" config user.name "Timebraid Example"
    git -C "$dir" config user.email "example@timebraid.test"
}

commit_at() {
    # commit_at <repo-dir> <name> <offset>
    local dir=$1 name=$2 offset=$3
    local t
    t=$(ts "$offset")
    echo "$name" >"$dir/$name.txt"
    git -C "$dir" add "$name.txt"
    GIT_AUTHOR_DATE="@$t +0000" GIT_COMMITTER_DATE="@$t +0000" \
        git -C "$dir" commit -q -m "$name"
}

merge_at() {
    # merge_at <repo-dir> <name> <offset> <other-ref>
    local dir=$1 name=$2 offset=$3 other=$4
    local t
    t=$(ts "$offset")
    GIT_AUTHOR_DATE="@$t +0000" GIT_COMMITTER_DATE="@$t +0000" \
        git -C "$dir" merge -q --no-ff --no-edit -m "$name" "$other"
    # No manual file staging here: the two branches never touch the same file, so a plain
    # merge with git's default strategy combines their trees without conflicts. The
    # graph shape (parents) is what these examples are about, not the merged content.
}

branch_from() {
    # branch_from <repo-dir> <new-branch> <start-point>
    git -C "$1" branch -q "$2" "$3"
}

checkout() { git -C "$1" checkout -q "$2"; }

commit_staged_at() {
    # commit_staged_at <repo-dir> <name> <offset>
    # Commits whatever is already in the index, for the entries `git add` cannot make: a gitlink,
    # and a .gitmodules written beside it.
    local dir=$1 name=$2 offset=$3
    local t
    t=$(ts "$offset")
    GIT_AUTHOR_DATE="@$t +0000" GIT_COMMITTER_DATE="@$t +0000" \
        git -C "$dir" commit -q -m "$name"
}

commit_path_at() {
    # commit_path_at <repo-dir> <name> <offset> <relpath>
    # As commit_at, but the file lands at a path of your choosing rather than at <name>.txt. The
    # layout examples are about where content ends up in the output, so the inputs need content
    # that sits somewhere in particular.
    local dir=$1 name=$2 offset=$3 rel=$4
    mkdir -p "$(dirname "$dir/$rel")"
    echo "$name" >"$dir/$rel"
    git -C "$dir" add -- "$rel"
    commit_staged_at "$dir" "$name" "$offset"
}

add_gitlink() {
    # add_gitlink <repo-dir> <path> <sha>
    # Stages a 160000 entry directly instead of going through `git submodule add`, which would
    # need a url that resolves on this machine -- and would then record it. update-index takes the
    # sha as given, so the fixture keeps the same commits and its .gitmodules can name a url that
    # never has to exist.
    git -C "$1" update-index --add --cacheinfo "160000,$3,$2"
}

add_gitmodules_section() {
    # add_gitmodules_section <repo-dir> <path> <url>
    local dir=$1 path=$2 url=$3
    printf '[submodule "%s"]\n\tpath = %s\n\turl = %s\n' "$path" "$path" "$url" >>"$dir/.gitmodules"
    git -C "$dir" add -- .gitmodules
}

tag_at() {
    # tag_at <repo-dir> <tag> <ref>
    git -C "$1" tag "$2" "$3"
}

annotated_tag_at() {
    # annotated_tag_at <repo-dir> <tag> <ref> <offset> <message>
    local dir=$1 tag=$2 ref=$3 offset=$4 msg=$5
    local t
    t=$(ts "$offset")
    GIT_COMMITTER_DATE="@$t +0000" \
        git -C "$dir" tag -a -m "$msg" "$tag" "$ref"
}

# ---------------------------------------------------------------------------
# Ordering-algorithm examples: what the interleave does with a given graph.
# ---------------------------------------------------------------------------

echo "== 01-two-linear-repos =="
EX="$ROOT/01-two-linear-repos/input"
new_repo "$EX/A"
commit_at "$EX/A" a1 10
commit_at "$EX/A" a2 30
commit_at "$EX/A" a3 50

new_repo "$EX/B"
commit_at "$EX/B" b1 20
commit_at "$EX/B" b2 40

echo "== 02-merge-with-late-branch =="
EX="$ROOT/02-merge-with-late-branch/input"
new_repo "$EX/A"
commit_at "$EX/A" a1 10
branch_from "$EX/A" feature main
commit_at "$EX/A" a2 20
checkout "$EX/A" feature
commit_at "$EX/A" f 90
checkout "$EX/A" main
merge_at "$EX/A" m 30 feature

new_repo "$EX/B"
commit_at "$EX/B" b1 25
commit_at "$EX/B" b2 35

echo "== 03-long-lived-side-branch =="
EX="$ROOT/03-long-lived-side-branch/input"
new_repo "$EX/A"
commit_at "$EX/A" a1 0
branch_from "$EX/A" feature main
commit_at "$EX/A" a2 100
checkout "$EX/A" feature
commit_at "$EX/A" f1 10
commit_at "$EX/A" f2 60
commit_at "$EX/A" f3 150
checkout "$EX/A" main
merge_at "$EX/A" m 200 feature

new_repo "$EX/B"
commit_at "$EX/B" b1 30
commit_at "$EX/B" b2 80
commit_at "$EX/B" b3 130
commit_at "$EX/B" b4 180

echo "== 04-clock-skew-in-repo =="
EX="$ROOT/04-clock-skew-in-repo/input"
new_repo "$EX/A"
commit_at "$EX/A" a1 50
commit_at "$EX/A" a2 10 # committed after a1 but stamped *earlier* -- simulated clock skew

new_repo "$EX/B"
commit_at "$EX/B" b1 20
commit_at "$EX/B" b2 40

# ---------------------------------------------------------------------------
# Layout examples: where each input's content ends up in the output tree, and
# which refs come with it. The interleave is the same one 01-04 demonstrate;
# these keep the graphs deliberately dull so the trees are the only variable.
#
# From here on the repositories have names rather than being A and B, because
# the name is what the layout is written in terms of.
# ---------------------------------------------------------------------------

echo "== 05-nested-layout =="
EX="$ROOT/05-nested-layout/input"
new_repo "$EX/backend"
commit_path_at "$EX/backend" a1 10 src/Main.kt
commit_path_at "$EX/backend" a2 30 src/Db.kt

new_repo "$EX/codegen"
commit_path_at "$EX/codegen" c1 20 gen.py

new_repo "$EX/webui"
commit_path_at "$EX/webui" b1 25 index.html
commit_path_at "$EX/webui" b2 40 style.css

echo "== 06-splice =="
EX="$ROOT/06-splice/input"
new_repo "$EX/platform"
commit_path_at "$EX/platform" p1 10 notes.md
commit_path_at "$EX/platform" p2 40 docs/setup.md

new_repo "$EX/backend"
commit_path_at "$EX/backend" a1 20 src/Main.kt
commit_path_at "$EX/backend" a2 30 src/Db.kt

# A second spelling of `platform` that grows an entry of its own at `backend/`, which is the one
# thing --splice does not excuse. It is a separate repository rather than a branch so that the two
# runs differ only in which input they are given.
new_repo "$EX/platform-collide"
commit_path_at "$EX/platform-collide" p1 10 notes.md
commit_path_at "$EX/platform-collide" p3 50 backend/README.md

echo "== 07-dissolve-submodule =="
EX="$ROOT/07-dissolve-submodule/input"
new_repo "$EX/lib"
commit_path_at "$EX/lib" l1 15 src/lib.kt
commit_path_at "$EX/lib" l2 45 src/util.kt
LIB_PIN=$(git -C "$EX/lib" rev-parse main~1) # l1: a pin already behind lib's tip, as pins are

# `other` is never given to the merge. It exists so that super's second gitlink names a real
# commit, and so the example has a submodule that stays one -- which is what keeps a root
# .gitmodules in the output at all.
new_repo "$EX/other"
commit_path_at "$EX/other" o1 5 other.txt
OTHER_PIN=$(git -C "$EX/other" rev-parse main)

new_repo "$EX/super"
commit_path_at "$EX/super" s1 10 a.txt
add_gitlink "$EX/super" vendor/lib "$LIB_PIN"
add_gitmodules_section "$EX/super" vendor/lib https://example.com/lib.git
add_gitlink "$EX/super" vendor/other "$OTHER_PIN"
add_gitmodules_section "$EX/super" vendor/other https://example.com/other.git
commit_staged_at "$EX/super" s2 20
commit_path_at "$EX/super" s3 35 b.txt

echo "== 08-scan =="
EX="$ROOT/08-scan/input"
rm -rf "$EX"
new_repo "$EX/platform" # the base of the scan, and so the output root
commit_path_at "$EX/platform" p1 10 README.md

new_repo "$EX/platform/libs/backend"
commit_path_at "$EX/platform/libs/backend" a1 20 src/Main.kt

new_repo "$EX/platform/libs/core"
commit_path_at "$EX/platform/libs/core" c1 25 core.kt

new_repo "$EX/platform/tools/core" # derives the same name as libs/core, so a reference to it names both
commit_path_at "$EX/platform/tools/core" t1 30 tool.sh

new_repo "$EX/platform/.cache/mirror" # skipped: a dot-name is never walked
commit_path_at "$EX/platform/.cache/mirror" x1 5 junk.txt

new_repo "$EX/platform/libs/backend/vendor/inner" # skipped: inside a repository, not descended into
commit_path_at "$EX/platform/libs/backend/vendor/inner" i1 15 inner.txt

# A bare repository is found the same way, and lands without the `.git`. Pushed into rather than
# cloned, so the fixture records no path of this machine's in its config.
new_repo "$EX/staging/webui" # outside the scanned tree
commit_path_at "$EX/staging/webui" b1 35 index.html
git init -q --bare -b main "$EX/platform/apps/webui.git"
git -C "$EX/staging/webui" push -q "$EX/platform/apps/webui.git" main

echo "== 09-ref-selection =="
EX="$ROOT/09-ref-selection/input"
new_repo "$EX/backend"
commit_path_at "$EX/backend" a1 10 src/Main.kt
commit_path_at "$EX/backend" a2 30 src/Db.kt
tag_at "$EX/backend" v1.0 main~1
branch_from "$EX/backend" release/1.x main~1
checkout "$EX/backend" release/1.x
commit_path_at "$EX/backend" r1 35 src/Hotfix.kt
annotated_tag_at "$EX/backend" v1.1 release/1.x 36 "1.1 hotfix"
checkout "$EX/backend" main

new_repo "$EX/webui"
commit_path_at "$EX/webui" b1 20 index.html
commit_path_at "$EX/webui" b2 40 style.css
tag_at "$EX/webui" v2.0 main

echo "done"
