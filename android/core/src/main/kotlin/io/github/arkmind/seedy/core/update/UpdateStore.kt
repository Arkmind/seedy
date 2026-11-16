package io.github.arkmind.seedy.core.update

import android.content.Context
import kotlinx.serialization.json.Json

/**
 * What the update check keeps on this device: the last offer it found (so GitHub is asked at most every
 * [RecheckMs]), and the version someone answered "Later" to (not offered again for [LaterMs]).
 */
class UpdateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("seedy.update", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** The last check's offer, while it's recent (null: check again). An empty offer means "nothing newer". */
    fun cached(now: Long): UpdateOffer? {
        val at = prefs.getLong("checkedAt", 0L)
        if (now - at !in 0 until RecheckMs) return null
        return prefs.getString("offer", null)?.let { runCatching { json.decodeFromString(UpdateOffer.serializer(), it) }.getOrNull() }
    }

    /** The notes kept for [code], however old (Google Play's update). */
    fun notesFor(code: Long): UpdateOffer? =
        prefs.getString("offer", null)?.let { runCatching { json.decodeFromString(UpdateOffer.serializer(), it) }.getOrNull() }?.takeIf { it.code == code }

    fun save(offer: UpdateOffer?, now: Long) =
        prefs.edit().putLong("checkedAt", now).putString("offer", json.encodeToString(UpdateOffer.serializer(), offer ?: UpdateOffer("", 0))).apply()

    fun later(code: Long, now: Long) = prefs.edit().putLong("laterCode", code).putLong("laterAt", now).apply()

    /** Whether "Later" was the answer to [code] recently. */
    fun snoozed(code: Long, now: Long): Boolean =
        prefs.getLong("laterCode", 0L) == code && now - prefs.getLong("laterAt", 0L) in 0 until LaterMs

    /** The version the APK updater is installing (so the new version knows it came from there). */
    fun installing(code: Long) = prefs.edit().putLong("installing", code).apply()

    /** Whether [code] is the version the APK updater installed; asked once. */
    fun takeInstalled(code: Long): Boolean {
        val was = prefs.getLong("installing", 0L)
        if (was != 0L) prefs.edit().remove("installing").apply()
        return was == code
    }

    companion object {
        const val RecheckMs = 6 * 3_600_000L
        const val LaterMs = 7 * 24 * 3_600_000L
    }
}
