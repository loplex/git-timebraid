#!/usr/bin/env python3
"""A KDoc link that resolves to nothing.

`[Something]` in a doc comment is a link. Kotlin's compiler does not read doc comments, so a link
whose target is renamed or deleted keeps compiling, keeps looking authoritative, and sends the next
reader after a declaration that is not there. Java has had `javadoc -Xdoclint` for this since
JDK 8; Kotlin's compiler has no equivalent, and Dokka, which would report it, is not in this build.

What counts as resolved, deliberately generously. A plain `[name]` resolves when it appears in the
CODE of the file that holds it -- as a declaration, a parameter, an import, a call -- or among the
declarations of any tracked Kotlin file. Comments are stripped before that set is built, so a link
cannot resolve against the prose it sits in: a comment that names its own target in passing would
otherwise vouch for a link to a function that is gone.

Comments are cut away by kotlin_source.py's scanner rather than by a regex over `/* */` and
`//`. A regex cannot tell a `//` in a comment from a `//` in a string, and what it over-removes
here is CODE: every line holding a `://` would lose its tail from the set a link resolves against,
so a link whose only mention sits after a URL would be reported as resolving to nothing. That is a
false positive, which is the direction that gets a checker switched off.

A dotted `[A.b]` is read by its head. Where `A` is declared in this repository, `b` is looked for in
the repository's own code; where it is not -- `[ParameterHelp.Argument]`, a nested type of a library
-- only `A` is checked, because the members of something this tree does not contain cannot be
enumerated from here, and guessing would report every such link.

The bound this leaves, stated rather than discovered later: it sees a link that resolves to NOTHING,
never one that resolves to the WRONG thing. `[Foo.bar]` where `bar` belongs to another class reads
as resolved here. Catching that needs real name resolution, which is Dokka's job, not a grep's.
"""
import pathlib
import re
import subprocess
import sys

import kotlin_source

# A link is a bracketed dotted name. `[text](url)` is a Markdown link and not one, and a bracketed
# span inside backticks is quoted rather than written.
LINK = re.compile(r"\[([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\](?!\()")
KDOC = re.compile(r"/\*\*.*?\*/", re.S)
INLINE_CODE = re.compile(r"`+[^`]*`+")
DECLARATION = re.compile(
    r"\b(?:fun|val|var|class|object|interface|typealias)\s+(?:<[^>]*>\s*)?([A-Za-z_]\w*)"
)
IDENTIFIER = re.compile(r"\b[A-Za-z_]\w*\b")


def tracked(*patterns: str) -> list[pathlib.Path]:
    done = subprocess.run(
        ["git", "ls-files", "-z", "--", *patterns], capture_output=True, text=True, check=True
    )
    # -z and a split on NUL: `git ls-files` prints a path holding a space verbatim, and splitting on
    # whitespace turns `src/a b.kt` into two names that no file answers to.
    return [pathlib.Path(p) for p in done.stdout.split("\0") if p]


def main() -> int:
    files = tracked("src/**/*.kt", "src/*.kt")
    bodies = {path: path.read_text(encoding="utf-8") for path in files}
    code = {path: kotlin_source.code(body) for path, body in bodies.items()}

    declared = set()
    everywhere = set()
    for body in code.values():
        declared.update(DECLARATION.findall(body))
        everywhere.update(IDENTIFIER.findall(body))

    problems = []
    for path, body in bodies.items():
        in_scope = set(IDENTIFIER.findall(code[path])) | declared
        for doc in KDOC.finditer(body):
            line = body[: doc.start()].count("\n") + 1
            for link in LINK.findall(INLINE_CODE.sub("``", doc.group(0))):
                segments = link.split(".")
                if len(segments) == 1:
                    ok = segments[0] in in_scope
                elif segments[0] in declared:
                    ok = segments[-1] in everywhere
                else:
                    ok = segments[0] in in_scope
                if not ok:
                    problems.append(f"{path}:{line}: [{link}] resolves to nothing")

    for problem in problems:
        print(f"check-kdoc-links: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"every KDoc link resolves: {len(files)} Kotlin files")
    return 0


if __name__ == "__main__":
    sys.exit(main())
