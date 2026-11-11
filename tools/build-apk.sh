#!/usr/bin/env bash
# Builds a signed, installable APK of Universal 3D Viewer WITHOUT the Android SDK / Android Gradle Plugin.
#
# Needs on PATH (or via env vars): java 17+, gradle 8.x, aapt2, zipalign, apksigner (e.g. Ubuntu packages
# android-sdk-build-tools + apksigner), python3, plus:
#   ANDROID_JAR  path to an android.jar for API 35 (the Robolectric "android-all" jar works; see BUILDING.md)
#   R8_JAR       path to r8lib.jar (https://storage.googleapis.com/r8-releases/raw/<version>/r8lib.jar)
# Optional: BUILD_TOOLS (dir containing aapt2/zipalign/apksigner), KEYSTORE/KEY_ALIAS/KEY_PASS, OUT_DIR.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP="$ROOT/app/src/main"
OUT="${OUT_DIR:-$ROOT/build/apk-nosdk}"
BT="${BUILD_TOOLS:-}"
AAPT2="${AAPT2:-${BT:+$BT/}aapt2}"; ZIPALIGN="${ZIPALIGN:-${BT:+$BT/}zipalign}"; APKSIGNER="${APKSIGNER:-${BT:+$BT/}apksigner}"
GRADLE="${GRADLE:-gradle}"
: "${ANDROID_JAR:?set ANDROID_JAR}"; : "${R8_JAR:?set R8_JAR}"
KEYSTORE="${KEYSTORE:-$ROOT/tools/debug.keystore}"; KEY_ALIAS="${KEY_ALIAS:-androiddebugkey}"; KEY_PASS="${KEY_PASS:-android}"
VERSION_CODE="${VERSION_CODE:-2}"; VERSION_NAME="${VERSION_NAME:-1.0.1}"
MIN_SDK=29; TARGET_SDK=35

rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/dex" "$OUT/compiled"
echo "==> [1/7] aapt2 compile resources"
"$AAPT2" compile --dir "$APP/res" -o "$OUT/compiled/res.zip"
echo "==> [2/7] aapt2 link (manifest + resources -> base.apk, R.java)"
ASSETS=(); [ -d "$APP/assets" ] && ASSETS=(-A "$APP/assets")
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$APP/AndroidManifest.xml" --java "$OUT/gen" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --auto-add-overlay "${ASSETS[@]}" "$OUT/compiled/res.zip"
echo "==> [3/7] compile Kotlin sources (Gradle, Kotlin JVM plugin, compileOnly android.jar)"
"$GRADLE" --no-daemon -q -p "$ROOT/buildtools" -PandroidJar="$ANDROID_JAR" -PgenDir="$OUT/gen" -PoutDir="$OUT/gradle" --project-cache-dir="$OUT/gradle-cache" appClassesJar runtimeJars
echo "==> [4/7] D8 dexing"
java -cp "$R8_JAR" com.android.tools.r8.D8 --release --min-api $MIN_SDK --lib "$ANDROID_JAR" --output "$OUT/dex" "$OUT/gradle/apk/app-classes.jar" "$OUT"/gradle/apk/runtime-jars/*.jar
echo "==> [5/7] package (base.apk + classes.dex + native libs)"
python3 - "$OUT/base.apk" "$OUT/unsigned.apk" "$OUT/dex" "$APP/jniLibs" <<'PY'
import sys, zipfile, os
base, out, dexdir, jni = sys.argv[1:5]
with zipfile.ZipFile(base) as zin, zipfile.ZipFile(out, "w") as zout:
    for item in zin.infolist():
        data = zin.read(item.filename)
        zout.writestr(item, data, compress_type=item.compress_type)
    for f in sorted(os.listdir(dexdir)):
        if f.endswith(".dex"):
            zout.write(os.path.join(dexdir, f), f, compress_type=zipfile.ZIP_DEFLATED)
    if os.path.isdir(jni):
        for abi in sorted(os.listdir(jni)):
            d = os.path.join(jni, abi)
            if not os.path.isdir(d): continue
            for lib in sorted(os.listdir(d)):
                if lib.endswith(".so"):
                    # stored uncompressed and later page-aligned by zipalign -p
                    zout.write(os.path.join(d, lib), f"lib/{abi}/{lib}", compress_type=zipfile.ZIP_STORED)
print("packaged", out)
PY
echo "==> [6/7] zipalign"
"$ZIPALIGN" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
echo "==> [7/7] sign"
if [ ! -f "$KEYSTORE" ]; then
  echo "    generating debug keystore $KEYSTORE"
  keytool -genkeypair -v -keystore "$KEYSTORE" -storepass "$KEY_PASS" -keypass "$KEY_PASS" -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Universal 3D Viewer Debug, O=Universal3DViewer, C=US" >/dev/null 2>&1
fi
FINAL="$OUT/Universal3DViewer-${VERSION_NAME}.apk"
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" --ks-key-alias "$KEY_ALIAS" --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true --out "$FINAL" "$OUT/aligned.apk"
"$APKSIGNER" verify --verbose --print-certs "$FINAL" | head -5
echo "==> APK: $FINAL ($(du -h "$FINAL" | cut -f1))"
