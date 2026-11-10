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
val defaultGen = file("../build/apk-nosdk/gen")
/** Directory with the aapt2-generated R.java; defaults to the last tools/build-apk.sh output when present. */
val genDir: String? = (project.findProperty("genDir") as String?) ?: (if (defaultGen.isDirectory) defaultGen.absolutePath else null)
/** -ProboSmoke=true runs the Robolectric UI smoke tests (needs ../build/apk-nosdk/base.apk from tools/build-apk.sh). */
val roboSmoke: Boolean = (project.findProperty("roboSmoke") as String?) == "true"
if (outDir != null) layout.buildDirectory.set(file(outDir))

val jvmTargetVersion = if (roboSmoke) JvmTarget.JVM_1_8 else JvmTarget.JVM_17 // Robolectric 3.8's ASM cannot read Java 17 class files
kotlin { compilerOptions { jvmTarget.set(jvmTargetVersion); freeCompilerArgs.add("-Xno-call-assertions"); freeCompilerArgs.add("-Xno-param-assertions"); freeCompilerArgs.add("-Xno-receiver-assertions") } }
java { sourceCompatibility = if (roboSmoke) JavaVersion.VERSION_1_8 else JavaVersion.VERSION_17; targetCompatibility = if (roboSmoke) JavaVersion.VERSION_1_8 else JavaVersion.VERSION_17 }

sourceSets {
    main {
        kotlin.srcDirs("../app/src/main/java")
        java.srcDirs(listOfNotNull("../app/src/main/java", genDir))
        // Without generated R.java (no aapt2 run yet) the UI package cannot compile; importers/renderer still can.
        if (genDir == null) kotlin.exclude("com/universal3d/viewer/ui/**")
    }
    test {
        kotlin.srcDirs("../app/src/test/java")
        resources.srcDirs("../app/src/test/resources")
        // The Robolectric UI smoke test only compiles in -ProboSmoke=true mode.
        if (!roboSmoke) kotlin.exclude("com/universal3d/viewer/ui/**")
    }
}

dependencies {
    if (androidJar != null) compileOnly(files(androidJar))
    testImplementation("junit:junit:4.13.2")
    if (roboSmoke) {
        testImplementation("org.robolectric:robolectric:3.8") // 3.8: last version without androidx.test (Google Maven) dependencies
        val roboRuntimeJar = (project.findProperty("roboRuntimeJar") as String?) ?: androidJar
        if (androidJar != null) testCompileOnly(files(androidJar))
        if (roboRuntimeJar != null) testRuntimeOnly(files(roboRuntimeJar)) // Java-8 era framework jar (API 27): annotation types (android.app.Application) must resolve outside the sandbox, like AGP's mockable jar
    } else if (androidJar != null) testRuntimeOnly(files(androidJar))
}

tasks.withType<Test> {
    maxHeapSize = "3g"
    testLogging { events("failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("samples.dir", file("../app/src/test/resources").absolutePath)
    systemProperty("extra.samples.dir", System.getenv("EXTRA_SAMPLES_DIR") ?: "")
    if (roboSmoke) {
        val root = file("..").absolutePath
        systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
        systemProperty("robolectric.dependency.repo.id", "central-mirror")
        // Offline mode: the directory must contain android-all-8.1.0-robolectric-4611349.jar (Maven Central).
        (project.findProperty("roboDependencyDir") as String?)?.let { systemProperty("robolectric.offline", "true"); systemProperty("robolectric.dependency.dir", it) }
        systemProperty("robolectric.logging.enabled", "true")
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED")
        include("**/*SmokeTest*")
    } else {
        exclude("**/*SmokeTest*")
    }
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

/** Writes the test runtime classpath to build/test-classpath.txt (used to run the Robolectric smoke test on a JDK 8 JVM). */
val printTestClasspath by tasks.registering {
    dependsOn("testClasses")
    val out = layout.buildDirectory.file("test-classpath.txt")
    doLast {
        val cp = (sourceSets.test.get().runtimeClasspath).files.joinToString(File.pathSeparator) { it.absolutePath }
        out.get().asFile.writeText(cp)
    }
}
