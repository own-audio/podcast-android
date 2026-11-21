plugins {
    id("ownaudio.jvm.library")
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.okhttp) // OkHttpClient is part of the public API
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
