plugins {
    id("ownaudio.jvm.library")
    `java-library`
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // The XmlPullParser API ships with Android; the JVM only needs it to compile against.
    compileOnly(libs.xmlpull)
    api(libs.okhttp) // OkHttpClient is part of the public API
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    // The same kXML parser Android's Xml.newPullParser() is built on.
    testImplementation(libs.kxml2)
}

// Fetches the feeds in src/test/resources/real-feeds.txt from the internet. Manual only:
// `./gradlew :kit:feed:realFeeds`. The ordinary test run skips them.
tasks.register<Test>("realFeeds") {
    description = "Parses the real feeds listed in real-feeds.txt."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("audio.own.kit.feed.LiveFeedTest") }
    environment("REAL_FEEDS", "1")
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}
