#!/usr/bin/env python3
"""Hold the shaded jar to what NOTICE says it carries.

NOTICE tells a reader where inside the jar to find each dependency's license text. Those paths are
not written by this build: they arrive from the dependencies themselves, and which file ends up at
a path two of them both use is decided by the order the dependencies resolve in. So an upgrade can
empty a path NOTICE names, or replace its text with another project's, without anything failing --
the jar still builds, still runs, and now points a reader at the wrong license or at nothing.

The check runs both ways. Every path listed below has to be in the jar and carry the text it is
supposed to; and every jar path NOTICE names has to be listed below. The second direction is what
keeps this file from going stale when NOTICE gains an entry.

NOTICE also lists which dependencies the jar carries, by `group:artifact`, and that list is the
half nothing held: a dependency added or dropped leaves it wrong while every path it names is
still right. So the coordinates are asked of the resolver, both ways again. The resolver rather
than the jar because `META-INF/maven/.../pom.properties` is written by the Maven Archiver, so a
jar carries it only when Maven built it, and most of these were built by something else.

It does not read the license texts for meaning. A marker is a short string that only the intended
file contains, enough to tell "slf4j's MIT" from "the Apache License that used to win here".

Usage:
  check-notice.py          verify target/git-timebraid.jar against NOTICE
"""
import pathlib
import re
import subprocess
import sys
import zipfile

NOTICE = pathlib.Path("NOTICE")
JAR = pathlib.Path("target/git-timebraid.jar")

# Path inside the jar -> a string only the file that belongs there contains.
CARRIED = {
    "about.html": "Eclipse Distribution License",
    "META-INF/LICENSE.txt": "QOS.ch",
    "META-INF/LICENSE": "Java Native Access",
    "META-INF/LGPL2.1": "GNU LESSER GENERAL PUBLIC LICENSE",
    "META-INF/git-timebraid/LICENSE": "Apache License",
}

# Jar paths as NOTICE writes them. Anchored to the two shapes that occur -- a META-INF path, and
# JGit's about.html, which sits at the root -- rather than to anything path-like, because NOTICE
# also names runtime/legal/, which belongs to the bundled-runtime archive and not to the jar.
NAMED = re.compile(r"META-INF/[\w./-]*[\w]|\babout\.html\b")

# `group:artifact` as NOTICE writes it, on a line of its own or inside a sentence. The word
# boundaries keep out a colon with a space after it and a colon ending a line. A URL is kept out by
# the `//` its scheme's colon is followed by, which `[\w.-]+` cannot span -- not by anything that
# reads the match afterwards, so loosening this class is what would let one in.
COORDINATE = re.compile(r"\b[a-zA-Z][\w.-]*:[\w.-]+\b")


def runtime_coordinates() -> set[str] | None:
    """Every runtime dependency as `group:artifact`, from the resolver. None if it cannot be run."""
    done = subprocess.run(
        ["mvn", "-B", "-ntp", "-q", "dependency:list", "-DincludeScope=runtime",
         "-DoutputFile=/dev/stdout"],
        capture_output=True, text=True,
    )
    if done.returncode != 0:
        return None
    found = set()
    for line in done.stdout.splitlines():
        parts = line.strip().split(":")
        if len(parts) >= 4 and parts[2] == "jar":
            found.add(f"{parts[0]}:{parts[1]}")
    return found or None


def main() -> int:
    if not JAR.is_file():
        print(f"check-notice: jar not found at {JAR}", file=sys.stderr)
        print("check-notice: run 'mvn -DskipTests package' to build it", file=sys.stderr)
        return 1

    problems = []

    text = NOTICE.read_text(encoding="utf-8")
    named = set(NAMED.findall(text))

    for path in sorted(named - CARRIED.keys()):
        problems.append(f"NOTICE names {path}, which this script does not check -- add it to CARRIED")
    for path in sorted(CARRIED.keys() - named):
        problems.append(f"CARRIED lists {path}, which NOTICE no longer names -- drop it or fix NOTICE")

    resolved = runtime_coordinates()
    if resolved is None:
        problems.append("the resolver did not list the runtime dependencies, so NOTICE's own list "
                        "was not checked -- run 'mvn dependency:list' to see why")
    else:
        # No filter on the shape of a group id. Keeping only groups holding a dot or a hyphen
        # would read as a way to keep a prose colon out, and it would drop `junit:junit` and the
        # rest of that old family: a real dependency with such a group would sit in `resolved`,
        # never in `listed`, and be reported as missing from NOTICE however NOTICE named it -- a
        # failure no edit could clear. The word boundaries in COORDINATE are enough here, this
        # file holding no prose colon followed by a word.
        listed = set(COORDINATE.findall(text))
        for dep in sorted(resolved - listed):
            problems.append(f"{dep} is a runtime dependency that NOTICE does not name")
        for dep in sorted(listed - resolved):
            problems.append(f"NOTICE names {dep}, which is no longer a runtime dependency")

    with zipfile.ZipFile(JAR) as jar:
        entries = set(jar.namelist())
        for path, marker in sorted(CARRIED.items()):
            if path not in entries:
                problems.append(f"{path} is named by NOTICE but is not in the jar")
                continue
            text = jar.read(path).decode("utf-8", errors="replace")
            if marker not in text:
                problems.append(f"{path} does not contain {marker!r}; another file won that path")

    for problem in problems:
        print(f"check-notice: {problem}", file=sys.stderr)
    if problems:
        return 1
    # Say what was checked. Silence on success is indistinguishable, in a CI log, from a run that
    # checked nothing -- and this file is the one that would be read after a dependency changed.
    print(f"the jar agrees with NOTICE: {len(CARRIED)} license paths, "
          f"{len(resolved)} dependencies")
    return 0


if __name__ == "__main__":
    sys.exit(main())
