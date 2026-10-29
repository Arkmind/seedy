package io.github.arkmind.seedy.player.audio

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The queue as saved between launches, for one profile on one server ([who]). */
@Serializable
internal data class SavedQueue(
    val who: String,
    val mode: AudioMode?,
    val current: Entry,
    val fromAdded: Boolean,
    val context: List<Entry>,
    val original: List<Entry>?,
    val pos: Int,
    val added: List<Entry>,
    val source: Source?,
    val shuffle: Boolean,
    val repeat: Repeat,
    val book: BookInfo?,
)

/**
 * What the audio player keeps on this phone (the web keeps it in localStorage): the queue (written when it changes),
 * where the current song was (every few seconds), the audiobook speed and the recently played sources.
 */
internal class AudioStore(context: Context) {
    private val prefs = context.getSharedPreferences("seedy.audio", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }

    var speed: Float
        get() = prefs.getFloat("speed", 1f).takeIf { it in 0.5f..3f } ?: 1f
        set(v) = prefs.edit().putFloat("speed", v).apply()

    fun queue(who: String): Pair<SavedQueue, Double>? {
        val raw = prefs.getString("queue", null) ?: return null
        val q = runCatching { json.decodeFromString(SavedQueue.serializer(), raw) }.getOrNull()?.takeIf { it.who == who } ?: return null
        return q to prefs.getFloat("position", 0f).toDouble()
    }

    fun saveQueue(q: SavedQueue?) {
        val e = prefs.edit()
        if (q == null) e.remove("queue").remove("position") else e.putString("queue", json.encodeToString(SavedQueue.serializer(), q))
        e.apply()
    }

    fun savePosition(seconds: Double) = prefs.edit().putFloat("position", seconds.toFloat()).apply()

    fun recent(who: String): List<RecentSource> =
        prefs.getString("recent.$who", null)?.let { raw -> runCatching { json.decodeFromString<List<RecentSource>>(raw) }.getOrNull() }.orEmpty()

    fun saveRecent(who: String, list: List<RecentSource>) = prefs.edit().putString("recent.$who", json.encodeToString(list)).apply()
}
