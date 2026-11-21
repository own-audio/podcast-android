plugins {
    id("ownaudio.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // OkHttp types (the injected client, HttpUrl) and the @Serializable models are part of
    // this module's public API.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}

// The live tests run only when AUDIOBOOKSHELF_HOST names a server. The host is an input so a
// cached result from a run without it is not reused; the password deliberately is not, so it
// never lands in Gradle's task history.
tasks.withType<Test>().configureEach {
    inputs.property("audiobookshelfHost", providers.environmentVariable("AUDIOBOOKSHELF_HOST").orElse(""))
}
