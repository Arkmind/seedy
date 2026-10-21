package io.github.arkmind.seedy.core.admin.system

import io.github.arkmind.seedy.core.admin.AdminException
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.JellyfinJson
import io.github.arkmind.seedy.core.admin.SeedyAdminStatus
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.ZoneOffset

/*
 * What the System pages (tasks, activity, plugins, backups, logs) read beyond the shared admin models, as in
 * web/src/screens/server/api.ts.
 */

/** GET /Repositories. */
@Serializable
data class RepositoryInfo(val name: String = "", val url: String = "", val enabled: Boolean = true)

/** GET /web/ConfigurationPages: a plugin's own settings page in Jellyfin's web app. */
@Serializable
data class ConfigPage(val name: String = "", val displayName: String? = null, val pluginId: String? = null, val enableInMainMenu: Boolean = false)

/** POST /Plugins/{id}/Manifest: the parts a plugin that won't load is judged by. */
@Serializable
data class PluginManifest(val targetAbi: String? = null, val version: String? = null, val owner: String? = null)

/** What a backup holds; PascalCase in Jellyfin's JSON and inside Seedy's schedule alike. */
@Serializable
data class BackupOptions(
    @SerialName("Database") val database: Boolean = true,
    @SerialName("Metadata") val metadata: Boolean = true,
    @SerialName("Subtitles") val subtitles: Boolean = true,
    @SerialName("Trickplay") val trickplay: Boolean = false,
)

/** GET /Seedy/Admin/BackupFiles: an archive's size and why it was made ("manual", "automatic", "upgrade"). */
@Serializable
data class BackupFile(val fileName: String = "", val size: Long = 0, val kind: String = "manual")

/** GET/POST /Seedy/Admin/BackupSchedule. [timeOfDay] is "04:00" on the server's clock. */
@Serializable
data class BackupSchedule(
    val enabled: Boolean = false,
    val dayOfWeek: String = "Sunday",
    val timeOfDay: String = "04:00",
    val keep: Int = 4,
    val options: BackupOptions = BackupOptions(),
)

/** GET /System/Logs. */
@Serializable
data class LogFile(val dateCreated: String = "", val dateModified: String = "", val size: Long = 0, val name: String = "")

/** GET /Items, for naming what an activity entry played. */
@Serializable
data class ItemBrief(
    val id: String = "",
    val name: String = "",
    val type: String = "",
    val seriesName: String? = null,
    val parentIndexNumber: Int? = null,
    val indexNumber: Int? = null,
    val indexNumberEnd: Int? = null,
)

/** A list body for Jellyfin (triggers, repositories) as the JSON tree [AdminHttp] sends as it is. */
internal inline fun <reified T> jellyfinList(list: List<T>): JsonElement =
    JellyfinJson.encodeToJsonElement(ListSerializer(kotlinx.serialization.serializer<T>()), list)

/** Seedy's endpoints are missing on an older plugin (404, or 405 for a path it doesn't route). */
internal fun Throwable.isNotFound(): Boolean = this is AdminException && (status == 404 || status == 405)

/** The reason after "Couldn’t load the backups.": the web's "the server did not answer", or what it said. */
internal fun reason(e: Throwable): String = errorText(e)

internal fun rethrowCancel(e: Throwable) {
    if (e is kotlinx.coroutines.CancellationException) throw e
}

/** The server clock's UTC offset in minutes (Seedy's status), remembered for the app's run. */
internal object ServerClock {
    @Volatile
    var offset: Int? = null
        private set
    private var asked = false

    suspend fun load(http: AdminHttp, hasSeedy: Boolean): Int? {
        if (offset != null || asked || !hasSeedy) return offset
        asked = true
        offset = runCatching { http.get<SeedyAdminStatus>("/Seedy/Admin/Status", json = SeedyJson).utcOffsetMinutes }.getOrNull()
        if (offset == null) asked = false
        return offset
    }

    /** True when the server's clock isn't this device's, so "Daily at 03:00" needs " (server time)". */
    fun differs(offset: Int?, clock: Clock): Boolean {
        if (offset == null) return false
        val here = clock.zone.rules.getOffset(clock.instant()).totalSeconds / 60
        return offset != here
    }

    fun zone(offset: Int?, clock: Clock) = offset?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: clock.zone
}

/** "{schedule} (server time)" when the server's clock differs from the phone's. */
internal fun withServerTime(text: String, onServerClock: Boolean): String =
    if (onServerClock) t("{schedule} (server time)", "schedule" to text) else text
