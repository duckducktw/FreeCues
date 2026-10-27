#!/usr/bin/env bash
# 無 Gradle 建置：javac → d8 → aapt2 → zipalign → apksigner
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/35.0.0
JAR=$SDK/platforms/android-35/android.jar
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/dex"

javac -source 17 -target 17 -nowarn -encoding UTF-8 -classpath "$JAR" -d "$OUT/classes" $(find src -name '*.java')
"$BT/d8" --min-api 26 --release --lib "$JAR" --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
"$BT/aapt2" link -o "$OUT/unsigned.apk" -I "$JAR" --manifest AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35
(cd "$OUT/dex" && zip -qj ../unsigned.apk classes.dex)
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

KS=${KEYSTORE:-$HOME/.android/freecues.jks}
if [ ! -f "$KS" ]; then
  mkdir -p "$(dirname "$KS")"
  keytool -genkeypair -keystore "$KS" -storepass freecues -keypass freecues -alias freecues \
    -keyalg RSA -keysize 2048 -validity 36500 -dname "CN=FreeCues" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$KS" --ks-pass pass:freecues --key-pass pass:freecues --out FreeCues.apk "$OUT/aligned.apk"
"$BT/apksigner" verify FreeCues.apk && ls -la FreeCues.apk
