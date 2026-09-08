#!/usr/bin/env bash
#
# Pins the checked-out pom to the version its release tag names.
#
# A branch's pom carries the version being worked towards, suffixed `-SNAPSHOT`; a tag carries the
# version being released. This reconciles the two -- it checks that they name the same version once
# the suffix is set aside, then rewrites the pom so the build produces `1.2.3` and not
# `1.2.3-SNAPSHOT`. Nothing is committed: the rewrite is for the runner's throwaway checkout, and
# the branch goes on carrying its snapshot.
#
# Keeping the check rather than just taking the tag's word is what stops `v9.9.9` from being cut
# off a pom that says 0.1.0: the tag may drop the suffix, but it may not invent a version nobody
# was working towards.
#
# Every job that packages an archive has to run this, not only the one that reports the version
# onwards. The archives are named from ${project.version} (see the assembly finalName in pom.xml),
# so a job that skipped it would upload `git-timebraid-1.2.3-SNAPSHOT-linux-x64.tar.gz` under a tag
# saying 1.2.3.
#
# Usage: set-release-version.sh <tag>
# The released version goes to stdout; the reasoning and Maven's own noise go to stderr.

set -euo pipefail

tag=${1:-}
if [ -z "$tag" ]; then
    echo "usage: set-release-version.sh <tag>" >&2
    exit 2
fi

version=${tag#v}

# A tag promises that these bytes stay put, so it may not name a version that by definition moves.
case "$version" in
    *-SNAPSHOT)
        echo "::error::refusing to release a snapshot version ($version)" >&2
        exit 1
        ;;
esac

pom=$(mvn -B -ntp -q help:evaluate -Dexpression=project.version -DforceStdout)
echo "tag: $version, pom: $pom" >&2

if [ "${pom%-SNAPSHOT}" != "$version" ]; then
    echo "::error::tag $tag says $version but pom.xml says $pom" >&2
    exit 1
fi

# Pinned rather than the `versions:set` prefix, which resolves to whatever the plugin's newest
# release happens to be on the day a release is cut.
versions_plugin=org.codehaus.mojo:versions-maven-plugin:2.16.2

if [ "$pom" != "$version" ]; then
    echo "setting the pom version to $version" >&2
    mvn -B -ntp "$versions_plugin:set" -DnewVersion="$version" -DgenerateBackupPoms=false >&2
fi

echo "$version"
