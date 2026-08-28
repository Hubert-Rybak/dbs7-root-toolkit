#!/bin/bash
# Download the Google apps package set needed by DBS7 Root Toolkit (button 5)
# and build a ready-to-use gapps/ directory (or a zip for a USB stick).
#
#   tools/download_gapps.sh [output-dir]
#
# What it fetches:
#   1. MindTheGapps 14.0.0 arm64 (official GitHub releases; provides GmsCore,
#      GoogleServicesFramework, GoogleFeedback, GooglePartnerSetup and the
#      privapp-permissions-*.xml allowlists) — sha256 verified.
#   2. Play Store for Android TV (APKMirror; the phone build ANR-loops on
#      leanback-only devices). APKMirror blocks scripted downloads, so the
#      script opens the page URL and asks you to download the file manually
#      into the working dir if curl cannot fetch it.
#
# After the script finishes, copy the gapps/ dir to a FAT32 USB stick (or
# adb push it to /data/local/tmp/) — see the README "Google apps" section.

set -e
OUT="${1:-.}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT/gapps"

MTG_URL="https://github.com/MindTheGapps/14.0.0-arm64/releases/download/MindTheGapps-14.0.0-arm64-20250203_200051/MindTheGapps-14.0.0-arm64-20250203_200051.zip"
MTG_SHA256="6e1c3616862ce5b33e2b96074f86ae846eb1351a26a980e1ed140f8a7e7a4fd6"
MTG_ZIP="$WORK/mtg.zip"

echo "[1/3] MindTheGapps 14.0.0 arm64 (~412 MB)…"
curl -fL --retry 3 -C - -o "$MTG_ZIP" "$MTG_URL" || { echo "download failed — download manually from $MTG_URL and place at $MTG_ZIP"; exit 1; }
echo "  verifying sha256…"
echo "$MTG_SHA256  $MTG_ZIP" | sha256sum -c -

echo "[2/3] extracting 5 APKs + 3 permission XMLs…"
CDIR="$WORK/mtg_x"
mkdir -p "$CDIR"
python3 - "$MTG_ZIP" "$CDIR" <<'PYEOF'
import sys, zipfile, fnmatch, os
z = zipfile.ZipFile(sys.argv[1])
want = ["GmsCore.apk", "GoogleServicesFramework.apk", "GoogleFeedback.apk",
        "GooglePartnerSetup.apk", "privapp-permissions-google-product.xml",
        "privapp-permissions-google-system-ext.xml", "privapp-permissions-mtg.xml"]
out = sys.argv[2]
found = set()
for n in z.namelist():
    base = os.path.basename(n)
    if base in want:
        with open(os.path.join(out, base), "wb") as f:
            f.write(z.read(n))
        found.add(base)
missing = set(want) - found
if missing:
    sys.exit("MISSING in MTG zip: %s" % missing)
print("extracted:", ", ".join(sorted(found)))
PYEOF

echo "[3/3] Play Store for Android TV…"
PS_URL_PAGE="https://www.apkmirror.com/apk/google-inc/google-play-store-android-tv/google-play-store-android-tv-48-4-15-release/"
echo "  APKMirror requires a browser session. Open:"
echo "    $PS_URL_PAGE"
echo "  download 'Google Play Store (Android TV) 48.4.15-31 [8] [PR]' base APK,"
echo "  then save it as: $OUT/gapps/PlayStoreTV.apk"
if [ -f PlayStoreTV.apk ]; then
  cp -f PlayStoreTV.apk "$OUT/gapps/PlayStoreTV.apk"
  echo "  copied existing ./PlayStoreTV.apk"
fi

cp "$CDIR"/* "$OUT/gapps/"
echo
echo "DONE. $OUT/gapps/ now contains (expected 8 files):"
ls -la "$OUT/gapps/" || true
echo
echo "Next steps (either):"
echo "  A) USB stick: copy gapps/ to <stick-root>/gapps/, plug into projector,"
echo "     toolkit button 5b (Stage from USB) → button 5 (Install) → button 3 (Restart)"
echo "  B) adb: adb push $OUT/gapps/* /data/local/tmp/gapps/ → button 5 → button 3"
