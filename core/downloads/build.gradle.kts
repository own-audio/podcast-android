plugins {
    id("ownaudio.android.library")
    alias(libs.plugins.ksp)
}

android {
    namespace = "audio.own.core.downloads"

    testOptions {
        // Robolectric runs the workers, Room and MockWebServer on the JVM.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // api: the payload check, the storage route memory, WorkManager's types and OkHttp are part
    // of this module's public signatures.
    api(project(":player:core"))
    api(libs.androidx.work.runtime)
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    // TestListenableWorkerBuilder; the same version as the runtime.
    testImplementation(libs.androidx.work.testing)
}
