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
git timebraid -h                     # the same through git, which keeps --help for its own page
```

There is one for Linux and macOS on x64 and aarch64, and one for Windows on x64.

`git` on `PATH` is the only other thing, and only for three jobs: cloning a remote input and
refreshing that clone on a later run, recording the inputs as remotes under `--keep-remotes`, and
checking out a `--no-bare` output. Reading the inputs, transferring their objects and writing the
braid all happen in-process.

Each release carries a `SHA256SUMS`; `sha256sum --check --ignore-missing SHA256SUMS` verifies what
you downloaded against it.

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
jar straight out of the build.

**`./git-timebraid` in the repo root runs that jar**, so a clone needs no install to be driven. It
is what the [worked examples](examples/README.md) are written in terms of, and it reads
`TIMEBRAID_JAVA` and `JAVA_OPTS` the way the shipped launcher does.

### Building an archive that carries its own JVM

`-Pbundled-runtime` builds the platform archive, trimming a JVM with `jlink`:

```bash
mvn -q -Pbundled-runtime package      # adds git-timebraid-<version>-<os>-<arch>.tar.gz (and .zip)
```

- It is the JDK running Maven that gets bundled, so the archive is for the platform you build on —
  hence the platform in its name.
- `--compress=zip-6` needs JDK 21 or newer; on JDK 17 build it with `-Djlink.compress=2`.
