# Building Universal 3D Viewer

There are two ways to build the APK.

## A. Android Studio / Android Gradle Plugin (standard)

The repository is a normal Android Studio project (`settings.gradle.kts`, `build.gradle.kts`, `app/`).

```bash
# Requirements: Android Studio Ladybug or newer (AGP 8.7, Kotlin 2.1), JDK 17, SDK platform 35
./gradlew :app:assembleRelease        # or open the project in Android Studio and press Run
# -> app/build/outputs/apk/release/app-release.apk
```

The app has **no AndroidX/Material dependencies** (framework widgets only), so the only artefacts Gradle
needs from Google's Maven repository are the Android Gradle Plugin itself and `aapt2`.
The STEP/IGES converter (`app/src/main/jniLibs/arm64-v8a/libu3dconv.so`) is a prebuilt static executable,
see `native/converter/README.md` for how it is produced; AGP packages it like any native library.

> This AGP route could **not** be executed in the environment where this project was authored
> (Google's download servers were blocked there), so it is provided as a conventional configuration
> rather than a verified build. The route below is the one that produced the delivered APK.

## B. SDK-less build (what produced the delivered APK)

`tools/build-apk.sh` builds a signed APK with only:

| Tool | Source used |
|------|-------------|
| `aapt2`, `zipalign`, `apksigner` | Ubuntu packages `android-sdk-build-tools` (29.0.3) + `apksigner` |
| Kotlin compiler | Gradle 8.14 + Kotlin JVM plugin 2.1.20 (`buildtools/`), Maven Central (or its Google-hosted mirror) |
| `android.jar` (API 34) | Robolectric's `org.robolectric:android-all:14-robolectric-10818077` jar from Maven Central: a complete framework jar incl. `resources.arsc` |
| D8 (dexer) | `r8lib.jar` 8.5.35 from `https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar` |
| Java | OpenJDK 21 (17+ works) |

```bash
sudo apt-get install android-sdk-build-tools apksigner openjdk-21-jdk-headless python3
M=https://maven-central.storage-download.googleapis.com/maven2     # or https://repo1.maven.org/maven2
curl -o /tmp/android-all.jar $M/org/robolectric/android-all/14-robolectric-10818077/android-all-14-robolectric-10818077.jar
curl -o /tmp/r8lib.jar https://storage.googleapis.com/r8-releases/raw/8.5.35/r8lib.jar

export ANDROID_JAR=/tmp/android-all.jar R8_JAR=/tmp/r8lib.jar \
       BUILD_TOOLS=/usr/lib/android-sdk/build-tools/29.0.3 GRADLE=gradle
tools/build-apk.sh
# -> build/apk-nosdk/Universal3DViewer-1.0.0.apk  (signed with tools/debug.keystore, generated on first run)
```

Steps performed by the script: `aapt2 compile` → `aapt2 link` (manifest, resources, `R.java`) →
Kotlin compile (`buildtools/`, `compileOnly android.jar`) → `D8 --min-api 29` → zip packaging
(`classes.dex`, `lib/arm64-v8a/libu3dconv.so` stored uncompressed) → `zipalign -p 4` → `apksigner` (v1+v2+v3).

Why API 34 and not 35: Ubuntu's `aapt2` 29.0.3 cannot read the API 35 resource table; the code uses no
API above 30, and `targetSdkVersion` is still 35.

To sign with your own key: `KEYSTORE=/path/my.keystore KEY_ALIAS=alias KEY_PASS=secret tools/build-apk.sh`.

## Tests

```bash
cd buildtools
gradle --no-daemon -PandroidJar=$ANDROID_JAR test                 # importer/geometry unit tests (pure JVM)
ANDROID_JAR=... ROBO_RUNTIME_JAR=/tmp/android-all-8.1.0-robolectric-4611349.jar ROBO_DEP_DIR=/tmp \
  JAVA8=/usr/lib/jvm/java-8-openjdk-amd64/bin/java tools/run-ui-smoke.sh   # Robolectric UI smoke test
```

The UI smoke test (`app/src/test/.../MainActivitySmokeTest.kt`) starts the real `MainActivity` on Robolectric 3.8
(API 27 framework, legacy resource mode): layout inflation, every toolbar/panel button, the formats dialog,
the overflow menu, loading an STL through `ACTION_VIEW`, Info/Parts panels and a configuration change.
Robolectric 3.8 is used because 4.x depends on `androidx.test` (Google Maven only); it needs JDK 8 for the
test JVM and `org.robolectric:android-all:8.1.0-robolectric-4611349` from Maven Central in `ROBO_DEP_DIR`.

Headless rendering test (runs the real renderer on Mesa's OpenGL ES 3.2, writes PNGs, fails on GL errors):

```bash
sudo apt-get install libegl1 libgles2 libegl-mesa0 libgl1-mesa-dri
# LWJGL 3.3.6 jars from Maven Central: lwjgl, lwjgl-egl, lwjgl-opengles (+ -natives-linux for lwjgl and lwjgl-opengles)
ANDROID_JAR=... LWJGL_DIR=/path/to/lwjgl tools/run-gl-harness.sh build/gl-harness   # needs build/apk-nosdk/gen
```

The native converter has its own tests (`native/converter/README.md`), including running the ARM64
binary under `qemu-aarch64-static`.
