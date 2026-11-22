#!/usr/bin/env bash
# Prints the size of the release APKs and fails when one is over the budget.
# Each app's budget is its play release APK at the first release build (2026-11-22) plus 20 %
# (docs/android-foundation.md §2.6). Raise it on purpose, not to get a build through.
# APK_BUDGET_MB overrides it for one run.
set -eu
cd "$(dirname "$0")/.."
case $(sed -n 's/^app.id=//p' gradle.properties) in
  audio.own.musicplayer) default_mb=11.1 ;;   # 9.23 MB
  audio.own.bookplayer) default_mb=10.6 ;;    # 8.85 MB
  audio.own.podcastplayer) default_mb=10.5 ;; # 8.76 MB
  *) default_mb=12 ;;
esac
budget_mb=${APK_BUDGET_MB:-$default_mb}
found=0
for apk in app/build/outputs/apk/*/release/*.apk; do
  [ -f "$apk" ] || continue
  found=1
  bytes=$(wc -c <"$apk" | tr -d ' ')
  mb=$(awk "BEGIN { printf \"%.2f\", $bytes / 1048576 }")
  echo "$apk: $mb MB (budget $budget_mb MB)"
  awk "BEGIN { exit !($bytes > $budget_mb * 1048576) }" && { echo "Over budget."; exit 1; }
done
[ $found -eq 1 ] || { echo "No release APK; run ./gradlew assemblePlayRelease assembleFossRelease first."; exit 1; }
