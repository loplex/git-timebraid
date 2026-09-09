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
was. Nor is there parsing to share: finding a block in order to measure it and finding one in order
to skip past it are different passes over the same file.
"""
import pathlib
import re
import subprocess
import sys

FENCE = re.compile(r"^ {0,3}(```|~~~)", re.M)
# The other kind of code block: four spaces of indentation, started by a blank line. The doc set
# indents a list continuation by two, so nothing here is read as a block that is not one.
INDENTED = re.compile(r"^ {4}")
# The marker that lets one block stay wide, and the reason it gives, which is not optional.
WIDE_OK = re.compile(r"^\s*<!--\s*wide block:\s*(\S.*?)\s*-->\s*$")

# What a code block can show before it scrolls.
WIDTH = 100


def documents() -> list[pathlib.Path]:
    """Every Markdown file git tracks.

    `git ls-files` and not a filesystem glob, because the worked examples generate Markdown under
    `doc/examples/*/input/` and `*/output/`. .gitignore covers those, a glob does not, and they are
    not documentation anyone here can hold to a rule. A CI runner never sees them: this runs in the
    `docs` job, which builds no example.
    Whoever has run the examples locally does see them, and that is the case being guarded against.

    Everything tracked, rather than README.md plus CHANGELOG.md plus a doc/ glob: a hand-kept list
    of where the documentation lives is a claim that rots the moment a document is written outside
    it, and .github/release-notes.md sits outside it already.
    """
    # -z, because `git ls-files` prints a path holding a space verbatim: splitting on whitespace
    # turns `doc/a note.md` into two names, neither of which is a file, and an is_file() filter
    # then drops the document without a word. A name git gives that the worktree cannot supply is
    # said out loud rather than skipped.
    listed = subprocess.run(
        ["git", "ls-files", "-z", "*.md"], capture_output=True, text=True, check=True
    )
    paths = [pathlib.Path(p) for p in listed.stdout.split("\0") if p]
    absent = [str(p) for p in paths if not p.is_file()]
    if absent:
        raise SystemExit("git names files the worktree does not have: " + ", ".join(absent))
    return paths


def code_blocks(body: str) -> list[tuple[int, int, list[tuple[int, str]]]]:
    """Every code block in a document, as (line it starts on, indentation it renders without, lines).

    Both kinds count, because GitHub scrolls both: a fenced block, and the indented kind that a
    blank line starts and the first unindented line ends. The indentation the block itself carries
    is reported alongside it -- four spaces for an indented block, whatever its opening fence sits
    at for a fenced one -- since that part is the marker rather than the content.
    """
    blocks: list[tuple[int, int, list[tuple[int, str]]]] = []
    fenced: tuple[int, int, list[tuple[int, str]]] | None = None
    indented: tuple[int, int, list[tuple[int, str]]] | None = None
    after_blank = True

    for number, line in enumerate(body.splitlines(), 1):
        blank = not line.strip()
        if fenced is not None:
            if FENCE.match(line):
                blocks.append(fenced)
                fenced = None
            else:
                fenced[2].append((number, line))
            after_blank = False
            continue
        if INDENTED.match(line) and (indented is not None or after_blank):
            indented = indented if indented is not None else (number, 4, [])
            indented[2].append((number, line))
        elif blank and indented is not None:
            indented[2].append((number, line))
        else:
            # Any other line ends an indented block, a fence included: the fence is not indented
            # content, it opens a block of its own.
            if indented is not None:
                blocks.append(indented)
                indented = None
            if FENCE.match(line):
                fenced = (number, len(line) - len(line.lstrip(" ")), [])
        after_blank = blank

    # A block the document never closes is still a block, and its lines still have to fit.
    blocks += [block for block in (fenced, indented) if block is not None]
    return sorted(blocks, key=lambda block: block[0])


def findings(doc: pathlib.Path, body: str) -> tuple[list[str], list[str]]:
    """The width rule's findings for one document: what is wrong, and what is allowed to be wide.

    A `<!-- wide block: ... -->` on the line above a block allows every line in that block, and is
    reported back with the reason it gives, so an exemption stays in sight instead of going quiet.
    The marker is itself a claim: it is wrong when no block follows it, and wrong when the block it
    covers turns out to fit, which is what stops one outliving the line that earned it.
    """
    lines = body.splitlines()
    blocks = {start: (indent, content) for start, indent, content in code_blocks(body)}
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
    docs = documents()
    allowed_wide: list[str] = []
    problems: list[str] = []
    measured = 0

    for doc in docs:
        body = doc.read_text(encoding="utf-8")
        measured += sum(len(content) for _, _, content in code_blocks(body))
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
