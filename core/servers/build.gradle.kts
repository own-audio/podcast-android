plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "audio.own.core.servers"
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.kotlinx.coroutines.test)
}
