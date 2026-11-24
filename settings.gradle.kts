pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
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

rootProject.name = "room-radar"

include(":core")

// The Android module is included by Android Studio / CI where an SDK exists.
// For a JVM-only checkout, create local.properties with sdk.dir or set ANDROID_HOME.
val hasAndroidSdk = providers.environmentVariable("ANDROID_HOME").isPresent ||
    providers.environmentVariable("ANDROID_SDK_ROOT").isPresent ||
    java.io.File(rootDir, "local.properties").exists()

if (hasAndroidSdk) {
    include(":android")
}
