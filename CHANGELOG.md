# Changelog

What changed between releases, and what a run that worked before may do differently after an
upgrade. The specification of the construction itself lives in
[doc/how-it-works.md](doc/how-it-works.md); this file only records the differences.

## 0.2.0 — unreleased

### Changed

- **`<repo>=<subdir>` is now `<repo>::=<subdir>`.** The input grammar is one rule: everything
  before the last `::` is the location, verbatim, and everything after it is
  `[<name>][=<subdir>]`. Previously each suffix was recognised only before a bare word, which made
  a location holding a `=` or a `::` ambiguous, and the ambiguity would have grown once a
  subdirectory was allowed to hold a `/`. Every location is now expressible: appending a bare `::`
  ends it exactly where it ends. The cost is that the two bare IPv6 spellings —
  `https://[fe80::1]/repo.git` and `git@[::1]:repo.git` — now want a trailing `::`.
- **`-b`/`--branch` no longer carries the tags.** It narrowed which branches were recreated but not
  which commits were loaded, so a run asking for one branch still pulled in whatever the tags could
  reach. It is now shorthand for `--ref refs/heads/<name>`, and the selection applies to branches
  and tags alike. The old behaviour is one pattern away:

      --ref refs/heads/main --ref 'refs/tags/*'

- **`.git` is refused at every segment of a destination**, not only as a whole name. A tree carrying
  an entry of that name is one git declines to check out and `git fsck` reports, so the entry used
  to be written and then be unusable. The exotic spellings git also guards against — `.git.`,
  `git~1`, the unicode look-alikes — are still not covered.
- **User-facing messages are ASCII.** Thirteen of them wrote an em dash between the finding and the
  remedy, and a Windows console takes an OEM code page — cp437 in the US, cp852 here — no member of
  which has that character, so the encoder substituted a `?`. They write `--` now. Forcing the
  streams to UTF-8 instead would have fixed a redirected stream and made the console one worse.

### Added

- **A destination may be a nested path**, so `libs/backend` beside `apps/webui` can be asked for:
  `git-timebraid -o out backend::=libs/backend webui::=apps/webui`. Inputs sharing a prefix share
  the tree for it, and where a nested destination reaches into a directory the repository at the
  output root already has, the two are spliced into one tree.
- **`--ref PATTERN`** — carry over only the refs matching this pattern, over branches and tags
  alike. A glob over full ref names, repeatable; the default is every ref, and the mainline is
  always kept whatever the patterns say.
- **`--splice`** — let one input's destination lie inside another's. The pair is refused by default,
  because the paths alone cannot tell "`libs` and `libs/backend` is a typo" from "…is the layout I
  want". Every splice is checked against every tree before any object is written, `--dry-run`
  included, since a repository's tree differs at every commit and so may a collision.
- **`--scan DIR`** — take the output layout from a directory tree: every repository under `DIR`
  becomes an input, placed in the output where it sits on disk, and `DIR` itself lands at the output
  root when it is a repository. A repository is not descended into. A `<repo>` argument naming one
  of the repositories found corrects that finding rather than adding a second input.
- **`--dissolve-submodules`** — where an input lands exactly on a gitlink of the repository around
  it, the gitlink gives way to the input's own tree and the `[submodule]` section whose path named
  it is left out of the root `.gitmodules`. Opt-in, because the substitution is not a faithful
  expansion: a gitlink names the one commit the superproject pinned, and what takes its place is
  whatever that input had reached at that point of the braid.
- **`doc/examples` covers what the output holds, not only what order it is in.** Nine worked
  examples, each a script that builds the input repositories and a README quoting what the tool
  prints over them: the interleave, nested destinations, `--splice`, `--dissolve-submodules`,
  `--scan` and the ref selection. Three show the refusal beside the result, since there the tool
  declining to guess is half the rule.

### Fixed

- **The environment no longer stands in for an input repository.** `GIT_DIR` satisfied the
  `no git repository at` check on its own, so a path that was no repository at all opened as
  whatever that variable named — silently, under the name that was written on the command line, and
  planned into the output at that name. `GIT_OBJECT_DIRECTORY` and
  `GIT_ALTERNATE_OBJECT_DIRECTORIES` were worse, since nothing overrode them: they applied to
  perfectly good inputs too and read their refs against someone else's object store. A single
  process-wide variable naming one repository cannot be right for a set of inputs, which is the same
  reasoning that strips those variables from every git subprocess.

### Internal

- The planner allocates less per plan: 64.2 MB → 59.4 MB at 159k commits, 260.8 MB → 241.5 MB at
  635k, by keeping the walks' priority queue free of boxed timestamps and boxed indices. **Wall time
  did not move** — this is GC pressure, not speed.
- The release and CI workflows moved onto the current major versions of the GitHub actions they use;
  the hosted runners no longer run the Node.js version the previous ones targeted.
- CI replays `doc/examples` on every run: the fixtures are rebuilt, the invocations the READMEs
  document are re-run, and the output they quote and the plans they track are compared against it.
  The READMEs are the source of truth — nothing is duplicated into the checker, and adding an
  example needs no edit to it.

## 0.1.0 — 2026-09-08

First release.
