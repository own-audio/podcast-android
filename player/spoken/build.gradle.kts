plugins {
    id("ownaudio.android.library")
}

android {
    namespace = "audio.own.player.spoken"
    testOptions {
        // Robolectric runs a real ExoPlayer (with fake renderers) over the fixtures.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // api: the spoken player hands out Media3 players and takes the core's link resolver.
    api(project(":player:core"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.media3.exoplayer.hls)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.media3.test.utils)
    testImplementation(libs.androidx.media3.test.utils.robolectric)
}
