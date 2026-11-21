plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "audio.own.podcast.library"
    testOptions {
        // Robolectric runs the Room tests on the JVM and needs the merged manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    // Exported so a schema change shows up in review and migrations can be tested against it.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The feed and gPodder models (FeedDocument, FeedFetcher.Result, GPodderClient) appear in
    // this module's public signatures.
    api(project(":kit:feed"))
    api(project(":kit:gpodder"))
    // The two server sources: their clients, records and errors are part of their public API.
    api(project(":kit:audiobookshelf"))
    api(project(":kit:ownaudio"))
    api(project(":core:servers"))
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    // PodcastDatabase is a RoomDatabase the app opens and hands to the stores.
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    // The live tests read real feeds; Android's own XML parser is kXML too.
    testImplementation(libs.kxml2)
}

// The live tests run only when the environment names a server (AUDIOBOOKSHELF_HOST,
// OWNAUDIO_HOST). The hosts are inputs so a cached result from a run without them is not
// reused; the passwords deliberately are not, so they never land in Gradle's task history.
tasks.withType<Test>().configureEach {
    inputs.property("audiobookshelfHost", providers.environmentVariable("AUDIOBOOKSHELF_HOST").orElse(""))
    inputs.property("ownAudioHost", providers.environmentVariable("OWNAUDIO_HOST").orElse(""))
}
