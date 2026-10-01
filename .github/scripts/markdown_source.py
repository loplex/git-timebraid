"""Where the code blocks are in a Markdown document, and which documents there are.

Two checkers need the blocks: check-blocks.py measures the lines inside a code block, and
check-width.py measures the prose outside one. Each line of a document but a fence or a table row
has to fall to exactly one of them, so both ask the same function where the blocks are; two
scanners that disagreed about an indented block would leave its lines to neither. Both take the
list of documents from here too, and so does check-links.py.
"""
import pathlib
import re
import subprocess

FENCE = re.compile(r"^ {0,3}(```|~~~)", re.M)
# The other kind of code block: four spaces of indentation, started by a blank line. The doc set
# indents a list continuation by two, so nothing here is read as a block that is not one.
INDENTED = re.compile(r"^ {4}")


def documents() -> list[pathlib.Path]:
    """Every Markdown file git tracks.

    `git ls-files` and not a filesystem glob, because the worked examples generate Markdown under
    `doc/examples/*/input/` and `*/output/`. .gitignore covers those, a glob does not, and they are
    not documentation anyone here can hold to a rule. A CI runner never sees them: the checkers
    run in the `docs` job and check-examples.py writes them in `examples`, which is a checkout of
    its own. Whoever has run the examples locally does see them, and that is the case being guarded
    against.

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
