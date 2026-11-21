package audio.own.core.servers

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressRaceTest {
    private val lan = "http://192.168.1.2:8080"
    private val public = "https://music.example.com"

    @Test fun theFastestSameServerWins() = runTest {
        val winner = AddressRace.race(record(), listOf(public, lan), "id-1") { url ->
            delay(if (url == lan) 10 else 200)
            AddressCheck(url, "id-1")
        }
        assertEquals(lan, winner?.url)
    }

    @Test fun anotherServerAtTheSameAddressIsRefused() = runTest {
        val winner = AddressRace.race(record(), listOf(public, lan), "id-1") { url ->
            delay(if (url == lan) 10 else 200)
            AddressCheck(url, if (url == lan) "someone-else" else "id-1")
        }
        assertEquals(public, winner?.url)
    }

    @Test fun failuresAndTimeoutsDontWin() = runTest {
        val winner = AddressRace.race(record(), listOf(lan, public), "id-1", timeoutMs = 100) { url ->
            if (url == lan) error("refused")
            delay(1_000)
            AddressCheck(url, "id-1")
        }
        assertNull(winner)
    }

    @Test fun aReinstalledServerIsReadoptedOnlyAtItsPublicHttpsAddress() = runTest {
        val reinstalled = AddressRace.race(record(), listOf(public, lan), "old-id") { url -> AddressCheck(url, "new-id") }
        assertEquals(public, reinstalled?.url)
        assertEquals("new-id", reinstalled?.serverIdentity)

        val lanOnly = record(host = lan)
        assertNull(AddressRace.race(lanOnly, listOf(lan), "old-id") { url -> AddressCheck(url, "new-id") })
    }

    @Test fun serversWithoutIdentityTakeTheFastestAnswer() = runTest {
        val winner = AddressRace.race(record(), listOf(public, lan), null) { url -> AddressCheck(url, null) }
        assertEquals(true, winner != null)
    }
}
