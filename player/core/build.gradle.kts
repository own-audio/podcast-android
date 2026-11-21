plugins {
    id("ownaudio.android.library")
}

android {
    namespace = "audio.own.player.core"
}

dependencies {
    // api: SleepTimer takes a CoroutineScope and exposes a StateFlow.
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
