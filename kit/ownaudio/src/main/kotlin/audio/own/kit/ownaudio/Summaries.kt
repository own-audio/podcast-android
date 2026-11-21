package audio.own.kit.ownaudio

import kotlinx.serialization.Serializable

// Episode summaries and questions (servers with `modules.summaries`), under
// `/api/v2/summaries`. The server reads the episode's transcript; nothing here sends audio.

/** How long a summary is; each length is made and kept on its own. */
@Serializable(with = SummaryLength.Serializer::class)
enum class SummaryLength(val wire: String) {
    SHORT("short"), MEDIUM("medium"), LONG("long"), UNKNOWN("unknown");

    internal object Serializer : TolerantEnumSerializer<SummaryLength>("SummaryLength", entries.toTypedArray(), UNKNOWN, { it.wire })
}

@Serializable
data class EpisodeSummary(
    val language: String,
    val length: SummaryLength? = null,
    val text: String,
    /** What the household paid for it, in millionths of the currency. */
    val chargedMicro: Long? = null,
)

/** A question about an episode and its answer, kept for the person who asked. */
@Serializable
data class EpisodeAnswer(val id: String, val question: String, val answer: String, val chargedMicro: Long = 0)

@Serializable
internal data class SummaryBody(val language: String, val length: SummaryLength)

@Serializable
internal data class QuestionBody(val question: String)

/** The household's summary in [language] and [length], `null` when none has been made yet. */
suspend fun OwnAudioClient.summary(episodeId: String, language: String, length: SummaryLength): EpisodeSummary? = try {
    getJson("summaries/episodes/$episodeId/summary", Query("language" to language, "length" to length.wire))
} catch (e: OwnAudioException) {
    if (e.httpStatus == 404) null else throw e
}

/**
 * Made now and paid from the household's credit, or the one already made. `402`: not enough
 * credit; `409`: today's limit for paid work; `400`: no transcript.
 */
suspend fun OwnAudioClient.summarize(episodeId: String, language: String, length: SummaryLength, idempotencyKey: String? = null): EpisodeSummary =
    send("POST", "summaries/episodes/$episodeId/summary", SummaryBody(language, length), idempotencyKey = idempotencyKey)

/** The listener's own questions about the episode and their answers, oldest first. */
suspend fun OwnAudioClient.questions(episodeId: String): List<EpisodeAnswer> =
    getJson<Page<EpisodeAnswer>>("summaries/episodes/$episodeId/questions").items

suspend fun OwnAudioClient.clearQuestions(episodeId: String) = sendOnly("DELETE", "summaries/episodes/$episodeId/questions")

/** An answer from the episode's transcript, paid and kept; the server adds the earlier turns. */
suspend fun OwnAudioClient.ask(episodeId: String, question: String, idempotencyKey: String? = null): EpisodeAnswer =
    send("POST", "summaries/episodes/$episodeId/ask", QuestionBody(question), idempotencyKey = idempotencyKey)
