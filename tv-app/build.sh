#!/usr/bin/env bash
# Builds TV Pilot TV without Gradle. Usage: MIN_SDK=30 ./build.sh  -> TVPilotTV.apk
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_SDK:-$HOME/Android/Sdk}
[ -n "${JAVA_HOME:-}" ] && export PATH=$JAVA_HOME/bin:$PATH
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar | sort -V | tail -1)"
AAPT2=${AAPT2:-$BT/aapt2}; ZA=${ZIPALIGN:-$BT/zipalign}
MIN=${MIN_SDK:-30}
rm -rf out && mkdir -p out/gen out/classes out/dex out/gen/dev/syed/tvpilot/tv
$AAPT2 compile --dir app/res -o out/res.zip
$AAPT2 link -o out/base.apk -I "$AJ" --manifest app/AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version "$MIN" --target-sdk-version 34
javac -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -d out/classes $(find app/src out/gen -name '*.java')
"$BT/d8" --release --min-api "$MIN" --lib "$AJ" --output out/dex $(find out/classes -name '*.class')
(cd out/dex && zip -qj ../base.apk classes*.dex)
$ZA -f -p 4 out/base.apk out/aligned.apk
[ -f tvpilottv.keystore ] || keytool -genkeypair -keystore tvpilottv.keystore -alias tvpilottv -keyalg RSA -keysize 2048 \
  -validity 20000 -storepass tvpilottv -keypass tvpilottv -dname "CN=TV Pilot TV" >/dev/null 2>&1
"$BT/apksigner" sign --ks tvpilottv.keystore --ks-pass pass:tvpilottv --key-pass pass:tvpilottv --out TVPilotTV.apk out/aligned.apk
echo "Built TVPilotTV.apk ($(du -h TVPilotTV.apk | cut -f1))"
