package audio.own.podcastplayer.podcasts

import audio.own.kit.ownaudio.EpisodeAnswer
import audio.own.kit.ownaudio.EpisodeSummary
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.OwnAudioException
import audio.own.kit.ownaudio.SummaryLength
import audio.own.kit.ownaudio.TokenSession
import audio.own.kit.ownaudio.Tokens
import audio.own.podcastplayer.FakeOwnAudio
import audio.own.podcastplayer.R
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import java.time.Instant
import java.util.Collections
import java.util.Locale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A summaries service that only answers; nothing here is paid, nothing reaches a server. */
private class FakeAI : EpisodeAI {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val summaries = HashMap<SummaryLength, EpisodeSummary>()
    val turns = mutableListOf(EpisodeAnswer("q1", "Who speaks?", "Two hosts.", 3_000))
    var refusal: Int? = null
    var holdSummarize: CompletableDeferred<Unit>? = null

    override suspend fun summary(length: SummaryLength): EpisodeSummary? {
        calls += "summary ${length.wire}"
        return summaries[length]
    }

    override suspend fun summarize(length: SummaryLength): EpisodeSummary {
        calls += "summarize ${length.wire}"
        holdSummarize?.await()
        refusal?.let { throw OwnAudioException.Problem(it, "refused") }
        return EpisodeSummary("en", length, "Made ${length.wire}.", 12_000).also { summaries[length] = it }
    }

    override suspend fun questions(): List<EpisodeAnswer> {
        calls += "questions"
        return turns.toList()
    }

    override suspend fun ask(question: String): EpisodeAnswer {
        calls += "ask $question"
        refusal?.let { throw OwnAudioException.Problem(it, "refused") }
        return EpisodeAnswer("q${turns.size + 1}", question, "An answer.", 2_500).also { turns += it }
    }

