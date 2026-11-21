package audio.own.kit.ownaudio

import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody

// Narration (servers with `modules.narration`): an ebook or a text file becomes an audiobook
// read by an AI voice, paid from the household's credit. The hosted service's own calls, under
// `/api/v2/narration`.

@Serializable
data class NarrationLanguage(val code: String, val label: String)

@Serializable
data class NarrationVoice(
    val id: String,
    val displayName: String,
    val language: String,
    val gender: String = "",
    val previewUrl: String? = null,
    val costPerMillionCharsCents: Int = 0,
)

/** The server's own count of the characters it would read, from the file as unpacked there. */
@Serializable
data class NarrationEstimate(val estimatedCharCount: Int)

@Serializable
data class NarrationQuote(
    val charCount: Int,
    val translationCostCents: Int = 0,
    val ttsCostCents: Int = 0,
    val quotedPriceCents: Int,
    val currency: String,
)

/** `single_m4b` is one file; `multi_file` a file per chapter. */
enum class NarrationOutput(val wire: String) { MULTI_FILE("multi_file"), SINGLE_M4B("single_m4b") }

/** A narration under way, or finished. [stage] can be `failed`, which is not among [stages]. */
@Serializable
data class NarrationJob(
    val id: String,
    val title: String,
    val stages: List<String> = emptyList(),
    val stage: String,
    val error: String? = null,
    val chapters: List<Chapter> = emptyList(),
    /** The finished book's id once it is published; the change feed brings the book itself. */
    val bookId: String? = null,
    val quotedPriceCents: Int = 0,
    val chargedPriceCents: Int? = null,
    val currency: String = "USD",
) {
    @Serializable
    data class Chapter(val id: String, val title: String, val status: String, val blocksDone: Int = 0, val blocksTotal: Int = 0)

    val isTerminal: Boolean get() = stage == "complete" || stage == "failed"
    val hasFailed: Boolean get() = stage == "failed"
}

@Serializable
internal data class NarrationQuoteBody(val charCount: Int, val sourceLanguage: String, val targetLanguage: String, val voiceProfileId: String)

suspend fun OwnAudioClient.narrationLanguages(): List<NarrationLanguage> = getJson("narration/languages")

suspend fun OwnAudioClient.narrationVoices(): List<NarrationVoice> = getJson("narration/voices")

/** Uploads the file so the server can count what it would read. Nothing is charged. */
suspend fun OwnAudioClient.narrationEstimate(file: File): NarrationEstimate =
    multipart("narration/estimate", emptyList(), file, NarrationEstimate.serializer())

suspend fun OwnAudioClient.narrationQuote(charCount: Int, sourceLanguage: String, targetLanguage: String, voiceId: String): NarrationQuote =
    send("POST", "narration/quote", NarrationQuoteBody(charCount, sourceLanguage, targetLanguage, voiceId))

/** Starts the narration; the file goes up again, there is no handle to the estimate's copy. */
suspend fun OwnAudioClient.createNarration(
    file: File,
    title: String,
    sourceLanguage: String,
    targetLanguage: String,
    voiceId: String,
    output: NarrationOutput,
): NarrationJob = multipart(
    "narration/jobs",
    listOf(
        "title" to title, "source_language" to sourceLanguage, "target_language" to targetLanguage,
        "voice_profile_id" to voiceId, "output_mode" to output.wire, "cover_mode" to "auto", "cover_show_title" to "true",
    ),
    file,
    NarrationJob.serializer(),
)

suspend fun OwnAudioClient.narration(id: String): NarrationJob = getJson("narration/jobs/$id")

/** A multipart POST with the file streamed from disk, signed like every other call. */
internal suspend fun <T> OwnAudioClient.multipart(path: String, fields: List<Pair<String, String>>, file: File, serializer: KSerializer<T>): T {
    if (isOffline()) throw OwnAudioException.Offline
    val body = multipartBody(fields, file)
    val bytes = authorized(timeoutMs = 600_000) { token ->
        base(url(path)).post(body).header("Authorization", "Bearer $token").build()
    }
    return decode(path, serializer, bytes)
}

internal fun multipartBody(fields: List<Pair<String, String>>, file: File): MultipartBody = MultipartBody.Builder()
    .setType(MultipartBody.FORM)
    .apply {
        fields.forEach { (name, value) -> addFormDataPart(name, value) }
        addFormDataPart("file", file.name.replace("\"", ""), file.asRequestBody(mimeType(file).toMediaType()))
    }
    .build()

internal fun mimeType(file: File): String = when (file.extension.lowercase()) {
    "epub" -> "application/epub+zip"
    "mobi" -> "application/x-mobipocket-ebook"
    "txt", "md" -> "text/plain"
    else -> "application/octet-stream"
}
