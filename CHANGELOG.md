# Changelog

What changed between releases, and what a run that worked before may do differently after an upgrade.\
The specification of the construction itself lives in [doc/how-it-works.md](doc/how-it-works.md);
this file only records the differences.

The format is based on [Keep a Changelog 1.1.0](https://keepachangelog.com/en/1.1.0/),
and the project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).\
One section is kept beyond Keep a Changelog's six:
`### Upgrading from <version>`, first, for what the changes below do to a command line written for that release.

## [Unreleased]

### Upgrading from 0.1.0

Two changes alter what a command line written for 0.1.0 does:

- `.git` as a destination, `repo=.git`, is refused.\
  0.1.0 accepted it, and wrote a tree that git will not check out.

- The jar a build writes is `target/lib/git-timebraid.jar`, not `target/git-timebraid.jar`,
  and a release no longer offers the jar on its own.\
  It runs only beside its dependencies' jars, which an archive's `lib/` and a build's `target/lib/` hold:
  `java -jar lib/git-timebraid.jar` in an unpacked archive works as before.

### Changed

- **The archives carry each dependency as its own jar in `lib/`,
  beside `git-timebraid.jar`, and a release no longer offers the jar on its own.**\
  The program's jar names the others in its manifest, so `java -jar lib/git-timebraid.jar` still runs it,
  and every jar keeps the license files its project shipped in it.\
  `doc/legal/` lists each library with the license it is used under, beside the texts of those licenses;
  NOTICE points there.

### Fixed

- **An `-o` that exists and is not a directory is refused.**\
  0.1.0 took a file there for an empty directory and failed inside JGit with a stack trace, with `--force` or without.\
  The refusal names the path, and the file is left as it was.

- **`.git` is refused as a destination.**\
  0.1.0 accepted `repo=.git`, and wrote a tree that git will not check out and `git fsck` warns about.\
  Still not covered: `.git.`, `git~1`, and the unicode look-alikes git also guards against.

- **User-facing messages are ASCII.**\
  Six of them wrote an em dash.\
  A Windows console's code page cannot encode it, so they write `--` now.

- **An `-o` that cannot be created, and a `--plan-out` that cannot be written, are reported as messages.**\
  In 0.1.0 either ended in a Java stack trace,
  the second only once the output was written, so a run that had written its braid still exited 1.\
  The message names the path, and `--plan-out` is checked before the output is created.

- **Two inputs that both describe a submodule with a blank name are refused with advice that fits.**\
  0.1.0 said to give one of them another subdirectory, which does not part them: a blank name is never prefixed.

## [0.1.0] - 2026-09-08

First release.

[Unreleased]: https://github.com/loplex/git-timebraid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/loplex/git-timebraid/releases/tag/v0.1.0
