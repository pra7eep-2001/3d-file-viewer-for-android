#!/usr/bin/env bash
# Headless rendering test: runs the app's real SceneRenderer on Mesa's OpenGL ES 3.2 (llvmpipe) and writes
# one PNG per scene. Catches shader compile/link failures, GL errors and blank frames without a phone.
#
# Needs: Mesa EGL/GLES (Ubuntu: libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri), JDK 17+, gradle,
#   ANDROID_JAR   android-all jar (API 34) used for compiling and for android.opengl.Matrix / Renderer interfaces
#   LWJGL_DIR     directory with lwjgl, lwjgl-egl, lwjgl-opengles 3.3.6 jars (+ natives-linux) from Maven Central
#   build/apk-nosdk/gen must exist (run tools/build-apk.sh once) because the app sources reference R.
# Usage: tools/run-gl-harness.sh [outDir] [model files...]   (default models: samples/bracket_binary.stl + AS1 U3DM)
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_JAR:?}"; : "${LWJGL_DIR:?}"; GRADLE="${GRADLE:-gradle}"
OUT="${1:-$ROOT/build/gl-harness}"; shift || true
MODELS=("$@"); [ ${#MODELS[@]} -eq 0 ] && MODELS=("$ROOT/samples/bracket_binary.stl" "$ROOT/app/src/test/resources/u3dm/as1-oc-214.u3dm")
WORK="$ROOT/build/gl-harness-work"; mkdir -p "$WORK/shim" "$WORK/harness"
"$GRADLE" --no-daemon -q -p "$ROOT/buildtools" -PandroidJar="$ANDROID_JAR" -PgenDir="$ROOT/build/apk-nosdk/gen" -PoutDir="$WORK/gradle" --project-cache-dir="$WORK/gradle-cache" appClassesJar runtimeJars
LW=$(ls "$LWJGL_DIR"/*.jar | tr '\n' ':')
javac -nowarn -d "$WORK/shim" -cp "$LW" $(find "$ROOT/tools/gl-harness/shim" -name '*.java')
APPCP="$WORK/gradle/apk/app-classes.jar:$(ls "$WORK"/gradle/apk/runtime-jars/*.jar | tr '\n' ':')"
javac -nowarn -d "$WORK/harness" -cp "$WORK/shim:$APPCP:$LW:$ANDROID_JAR" "$ROOT/tools/gl-harness/harness/GlHarness.java"
# shim first so it shadows the framework's native android.opengl.GLES30 / android.util.Log
EGL_PLATFORM=surfaceless java -Djava.awt.headless=true -Dorg.lwjgl.util.NoChecks=true -cp "$WORK/shim:$WORK/harness:$APPCP:$LW:$ANDROID_JAR" GlHarness "$OUT" "${MODELS[@]}"
