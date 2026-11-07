#!/bin/bash
# Aral TV Android — بیلد دستی APK بدون Gradle (aapt2 → javac → d8 → apksigner)
# پیش‌نیاز: ANDROID_HOME با platforms;android-35 و build-tools;35.0.0 و JDK 11+
set -e

: "${ANDROID_HOME:?متغیر ANDROID_HOME تنظیم نشده است}"

BT="$ANDROID_HOME/build-tools/35.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-35/android.jar"
JAVAC="${JAVAC:-javac}"
KEYTOOL="${KEYTOOL:-keytool}"

PROJ="$(cd "$(dirname "$0")/.." && pwd)"   # ریشهٔ پروژه (شامل app/)
MAIN="$PROJ/app/src/main"
BUILD="$PROJ/build"
OUT="$PROJ/dist"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$OUT"

echo "── [1/7] aapt2 compile ──"
"$BT/aapt2" compile --dir "$MAIN/res" -o "$BUILD/res.zip"

echo "── [2/7] aapt2 link ──"
"$BT/aapt2" link \
  -o "$BUILD/linked.apk" \
  -I "$PLATFORM" \
  --manifest "$MAIN/AndroidManifest.xml" \
  --min-sdk-version 24 \
  --target-sdk-version 34 \
  --version-code 10702 \
  --version-name 1.7.2 \
  --java "$BUILD/gen" \
  --auto-add-overlay \
  "$BUILD/res.zip"

echo "── [3/7] javac ──"
"$JAVAC" --release 8 \
  -classpath "$PLATFORM" \
  -d "$BUILD/classes" \
  -encoding UTF-8 \
  -nowarn \
  $(find "$BUILD/gen" -name '*.java') \
  $(find "$MAIN/java" -name '*.java')

echo "── [4/7] d8 (dex) ──"
"$BT/d8" --release \
  --lib "$PLATFORM" \
  --min-api 24 \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class')

echo "── [5/7] بسته‌بندی ──"
cp "$BUILD/linked.apk" "$BUILD/unsigned.apk"
cd "$BUILD"
zip -q -j unsigned.apk dex/classes.dex
cd "$MAIN"
zip -q -r "$BUILD/unsigned.apk" assets

echo "── [6/7] zipalign ──"
"$BT/zipalign" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "── [7/7] امضا ──"
KS="$PROJ/keystore/araltv-release.jks"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -v \
    -keystore "$KS" -alias araltv \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass araltv2024 -keypass araltv2024 \
    -dname "CN=Aral TV, OU=Aral, O=Aral Dev, L=Tehran, C=IR"
fi
"$BT/apksigner" sign \
  --ks "$KS" --ks-key-alias araltv \
  --ks-pass pass:araltv2024 --key-pass pass:araltv2024 \
  --out "$OUT/AralTV-v1.7.2.apk" \
  "$BUILD/aligned.apk"

"$BT/apksigner" verify "$OUT/AralTV-v1.7.2.apk"
echo ""
echo "✔ APK ساخته شد: $OUT/AralTV-v1.7.2.apk"
