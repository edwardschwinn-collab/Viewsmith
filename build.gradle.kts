// ============================================================================
// Top-level build file. Only declares plugin VERSIONS here; the app module
// (app/build.gradle.kts) actually applies them.
//   - Android Gradle Plugin 8.7.3 : builds the APK
//   - Kotlin 2.0.21               : the language the app is written in
// ============================================================================
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
}
