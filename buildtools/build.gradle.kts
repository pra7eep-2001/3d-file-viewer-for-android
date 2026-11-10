import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/*
 * SDK-less build for Universal 3D Viewer.
 *
 * Compiles the app's Kotlin sources against an android.jar-compatible platform jar and runs the JUnit
 * tests on the host JVM (the importers are pure Kotlin). The APK packaging steps (aapt2, D8, zipalign,
 * apksigner) are driven by ../tools/build-apk.sh which calls this build for the compile step.
 *
 * Properties:
 *   -PandroidJar=/path/to/android.jar   platform jar to compile against (required for compile)
 *   -PoutDir=/path/to/build             build directory (default: build)
 *   -PgenDir=/path/to/generated/sources directory with generated R.java (optional)
 */
plugins {
    kotlin("jvm") version "2.1.20"
}

val androidJar: String? = (project.findProperty("androidJar") as String?) ?: System.getenv("ANDROID_JAR")
val outDir: String? = project.findProperty("outDir") as String?
val genDir: String? = project.findProperty("genDir") as String?
if (outDir != null) layout.buildDirectory.set(file(outDir))

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17); freeCompilerArgs.add("-Xno-call-assertions"); freeCompilerArgs.add("-Xno-param-assertions"); freeCompilerArgs.add("-Xno-receiver-assertions") } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }

sourceSets {
    main {
        kotlin.srcDirs("../app/src/main/java")
        java.srcDirs(listOfNotNull("../app/src/main/java", genDir))
    }
    test {
        kotlin.srcDirs("../app/src/test/java")
        resources.srcDirs("../app/src/test/resources")
    }
}

dependencies {
    if (androidJar != null) compileOnly(files(androidJar))
    testImplementation("junit:junit:4.13.2")
    if (androidJar != null) testRuntimeOnly(files(androidJar))
}

tasks.withType<Test> {
    maxHeapSize = "3g"
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("samples.dir", file("../app/src/test/resources").absolutePath)
    systemProperty("extra.samples.dir", System.getenv("EXTRA_SAMPLES_DIR") ?: "")
}

/** Jar of compiled app classes (input to D8). */
val appClassesJar by tasks.registering(Jar::class) {
    archiveFileName.set("app-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("apk"))
    from(sourceSets.main.get().output)
}

/** Copies the runtime jars (kotlin-stdlib etc.) that must be dexed into the APK. */
val runtimeJars by tasks.registering(Copy::class) {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("apk/runtime-jars"))
}
