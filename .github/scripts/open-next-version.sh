#!/usr/bin/env bash
#
# Opens the next version on every branch that carries the one just released.
#
# A branch's pom holds the version being worked towards, `-SNAPSHOT` suffix and all; a tag names a
# version being released, and set-release-version.sh refuses the two unless they agree once that
# suffix is set aside. A release therefore leaves the branch's pom naming a version that is now
# spent, and the tag after it has nothing to move to:
#
#     ::error::tag v0.3.0 says 0.3.0 but pom.xml says 0.2.0-SNAPSHOT
#
# This is the commit that was missing. It is the counterpart of set-release-version.sh: that one
# pins a throwaway checkout and deliberately commits nothing, this one writes to the branch.
#
# Which branches: every remote branch the tag is an ancestor of, rather than one named here. A tag
# does not record which branch it was cut from, and more than one branch can carry the same line --
# where two are at the same commit they share one bump commit rather than getting a divergent one
# each. A branch whose pom does not name the released version's snapshot is left alone and said so:
# it is either already ahead or was never on this line.
#
# The next version is the patch one -- 1.2.3 opens 1.2.4-SNAPSHOT. That is the smallest claim the
# branch can make, and at the moment a release goes out nobody knows yet whether the one after it is
# a fix, a feature or a break. A bump that guessed "minor" would announce a release that may never
# happen, and would make an actual patch release start by editing the version back down -- the very
# manual step this script exists to remove. Raising it to a minor or a major belongs to whoever
# lands the work that earns it, next to the changelog entry that explains it, and with the version
# in the .TH line of src/main/man/git-timebraid.1, which check-man.py holds to the pom's. The patch
# bump is also what maven-release-plugin does by default and what `versions:set -DnextSnapshot`
# computes.
#
# Each branch is read and written in its own throwaway worktree, so nothing here depends on, or
# disturbs, whatever the caller has checked out.
#
# Usage: open-next-version.sh <tag> [--dry-run]
#   --dry-run  report what would change and write nothing, locally or upstream.

set -euo pipefail

tag=${1:?usage: open-next-version.sh <tag> [--dry-run]}
dry_run=false
[ "${2:-}" = "--dry-run" ] && dry_run=true

version=${tag#v}
if [[ ! $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "::error::$tag does not name a release version (expected vMAJOR.MINOR.PATCH)" >&2
    exit 1
fi
IFS=. read -r major minor patch <<<"$version"
next="$major.$minor.$((patch + 1))-SNAPSHOT"

# Checked before anything else: `--contains` below prints its complaint and carries on, so an
# unresolvable tag would otherwise reach the end as "no branch contains it" and leave the job green
# having done nothing -- which is the failure this script exists to prevent, wearing a success.
if ! git rev-parse -q --verify "refs/tags/$tag^{commit}" >/dev/null; then
    echo "::error::no tag $tag in this repository" >&2
    exit 1
fi

echo "released $version, opening $next" >&2

# Pinned rather than the `versions:set` prefix, which resolves to whatever the plugin's newest
# release happens to be on the day this runs. The same pin as set-release-version.sh.
versions_plugin=org.codehaus.mojo:versions-maven-plugin:2.16.2

man_page=src/main/man/git-timebraid.1

# refname -> commit, for every branch of origin the tag is an ancestor of.
#
# Full refnames, not the short ones: a clone's refs/remotes/origin/HEAD abbreviates to plain
# `origin`, which reads as a branch of that name and is really a symbolic ref onto one of the
# others -- bumping it would push to a branch called `origin` or bump a real one twice. Restricted
# to origin as well, since a checkout carrying a second remote would offer its branches here too
# and none of them are ours to write to.
mapfile -t refs < <(
    git branch -r --contains "$tag" --format='%(refname) %(objectname)' \
        | grep '^refs/remotes/origin/' | grep -v '^refs/remotes/origin/HEAD ' || true
)
if [ ${#refs[@]} -eq 0 ]; then
    echo "::warning::no remote branch contains $tag; nothing to open" >&2
    exit 0
fi

# Group by commit, so two branches sitting on the same one get the same bump commit pushed to both
# rather than two commits with identical content.
declare -A at
for entry in "${refs[@]}"; do
    name=${entry%% *}
    sha=${entry##* }
    at[$sha]="${at[$sha]:-} ${name#refs/remotes/origin/}"
done

failed=()
for sha in "${!at[@]}"; do
    branches=(${at[$sha]})
    work=$(mktemp -d)
    trap 'git worktree remove --force "$work" >/dev/null 2>&1 || true; rm -rf "$work"' EXIT
    git worktree add --detach "$work" "$sha" >/dev/null 2>&1

    pom=$(cd "$work" && mvn -B -ntp -q help:evaluate -Dexpression=project.version -DforceStdout)
    echo "${branches[*]} at ${sha:0:7}: pom says $pom" >&2

    if [ "$pom" != "$version-SNAPSHOT" ]; then
        echo "  left alone -- it does not carry $version-SNAPSHOT" >&2
    elif $dry_run; then
        echo "  would set $next and push to: ${branches[*]}" >&2
    else
        (
            cd "$work"
            mvn -B -ntp "$versions_plugin:set" -DnewVersion="$next" -DgenerateBackupPoms=false >&2
            # The manual page names the version being worked towards as well, and check-man.py
            # holds it to the pom's, so the two move in one commit or the next push fails there.
            # Only the version: the date beside it is that of the page's last nontrivial change,
            # per man-pages(7), and a version bump is not one.
            sed -i "s/^\(\.TH .*\"git\\\\-timebraid \)[^\"]*\"/\1${next%-SNAPSHOT}\"/" "$man_page"
            grep -q "git\\\\-timebraid ${next%-SNAPSHOT}\"" "$man_page" || {
                echo "::error::could not set the .TH version in $man_page" >&2
                exit 1
            }
            git -c user.name='github-actions[bot]' \
                -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
                commit -q -m "build(release): raise the version to $next" -- pom.xml "$man_page"
        )
        for branch in "${branches[@]}"; do
            # No force: a branch that moved since the release was published is a real conflict, and
            # overwriting it would drop whatever landed there.
            if git -C "$work" push origin "HEAD:refs/heads/$branch"; then
                echo "  pushed $next to $branch" >&2
            else
                echo "::error::could not push the bump to $branch" >&2
                failed+=("$branch")
            fi
        done
    fi

    git worktree remove --force "$work" >/dev/null 2>&1 || true
    rm -rf "$work"
    trap - EXIT
done

if [ ${#failed[@]} -ne 0 ]; then
    echo "::error::${#failed[@]} branch(es) still on $version-SNAPSHOT: ${failed[*]}" >&2
    exit 1
fi
