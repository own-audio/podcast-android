package audio.own.core.servers

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerStoreTest {
    /** Reverses the bytes: enough to show the file isn't stored in the clear. */
    private val box = object : SecretBox {
        override fun seal(plain: ByteArray) = plain.reversedArray()

        override fun open(sealed: ByteArray) = sealed.reversedArray()
    }

    private fun store(): Pair<ServerStore, File> {
        val file = File(Files.createTempDirectory("servers").toFile(), "servers.bin")
        return ServerStore(file, box) to file
    }

    @Test fun roundTripAndSealed() = runTest {
        val (store, file) = store()
        store.add(record())
        store.add(record(id = "s2", auth = ServerAuth.Subsonic("sam", "secret")))
        assertEquals(listOf("s1", "s2"), store.loadAll().map { it.id })
        assertFalse(file.readBytes().decodeToString().contains("secret"))
        store.rename("s2", "Navidrome")
        assertEquals("Navidrome", store.loadAll().last().displayName)
        store.remove("s1")
        assertEquals(listOf("s2"), store.loadAll().map { it.id })
    }

    // The iOS 1.0.0 bug: saving a record for another reason (a new address, a library) wrote back
    // the tokens it was loaded with, over tokens rotated since; the next launch presented a used
    // refresh token and the server signed the device out.
    @Test fun updateKeepsRotatedTokens() = runTest {
        val (store, _) = store()
        val loaded = record()
        store.add(loaded)
        store.updateTokens("s1", StoredTokens("a2", "r2", 2000))
        val saved = store.update(loaded.copy(displayName = "Renamed"))
        assertEquals("Renamed", saved.displayName)
        assertEquals(StoredTokens("a2", "r2", 2000), (store.loadAll().single().auth as ServerAuth.OwnAudio).tokens)
    }

    @Test fun aFreshSignInReplacesTheTokens() = runTest {
        val (store, _) = store()
        store.add(record())
        store.updateTokens("s1", null)
        val fresh = record(auth = ServerAuth.OwnAudio("anna@example.com", StoredTokens("a9", "r9", 9000)))
        store.update(fresh, signIn = true)
        assertTrue(store.loadAll().single().auth.isSignedIn)
        assertEquals("r9", (store.loadAll().single().auth as ServerAuth.OwnAudio).tokens!!.refresh)
    }

    @Test fun anotherAccountIsNotTreatedAsTheSame() = runTest {
        val (store, _) = store()
        store.add(record())
        val other = record(auth = ServerAuth.OwnAudio("ben@example.com", StoredTokens("b1", "rb", 1)))
        store.update(other)
        assertEquals("ben@example.com", store.loadAll().single().auth.userName)
        assertEquals("rb", (store.loadAll().single().auth as ServerAuth.OwnAudio).tokens!!.refresh)
    }

    @Test fun anUnreadableFileReadsAsNoServers() = runTest {
        val (store, file) = store()
        file.parentFile!!.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(emptyList<ServerRecord>(), store.loadAll())
    }
}
