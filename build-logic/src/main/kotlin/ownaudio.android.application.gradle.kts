import org.gradle.api.artifacts.VersionCatalogsExtension

// AGP 9 has Kotlin built in: applying org.jetbrains.kotlin.android is an error.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

android {
    ownAudioBase()

    defaultConfig {
        targetSdk = OwnAudioAndroid.TARGET_SDK
        // App identity lives only in gradle.properties, so a fork renames the app in one place.
        applicationId = providers.gradleProperty("app.id").get()
        versionName = providers.gradleProperty("app.versionName").get()
        versionCode = providers.gradleProperty("app.versionCode").get().toInt()
        resValue("string", "app_name", providers.gradleProperty("app.name").get())
    }

    androidResources {
        // Puts the app in Android 13+'s per-app language picker; needs res/resources.properties.
        generateLocaleConfig = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    // "play" signs in with Google through Google Play services; "foss" has no Google code at
    // all and is the build for F-Droid and the APKs on GitHub releases.
    flavorDimensions += "distribution"
    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    buildTypes {
        release {
            // R8 on for release, so the build testers install is the one that ships.
            optimization { enable = true }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    "implementation"(platform(libs.findLibrary("androidx-compose-bom").get()))
    "testImplementation"(libs.findLibrary("junit").get())
}
