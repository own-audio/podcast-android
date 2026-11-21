package audio.own.core.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddressesTest {
    @Test fun scopes() {
        val table = mapOf(
            "http://localhost:4533" to AddressScope.LAN,
            "http://nas.local" to AddressScope.LAN,
            "http://music.lan" to AddressScope.LAN,
            "http://box.home.arpa" to AddressScope.LAN,
            "http://10.0.0.5" to AddressScope.LAN,
            "http://172.16.1.1" to AddressScope.LAN,
            "http://172.31.255.1" to AddressScope.LAN,
            "http://172.32.0.1" to AddressScope.PUBLIC,
            "http://192.168.1.20:4533" to AddressScope.LAN,
            "http://169.254.3.4" to AddressScope.LAN,
            "http://127.0.0.1" to AddressScope.LAN,
            "http://100.64.0.1" to AddressScope.VPN,
            "http://100.127.255.255" to AddressScope.VPN,
            "http://100.128.0.1" to AddressScope.PUBLIC,
            "https://box.tail1234.ts.net" to AddressScope.VPN,
            "http://[::1]:8080" to AddressScope.LAN,
            "http://[fd12::1]" to AddressScope.LAN,
            "http://[fe80::1]" to AddressScope.LAN,
            "http://[2001:db8::1]" to AddressScope.PUBLIC,
            "https://music.example.com" to AddressScope.PUBLIC,
            "https://8.8.8.8" to AddressScope.PUBLIC,
        )
        table.forEach { (url, scope) -> assertEquals(url, scope, AddressScope.infer(url)) }
    }

    @Test fun credentialsNeverGoOverPlainHttpToAnAdvertisedPublicAddress() {
        assertFalse(ServerAddress.of("http://music.example.com", AddressOrigin.SERVER).isSafeToUse)
        assertTrue(ServerAddress.of("https://music.example.com", AddressOrigin.SERVER).isSafeToUse)
        assertTrue(ServerAddress.of("http://192.168.1.2", AddressOrigin.SERVER).isSafeToUse)
        assertTrue(ServerAddress.of("http://music.example.com", AddressOrigin.USER).isSafeToUse)
        assertTrue(ServerAddress.of("http://music.example.com", AddressOrigin.ADDED).isSafeToUse)
    }

    @Test fun typedAddressesTryHttpsFirst() {
        assertEquals(listOf("https://music.example.com", "http://music.example.com"), TypedAddress.candidates(" music.example.com/ "))
        assertEquals(listOf("http://192.168.1.2:4533"), TypedAddress.candidates("http://192.168.1.2:4533"))
        assertEquals(emptyList<String>(), TypedAddress.candidates(""))
    }

    @Test fun hostedWebAddressesLeadToTheApi() {
        assertEquals(listOf("https://api.own.audio"), TypedAddress.candidates("own.audio"))
        assertEquals(listOf("https://api.own.audio"), TypedAddress.candidates("https://www.own.audio"))
        assertEquals(listOf("https://api.own.audio"), TypedAddress.candidates("app.own.audio"))
        assertEquals("https://api-test.own.audio", TypedAddress.hostedApi("app-test.own.audio"))
        assertNull(TypedAddress.hostedApi("music.example.com"))
    }

    @Test fun candidatesStartWithTheAddedAddressOnceEach() {
        val record = record().learning(
            listOf(
                ServerAddress.of("https://music.example.com/", AddressOrigin.SERVER),
                ServerAddress.of("http://192.168.1.2:8080", AddressOrigin.SERVER),
                ServerAddress.of("http://music.example.com", AddressOrigin.SERVER),
            ),
        )
        assertEquals(listOf("https://music.example.com", "http://192.168.1.2:8080"), record.candidateAddresses.map { it.url })
    }

    @Test fun compositeIds() {
        assertEquals("s1|a|b", CompositeId.make("s1", "a|b"))
        assertEquals("s1" to "a|b", CompositeId.split("s1|a|b"))
        assertNull(CompositeId.split("bare"))
    }
}

internal fun record(
    id: String = "s1",
    host: String = "https://music.example.com",
    auth: ServerAuth = ServerAuth.OwnAudio("anna@example.com", StoredTokens("a1", "r1", 1000)),
) = ServerRecord(id = id, kind = "own_audio", displayName = "Music", host = host, auth = auth)
