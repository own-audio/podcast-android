package audio.own.podcastplayer.account

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.strings.R
import audio.own.kit.ownaudio.ListeningStats
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.book
import audio.own.kit.ownaudio.episode
import audio.own.kit.ownaudio.familyStats
import audio.own.kit.ownaudio.listeningStats
import audio.own.kit.ownaudio.me
import audio.own.kit.ownaudio.members
import audio.own.kit.ownaudio.setStatsVisibleToAdmin
import audio.own.kit.ownaudio.settings
import audio.own.kit.ownaudio.track
import audio.own.podcastplayer.app.Load
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.userMessage
import java.time.Clock
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class StatsRange(@StringRes val title: Int, val days: Long?, val group: String) {
    WEEK(R.string.range_week, 7, "day"),
    MONTH(R.string.range_month, 30, "day"),
    YEAR(R.string.range_year, 365, "week"),
    ALL(R.string.range_all, null, "month"),
}

/** One of the most played, with its title when it still exists. */
data class TopLine(val kind: String, val title: String?, val listenedMs: Long)

/** A member's total for the admin; null when they don't share it. */
data class MemberLine(val name: String, val listenedMs: Long?)

data class Listening(val stats: ListeningStats, val top: List<TopLine>)

data class ListeningState(
    val range: StatsRange = StatsRange.MONTH,
    val load: Load<Listening> = Load.Loading,
    /** Null for anyone but the family's admin. */
    val family: List<MemberLine>? = null,
    val shared: Boolean = false,
    val savingShared: Boolean = false,
    val error: UiText? = null,
)

/**
 * What the account listened to on an own.audio server, from all of own.audio's apps: time per
 * day, per kind and the most played; for the family's admin everyone's totals too.
 */
class ListeningViewModel(private val client: () -> OwnAudioClient?, private val clock: Clock = Clock.systemUTC()) : ViewModel() {
    private val _state = MutableStateFlow(ListeningState())
    val state: StateFlow<ListeningState> = _state.asStateFlow()
    private var loading: Job? = null

    init {
        load()
    }

    fun setRange(range: StatsRange) {
        if (range == _state.value.range) return
        _state.update { it.copy(range = range) }
        load()
    }

    fun load() {
        val client = client() ?: run {
            _state.update { it.copy(load = Load.Failed(UiText(R.string.sign_in_to_this_server_again))) }
            return
        }
        val range = _state.value.range
        loading?.cancel()
        _state.update { it.copy(load = Load.Loading) }
        loading = viewModelScope.launch {
            try {
                val to = clock.instant()
                val from = range.days?.let { to.minus(Duration.ofDays(it)) }
                val stats = client.listeningStats(from = from, to = to, group = range.group)
                val top = titles(client, stats.top.take(10))
                val me = runCatching { client.me() }.getOrNull()
                val isAdmin = me?.family?.role == "admin"
                val family = if (isAdmin) {
                    runCatching {
                        val names = client.members().associate { it.userId to it.name }
                        val totals = client.familyStats(from = from, to = to).associateBy { it.userId }
                        names.map { (id, name) -> MemberLine(name, totals[id]?.listenedMs) }.sortedByDescending { it.listenedMs ?: -1 }
                    }.getOrNull()
                } else {
                    null
                }
                val shared = if (!isAdmin) runCatching { client.settings().statsVisibleToAdmin }.getOrDefault(false) else false
                _state.update { it.copy(load = Load.Loaded(Listening(stats, top)), family = family, shared = shared) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(load = Load.Failed(e.userMessage())) }
            }
        }
    }

    private suspend fun titles(client: OwnAudioClient, items: List<ListeningStats.TopItem>): List<TopLine> = coroutineScope {
        items.map { item ->
            async {
                val title = runCatching {
                    when (item.kind) {
                        "track" -> client.track(item.id, include = emptyList()).track.title
                        "book" -> client.book(item.id).record.title
                        "episode" -> client.episode(item.id).title
                        else -> null
                    }
                }.getOrNull()
                TopLine(item.kind, title, item.listenedMs)
            }
        }.awaitAll()
    }

    fun setShared(value: Boolean) {
        val client = client() ?: return
        _state.update { it.copy(savingShared = true) }
        viewModelScope.launch {
            try {
                client.setStatsVisibleToAdmin(value)
                _state.update { it.copy(shared = value) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.userMessage()) }
            } finally {
                _state.update { it.copy(savingShared = false) }
            }
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }
}
