## Install

Every archive holds `bin/git-timebraid` (and `git-timebraid.bat` for Windows) beside
`lib/git-timebraid.jar`. Unpack it and put `bin/` on `PATH` — `git timebraid …` then works as well as
`git-timebraid …`, because git runs any `git-<name>` it finds there.

| Asset                                                 | What it is                                                                                           |
|-------------------------------------------------------|------------------------------------------------------------------------------------------------------|
| `git-timebraid-<version>.tar.gz` / `.zip`             | Runs anywhere; needs Java 17 or newer                                                                |
| `git-timebraid-<version>-<os>-<arch>.tar.gz` / `.zip` | Carries its own trimmed JVM, for machines without one. Larger, and only for the platform in its name |
| `git-timebraid.jar`                                   | The same program as `java -jar`, dependencies shaded in                                              |

`git` has to be on `PATH` either way: it is what clones and fetches the inputs.

Verify a download against `SHA256SUMS`:

```
sha256sum --check --ignore-missing SHA256SUMS
```

See the [README](https://github.com/loplex/git-timebraid#readme) for what the tool does and how the
braid is built. What changed in this release is below, taken from the changelog entry for this tag;
the [CHANGELOG](https://github.com/loplex/git-timebraid/blob/main/CHANGELOG.md) carries every
release's, and is the place to read a run of them.
