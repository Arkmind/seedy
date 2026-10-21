package io.github.arkmind.seedy.core.admin.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.BackupManifest
import io.github.arkmind.seedy.core.admin.JellyfinJson
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.fmtSize
import io.github.arkmind.seedy.core.admin.hhmm
import io.github.arkmind.seedy.core.admin.majorMinor
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.admin.weekdayLabel
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URLEncoder
import java.time.Clock
import java.time.Instant

/*
 * Backups (web/src/screens/server/Backups.tsx, web board "Server · Backups"): a new backup with the parts to include,
 * Seedy's weekly automatic backup, and the archives on the server to download, restore or delete. Jellyfin 12 can
 * create, list and restore; sizes, downloads, deleting and the schedule come from the Seedy plugin when it's there.
 */

data class BackupPart(val key: String, val name: String, val help: String, val on: Boolean)

data class BackupRow(
    val path: String,
    val fileName: String,
    /** "19 Oct 2026, 14:02". */
    val whenText: String,
    /** "Manual", "Automatic", "Before upgrade". */
    val kind: String,
    /** "Database, metadata, subtitles". */
    val parts: String,
    /** "1.8 GB · Jellyfin 12.0". */
    val meta: String,
    val restoreTitle: String,
    val restoreText: String,
    val deleteTitle: String,
)

data class BackupsState(
    val parts: List<BackupPart> = emptyList(),
    val creating: Boolean = false,
    val createError: String? = null,
    /** Whether Seedy can size, serve, delete and schedule backups; null until known. */
    val supported: Boolean? = null,
    val scheduleLoaded: Boolean = false,
    val autoOn: Boolean = false,
    /** "Sunday at 04:00, with the parts checked above". */
    val autoHelp: String = "",
    val keep: Int = 4,
    /** Null while loading. */
    val rows: List<BackupRow>? = null,
    val listError: String? = null,
    val message: String? = null,
) {
    val anyPart: Boolean get() = parts.any { it.on }
}

interface BackupsActions {
    fun setPart(key: String, on: Boolean) {}
    fun create() {}
    fun setAuto(on: Boolean) {}
    fun setKeep(n: Int) {}
    fun restore(path: String) {}
    fun delete(path: String) {}
    fun retry() {}
    fun say(text: String) {}
    fun messageShown() {}
}

private class PartCopy(val key: String, val name: String, val help: String, val short: String)

private val Parts = listOf(
    PartCopy("Database", "Database", "Users, watch history, settings, library index", "database"),
    PartCopy("Metadata", "Metadata", "Downloaded artwork and info", "metadata"),
    PartCopy("Subtitles", "Subtitles", "Downloaded and extracted subtitles", "subtitles"),
    PartCopy("Trickplay", "Seek previews", "Trickplay images, often the largest part", "seek previews"),
)

private fun BackupOptions.has(key: String) = when (key) {
    "Database" -> database
    "Metadata" -> metadata
    "Subtitles" -> subtitles
    else -> trickplay
}

private fun BackupOptions.with(key: String, on: Boolean) = when (key) {
    "Database" -> copy(database = on)
    "Metadata" -> copy(metadata = on)
    "Subtitles" -> copy(subtitles = on)
    else -> copy(trickplay = on)
}

/** "Database, metadata, subtitles", or "Server settings only". */
fun partsLine(o: BackupOptions): String {
    val list = Parts.filter { o.has(it.key) }.map { t(it.short) }
    if (list.isEmpty()) return t("Server settings only")
    return list.joinToString(", ").replaceFirstChar { it.uppercaseChar() }
}

/** "19 Oct 2026, 14:02". */
fun dateTime(d: Instant, clock: Clock): String = t("{date}, {time}", "date" to fullDate(d.atZone(clock.zone).toLocalDate()), "time" to hhmm(d, clock))

fun fileOf(path: String): String = path.split('/', '\\').last()

