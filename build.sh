#!/system/bin/sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-$HOME/tools/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-37/android.jar"
VERSION_NAME="2.0"
VERSION_CODE="2"
PKG="com.appvolume"

BUILD="$ROOT/build"
OUT="$ROOT/out"
APK_RAW="$BUILD/base.apk"
APK_ALIGNED="$BUILD/aligned.apk"
APK_SIGNED="$OUT/AppVolume.apk"

command -v aapt2 >/dev/null || { echo "aapt2 not found in PATH" >&2; exit 1; }
command -v d8 >/dev/null || { echo "d8 not found in PATH" >&2; exit 1; }
command -v zipalign >/dev/null || { echo "zipalign not found in PATH" >&2; exit 1; }
command -v apksigner >/dev/null || { echo "apksigner not found in PATH" >&2; exit 1; }

rm -rf "$BUILD" "$OUT"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/stubs" "$BUILD/dex" "$OUT"

echo "[1/7] compiling Xposed API stubs"
find "$ROOT/app/stubs" -name '*.java' -print > "$BUILD/stubs-sources.txt"
javac -source 8 -target 8 -d "$BUILD/stubs" @"$BUILD/stubs-sources.txt"
(cd "$BUILD/stubs" && jar cf "$BUILD/stubs.jar" .)

echo "[2/7] compiling resources"
aapt2 compile --dir "$ROOT/app/res" -o "$BUILD/res.zip"

echo "[3/7] linking resources"
aapt2 link \
    -o "$APK_RAW" \
    -I "$PLATFORM" \
    --manifest "$ROOT/app/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    -A "$ROOT/app/assets" \
    --min-sdk-version 29 \
    --target-sdk-version 35 \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD/res.zip"

echo "[4/7] compiling Java"
find "$ROOT/app/src" "$BUILD/gen" -name '*.java' -print > "$BUILD/sources.txt"
javac \
    -source 8 -target 8 \
    -classpath "$PLATFORM:$BUILD/stubs.jar" \
    -d "$BUILD/classes" \
    @"$BUILD/sources.txt"

echo "[5/7] dexing"
find "$BUILD/classes" -name '*.class' -print > "$BUILD/classes.txt"
d8 --min-api 29 --lib "$PLATFORM" --lib "$BUILD/stubs.jar" \
    --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[6/7] packaging"
(cd "$BUILD" && zip -q -j base.apk dex/classes.dex)
zipalign -f 4 "$APK_RAW" "$APK_ALIGNED"

if [ ! -f "$ROOT/debug.keystore" ]; then
    echo "[7/7] creating signing key"
    keytool -genkeypair -v \
        -keystore "$ROOT/debug.keystore" \
        -storepass android -keypass android \
        -alias appvolume -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=AppVolume,O=AppVolume,C=US" >/dev/null 2>&1
fi

apksigner sign \
    --ks "$ROOT/debug.keystore" \
    --ks-pass pass:android \
    --key-pass pass:android \
    --ks-key-alias appvolume \
    --out "$APK_SIGNED" \
    "$APK_ALIGNED"

apksigner verify --print-certs "$APK_SIGNED" | head -6

echo "[+] module package"
rm -rf "$ROOT/module/system" "$ROOT/module/product"
rm -f "$ROOT/module/AppVolume.apk" "$ROOT/module/post-fs-data.sh"
cp "$APK_SIGNED" "$ROOT/module/AppVolume.apk"
chmod 0644 "$ROOT/module/AppVolume.apk"
chmod 0755 "$ROOT/module/service.sh" "$ROOT/module/uninstall.sh" "$ROOT/module/customize.sh" 2>/dev/null || true
(cd "$ROOT/module" && zip -q -r "$OUT/AppVolume-v$VERSION_NAME.zip" . -x '*.DS_Store' -x 'service.log')

echo
echo "APK:    $APK_SIGNED"
echo "Module: $OUT/AppVolume-v$VERSION_NAME.zip"
ls -lh "$APK_SIGNED" "$OUT/AppVolume-v$VERSION_NAME.zip"
