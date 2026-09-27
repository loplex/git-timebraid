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

Sixteen changes alter what a command line written for 0.1.0 does:

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

- `GIT_DIR` and its kin no longer decide which repository an input is read from.\
  0.1.0 read an input named by its working tree, or by a path holding no repository, from the one
  `GIT_DIR` named.

- A remote input whose name finds a clone of another URL under `.timebraid-clones/` is refused.\
  0.1.0 refreshed that clone and braided it in place of the URL given.

- A ref name with a component ending in `.lock` is refused, from `--tag-prefix '{repo}.lock/'` or
  an input named `x.lock` alike.\
  0.1.0 wrote such refs where git does not see them.

- A repository name git would not accept inside a ref, `my repo` from `/path/my repo` among them,
  is refused.\
  0.1.0 braided such an input where no ref carried the name: no tag, no branch shared with another
  input, no `--keep-remotes`.

- `--keep-remotes` beside a `-b` that leaves the mainline out mirrors each input's mainline too.\
  0.1.0 wrote a remote-tracking ref only for the branches `-b` took, beside every tag.

- `--keep-remotes` refuses to mirror both a branch `tags/<name>` that `-b` took and a tag `<name>`
  of one input.\
  0.1.0 mirrored both to one name, and kept the tag's.

- A `--tag-prefix` that does not keep `{repo}` apart from the tag name, `''` and any without
  `{repo}` among them, refuses two inputs whose tags meet on a name.\
  0.1.0 kept the tag of whichever input came last; a template that keeps `{repo}` apart from the
  tag name, as `{repo}/` does, keeps both.

- A branch one input alone has, under the name another input's shared branch is qualified to,
  refuses the run.\
  0.1.0 kept whichever of the two was written last; leave one of them out with `-b`.

- A shared branch qualified onto the braid's own name refuses the run: `x` in two inputs beside a
  `--mainline-branch backend/x`.\
  0.1.0 wrote input backend's `x` over the braid's branch, so the output's mainline could point at
  that `x` rather than at the braid; leave that branch out with `-b`.

- `--interleave-ref` brings the whole ancestry of what it names into scope.\
  A ref sitting on a mainline is no longer a no-op, and one off the mainlines reaches past the first
  mainline commit it meets; either can move the braid.

- `--keep-remotes` into an output that already records a remote of an input's name keeps it where
  its URL is the input's, as on a rerun, and refuses it under another URL, on a dry run too.\
  0.1.0 failed on either at `git remote add`, with the braid already written; its dry run passed
  both.

- `--dry-run` refuses a collision with an entry of the `--root-repo`, as the run itself does.\
  0.1.0 found it only while writing, so a dry run passed the plan.

### Fixed

- **An `-o` that exists and is not a directory is refused.**\
  0.1.0 took a file there for an empty directory and failed inside JGit with a stack trace, with
  `--force` or without.\
  The refusal names the path, and the file is left as it was.

- **A mistyped option is refused with the option probably meant, and `--` ends the options.**\
  0.1.0 answered `--dryrun` with "put options before the input repositories", though an option may
  stand anywhere, and refused an input opening with `-` even after `--`.\
  The refusal now reads "no such option --dryrun. Did you mean --dry-run?", and in `-- -dash` the
  `-dash` is an input.

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

- **A git that cannot be started is reported as a message.**\
  `--no-bare`, `--keep-remotes` and a remote input run git as a subprocess, and with none on `PATH`
  the run ended in a Java stack trace.

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

- **The environment no longer stands in for an input repository.**\
  `GIT_DIR` replaced every input that was not itself a git directory, a working tree included.\
  A path that was no repository at all passed the `no git repository at` check because of it.\
  `GIT_COMMON_DIR`, `GIT_OBJECT_DIRECTORY` and `GIT_ALTERNATE_OBJECT_DIRECTORIES` reached a bare
  input as well: nothing overrode them, so they read its refs or its objects from another
  repository.

- **A missing location now names the suffix read off its end.**\
  `/some/path::libs` used to report only `/some/path` when nothing is there, and so did
  `/some/path=libs`.\
  The reading is unchanged, and which one was meant is still not guessed at.\
  The run fails on the location either way, so the refusal says what it cut off.

- **A repository name two inputs derive is refused naming every location that derives it.**\
  The refusal listed every input's name, the repeated one among them, and located none of them.

