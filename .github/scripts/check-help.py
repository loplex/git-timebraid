#!/usr/bin/env python3
"""Keep the Options block in doc/usage.md equal to what `git-timebraid --help` prints.

A hand-written option list is a claim about the program that nothing checks, and it drifts the first
time an option is added, renamed or reworded. It still looks exactly like terminal output, so a
reader trusts it and copies a flag the program no longer accepts.

So the block is not written, it is taken. This script regenerates it with `--write` and, without,
fails when the file and the program disagree. The document marks where it goes with a BEGIN/END
pair, so what gets rewritten is stated by the document rather than inferred from its headings.

The output is stable to compare: the run below pins COLUMNS, so the layout does not depend on the
caller's window, and the width is the one the rest of doc/usage.md is written to rather than the
narrow fallback a redirected `--help` would otherwise take.

Usage:
  check-help.py            verify doc/usage.md matches the built jar
  check-help.py --write    rewrite the block from the built jar
"""
import os
import pathlib
import re
import subprocess
import sys

DOC = pathlib.Path("doc/usage.md")
JAR = pathlib.Path("target/git-timebraid.jar")

# The width doc/usage.md is written to; the program takes it from COLUMNS.
WIDTH = 100

# The block is delimited in the document rather than located by its surroundings. Finding it as
# "the first fence after `## Options`" works only for as long as nobody renames that heading or
# puts a second fenced block in the prose above it -- and either would go unnoticed here, because
# the failure is a rewrite landing somewhere else, not an error.
BEGIN = "<!-- BEGIN --help -->"
END = "<!-- END --help -->"
# The opening fence's info string may be any lowercase word, or none, and is not rewritten: it says
# how the block renders, while the markers say which block this is.
BLOCK = re.compile(
    rf"^{re.escape(BEGIN)}\n```[a-z]*\n(?P<body>.*?)^```\n{re.escape(END)}$", re.M | re.S
)


def rendered_help() -> str:
    if not JAR.exists():
        sys.exit(f"{JAR} is missing -- run `mvn -DskipTests package` first")
    done = subprocess.run(
        ["java", "-jar", str(JAR), "--help"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "COLUMNS": str(WIDTH)},
    )
    if done.returncode != 0:
        sys.exit(f"--help exited {done.returncode}:\n{done.stderr}")
    return done.stdout.rstrip("\n") + "\n"


def main() -> int:
    write = "--write" in sys.argv[1:]

    doc = DOC.read_text(encoding="utf-8")
    match = BLOCK.search(doc)
    if match is None:
        sys.exit(f"no block between {BEGIN} and {END} in {DOC}")

    expected = rendered_help()
    actual = match.group("body")

    if actual == expected:
        print(f"{DOC} quotes the real --help: {len(expected.splitlines())} lines")
        return 0

    if write:
        DOC.write_text(
            doc[: match.start("body")] + expected + doc[match.end("body") :],
            encoding="utf-8",
        )
        print(f"rewrote the Options block in {DOC}: {len(expected.splitlines())} lines")
        return 0

    import difflib

    print(f"{DOC} no longer matches `--help`. Run `.github/scripts/check-help.py --write`.\n")
    diff = difflib.unified_diff(
        actual.splitlines(keepends=True),
        expected.splitlines(keepends=True),
        fromfile=f"{DOC} (as written)",
        tofile="--help (as printed)",
    )
    sys.stdout.writelines(diff)
    return 1


if __name__ == "__main__":
    sys.exit(main())
