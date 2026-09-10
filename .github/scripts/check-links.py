#!/usr/bin/env python3
"""Check what the documentation claims about itself: that every link it makes resolves.

The docs link into each other: README.md, CHANGELOG.md and everything under doc/, the worked
examples included. A section renamed in one of them leaves a link in another pointing at an anchor
that no longer exists, and the rendered page gives no sign of it -- the link simply lands at the
top of the file. `mvn verify` cannot see this and neither can check-examples.py, which compares
quoted command output rather than prose.

Three things are checked:

  * every relative link resolves -- the file exists, and when the link names an anchor, that file
    has a heading which produces it;
  * no link points at a heading whose anchor is not predictable (see `anchors`);
  * every pointer at a document from a Kotlin comment resolves the same way. A comment saying
    `see doc/how-it-works.md#the-parent-rule` makes exactly the claim a link makes, and rots for
    exactly the same reason, but it is not in a document so nothing above would look at it. Only
    comments are read, never code -- cut out by kotlin_source.py's scanner, because a regex over
    `//` cannot tell a comment from the `//` in a URL literal, and a string holding `// doc/x.md`
    would then be reported as a broken pointer. Sources other than Kotlin are a gap rather than a
    decision.

The second is deliberately about the link rather than the heading. Most headings here are titles
nothing anchors to -- `# 01 — two linear repositories`, `# 04 — a child commit timestamped before
its own parent` -- and their dashes are the doc set's voice, not a defect. A dash only costs
anything once something tries to link past it, so that is where the check bites; the rest are
listed as a note.

The width of a code block is check-blocks.py's rule next door, not this one's: it is about how a
block is shown rather than about what a document points at, and one checker over both would go red
without saying which of the two had failed.

External links are left alone on purpose: they fail for reasons that have nothing to do with the
commit under test, and a check that goes red on someone else's outage stops being read.
"""
import pathlib
import re
import subprocess
import sys

import kotlin_source

# [text](target). Nested brackets in the text would need a real parser, and a reference-style
# `[text][label]` needs its definition resolved, so neither is followed here. Both are shapes this
# cannot check rather than shapes it has checked, so both are reported: the promise is that nothing
# a reader can click goes past silently, not that everything is resolved.
LINK = re.compile(r"\[(?:[^\[\]]*)\]\(([^)\s]+)\)")
# A reference-style link, which is not matched to its definition.
REF_LINK = re.compile(r"(?<!\])\[[^\[\]]+\]\[[^\[\]]*\]")
# A link definition, `[label]: target` on a line of its own, as CHANGELOG.md ends with. Its target
# is a link like any other and is checked as one.
REF_DEFINITION = re.compile(r"^ {0,3}\[[^\[\]]+\]:\s*(\S+)", re.M)
# A link whose text holds a bracket, which LINK's own character class cannot span.
NESTED_LINK = re.compile(r"\[[^\]]*\[[^\]]*\][^\]]*\]\([^)\s]+\)")
# A backticked span is a quoted token, not prose: a grammar can be written
# `<repo>[::[<subdir>][=<name>]]`, which is the shape of a reference-style link and is not one.
INLINE_CODE = re.compile(r"(`+)(?:(?!\1).)*\1")
# A document named in a comment, with an anchor when it names a section. Written from the
# repository root, which is how the comments here already write it. Any path ending .md, rather
# than doc/ plus an ALL-CAPS name: that shape is the hand-kept list of where the documentation
# lives that documents() below refuses to keep, and it cannot see .github/release-notes.md, the
# file named there as the counterexample.
POINTER = re.compile(r"(?<![\w/.-])([\w.-]+(?:/[\w.-]+)*\.md)(#[\w-]+)?")
# An external link is somebody else's page, left alone here as everywhere in this file -- and cut
# out before a path inside one is read as a pointer at a document of ours.
URL = re.compile(r"\b[a-z][\w+.-]*://\S*")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*$", re.M)
FENCE = re.compile(r"^ {0,3}(```|~~~)", re.M)


