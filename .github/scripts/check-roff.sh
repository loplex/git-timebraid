#!/usr/bin/env bash
#
# Refuses a manual page that roff cannot read as it was meant, in four ways that catch four
# different mistakes.
#
# check-man.py next door reads the page as text and compares each entry's signature with the
# program's own. That says nothing about whether the file is valid roff: a mistyped macro, an
# argument roff reads as a request, a font escape that never closes -- all leave the signatures
# intact and the page malformed.
#
# The first check is groff's own diagnostics. They are not its exit status: it renders what it can
# and says so on stderr while exiting 0, so the output is the gate and not the status. -z renders
# nothing and keeps the diagnostics; -ww turns on every warning category rather than the default
# handful, the ones off by default being about macros and escapes -- exactly what a hand-written
# page gets wrong.
#
# The second check is the rendered page, because the first misses the mistake that does the most
# damage. Measured: a line ending in a lone backslash continues onto the next, which swallowed the
# `.EE` closing a verbatim block, and everything below it then rendered unfilled -- thirty lines
# running past the margin and a macro name printed as text. groff said nothing, the signatures
# still matched, and only looking at the output showed it. So:
#
#   * no rendered line may be wider than the width it was rendered at, which is what unclosed
#     no-fill looks like from the outside;
#   * no rendered line may hold something shaped like a macro anywhere on it -- not only at its
#     start -- which is what a request that got joined to the line above looks like once it is
#     too late to run. The comment above that pattern says why the position is open.
#
# The third check counts the macros that come in pairs, .RS/.RE and .EX/.EE, because groff warns
# about neither and an unclosed .RS does its damage in the indentation rather than in the width,
# where the second check would see it.
#
# It counts the source, so it is not a second way of finding the bug above: that page had its .EE,
# on a line of its own, and roff joined it to the line before. What this finds is a closing macro
# that was never written; what the render finds is one that was written and never ran.
#
# The fourth check is the font escape named above, and it is the one none of the other three can
# see: groff does not warn about it, the pair counts balance because nothing pairs with \fB, and the
# render is measured with overstrike stripped, which is the only trace an open font leaves. It is
# asked per line because that is how this page uses fonts -- an entry opens and closes within its
# own line -- so a line ending with one open is a line whose font runs on into the rest of the page.
#
# Usage: check-roff.sh [file ...]      (default: src/main/man/*.1)
# Exit status is 0 when groff is silent and the rendering is clean, 1 otherwise.

set -euo pipefail

if ! command -v groff > /dev/null; then
    echo "groff is not installed -- it is what reads the page; install groff and run again" >&2
    exit 1
fi

# The width the pages are written to, and the one they are rendered at here.
WIDTH=80

shopt -s nullglob
# The count of the arguments, not the contents of the first: an empty first argument is still an
# argument, and reading what is in it would swap `check-roff.sh "" other.1` for the default glob --
# checking a page the caller never named, and none of the ones it did.
pages=("$@")
[ $# -gt 0 ] || pages=(src/main/man/*.1)

if [ ${#pages[@]} -eq 0 ]; then
    echo "no manual pages to check" >&2
    exit 1
fi

# Refused here rather than handed to groff, which is the one path name it does not fail on: an empty
# one it reads as standard input, and on the EOF a CI step's stdin gives it, it reports success. The
# page would be counted and called clean without anything having been read.
for page in "${pages[@]}"; do
    if [ -z "$page" ]; then
        echo "an empty argument is not a manual page; name the pages, or none for the default" >&2
        exit 1
    fi
done

# Bold and underline reach a terminal as a character, a backspace and the character again. Undone
# here so that a rendered line is measured at the width a reader sees, not at three times it.
overstrike="s/.$(printf '\b')//g"

status=0

for page in "${pages[@]}"; do
    if ! warnings=$(groff -man -ww -z "$page" 2>&1 > /dev/null); then
        echo "groff failed to read $page" >&2
        status=1
        continue
    fi

    if [ -n "$warnings" ]; then
        echo "$page is not the roff it looks like:"
        echo "$warnings"
        status=1
    fi

    rendered=$(GROFF_NO_SGR=1 groff -man -Tutf8 -rLL=${WIDTH}n "$page" 2> /dev/null |
        sed -e "$overstrike" -e 's/[[:space:]]*$//')

    # Captured whole, and trimmed for display without a pipe. With the `head` inside the
    # substitution, the writer takes SIGPIPE as soon as head has its five lines, pipefail makes the
    # pipeline fail, the substitution fails with it, and an `&&` guard then skips the gate -- so the
    # more lines a page gets wrong, the likelier this is to report it clean -- and it is a race, so
    # the same page passes some runs and fails others, which is worse than a gate that never fires
    # at all. The display below reads a here-string rather than a pipe for the same reason one step
    # on: under `set -e` and pipefail a trimmed pipeline exits 141 on that race, which would skip
    # the `status=1` beneath it and every gate and page after it.
    overlong=$(echo "$rendered" | awk -v w="$WIDTH" 'length > w { printf "  %d: %s\n", NR, $0 }')
    if [ -n "$overlong" ]; then
        echo "$page renders past $WIDTH columns, so something is not being filled:"
        head -5 <<< "$overlong"
        status=1
    fi

    # Anywhere on the line, not only at its start: a request swallowed by a line ending in a lone
    # backslash is printed at the end of the line that ate it, which an anchored pattern never sees.
    # Nor does one that wants a space in front of it, since `text\` is the ordinary way to type the
    # mistake and renders as `text.EE`.
    leaked=$(echo "$rendered" | grep -n -E '\.[A-Z]{1,2}([[:space:]]|$)' || true)
    if [ -n "$leaked" ]; then
        echo "$page prints a macro name as text, so a request was joined to the line above it:"
        sed -e 's/^/  /' -e '5q' <<< "$leaked"
        status=1
    fi

    for pair in "RS RE" "EX EE"; do
        set -- $pair
        opened=$(grep -c -E "^\.$1( |$)" "$page" || true)
        closed=$(grep -c -E "^\.$2( |$)" "$page" || true)
        if [ "$opened" != "$closed" ]; then
            echo "$page opens $opened .$1 and closes $closed .$2"
            status=1
        fi
    done

    # The font escape, which is the fourth check the header describes.
    # The last escape on the line, not a count of them: `\fRplain\fB text` balances at one open
    # and one close and still ends with a font open, which is exactly the shape this looks for.
    # The two-letter `\f(BI` and bracketed `\f[...]` forms are read too; everything but R and P
    # opens.
    unclosed=$(awk '{
            line = $0; last = ""
            while (match(line, /\\f(\(..|\[[^]]*\]|.)/)) {
                last = substr(line, RSTART + 2, RLENGTH - 2)
                line = substr(line, RSTART + RLENGTH)
            }
            if (last != "" && last != "R" && last != "P" && last != "[R]" && last != "[P]")
                printf "  %d: the last font escape is \\f%s, which opens\n", NR, last
        }' "$page")
    if [ -n "$unclosed" ]; then
        echo "$page leaves a font escape open at the end of a line, so it runs on below:"
        echo "$unclosed"
        status=1
    fi
done

if [ $status -eq 0 ]; then
    echo "groff reads ${#pages[@]} manual page(s) without a warning, and renders them clean"
fi

exit $status
