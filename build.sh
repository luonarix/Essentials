#!/usr/bin/env bash
# Build Essentials.apk on-device (aarch64 Termux/PRoot). No Gradle, no Android Studio.
# Toolchain: aapt2 + zipalign (static arm64), JDK 21, d8/apksigner (build-tools 36 jars).
set -euo pipefail

ROOT=/tmp/opencode
APP=$ROOT/Essentials
SDK=$ROOT/sdk/android-35
BT=$ROOT/bt36/android-16
TOOLS=$ROOT/tools/build-tools
AJAR=$SDK/android.jar

cd "$APP"
rm -rf build
mkdir -p build/res build/classes build/dex build/gen keys

# Naming rule: major release -> Essentials_v<major>.0.apk, small patch -> Essentials_v<major>.<patch>.apk
VERSION_NAME="1.1.0"
VERSION_CODE=37
OUTDIR=/storage/emulated/0/Download/Essentials
APKNAME="Essentials_v${VERSION_NAME}.apk"

echo "[1/6] compile resources"
"$TOOLS/aapt2" compile --dir res -o build/res.zip

echo "[2/6] link resources + manifest (+ R.java)"
"$TOOLS/aapt2" link \
  -o build/base.apk -I "$AJAR" --manifest AndroidManifest.xml \
  --java build/gen --custom-package dev.local.essentials \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  build/res.zip

echo "[3/6] compile java"
find src build/gen -name '*.java' > build/sources.txt
javac -g -source 8 -target 8 -bootclasspath "$AJAR" -d build/classes @build/sources.txt
[ -n "$(find build/classes -name '*.class' -print -quit)" ] || { echo "javac failed" >&2; exit 1; }

echo "[4/6] dex (d8)"
java -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 24 --lib "$AJAR" --output build/dex \
  $(find build/classes -name '*.class')

echo "[5/6] package + align"
python3 - <<'PY'
import zipfile, shutil
shutil.copy('build/base.apk', 'build/unsigned.apk')
with zipfile.ZipFile('build/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('build/dex/classes.dex', 'classes.dex')
PY
"$TOOLS/zipalign" -f 4 build/unsigned.apk build/aligned.apk

echo "[6/6] sign"
# Keystore is NOT part of the repo. Point these at your own signing key:
#   export KS_FILE=keys/essentials.keystore
#   export KS_PASS='your-keystore-password'
KS_FILE="${KS_FILE:-keys/essentials.keystore}"
if [ -z "${KS_PASS:-}" ]; then
  echo "Thiếu KS_PASS. Đặt biến môi trường KS_PASS (mật khẩu keystore) rồi chạy lại." >&2
  exit 1
fi
if [ ! -f "$KS_FILE" ]; then
  keytool -genkeypair -keystore "$KS_FILE" -storepass "$KS_PASS" \
    -alias essentials -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Essentials, O=Personal, C=VN" >/dev/null 2>&1
fi
java -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS_FILE" --ks-pass env:KS_PASS --key-pass env:KS_PASS \
  --min-sdk-version 24 --out Essentials.apk build/aligned.apk
java -jar "$BT/lib/apksigner.jar" verify Essentials.apk

mkdir -p "$OUTDIR"
cp Essentials.apk "$OUTDIR/$APKNAME"
ls -lh "$OUTDIR/$APKNAME"
