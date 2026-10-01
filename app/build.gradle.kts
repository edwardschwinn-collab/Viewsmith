// ============================================================================
// app/build.gradle.kts
// The recipe for building the PencilCam app itself.
// ============================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The app's unique ID on the phone. Changing it later = a "different app".
    namespace = "com.pencilcam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pencilcam.app"
        minSdk = 29          // Android 10+. Keeps photo saving simple.
        targetSdk = 35
        versionCode = 1      // Bump this number for every release you install over the top
        versionName = "0.1"
    }

    // ------------------------------------------------------------------------
    // SIGNING: every APK must be signed. We use ONE fixed test key that lives
    // in this repo, so every GitHub build is signed the same way and your
    // phone accepts new builds as UPDATES (no uninstalling each time).
    // This is a TEST key only. Keep the repo private. Before ever sharing or
    // selling the app, we will make a real secret release key.
    // ------------------------------------------------------------------------
    signingConfigs {
        create("shared") {
            storeFile = file("pencilcam-test.keystore")
            storePassword = "pencilcam"
            keyAlias = "pencilcam"
            keyPassword = "pencilcam"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Basic Android helpers and the Material look for buttons and sliders
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX: Google's easy camera library
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
}
