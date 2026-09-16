#!/usr/bin/env python3
"""Hold the punctuation dash to one spelling, where the spelling is a choice and not a constraint.

Everything the program prints is ASCII, because a Windows console encodes to a code page that has
no em dash and substitutes a `?` for it. So a user-facing string writes ` -- `, and MessageCharsetTest
holds it there by reading the compiled constant pool.

Comments and prose reach no console, so that reason does not apply to them and the em dash is what
they use. Both spellings then live in one tree for different reasons, which is how they drift: a
pair of dashes around an aside gets its opening half from one file's habit and its closing half
from another's, and nothing notices.

**The unit is a comment, in whatever language the file is written in** -- not a file under src/main.
That is what keeps `readlink -- "$path"` out of it: an end-of-options marker is code, and code is
not read here. Each kind of file says where its comments are:

  * Kotlin -- a `//` anywhere on a line, and the body of a block comment;
  * shell and .properties -- a `#` line;
  * the Windows launcher -- a `@rem` or `rem` line, which is not the same marker as the shell's;
  * XML -- between `<!--` and `-->`;
  * Markdown -- the whole file is prose, minus fenced and indented blocks and HTML comments.

A backticked span is stripped wherever it appears, comment or prose, because a token in backticks
is quoted rather than written -- `-`/`--` names the argument, it does not punctuate the sentence.
The delimiter is a run of backticks of any length, so a ``--`` written in a doubled span is quoted
too.

Kotlin is scanned rather than searched, and the scanner is kotlin_source.py next door, because
three other checkers ask the same question of the same files: check-links.py reads what a comment
says, check-kdoc-links.py reads everything a comment is not, and check-doc-comments.py where each
comment lies. That module states why searching for the four openers one at a time cannot work.

src/test is read on the same terms as src/main. It holds no user-facing message, so nothing there
can be confused with one, and a comment in a test is read in the same editor as any other.
.github/scripts is left out because it writes ` -- ` throughout and is consistent in itself, and
pom.xml because Maven's own vocabulary carries the ASCII mark in contexts this cannot tell from
prose. The rest of what the scan below does not read is named here too:
doc/examples/build-inputs.sh, the workflows and .gitattributes, for the first of those reasons,
writing the ASCII mark and never the em dash; LICENSE and NOTICE, which are legal text; and
.gitignore and the plan.txt dumps, which are not prose at all.

**The pattern over-matches and is then filtered**, rather than enumerating the positions a dash can
sit in. A list of them -- between words, and at the end of a line -- misses three more: a line that
*begins* with the mark, a Markdown hard break ending ` --\\`, and a comment trailing a line of code.
Enumerating is how a detector is made to pass on the shape nobody thought of, which is the shape a
drift takes.

Usage:
  check-dashes.py          refuse a punctuation `--` in a comment or in prose
"""
import pathlib
import re
import subprocess
import sys

import kotlin_source

# A dash standing on its own. The lookarounds are the filter the over-matching needs: `---`, an
# option (`--verbose`, the `--[no-]x` spelling the help uses, a `--<name>` placeholder), and
# `foo--bar` are all spelled with a dash and none of them is punctuation.
DASH = re.compile(r"(?<![-\w])--(?![-\w\[<])")
FENCE = re.compile(r"^ {0,3}(```|~~~)")
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")
HTML_COMMENT = re.compile(r"<!--.*?-->", re.S)
# A bullet or a number opens a list; what is indented under one is a nested item, not code.
LIST_ITEM = re.compile(r"^ {0,7}(?:[-*+]|\d+[.)])\s")


def tracked(*patterns: str) -> list[pathlib.Path]:
    # -z, because `git ls-files` prints a path holding a space verbatim, and splitting on
    # whitespace turns `doc/a note.md` into two names that no file answers to.
    done = subprocess.run(["git", "ls-files", "-z", *patterns], capture_output=True, text=True, check=True)
    return [pathlib.Path(p) for p in done.stdout.split("\0") if p]


def prefixed_comments(body: str, *markers: str):
    """Comment text per line, where a comment is a line opening with one of `markers` (lower case)."""
    for n, line in enumerate(body.splitlines(), 1):
        stripped = line.lstrip()
        for marker in markers:
            if stripped[: len(marker)].lower() == marker:
                yield n, stripped[len(marker) :]
                break


def xml_comments(body: str):
    for m in HTML_COMMENT.finditer(body):
        yield body[: m.start()].count("\n") + 1, m.group(0)[4:-3]


def markdown_prose(body: str):
    """Prose only: no fenced or indented blocks, no inline code, no HTML comments."""
    # Replaced by its own newlines rather than by a space: collapsing a multi-line comment to one
    # line shifts every line number reported below it.
    body = HTML_COMMENT.sub(lambda m: "\n" * m.group(0).count("\n"), body)
    fenced, in_list = False, False
    for n, line in enumerate(body.splitlines(), 1):
        if FENCE.match(line):
            fenced = not fenced
            continue
        if fenced:
            continue
        indented = line.startswith("    ") or line.startswith("\t")
        if line.strip():
            if LIST_ITEM.match(line):
                in_list = True
            elif not indented:
                in_list = False
        if indented and not in_list:
            continue
        yield n, INLINE_CODE.sub("``", line)


def main() -> int:
    problems, scanned = [], 0

    def check(path: pathlib.Path, pairs, what: str) -> None:
        nonlocal scanned
        scanned += 1
        for n, text in pairs:
            # A backticked token is quoted, not written, in a KDoc as much as in Markdown.
            if DASH.search(INLINE_CODE.sub("``", text)):
                problems.append(f"{path}:{n}: `--` in {what}; the prose mark here is an em dash")

    for path in tracked("src/**/*.kt"):
        check(path, kotlin_source.comments(path.read_text(encoding="utf-8")), "a comment")
    for path in tracked("src/main/scripts/*", "src/main/resources/*.properties"):
        markers = ("@rem", "rem") if path.suffix == ".bat" else ("#",)
        check(path, prefixed_comments(path.read_text(encoding="utf-8"), *markers), "a comment")
    for path in tracked("src/main/**/*.xml"):
        check(path, xml_comments(path.read_text(encoding="utf-8")), "an XML comment")
    for path in tracked("*.md"):
        check(path, markdown_prose(path.read_text(encoding="utf-8")), "prose")

    for problem in problems:
        print(f"check-dashes: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"one spelling of the punctuation dash: comments and prose over {scanned} files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
