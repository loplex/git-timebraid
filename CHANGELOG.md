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

One change alters what a command line written for 0.1.0 does:

- `.git` as a destination, `repo=.git`, is refused.\
  0.1.0 accepted it, and wrote a tree that git will not check out.

### Fixed

- **`.git` is refused as a destination.**\
  0.1.0 accepted `repo=.git`, and wrote a tree that git will not check out and `git fsck` warns
  about.\
  Still not covered: `.git.`, `git~1`, and the unicode look-alikes git also guards against.

- **User-facing messages are ASCII.**\
  Six of them wrote an em dash.\
  A Windows console's code page cannot encode it, so they write `--` now.

- **Two inputs that both describe a submodule with a blank name are refused with advice that
  fits.**\
  0.1.0 said to give one of them another subdirectory, which does not part them: a blank name is
  never prefixed.

## [0.1.0] - 2026-09-08

First release.

[Unreleased]: https://github.com/loplex/git-timebraid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/loplex/git-timebraid/releases/tag/v0.1.0
