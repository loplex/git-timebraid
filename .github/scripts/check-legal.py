#!/usr/bin/env python3
"""Hold doc/legal/README.md's list of libraries to the build's runtime dependencies.

Every archive ships each runtime dependency as its own jar in lib/, and doc/legal/README.md says
under which license each one is used. Nothing else connects the two: a dependency added to the pom
lands in lib/ and in every release without a row, and a row outlives the dependency it was written
for, while the build, the tests and the archives all stay green.

So the list is read from the table's first column, one `group:artifact` per row, and asked of the
resolver both ways: every runtime dependency has a row, and every row is a runtime dependency. The
resolver rather than the jars in target/lib/, because a jar carries its coordinates only when Maven
built it (`META-INF/maven/.../pom.properties`), and most of these were built by something else.

That a row's license links a text which exists is check-links.py's question, asked of every
document; what the license of a library is, nothing here can know.

Usage:
  check-legal.py          compare doc/legal/README.md with `mvn dependency:list`
"""
import pathlib
import re
import subprocess
import sys

LIST = pathlib.Path("doc/legal/README.md")

# A table row whose first cell is a `group:artifact` in backticks.
# Rows of any other shape, the header and the separator among them, are not libraries and are skipped.
ROW = re.compile(r"^\|\s*`([^`:\s]+:[^`:\s]+)`\s*\|")


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
    listed = set()
    for line in LIST.read_text(encoding="utf-8").splitlines():
        match = ROW.match(line)
        if match:
            listed.add(match.group(1))

    resolved = runtime_coordinates()
    if resolved is None:
        print("check-legal: the resolver did not list the runtime dependencies -- "
              "run 'mvn dependency:list' to see why", file=sys.stderr)
        return 1

    problems = []
    for dep in sorted(resolved - listed):
        problems.append(f"{dep} is a runtime dependency that {LIST} has no row for")
    for dep in sorted(listed - resolved):
        problems.append(f"{LIST} has a row for {dep}, which is no longer a runtime dependency")

    for problem in problems:
        print(f"check-legal: {problem}", file=sys.stderr)
    if problems:
        return 1
    # Say what was checked: silence on success reads, in a CI log, like a run that checked nothing.
    print(f"{LIST} lists every runtime dependency and nothing else: {len(resolved)} libraries")
    return 0


if __name__ == "__main__":
    sys.exit(main())
