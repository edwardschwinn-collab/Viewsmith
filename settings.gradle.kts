// ============================================================================
// settings.gradle.kts
// Tells Gradle (the Android build tool) where to download libraries from
// and which parts make up the project. There's only one part: "app".
// ============================================================================
pluginManagement {
    repositories {
        google()            // Android build tools and AndroidX libraries
        mavenCentral()      // Kotlin and general Java libraries
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "ViewSmith"
include(":app")