/** The parts picked last time (the web keeps them in the browser). */
private object BackupParts {
    var options: BackupOptions? = null
}

class BackupsViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), BackupsActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(BackupsState())
    val state: StateFlow<BackupsState> = _state.asStateFlow()

    private var parts = BackupParts.options ?: BackupOptions()
    private var schedule: BackupSchedule? = null
    private var list: List<BackupManifest>? = null
    private var files: Map<String, BackupFile>? = null
    private var supported: Boolean? = if (session.seedyApi == null) false else null
    private var offset: Int? = ServerClock.offset
    private var saveJob: Job? = null
    /** A schedule edit still waiting for its delay when the page closes is sent then, not dropped. */
    private var pendingSave: BackupSchedule? = null
    private var saveSeq = 0

    init {
        publish()
    }

    fun load() {
        loadList()
        if (supported != false) {
            viewModelScope.launch {
                runCatching { http.get<BackupSchedule>("/Seedy/Admin/BackupSchedule", json = SeedyJson) }
                    .onSuccess { schedule = it; parts = it.options; BackupParts.options = parts }
                    .onFailure { rethrowCancel(it); if (it.isNotFound() || it is kotlinx.serialization.SerializationException) supported = false }
                publish()
            }
            viewModelScope.launch { offset = ServerClock.load(http, session.seedyApi != null); publish() }
        }
    }

    private fun loadList() {
        _state.update { it.copy(listError = null) }
        viewModelScope.launch {
            runCatching { http.get<List<BackupManifest>>("/Backup") }
                .onSuccess { l -> list = l.sortedByDescending { realDate(it.dateCreated) ?: Instant.EPOCH } }
                .onFailure { rethrowCancel(it); if (list == null) _state.update { s -> s.copy(listError = t("Couldn’t load the backups.") + " " + errorText(it)) } }
            publish()
        }
        if (supported == false) return
        viewModelScope.launch {
            runCatching { http.get<List<BackupFile>>("/Seedy/Admin/BackupFiles", json = SeedyJson) }
                .onSuccess { f -> supported = true; files = f.associateBy { it.fileName.lowercase() } }
                .onFailure { rethrowCancel(it); if (it.isNotFound() || it is kotlinx.serialization.SerializationException) supported = false }
            publish()
        }
    }

    private fun options(o: kotlinx.serialization.json.JsonObject?): BackupOptions =
        o?.let { runCatching { JellyfinJson.decodeFromJsonElement(BackupOptions.serializer(), it) }.getOrNull() } ?: BackupOptions(false, false, false, false)

    private fun publish() {
        val sched = schedule ?: BackupSchedule(options = parts)
        val onServerClock = ServerClock.differs(offset, clock)
        val day = weekdayLabel(sched.dayOfWeek)
        _state.update { st ->
            st.copy(
                parts = Parts.map { BackupPart(it.key, t(it.name), t(it.help), parts.has(it.key)) },
                supported = supported,
                scheduleLoaded = schedule != null,
                autoOn = supported != false && sched.enabled,
                autoHelp = if (supported == false) t("Not available on this server yet. It needs a newer Seedy plugin.")
                else if (onServerClock) t("{day} at {time} (server time), with the parts checked above", "day" to day, "time" to sched.timeOfDay)
                else t("{day} at {time}, with the parts checked above", "day" to day, "time" to sched.timeOfDay),
                keep = sched.keep,
                rows = list?.map(::row),
            )
        }
    }

    private fun row(b: BackupManifest): BackupRow {
        val name = fileOf(b.path)
        val f = files?.get(name.lowercase())
        val whenText = dateTime(realDate(b.dateCreated) ?: clock.instant(), clock)
        val o = options(b.options)
        return BackupRow(
            path = b.path,
            fileName = name,
            whenText = whenText,
            kind = t(
                when (f?.kind) {
                    "automatic" -> "Automatic"
                    "upgrade" -> "Before upgrade"
                    else -> "Manual"
                },
            ),
            parts = partsLine(o),
            meta = listOf(f?.let { fmtSize(it.size) }.orEmpty(), "Jellyfin ${majorMinor(b.serverVersion)}").filter { it.isNotEmpty() }.joinToString(" · "),
            restoreTitle = t("Restore the backup from {when}?", "when" to whenText),
            restoreText = t(
                "The server restarts and replaces its {parts} and settings with the backup’s. Anything changed since {when} is lost, and everyone watching is stopped while it restarts.",
                "parts" to partsLine(o).lowercase(), "when" to whenText,
            ),
            deleteTitle = t("Delete the backup from {when}?", "when" to whenText),
        )
    }

    override fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun retry() = loadList()

    private fun saveSchedule(next: BackupSchedule, delayMs: Long = 0) {
        schedule = next
        pendingSave = next
        publish()
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(delayMs)
            pendingSave = null
            val seq = ++saveSeq
            runCatching { http.post<BackupSchedule>("/Seedy/Admin/BackupSchedule", next, json = SeedyJson) }
                // Only the newest save's answer replaces what's on screen.
                .onSuccess { if (seq == saveSeq) { schedule = it; publish() } }
                .onFailure { rethrowCancel(it); say(t("Couldn’t save automatic backups. {error}", "error" to errorText(it))) }
        }
    }

    override fun setPart(key: String, on: Boolean) {
        parts = parts.with(key, on)
        BackupParts.options = parts
        _state.update { it.copy(createError = null) }
        val s = schedule
        if (s != null && supported == true) saveSchedule(s.copy(options = parts), 400) else publish()
    }

    override fun setAuto(on: Boolean) {
        val s = schedule ?: return
        if (supported == false) return
        saveSchedule(s.copy(enabled = on, options = parts))
    }

    override fun setKeep(n: Int) {
        val s = schedule ?: return
        if (supported == false) return
        saveSchedule(s.copy(keep = n.coerceIn(1, 52)), 300)
    }

    override fun create() {
        if (_state.value.creating || !_state.value.anyPart) return
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            runCatching { http.post<BackupManifest>("/Backup/Create", parts) }
                .onSuccess { m ->
                    say(t("Backup created · {when}", "when" to dateTime(realDate(m.dateCreated) ?: clock.instant(), clock)))
                    loadList()
                }
                .onFailure { rethrowCancel(it); _state.update { s -> s.copy(createError = errorText(it)) } }
            _state.update { it.copy(creating = false) }
        }
    }

    override fun restore(path: String) {
        viewModelScope.launch {
            runCatching { http.send("/Backup/Restore", buildJsonObject { put("ArchiveFileName", JsonPrimitive(fileOf(path))) }) }
                .onSuccess { say(t("Restoring. The server is restarting…")) }
                .onFailure { rethrowCancel(it); say(t("Couldn’t start the restore. {error}", "error" to errorText(it))) }
        }
    }

    override fun delete(path: String) {
        viewModelScope.launch {
            runCatching { http.delete("/Seedy/Admin/BackupFiles/" + URLEncoder.encode(fileOf(path), "UTF-8").replace("+", "%20")) }
                .onSuccess { say(t("Backup deleted")); loadList() }
                .onFailure { rethrowCancel(it); say(t("Couldn’t delete the backup. {error}", "error" to errorText(it))) }
        }
    }

    override fun onCleared() {
        val next = pendingSave ?: return
        // The page closed before the delay ran out: send the change anyway.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { http.post<BackupSchedule>("/Seedy/Admin/BackupSchedule", next, json = SeedyJson) }
        }
    }
}

/** Where Seedy serves a backup archive (with the person's token in the header). */
fun backupFilePath(fileName: String): String = "/Seedy/Admin/BackupFiles/" + URLEncoder.encode(fileName, "UTF-8").replace("+", "%20") + "/File"
