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
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // The STEP/IGES converter is a static executable shipped as a "library" so Android extracts it
    // to the native library directory, the only location an app may execute from on Android 10+.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
    packaging { jniLibs { useLegacyPackaging = true } }

    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Intentionally no AndroidX / Material dependencies: the UI is built on framework widgets so the
    // app also builds without access to Google's Maven repository (see BUILDING.md).
    testImplementation("junit:junit:4.13.2")
}
