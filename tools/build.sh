#!/bin/bash
# Build script for DBS7 Root Toolkit (no gradle needed).
#
# Requirements: JDK 17 (keytool + javac), android.jar (API 34), build-tools
# (aapt2, d8, apksigner). Adjust SDK/BT below or export SDK/BT.
#
# The signing keystore is generated on first run (auto-generated debug key,
# password = dbstool123). If you plan to ship updates to users, keep the
# generated keystore safe: APK updates must be signed with the same key.
set -e
SDK=${SDK:-/tmp/sdk/android-34}
BT=${BT:-/tmp/sdk/android-14}
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build"
KS="$ROOT/tools/dbstoolkit.keystore"
KS_PASS=${KS_PASS:-dbstool123}
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen"

echo "[1/5] aapt2 compile+link"
"$BT/aapt2" compile --dir "$ROOT/app/src/main/res" -o "$OUT/res.zip"
"$BT/aapt2" link -o "$OUT/base.apk" -I "$SDK/android.jar" \
  --manifest "$ROOT/app/src/main/AndroidManifest.xml" \
  --java "$OUT/gen" "$OUT/res.zip"

echo "[2/5] javac"
javac -source 11 -target 11 -classpath "$SDK/android.jar:$OUT/gen" -d "$OUT/classes" \
  "$ROOT/app/src/main/java/com/dbs7/rootkit/MainActivity.java" \
  "$OUT/gen/com/dbs7/rootkit/R.java" 2>&1 | grep -v "^Note:" || true

echo "[3/5] d8"
"$BT/d8" --release --lib "$SDK/android.jar" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "[4/5] package"
cd "$OUT/dex" && zip -qj "$OUT/base.apk" classes.dex
cd "$OUT"

echo "[5/5] sign"
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -alias dbstool \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=DBS7 Root Toolkit, O=DBS7, C=PL"
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass "pass:$KS_PASS" --ks-key-alias dbstool \
  --out "$OUT/DBS7RootToolkit.apk" "$OUT/base.apk"

"$BT/apksigner" verify "$OUT/DBS7RootToolkit.apk" && echo "OK: $OUT/DBS7RootToolkit.apk"
ls -la "$OUT/DBS7RootToolkit.apk"
