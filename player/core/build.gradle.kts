plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "audio.own.player.core"

    testOptions {
        // Robolectric runs a real ExoPlayer (with fake renderers) against MockWebServer.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api: SleepTimer takes a CoroutineScope and exposes a StateFlow; the apps build their
    // service on the Media3 types these helpers take and return.
    api(libs.kotlinx.coroutines.core)
    api(libs.androidx.media3.exoplayer)
    api(libs.androidx.media3.session)
    api(libs.androidx.media3.datasource.okhttp)
    api(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
}
