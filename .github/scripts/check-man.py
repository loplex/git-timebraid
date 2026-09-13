#!/usr/bin/env python3
"""Keep the options in src/main/man/git-timebraid.1 equal to the options the program has.

The page is written rather than generated, unlike the block in doc/usage.md next door. A manual
page is read by someone who wants to understand an option, not to be reminded of it, so its prose
is allowed to say more than `--help` does and to say it differently. What it may not do is describe
an option that does not exist, leave one out, or get its spelling wrong -- and those are claims a
reader has no way to doubt, since the page looks equally authoritative either way.

So one line of each entry is not free: the signature, the line after `.TP`. It is the name column
`--help` prints, written one canonical way -- each name bold with its hyphens escaped, the names
joined by `, `, and the metavar italic after an unspaced `=` -- and it is compared with that
rendering character for character. That covers the names, the metavar, the `--[no-]x` spelling of a
flag that can be turned off, and the fonts. Everything below it is prose and is not looked at.

Two things follow from checking the signature rather than the whole entry:

  * the page needs one canonical spelling of it, since roff offers several ways to write the same
    row and this would otherwise have to guess at all of them. The page's own header states it, and
    a failure here prints what was expected beside what was found;
  * roff escaping needs a check of its own, which is below. It cannot fall out of the comparison:
    `--bare` and `\\-\\-bare` unescape to the same signature, so a page rendering hyphens the reader
    cannot copy back out would still match the program option for option.

The `.TH` version is checked too. It is the version being worked towards, as pom.xml carries it, so
that the page shipped in a release names that release rather than the one before it. The date on the
same line is that of the page's last nontrivial change, per man-pages(7), and nothing here holds it:
a depth-1 checkout cannot read when a file last changed, and a full clone for one field is a poor
trade.

Usage:
  check-man.py            verify the page against the built jar
"""
import os
import pathlib
import re
import subprocess
import sys

PAGE = pathlib.Path("src/main/man/git-timebraid.1")
JAR = pathlib.Path("target/git-timebraid.jar")

# The width the program lays out to. Only the name column is read here, and it is the same at any
# width, but a pinned value keeps the run independent of the caller's window.
WIDTH = 100

# A name column as `--help` prints it: one or more comma-separated names, then the gap before the
# description, or the end of the line where the description was pushed onto the next one. The gap
# is what tells an entry from a group's prose, which is indented to the same column and can begin
# with option names -- "--tag-prefix, --branch-prefix and --notes-prefix qualify a ref name" is a
# sentence, not a signature, though it opens like one.
COLUMN = re.compile(r"^ {2}(-\S+(?:, -\S+)*)(?:\s{2,}|$)")

# What the page is allowed to put around a signature: the font escapes, and the zero-width
# character that keeps a line from being read as a request.
MARKUP = re.compile(r"\\f[BIRP]|\\&")

# A hyphen roff was not told to leave alone. It renders as a character the reader cannot copy back
# out, which is invisible in the page's source and obvious in exactly the place it does harm.
BARE_HYPHEN = re.compile(r"(?<!\\)-")

TH_VERSION = re.compile(r'^\.TH\s+\S+\s+\S+\s+"[^"]*"\s+"git\\-timebraid ([^"]+)"', re.M)


def signature(line: str) -> str:
    """The row a signature line renders to, with spacing dropped: the key an entry is found by."""
    return re.sub(r"\s+", "", MARKUP.sub("", line).replace(r"\-", "-"))


def canonical(column: str) -> str:
    """The one way the page writes the signature for a name column as `--help` prints it."""
    names, _, metavar = column.partition("=")
    written = ", ".join(r"\fB" + name.replace("-", r"\-") + r"\fR" for name in names.split(", "))
    if metavar:
        written += r"=\fI" + metavar.replace("-", r"\-") + r"\fR"
    return written


def page_signatures(page: str) -> tuple[dict[str, str], list[str]]:
    """Every signature line in OPTIONS, keyed by [signature], and the ones with a bare hyphen."""
    signatures: dict[str, str] = {}
    unescaped: list[str] = []
    section = ""
    entry = False
    for line in page.splitlines():
        if line.startswith(".SH"):
            section = line[3:].strip()
        if entry and section == "OPTIONS":
            signatures[signature(line)] = line.rstrip()
            if BARE_HYPHEN.search(MARKUP.sub("", line)):
                unescaped.append(line)
        entry = line.rstrip() == ".TP"
    return signatures, unescaped


def program(jar: pathlib.Path, *args: str) -> str:
    done = subprocess.run(
        ["java", "-jar", str(jar), *args],
        capture_output=True,
        text=True,
        encoding="utf-8",
        env={**os.environ, "COLUMNS": str(WIDTH)},
    )
    if done.returncode != 0:
        sys.exit(f"`{' '.join(args)}` exited {done.returncode}:\n{done.stderr}")
    return done.stdout


def program_signatures(help_text: str) -> dict[str, str]:
    """Every option's name column, keyed by the same spacing-free form the page is read into."""
    found = {}
    for line in help_text.splitlines():
        match = COLUMN.match(line)
        if match:
            found[re.sub(r"\s+", "", match.group(1))] = match.group(1)
    return found


def main() -> int:
    if not JAR.exists():
        sys.exit(f"{JAR} is missing -- run `mvn -DskipTests package` first")

    page = PAGE.read_text(encoding="utf-8")
    written, unescaped = page_signatures(page)
    real = program_signatures(program(JAR, "--help"))

    failures = []

    for line in unescaped:
        failures.append(f"a hyphen is not escaped as \\- : {line}")

    for key in sorted(set(real) - written.keys()):
        failures.append(f"the page has no entry for: {real[key]}")

    for key in sorted(written.keys() - set(real)):
        failures.append(f"the page has an entry the program does not: {key}")

    # The spelling, once the entry is known to be there: fonts and the `=` are the page's to get
    # right, and the key above sets both aside.
    for key in sorted(written.keys() & set(real)):
        expected = canonical(real[key])
        if written[key] != expected:
            failures.append(f"a signature is not written the canonical way: {written[key]}  -- "
                            f"expected {expected}")

    # The version the page names, against the version the program reports. The page carries the
    # release being worked towards, which is the pom's version without its snapshot suffix.
    stated = TH_VERSION.search(page)
    running = program(JAR, "--version").split()[-1].removesuffix("-SNAPSHOT")
    if stated is None:
        failures.append(".TH does not name a version in the expected form")
    elif stated.group(1) != running:
        failures.append(f".TH says {stated.group(1)}, the program says {running}")

    if not failures:
        print(f"{PAGE} documents all {len(real)} options, as the program spells them")
        return 0

    print(f"{PAGE} and the program disagree.\n")
    for failure in failures:
        print(f"  {failure}")
    print(
        "\nA signature is the line after .TP, written "
        r"\fB\-o\fR, \fB\-\-output\fR=\fI<path>\fR -- "
        "names bold with escaped hyphens and joined by `, `, metavar italic after an unspaced `=`, "
        "nothing else on the line."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
