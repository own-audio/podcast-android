import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion

/** SDK levels and lint rules every Android module of the own.audio apps shares. */
object OwnAudioAndroid {
    const val COMPILE_SDK = 37
    const val COMPILE_SDK_MINOR = 2
    const val TARGET_SDK = 37
    // DynamicsProcessing (the music equalizer) and the Media3 paths the apps rely on need API 28.
    const val MIN_SDK = 28

    // Localization is a build-time contract: a hardcoded string can't be translated, and a
    // string added to values/ but not values-cs ships English to Czech readers.
    val I18N_LINT_ERRORS = setOf(
        "HardcodedText",
        "MissingTranslation",
        "ExtraTranslation",
        "StringFormatInvalid",
        "StringFormatMatches",
        "MissingQuantity",
        "SetTextI18n",
    )

    val JAVA = JavaVersion.VERSION_17
}

internal fun CommonExtension.ownAudioBase() {
    compileSdk {
        version = release(OwnAudioAndroid.COMPILE_SDK) {
            minorApiLevel = OwnAudioAndroid.COMPILE_SDK_MINOR
        }
    }
    defaultConfig.minSdk = OwnAudioAndroid.MIN_SDK
    defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    compileOptions.sourceCompatibility = OwnAudioAndroid.JAVA
    compileOptions.targetCompatibility = OwnAudioAndroid.JAVA
    lint.error += OwnAudioAndroid.I18N_LINT_ERRORS
    lint.warningsAsErrors = false
    lint.abortOnError = true
}
