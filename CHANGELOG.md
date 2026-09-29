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

Thirty changes alter what a command line written for 0.1.0 does:

- `repo=subdir` is now `repo::subdir=<name>`.\
  Written as `repo::subdir`, the input is also named after the subdirectory, so its tags change with
  it, as does every other ref name built from its name.\
  Written with `=<name>`, those stay, and its commit subjects take the name, as the bullet on them
  below says.

- `repo::name=subdir` now reads as `repo::subdir=name`.\
  **It does not fail:** an argument giving both still parses, and places the input somewhere else.

- A location holding a `::` of its own, an IPv6 URL for one, now ends with a bare `::`:
  `https://[fe80::1]/repo.git::`.

- `-b` takes a pattern, as `--ref refs/heads/<value>` does, rather than a branch's exact name.\
  `-b main` no longer carries the tags: add `--ref 'refs/tags/*'` for the old behaviour.\
  A `*` matches any run of characters, where 0.1.0 looked for a branch with a `*` in its name.\
  An `<input>::` scope, a `^` that subtracts and a `:<destination>` around the branch mean what
  they mean in a ref pattern, and a value they do not fit is refused.

- Every branch is now qualified as `<repo>/<branch>`, not only one two inputs share.\
  One that then sits under the braid's own branch refuses the run: input `main`'s `x`, qualified as
  `main/x`, beside the mainline `main`.\
  `--branch-prefix ''` gives the plain names back, and refuses a collision rather than resolving it.

- `--mainline-branch` given more than once without an `<input>::` scope is refused.\
  0.1.0 took the last value given.

- An `--interleave-ref` value holding a `:` after its scope, scoped to a name that is no input, or
  empty, is refused.\
  0.1.0 took each for a pattern, one that matched no ref.

- A `^` in front of an `--interleave-ref` pattern subtracts, and an input given nothing but
  subtractions is refused; a `^` anywhere else in a pattern is refused.\
  0.1.0 took each for a pattern, one that matched no ref: `--interleave-ref '*'` beside
  `--interleave-ref '^refs/heads/main'` opted `main` in.

- A space in a `-b` or an `--interleave-ref` value separates two values, and a value of nothing but
  whitespace is refused.\
  0.1.0 took the whole value for one name or pattern, which matched nothing.

- `.git` as a destination, written `repo=.git` in 0.1.0 and `repo::.git=<name>` now, is refused.\
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

- A remote input's clone under `.timebraid-clones/` is named by its URL, not by the input's name.\
  0.1.0's clones there are not reused, and the first run downloads each URL again.\
  Two URLs deriving one name no longer share a clone: 0.1.0 refreshed the first one's and braided
  it in place of the URL given.

- A ref name with a component ending in `.lock` is refused, from `--tag-prefix '{repo}.lock/'` or
  an input named `x.lock` alike.\
  0.1.0 wrote such refs where git does not see them.

- A repository name git would not accept inside a ref, `my repo` from `/path/my repo` among them,
  is refused.\
  0.1.0 braided such an input where no ref carried the name: no tag, no branch shared with another
  input, no `--keep-remotes`.

- `--keep-remotes` beside a `-b` that leaves the mainline out mirrors each input's mainline too.\
  0.1.0 wrote a remote-tracking ref only for the branches `-b` took, beside every tag.

- `--keep-remotes` refuses to mirror both a branch `tags/<name>` and a tag `<name>` of one input.\
  0.1.0 kept the tag's mirror and left the branch with none, whether `-b` took it or not.

- A `--tag-prefix` that does not keep `{repo}` apart from the tag name, `''` and any without
  `{repo}` among them, refuses two inputs whose tags meet on a name.\
  0.1.0 kept the tag of whichever input came last; a template that keeps `{repo}` apart from the
  tag name, as `{repo}/` does, keeps both.

- A branch qualified onto the braid's own name refuses the run: backend's `x` beside a
  `--mainline-branch backend/x`.\
  0.1.0 wrote it over the braid's branch where another input had an `x` too, so the output's
  mainline could point at that `x` rather than at the braid; give `--branch-prefix` a template, or
  the input a name, that moves its branches off the braid's, or narrow the run.

- Commit subjects are prefixed with the input's name, not its destination.

- `--interleave-ref` brings the whole ancestry of what it names into scope.\
  A ref sitting on a mainline is no longer a no-op, and one off the mainlines reaches past the first
  mainline commit it meets; either can move the braid.

- `--keep-remotes` into an output that already records a remote of an input's name keeps it where
  its URL is the input's, as on a rerun, and refuses it under another URL, on a dry run too.\
  0.1.0 failed on either at `git remote add`, with the braid already written; its dry run passed
  both.

- `--dry-run` refuses three things 0.1.0 refused only while writing: a collision with an entry of
  the `--root-repo`, a ref name that is a directory of another, and a ref name JGit would not
  write.\
  0.1.0's dry run passed such a plan. The refusals of ref names new in this release come before
  the output as well, a dry run's included.

- `--mainline-branch` naming a revision rather than a branch, as `main~1` or `main^` does, is
  refused.\
  0.1.0 braided along that commit on a dry run, and the run itself failed after creating the
  output.

- A ref pattern that cannot begin with `refs/` is refused, and so is one under `refs/notes/`.\
  `--interleave-ref wip` matched nothing in 0.1.0; it is now an error, and so is any short name.\
  `--interleave-ref 'refs/changes/*'` matched nothing too, 0.1.0 reading only branches and tags;
  a namespace a pattern names is now read, and its refs interleave as a branch's do.

- `-h` prints less than it did, and less than `--help`.\
  Every option is still listed, with the first line of its entry; the qualifiers, the defaults, and
  all but the first paragraph of the text above the options and above each group of them are now
  `--help` only, or `-hh` where git handles `--help` itself.

- One local repository given as two arguments is refused, however the second spells its
  location.\
  0.1.0 braided it as two inputs, each against the other, wherever the two arguments gave it
  different names.

- `--tag-prefix` substitutes `{subdir}` as well as `{repo}`.\
  0.1.0 substituted only `{repo}` there, and wrote a `{subdir}` into the tag's name as it stood.

### Changed

- **`<path-or-url>=<subdir>` is now `<path-or-url>::<subdir>`.**\
  Everything before the last `::` is the location, verbatim.\
  Everything after it is `[<subdir>][=<name>]`.\
  A location holding a `::` of its own ends with a bare one.\
  An IPv6 URL therefore needs one: `https://[fe80::1]/repo.git::`, `git@[::1]:repo.git::`.

- **The suffix gives the destination, and the name follows it.**\
  It used to be the other way round.\
  `::libs/core` places an input and calls it `core`.\
  `::libs/core=legacy` sets the two apart.

- **A name is a label, and two inputs may share one; the destination tells them apart.**\
  0.1.0 refused two inputs of one name, which is what two directories called `core` derive.\
  Two inputs placed at one destination are refused instead, on the command line, naming both and
  offering each a subdirectory of its own.\
  A reference to a shared name — a pattern's `<input>::`, `--root-repo` — is refused as naming
  both, and so are two inputs `--keep-remotes` would add as one remote.\
  Two of one name meeting on a ref name are refused naming `{subdir}`, which keeps them apart
  where `{repo}` cannot.

- **Neither the destination nor the name may be written with a `:` or a `=`.**\
  Both are refused rather than escaped.\
  For the name the `:` costs nothing: git refuses one in a ref name, and the name becomes a tag
  prefix.\
  The `=` is what separates the subdirectory from the name, so it is refused in both although git
  accepts one in a ref; a name derived from the location keeps a `=` its last segment holds.

- **`--subject-prefix` defaults to `{repo}: `, not `{subdir}: `.**\
  A destination can be nested arbitrarily deep.\
  The name is one segment.

- **`--tag-prefix` substitutes `{subdir}`, where the input lands, beside `{repo}`.**\
  As `--subject-prefix` already did.\
  The input at the output root gives its name there.

- **The branch qualifier applies to every branch, not only a shared one.**\
  It used to go on only where two inputs had used the name.\
  So what a branch was called depended on what the other inputs called theirs: adding an input
  that had a branch of the same name renamed this one's.\
  An output ref name now follows from the input it came from, and from nothing else the run did.\
  `--branch-prefix ''` asks for the plain names; two inputs meeting on one is refused, naming both.\
  `--tag-prefix ''` says the same thing for tags; before, a collision there was resolved by
  whichever input came last.

- **`-b`/`--branch` no longer carries the tags.**\
  It narrowed the branches but not the tags, which were all read.\
  A run asking for one branch still pulled in whatever the tags could reach.\
  It is now shorthand for `--ref refs/heads/<name>`, and naming any ref leaves out the rest.\
  The old behaviour is one pattern away: `--ref refs/heads/main --ref 'refs/tags/*'`.

- **`--help` groups its options by the decision they belong to, one line per default.**\
  0.1.0 listed every option in one ungrouped run.\
  Syntax several options share is stated once, under their group's heading: the options taking a
  ref pattern carry `<ref-pattern>` as their metavar, and their group defines it.\
  What an option *means* is left to `doc/usage.md`, which the epilog points at; an entry says
  enough to recognise a flag and gives its default.

- **`-v`/`--verbose` covers the in-process work, not only the subprocesses.**\
  Four operations shell out to git; the transfer and every object and ref are JGit.\
  A merge of local inputs into a bare output starts none of the four itself, and the flag printed
  nothing.\
  It now gives the transfer and each ref written as the `git` command it is the equivalent of.\
  The commit-by-commit writing stays out: it is not one command, and `--plan-out` already dumps it.

- **`-h` is no longer a synonym for `--help`.**\
  It prints every option with its entry cut to the first line — what the option does, without the
  qualifiers and the defaults under it.\
  The text above the options, and above each group of them, keeps its first paragraph.\
  Nothing is left out of the list, so no flag becomes harder to find; `git fetch -h` is the same
  split.\
  `--help` is unchanged, and `-hh` is a second name for it.

- **A flag that can be turned off is written `--[no-]bare`, not `--bare / --no-bare`.**\
  That is how git writes one, and it is a line shorter each time.

### Added

- **`--notes`** — carry over every input's `refs/notes/`, rekeyed onto the commits the run writes.\
  A note is filed under the sha of what it annotates, and a merge gives every commit a new one, so a
  note carried over unchanged would be attached to nothing.\
  `--notes-prefix` qualifies the refs, `{repo}/` by default: an input with notes usually has
  `refs/notes/commits`.\
  It substitutes `{repo}` and `{subdir}`, as every template does.\
  A note on an object the run did not write is skipped, and the closing report says how many.\
  The history of a notes ref is not carried over — the output's is one commit, keeping the input's
  author, committer and message.

- **`--lightweight-tags`** — recreate every annotated tag as a lightweight one.\
  The ref lands at the same commit and no tag object is written.\
  For a run that wants the ref names without the tagger, date and message of each release.\
  Default unchanged: an annotated tag stays annotated.

- **A ref pattern is a git refspec, and carries its right half.**\
  `--ref 'legacy::refs/heads/*:refs/tags/'`; a value git takes as a refspec means the same here.\
  The prefix rules are the default naming, and a destination overrides exactly the part of the name
  it spells out — nothing, the namespace, or all of it.\
  A destination holding a `*` substitutes what the pattern matched, so
  `backend::refs/legacy/*:refs/archived_*` writes `refs/archived_alpha`, and no prefix goes near
  it.\
  `refs/tags/` and `refs/heads/` may be given as a bare namespace, handing the rest to
  `--tag-prefix` or `--branch-prefix`.\
  `{repo}` and `{subdir}` are substituted, which is what makes an unscoped destination safe.\
  A destination meeting another input's ref is refused naming both, and under `--keep-remotes` so
  is one under an input's `refs/remotes/<name>/`, where a pruning fetch would delete it.\
  One under `refs/timebraid-fetch/` is refused as well: the run parks the refs it fetches there,
  and deletes everything under it once the braid is written.\
  That is how forty dead branches are kept for the record without being kept as branches — and
  without dropping the commits only they reach.\
  Available on `-b`, `--ref` and `--label-ref`, the three that write refs; refused on
  `--interleave-ref`, which writes none.\
  Unlike git's fetch, a destination may be a namespace and may hold `{repo}` and `{subdir}`, a
  pattern with stars may go without a destination, which git's fetch allows only in a negative
  refspec, or name one ref as its destination, and there is no `+`, no empty pattern or destination
  and no short name.

- **A namespace beyond `refs/heads/` and `refs/tags/` is read when a pattern names it.**\
  A Gerrit `refs/changes/`, a forge's `refs/pull/`, the branches an ordinary clone keeps under
  `refs/remotes/origin/`.\
  The destination is required there, nothing else being able to name the result.\
  A bare `*` and a pattern under `refs/` still mean every branch and every tag, so nothing arrives
  unasked.\
  This is what lets an ordinary clone contribute more than the one branch it has checked out, its
  tags named beside them since naming refs narrows the input:
  `--ref 'clone::refs/remotes/origin/*:refs/heads/{repo}/* clone::refs/tags/*'`.\
  A symbolic ref such as `refs/remotes/origin/HEAD` is skipped.\
  A pattern aimed at `refs/notes/` is still refused, naming `--notes`.

- **`--branch-prefix TEMPLATE`** — the qualifier on every recreated branch.\
  `{repo}` and `{subdir}` are substituted; the default `{repo}/` matches what `--tag-prefix` does
  for tags.\
  An empty value asks for the plain names, and refuses two inputs meeting on one.

- **`--provenance-trailer TEMPLATE`** — the line `--provenance` writes.\
  `{repo}`, `{subdir}`, `{commit}` and `{parents}` are substituted; the default is unchanged.\
  Leaving out `{commit}` or `{parents}` keeps the trailer and gives up what makes it checkable.

- **A destination may be a nested path.**\
  `git-timebraid -o out backend::libs/backend webui::apps/webui`\
  Inputs sharing a prefix share the tree for it.

- **`--ref PATTERN`** — carry over the refs matching this glob, or with `^` leave them out
  (repeatable).\
  Branches and tags alike.\
  The default is every branch and tag, and the mainline is kept whatever the patterns say.

- **Every ref pattern may name one input**, as `<input>::<refspec>`.\
  `--ref 'backend::refs/heads/main' --ref 'webui::refs/heads/release/*'`.\
  Without a scope a pattern speaks for every input, so nothing already written changes.\
  The empty case stays per input: one that no pattern names keeps that option's default.\
  The scope is ended by its first `::`, so a refspec after it keeps git's
  meaning.\
  An `<input>::` naming something that is not an input is refused rather than matching nothing,
  and so is an empty one.

- **One quoted argument may hold several values, separated by spaces.**\
  `--ref 'backend::refs/heads/main webui::refs/heads/release/*'` is the repeated option written
  once, and the two are the same run.\
  It covers `-b`, `--ref`, `--label-ref`, `--interleave-ref` and `--mainline-branch`.\
  Safe because git refuses a space in a ref name, where it accepts `,`, `;` and `|`.\
  The quotes are not optional — an option takes one argument, and the rest would be read as
  input repositories.

- **`--mainline-branch` is repeatable and takes the same `<input>::` scope.**\
  `--mainline-branch 'backend::main' --mainline-branch 'webui::master'` merges two inputs that
  never agreed on a name — which could not be expressed at all before, one branch having had to
  be present in every input.\
  An unscoped value covers the inputs with no scoped one and names the output's branch;
  without one the output takes the first input's.\
  Detection is unchanged: the first of `main`/`master`/`develop` present in every input still
  awaiting one, rather than per input.

- **A ref pattern may subtract**, written `^<pattern>` — git's own spelling for a negative
  refspec, in the same place.\
  `--ref '^refs/heads/wip/*'` drops those and keeps every other branch and tag, so narrowing a run
  no longer means naming everything it wanted.\
  A `^` is decidable as the mark rather than a convention: git refuses one anywhere in a ref name,
  as it refuses the `:` a refspec is divided on.\
  It goes in front of the refspec, not the value: `backend::^refs/heads/wip`, since `^backend::`
  would read as *not backend*.\
  A subtraction carries no destination — nothing lands from it — and git refuses that spelling
  too.\
  It applies after the patterns that select, over what they left; with none, over that option's
  empty case. `--ref` starts from every branch and tag, so `^` alone is *every one of those
  except*; `--label-ref` and `--interleave-ref` start from none, so subtractions alone are
  refused.\
  That last part is where this parts company with git, whose command line drops the configured
  refspec as soon as one is named and so gives nothing back for subtractions alone.\
  `--interleave-ref 'refs/heads/* ^refs/heads/main'` is the pairing worth knowing: every side
  branch, a mainline tip's ancestry being everything it ever merged.\
  A bare star opts in every tag as well, and a tag on a mainline reaches what it had merged.\
  It subtracts the ref, not the commits behind it — they stay in scope if another opted-in ref
  reaches them.

- **`-b` takes the rest of the ref-pattern grammar around its branch name.**\
  An `<input>::` scope, a `^` in front of the branch and a `:<destination>`:
  `-b backend::^wip` is `--ref backend::^refs/heads/wip`.\
  Only the branch is put under `refs/heads/`, and neither mark can occur in a ref name, so no
  branch is ever read as one.

- **`--label-ref PATTERN`** — also recreate the refs matching this glob whose target the run
  already holds (repeatable).\
  It reads nothing extra and never delays a merge, so adding one cannot change a commit.\
  `--ref 'refs/heads/main' --label-ref 'refs/tags/*'` is *this branch, and the tags on it* —
  which the selection alone cannot say, `--ref 'refs/tags/*'` carrying every tag in the
  repository and the commits behind them.\
  A match whose target was not loaded is skipped, and the closing report says how many were.

- **`--splice`** — let one input's destination lie inside another's.\
  The pair is refused without it: the paths alone cannot tell a typo from an intended layout.\
  Every splice is checked against every tree before any object is written into the output,
  `--dry-run` included.

- **`--scan DIR`** — take the output layout from a directory tree.\
  Every repository under `DIR` becomes an input, placed in the output where it sits on disk.\
  `DIR` itself lands at the output root when it is a repository.\
  A repository is not descended into, and the run's own output is left out.\
  A `<repo>` argument naming one corrects that finding rather than adding a second input.

- **`--dissolve-submodules`** — let an input take the place of the gitlink it lands on.\
  The `[submodule]` section naming that path is left out of `.gitmodules` with it.\
  Opt-in, because it is not a faithful expansion.\
  A gitlink names the one commit the superproject pinned.\
  What takes its place is whatever that input had reached at that point of the braid.

- **`doc/examples` covers what the output holds, not only what order it is in.**\
  Each example is a README quoting what the tool prints, over inputs `build-inputs.sh` builds.\
  Three show the refusal beside the result.

- **The run reports itself by phase, and shows the ones that take the time.**\
  Each phase opens with a heading, and what came of it is said on an indented line under it; reading
  and planning, which draw no bar, add how long they took.\
  While one runs it draws a bar: the transfer what JGit reports of it, the writing a bar over the
  commits, and a clone, a refresh or a checkout what git itself reports — which had been read and
  then dropped.\
  Every bar leaves a line behind carrying what it reached and how long it took, and then goes: what
  it was showing stops being a question, and the counts are the part worth keeping.\
  A bar that runs for one input is named after it, which `Receiving objects` alone never said.\
  The stretches with nothing to count spin rather than going quiet for seconds under a heading
  already printed.\
  Only on a terminal: redirected, no bar is drawn at all, as `git` does with its own progress, and
  the phases and their lines read the same either way.\
  `--quiet` silences all of it, and `--progress`/`--no-progress` override which way that is
  decided.\
  The bar and the spinner are drawn in what the console can encode, each asked separately: a charset
  that carries one of them and not the other keeps the one it can draw, and the other falls back to
  ASCII.\
  `--ascii` and `--no-ascii` override that check in either direction.\
  Ctrl-C gives the cursor back that the animation hid. A signal unwinds no `finally`, so this is a
  shutdown hook of this program's own, which stops the threads still painting before it writes.\
  `doc/usage.md` says what a run prints, stream by stream.

- **A `git-timebraid(1)` manual page, which is what `git timebraid --help` shows.**\
  Git handles `--help` on a subcommand itself and looks for a manual page rather than running the
  program, so that spelling used to report no manual entry — in `man`'s words, with `man`'s exit
  status, neither of which said the program had never run.\
  The archive carries the page under `share/man/man1`, found while `MANPATH` is unset or has an
  empty entry — a leading or trailing `:`, or a `::` — because `man` then adds the search path it
  derives from `PATH`: from the archive's `bin/` on it, or from a link to the launcher with the page
  linked into the `share/man/man1/` beside that link's directory.\
  Git for Windows asks for an HTML page instead, and only in git's own `git --html-path`: every
  archive carries the page as `share/doc/git-doc/git-timebraid.html`, which `--help` opens once it
  is copied there.\
  `-h` and `-hh` reach the program under either spelling; where git has no viewer to hand the page
  to, `-hh` is still the one to use.\
  The page is written rather than generated, and `.github/scripts/check-man.py` holds it to the
  options the program actually has, down to their spelling.

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

- **A missing location now names the text the `::` dropped.**\
  `/some/path::libs` used to report only `/some/path` when nothing is there.\
  The reading is unchanged, and which one was meant is still not guessed at.\
  The run fails on the location either way, so the refusal says what it cut off.

- **`--keep-remotes` mirrors each input's mainline whether the selection took it or not.**\
  A run narrowed away from the mainline, `-b feature` for one, left its original commits in the
  output with nothing naming them.\
  They were fetched and rewritten under the output's own branch, and `git gc` pruned them once
  they were older than its grace period for unreachable objects, two weeks by default.

- **Two refs of one input meeting on one `--keep-remotes` mirror name are refused.**\
  A branch literally called `tags/v1.0` mirrors to the name the tag `v1.0` does.\
  0.1.0 kept the tag's mirror, leaving the branch's originals unnamed, and the closing report
  counted both.\
  The mainline is one of those refs whether the selection took it or not.\
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

- **Two inputs' branches meeting on one name are no longer resolved by whichever came last.**\
  In 0.1.0 a branch one input alone had kept its own name, which could be the one another input's
  shared branch was qualified to: `A/release` in C, beside a `release` that A and B both had.\
  The output kept the branch of the input written last, and the closing report counted both.\
  Every branch now carries its input's qualifier (see *Changed*), so the two stay apart. A
  `--branch-prefix` that does not keep `{repo}` apart from the name, `''` and any without `{repo}`
  among them, can still bring two branches onto one name, and that is refused, naming both inputs
  and the branch.

- **A shared branch qualified onto the braid's own name is refused, not written over it.**\
  In 0.1.0 a branch two inputs had was qualified as `<repo>/<branch>`, which could be the
  mainline's own name: `backend/x` for backend's `x` beside a `--mainline-branch backend/x`.\
  The branch was written over the braid's, so the output's mainline could point at backend's `x`.\
  The refusal names the braid and the input, and says how to move the input's refs off the braid's
  name, or to narrow the run.

- **The archives carry the documents README.md links to.**\
  Its links into `doc/` led nowhere once an archive was unpacked.

- **The jar settles which `META-INF/LICENSE.txt` it carries, rather than letting one shadow
  another.**\
  Which copy reached it followed the order the dependencies resolved in.

- **`--interleave-ref` brings the whole ancestry of what it names into scope.**\
  A ref sitting on a mainline was skipped whole, so naming a mainline branch, or a release tag on
  one, did nothing.\
  From any other ref the walk stopped where it met a mainline, leaving out what the earlier merges
  on that mainline had merged, so a bare star was not the pass over the whole graph it was
  documented to be, as it now is while the selection carries the mainline branches.\
  A side branch timestamped after the merge that took it in can now hold that merge back, and the
  mainline after it, whenever a ref above the merge is opted in: that is what opting in asks for.

- **The count `--verbose` gives for `--interleave-ref` says commits, which is what it counts.**\
  0.1.0 printed "2 refs opted into the interleave" for three refs on two commits: the count is of
  the commits the matched refs name, each once.

- **A dry run without `-o` removes the clones it made.**\
  Each such run cloned every URL input into a new temporary directory and left it there.

- **Two URLs never share a clone under `.timebraid-clones/`.**\
  A clone was found by the input's name, and two locations can derive one: 0.1.0 refreshed the
  first one's clone and braided it as the second.\
  A clone is now named by its URL, its last segment and a hash of the whole, so the same URL finds
  it again whatever the run names or places the input.\
  A clone of another URL found in its place is refused, naming both URLs and the directory to
  remove.

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

- **A `--mainline-branch` that names no branch is refused before the output is created.**\
  0.1.0 read the value as a revision, so `main~1` named the parent of `main` and passed a dry run,
  and the run then failed after creating the output, the half-made repository left behind.

- **A run on JDK 24 or later prints no warnings about native access.**\
  0.1.0 printed four `WARNING:` lines there on every run, for the native library JNA loads to read
  the terminal size.\
  The jar's manifest now allows native access, with `Enable-Native-Access`, and on JDK 22 and later
  mordant then reads the terminal size through the JDK's own foreign function API instead.

- **A clash between two ref names is refused before the output is created, `--dry-run` included.**\
  0.1.0 found `refs/heads/a` beside `refs/heads/a/b` only once every commit was written, and left
  the output with the history of every input fetched into it.\
  So is every other refusal of a ref name in this release: two inputs, or two refs of one input,
  meeting on one name, a ref meeting the braid's own branch, a `--keep-remotes` mirror meeting a
  destination, a destination among an input's mirrors that meets none of them, one under
  `refs/timebraid-fetch/`, and a name git does not accept, such as one a `--tag-prefix` gives a
  component ending in `.lock`.

- **An input whose `..` leads, past a symlink, elsewhere than its text is fetched from, and named
  after, the directory it is read from.**\
  0.1.0 read it from the directory the filesystem resolved, and fetched it from and named it after
  the one the text normalized to: a failed fetch, or a braid over trees the output lacked.\
  `--keep-remotes` records that directory too, by its real path, where 0.1.0 recorded the
  normalized text.

- **One local repository given as two arguments is refused.**\
  0.1.0 took `./a::x ./a::y` for two inputs and braided the one history against itself, every
  commit twice.

## [0.1.0] - 2026-09-08

First release.

[Unreleased]: https://github.com/loplex/git-timebraid/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/loplex/git-timebraid/releases/tag/v0.1.0
