#!/usr/bin/env python3
"""Take the section CHANGELOG.md holds for one tag, ready to be the body of that release.

A release body and a changelog entry answer the same question, and the release page is where it gets
asked: someone arriving at v0.2.0 wants to know what changed in 0.2.0. Linking the changelog on
`main` instead shows them whatever is newest, which after the next release is somebody else's entry.

So the entry is not written twice. It is written once, in CHANGELOG.md, and this lifts the section
for the tag being released.

Three things are refused rather than guessed at, because each would ship silently:

  * no section for the version, which means the release would go out with install instructions and
    no account of what changed. The open entry is headed `## [Unreleased]` while the work is in
    progress, as Keep a Changelog has it, and names no version: the version being worked towards is
    the pom's. Giving that heading its version and date is a hand's job before the tag is cut --
    the release runs on a throwaway checkout and has nowhere to commit the change to -- so this is
    what makes forgetting it loud, and the refusal names the entry to rename;
  * a heading whose date field is not a `YYYY-MM-DD` date. Anything but a date in that field is
    refused rather than read as one;
  * a version with no link definition at the foot of the file, where its heading's `[0.2.0]` would
    otherwise render as the brackets themselves.

Relative links are rewritten against the tag. A link in CHANGELOG.md has to be relative, since that
is what resolves in the repository; a link in a release body has to be absolute, since that page is
not inside the tree. Pinning at the tag rather than at a branch also
means the target says what it said on the day, which is the same reason the entry is lifted at all.

Usage:
  changelog-section.py v0.2.0
  changelog-section.py v0.2.0 https://github.com/owner/repo/blob/v0.2.0
"""
import datetime
import pathlib
import re
import sys

CHANGELOG = pathlib.Path("CHANGELOG.md")

# `## [0.2.0] - 2026-09-08`. The open entry, `## [Unreleased]`, is no heading to this pattern.
HEADING = re.compile(r"^## \[(?P<version>[^\]\s]+)\] - (?P<when>.+?)\s*$", re.M)
UNRELEASED = re.compile(r"^## \[Unreleased\]\s*$", re.M)
# `[0.2.0]: https://...`, the link definitions at the foot of the file.
DEFINITION = re.compile(r"^\[(?P<label>[^\]]+)\]: \S+\s*$", re.M)
DATED = re.compile(r"^\d{4}-\d{2}-\d{2}$")
LINK = re.compile(r"(?P<text>\[[^\[\]]*\])\((?P<target>[^)\s]+)\)")


def is_date(when: str) -> bool:
    """Whether [when] is a `YYYY-MM-DD` date that exists, not only a field shaped like one."""
    if not DATED.match(when):
        return False
    try:
        datetime.date.fromisoformat(when)
    except ValueError:
        return False
    return True


def section(body: str, version: str) -> str:
    """The lines under the heading for [version], up to the next heading or the link definitions."""
    headings = list(HEADING.finditer(body))
    for i, heading in enumerate(headings):
        if heading.group("version") != version:
            continue
        if DATED.match(heading.group("when")) and not is_date(heading.group("when")):
            sys.exit(
                f"CHANGELOG.md says '{heading.group(0).strip()}', and there is no such date"
                f" -- correct it before cutting the tag"
            )
        if not is_date(heading.group("when")):
            sys.exit(
                f"CHANGELOG.md still says '{heading.group(0).strip()}'"
                f" -- date the heading before cutting the tag"
            )
        if not any(d.group("label") == version for d in DEFINITION.finditer(body)):
            sys.exit(
                f"CHANGELOG.md has no link for [{version}] at its foot"
                f" -- add '[{version}]: <URL>' before cutting the tag"
            )
        end = headings[i + 1].start() if i + 1 < len(headings) else len(body)
        footer = DEFINITION.search(body, heading.end())
        if footer and footer.start() < end:
            end = footer.start()
        return body[heading.end() : end].strip("\n")
    offered = ", ".join(h.group("version") for h in headings) or "none"
    if UNRELEASED.search(body):
        sys.exit(
            f"CHANGELOG.md has no section for {version} -- it has {offered} and an Unreleased"
            f" entry: head that one '## [{version}] - <date>', and link it at the foot, before"
            f" cutting the tag"
        )
    sys.exit(f"CHANGELOG.md has no section for {version} -- it has {offered}")


def absolute(text: str, base: str | None) -> str:
    """Relative link targets, rewritten against [base], which is the repository at the tag."""
    def rewrite(match: re.Match) -> str:
        target = match.group("target")
        if target.startswith(("http://", "https://", "mailto:", "#")):
            return match.group(0)
        if base is None:
            sys.exit(
                f"the section links to '{target}', which does not resolve outside the repository"
                f" -- pass the blob URL for the tag so it can be rewritten"
            )
        return f"{match.group('text')}({base.rstrip('/')}/{target.removeprefix('./')})"

    return LINK.sub(rewrite, text)


def main(argv: list[str]) -> int:
    if not 1 <= len(argv) <= 2:
        sys.exit(__doc__.rsplit("Usage:", 1)[-1].strip())
    tag, base = argv[0], (argv[1] if len(argv) == 2 else None)
    if not CHANGELOG.is_file():
        sys.exit(f"{CHANGELOG} is missing -- run this from the repository root")
    print(absolute(section(CHANGELOG.read_text(encoding="utf-8"), tag.removeprefix("v")), base))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
