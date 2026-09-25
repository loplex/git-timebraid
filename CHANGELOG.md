# Changelog

What changed between releases, and what a run that worked before may do differently after an
upgrade. The specification of the construction itself lives in
[doc/how-it-works.md](doc/how-it-works.md); this file only records the differences.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/), and the
project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html). One section is kept
beyond Keep a Changelog's six: `### Upgrading from <version>`, first, for what the changes below do
to a command line written for that release.

## [Unreleased]

### Upgrading from 0.1.0

Four changes alter what a command line written for 0.1.0 does:

- `.git` as a destination, `repo=.git`, is refused.\
  0.1.0 accepted it, and wrote a tree that git will not check out.

- A shallow or a partial clone is refused as an input, on a dry run too.\
  0.1.0 planned either on a dry run, and braided a shallow one given last, or alone, as the part
  of its history it held.

- An input that is the output, as in `-o merged.git merged.git …`, is refused, and so is an
  `-o x/.git` inside a bare input `x`.\
  0.1.0 passed the first on a dry run and under `--force` wrote the braid into the input itself;
  the second it ran without `--force`, as a new repository inside `x` that git then opens for `x`.

- `--dry-run` refuses an `-o` the run could not write into: one that is not a directory, is not
  empty without `--force`, or cannot be created.\
  0.1.0's dry run passed it, and the run stopped on it only once the inputs were read and the braid
  planned.\
  Beside a remote input, one that could not be created stopped a dry run and a run alike, earlier,
  on the directory for the clones.

### Fixed

- **An `-o` that exists and is not a directory is refused.**\
  0.1.0 took a file there for an empty directory and failed inside JGit with a stack trace, with
  `--force` or without.\
  The refusal names the path, and the file is left as it was.

- **`.git` is refused as a destination.**\
  0.1.0 accepted `repo=.git`, and wrote a tree that git will not check out and `git fsck` warns
  about.\
  Still not covered: `.git.`, `git~1`, and the unicode look-alikes git also guards against.

- **User-facing messages are ASCII.**\
  Six of them wrote an em dash.\
  A Windows console's code page cannot encode it, so they write `--` now.

- **Shallow and partial clones are refused, as the README said they were.**\
  0.1.0 planned either without complaint.\
  A shallow input then broke the fetch of another input, or, given after it, was braided as the
  part of its history it held; a partial clone broke the fetch. Either could leave a half-written
  output directory behind.\
  An input is now refused when it is opened, before the output is created, and the refusal says how
  to complete it.

- **An `-o` that cannot be created, and a `--plan-out` that cannot be written, are reported as
  messages.**\
  In 0.1.0 either ended in a Java stack trace, the second only once the output was written, so a
  run that had written its braid still exited 1.\
  The message names the path, and `--plan-out` is checked before the output is created.

- **An input that is the output is refused.**\
  0.1.0 read and wrote it at once: a dry run passed it, and under `--force` the run fetched the
  repository into itself and wrote the braid on top of its own history.\
  The two are compared by the directories they take up, as the filesystem resolves them: a
  location, the git directory it holds (the one a `.git` file names in a linked worktree or a
  submodule included), the common directory a linked worktree shares with its main repository, and
  the directory around a `.git`, a bare repository's included. So a working tree and its git
  directory count as one, and so does a symlink to either, and an `-o x/.git` inside a bare `x`,
  which 0.1.0 ran without `--force` and git then opens for `x`, is refused too. A `file://` URL is a
  remote input and is not compared: `--force` with `-o x` and `file://…/x` still writes the braid
  into `x`.

- **Two inputs that both describe a submodule with a blank name are refused with advice that
  fits.**\
  0.1.0 said to give one of them another subdirectory, which does not part them: a blank name is
  never prefixed.

## [0.1.0] - 2026-09-08

First release.

[Unreleased]: https://github.com/loplex/git-timebraid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/loplex/git-timebraid/releases/tag/v0.1.0
