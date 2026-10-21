package io.github.arkmind.seedy.core.admin.seedy

import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/*
 * Seedy's own admin settings (GET/POST /Seedy/Admin/Config, camelCase), as web/src/screens/admin/config.ts keeps them:
 * loaded once per page, changed in place and saved whole after a short pause (Branding, Requests, Play log), or a
 * section at a time (Home screen). What's sent is the object the server gave with this app's changes merged in, so
 * settings a newer plugin adds survive a save from here.
 */

@Serializable
data class BrandingConfig(
    val name: String = "Seedy",
    val accent: String = "#ff9fff",
    val hasLogo: Boolean = false,
    val logoVersion: String = "0",
    val logoFileName: String? = null,
    /** "backdrops" or "plain". */
    val signInBackground: String = "backdrops",
)

/** One of the default Home rows; [count] is how many rows of that kind (Because you watched, Genre rows). */
@Serializable
data class HomeRowConfig(val kind: String, val enabled: Boolean = true, val count: Int? = null)

/** [watchedWhen] is "finished" or "half"; 0 days looks back without limit. */
@Serializable
data class BecauseConfig(val watchedWhen: String = "finished", val lookBackDays: Int = 60, val minTitles: Int = 4)

@Serializable
data class Top10Config(val periodDays: Int = 7, val minProfiles: Int = 3)

@Serializable
data class GenresConfig(val windowDays: Int = 90, val starting: List<String> = emptyList())

/** [mode] is "auto", "picks" or "both"; [picks] are item ids. */
@Serializable
data class FeaturedConfig(val mode: String = "both", val picks: List<String> = emptyList())

@Serializable
data class HomeConfig(
    val rows: List<HomeRowConfig> = emptyList(),
    val adaptOrder: Boolean = true,
    val because: BecauseConfig = BecauseConfig(),
    val top10: Top10Config = Top10Config(),
    val genres: GenresConfig = GenresConfig(),
    val featured: FeaturedConfig = FeaturedConfig(),
)

/** [upcomingSource] is "requests" or "requestsAndRadarr"; [who] is "everyone" or "selected". */
@Serializable
data class RequestsConfig(
    val url: String = "",
    val apiKeyHint: String? = null,
    val showDiscover: Boolean = true,
    val showUpcomingMovies: Boolean = true,
    val showRequestButtons: Boolean = true,
    val showRequestInSearch: Boolean = true,
    val upcomingSource: String = "requests",
    val who: String = "everyone",
    val allow4k: Boolean = false,
)

/** [retentionDays] 0 keeps plays forever. */
@Serializable
data class PlayLogConfig(
    val enabled: Boolean = true,
    val retentionDays: Int = 365,
    val useTop10: Boolean = true,
    val useHistory: Boolean = true,
    val useRowOrder: Boolean = true,
    val useGenres: Boolean = true,
)

/** [opensAt] is "alongside" (Seedy under /seedy) or "replace" (Seedy at the server address). */
@Serializable
data class AdminConfig(
    val opensAt: String = "alongside",
    val branding: BrandingConfig = BrandingConfig(),
    val playbackDefaults: PlaybackPrefs = PlaybackPrefs(),
    val home: HomeConfig = HomeConfig(),
    val requests: RequestsConfig = RequestsConfig(),
    val playLog: PlayLogConfig = PlayLogConfig(),
)

/* ── Home rows, as the web normalises them ── */

/** Featured and Continue watching: always first and second, always on. */
val LockedRows = setOf("featured", "continue")

val RowKinds = listOf(
    "featured", "continue", "nextup", "top10", "because", "genres", "mymedia", "latest",
    "discover", "upcomingMovies", "upcomingShows", "collections",
)

private val DefaultCount = mapOf("because" to 6, "genres" to 4)

/** Every known kind once, in the saved order, the locked ones first; unknown kinds dropped. */
fun normaliseRows(rows: List<HomeRowConfig>): List<HomeRowConfig> {
    val list = rows.filter { it.kind in RowKinds }
    val byKind = list.associateBy { it.kind }
    val out = mutableListOf<HomeRowConfig>()
    val seen = mutableSetOf<String>()
    fun push(r: HomeRowConfig) {
        if (!seen.add(r.kind)) return
        val count = DefaultCount[r.kind]
        out += if (count != null) r.copy(count = r.count ?: count) else r
    }
    for (k in listOf("featured", "continue")) push((byKind[k] ?: HomeRowConfig(k)).copy(enabled = true))
    for (r in list) if (r.kind !in LockedRows) push(r)
    for (k in RowKinds) if (k !in seen) push(HomeRowConfig(k, enabled = k != "collections"))
    return out
}

