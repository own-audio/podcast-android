plugins {
    id("ownaudio.android.library")
}

android {
    namespace = "audio.own.player.spoken"
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
