#!/usr/bin/env python3
"""Hold every code block in the documentation to the width it can be shown at.

This is a rule about blocks and not about prose. A paragraph wraps to whatever width the reader
has; a code block, fenced or indented, scrolls instead, so one long line puts a horizontal
scrollbar under everything around it and hides the end of the line from anyone who does not drag
it.

Quoted output is the case where that cannot always be fixed: shortening a message the program
really prints would make the documentation untrue. A block that has to stay wide therefore says so,
on the line above it:

    <!-- wide block: why this one cannot be shortened -->

which is a claim with a cost, not an off switch. A marker covering no overlong line fails as loudly
as an overlong line does, so no block keeps its exemption after the line that earned it is gone.

**It is its own script because width has nothing to do with links.** A link that goes nowhere and
a block too wide to read fail for unrelated reasons, so one checker over both would have to name
the union in its step to stay true, and would go red under a name that did not say which rule it
was. Nor does it share check-links.py's parsing, which skips fenced blocks only. What it shares is
with check-width.py, which measures the prose outside a block: both take the blocks from
markdown_source.py, so no line but a fence or a table row falls to neither.
"""
import pathlib
import re
import sys

import markdown_source

# The marker that lets one block stay wide, and the reason it gives, which is not optional.
WIDE_OK = re.compile(r"^\s*<!--\s*wide block:\s*(\S.*?)\s*-->\s*$")

# What a code block can show before it scrolls. It is the width doc/usage.md is written to and the
# one check-help.py renders `--help` at, so the generated Options block passes without an exemption.
WIDTH = 100


def findings(doc: pathlib.Path, body: str) -> tuple[list[str], list[str]]:
    """The width rule's findings for one document: what is wrong, and what is allowed to be wide.

    A `<!-- wide block: ... -->` on the line above a block allows every line in that block, and is
    reported back with the reason it gives, so an exemption stays in sight instead of going quiet.
    The marker is itself a claim: it is wrong when no block follows it, and wrong when the block it
    covers turns out to fit, which is what stops one outliving the line that earned it.
    """
    lines = body.splitlines()
    blocks = {
        start: (indent, content) for start, indent, content in markdown_source.code_blocks(body)
    }
    allows: dict[int, tuple[int, str]] = {}
    # Kept by line number until the end, so that what is printed reads in the order of the file
    # rather than in the order the blocks happened to be looked at.
    problems: list[tuple[int, str]] = []
    allowed: list[tuple[int, str]] = []

    for number, line in enumerate(lines, 1):
        marker = WIDE_OK.match(line)
        if not marker:
            continue
        follows = next((n for n, text in enumerate(lines[number:], number + 1) if text.strip()), 0)
        if follows in blocks:
            allows[follows] = (number, marker.group(1))
        else:
            problems.append(
                (number, f"{doc}:{number}: a block is allowed to be wide here, and none follows")
            )

    for start, (indent, content) in blocks.items():
        wide = [(n, len(text[indent:].rstrip())) for n, text in content]
        wide = [(n, width) for n, width in wide if width > WIDTH]
        if start not in allows:
            problems += [
                (n, f"{doc}:{n}: {width} characters in a code block, which will not fit in {WIDTH}")
                for n, width in wide
            ]
            continue
        at, why = allows[start]
        if not wide:
            problems.append(
                (at, f"{doc}:{at}: this block fits, so it no longer needs allowing -- {why}")
            )
        allowed += [(n, f"{doc}:{n}: {width} characters -- {why}") for n, width in wide]

    return [text for _, text in sorted(problems)], [text for _, text in sorted(allowed)]


def main() -> int:
    docs = markdown_source.documents()
    allowed_wide: list[str] = []
    problems: list[str] = []
    measured = 0

    for doc in docs:
        body = doc.read_text(encoding="utf-8")
        measured += sum(len(content) for _, _, content in markdown_source.code_blocks(body))
        found, allowed = findings(doc, body)
        problems += found
        allowed_wide += allowed

    for problem in problems:
        print(f"  {problem}")

    if allowed_wide:
        print(f"\nnote: {len(allowed_wide)} lines are allowed to be wider than {WIDTH}.")
        print("Each says why in the document above it; that reason is what keeps it here.")
        for line in allowed_wide:
            print(f"  {line}")

    counted = f"{measured} code-block lines in {len(docs)} documents"
    if problems:
        print(f"\n{len(problems)} too wide, over {counted}")
        return 1
    print(f"every code block fits: {counted}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
