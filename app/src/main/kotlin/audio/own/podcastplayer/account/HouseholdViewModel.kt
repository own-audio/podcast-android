package audio.own.podcastplayer.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import audio.own.kit.ownaudio.Family
import audio.own.kit.ownaudio.FamilyBilling
import audio.own.kit.ownaudio.FamilyStorage
import audio.own.kit.ownaudio.Invite
import audio.own.kit.ownaudio.Member
import audio.own.kit.ownaudio.OwnAudioClient
import audio.own.kit.ownaudio.createInvite
import audio.own.kit.ownaudio.deleteInvite
import audio.own.kit.ownaudio.family
import audio.own.kit.ownaudio.familyBilling
import audio.own.kit.ownaudio.familyStorage
import audio.own.kit.ownaudio.invites
import audio.own.kit.ownaudio.join
import audio.own.kit.ownaudio.members
import audio.own.podcastplayer.app.Load
import audio.own.podcastplayer.app.UiText
import audio.own.podcastplayer.app.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The household as the page shows it: the members with this account first. */
data class HouseholdInfo(val family: Family, val members: List<Member>) {
    val isAdmin: Boolean get() = family.me.role == "admin"
    val meId: String get() = family.me.userId
}

data class HouseholdState(
    val load: Load<HouseholdInfo> = Load.Loading,
    val invites: List<Invite> = emptyList(),
    val storage: FamilyStorage? = null,
    /** Only where the server charges; shown, never linked to a place to top up. */
    val credit: FamilyBilling? = null,
    val creatingInvite: Boolean = false,
    val shownInvite: Invite? = null,
    val error: UiText? = null,
)

/**
 * The household on an own.audio server: who is in it, the storage, the credit where the server
 * charges, invitations for admins, and joining another household with a code.
 */
class HouseholdViewModel(private val client: () -> OwnAudioClient?, private val onJoined: () -> Unit = {}) : ViewModel() {
    private val _state = MutableStateFlow(HouseholdState())
    val state: StateFlow<HouseholdState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        val client = client() ?: return
        viewModelScope.launch {
            try {
                coroutineScope {
                    val storage = async { runCatching { client.familyStorage() }.getOrNull() }
                    val credit = async { runCatching { client.familyBilling() }.getOrNull() }
                    val family = client.family()
                    val members = client.members()
                    val info = HouseholdInfo(family, members.sortedWith(compareByDescending<Member> { it.userId == family.me.userId }))
                    val invites = if (info.isAdmin) runCatching { client.invites() }.getOrDefault(emptyList()) else emptyList()
                    _state.update { it.copy(load = Load.Loaded(info), invites = invites, storage = storage.await(), credit = credit.await()) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(load = Load.Failed(e.userMessage())) }
            }
        }
    }

    fun createInvite() {
        val client = client() ?: return
        _state.update { it.copy(creatingInvite = true) }
        perform {
            try {
                val invite = client.createInvite()
                _state.update { it.copy(invites = listOf(invite) + it.invites, shownInvite = invite) }
            } finally {
                _state.update { it.copy(creatingInvite = false) }
            }
        }
    }

    fun show(invite: Invite?) = _state.update { it.copy(shownInvite = invite) }

    fun withdraw(invite: Invite) {
        val client = client() ?: return
        perform {
            client.deleteInvite(invite.id)
            _state.update { it.copy(invites = it.invites.filterNot { i -> i.id == invite.id }) }
        }
    }

    /** Leaves this household for the one the code belongs to. */
    fun join(code: String) {
        val client = client() ?: return
        perform {
            client.join(code.trim())
            // TODO(P4): another household's shows: the catalogue on this device starts again.
            onJoined()
            load()
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private fun perform(work: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                work()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.update { it.copy(error = e.userMessage()) }
            }
        }
    }
}
