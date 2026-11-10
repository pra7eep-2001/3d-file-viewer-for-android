plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.universal3d.viewer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.universal3d.viewer"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64", "armeabi-v7a", "x86") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The STEP/IGES converter is a static executable shipped as a "library" so Android extracts it
    // to the native library directory, the only location an app may execute from on Android 10+.
    // arm64-v8a and x86_64 carry the real Open CASCADE converter; armeabi-v7a and x86 carry a 5 KB stub that
    // reports "needs a 64-bit device" so the APK still installs (and all mesh formats work) on 32-bit devices.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    packaging { jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libu3dconv.so" } }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Intentionally no AndroidX / Material dependencies: the UI is built on framework widgets so the
    // app also builds without access to Google's Maven repository (see BUILDING.md).
    testImplementation("junit:junit:4.13.2")
}
