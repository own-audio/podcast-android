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
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
