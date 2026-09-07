#!/usr/bin/env bash
#
# Unpacks a distribution archive and merges two repositories with the launcher inside it.
#
# This is the half `jlink` and the assembly plugin cannot check between them: that the thing a user
# downloads actually runs. The fixture is small but chosen for what it exercises --
#
#   * a commit message outside ASCII, which needs `jdk.charsets` in the bundled runtime (charsets
#     arrive through ServiceLoader, so jdeps never sees them and the module is on the list by hand);
#   * running `git` as a subprocess, which on Linux needs `lib/jspawnhelper` to have kept its
#     executable bit through the assembly;
#   * the launcher's own JVM lookup and its jar-relative path resolution.
#
# Usage: smoke-distribution.sh [archive.tar.gz]
# With no argument it takes the platform archive out of target/ -- the one whose name carries an
# os-arch suffix, as opposed to the portable archive built beside it.
#
# Works on either archive. When the unpacked tree has a `runtime/`, JAVA_HOME is pointed at a path
# that does not exist, so reaching a JVM at all proves the launcher preferred the bundled one; the
# portable archive has no runtime and is run against whatever JVM is installed.

set -euo pipefail

archive=${1:-}
if [ -z "$archive" ]; then
    for candidate in target/git-timebraid-*-{linux,macos,windows}-*.tar.gz; do
        [ -e "$candidate" ] || continue
        archive=$candidate
        break
    done
fi
if [ -z "$archive" ]; then
    echo "smoke: no platform archive in target/" >&2
    exit 1
fi
echo "smoke: $archive"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

tar xzf "$archive" -C "$work"
home=$(cd "$work"/*/ && pwd)

if [ -d "$home/runtime" ]; then
    # Nothing here may fall back to an installed JVM: the archive's whole purpose is to carry one.
    export JAVA_HOME=/does-not-exist
    echo "smoke: bundled runtime present, JAVA_HOME deliberately broken"
fi

for repo in alpha beta; do
    git init -q -b main "$work/$repo"
    echo "$repo" > "$work/$repo/file.txt"
    git -C "$work/$repo" add file.txt
    git -C "$work/$repo" \
        -c user.name=CI -c user.email=ci@example.invalid \
        commit -q -m "Add the $repo naïve façade"
done

# On Windows the launcher a user runs is the .bat, and cmd is the only thing that runs it -- this
# script's own bash notwithstanding.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) timebraid() { cmd //c "$(cygpath -w "$home/bin/git-timebraid.bat")" "$@"; } ;;
    *)                    timebraid() { "$home/bin/git-timebraid" "$@"; } ;;
esac

timebraid --version
timebraid -o "$work/out" "$work/alpha" "$work/beta"

git -C "$work/out" fsck --strict
git -C "$work/out" log --oneline --all

# The non-ASCII subjects are the point of the fixture, so assert on them rather than trusting that
# a clean fsck means the bytes came through.
subjects=$(git -C "$work/out" log --format='%s' --all | sort | tr '\n' '|')
expected='alpha: Add the alpha naïve façade|beta: Add the beta naïve façade|'
if [ "$subjects" != "$expected" ]; then
    echo "smoke: commit subjects did not survive:" >&2
    echo "  expected: $expected" >&2
    echo "  actual:   $subjects" >&2
    exit 1
fi

echo "smoke: ok"
