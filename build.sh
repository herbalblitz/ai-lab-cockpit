#!/usr/bin/env bash
# AI-Lab Cockpit APK build — no Gradle, no AndroidX.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-arm64}"
export PATH="$JAVA_HOME/bin:$PATH"
JAVA="$JAVA_HOME/bin/java"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
KEYTOOL="$JAVA_HOME/bin/keytool"

AAPT2="${AAPT2:-aapt2}"
ZIPALIGN="${ZIPALIGN:-zipalign}"
if [ -f "$ROOT/tools/android-28.jar" ]; then
  ANDROID_JAR="$ROOT/tools/android-28.jar"
else
  ANDROID_JAR="$ROOT/tools/android.jar"
fi
R8_JAR="$ROOT/tools/r8.jar"
APKSIGNER_JAR="$ROOT/tools/apksigner.jar"

BUILD="$ROOT/build"
DIST="$ROOT/dist"
SIGNING="$ROOT/signing"
APP="$ROOT/app/src/main"
VERSION_NAME="0.1.0"
VERSION_CODE="1"

rm -rf "$BUILD"
mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex" "$DIST" "$SIGNING"

echo "== 1/7 compile resources"
"$AAPT2" compile --dir "$APP/res" -o "$BUILD/res.zip"

echo "== 2/7 link resources + manifest"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$APP/AndroidManifest.xml" \
  -R "$BUILD/res.zip" \
  --java "$BUILD/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 35 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay

echo "== 3/7 compile Java"
find "$APP/java" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" \
  --release 8 \
  -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt"

echo "== 4/7 dex"
"$JAR" cf "$BUILD/classes.jar" -C "$BUILD/classes" .
"$JAVA" -cp "$R8_JAR" com.android.tools.r8.D8 \
  --release \
  --min-api 26 \
  --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  "$BUILD/classes.jar"

echo "== 5/7 package + align"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
( cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes.dex )
"$ZIPALIGN" -f 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "== 6/7 sign"
if [ ! -f "$SIGNING/ailabcockpit.jks" ]; then
  STORE_PASS="$(openssl rand -hex 18)"
  "$KEYTOOL" -genkeypair -v \
    -keystore "$SIGNING/ailabcockpit.jks" \
    -alias ailabcockpit \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$STORE_PASS" -keypass "$STORE_PASS" \
    -dname "CN=AI-Lab Cockpit, OU=AI Lab, O=herbalblitz, C=VN" >/dev/null 2>&1
  printf 'storePassword=%s\nkeyPassword=%s\nkeyAlias=ailabcockpit\n' \
    "$STORE_PASS" "$STORE_PASS" > "$SIGNING/keystore.properties"
  chmod 600 "$SIGNING/keystore.properties" "$SIGNING/ailabcockpit.jks"
fi
STORE_PASS="$(sed -n 's/^storePassword=//p' "$SIGNING/keystore.properties")"
KEY_PASS="$(sed -n 's/^keyPassword=//p' "$SIGNING/keystore.properties")"
KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$SIGNING/keystore.properties")"

OUT="$DIST/AiLabCockpit-$VERSION_NAME.apk"
if command -v apksigner >/dev/null 2>&1; then
  apksigner sign --ks "$SIGNING/ailabcockpit.jks" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT" "$BUILD/aligned.apk"
else
  "$JAVA" -jar "$APKSIGNER_JAR" sign --ks "$SIGNING/ailabcockpit.jks" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$STORE_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$OUT" "$BUILD/aligned.apk"
fi

echo "== 7/7 verify"
"$AAPT2" dump badging "$OUT" | head -6
if command -v apksigner >/dev/null 2>&1; then
  apksigner verify --print-certs "$OUT" | head -8
else
  "$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT" | head -8
fi
sha256sum "$OUT" | tee "$OUT.sha256"
ls -lh "$OUT"

echo "OK: $OUT"