fun AdminConfig.normalised(): AdminConfig = copy(home = home.copy(rows = normaliseRows(home.rows)))

/* ── JSON trees ── */

/** [this] with [patch] laid over it: objects merge key by key, everything else is replaced. */
fun JsonObject.mergedWith(patch: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(this)
    for ((k, v) in patch) {
        val old = out[k]
        out[k] = if (old is JsonObject && v is JsonObject) old.mergedWith(v) else v
    }
    return JsonObject(out)
}

fun decodeAdminConfig(raw: JsonObject): AdminConfig = SeedyJson.decodeFromJsonElement(AdminConfig.serializer(), raw).normalised()

fun encodeAdminConfig(c: AdminConfig): JsonObject = SeedyJson.encodeToJsonElement(AdminConfig.serializer(), c).jsonObject

enum class SaveState { Saved, Saving, Error }

/**
 * The admin settings one page works on. [edit] changes them now and saves after [edit]'s pause; [commit] saves at
 * once and puts the old value back if the server refuses it. [onSaved] runs after each save the server accepted.
 */
class SeedyConfigStore(
    private val http: AdminHttp,
    private val scope: CoroutineScope,
    private val onSaved: (AdminConfig) -> Unit = {},
) {
    private val _config = MutableStateFlow<AdminConfig?>(null)
    val config: StateFlow<AdminConfig?> = _config.asStateFlow()

    private val _save = MutableStateFlow(SaveState.Saved)
    val save: StateFlow<SaveState> = _save.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var raw = JsonObject(emptyMap())
    private var version = 0
    private var savedVersion = 0
    private var timer: Job? = null
    private val mutex = Mutex()

    suspend fun load() {
        _loadError.value = null
        try {
            take(http.get<JsonObject>("/Seedy/Admin/Config", json = SeedyJson))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _loadError.value = errorText(e)
        }
    }

    /** The server's settings as they are now (a logo upload or a saved key answers with them). */
    fun take(server: JsonObject) {
        raw = server
        _config.value = decodeAdminConfig(server)
        savedVersion = version
    }

    private fun apply(next: AdminConfig) {
        _config.value = next
        raw = raw.mergedWith(encodeAdminConfig(next))
        version++
        _save.value = SaveState.Saving
    }

    /** Changes the settings now and saves them after [delayMs] without another change. */
    fun edit(delayMs: Long = 600, fn: (AdminConfig) -> AdminConfig) {
        val cur = _config.value ?: return
        apply(fn(cur))
        timer?.cancel()
        timer = scope.launch {
            delay(delayMs)
            timer = null
            runCatching { send() }
        }
    }

    /** Saves what's waiting now. */
    suspend fun flush() {
        timer?.cancel()
        timer = null
        send()
    }

    /** Changes and saves at once; on failure the settings go back to what they were and the error is thrown. */
    suspend fun commit(fn: (AdminConfig) -> AdminConfig) {
        runCatching { flush() }
        val before = _config.value ?: return
        val beforeRaw = raw
        apply(fn(before))
        try {
            flush()
        } catch (e: Exception) {
            _config.value = before
            raw = beforeRaw
            throw e
        }
    }

    private suspend fun send() = mutex.withLock {
        while (true) {
            val v = version
            if (v == savedVersion) {
                _save.value = SaveState.Saved
                return@withLock
            }
            val body = raw
            try {
                val res = http.post<JsonObject>("/Seedy/Admin/Config", body, json = SeedyJson)
                if (v != version) {
                    // A newer change came in while this one was saving: it saves on its own pause, or now.
                    if (timer != null) return@withLock
                    continue
                }
                take(res)
                _save.value = SaveState.Saved
                onSaved(_config.value!!)
                return@withLock
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (v != version) {
                    if (timer != null) return@withLock
                    continue
                }
                _save.value = SaveState.Error
                throw e
            }
        }
    }
}
