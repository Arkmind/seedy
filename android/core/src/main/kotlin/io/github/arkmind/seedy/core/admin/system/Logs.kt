package io.github.arkmind.seedy.core.admin.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.fmtSize
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jellyfin.sdk.api.client.HttpMethod
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

/*
 * Logs (web/src/screens/server/Logs.tsx and logs.ts, web board "Server · Logs"): the server's log files, newest
 * first, and a viewer that searches, filters by level, follows a file that's being written, copies and downloads.
 */

/* ── Jellyfin's Serilog lines: "[2026-10-19 13:10:09.983 +00:00] [INF] [4] Emby.Server.Implementations.Foo: message",
   shown as "20:01:58  INF  Foo: message" in this phone's time zone. ── */

data class LogEntry(val i: Int, val time: String, val level: String, val text: String, val raw: String)

class ParsedLog(val entries: List<LogEntry>, val structured: Boolean, val text: String, val lastStart: Int)

private val Line = Regex("^\\[(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2})(?:\\.\\d+)? ?([+-]\\d{2}:?\\d{2}|Z)?] \\[(VRB|DBG|INF|WRN|ERR|FTL)] (?:\\[\\d+] )?(.*)$")
private val Source = Regex("^((?:[A-Za-z_][\\w`]*\\.)+)([A-Za-z_][\\w`]*(?:\\[[^\\]]*])?): ")

private fun localTime(date: String, time: String, offset: String?, clock: Clock): String {
    if (offset == null) return time
    val off = if (offset == "Z") "Z" else if (offset.length == 5) offset.substring(0, 3) + ":" + offset.substring(3) else offset
    val d = runCatching { OffsetDateTime.parse("${date}T$time$off") }.getOrNull() ?: return time
    val z = d.atZoneSameInstant(clock.zone)
    return "%02d:%02d:%02d".format(z.hour, z.minute, z.second)
}

/**
 * Parses the text. When the new text only grows the old one (a log being written), the entries before the last one
 * are kept as they are and only the tail is read again.
 */
fun parseLog(text: String, clock: Clock, prev: ParsedLog? = null): ParsedLog {
    val entries = mutableListOf<LogEntry>()
    var offset = 0
    var structured = false
    if (prev != null && prev.structured && prev.entries.size > 1 && text.length >= prev.text.length && text.startsWith(prev.text)) {
        entries += prev.entries.dropLast(1)
        offset = prev.lastStart
        structured = true
    }
    val lines = text.substring(offset).split('\n').toMutableList()
    if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.lastIndex)
    if (!structured) structured = lines.take(40).any { Line.matches(it.removeSuffix("\r")) }
    var lastStart = offset
    for (rawLine in lines) {
        val line = rawLine.removeSuffix("\r")
        val m = if (structured) Line.find(line) else null
        if (m != null) {
            lastStart = offset
            val g = m.groupValues
            entries += LogEntry(entries.size, localTime(g[1], g[2], g[3].ifEmpty { null }, clock), g[4], g[5].replace(Source, "$2: "), line)
        } else if (structured && entries.isNotEmpty()) {
            val e = entries.last()
            entries[entries.lastIndex] = e.copy(text = e.text + "\n" + line, raw = e.raw + "\n" + line)
        } else {
            lastStart = offset
            entries += LogEntry(entries.size, "", "", line, line)
        }
        offset += rawLine.length + 1
    }
    return ParsedLog(entries, structured, text, lastStart)
}

fun isWarnLevel(l: String) = l == "WRN" || l == "ERR" || l == "FTL"
fun isErrorLevel(l: String) = l == "ERR" || l == "FTL"

/* ── File list labels ── */

private val Daily = Regex("^log_(\\d{4})(\\d{2})(\\d{2})\\.(log|txt)$", RegexOption.IGNORE_CASE)
private val Ffmpeg = Regex("^FFmpeg\\.([A-Za-z]+)-(\\d{4})-(\\d{2})-(\\d{2})_(\\d{2})-(\\d{2})")

fun isDailyLog(name: String) = Daily.matches(name)

/** FFmpeg log kinds Jellyfin writes, and how a titled one reads ("Sintel transcode"). Others keep the file's word. */
private val Kinds = mapOf(
    "Transcode" to ("Transcode" to "{title} transcode"),
    "Remux" to ("Remux" to "{title} remux"),
    "DirectStream" to ("Direct Stream" to "{title} direct stream"),
)

