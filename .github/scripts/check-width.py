#!/usr/bin/env python3
"""Hold every line of the Kotlin sources and of the documentation's prose to its width.

The width is the source's, not the page's. A renderer joins the lines of a Markdown paragraph, so
the page looks the same however they are broken; what a long line costs is everywhere the source
is read without one -- a diff, `git show`, an editor with no wrapping, a terminal.

The widths are `max_line_length` in .editorconfig, the one place they are written: an editor that
reads that file wraps and marks at the same column this checks. A section there whose files this
script does not know fails loudly, rather than setting a width nothing holds.

What is not measured, in Markdown:

  * a code block, fenced or indented -- check-blocks.py measures those, at the width they are
    shown at and with an exemption a block can claim, and both take the blocks from
    markdown_source.py, so no line of one but its fences falls to neither;
  * a table row: its cells are padded so the pipes line up, and a row cannot be broken.

And in both kinds of file a line may run past its width where breaking it would not help: the
word that crosses the limit is the only one on the line, after its indentation and any list,
quote or comment marker -- a long URL or link target on a line of its own.

The code-block and table exceptions are markdownlint's MD013 options `code_blocks` and `tables`.
The unbreakable-word one is narrower than MD013's: without `strict`, MD013 lets any line through
that has no whitespace past the limit, which passes every line whose last word merely straddles it.
"""
import pathlib
import re
import subprocess
import sys

import markdown_source

# Each .editorconfig section this checks, and the files git tracks under it. Markdown is the set
# check-blocks.py reads, so the two divide the same documents between them.
SECTIONS = {
    "*.{kt,kts}": lambda: tracked(["*.kt", "*.kts"]),
    "*.md": markdown_source.documents,
}
TABLE_ROW = re.compile(r"^ {0,3}\|")
# What may stand before an unbreakable word: indentation, and list, quote or comment markers.
LINE_START = re.compile(r"\s*(?:(?:[-*+]|\d+[.)]|>|//|\*|/\*\*?)\s+)*")


def widths() -> dict[str, int]:
    """`max_line_length` per section of .editorconfig, refusing a section this script cannot check.

    Read by hand rather than with an INI parser: .editorconfig puts `root = true` above the first
    section, which configparser refuses, and a section name is a glob, matched here as written.
    """
    found: dict[str, int] = {}
    section = None
    for line in pathlib.Path(".editorconfig").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue
        key, _, value = (part.strip() for part in line.partition("="))
        if key == "max_line_length" and section is not None:
            found[section] = int(value)
    unknown = sorted(set(found) - set(SECTIONS))
    if unknown:
        raise SystemExit(
            f".editorconfig sets max_line_length for {', '.join(unknown)}, which nothing checks; "
            "add the section to SECTIONS in this script"
        )
    missing = sorted(set(SECTIONS) - set(found))
    if missing:
        raise SystemExit(f".editorconfig sets no max_line_length for {', '.join(missing)}")
    return found


def tracked(patterns: list[str]) -> list[pathlib.Path]:
    # -z, because `git ls-files` prints a path holding a space verbatim.
    listed = subprocess.run(
        ["git", "ls-files", "-z", *patterns], capture_output=True, text=True, check=True
    )
    return [pathlib.Path(p) for p in listed.stdout.split("\0") if p]


def measured(path: pathlib.Path, body: str) -> list[tuple[int, str]]:
    """The lines of [path] the width applies to, numbered from 1."""
    lines = list(enumerate(body.splitlines(), 1))
    if path.suffix != ".md":
        return lines
    in_blocks = {
        number
        for _, _, content in markdown_source.code_blocks(body)
        for number, _ in content
    }
    # A fence line is not in the content code_blocks reports, and is part of the block all the same.
    fences = {number for number, line in lines if markdown_source.FENCE.match(line)}
    return [
        (number, line)
        for number, line in lines
        if number not in in_blocks and number not in fences and not TABLE_ROW.match(line)
    ]


def too_wide(line: str, width: int) -> bool:
    """Whether [line] runs past [width] where breaking it before the limit would have helped."""
    if len(line) <= width:
        return False
    # The word that crosses the limit starts after the last whitespace at or before it.
    start = max(line.rfind(" ", 0, width + 1), line.rfind("\t", 0, width + 1)) + 1
    return not LINE_START.fullmatch(line[:start])


def main() -> int:
    problems: list[str] = []
    counted = 0
    for section, width in widths().items():
        for path in SECTIONS[section]():
            for number, line in measured(path, path.read_text(encoding="utf-8")):
                counted += 1
                if too_wide(line, width):
                    problems.append(f"{path}:{number}: {len(line)} characters, over {width}")
    for problem in problems:
        print(f"  {problem}")
    if problems:
        print(f"\n{len(problems)} too wide, over {counted} lines")
        return 1
    print(f"every line fits its width: {counted} lines")
    return 0


if __name__ == "__main__":
    sys.exit(main())
