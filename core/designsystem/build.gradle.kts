plugins {
    id("ownaudio.android.library.compose")
    id("io.github.takahirom.roborazzi")
}

android {
    namespace = "audio.own.core.designsystem"
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:strings"))
    api(libs.androidx.compose.material3)
    api(libs.androidx.compose.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.kotlinx.serialization.json)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
