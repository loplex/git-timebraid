#!/usr/bin/env python3
"""Replays doc/examples and checks that it still says the truth.

The examples are the one place the tool's output is quoted to a reader verbatim, as something they
can reproduce on their own machine. `mvn verify` never goes near them: it proves the behaviour, not
the accuracy of what the READMEs claim that behaviour looks like. Those are different claims, and
only the first one had a check.

So the READMEs are the source of truth here, and this script executes what they say and compares it
against what they show. Nothing is duplicated into the script -- adding an example, or changing a
command, needs no edit here.

Three block conventions in an example's README, and nothing else is looked at:

  ```
  mvn -q exec:java -Dexec.args="..."          an invocation that must succeed. Continuation lines
  ```                                         end with a backslash, as in the READMEs.

  ```
  $ mvn -q exec:java -Dexec.args="..."        an invocation whose output must contain the lines
                                              below it -- either its closing report, or the refusal
  spliced: libs/backend inside libs at ...    three of the examples demonstrate. Which of the two
  ```                                         it is, is settled by the text and needs no marker:
                                              a refusal cannot print a report, or the reverse.

  ```
  $ git -C output log ...                     run in the example's directory; its output must match
  cf6b66a 2023-11-16 14:13:20 +0000 ...       the lines that follow, exactly.
  ```

A line belongs to the command above it and nowhere else. There is no rule that reads a command-free
block, because a README also uses those for things that are not output at all -- the `<name>@<n>`
fixture notation, and example 02's hand-written summary of the two orderings.

Comparison allows for the three ways the READMEs are written, and nothing more:

  * an object id written with a trailing ellipsis matches any id with those leading digits, so
    `655c6e6...` stands for the whole sha -- while an id written out in full has to match in full,
    which is what keeps example 05's "these two trees are the same object" claim at full strength;
  * runs of whitespace collapse, since `ls-tree` output is padded by hand to line up;
  * an inline `# ...` annotation is stripped, which examples 02 and 04 use to date a commit inside
    the output they quote.

`mvn -q exec:java -Dexec.args="<args>"` is executed as `java -jar <jar> <args>` -- the same main
class, without paying for a Maven JVM per invocation. Pass the jar as the first argument; it defaults
to target/git-timebraid.jar, which `mvn package` puts there.

Usage: check-examples.py [path/to/git-timebraid.jar]
Exit status is 0 when every example reproduces, 1 otherwise, with each failure printed.
"""

import pathlib
import re
import shlex
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
EXAMPLES = ROOT / "doc" / "examples"

failures: list[str] = []


def fail(where: str, message: str, expected=None, got=None) -> None:
    failures.append(where)
    print(f"FAIL {where}: {message}")
    if expected is not None:
        print("  expected:")
        for line in expected:
            print(f"    {line}")
        print("  got:")
        for line in got or ["(nothing)"]:
            print(f"    {line}")


def normalise(lines) -> list[str]:
    """A line reduced to what is actually being claimed, on either side of the comparison."""
    out = []
    for line in lines:
        if not line.strip() or line.strip() == "(no output)":
            continue
        line = re.sub(r"\s+#.*$", "", line)  # an inline annotation, not output
        line = re.sub(r"\s+", " ", line).strip()
        if line:
            out.append(line)
    return out


def pattern(claimed: str) -> re.Pattern:
    """A claimed line as a matcher, where a trailing ellipsis on an id means "and the rest"."""
    parts = re.split(r"([0-9a-f]{7,}…)", claimed)
    built = "".join(
        p[:-1] + "[0-9a-f]*" if p.endswith("…") else re.escape(p) for p in parts
    )
    return re.compile(built)


def matches(claimed: list[str], printed: list[str]) -> bool:
    """Exactly the lines claimed, in the order claimed."""
    return len(claimed) == len(printed) and all(
        pattern(c).fullmatch(p) for c, p in zip(claimed, printed)
    )


def contains(printed: list[str], claimed: list[str]) -> bool:
    """Every claimed line appears somewhere in what the run printed.

    Containment rather than equality because a README wraps a long refusal to fit the page, and
    because the quoted report is deliberately an excerpt of the whole.
    """
    joined = " ".join(printed)
    return all(pattern(c).search(joined) for c in claimed)


def blocks(md: pathlib.Path):
    """Yield (section, lines) for each fenced block, with the enclosing `##` heading."""
    section = ""
    fence = None
    for line in md.read_text().split("\n"):
        if line.startswith("## ") and fence is None:
            section = line[3:].strip()
        if line.startswith("```"):
            if fence is None:
                fence = []
            else:
                yield section, fence
                fence = None
        elif fence is not None:
            fence.append(line)


