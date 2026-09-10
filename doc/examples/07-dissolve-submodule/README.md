# 07 — a submodule replaced by its own history (`--dissolve-submodules`)

`super` keeps a gitlink at `vendor/lib`, and `lib` — the repository that gitlink points at — is an
input of the same merge, placed at that exact path. Shows the gitlink giving way to real content,
the `.gitmodules` bookkeeping that has to go with it, and why the result is not a faithful expansion
of the submodule.

## Input

```
lib:   l1@15 <- l2@45        src/lib.kt, then src/util.kt
super: s1@10 <- s2@20 <- s3@35
         s1: a.txt
         s2: gitlinks at vendor/lib and vendor/other, plus the .gitmodules describing both
         s3: b.txt
```

```
$ git -C input/super ls-tree -r HEAD
100644 blob 23dfcb2…    .gitmodules
100644 blob 655c6e6…    a.txt
100644 blob c4bf846…    b.txt
160000 commit d8cd43f…  vendor/lib
160000 commit c7ada63…  vendor/other

$ git -C input/super show HEAD:.gitmodules
[submodule "vendor/lib"]
	path = vendor/lib
	url = https://example.com/lib.git
[submodule "vendor/other"]
	path = vendor/other
	url = https://example.com/other.git
```

Two details of the fixture carry weight:

- The `vendor/lib` gitlink names `d8cd43f`, which is `l1` — **not** `lib`'s tip (`l2`). A pin behind
  the submodule's own head is the normal state of a superproject, and it is what makes the
  substitution visible below.
- There is a **second** submodule, `vendor/other`, and no input for it. It stays a submodule, which
  is what keeps a root `.gitmodules` in the output at all and lets the two cases be read side by
  side. `input/other` exists only so that gitlink names a real commit; it is never given to the
  merge.

The gitlinks are staged with `update-index --cacheinfo` rather than `git submodule add`, so the
fixture records no url that has to resolve on this machine — `https://example.com/…` never has to
exist for any of this to work.

## Refused by default

<!-- wide block: the refusal is quoted as the program prints it, flag and object id and all -->
```
$ ./git-timebraid -o doc/examples/07-dissolve-submodule/output-no-dissolve --no-bare \
    --root-repo super \
    doc/examples/07-dissolve-submodule/input/super \
    doc/examples/07-dissolve-submodule/input/lib::vendor/lib

one repository cannot be placed inside another where it is:
  - 'vendor/lib' is a submodule of super, so --dissolve-submodules would replace it with that repository's own content at super/1b5780ff3cc5292ca34a492d0a96ad8349085b72
  give the repository at 'vendor/lib' another subdirectory, as 'doc/examples/07-dissolve-submodule/input/lib::<subdir>'
```

A gitlink at the destination is a collision like any other by default. The tool cannot tell from the
paths whether landing on it is the point or an accident, so it says which flag would make it the
point and names the commit (`1b5780f` is `s2`, where the gitlink first appears).

No `--splice` is needed here even though `vendor/lib` lies inside `super`'s destination: `super` is
the `--root-repo`, and every destination lies inside *that* by construction — which is what asking
for a root repository asked for.

## Command

```
$ ./git-timebraid -o doc/examples/07-dissolve-submodule/output --no-bare \
    --root-repo super --dissolve-submodules \
    --plan-out doc/examples/07-dissolve-submodule/plan.txt \
    doc/examples/07-dissolve-submodule/input/super \
    doc/examples/07-dissolve-submodule/input/lib::vendor/lib
dissolved: the submodule at vendor/lib in super replaced by its own content at 3 commits
repositories:
  super -> <root>
  lib -> vendor/lib/
```

## Result

```
$ git -C output log --first-parent main --date=iso --pretty="format:%h %ad %s"
3e2c690 2023-11-16 19:13:20 +0000 lib: l2
6f4bb86 2023-11-16 09:13:20 +0000 super: s3
6021a7a 2023-11-15 18:13:20 +0000 super: s2
a505a1b 2023-11-15 13:13:20 +0000 lib: l1
10fee29 2023-11-15 08:13:20 +0000 super: s1

$ git -C output ls-tree -r HEAD
100644 blob ce7382e…    .gitmodules
100644 blob 655c6e6…    a.txt
100644 blob c4bf846…    b.txt
100644 blob 1f25f40…    vendor/lib/src/lib.kt
100644 blob 0d5b62c…    vendor/lib/src/util.kt
160000 commit c7ada63…  vendor/other

$ git -C output show HEAD:.gitmodules
[submodule "vendor/other"]
	path = vendor/other
	url = https://example.com/other.git
```

Three things happened at once, and they are the whole rule:

- `vendor/lib` is a **tree**, not a gitlink — `lib`'s own files, reachable by `git log` in this
  repository rather than by `git submodule update` against another one.
- The `vendor/lib` section is **gone** from the root `.gitmodules`, because a `path` naming a
  directory git finds no gitlink at is a mapping `git submodule` reports as broken.
- The `vendor/other` section is **kept**, and its gitlink is untouched. The flag applies where an
  input lands, and nowhere else.

The root `.gitmodules` is the braid's own file, not `super`'s: `super`'s copy listed both
submodules, and what the output holds is the sections of every input that has content here, with
`vendor/lib`'s left out. (Had `vendor/lib` been the only section, no root `.gitmodules` would be
written at all, and `super`'s own copy would come out of the tree with it.)

### It is decided per commit, not per run

```
$ git -C output ls-tree -r HEAD~4          # s1@10: super's first commit
100644 blob 655c6e6…    a.txt

$ git -C output ls-tree -r HEAD~3          # l1@15: lib has content, super has no gitlink yet
100644 blob 655c6e6…    a.txt
100644 blob 1f25f40…    vendor/lib/src/lib.kt

$ git -C output ls-tree -r HEAD~2          # s2@20: the gitlinks arrive
100644 blob ce7382e…    .gitmodules
100644 blob 655c6e6…    a.txt
100644 blob 1f25f40…    vendor/lib/src/lib.kt
160000 commit c7ada63…  vendor/other
```

At `s1` there is no `.gitmodules` and nothing at `vendor/`: `super`'s content at that point has
neither, and `lib` has not started. At `l1` the library's content is there — placed at its
destination like any input's, with no gitlink involved, because `super` does not have one yet. Only
from `s2` on is there a gitlink to give way, which is what "at 3 commits" counts: `s2`, `s3`, `l2`.

### What this is not: a faithful expansion

The pin was `l1`. At the tip, `vendor/lib` holds `src/util.kt` too — `l2`'s content, which the
superproject never pinned:

```
$ git -C output ls-tree --name-only -r HEAD -- vendor/lib
vendor/lib/src/lib.kt
vendor/lib/src/util.kt
```

The output's `vendor/lib` moves with the **braid**, not with the superproject's pin. At every commit
it holds whatever `lib` had last committed at or before that point, which is the same tree rule
every other destination follows — and it is a different history from the one the gitlinks recorded.
That is why the flag is opt-in rather than inferred from the paths.

For the same reason, a gitlink at a segment *above* a destination stays an error: nothing is placed
at that path, so there is no content that could stand in for the submodule.

Try it yourself: `git -C doc/examples/07-dissolve-submodule/output log --stat --first-parent` from
the repo root.