def documents() -> list[pathlib.Path]:
    """Every Markdown file git tracks.

    `git ls-files` and not a filesystem glob, because the worked examples generate Markdown under
    `doc/examples/*/input/` and `*/output/`. .gitignore covers those, a glob does not, and they are
    not documentation anyone here can hold to a rule. A CI runner never sees them: this runs in the
    `docs` job and check-examples.py writes them in `examples`, which is a checkout of its own.
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


def strip_fenced(body: str) -> str:
    """Blank out fenced code blocks, so a `#` comment in a shell sample is not read as a heading."""
    out, fenced = [], False
    for line in body.splitlines():
        if FENCE.match(line):
            fenced = not fenced
            out.append("")
        else:
            out.append("" if fenced else line)
    return "\n".join(out)


def anchors(body: str) -> tuple[dict[str, str], list[str]]:
    """The anchors a document offers, and the headings whose anchor is shaky.

    Each anchor maps to the heading it came from where that heading is shaky, and to "" where it is
    not, which is how the caller tells the two apart.

    GitHub lowercases a heading, drops punctuation, and turns the spaces that are left into
    hyphens. A dash surrounded by spaces is dropped along with the rest of the punctuation but
    leaves both its spaces behind, so the anchor comes out with a doubled hyphen or a single one
    depending on which slugger renders it. Both spellings are offered here, so a link is never
    failed for picking the wrong one, and the heading is returned as shaky so that a link which
    relies on it can be reported instead. Writing the dash as a comma or a parenthesis costs
    nothing and makes the anchor certain.
    """
    offered: dict[str, str] = {}
    seen: dict[str, int] = {}
    shaky: list[str] = []

    for _, heading in HEADING.findall(strip_fenced(body)):
        text = re.sub(r"`([^`]*)`", r"\1", heading)
        text = re.sub(r"\[([^\]]*)\]\([^)]*\)", r"\1", text)
        text = re.sub(r"\*+([^*]+)\*+", r"\1", text)
        text = re.sub(r"[^\w\s-]", "", text.lower()).strip()
        unpredictable = bool(re.search(r"\s\s", text))
        if unpredictable:
            shaky.append(heading)
        slug = re.sub(r"\s+", "-", text)
        # A repeated heading gets -1, -2 ... appended, in document order.
        count = seen.get(slug, 0)
        seen[slug] = count + 1
        suffix = "" if count == 0 else f"-{count}"
        for spelling in {slug, re.sub(r"\s", "-", text)}:
            offered.setdefault(spelling + suffix, heading if unpredictable else "")
    return offered, shaky


def sources() -> list[pathlib.Path]:
    """Every Kotlin file git tracks."""
    done = subprocess.run(
        ["git", "ls-files", "-z", "--", "src/**/*.kt", "src/*.kt"],
        capture_output=True, text=True, check=True,
    )
    return [pathlib.Path(p) for p in done.stdout.split("\0") if p]


def main() -> int:
    docs = documents()
    body: dict[pathlib.Path, str] = {}
    known: dict[pathlib.Path, dict[str, str]] = {}
    shaky_in: dict[pathlib.Path, list[str]] = {}
    # The shaky headings something does point at, so the note leaves them out.
    linked: set[tuple[pathlib.Path, str]] = set()
    problems: list[str] = []

    print("== reading the documents")
    for doc in docs:
        body[doc] = doc.read_text(encoding="utf-8")
        offered, shaky = anchors(body[doc])
        known[doc.resolve()] = offered
        shaky_in[doc] = shaky

    print("== resolving the relative links")
    checked = 0
    for doc in docs:
        prose = INLINE_CODE.sub("``", strip_fenced(body[doc]))
        for shape, what in ((REF_LINK, "a reference-style link"),
                            (NESTED_LINK, "a link whose text holds a bracket")):
            for hit in shape.findall(prose):
                problems.append(f"{doc}: {what} this cannot follow -> {hit.strip()}")
        for target in LINK.findall(body[doc]) + REF_DEFINITION.findall(prose):
            if target.startswith(("http://", "https://", "mailto:", "#!")):
                continue
            path, _, fragment = target.partition("#")
            resolved = (doc.parent / path).resolve() if path else doc.resolve()
            checked += 1
            if not resolved.exists():
                problems.append(f"{doc}: no such file -> {target}")
                continue
            if not fragment:
                continue
            offered = known.get(resolved)
            if offered is None:
                problems.append(f"{doc}: anchor into an unchecked file -> {target}")
            elif fragment not in offered:
                problems.append(f"{doc}: no heading makes this anchor -> {target}")
            elif offered[fragment]:
                linked.add((resolved, offered[fragment]))
                problems.append(
                    f"{doc}: points at a heading whose anchor is not predictable, a dash between"
                    f" spaces -> {target}"
                )

    print("== resolving the pointers comments make at documents")
    pointed = 0
    for source in sources():
        for _, comment in kotlin_source.comments(source.read_text(encoding="utf-8")):
            for path, fragment in POINTER.findall(URL.sub(" ", comment)):
                resolved = pathlib.Path(path).resolve()
                pointed += 1
                if not resolved.exists():
                    problems.append(f"{source}: no such document -> {path}{fragment}")
                    continue
                if not fragment:
                    continue
                offered = known.get(resolved)
                if offered is None:
                    problems.append(f"{source}: anchor into an unchecked file -> {path}{fragment}")
                elif fragment[1:] not in offered:
                    problems.append(f"{source}: no heading makes this anchor -> {path}{fragment}")
                elif offered[fragment[1:]]:
                    linked.add((resolved, offered[fragment[1:]]))
                    problems.append(
                        f"{source}: points at a heading whose anchor is not predictable, a dash"
                        f" between spaces -> {path}{fragment}"
                    )

    for problem in problems:
        print(f"  {problem}")

    unlinked_shaky = [
        f"{doc}: {heading!r}"
        for doc in docs
        for heading in shaky_in[doc]
        if (doc.resolve(), heading) not in linked
    ]
    if unlinked_shaky:
        print(f"\nnote: {len(unlinked_shaky)} headings would give an unpredictable anchor.")
        print("Nothing links to them, so nothing is broken; reword the dash before one does.")
        for heading in unlinked_shaky:
            print(f"  {heading}")

    counted = f"{checked} links in {len(docs)} documents, {pointed} pointers from sources"
    if problems:
        print(f"\n{len(problems)} broken, over {counted}")
        return 1
    print(f"\nevery link resolves: {counted}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
