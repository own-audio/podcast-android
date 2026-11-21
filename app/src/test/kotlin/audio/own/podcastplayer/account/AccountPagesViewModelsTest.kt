package audio.own.podcastplayer.account

import audio.own.podcastplayer.FakeOwnAudio
import audio.own.podcastplayer.ModelHarness
import audio.own.podcastplayer.app.Load
import audio.own.podcastplayer.eventually
import audio.own.podcastplayer.mainThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AccountPagesViewModelsTest {
    private val main = mainThread()
    private val fake = FakeOwnAudio()
    private val harness = ModelHarness(hosted = fake.url, demo = fake.url)
    private lateinit var id: String

    @Before
    fun setUp() = runBlocking {
        Dispatchers.setMain(main)
        harness.model.signIn(fake.url, "a@example.com", fake.password, "")
        id = harness.store.loadAll().single().id
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        main.close()
        fake.close()
        harness.close()
    }

    private fun client() = harness.model.client(id)

    @Test
    fun theFamilyListsThisAccountFirstWithStorageAndNoCreditWhereNothingIsCharged() = runBlocking {
        val vm = HouseholdViewModel(::client)
        eventually { vm.state.value.load is Load.Loaded }
        val info = (vm.state.value.load as Load.Loaded).value
        assertEquals("u1", info.members.first().userId)
        assertTrue(info.isAdmin)
        assertEquals(3000L, vm.state.value.storage?.totalBytes)
        assertNull(vm.state.value.credit)
        assertTrue(fake.paths().contains("GET /api/v2/family/invites"))
    }

    @Test
    fun anInvitationIsShownAtOnceAndCanBeWithdrawn() = runBlocking {
        val vm = HouseholdViewModel(::client)
        eventually { vm.state.value.load is Load.Loaded }
        vm.createInvite()
        eventually { vm.state.value.shownInvite != null }
        assertEquals("ABCD-1234", vm.state.value.shownInvite?.code)
        assertFalse(vm.state.value.creatingInvite)
        vm.show(null)
        vm.withdraw(vm.state.value.invites.single())
        eventually { vm.state.value.invites.isEmpty() }
        assertTrue(fake.paths().contains("DELETE /api/v2/family/invites/i1"))
    }

    @Test
    fun membersSeeNoInvitations() = runBlocking {
        fake.role = "member"
        val vm = HouseholdViewModel(::client)
        eventually { vm.state.value.load is Load.Loaded }
        assertFalse((vm.state.value.load as Load.Loaded).value.isAdmin)
        assertFalse(fake.paths().contains("GET /api/v2/family/invites"))
    }

    @Test
    fun joiningAnotherFamilyReloads() = runBlocking {
        var joined = false
        val vm = HouseholdViewModel(::client) { joined = true }
        eventually { vm.state.value.load is Load.Loaded }
        vm.join(" CODE ")
        eventually { joined }
        val join = fake.requests.last { it.url.encodedPath == "/api/v2/family/join" }
        assertEquals("""{"code":"CODE"}""", join.body?.utf8())
    }

    @Test
    fun devicesPutThisOneFirstThenTheMostRecent() = runBlocking {
        val vm = DevicesViewModel(::client) { harness.model.signOutEverywhere(id) }
        eventually { vm.state.value.load is Load.Loaded }
        val sessions = (vm.state.value.load as Load.Loaded).value
        assertEquals(listOf("s2", "s3", "s1"), sessions.map { it.id })
        vm.signOut(sessions.last())
        eventually { fake.paths().contains("DELETE /api/v2/me/sessions/s1") }
        assertEquals(listOf("s2", "s3"), (vm.state.value.load as Load.Loaded).value.map { it.id })
    }

    @Test
    fun signingOutEverywhereSignsThisDeviceOutToo() = runBlocking {
        val vm = DevicesViewModel(::client) { harness.model.signOutEverywhere(id) }
        vm.signOutEverywhere()
        eventually { vm.state.value.signedOutEverywhere }
        assertTrue(fake.paths().contains("DELETE /api/v2/me/sessions"))
        assertTrue(harness.model.state.value.isRefused(id))
    }

    @Test
    fun listeningLoadsTotalsTitlesAndTheFamilyForTheAdmin() = runBlocking {
        val vm = ListeningViewModel(::client)
        eventually { vm.state.value.load is Load.Loaded }
        val listening = (vm.state.value.load as Load.Loaded).value
        assertEquals(7_800_000L, listening.stats.listenedMs)
        assertEquals(listOf("One", null), listening.top.map { it.title })
        assertEquals(listOf("Alex", "Sam", "Kim"), vm.state.value.family?.map { it.name })
        assertEquals(listOf(600_000L, null, null), vm.state.value.family?.map { it.listenedMs })
        val query = fake.requests.first { it.url.encodedPath == "/api/v2/me/stats" }.url
        assertEquals("day", query.queryParameter("group"))
    }

    @Test
    fun aMemberCanShareWithTheAdmin() = runBlocking {
        fake.role = "member"
        val vm = ListeningViewModel(::client)
        eventually { vm.state.value.load is Load.Loaded }
        assertNull(vm.state.value.family)
        assertFalse(vm.state.value.shared)
        vm.setShared(true)
        eventually { vm.state.value.shared }
        val patch = fake.requests.last { it.method == "PATCH" && it.url.encodedPath == "/api/v2/me/settings" }
        assertEquals("""{"stats_visible_to_admin":true}""", patch.body?.utf8())
    }

    @Test
    fun anotherRangeAsksAgain() = runBlocking {
        val vm = ListeningViewModel(::client)
        eventually { vm.state.value.load is Load.Loaded }
        vm.setRange(StatsRange.ALL)
        eventually { fake.requests.count { it.url.encodedPath == "/api/v2/me/stats" } == 2 && vm.state.value.load is Load.Loaded }
        val all = fake.requests.last { it.url.encodedPath == "/api/v2/me/stats" }.url
        assertNull(all.queryParameter("from"))
        assertEquals("month", all.queryParameter("group"))
    }
}