    override suspend fun clearQuestions() {
        calls += "clear"
        turns.clear()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeSummariesTest {
    private val main = mainThread()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
    }

    @Test
    fun `opens on Medium, reads the summary and the questions, and pays for nothing by itself`() = runBlocking {
        val ai = FakeAI().apply { summaries[SummaryLength.MEDIUM] = EpisodeSummary("en", SummaryLength.MEDIUM, "Kept.", 12_000) }
        val vm = EpisodeSummaryViewModel(ai)
        eventually { vm.state.value.checked && vm.state.value.turns.isNotEmpty() }
        assertEquals(SummaryLength.MEDIUM, vm.state.value.length)
        assertEquals("Kept.", vm.state.value.summary?.text)
        assertEquals(listOf("Who speaks?"), vm.state.value.turns.map { it.question })
        assertTrue("only reads", ai.calls.none { it.startsWith("summarize") || it.startsWith("ask") })
    }

    @Test
    fun `without a summary in that length, Summarize Episode makes one when tapped`() = runBlocking {
        val ai = FakeAI()
        val vm = EpisodeSummaryViewModel(ai)
        eventually { vm.state.value.checked }
        assertNull(vm.state.value.summary)
        vm.setLength(SummaryLength.SHORT)
        eventually { vm.state.value.checked && vm.state.value.length == SummaryLength.SHORT }
        vm.summarize()
        eventually { vm.state.value.summary != null && !vm.state.value.working }
        assertEquals("Made short.", vm.state.value.summary?.text)
        assertEquals(listOf("summarize short"), ai.calls.filter { it.startsWith("summarize") })
    }

    @Test
    fun `a summary that arrives after the length changed is not shown under the new length`() = runBlocking {
        val ai = FakeAI().apply { holdSummarize = CompletableDeferred() }
        val vm = EpisodeSummaryViewModel(ai)
        eventually { vm.state.value.checked }
        vm.summarize()
        eventually { vm.state.value.working }
        vm.setLength(SummaryLength.LONG)
        ai.holdSummarize?.complete(Unit)
        eventually { !vm.state.value.working && vm.state.value.checked }
        assertEquals(SummaryLength.LONG, vm.state.value.length)
        assertNull(vm.state.value.summary)
    }

    @Test
    fun `Ask adds the turn and clears the field, a blank question asks nothing, and Clear Questions empties the thread`() = runBlocking {
        val ai = FakeAI()
        val vm = EpisodeSummaryViewModel(ai)
        eventually { vm.state.value.turns.size == 1 }
        vm.setQuestion("   ")
        assertFalse(vm.state.value.canAsk)
        vm.ask()
        vm.setQuestion("  What is the guest's book?  ")
        vm.ask()
        eventually { vm.state.value.turns.size == 2 && !vm.state.value.asking }
        assertEquals("What is the guest's book?", vm.state.value.turns.last().question)
        assertEquals("", vm.state.value.question)
        assertEquals(listOf("ask What is the guest's book?"), ai.calls.filter { it.startsWith("ask") })
        vm.clearQuestions()
        eventually { vm.state.value.turns.isEmpty() }
    }

    @Test
    fun `refusals read as the family's credit, today's limit and a missing transcript`() = runBlocking {
        val ai = FakeAI()
        val vm = EpisodeSummaryViewModel(ai)
        eventually { vm.state.value.checked }
        for ((status, message) in listOf(402 to R.string.summary_no_credit, 409 to R.string.summary_daily_limit, 400 to R.string.summary_no_transcript)) {
            ai.refusal = status
            vm.summarize()
            eventually { !vm.state.value.working && vm.state.value.error != null }
            assertEquals(message, vm.state.value.error?.id)
            vm.setQuestion("Why?")
            vm.ask()
            eventually { !vm.state.value.asking && vm.state.value.error != null }
            assertEquals(message, vm.state.value.error?.id)
            assertEquals("the question stays to try again", "Why?", vm.state.value.question)
        }
        assertNull(vm.state.value.summary)
    }

    @Test
    fun `prices are in dollars, three decimals below ten cents`() {
        assertEquals("$0.012", SummaryPrice.format(12_000, Locale.US))
        assertEquals("$0.003", SummaryPrice.format(2_500, Locale.US))
        assertEquals("$0.15", SummaryPrice.format(150_000, Locale.US))
        assertEquals("$1.20", SummaryPrice.format(1_200_000, Locale.US))
        val czech = SummaryPrice.format(12_000, Locale.forLanguageTag("cs-CZ"))
        assertTrue(czech, czech.contains("0,012") && czech.contains("US$"))
    }

    @Test
    fun `the own_audio client asks v2 in the app's language, and each paid request carries its own key`() = runBlocking {
        FakeOwnAudio().use { server ->
            server.extra = { request ->
                val path = request.url.encodedPath
                when {
                    path == "/api/v2/summaries/episodes/e1/summary" && request.method == "GET" && request.url.queryParameter("length") == "short" ->
                        FakeOwnAudio.json("""{"language":"cs","length":"short","text":"Krátce.","charged_micro":9000}""")
                    path == "/api/v2/summaries/episodes/e1/summary" && request.method == "GET" -> FakeOwnAudio.problem("not_found", 404)
                    path == "/api/v2/summaries/episodes/e1/summary" -> FakeOwnAudio.json("""{"language":"cs","length":"medium","text":"Středně.","charged_micro":12000}""")
                    path == "/api/v2/summaries/episodes/e1/questions" && request.method == "GET" ->
                        FakeOwnAudio.json("""{"items":[{"id":"a1","question":"Kdo?","answer":"Dva.","charged_micro":2000}]}""")
                    path == "/api/v2/summaries/episodes/e1/questions" -> MockResponse.Builder().code(204).build()
                    path == "/api/v2/summaries/episodes/e1/ask" -> FakeOwnAudio.json("""{"id":"a2","question":"Proč?","answer":"Protože.","charged_micro":2500}""")
                    else -> null
                }
            }
            val client = OwnAudioClient(server.url, OkHttpClient(), "test", TokenSession(Tokens("a", "r", Instant.now().plusSeconds(3600))))
            val ai = OwnAudioEpisodeAI(client, "e1", "cs")
            assertEquals("Krátce.", ai.summary(SummaryLength.SHORT)?.text)
            assertNull("404 is no summary yet", ai.summary(SummaryLength.MEDIUM))
            assertEquals(12_000L, ai.summarize(SummaryLength.MEDIUM).chargedMicro)
            assertEquals(listOf("Kdo?"), ai.questions().map { it.question })
            assertEquals("Protože.", ai.ask("Proč?").answer)
            ai.clearQuestions()

            val summaries = server.requests.filter { it.url.encodedPath.startsWith("/api/v2/summaries/") }
            assertTrue(summaries.filter { it.method == "GET" && it.url.encodedPath.endsWith("/summary") }.all { it.url.queryParameter("language") == "cs" })
            val paid = summaries.filter { it.method == "POST" }
            assertEquals(2, paid.size)
            val keys = paid.map { it.headers["Idempotency-Key"] }
            assertTrue(keys.all { !it.isNullOrBlank() })
            assertEquals(2, keys.toSet().size)
            assertTrue(paid[0].body!!.utf8().contains("\"length\":\"medium\""))
            assertTrue(paid[1].body!!.utf8().contains("\"question\":\"Proč?\""))
            assertNotNull(summaries.firstOrNull { it.method == "DELETE" })
            assertTrue("nothing outside v2", server.requests.none { it.url.encodedPath.startsWith("/api/v1") })
        }
    }
}
