# Installing git-timebraid

*Getting a runnable `git-timebraid`.*

- [README](../README.md) — what the tool is, and why the braid is shaped the way it is.
- [usage.md](usage.md) — what to type, and what the output holds when the run finishes.

---

## Install

Download the archive for your platform from
[Releases](https://github.com/loplex/git-timebraid/releases). It carries its own JVM, so there is
nothing else to install:

```bash
mkdir -p ~/opt
tar xzf git-timebraid-<version>-linux-x64.tar.gz -C ~/opt
export PATH="$HOME/opt/git-timebraid-<version>-linux-x64/bin:$PATH"

git-timebraid --help
git timebraid -hh                    # git runs any git-<name> it finds on PATH
```

There is one for Linux and macOS on x64 and aarch64, and one for Windows on x64.

`-hh` rather than `--help` in the last line, and that is not a typo. Git handles `--help` on a
subcommand itself: rather than running the program it looks for its documentation — a
`git-timebraid(1)` manual page, or on Git for Windows an HTML page, as
[below](#git-for-windows-reads-the-html-page).

The archive ships the manual page under `share/man/man1`, and it is found there while `MANPATH` is
unset or has an empty entry — a leading or trailing `:`, or a `::` — because `man` then adds the
search path it derives from the `PATH` the `export` line sets. A `MANPATH` your shell sets otherwise
is the whole search path: add the archive's `share/man` to it, or leave an empty entry in it. Where
the launcher is linked into a directory already on `PATH` instead of the archive's `bin` being added
to it, link `share/man/man1/git-timebraid.1` into the matching place beside that directory as well
— `~/share/man/man1/` for a link in `~/bin/` — because `man` looks next to each `PATH` entry, not
next to where a link points.

So `git timebraid --help` opens the manual, while `-h` and `-hh` are this program's own help —
each option's first line, and every option with its defaults. Run as `git-timebraid`, all three
reach the program and mean what they say.

Where git finds no page, or has no viewer to hand it to, `--help` reports that instead of running
anything, and the message is git's or the viewer's rather than this program's. `-hh` is the spelling
that reaches the program on any platform, which is why it is the one written above.

`git` on `PATH` is the only other thing, and only for three jobs: cloning a remote input and
refreshing that clone on a later run, recording the inputs as remotes under `--keep-remotes`, and
checking out a `--no-bare` output. Reading the inputs, transferring their objects and writing the
braid all happen in-process.

Each release carries a `SHA256SUMS`; `sha256sum --check --ignore-missing SHA256SUMS` verifies what
you downloaded against it.

### Git for Windows reads the HTML page

Git for Windows shows help as HTML — `html` is its default `help.format` — so
`git timebraid --help` there looks for `git-timebraid.html`, and only in the one directory
`git --html-path` prints, inside git's own installation. The archive carries the page at
`share/doc/git-doc/git-timebraid.html`, and no place in an unpacked archive is found by itself:
copy it into that directory, from Git Bash in the unpacked archive:

```bash
cp share/doc/git-doc/git-timebraid.html "$(git --html-path)/"
```

Under `C:\Program Files` that takes a Git Bash run as administrator. An update of Git for Windows
can replace the directory, and the copy with it; copy it again if `--help` stops finding it. Until
the page is there, `git timebraid --help` fails with git's `documentation file not found`.

## The portable archive, if you already have Java

One archive for every platform, without a JVM inside it:

```bash
mkdir -p ~/opt
tar xzf git-timebraid-<version>.tar.gz -C ~/opt
export PATH="$HOME/opt/git-timebraid-<version>/bin:$PATH"
```

- It wants **Java 17 or newer** on the machine.
- Roughly 55 MB unpacked for a platform archive on Linux, against the 9 MB of the self-contained jar
  this one carries.
- Unlike a platform archive it is not tied to the machine that built it, which is what makes it the
  one to put in an image or a shared directory.

## The launcher, and which JVM it picks

Both archives are `bin/git-timebraid` (plus `git-timebraid.bat` for Windows) beside
`lib/git-timebraid.jar`. The launcher finds the jar relative to itself, through symlinks, so linking
`bin/git-timebraid` into a directory already on `PATH` works too.

The JVM it runs the jar on is picked in this order, first hit wins:

1. `TIMEBRAID_JAVA` — names a java binary outright
2. `runtime/` beside the launcher — what a platform archive carries
3. `JAVA_HOME`
4. `java` from `PATH`

So `TIMEBRAID_JAVA=/path/to/java` is how a platform archive is made to run on your own JVM rather
than the one it brought.

`JAVA_OPTS` goes to the JVM, which is where a larger heap belongs for a large history:

```bash
JAVA_OPTS=-Xmx4g git-timebraid -o /tmp/merged ~/repos/backend.git ~/repos/webui.git
```

The jar is self-contained — every dependency is shaded in — so running it without the launcher works
as well:

```bash
java -jar lib/git-timebraid.jar --help
```

---

## Building from source

**You do not need this to run the tool.** The archives above are this same build, produced by CI and
smoke-tested on every platform it publishes. Build it yourself to work on git-timebraid, or to get
an archive for a platform the releases do not cover.

```bash
mvn -q package                       # builds target/git-timebraid-<version>.tar.gz (and .zip)
```

That is the portable archive, unpacked the same way, plus `target/git-timebraid.jar` for running the
jar straight out of the build. The release archives also carry the manual page as HTML, which
`.github/scripts/render-man-html.sh` renders before they are packaged, and which needs groff and
python3; an archive packaged without running it first has no `share/doc/git-doc/`, which only
[Git for Windows](#git-for-windows-reads-the-html-page) misses.

**`./git-timebraid` in the repo root runs that jar**, so a clone needs no install to be driven. It
is what the [worked examples](examples/README.md) are written in terms of, and it reads
`TIMEBRAID_JAVA` and `JAVA_OPTS` the way the shipped launcher does.

### Building an archive that carries its own JVM

`-Pbundled-runtime` builds the platform archive, trimming a JVM with `jlink`:

```bash
mvn -q -Pbundled-runtime package      # adds git-timebraid-<version>-<os>-<arch>.tar.gz (and .zip)
```

- By default it is the JDK running Maven that gets bundled, so the archive is for the platform you
  build on — hence the platform in its name.
- `-Djlink.jmods=<dir>` links the `jmods` of another JDK instead, which builds another platform's
  archive: the Windows one is built on Linux this way. The `jmods` have to be from the same JDK
  version as the one running Maven, and `-Ddist.os` and `-Ddist.arch` name the platform it is for:

  ```bash
  mvn -q -Pbundled-runtime -Djlink.jmods=<windows-jdk>/jmods -Ddist.os=windows -Ddist.arch=x64 package
  ```
- `--compress=zip-6` needs JDK 21 or newer; on JDK 17 build it with `-Djlink.compress=2`.
