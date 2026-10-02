// ============================================================================
// app/build.gradle.kts
// The build recipe for the ViewSmith app itself.
// ============================================================================
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // The app's ID on the phone. It still says "pencilcam" from before the
    // rename, and that's deliberate: Android treats a new ID as a completely
    // different app, so changing it would mean uninstalling the old one and
    // losing saved settings. It never shows on screen. I'll switch it to
    // com.viewsmith.app when the real release key goes in, since that needs
    // a fresh install anyway.
    namespace = "com.pencilcam.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pencilcam.app"
        minSdk = 29          // Android 10 and up. Keeps saving to the gallery simple.
        targetSdk = 35
        versionCode = 6      // Goes up by one for every build I install over the top
        versionName = "0.6"  // What the Settings menu shows
    }

    // ------------------------------------------------------------------------
    // SIGNING: Android only installs signed apps. Every build is signed with
    // the same test key, which lives in this repo, so each new build installs
    // as an UPDATE instead of needing an uninstall first.
    // This is a TEST key only, which is why the repo has to stay private.
    // Before the app is ever shared or sold, it gets a real secret release key.
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
    // Basic Android helpers and the Material look for check boxes and sliders
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX: Google's camera library, much simpler than talking to the camera directly
    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
}
