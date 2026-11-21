package audio.own.player.core

import android.util.AtomicFile
import java.io.File
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * One value kept as a JSON file. Written whole through [AtomicFile], so a process killed
 * mid-write (the usual way a player app ends) leaves the previous value, never half of one.
 * A file that no longer parses (an older shape) reads as nothing.
 */
class JsonFile<T>(file: File, private val serializer: KSerializer<T>) {
    private val atomic = AtomicFile(file)

    @Synchronized
    fun read(): T? = try {
        json.decodeFromString(serializer, atomic.readFully().decodeToString())
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun write(value: T) {
        val bytes = json.encodeToString(serializer, value).encodeToByteArray()
        val stream = atomic.startWrite()
        try {
            stream.write(bytes)
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }

    @Synchronized
    fun delete() = atomic.delete()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * [ResumeStore] in a JSON file (`filesDir/resume.json`). Small: one entry per container
 * played, the oldest dropped past [capacity].
 */
class DiskResumeStore(
    file: File,
    private val capacity: Int = 500,
    private val now: () -> Long = System::currentTimeMillis,
) : ResumeStore {
    @Serializable
    private data class Entry(val itemId: String, val positionMs: Long, val savedAtMs: Long)

    private val store = JsonFile(file, MapSerializer(String.serializer(), Entry.serializer()))
    private val entries: MutableMap<String, Entry> = store.read().orEmpty().toMutableMap()

    @Synchronized
    override fun saveResume(containerId: String, itemId: String, positionMs: Long) {
        entries[containerId] = Entry(itemId, positionMs, now())
        while (entries.size > capacity) {
            entries.minByOrNull { it.value.savedAtMs }?.let { entries.remove(it.key) } ?: break
        }
        store.write(entries)
    }

    @Synchronized
    override fun loadResume(containerId: String): ResumeStore.Resume? =
        entries[containerId]?.let { ResumeStore.Resume(it.itemId, it.positionMs) }

    @Synchronized
    override fun clearResume(containerId: String) {
        if (entries.remove(containerId) != null) store.write(entries)
    }
}

/**
 * The last item played, for the paused mini player after a relaunch and for Media3's playback
 * resumption. The spoken apps keep one of these; Music keeps its whole last queue instead.
 */
@Serializable
data class LastItem(
    val itemId: String,
    val positionMs: Long,
    val containerId: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val artworkId: String? = null,
)

/** `filesDir/last-item.json`. */
fun lastItemStore(file: File): JsonFile<LastItem> = JsonFile(file, LastItem.serializer())
