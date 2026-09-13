#!/usr/bin/env python3
"""A doc comment that stands on another doc comment.

Kotlin binds a KDoc to the declaration that follows it. Two KDocs in a row therefore document one
declaration, the second one wins, and the first documents nothing -- and it compiles, because the
compiler does not read doc comments at all.

The shape has one usual cause: a new, documented function inserted immediately before an existing
one, anchored on the line of the existing declaration. The existing function's KDoc sits above that
anchor, so the new function and its own KDoc land between the old KDoc and its declaration, and the
old KDoc documents nothing. An undocumented function inserted the same way takes the old KDoc for
itself instead, which leaves no stack for this to find.

Comments come from kotlin_source.py's scanner rather than from a regex over the file, because a
regex cannot tell a `*/` in a string from one closing a comment. Two block comments count as
stacked when both open with `/**` and nothing but whitespace separates them. An annotation between
them is not looked past: `/** a */ @Deprecated /** b */` is rare, and looking past it means
deciding what else may sit there, which is where a checker like this starts to guess.

Usage:
  check-doc-comments.py     refuse a KDoc followed by another KDoc in src/**/*.kt
"""
import pathlib
import subprocess
import sys

import kotlin_source


def tracked(*patterns: str) -> list[pathlib.Path]:
    done = subprocess.run(
        ["git", "ls-files", "-z", "--", *patterns], capture_output=True, text=True, check=True
    )
    return [pathlib.Path(p) for p in done.stdout.split("\0") if p]


def is_doc(body: str, start: int) -> bool:
    # `/**/` is an empty block comment, not a doc comment that opens with `/**`.
    return body.startswith("/**", start) and not body.startswith("/**/", start)


def stacked(body: str) -> list[int]:
    """Line numbers of every doc comment that is directly followed by another."""
    spans = kotlin_source.comment_spans(body)
    lines = []
    for (start, end, _, _), (next_start, _, _, _) in zip(spans, spans[1:]):
        if is_doc(body, start) and is_doc(body, next_start) and not body[end:next_start].strip():
            lines.append(body[:start].count("\n") + 1)
    return lines


def main() -> int:
    files = tracked("src/**/*.kt", "src/*.kt")
    problems = []
    for path in files:
        for line in stacked(path.read_text(encoding="utf-8")):
            problems.append(f"{path}:{line}: a doc comment stands on another doc comment")

    for problem in problems:
        print(f"check-doc-comments: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"every doc comment has a declaration of its own: {len(files)} Kotlin files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
