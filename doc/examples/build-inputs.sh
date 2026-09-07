#!/usr/bin/env bash
# Builds the input repositories for the ordering-algorithm examples in this directory.
#
# Timestamps map directly to the "@n" notation the example READMEs use for a commit's ordering
# timestamp: offset n -> BASE_EPOCH + n hours, so relative order and gaps are preserved and stay
# readable in `git log`. Every date, name and address is pinned, so the repositories this builds
# are byte-identical on every machine -- which is why the commit hashes quoted in those READMEs
# can be checked against a fresh run.
#
# The repositories it writes are generated output and are not tracked by git; rerun freely.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE=1700000000 # 2023-11-14 22:13:20 UTC, arbitrary fixed reference
HOUR=3600

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
    # recursive merge (git's default strategy) combines their trees without conflicts. The
    # graph shape (parents) is what these examples are about, not the merged content.
}

branch_from() {
    # branch_from <repo-dir> <new-branch> <start-point>
    git -C "$1" branch -q "$2" "$3"
}

checkout() { git -C "$1" checkout -q "$2"; }

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

echo "done"
