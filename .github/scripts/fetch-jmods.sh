#!/usr/bin/env bash
#
# Downloads the Temurin JDK for another platform, of the very version that JAVA_HOME holds, and
# prints the path of its jmods directory -- what `-Djlink.jmods` takes to link that platform's
# runtime image here.
#
# The version has to be the same one: jlink links the modules it is given with the code of its own
# JDK, and the two are released together. JAVA_HOME's `release` file names its version
# (SEMANTIC_VERSION, e.g. 21.0.12.1+1), and Temurin's release name is that version behind `jdk-`,
# so the Adoptium API is asked for exactly that release rather than for the latest of a line --
# which would be a different version the day a new one is published while the runner's JDK is not.
#
# The download is checked against the SHA-256 the same API gives for it, and the script fails rather
# than unpacking anything that does not match.
#
# Usage: fetch-jmods.sh <os> <arch> <directory>
#   os, arch   as the Adoptium API spells them: windows, linux, mac; x64, aarch64
#   directory  where the JDK is downloaded and unpacked; created if missing
#
# JAVA_HOME has to be a Temurin JDK; another vendor's release file carries no SEMANTIC_VERSION, and a
# Temurin release of the same number is not the same build.

set -euo pipefail

if [ $# -ne 3 ]; then
    echo "usage: fetch-jmods.sh <os> <arch> <directory>" >&2
    exit 2
fi
os=$1
arch=$2
dir=$3

release_file=${JAVA_HOME:?JAVA_HOME is not set}/release
version=$(sed -n 's/^SEMANTIC_VERSION="\(.*\)"$/\1/p' "$release_file")
if [ -z "$version" ]; then
    echo "fetch-jmods: $release_file names no SEMANTIC_VERSION; JAVA_HOME has to be a Temurin JDK" >&2
    exit 1
fi

mkdir -p "$dir"
api=https://api.adoptium.net/v3/assets/release_name/eclipse/jdk-${version/+/%2B}
query="architecture=$arch&image_type=jdk&jvm_impl=hotspot&os=$os&heap_size=normal"
curl -fsSL --retry 3 "$api?$query" > "$dir/assets.json"

# One binary is expected; anything else means the query no longer says what it meant to.
read -r name link checksum < <(python3 -I - "$dir/assets.json" <<'EOF'
import json, sys
binaries = json.load(open(sys.argv[1]))["binaries"]
if len(binaries) != 1:
    sys.exit(f"fetch-jmods: expected one binary, the API lists {len(binaries)}")
package = binaries[0]["package"]
print(package["name"], package["link"], package["checksum"])
EOF
)

echo "fetch-jmods: $name" >&2
curl -fsSL --retry 3 -o "$dir/$name" "$link"
(cd "$dir" && echo "$checksum  $name" | sha256sum --check --quiet -)

case "$name" in
    *.zip)    unzip -q -o "$dir/$name" -d "$dir" ;;
    *.tar.gz) tar xzf "$dir/$name" -C "$dir" ;;
    *)        echo "fetch-jmods: no way to unpack $name" >&2; exit 1 ;;
esac

# The archive unpacks into one directory named after the release; macOS ones nest the JDK in
# Contents/Home.
for jmods in "$dir/jdk-$version"/jmods "$dir/jdk-$version"/Contents/Home/jmods; do
    if [ -d "$jmods" ]; then
        echo "$jmods"
        exit 0
    fi
done
echo "fetch-jmods: $name holds no jmods directory under jdk-$version" >&2
exit 1
