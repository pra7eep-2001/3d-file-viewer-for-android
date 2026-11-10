#!/usr/bin/env bash
# Runs the Robolectric UI smoke test (real framework code for layout inflation, clicks and intent handling).
# Robolectric 3.8 is used because newer versions need androidx.test from Google's Maven repository; it requires
# a JDK 8 JVM for the test run itself (Gradle may run on a newer JDK).
#   ANDROID_JAR      API 34 android-all jar (compile)            ROBO_RUNTIME_JAR  android-all-8.1.0-robolectric-4611349.jar
#   ROBO_DEP_DIR     directory containing the runtime jar          JAVA8             path to a JDK 8 `java` binary
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_JAR:?}"; : "${ROBO_RUNTIME_JAR:?}"; : "${ROBO_DEP_DIR:?}"; : "${JAVA8:?}"
GRADLE="${GRADLE:-gradle}"; OUT="${OUT_DIR:-$ROOT/build/robo}"
"$GRADLE" --no-daemon -q -p "$ROOT/buildtools" -PandroidJar="$ANDROID_JAR" -ProboRuntimeJar="$ROBO_RUNTIME_JAR" -ProboSmoke=true -PoutDir="$OUT" --project-cache-dir="$OUT-cache" printTestClasspath
cd "$ROOT/buildtools"   # @Config(manifest=...) paths are relative to this directory
"$JAVA8" -Xmx3g -Drobolectric.offline=true -Drobolectric.dependency.dir="$ROBO_DEP_DIR" -cp "$(cat "$OUT/test-classpath.txt")" org.junit.runner.JUnitCore com.universal3d.viewer.ui.MainActivitySmokeTest
