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
#     executable bit through the assembly. That is why the run is `--no-bare`: its checkout is a git
#     that has to start, where the calls JGit makes to git of its own accord only log a warning;
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
timebraid --no-bare -o "$work/out" "$work/alpha" "$work/beta"

# --no-dangling because the inputs' own commits come in with the fetch and the braid points no ref
# at those; `git gc --prune=now` reclaims them. Corruption is what this checks.
git -C "$work/out" fsck --strict --no-dangling
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

# The manual page, in two parts. That the archive carries it is a property of the assembly and is
# asserted everywhere. That a reader can reach it is a property of the platform: `man` derives its
# search path from PATH, so putting bin/ on PATH -- which is what install.md asks for -- is what
# puts share/man on the man path and makes `git timebraid --help` print this page rather than
# reporting no manual entry.
#
# That second half was measured on Linux only. It runs here so the other legs of the matrix answer
# for themselves, on the archive they just built: where there is no `man` at all, as on Windows,
# there is nothing to measure and the run says so instead of passing quietly.
page=$home/share/man/man1/git-timebraid.1
if [ ! -f "$page" ]; then
    echo "smoke: the archive carries no manual page at share/man/man1/" >&2
    exit 1
fi

if command -v man > /dev/null 2>&1; then
    # A subshell: PATH is changed to the arrangement being tested, and nothing after this needs it.
    # MANPATH is dropped inside it, so that what is measured is man finding the page from PATH, and
    # not whatever the runner happened to export.
    (
        export PATH="$home/bin:$PATH"
        unset MANPATH
        found=$(man -w git-timebraid 2> /dev/null || true)
        if [ -z "$found" ]; then
            echo "smoke: man does not find git-timebraid with $home/bin on PATH" >&2
            echo "smoke: man path is $(manpath 2> /dev/null || echo unknown)" >&2
            exit 1
        fi
        # Compared by content rather than by path, which differs between what man reports and what
        # was unpacked wherever a temporary directory is reached through a symlink.
        if ! cmp -s "$found" "$page"; then
            echo "smoke: man finds $found, which is not the page this archive carries" >&2
            exit 1
        fi
        echo "smoke: man finds the archive's own page at $found"
    )
else
    echo "smoke: no man on this platform, so the page ships unread here"
fi

# The same page as HTML, which git reads where `help.format` is `html`, Git for Windows' default.
# Only that the archive carries it is asserted here, on every platform, since every archive carries
# it: git reads it from its own installation, which this script does not write to. The Windows job
# in ci.yml copies it there and asks git for it.
#
# The title is matched without its hyphen: groff writes the page's `\-` as `-` where the system's
# man.local maps it so, as Debian's does, and as `&minus;` where it does not.
html=$home/share/doc/git-doc/git-timebraid.html
if ! grep -q 'TIMEBRAID</title>' "$html" 2> /dev/null; then
    echo "smoke: the archive carries no HTML page at share/doc/git-doc/;" \
        "render-man-html.sh has to run before the archive is packaged" >&2
    exit 1
fi
echo "smoke: the archive carries the page as HTML"

echo "smoke: ok"
