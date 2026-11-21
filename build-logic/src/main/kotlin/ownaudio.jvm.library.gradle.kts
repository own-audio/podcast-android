import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Server clients (":kit:*") are plain Kotlin so their tests run on the JVM in seconds.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    sourceCompatibility = OwnAudioAndroid.JAVA
    targetCompatibility = OwnAudioAndroid.JAVA
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    "testImplementation"(libs.findLibrary("junit").get())
}
