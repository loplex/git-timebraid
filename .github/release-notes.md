## Install

Every archive holds `bin/git-timebraid` (and `git-timebraid.bat` for Windows) beside
`lib/git-timebraid.jar`. Unpack it and put `bin/` on `PATH` — `git timebraid …` then works as well as
`git-timebraid …`, because git runs any `git-<name>` it finds there. `--help` is the exception: git
takes `git timebraid --help` for itself and looks for the command's page in git's documentation, a
manual page or, where git is set to show HTML help, an HTML one; so the program's own help is
`git-timebraid --help`.

| Asset                                                 | What it is                                                                                           |
|-------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `git-timebraid-<version>.tar.gz` / `.zip`             | Runs anywhere; needs Java 17 or newer                                                                |
| `git-timebraid-<version>-<os>-<arch>.tar.gz` / `.zip` | Carries its own trimmed JVM, for machines without one. Larger, and only for the platform in its name |
| `git-timebraid.jar`                                   | The same program as `java -jar`, dependencies shaded in                                              |

Whichever asset you take, `git` on `PATH` is needed for three jobs only: cloning a remote input and
refreshing that clone on a later run, recording the inputs as remotes under `--keep-remotes`, and
checking out a `--no-bare` output.

Verify a download against `SHA256SUMS`:

```bash
sha256sum --check --ignore-missing SHA256SUMS
```

See the [README](https://github.com/loplex/git-timebraid#readme) for what the tool does and how the
braid is built. What changed in this release is below, taken from the changelog entry for this tag;
the [CHANGELOG](https://github.com/loplex/git-timebraid/blob/main/CHANGELOG.md) carries every
release's, and is the place to read a run of them.
