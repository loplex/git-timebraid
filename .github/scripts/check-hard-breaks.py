#!/usr/bin/env python3
"""Refuse a hard line break that breaks nothing, which a reader sees as a stray `\\`.

The prose is laid out one information per line, and where a renderer would join two of them into
one line of the page, the first ends in `\\`, CommonMark's hard line break. The break is one only
between two lines of one paragraph: on a paragraph's last line, or with a space after it, the
backslash is plain text and the page shows it. Nothing else notices -- the source reads the same
either way, and a line moved or deleted below a break leaves it at a paragraph's end.

Read: every Markdown document outside its code blocks, and every KDoc, since KDoc is Markdown.
A plain comment is no paragraph of anyone's: no renderer joins its lines, so it needs no break.

A line ends a paragraph where the next one is blank or missing, or starts a block of its own --
a heading, a fence, a list item, a quote, a table row, HTML, a rule, a setext underline, and in a
KDoc a block tag. That is more than CommonMark lets interrupt a paragraph: a `2.` opens no list
there, nor an empty `-`. Over-matching is what keeps a shape nobody listed from passing, and a
line starting with markup is one the layout avoids anyway, since a renderer may read it as markup.
A heading or a table row ending in `\\` is refused for the same reason: neither is a paragraph.

Usage:
  check-hard-breaks.py     refuse a `\\` that ends a paragraph, or has a space after it
"""
import pathlib
import re
import subprocess
import sys

import kotlin_source
import markdown_source

BLOCK_START = re.compile(
    r"""^\s*(?:
        \#                       # a heading
      | ```|~~~                  # a fence
      | [-*+](?:\s|$)            # a bullet
      | \d+[.)](?:\s|$)          # a number
      | >                        # a quote
      | \|                       # a table row
      | <                        # HTML
      | (?:[-*_]\s*){3,}$        # a rule
      | =+\s*$                   # a setext underline; `---` is a rule above
    )""",
    re.X,
)
LIST_ITEM = re.compile(r"^\s*(?:[-*+]|\d+[.)])\s")
KDOC_TAG = re.compile(r"^\s*@\w")
# A break is an odd run of backslashes: an even one is escaped backslashes, and ends in none.
BREAK = re.compile(r"(?<!\\)(?:\\\\)*\\$")
SPACED = re.compile(r"(?<!\\)(?:\\\\)*\\[ \t]+$")


def tracked(*patterns: str) -> list[pathlib.Path]:
    # -z, because `git ls-files` prints a path holding a space verbatim.
    done = subprocess.run(["git", "ls-files", "-z", *patterns], capture_output=True, text=True, check=True)
    return [pathlib.Path(p) for p in done.stdout.split("\0") if p]


def problems(lines: list[tuple[int, str]], kdoc: bool) -> list[tuple[int, str]]:
    """The lines of one Markdown text whose `\\` breaks nothing, from (line number, text) pairs."""
    in_block = {
        number
        for _, _, content in markdown_source.code_blocks("\n".join(text for _, text in lines))
        for number, _ in content
    }
    found = []
    for i, (number, text) in enumerate(lines):
        if i + 1 in in_block or markdown_source.FENCE.match(text):
            continue
        if SPACED.search(text):
            found.append((number, "a space after the `\\`, which makes it text"))
            continue
        if not BREAK.search(text):
            continue
        after = lines[i + 1][1] if i + 1 < len(lines) else ""
        if BLOCK_START.match(text) and not LIST_ITEM.match(text):
            found.append((number, "a `\\` ending a line that is no paragraph's"))
        elif not after.strip() or BLOCK_START.match(after) or (kdoc and KDOC_TAG.match(after)):
            found.append((number, "a `\\` ending a paragraph, which breaks nothing"))
    return found


def kdocs(body: str):
    """Every KDoc as (line number, text) pairs, its `*` margin stripped."""
    for start, _, text_start, text_end in kotlin_source.comment_spans(body):
        if not body.startswith("/**", start):
            continue
        first = body.count("\n", 0, text_start) + 1
        lines = body[text_start:text_end].split("\n")
        # The first line's text follows the second `*` of the opener.
        lines[0] = lines[0][1:]
        yield [(first + k, re.sub(r"^\s*\*? ?", "", line, count=1)) for k, line in enumerate(lines)]


def main() -> int:
    found, scanned = [], 0
    for path in markdown_source.documents():
        scanned += 1
        lines = list(enumerate(path.read_text(encoding="utf-8").splitlines(), 1))
        found += [(path, n, what) for n, what in problems(lines, kdoc=False)]
    for path in tracked("*.kt", "*.kts"):
        scanned += 1
        for kdoc in kdocs(path.read_text(encoding="utf-8")):
            found += [(path, n, what) for n, what in problems(kdoc, kdoc=True)]

    for path, n, what in found:
        print(f"check-hard-breaks: {path}:{n}: {what}", file=sys.stderr)
    if found:
        return 1
    print(f"no hard line break ends a paragraph: {scanned} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
