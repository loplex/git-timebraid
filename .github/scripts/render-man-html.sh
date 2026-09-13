#!/usr/bin/env bash
#
# Renders each manual page in src/main/man as HTML under target/man-html, for the archives to carry.
#
# Git for Windows defaults `help.format` to `html`, so `git timebraid --help` there asks for
# `git-timebraid.html` in `git --html-path` rather than for a manual page, and an archive carrying
# only the roff gives it nothing to open. The archives carry the HTML beside the roff, at
# share/doc/git-doc, the directory git's own pages sit in under its prefix; doc/install.md says where
# it has to be copied for git to find it.
#
# It is rendered here rather than in the Maven build, and committed nowhere: groff is a system tool
# Maven cannot fetch, so a `mvn package` that rendered the page would fail on every machine without
# it. The jobs that build an archive to ship or to smoke-test run this before they package; a build
# that skips it packages no HTML.
#
# groff stamps a CreationDate into the output, which would make every render differ from the last.
# SOURCE_DATE_EPOCH is set to the date in the page's `.TH` line -- that of its last nontrivial
# change, per man-pages(7) -- so the same page renders to the same bytes on the same groff, and the
# stamp says something true. The groff version is in the output as well, in a Creator comment.
#
# The date becomes seconds through python3, which CI needs for its other checks anyway: `date`
# parses a date one way on Linux and another on macOS, and POSIX gives it no way at all. Written for
# the bash 3.2 macOS ships, since the macOS legs run it.
#
# Usage: render-man-html.sh
# Exit status is 0 when every page rendered without a word from groff on stderr, 1 otherwise.

set -euo pipefail

for tool in groff python3; do
    if ! command -v "$tool" > /dev/null; then
        echo "$tool is not installed -- render-man-html.sh needs it; install $tool and run again" >&2
        exit 1
    fi
done

shopt -s nullglob
pages=(src/main/man/*.1)
if [ ${#pages[@]} -eq 0 ]; then
    echo "no manual pages in src/main/man" >&2
    exit 1
fi

out=target/man-html
mkdir -p "$out"
err=$(mktemp)
trap 'rm -f "$err"' EXIT

status=0
for page in "${pages[@]}"; do
    # The third field of `.TH NAME 1 "2026\-09\-29" ...`, its hyphens escaped for roff or not.
    date=$(sed -n 's/^\.TH[[:space:]][[:space:]]*[^[:space:]][^[:space:]]*[[:space:]][[:space:]]*[^[:space:]][^[:space:]]*[[:space:]][[:space:]]*"\([0-9]\{4\}\)\\\{0,1\}-\([0-9]\{2\}\)\\\{0,1\}-\([0-9]\{2\}\)".*/\1 \2 \3/p' "$page")
    if [ -z "$date" ]; then
        echo "$page has no .TH line with a YYYY-MM-DD date to stamp the HTML with" >&2
        status=1
        continue
    fi
    read -r year month day <<< "$date"
    epoch=$(python3 -c 'import calendar, sys; print(calendar.timegm((*map(int, sys.argv[1:]), 0, 0, 0)))' \
        "$year" "$month" "$day")

    target=$out/$(basename "$page" .1).html
    # Diagnostics are groff's stderr and not its status: it renders what it can and exits 0.
    # Written to a file rather than captured, so the page keeps its last newline.
    if ! SOURCE_DATE_EPOCH=$epoch groff -man -Thtml "$page" > "$target.tmp" 2> "$err" ||
            [ -s "$err" ] || [ ! -s "$target.tmp" ]; then
        echo "groff did not render $page cleanly:" >&2
        cat "$err" >&2
        rm -f "$target.tmp"
        status=1
        continue
    fi
    mv "$target.tmp" "$target"
    echo "rendered $page to $target"
done

exit $status
