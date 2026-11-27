#!/usr/bin/env bash
# Build velocita.apk without Gradle: aapt2 + javac + d8 + apksigner.
# Needs JDK 17+ (JAVA_HOME or javac on PATH) and an Android SDK with build-tools and platform 36.
# KEYSTORE: signing key to use; without one, a new key.jks is created here (never commit it).
set -euo pipefail
cd "$(dirname "$0")"
SDK=${ANDROID_HOME:-$HOME/Android/Sdk}
BT=$SDK/build-tools/${BUILD_TOOLS:-36.1.0}
JAR=$SDK/platforms/android-36/android.jar
KS=${KEYSTORE:-key.jks}
[ -n "${JAVA_HOME:-}" ] && export PATH=$JAVA_HOME/bin:$PATH

rm -rf build && mkdir -p build/classes build/dex build/gen
$BT/aapt2 compile --dir res -o build/res.zip
$BT/aapt2 link -I "$JAR" --manifest AndroidManifest.xml --java build/gen -o build/base.apk \
  --min-sdk-version 26 --target-sdk-version 29 --version-code 8 --version-name 1.7 build/res.zip
javac --release 8 -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')

# self-check of the formatting before packaging
mkdir -p build/check && cat > build/check/Check.java <<'EOF'
package it.radio.velocita;
public class Check { public static void main(String[] a) {
  check(SpeedService.format(0f), "0");
  check(SpeedService.format(49.6f), "50");
  check(SpeedService.format(-1f), "--");
  check(SpeedService.format(Float.NaN), "--");
  check(String.valueOf(BtMediaBridge.shownState(3, 2)), "2");  // commanded pause wins over stale "playing"
  check(String.valueOf(BtMediaBridge.shownState(3, -1)), "3"); // no command: follow Bluetooth
  check(String.join("/", SpeedService.station("FM", "98,70", "RADIO 1        ")), "RADIO 1/FM 98.70 MHz");
  check(String.join("/", SpeedService.station("FM", "98,70", "\0\0\0  ")), "FM 98.70 MHz/Radio"); // no RDS
  check(String.join("/", SpeedService.station("AM", "1008", null)), "AM 1008 kHz/Radio");
  check(String.valueOf(SpeedService.station(null, "98,70", "")), "null");
  System.out.println("format ok"); }
  static void check(String got, String want) { if (!got.equals(want)) throw new AssertionError(got + " != " + want); } }
EOF
javac --release 8 -cp "build/classes:$JAR" -d build/check build/check/Check.java
java -cp "build/check:build/classes:$JAR" it.radio.velocita.Check

$BT/d8 --release --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q ../unsigned.apk classes.dex)
$BT/zipalign -f 4 build/unsigned.apk build/aligned.apk
[ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass velocita -keypass velocita \
  -alias velocita -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Velocita" >/dev/null 2>&1
$BT/apksigner sign --ks "$KS" --ks-pass pass:velocita --out velocita.apk build/aligned.apk
echo "built $(pwd)/velocita.apk"
