// ============================================================================
// Top-level build file. This only sets which VERSIONS of the build plugins
// to use; app/build.gradle.kts is where they're actually applied.
//   - Android Gradle Plugin 8.7.3 : builds the APK
//   - Kotlin 2.0.21               : the language the app is written in
// ============================================================================
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