- **`--keep-remotes` mirrors each input's mainline whether `-b` took it or not.**\
  A run narrowed away from the mainline, `-b feature` for one, left its original commits in the
  output with nothing naming them.\
  They were fetched and rewritten under the output's own branch, and `git gc` pruned them once
  they were older than its grace period for unreachable objects, two weeks by default.

- **A branch `-b` took and a tag of one input meeting on one `--keep-remotes` mirror name are
  refused.**\
  A branch literally called `tags/v1.0` mirrors to the name the tag `v1.0` does.\
  0.1.0 kept the tag's mirror, leaving the branch's originals unnamed, and the closing report
  counted both.\
  The refusal names both refs.

- **A logged subprocess names the repository it ran in.**\
  `git fetch --prune origin` left out which clone it refreshed, and a failure reported it the same
  way.\
  Both are written with the `-C` the command would need to run anywhere else.

- **Two inputs' tags meeting on one name are refused, not resolved by whichever came last.**\
  A `--tag-prefix` that does not keep `{repo}` apart from the tag name, `''` and any without
  `{repo}` among them, let one tag name from two inputs meet.\
  The output kept the tag of the input given last, and the closing report counted both.\
  The refusal names both inputs and the tag; a template that keeps `{repo}` apart from the tag
  name, as `{repo}/` does, keeps both.

- **Two inputs' branches meeting on one name are refused, not resolved by whichever came last.**\
  In 0.1.0 a branch one input alone had kept its own name, which could be the one another input's
  shared branch was qualified to: `A/release` in C, beside a `release` that A and B both had.\
  The output kept the branch of the input written last, and the closing report counted both.\
  The refusal names both inputs and the branch.

- **A shared branch qualified onto the braid's own name is refused, not written over it.**\
  In 0.1.0 a branch two inputs had was qualified as `<repo>/<branch>`, which could be the
  mainline's own name: `backend/x` for backend's `x` beside a `--mainline-branch backend/x`.\
  The branch was written over the braid's, so the output's mainline could point at backend's `x`.\
  The refusal names the braid and the input, and says to leave that branch out with `-b`.

- **`--interleave-ref` brings the whole ancestry of what it names into scope.**\
  A ref sitting on a mainline was skipped whole, so naming a mainline branch, or a release tag on
  one, did nothing.\
  From any other ref the walk stopped where it met a mainline, leaving out what the earlier merges
  on that mainline had merged, so a bare star was not the pass over the whole graph it was
  documented to be, as it now is while `-b` leaves none of the mainlines out.\
  A side branch timestamped after the merge that took it in can now hold that merge back, and the
  mainline after it, whenever a ref above the merge is opted in: that is what opting in asks for.

- **The count `--verbose` gives for `--interleave-ref` says commits, which is what it counts.**\
  0.1.0 printed "2 refs opted into the interleave" for three refs on two commits: the count is of
  the commits the matched refs name, each once.

- **A dry run without `-o` removes the clones it made.**\
  Each such run cloned every URL input into a new temporary directory and left it there.

- **A clone that another URL left under `.timebraid-clones/` is refused, not refreshed.**\
  A clone is found by the input's name, and two locations can derive one: 0.1.0 refreshed the
  first one's clone and braided it as the second.\
  The refusal names both URLs and the directory to remove.

- **A ref name git refuses is no longer written.**\
  0.1.0 asked JGit, which lets a component ending in `.lock` through where git does not:
  `--tag-prefix '{repo}.lock/'` wrote tags that `git tag` does not list, and the closing report
  counted them.\
  Such a name is refused now, by git's rules.

- **A name git would not accept inside a ref is refused when it is read.**\
  0.1.0 let one through, `my repo` from the location `/path/my repo` among them.\
  An input whose name a ref carried (a tag, a branch shared with another input, a `--keep-remotes`
  mirror) then failed at the write of that ref, the braid already written; one no ref carried went
  through.

- **A rerun with `--keep-remotes` into its own output keeps the remotes it recorded.**\
  0.1.0 tried to add them again and failed, `remote … already exists`, with the braid already
  written.\
  A remote of an input's name under another URL is refused instead, before anything is written into
  the output, on a dry run too.

- **A collision with an entry of the `--root-repo` is refused before the output is created.**\
  0.1.0 found it only while writing, after creating the output and fetching every input into it, so
  a dry run passed the plan and the run left a half-written output behind. `--dry-run` refuses it
  now too.

## [0.1.0] - 2026-09-08

First release.

[Unreleased]: https://github.com/loplex/git-timebraid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/loplex/git-timebraid/releases/tag/v0.1.0
