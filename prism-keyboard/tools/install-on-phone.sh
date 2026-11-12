#!/bin/bash
# Waits for the newest "Build Prism Keyboard" run on the current branch, then installs its APK on the
# phone connected over adb and keeps Prism as the keyboard. (The release key lives only in CI, so this
# is the way to update the phone without losing learned words.)
set -e
cd "$(dirname "$0")/../.."
sleep 10
BRANCH=$(git rev-parse --abbrev-ref HEAD)
RID=$(gh run list --branch "$BRANCH" --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RID" --exit-status >/dev/null 2>&1 || { echo "BUILD FAILED $RID"; gh run view "$RID" --log-failed | grep -E "e: |error:|What went wrong" | head -30; exit 1; }
N=$(gh run view "$RID" --json number -q .number)
OUT=$(mktemp -d)
gh release download "prism-v0.1.$N" -p Prism.apk -D "$OUT"
adb install -r "$OUT/Prism.apk" | tail -1
adb shell ime set dev.msjr.prism/.ime.PrismIME | tail -1
echo "installed 0.1.$N"