def commands(lines):
    """Split a block into (kind, command, expected) triples. Anything unattached is not output."""
    found = []
    i = 0
    while i < len(lines):
        line = lines[i]
        kind = None
        if line.startswith("mvn -q exec:java"):
            kind, cmd = "run", line
        elif line.startswith("$ mvn -q exec:java"):
            kind, cmd = "quoted", line[2:]
        elif line.startswith("$ git "):
            kind, cmd = "git", line[2:]
        if kind is None:
            i += 1
            continue
        while cmd.rstrip().endswith("\\") and i + 1 < len(lines):
            i += 1
            cmd = cmd.rstrip()[:-1] + lines[i]
        expected = []
        j = i + 1
        while j < len(lines) and not lines[j].startswith(("$ ", "mvn -q exec:java")):
            expected.append(lines[j])
            j += 1
        found.append((kind, cmd, expected))
        i = j
    return found


def cli_args(mvn_command: str) -> list[str]:
    """The `-Dexec.args="..."` payload of a documented invocation, as an argv list."""
    m = re.search(r'-Dexec\.args="(.*)"\s*$', mvn_command, re.S)
    if not m:
        raise ValueError(f"cannot read the arguments out of: {mvn_command}")
    return shlex.split(m.group(1))


def main() -> int:
    jar = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "target" / "git-timebraid.jar"
    if not jar.is_file():
        print(f"no jar at {jar} — run `mvn -DskipTests package` first, or pass its path")
        return 1

    print("== clearing what the recipe regenerates")
    for ex in sorted(EXAMPLES.glob("0*")):
        for generated in list(ex.glob("input")) + list(ex.glob("output*")):
            shutil.rmtree(generated)

    print("== build-inputs.sh")
    build = subprocess.run(["bash", str(EXAMPLES / "build-inputs.sh")], capture_output=True, text=True)
    if build.returncode != 0:
        fail("build-inputs.sh", f"exited {build.returncode}", build.stderr.split("\n"), [])
        return 1

    parsed = {}
    for md in sorted(EXAMPLES.glob("0*/README.md")):
        parsed[md] = [(section, commands(lines)) for section, lines in blocks(md)]

    # The invocations first, so every output directory exists before anything reads one.
    print("== the invocations each README documents")
    for md, items in parsed.items():
        for section, found in items:
            for kind, cmd, expected in found:
                if kind == "git":
                    continue
                where = f"{md.relative_to(ROOT)} [{section}]"
                r = subprocess.run(
                    ["java", "-jar", str(jar), *cli_args(cmd)],
                    cwd=ROOT, capture_output=True, text=True,
                )
                printed = normalise((r.stdout + r.stderr).split("\n"))
                if kind == "run":
                    if r.returncode != 0:
                        fail(where, "a documented invocation failed", ["(it should succeed)"], printed)
                elif not contains(printed, normalise(expected)):
                    fail(where, "the run does not print what is quoted", normalise(expected), printed)

    print("== the tracked plan.txt files")
    # Only the plans: they are generated *and* tracked, so a rebuild that changes one is a
    # behaviour change the docs have not caught up with. The READMEs are hand-written and may
    # legitimately be dirty while someone is editing them.
    diff = subprocess.run(
        ["git", "diff", "--exit-code", "--stat", "--", "doc/examples/*/plan.txt"],
        cwd=ROOT, capture_output=True, text=True,
    )
    if diff.returncode != 0:
        fail("doc/examples/*/plan.txt", "a regenerated plan differs from the tracked one",
             ["(no change)"], diff.stdout.split("\n"))

    print("== the git output each README quotes")
    checked = 0
    for md, items in parsed.items():
        for section, found in items:
            for kind, cmd, expected in found:
                if kind != "git":
                    continue
                r = subprocess.run(cmd, cwd=md.parent, capture_output=True, text=True, shell=True)
                got, want = normalise(r.stdout.rstrip("\n").split("\n")), normalise(expected)
                checked += 1
                if not matches(want, got):
                    fail(f"{md.relative_to(ROOT)} [{section}] {cmd}", "quoted output does not reproduce",
                         want, got)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed")
        return 1
    print(f"every example reproduces: {checked} quoted git outputs, "
          f"{len(list(EXAMPLES.glob('0*/plan.txt')))} plans unchanged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
