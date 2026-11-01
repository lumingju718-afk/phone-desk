#!/usr/bin/env bash
# Linux/macOS 构建脚本（CI 与本地通用），与 build.ps1 步骤一致。
# 需要：JDK 17、Android build-tools 34.0.0、platforms/android-34/android.jar
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
APP="$ROOT/app"
BUILD="$ROOT/build"
DIST="$ROOT/dist"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
BT="${BUILD_TOOLS:-$SDK/build-tools/34.0.0}"
AJAR="${PLATFORM_JAR:-$SDK/platforms/android-34/android.jar}"

for p in "$APP/AndroidManifest.xml" "$BT/aapt2" "$AJAR"; do
  [ -e "$p" ] || { echo "missing dependency: $p" >&2; exit 1; }
done

rm -rf "$BUILD" "$DIST"
mkdir -p "$BUILD/res" "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$APP/res" -o "$BUILD/res"

mapfile -t FLATS < <(find "$BUILD/res" -name '*.flat')
[ "${#FLATS[@]}" -gt 0 ] || { echo "no resources compiled" >&2; exit 1; }

echo "==> aapt2 link"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$AJAR" \
  --manifest "$APP/AndroidManifest.xml" --java "$BUILD/gen" "${FLATS[@]}"

echo "==> javac"
mapfile -t SOURCES < <(find "$APP/src" "$BUILD/gen" -name '*.java')
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" \
  -d "$BUILD/classes" "${SOURCES[@]}"

echo "==> jar + d8"
jar cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$BT/d8" --lib "$AJAR" --min-api 26 --output "$BUILD/dex" "$BUILD/classes.jar"

echo "==> pack classes.dex"
python3 - "$BUILD" <<'PY'
import shutil, sys, zipfile
build = sys.argv[1]
src = f"{build}/base.apk"
dst = f"{build}/base_with_dex.apk"
shutil.copyfile(src, dst)
with zipfile.ZipFile(dst, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(f"{build}/dex/classes.dex", "classes.dex")
PY

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$BUILD/base_with_dex.apk" "$BUILD/aligned.apk"

echo "==> sign"
KS="$BUILD/ci.keystore"
keytool -genkeypair -keystore "$KS" -alias ci -keyalg RSA -keysize 2048 \
  -validity 10000 -storepass cipass -keypass cipass \
  -dname "CN=CI,OU=DSH,O=DSH,L=CN,ST=CN,C=CN" > /dev/null 2>&1
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:cipass --key-pass pass:cipass \
  --out "$DIST/phone-desk.apk" "$BUILD/aligned.apk"
"$BT/apksigner" verify --print-certs "$DIST/phone-desk.apk"

echo "OK  $DIST/phone-desk.apk"