/** "Today", "Yesterday", "Sat 17 Oct"; "Sintel transcode" once its title is known, else "Transcode · 14:09". */
fun logFileLabel(name: String, title: String?, clock: Clock): String {
    Daily.find(name)?.let { m ->
        val g = m.groupValues
        val date = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
        return when (ChronoUnit.DAYS.between(LocalDate.now(clock), date)) {
            0L -> t("Today")
            -1L -> t("Yesterday")
            else -> weekdayDate(date)
        }
    }
    Ffmpeg.find(name)?.let { m ->
        val g = m.groupValues
        val known = Kinds[g[1]]
        val kind = known?.let { t(it.first) } ?: g[1].replace(Regex("([a-z])([A-Z])"), "$1 $2")
        if (!title.isNullOrEmpty()) return if (known != null) t(known.second, "title" to title) else "$title ${kind.lowercase()}"
        return "$kind · ${g[5]}:${g[6]}"
    }
    return name
}

/** FFmpeg logs for playback start with the media source as JSON; its Name is the title. */
fun wantsTitle(name: String) = Regex("^FFmpeg\\.(Transcode|Remux|DirectStream)-", RegexOption.IGNORE_CASE).containsMatchIn(name)

fun titleFromLog(text: String): String? {
    val nl = text.indexOf('\n')
    val first = text.substring(0, if (nl > 0) nl else minOf(text.length, 4000))
    val m = Regex("\"Name\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(first) ?: return null
    return runCatching { Json.decodeFromString<String>("\"${m.groupValues[1]}\"") }.getOrNull()
        ?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")?.trim()?.ifEmpty { null }
}

/** "FFmpeg.Transcode-2026-10-19_14-09-30_fb0a…_07a39e63.log" → "FFmpeg.Transcode-2026-10-19_14-09-30_….log". */
fun shortFileName(name: String): String = name.replace(Regex("_[0-9a-f]{16,}(?:_[0-9a-f]{6,})?(\\.\\w+)$", RegexOption.IGNORE_CASE), "_…$1")

/* ── The page ── */

enum class LogLevelFilter(val label: String) { All("All"), Warn("Warnings"), Error("Errors") }

data class LogFileRow(val name: String, val label: String, val sub: String, val writing: Boolean)

data class LogLine(val i: Int, val time: String, val level: String, val text: String, val hit: Boolean = false, val current: Boolean = false)

data class LogViewerState(
    val name: String,
    val label: String,
    /** "log_20261021.log · 275 KB". */
    val sub: String,
    val live: Boolean,
    val following: Boolean = true,
    val loading: Boolean = true,
    val error: String? = null,
    val structured: Boolean = true,
    val lines: List<LogLine> = emptyList(),
    val term: String = "",
    val level: LogLevelFilter = LogLevelFilter.All,
    /** "4 matches", "2 of 4"; empty without a search. */
    val count: String = "",
    val matches: Int = 0,
    /** The line to bring into view (its position in [lines]) after a jump to a match; [jump] changes each time. */
    val scrollTo: Int? = null,
    val jump: Int = 0,
    /** "This log is empty.", "No errors in this log." */
    val empty: String? = null,
)

data class LogsState(
    val lede: String = "",
    /** Null while loading. */
    val files: List<LogFileRow>? = null,
    val hidden: Int = 0,
    val error: String? = null,
    val viewer: LogViewerState? = null,
    val message: String? = null,
)

interface LogsActions {
    fun open(name: String, query: String = "") {}
    fun close() {}
    fun showAll() {}
    fun setTerm(term: String) {}
    fun setLevel(level: LogLevelFilter) {}
    /** The next (1) or previous (-1) match. */
    fun jump(dir: Int) {}
    fun setFollowing(on: Boolean) {}
    /** The shown lines as the server wrote them (Copy). */
    fun linesText(): String = ""
    fun retry() {}
    fun say(text: String) {}
    fun messageShown() {}
}

/** Titles read from FFmpeg logs ("Sintel transcode"), kept for the app's run. */
private object LogTitles {
    val map = mutableMapOf<String, String>()
}

private const val OthersShown = 12

class LogsViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), LogsActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(LogsState(lede = t("The server's log files, newest first. How long they’re kept and slow-response warnings are set in Settings.")))
    val state: StateFlow<LogsState> = _state.asStateFlow()

    private var files: List<LogFile>? = null
    private var showAll = false
    private var parsed: ParsedLog? = null
    private var size = -1L
    private var hit = -1
    private var tail: Job? = null
    private var read: Job? = null

    fun load() {
        viewModelScope.launch { loadFiles()?.let { nameFfmpegLogs(it) } }
        viewModelScope.launch {
            runCatching { http.getObject("/System/Configuration") }.onSuccess { cfg ->
                val days = (cfg["LogFileRetentionDays"] as? JsonPrimitive)?.intOrNull
                if (days != null && days > 0) _state.update {
                    it.copy(lede = tn(days, "The server's log files, newest first. Kept for {n} day and slow-response warnings are set in Settings.", "The server's log files, newest first. Kept for {n} days and slow-response warnings are set in Settings."))
                }
            }.onFailure(::rethrowCancel)
        }
    }

    private suspend fun loadFiles(): List<LogFile>? = try {
        val l = http.get<List<LogFile>>("/System/Logs")
        files = l
        _state.update { it.copy(error = null) }
        publish()
        l
    } catch (e: Throwable) {
        rethrowCancel(e)
        if (files == null) _state.update { it.copy(error = t("Couldn’t load the log files.") + " " + errorText(e)) }
        null
    }

    /** Name the FFmpeg logs after what they played: their first line is the media source. */
    private suspend fun nameFfmpegLogs(list: List<LogFile>) {
        for (f in list.filter { wantsTitle(it.name) && it.name !in LogTitles.map && it.size < 2_000_000 }.take(24)) {
            val title = runCatching { titleFromLog(text(f.name)) }.onFailure(::rethrowCancel).getOrNull()
            LogTitles.map[f.name] = title.orEmpty()
            publish()
        }
    }

    private suspend fun text(name: String): String = http.raw(HttpMethod.GET, "/System/Logs/Log", mapOf("name" to name)).decodeToString()

    /** Daily logs first (newest first), then FFmpeg and other logs. */
    private fun ordered(): List<LogFile> {
        val l = files ?: return emptyList()
        return l.filter { isDailyLog(it.name) }.sortedByDescending { it.name } + l.filter { !isDailyLog(it.name) }.sortedByDescending { it.dateModified }
    }

    private fun isLive(f: LogFile, newest: LogFile?): Boolean =
        f == newest || (realDate(f.dateModified)?.let { clock.millis() - it.toEpochMilli() < 90_000 } ?: false)

    private fun publish() {
        val all = ordered()
        val newest = all.firstOrNull { isDailyLog(it.name) }
        val others = all.filter { !isDailyLog(it.name) }
        val current = _state.value.viewer?.name
        val shown = if (showAll) all else all.filter { isDailyLog(it.name) || others.indexOf(it) < OthersShown || it.name == current }
        _state.update { st ->
            st.copy(
                files = files?.let { shown.map { f -> LogFileRow(f.name, logFileLabel(f.name, LogTitles.map[f.name], clock), "${shortFileName(f.name)} · ${fmtSize(f.size)}", isLive(f, newest)) } },
                hidden = all.size - shown.size,
            )
        }
    }

    override fun showAll() {
        showAll = true
        publish()
    }

    override fun retry() {
        val v = _state.value.viewer
        if (v != null) readFile(v.name) else viewModelScope.launch { loadFiles() }
    }

    override fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun open(name: String, query: String) {
        val all = ordered()
        val f = all.firstOrNull { it.name.equals(name, ignoreCase = true) }
        val newest = all.firstOrNull { isDailyLog(it.name) }
        parsed = null
        hit = -1
        size = f?.size ?: -1
        _state.update {
            it.copy(
                viewer = LogViewerState(
                    name = f?.name ?: name,
                    label = logFileLabel(f?.name ?: name, LogTitles.map[name], clock),
                    sub = listOfNotNull(shortFileName(f?.name ?: name), f?.let { x -> fmtSize(x.size) }).joinToString(" · "),
                    live = f != null && isLive(f, newest),
                    term = query,
                ),
            )
        }
        readFile(f?.name ?: name)
        startTail()
    }

    override fun close() {
        tail?.cancel()
        read?.cancel()
        parsed = null
        _state.update { it.copy(viewer = null) }
        publish()
    }

    private fun readFile(name: String) {
        read?.cancel()
        _state.update { st -> st.copy(viewer = st.viewer?.copy(error = null)) }
        read = viewModelScope.launch {
            try {
                val next = parseLog(text(name), clock, parsed)
                parsed = next
                _state.update { st -> st.copy(viewer = st.viewer?.copy(loading = false, structured = next.structured)) }
                lines()
            } catch (e: Throwable) {
                rethrowCancel(e)
                if (parsed == null) _state.update { st -> st.copy(viewer = st.viewer?.copy(loading = false, error = t("Couldn’t read {file}. {error}", "file" to name, "error" to errorText(e)))) }
            }
        }
    }

    /** While following a file that's being written, re-read it whenever it grows. */
    private fun startTail() {
        tail?.cancel()
        tail = viewModelScope.launch {
            while (true) {
                delay(2_500)
                val v = _state.value.viewer ?: break
                if (!v.live || !v.following) continue
                val f = loadFiles()?.firstOrNull { it.name == v.name } ?: continue
                if (f.size != size) {
                    size = f.size
                    _state.update { st -> st.copy(viewer = st.viewer?.copy(sub = "${shortFileName(f.name)} · ${fmtSize(f.size)}")) }
                    readFile(f.name)
                }
            }
        }
    }

    private fun filtered(v: LogViewerState): List<LogEntry> {
        val all = parsed?.entries ?: return emptyList()
        return when (v.level) {
            LogLevelFilter.All -> all
            LogLevelFilter.Warn -> all.filter { isWarnLevel(it.level) }
            LogLevelFilter.Error -> all.filter { isErrorLevel(it.level) }
        }
    }

    /** Rebuilds the shown lines: the level filter, the search's hits and the current one. */
    private fun lines(scrollTo: Int? = null) {
        val v = _state.value.viewer ?: return
        val list = filtered(v)
        val q = v.term.trim().lowercase()
        val matchIds = if (q.isEmpty()) emptyList() else list.filter { it.text.lowercase().contains(q) || it.time.contains(q) }.map { it.i }
        val matchSet = matchIds.toSet()
        val current = if (hit in matchIds.indices) matchIds[hit] else -1
        val count = when {
            q.isEmpty() -> ""
            hit >= 0 && hit < matchIds.size -> t("{i} of {n}", "i" to hit + 1, "n" to matchIds.size)
            else -> tn(matchIds.size, "{n} match", "{n} matches")
        }
        _state.update { st ->
            st.copy(
                viewer = st.viewer?.copy(
                    lines = list.map { LogLine(it.i, it.time, it.level, it.text, it.i in matchSet, it.i == current) },
                    count = count,
                    matches = matchIds.size,
                    scrollTo = scrollTo ?: st.viewer.scrollTo,
                    jump = if (scrollTo != null) st.viewer.jump + 1 else st.viewer.jump,
                    empty = if (parsed != null && list.isEmpty()) when (v.level) {
                        LogLevelFilter.All -> t("This log is empty.")
                        LogLevelFilter.Warn -> t("No warnings or errors in this log.")
                        LogLevelFilter.Error -> t("No errors in this log.")
                    } else null,
                ),
            )
        }
    }

    override fun setTerm(term: String) {
        hit = -1
        _state.update { st -> st.copy(viewer = st.viewer?.copy(term = term)) }
        lines()
    }

    override fun setLevel(level: LogLevelFilter) {
        hit = -1
        _state.update { st -> st.copy(viewer = st.viewer?.copy(level = level)) }
        lines()
    }

    override fun jump(dir: Int) {
        val v = _state.value.viewer ?: return
        val list = filtered(v)
        val q = v.term.trim().lowercase()
        if (q.isEmpty()) return
        val matchIds = list.filter { it.text.lowercase().contains(q) || it.time.contains(q) }.map { it.i }
        if (matchIds.isEmpty()) return
        hit = if (hit < 0) (if (dir > 0) 0 else matchIds.size - 1) else (hit + dir + matchIds.size) % matchIds.size
        _state.update { st -> st.copy(viewer = st.viewer?.copy(following = false)) }
        lines(scrollTo = list.indexOfFirst { it.i == matchIds[hit] })
    }

    override fun setFollowing(on: Boolean) {
        val v = _state.value.viewer ?: return
        if (v.following == on) return
        if (on) hit = -1
        _state.update { st -> st.copy(viewer = st.viewer?.copy(following = on)) }
        if (on) lines()
    }

    override fun linesText(): String {
        val v = _state.value.viewer ?: return ""
        val all = parsed?.entries ?: return ""
        return when (v.level) {
            LogLevelFilter.All -> all
            LogLevelFilter.Warn -> all.filter { isWarnLevel(it.level) }
            LogLevelFilter.Error -> all.filter { isErrorLevel(it.level) }
        }.joinToString("\n") { it.raw }
    }
}
