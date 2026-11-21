package audio.own.core.servers

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Seals and opens the stored bytes. On a device: AES-GCM with a key the Android Keystore keeps. */
interface SecretBox {
    fun seal(plain: ByteArray): ByteArray

    fun open(sealed: ByteArray): ByteArray
}

/**
 * Every configured server in one encrypted file, so adding or removing one is a single atomic
 * write (a temporary file renamed over the old one). This is the Keychain item of the iOS apps.
 */
class ServerStore(private val file: File, private val box: SecretBox, private val onError: (String) -> Unit = {}) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "type" }
    private val serializer = ListSerializer(ServerRecord.serializer())

    suspend fun loadAll(): List<ServerRecord> = mutex.withLock { read() }

    suspend fun add(record: ServerRecord) = mutate { it + record }

    suspend fun remove(id: String) = mutate { list -> list.filterNot { it.id == id } }

    suspend fun rename(id: String, displayName: String) = mutate { list -> list.map { if (it.id == id) it.copy(displayName = displayName) else it } }

    /**
     * Saves a changed record and returns it as saved. The tokens stay as stored unless [signIn]
     * says the record carries a new sign-in: the caller's copy holds the tokens from when it was
     * loaded, and the server may have rotated them since. Saving that copy brought back a refresh
     * token already used, which the server takes for theft and signs the device out.
     */
    suspend fun update(record: ServerRecord, signIn: Boolean = false): ServerRecord = mutex.withLock {
        val records = read()
        val index = records.indexOfFirst { it.id == record.id }
        if (index < 0) return@withLock record
        val stored = records[index]
        val saved = if (!signIn && record.auth.sameAccount(stored.auth)) record.copy(auth = record.auth.keepingTokensOf(stored.auth)) else record
        write(records.toMutableList().also { it[index] = saved })
        saved
    }

    /** Rotated tokens, or null after a refused refresh. */
    suspend fun updateTokens(serverId: String, tokens: StoredTokens?) = mutate { list ->
        list.map { if (it.id == serverId) it.copy(auth = it.auth.withTokens(tokens)) else it }
    }

    suspend fun clear() = mutex.withLock { withContext(Dispatchers.IO) { file.delete() } }

    private suspend fun mutate(change: (List<ServerRecord>) -> List<ServerRecord>) = mutex.withLock { write(change(read())) }

    private suspend fun read(): List<ServerRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching { json.decodeFromString(serializer, box.open(file.readBytes()).decodeToString()) }
            .getOrElse {
                onError("Saved servers could not be read.")
                emptyList()
            }
    }

    private suspend fun write(records: List<ServerRecord>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeBytes(box.seal(json.encodeToString(serializer, records).encodeToByteArray()))
        if (!temp.renameTo(file)) {
            file.delete()
            check(temp.renameTo(file)) { "Saved servers could not be written." }
        }
    }
}

private fun ServerAuth.keepingTokensOf(stored: ServerAuth): ServerAuth = when (stored) {
    is ServerAuth.OwnAudio -> withTokens(stored.tokens)
    is ServerAuth.Audiobookshelf -> withTokens(stored.tokens)
    else -> this
}
