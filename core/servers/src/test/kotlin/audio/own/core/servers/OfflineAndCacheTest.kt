package audio.own.core.servers

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAndCacheTest {
    private val wifi = NetworkState(connected = true, metered = false, dataSaver = false, signature = "wifi,wlan0")
    private val mobile = NetworkState(connected = true, metered = true, dataSaver = false, signature = "cellular,rmnet0")

    @Test fun offlineRules() {
        val s = OfflineSwitch()
        s.update(offlineMode = false, network = wifi, mobileData = MobileData.DOWNLOADED_ONLY)
        assertFalse(s.isOffline)
        s.update(offlineMode = false, network = mobile, mobileData = MobileData.DOWNLOADED_ONLY)
        assertTrue(s.isOffline)
        assertTrue(s.isOfflineByChoice)
        s.update(offlineMode = false, network = mobile, mobileData = MobileData.SAVER)
        assertFalse(s.isOffline)
        s.update(offlineMode = false, network = NetworkState.Offline, mobileData = MobileData.SAVER)
        assertTrue(s.isOffline)
        assertFalse(s.isOfflineByChoice)
        s.update(offlineMode = true, network = wifi, mobileData = MobileData.ORIGINAL)
        assertTrue(s.isOfflineByChoice)
    }

    @Test fun signatureIgnoresOrder() {
        assertEquals(
            NetworkState.signature(true, listOf("wifi", "vpn"), listOf("wlan0", "tun0")),
            NetworkState.signature(true, listOf("vpn", "wifi"), listOf("tun0", "wlan0")),
        )
        assertEquals("none", NetworkState.signature(false, listOf("wifi"), listOf("wlan0")))
    }

    @Test fun answerCache() {
        val cache = DiskAnswerCache(Files.createTempDirectory("answers").toFile())
        assertNull(cache.get("server-1 /albums?limit=100"))
        cache.put("server-1 /albums?limit=100", byteArrayOf(1, 2))
        assertArrayEquals(byteArrayOf(1, 2), cache.get("server-1 /albums?limit=100"))
        cache.clear()
        assertNull(cache.get("server-1 /albums?limit=100"))
    }

    @Test fun addressMemory() {
        val memory = InMemoryAddressMemory()
        memory.set("s1", "wifi,wlan0", "http://192.168.1.2:8080")
        assertEquals("http://192.168.1.2:8080", memory.get("s1", "wifi,wlan0"))
        assertNull(memory.get("s1", "cellular,rmnet0"))
        memory.set("s1", "wifi,wlan0", null)
        assertNull(memory.get("s1", "wifi,wlan0"))
    }
}
