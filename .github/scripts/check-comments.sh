#!/usr/bin/env bash
#
# Refuses a `/*` inside a block comment, which Kotlin reads as a nested comment.
#
# Kotlin nests block comments where Java does not, so a ref glob in a KDoc -- `refs/heads/` followed
# by a star -- opens a comment that swallows what comes after it.
#
# Usually the compiler says so, though badly: `Unclosed comment` at the last line of the file, plus a
# syntax error hundreds of lines above the glob that caused it, and neither is the glob's own line.
#
# Sometimes it says nothing at all, which is why this runs in CI rather than being left to the build.
# Any `*/` below the glob closes the nested comment, and where what remains is valid Kotlin the file
# compiles with a hole in it. Measured:
#
#   /** Matches refs/heads/<star> and nothing else. */
#   fun a(): Int = 1
#   fun swallowed(): Int = 999
#   // a line mentioning the closing delimiter */
#   fun b(): Int = 2
#
# compiles clean and yields a class holding `b()` alone. In a test file that is a @Test that stops
# running while the build stays green -- which nothing else here would catch.
#
# It has to know where a comment is, not merely where the two characters are. A grep for the pair
# reports every `refs/heads/*` in a string literal and every one in a `//` comment, none of which
# nests anything: on this tree every one of them is a false positive, which is the kind of noise
# that gets a check ignored. Tracking the depth without skipping strings is worse still --
# `"refs/heads/*"` holds a `/*` and no `*/`, so a naive counter believes it is inside a comment for
# the rest of the file.
#
# So: three states. Inside a raw string, inside a block comment, or neither -- and only the middle
# one reports. Line comments and both kinds of string literal are skipped in the third.
#
# One finding per file, and the rest of it is skipped -- with a flag rather than `nextfile`, which
# is an extension the runners' mawk need not have. After the first nesting every later `/**` is
# inside a comment too, so a file with one bad glob otherwise reports every KDoc below it -- twenty
# lines of which nineteen are the consequence. The first is the cause; fix it and run again.
#
# Covers src whole, tests included, that silent case being the one that matters most there.
#
# The Python checkers read comments through kotlin_source.py, which ends a block comment at its
# first `*/`; refusing the nesting here is also what keeps that reading exact.
#
# Usage: check-comments.sh [path ...]     (default: src)
# Exit status is 0 when nothing nests, 1 otherwise, with the first occurrence in each file
# printed as file:line.

set -euo pipefail

roots=("${@:-src}")

found=$(find "${roots[@]}" -name '*.kt' -print0 | xargs -0 awk '
FNR == 1 { depth = 0; raw = 0; done = 0 }
done { next }
{
  i = 1
  while (i <= length($0)) {
    two = substr($0, i, 2)
    if (raw) {
      if (substr($0, i, 3) == "\"\"\"") { raw = 0; i += 3 } else i++
    } else if (depth > 0) {
      if (two == "*/")      { depth--; i += 2 }
      else if (two == "/*") { printf "%s:%d: /* inside a block comment\n", FILENAME, FNR; done = 1; next }
      else i++
    } else {
      if (two == "//") break
      else if (two == "/*") { depth++; i += 2 }
      else if (substr($0, i, 3) == "\"\"\"") { raw = 1; i += 3 }
      else if (substr($0, i, 1) == "\"") {
        for (i++; i <= length($0); i++) {
          if (substr($0, i, 1) == "\\") i++
          else if (substr($0, i, 1) == "\"") { i++; break }
        }
      }
      else i++
    }
  }
}
')

if [ -n "$found" ]; then
  echo "$found"
  echo
  echo "A block comment cannot hold '/' followed by '*': Kotlin nests block comments, so the file"
  echo "ends there. Write the glob in words, or one whose star follows something else (refs/tags/v1.*)."
  exit 1
fi

echo "no block comment opens another: $(find "${roots[@]}" -name '*.kt' | wc -l) Kotlin files"
