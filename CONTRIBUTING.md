# Contributing to git-timebraid

*Building it from a clone, checking a change, and how its text and its commits are written.*

- [README](README.md) — what the tool is, and why the braid is shaped the way it is.
- [doc/install.md](doc/install.md) — running a released archive, which needs nothing on this page.

---

## Building from source

The [released archives](doc/install.md) are this same build,
produced by CI and smoke-tested on every platform it publishes.\
Build it yourself to work on git-timebraid, or to get an archive for a platform the releases do not cover.\
It takes a JDK 17 or newer, and Maven.

```bash
mvn -q package                       # builds target/git-timebraid-<version>.tar.gz (and .zip)
```

That is the portable archive, unpacked the same way, plus `target/lib/`, the archive's `lib/` as it ships,
for running the jar straight out of the build: `java -jar target/lib/git-timebraid.jar`.\
The release archives also carry the manual page as HTML,
which `.github/scripts/render-man-html.sh` renders before they are packaged, and which needs groff and python3;
an archive packaged without running it first has no `share/doc/git-doc/`,
which only [Git for Windows](doc/install.md#git-for-windows-reads-the-html-page) misses.

**`./git-timebraid` in the repo root runs that jar**, so a clone needs no install to be driven.\
It is what the [worked examples](doc/examples/README.md) are written in terms of,
and it reads `TIMEBRAID_JAVA` and `JAVA_OPTS` the way the shipped launcher does.

### Building an archive that carries its own JVM

`-Pbundled-runtime` builds the platform archive, trimming a JVM with `jlink`:

```bash
mvn -q -Pbundled-runtime package      # adds git-timebraid-<version>-<os>-<arch>.tar.gz (and .zip)
```

- By default it is the JDK running Maven that gets bundled, so the archive is for the platform you build on —
  hence the platform in its name.
- `-Djlink.jmods=<dir>` links the `jmods` of another JDK instead, which builds another platform's archive:
  the Windows one is built on Linux this way.\
  The `jmods` have to be from the same JDK version as the one running Maven,
  and `-Ddist.os` and `-Ddist.arch` name the platform it is for:

  ```bash
  mvn -q -Pbundled-runtime -Djlink.jmods=<windows-jdk>/jmods -Ddist.os=windows -Ddist.arch=x64 package
  ```
- `--compress=zip-6` needs JDK 21 or newer; on JDK 17 build it with `-Djlink.compress=2`.

## Checking a change

```bash
mvn verify                           # the unit and the integration tests
```

Beside the tests, CI runs the scripts in `.github/scripts/` whose names start with `check-`,
each a step of [ci.yml](.github/workflows/ci.yml) under a comment saying what it catches.\
Each runs from the repository root with no arguments, so all of them run as:

```bash
mvn -q -DskipTests package           # check-examples, check-help and check-man run the built jar
for check in .github/scripts/check-*; do "$check" || echo "FAILED: $check"; done
```

`check-roff.sh` needs groff as well.

The option list in [doc/usage.md](doc/usage.md#options) is what `--help` prints, not written by hand:
after a change of the help text, `.github/scripts/check-help.py --write` regenerates it.

## How the text is written

The prose — the documents, and the comments in the code, the scripts and the build —
is laid out one information per line.\
That is [Semantic Line Breaks](https://sembr.org/), with three differences:
two sentences that are one information share a line,
a clause breaks only past the cap, and the cap is 120 columns rather than 80.\
The manual page follows the same rule,
which [man-pages(7)](https://man7.org/linux/man-pages/man7/man-pages.7.html) asks of its source as "semantic newlines".

- **A sentence starts a line**, unless it is one information with the sentence before —
  a claim and its reason, a statement and its example — and the joined line fits.
- **In Markdown and in KDoc**, where the page has to show two informations apart,
  the first line ends in `\`, CommonMark's hard line break; without it a renderer joins the lines.\
  The last line of a paragraph never ends in one, since there the page shows it as text.
- **In a plain comment or a commit message** a line break is enough, since nothing joins the lines.
- **A line past the cap breaks at a clause** — after a comma, a semicolon, a colon or a dash —
  never inside inline code or a link, and never so that the next line starts with markup such as `#`, `-` or `>`.
- **The punctuation dash** is an em dash in prose and comments, and ` -- ` in what the program prints;
  [check-dashes.py](.github/scripts/check-dashes.py) says why, and where else the ASCII one stands.

Left as written:

- code, strings, headings, tables and link definitions;
- the manual page's unfilled text (`.nf`, `.EX`, a table), which roff shows line for line;
- the Python scripts' docstrings, which are paragraphs filled to about 100 columns;
- texts this project did not write, such as the licenses.

### What the checks hold, and what they cannot

- **The width:** [.editorconfig](.editorconfig) sets 120 columns for Kotlin, Markdown, Python, shell, YAML and XML,
  and `check-width.py` holds every line of them to it, code included.\
  In Markdown it leaves out a code block, which scrolls on the page rather than wrapping:
  `check-blocks.py` holds a block to the 100 columns shown without a scrollbar.\
  It leaves out a table row too, which cannot be broken; its cells are padded so the pipes line up in the source.
- **The hard breaks:** `check-hard-breaks.py` refuses a `\` that ends a paragraph, or has a space after it.
- **The layout itself** no script can check: whether a line holds one information,
  and where a long one breaks, is the writer's judgment, and the reviewer's.

## Commit messages

- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/): `type(scope): summary`.\
  `feat`, `fix` and `perf` say what the program does differently;
  `docs`, `test`, `build`, `ci`, `refactor`, `style` and `chore` say what was done to the repository.
- The summary is imperative, lower case and without a period, at most 72 characters.
- The body, wrapped at 72 columns, says why rather than how.
- A breaking change, one that breaks something a user relied on, such as a command line that worked,
  has a `!` before the colon and a `BREAKING-CHANGE:` trailer saying what breaks.
- A change a user sees gets an entry under `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md),
  whose head says how it is kept.
