#!/usr/bin/env bash
# Reports drift between this repository's shared modules and its siblings
# (music-android, book-android, podcast-android), checked out next to it.
#
# Tier 1 must be byte-identical; any difference fails. Tier 2 is the same code adapted per
# app: differences are listed for reading, never failed, so a fix in one copy is noticed in
# the others. Shared string keys must have the same English and Czech wording unless the key
# is in the allowlist. Run before ticking a phase:
#
#   tools/sibling-drift.sh            # every sibling found
#   tools/sibling-drift.sh book       # one sibling
#
# Paths a repository doesn't have yet are reported, not failed.
set -u

here=$(cd "$(dirname "$0")/.." && pwd)
root=$(cd "$here/.." && pwd)
me=$(basename "$here")

tier1=(
  build-logic/build.gradle.kts
  build-logic/settings.gradle.kts
  build-logic/src
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.properties
  docs/android-foundation.md
  docs/design-tokens.json
  tools/sibling-drift.sh
  tools/apk-size.sh
  tools/store-icon.py
  .github/workflows/release.yml
  .github/workflows/pages.yml
  core/common/src
  core/network/src
  core/servers/src
  core/designsystem/src/main/kotlin/audio/own/core/designsystem/brand
  core/designsystem/src/main/kotlin/audio/own/core/designsystem/components
  core/designsystem/src/main/kotlin/audio/own/core/designsystem/theme/Tokens.kt
  player/core/src
  kit/ownaudio/src
)
# Shared by Books and Podcasts only.
tier1_spoken=(
  kit/audiobookshelf/src
  player/spoken/src
)

tier2=(
  gradle.properties
  core/designsystem/src/main/kotlin/audio/own/core/designsystem/theme/Accent.kt
  core/downloads/src
  core/sync/src
  .github/workflows/ci.yml
)

# Keys that name the medium (songs, books, episodes) and so differ by definition.
string_allowlist=''

siblings=()
if [ $# -gt 0 ]; then
  for s in "$@"; do siblings+=("$root/$s-android"); done
else
  for d in "$root"/music-android "$root"/book-android "$root"/podcast-android; do
    [ "$(basename "$d")" = "$me" ] && continue
    [ -d "$d/.git" ] && siblings+=("$d")
  done
fi

compare() { # path sibling
  local p=$1 sib=$2
  if [ ! -e "$here/$p" ] && [ ! -e "$sib/$p" ]; then return 0; fi
  if [ ! -e "$here/$p" ]; then echo "   missing here     $p"; return 0; fi
  if [ ! -e "$sib/$p" ]; then echo "   missing there    $p"; return 0; fi
  if ! diff -rq --exclude=build --exclude=.gradle "$here/$p" "$sib/$p" >/dev/null; then
    echo "   TIER 1 DRIFT     $p"
    diff -rq --exclude=build --exclude=.gradle "$here/$p" "$sib/$p" | sed 's/^/                    /'
    return 1
  fi
}

status=0
for sib in "${siblings[@]}"; do
  name=$(basename "$sib")
  echo "== $me vs $name"
  for p in "${tier1[@]}"; do compare "$p" "$sib" || status=1; done
  if [ "$me" != music-android ] && [ "$name" != music-android ]; then
    for p in "${tier1_spoken[@]}"; do compare "$p" "$sib" || status=1; done
  fi
  for p in "${tier2[@]}"; do
    [ -e "$here/$p" ] && [ -e "$sib/$p" ] || continue
    if ! diff -rq --exclude=build --exclude=.gradle "$here/$p" "$sib/$p" >/dev/null; then
      echo "   tier 2 differs   $p"
    fi
  done
  for lang in values values-cs; do
    mine="$here/core/strings/src/main/res/$lang/strings.xml"
    theirs="$sib/core/strings/src/main/res/$lang/strings.xml"
    [ -f "$mine" ] && [ -f "$theirs" ] || continue
    python3 -I - "$mine" "$theirs" "$string_allowlist" "$lang" <<'PY' || status=1
import re, sys
def load(p):
    return dict(re.findall(r'<string name="([^"]+)"[^>]*>(.*?)</string>', open(p, encoding='utf-8').read(), re.S))
a, b = load(sys.argv[1]), load(sys.argv[2])
allow = set(sys.argv[3].split())
bad = sorted(k for k in a.keys() & b.keys() if a[k].strip() != b[k].strip() and k not in allow)
for k in bad:
    print(f"   STRING DRIFT     {sys.argv[4]}/{k}: {a[k].strip()[:50]!r} vs {b[k].strip()[:50]!r}")
sys.exit(1 if bad else 0)
PY
  done
done
[ ${#siblings[@]} -eq 0 ] && echo "No sibling repository next to $me."
exit $status
