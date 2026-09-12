# 09 — which refs a run carries over (`-b`, `--ref`)

`-b` and `--ref` are one selection, not two, and it applies to branches and tags alike. Shows what
each of four runs ends up holding — including the one that surprises people, where narrowing to a
branch drops the tags *and* the commits only a tag could reach.

## Input

```text
backend: a1@10 <- a2@30              on main
         r1@35                       on release/1.x, forked from a1
         tag v1.0 -> a1              lightweight
         tag v1.1 -> r1              annotated, tagged @36
webui:   b1@20 <- b2@40              on main
         tag v2.0 -> b2              lightweight
```

```console
$ git -C input/backend for-each-ref
d83bd476df949d6a17d72b278e3ca63b6cec5474 commit	refs/heads/main
83f39e552b926c0f3b2607eec4431726d24dc427 commit	refs/heads/release/1.x
c28627b5a6b5e222fd02f726cf65b3da6db6a728 commit	refs/tags/v1.0
fe31057d0ddc3794e3f62f31ff239a54cb783da3 tag	refs/tags/v1.1
```

`r1` is reachable from `release/1.x` and from `v1.1`, and from nothing on `main`. That is what makes
it the interesting commit here.

## The four runs

Every run merges the same two inputs; they differ only in the selection. Each writes its own output
directory, so they can be compared side by side.

### Everything (the default)

```console
$ ./git-timebraid -o doc/examples/09-ref-selection/output --no-bare \
    --plan-out doc/examples/09-ref-selection/plan.txt \
    doc/examples/09-ref-selection/input/backend doc/examples/09-ref-selection/input/webui
commits: 5 (braid: 4)
refs: 2 branches, 3 tags, HEAD -> main
```

```console
$ git -C output for-each-ref --format='%(objecttype) %(refname)'
commit refs/heads/main
commit refs/heads/release/1.x
commit refs/tags/backend/v1.0
tag refs/tags/backend/v1.1
commit refs/tags/webui/v2.0
```

All five commits, both branches, all three tags. `release/1.x` keeps its own name because only one
input used it; the two `main` branches collapse into the single braid. Tags are prefixed with the
repository name (`--tag-prefix` defaults to `{repo}/`), which is what stops `backend`'s `v1.0` and a
`v1.0` from anywhere else colliding. `backend/v1.1` is still `objecttype tag` — an annotated tag
stays annotated, keeping its tagger and message.

`braid: 4` against `commits: 5` is `r1`: it is off the mainline, so it is recreated but is not part
of the braid.

### Narrowed to one branch, and this is the one that bites

```console
$ ./git-timebraid -o doc/examples/09-ref-selection/output-branch-only --no-bare \
    -b main \
    doc/examples/09-ref-selection/input/backend doc/examples/09-ref-selection/input/webui
commits: 4 (braid: 4)
refs: 1 branches, 0 tags, HEAD -> main
```

```console
$ git -C output-branch-only for-each-ref --format='%(objecttype) %(refname)'
commit refs/heads/main
```

**No tags at all**, and `r1` is not merely unreferenced — it is not in the output. `commits: 4`, and
`git log --all` finds `a1, a2, b1, b2` and nothing else. Naming any ref leaves out every ref not
named, and what is left out is left out of the *fetch* too, so a commit only an unselected ref could
reach has no objects here.

This is a change in 0.2.0: `-b` used to narrow the branches but not the tags, every one of which
was read, so a run like this still pulled in whatever the tags could reach. See
[CHANGELOG.md](../../../CHANGELOG.md).

### One branch, keeping the tags

```console
$ ./git-timebraid -o doc/examples/09-ref-selection/output-branch-and-tags --no-bare \
    -b main --ref 'refs/tags/*' \
    doc/examples/09-ref-selection/input/backend doc/examples/09-ref-selection/input/webui
commits: 5 (braid: 4)
refs: 1 branches, 3 tags, HEAD -> main
```

```console
$ git -C output-branch-and-tags for-each-ref --format='%(objecttype) %(refname)'
commit refs/heads/main
commit refs/tags/backend/v1.0
tag refs/tags/backend/v1.1
commit refs/tags/webui/v2.0
```

Writing both halves out is how 0.1.0's `-b main`, which carried every tag along, is asked for now.
Note the count: **5** commits again, `r1` among them, even though `release/1.x` was not selected and
no branch in the output points at it. Selecting a ref selects its ancestry, and `backend/v1.1`
points at `r1` — so the tag brings the commit with it, and the tag is the only thing that reaches
it.

### A glob over a nested branch name

```console
$ ./git-timebraid -o doc/examples/09-ref-selection/output-release-glob --no-bare \
    --ref 'refs/heads/release/*' \
    doc/examples/09-ref-selection/input/backend doc/examples/09-ref-selection/input/webui
commits: 5 (braid: 4)
refs: 2 branches, 0 tags, HEAD -> main
```

```console
$ git -C output-release-glob for-each-ref --format='%(objecttype) %(refname)'
commit refs/heads/main
commit refs/heads/release/1.x
```

Three rules at once:

- `*` **spans path separators**, so `refs/heads/release/*` reaches `release/1.x` — and would reach
  `release/2/1.x` just as well.
- Matching is against the **full** ref name. A short name could not say whether `v1.0` is a branch
  or a tag, so `refs/heads/`/`refs/tags/` is written out.
- **`main` is there without being asked for.** The mainline is always loaded, because the braid is
  built along it, and the output's branch of that name is the braid's tip. It is the one ref a
  selection cannot exclude.

## In short

| selection                        | commits | branches                | tags                |
|----------------------------------|--------:|-------------------------|---------------------|
| *(none)*                         |       5 | `main`, `release/1.x`   | all three           |
| `-b main`                        |       4 | `main`                  | none                |
| `-b main --ref 'refs/tags/*'`    |       5 | `main`                  | all three           |
| `--ref 'refs/heads/release/*'`   |       5 | `main`, `release/1.x`   | none                |

`-b main` *is* `--ref refs/heads/main`; the shorthand carries no exemption of its own.

Try it yourself: `git -C doc/examples/09-ref-selection/output log --graph --all --oneline` from the
repo root.
