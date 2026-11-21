import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("com.android.library")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    ownAudioBase()
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
