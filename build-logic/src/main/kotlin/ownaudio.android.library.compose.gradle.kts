import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("ownaudio.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    buildFeatures { compose = true }
}

dependencies {
    "implementation"(platform(libs.findLibrary("androidx-compose-bom").get()))
    "implementation"(libs.findLibrary("androidx-compose-ui").get())
    "implementation"(libs.findLibrary("androidx-compose-foundation").get())
    "implementation"(libs.findLibrary("androidx-compose-material3").get())
    "implementation"(libs.findLibrary("androidx-compose-ui-tooling-preview").get())
    "debugImplementation"(libs.findLibrary("androidx-compose-ui-tooling").get())
}
