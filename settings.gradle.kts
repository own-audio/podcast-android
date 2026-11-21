pluginManagement {
    includeBuild("build-logic")
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "podcast-android"

// Shared with book-android and podcast-android (docs/android-foundation.md §2.3).
include(":app")
include(":core:common")
include(":core:designsystem")
include(":core:strings")
include(":core:network")
include(":core:servers")
include(":core:sync")
include(":core:downloads")
include(":player:core")
include(":kit:ownaudio")

// This app's own modules.
include(":kit:feed")
include(":kit:audiobookshelf")
include(":kit:gpodder")
include(":library")
include(":player:spoken")
