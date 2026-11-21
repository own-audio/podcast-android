plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "audio.own.podcast.library"
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

    testImplementation(libs.junit)
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
