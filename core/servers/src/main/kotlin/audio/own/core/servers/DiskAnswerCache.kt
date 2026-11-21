package audio.own.core.servers

import java.io.File
import java.security.MessageDigest

/**
 * Saved GET answers, returned when offline or when the network fails. Keys are built by the
 * clients from the server's identity (not its address), the path and the sorted query — never a
 * token. One file per key, named by its SHA-256. Cleared when the server is removed.
 */
class DiskAnswerCache(private val dir: File) {
    fun get(key: String): ByteArray? = file(key).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    fun put(key: String, bytes: ByteArray) {
        runCatching {
            dir.mkdirs()
            val target = file(key)
            val temp = File(dir, target.name + ".tmp")
            temp.writeBytes(bytes)
            temp.renameTo(target)
        }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun file(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encodeToByteArray())
        return File(dir, digest.joinToString("") { "%02x".format(it) })
    }
}
