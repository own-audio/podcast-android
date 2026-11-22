package audio.own.podcastplayer.podcasts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.core.designsystem.theme.LocalPalette
import audio.own.core.designsystem.theme.Tokens
import audio.own.kit.ownaudio.EpisodeAnswer
import audio.own.kit.ownaudio.EpisodeSummary
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.SummaryLength
import audio.own.kit.ownaudio.ask
import audio.own.kit.ownaudio.clearQuestions
import audio.own.kit.ownaudio.httpStatus
import audio.own.kit.ownaudio.questions
import audio.own.kit.ownaudio.summarize
import audio.own.kit.ownaudio.summary
import audio.own.podcastplayer.R
import audio.own.podcastplayer.ui.asHeading
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.text
import audio.own.podcastplayer.app.userMessage
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Summaries and questions about one episode (own.audio's hosted service, `modules.summaries`).
 * Reading what was made is free; [summarize] and [ask] are paid from the family's credit, so
 * only the listener's tap ever calls them.
 */
interface EpisodeAI {
    suspend fun summary(length: SummaryLength): EpisodeSummary?

    suspend fun summarize(length: SummaryLength): EpisodeSummary

    suspend fun questions(): List<EpisodeAnswer>

    suspend fun ask(question: String): EpisodeAnswer

    suspend fun clearQuestions()
}

/**
 * [EpisodeAI] on an own.audio server, in the app's [language]. Each paid request carries a key of
 * its own, so a request the network repeats is not paid for twice.
 */
class OwnAudioEpisodeAI(private val client: OwnAudioClient, private val episodeId: String, private val language: String) : EpisodeAI {
    override suspend fun summary(length: SummaryLength) = client.summary(episodeId, language, length)

    override suspend fun summarize(length: SummaryLength) = client.summarize(episodeId, language, length, idempotencyKey = UUID.randomUUID().toString())

    override suspend fun questions() = client.questions(episodeId)

    override suspend fun ask(question: String) = client.ask(episodeId, question, idempotencyKey = UUID.randomUUID().toString())

    override suspend fun clearQuestions() = client.clearQuestions(episodeId)
}

object SummaryPrice {
    /** A price in the ledger's dollars; summaries cost fractions of a cent, so three decimals below 10 ¢. */
    fun format(micro: Long, locale: Locale = Locale.getDefault()): String {
        val usd = micro / 1_000_000.0
        val format = NumberFormat.getCurrencyInstance(locale)
        format.currency = Currency.getInstance("USD")
        val digits = if (usd < 0.1) 3 else 2
        format.minimumFractionDigits = digits
        format.maximumFractionDigits = digits
        return format.format(usd)
    }

    /** What the listener reads when a paid summary or answer is refused. */
    fun message(error: Throwable): UiText = when (error.httpStatus) {
        402 -> UiText(R.string.summary_no_credit)
        409 -> UiText(R.string.summary_daily_limit)
        400 -> UiText(R.string.summary_no_transcript)
        else -> error.userMessage()
    }
}

data class SummaryUi(
    val length: SummaryLength = SummaryLength.MEDIUM,
    val summary: EpisodeSummary? = null,
    /** The summary in [length] was asked for; without one, Summarize Episode shows. */
    val checked: Boolean = false,
    val working: Boolean = false,
    val turns: List<EpisodeAnswer> = emptyList(),
    val question: String = "",
    val asking: Boolean = false,
    val error: UiText? = null,
) {
    val canAsk: Boolean get() = !asking && question.isNotBlank()
}

class EpisodeSummaryViewModel(private val ai: EpisodeAI) : ViewModel() {
    private val _state = MutableStateFlow(SummaryUi())
    val state: StateFlow<SummaryUi> = _state.asStateFlow()
    private var summaryJob: Job? = null

    init {
        viewModelScope.launch {
            val turns = runCatching { ai.questions() }.getOrElse { if (it is CancellationException) throw it else emptyList() }
            _state.update { it.copy(turns = turns) }
        }
        loadSummary()
    }

    fun setLength(length: SummaryLength) {
        if (length == _state.value.length) return
        _state.update { it.copy(length = length, summary = null, checked = false, error = null) }
        loadSummary()
    }

    private fun loadSummary() {
        summaryJob?.cancel()
        val length = _state.value.length
        summaryJob = viewModelScope.launch {
            // An unreachable server reads as no summary yet, as on iOS: the button says what is possible.
            val found = runCatching { ai.summary(length) }.getOrElse { if (it is CancellationException) throw it else null }
            _state.update { if (it.length == length) it.copy(summary = found, checked = true) else it }
        }
    }

    fun summarize() {
        if (_state.value.working) return
        val asked = _state.value.length
        _state.update { it.copy(working = true, error = null) }
        viewModelScope.launch {
            try {
                val made = ai.summarize(asked)
                // The listener may have switched length while it was being made.
                _state.update { if (it.length == asked) it.copy(summary = made, working = false) else it.copy(working = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(working = false, error = SummaryPrice.message(error)) }
            }
        }
    }

    fun setQuestion(text: String) = _state.update { it.copy(question = text) }

    fun ask() {
        val text = _state.value.question.trim()
        if (text.isEmpty() || _state.value.asking) return
        _state.update { it.copy(asking = true, error = null) }
        viewModelScope.launch {
            try {
                val answer = ai.ask(text)
                _state.update { it.copy(turns = it.turns + answer, question = "", asking = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(asking = false, error = SummaryPrice.message(error)) }
            }
        }
    }

    fun clearQuestions() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            try {
                ai.clearQuestions()
                _state.update { it.copy(turns = emptyList()) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(error = SummaryPrice.message(error)) }
            }
        }
    }
}

/** The episode page's Summary: Short / Medium / Long, the text or Summarize Episode, then the questions. */
@Composable
fun EpisodeSummarySection(
    state: SummaryUi,
    onLength: (SummaryLength) -> Unit,
    onSummarize: () -> Unit,
    onQuestion: (String) -> Unit,
    onAsk: () -> Unit,
    onClearQuestions: () -> Unit,
) {
    val palette = LocalPalette.current
    var confirmingClear by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm)) {
        Text(stringResource(R.string.summary), style = MaterialTheme.typography.titleMedium, modifier = Modifier.asHeading())
        val lengths = listOf(SummaryLength.SHORT to R.string.summary_short, SummaryLength.MEDIUM to R.string.summary_medium, SummaryLength.LONG to R.string.summary_long)
        val lengthLabel = stringResource(R.string.summary_length)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().semantics { contentDescription = lengthLabel }) {
            lengths.forEachIndexed { index, (length, label) ->
                SegmentedButton(
                    selected = state.length == length,
                    onClick = { onLength(length) },
                    shape = SegmentedButtonDefaults.itemShape(index, lengths.size),
                ) { Text(stringResource(label)) }
            }
        }
        val summary = state.summary
        if (summary != null) {
            SelectionContainer { Text(summary.text, style = MaterialTheme.typography.bodyLarge, color = palette.foreground) }
            Price(summary.chargedMicro ?: 0)
        } else if (state.checked) {
            Text(stringResource(R.string.summary_explanation), style = MaterialTheme.typography.bodySmall, color = palette.muted)
            OutlinedButton(onClick = onSummarize, enabled = !state.working, modifier = Modifier.fillMaxWidth().heightIn(min = Tokens.minTarget)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(if (state.working) R.string.summarizing else R.string.summarize_episode), modifier = Modifier.padding(start = Tokens.Spacing.xs))
            }
        }
        if (state.checked) {
            for (turn in state.turns) {
                Column(verticalArrangement = Arrangement.spacedBy(Tokens.Spacing.xs)) {
                    Text(turn.question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = palette.foreground)
                    SelectionContainer { Text(turn.answer, style = MaterialTheme.typography.bodyMedium, color = palette.muted) }
                    Price(turn.chargedMicro)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Tokens.Spacing.sm), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = state.question,
                    onValueChange = onQuestion,
                    placeholder = { Text(stringResource(R.string.ask_about_episode)) },
                    minLines = 1,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (state.canAsk) onAsk() }),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onAsk, enabled = state.canAsk, modifier = Modifier.heightIn(min = Tokens.minTarget)) {
                    if (state.asking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(stringResource(R.string.ask))
                }
            }
            Text(stringResource(R.string.ask_footnote), style = MaterialTheme.typography.bodySmall, color = palette.muted)
            if (state.turns.isNotEmpty()) {
                TextButton(onClick = { confirmingClear = true }) {
                    Text(stringResource(R.string.clear_questions), color = palette.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        state.error?.let { Text(it.text(), style = MaterialTheme.typography.bodySmall, color = palette.error) }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.clear_questions_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClearQuestions()
                }) { Text(stringResource(R.string.clear_questions), color = palette.error) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(audio.own.core.strings.R.string.cancel)) } },
        )
    }
}

@Composable
private fun Price(micro: Long) {
    if (micro > 0) {
        Text(stringResource(R.string.summary_cost, SummaryPrice.format(micro)), style = MaterialTheme.typography.labelSmall, color = LocalPalette.current.muted)
    }
}
