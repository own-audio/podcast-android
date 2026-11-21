#!/usr/bin/env bash
# Prints the size of the release APKs and fails when one is over the budget.
# The budget is set at the first release (docs/android-foundation.md §2.6).
set -eu
budget_mb=${APK_BUDGET_MB:-12}
cd "$(dirname "$0")/.."
found=0
for apk in app/build/outputs/apk/*/release/*.apk; do
  [ -f "$apk" ] || continue
  found=1
  bytes=$(wc -c <"$apk" | tr -d ' ')
  mb=$(awk "BEGIN { printf \"%.2f\", $bytes / 1048576 }")
  echo "$apk: $mb MB (budget $budget_mb MB)"
  awk "BEGIN { exit !($bytes > $budget_mb * 1048576) }" && { echo "Over budget."; exit 1; }
done
[ $found -eq 1 ] || { echo "No release APK; run ./gradlew assembleRelease first."; exit 1; }
