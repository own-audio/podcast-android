plugins {
    id("ownaudio.jvm.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    compilerOptions {
        // JsonNamingStrategy (snake_case on the wire) is still marked experimental.
        optIn.add("kotlinx.serialization.ExperimentalSerializationApi")
    }
}

dependencies {
    // api: OkHttpClient, Flow and JsonElement are part of this module's public signatures.
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
