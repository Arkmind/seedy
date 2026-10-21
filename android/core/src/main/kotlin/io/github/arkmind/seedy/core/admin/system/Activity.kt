package io.github.arkmind.seedy.core.admin.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.ActivityEntry
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.AdminUser
import io.github.arkmind.seedy.core.admin.ItemsResult
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.hhmm
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.admin.realDate
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choice
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs

/*
 * Activity log (board "Android · Server · Activity log", web/src/screens/server/Activity.tsx): today's tiles, the log
 * by day with Jellyfin's English sentences rebuilt into the board's rows, filters by person and level, Live (a poll
 * every 10 s), "Show earlier", and Block address / Re-enable where a row offers them.
 */

enum class ActivityLevel { Info, Ok, Warn, Error }
enum class ActivityCategory(val tag: String) { People("People"), Playback("Playback"), SignIn("Sign-in"), System("System") }
enum class LevelFilter(val label: String) { All("All levels"), Warn("Warnings and errors"), Error("Errors only") }
enum class TypeFilter(val label: String) { All("All"), People("People"), Playback("Playback"), SignIn("Sign-ins"), System("System") }

enum class ActivityActionKind { Block, Enable }

/** A row's button and the question asked before it acts. */
data class ActivityAction(
    val kind: ActivityActionKind,
    val label: String,
    val confirmTitle: String,
    val confirmText: String,
    val danger: Boolean,
)

data class ActivityRow(
    val key: String,
    /** "20:02". */
    val time: String,
    val title: String,
    /** "Playback", "Sign-in"… */
    val tag: String,
    val detail: String,
    val level: ActivityLevel,
    val action: ActivityAction? = null,
    /** Arrived with the last refresh (briefly highlighted). */
    val fresh: Boolean = false,
)

data class ActivityDay(val key: String, val label: String, val rows: List<ActivityRow>)

data class ActivityTile(val n: String, val label: String, val alert: Boolean = false, val on: Boolean = false)

data class ActivityState(
    val lede: String = "",
    /** Plays, sign-ins, failed sign-ins, errors (today); null while loading. */
    val tiles: List<ActivityTile>? = null,
    val people: List<Choice<String>> = emptyList(),
    val person: String = "all",
    val personLabel: String = "",
    val level: LevelFilter = LevelFilter.All,
    val type: TypeFilter = TypeFilter.All,
    val live: Boolean = true,
    /** Null while loading. */
    val days: List<ActivityDay>? = null,
    val emptyTitle: String? = null,
    val emptyText: String? = null,
    val error: String? = null,
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val message: String? = null,
)

interface ActivityActions {
    fun setPerson(id: String) {}
    fun setLevel(level: LevelFilter) {}
    fun setType(type: TypeFilter) {}
    /** The failed sign-ins tile: only failed sign-ins, or everything again. */
    fun toggleFailed() {}
    fun setLive(on: Boolean) {}
    fun loadMore() {}
    fun act(key: String) {}
    fun retry() {}
    fun messageShown() {}
}

/* ── Turning Jellyfin's log lines into rows ── */

internal data class Row(
    val key: String,
    val date: Instant,
    val title: String,
    val detail: String,
    val level: ActivityLevel,
    val category: ActivityCategory,
    val userId: String? = null,
    val userName: String? = null,
    val ip: String? = null,
    val action: ActivityActionKind? = null,
    val entry: ActivityEntry,
)

private val IpLine = Regex("IP address: (.+)$")
internal fun ipOf(e: ActivityEntry?): String? = e?.shortOverview?.let { IpLine.find(it)?.groupValues?.get(1)?.trim() }

internal fun cleanIp(ip: String): String {
    val s = ip.replace(Regex("^::ffff:", RegexOption.IGNORE_CASE), "").replace(Regex("^\\[|]$"), "")
    return s.replace(Regex(":\\d+$")) { m -> if (ip.contains('.')) "" else m.value }
}

/** Addresses from the home network (or the machine itself): blocking them would lock out the household. */
fun isPrivateIp(raw: String): Boolean {
    val ip = cleanIp(raw)
    Regex("^(\\d+)\\.(\\d+)\\.(\\d+)\\.(\\d+)$").find(ip)?.let { m ->
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
    }
    val l = ip.lowercase()
    return l == "::1" || l.startsWith("fc") || l.startsWith("fd") || l.startsWith("fe80")
}

/** "3rd" / "3e". */
internal fun ordinal(n: Int): String = if (I18n.lang == Lang.Fr) "$n${if (n == 1) "er" else "e"}"
else n.toString() + if (n % 100 in 11..13) "th" else listOf("th", "st", "nd", "rd").getOrElse(n % 10) { "th" }

private const val SameMoment = 3000L
private val PlayTypes = setOf("VideoPlayback", "AudioPlayback", "VideoPlaybackStopped", "AudioPlaybackStopped", "UserDownloadingContent")

private fun ItemBrief.episodeTag(): String {
    val e = indexNumber ?: return ""
    val end = indexNumberEnd?.let { "–$it" }.orEmpty()
    return when (val s = parentIndexNumber) {
        null -> "E$e"
        0 -> t("Special {n}", "n" to "$e$end")
        else -> t("S{s} E{e}", "s" to s, "e" to "$e$end")
    }
}

private fun itemLabel(e: ActivityEntry, fallback: String, items: Map<String, ItemBrief>): String {
    val it = e.itemId?.let { items[normId(it)] } ?: return fallback.replaceFirst(" - ", " · ")
    if (it.type == "Episode" && !it.seriesName.isNullOrEmpty()) return "${it.seriesName} ${it.episodeTag()}".trim()
    return it.name
}

private fun Regex.group(s: String?, n: Int = 1): String? = s?.let { find(it)?.groupValues?.getOrNull(n) }

private val SignedIn = Regex("^(.+?) successfully authenticated$")
private val Online = Regex("^(.+?) is online from (.+)$")
private val Offline = Regex("^(.+?) has disconnected from (.+)$")
private val FailedFrom = Regex("^Failed login attempt from (.+)$")
private val LockedOut = Regex("^User (.+) has been locked out$")
private val Playing = Regex("^(.+?) is playing (.+?) on (.+)$")
private val Finished = Regex("^(.+?) has finished playing (.+?) on (.+)$")
private val Downloading = Regex("^(.+?) is downloading (.+)$")
private val AppDevice = Regex("^App: (.+?), Device: (.+)$")
private val Created = Regex("^User (.+) has been created$")
private val Deleted = Regex("^User (.+) has been deleted$")
private val Password = Regex("^Password has been changed for user (.+)$")
private val VersionLine = Regex("^Version (.+)$")
private val Updated = Regex("^(.+) was updated$")
private val Installed = Regex("^(.+) was installed$")
private val Uninstalled = Regex("^(.+) was uninstalled$")
private val InstallFailed = Regex("^(.+) installation failed$")
private val TaskFailedLine = Regex("^(.+) failed$")
private val RunningTime = Regex("^Running time: ([\\s\\S]*)$")

private fun at(e: ActivityEntry): Long = realDate(e.date)?.toEpochMilli() ?: 0L

private fun joinDot(vararg parts: String?): String = parts.filter { !it.isNullOrEmpty() }.joinToString(" · ")

/**
 * Rows for entries ordered newest first. Sign-ins that came with a session start (and the previous session's end) on
 * the same device at the same moment become one "signed in" row.
 */
internal fun buildRows(entries: List<ActivityEntry>, items: Map<String, ItemBrief>, users: List<AdminUser>, clock: Clock): List<Row> {
    val rows = mutableListOf<Row>()
    val used = mutableSetOf<Long>()
    fun userName(id: String?) = users.firstOrNull { normId(it.id) == normId(id) && !id.isNullOrEmpty() }?.name

    entries.forEachIndexed { i, e ->
        if (e.id in used) return@forEachIndexed
        val date = realDate(e.date) ?: Instant.EPOCH
        val uid = e.userId?.takeIf { !Regex("^0+$").matches(it.replace("-", "")) }
        fun row(title: String, detail: String, level: ActivityLevel, category: ActivityCategory, userName: String? = null, ip: String? = null, action: ActivityActionKind? = null) =
            Row(e.id.toString(), date, title, detail, level, category, uid, userName, ip, action, e)
        val ip = ipOf(e)

        when {
            e.type == "AuthenticationSucceeded" -> {
                val who = SignedIn.group(e.name) ?: userName(e.userId) ?: e.name
                var device = ""
                for (j in maxOf(0, i - 4) until minOf(entries.size, i + 5)) {
                    val o = entries[j]
                    if (o === e || o.userId != e.userId || abs(at(o) - at(e)) > SameMoment) continue
                    if (o.type == "SessionStarted" && device.isEmpty()) {
                        device = Online.group(o.name, 2) ?: ""
                        used += o.id
                    } else if (o.type == "SessionEnded") used += o.id
                }
                rows += row(t("{name} signed in", "name" to who), joinDot(device, ip), ActivityLevel.Info, ActivityCategory.SignIn, who, ip)
            }
            e.type == "AuthenticationFailed" -> {
                val who = FailedFrom.group(e.name) ?: ""
                // Attempts from the same address in the ten minutes up to this one.
                val burst = if (ip != null) entries.filter { o -> o.type == "AuthenticationFailed" && ipOf(o) == ip && at(o) <= at(e) && at(e) - at(o) <= 600_000 } else listOf(e)
                val n = burst.size
                // The earlier attempts of a burst are told by this row ("3rd attempt in 10 minutes").
                for (o in burst) if (o !== e && FailedFrom.group(o.name) == who) used += o.id
                val detail = when {
                    ip == null -> e.shortOverview.orEmpty()
                    n > 1 -> t("From {ip} · {nth} attempt in 10 minutes", "ip" to ip, "nth" to ordinal(n))
                    else -> t("From {ip}", "ip" to ip)
                }
                rows += row(
                    if (who.isNotEmpty()) t("Failed sign-in for “{name}”", "name" to who) else e.name, detail, ActivityLevel.Error, ActivityCategory.SignIn, who, ip,
                    if (ip != null && !isPrivateIp(ip)) ActivityActionKind.Block else null,
                )
            }
            e.type == "UserLockedOut" -> {
                val who = LockedOut.group(e.name) ?: userName(e.userId) ?: ""
                val fails = entries.count { o ->
                    o.type == "AuthenticationFailed" && at(o) <= at(e) && at(e) - at(o) <= 3_600_000 && (FailedFrom.group(o.name) ?: "").lowercase() == who.lowercase()
                }
                rows += row(
                    if (who.isNotEmpty()) t("{name} was locked out", "name" to who) else e.name,
                    if (fails > 0) tn(fails, "{n} wrong password in a row", "{n} wrong passwords in a row") else t("Too many wrong passwords"),
                    ActivityLevel.Warn, ActivityCategory.SignIn, who, action = if (uid != null) ActivityActionKind.Enable else null,
                )
            }
            e.type == "SessionStarted" && Online.find(e.name) != null -> {
                val m = Online.find(e.name)!!.groupValues
                // An app that drops its connection and comes straight back logs "disconnected" and "online" together.
                val device = m[2]
                val ended = entries.subList(maxOf(0, i - 4), minOf(entries.size, i + 5)).firstOrNull { o ->
                    o.type == "SessionEnded" && o.id !in used && o.userId == e.userId && abs(at(o) - at(e)) <= SameMoment && Offline.group(o.name, 2) == device
                }
                if (ended != null) used += ended.id
                rows += row(if (ended != null) t("{name} reconnected", "name" to m[1]) else t("{name} came online", "name" to m[1]), joinDot(device, ip), ActivityLevel.Info, ActivityCategory.SignIn, m[1], ip)
            }
            e.type == "SessionEnded" && Offline.find(e.name) != null -> {
                val m = Offline.find(e.name)!!.groupValues
                rows += row(t("{name} went offline", "name" to m[1]), joinDot(m[2], ip), ActivityLevel.Info, ActivityCategory.SignIn, m[1], ip)
            }
            (e.type == "VideoPlayback" || e.type == "AudioPlayback") && Playing.find(e.name) != null -> {
                val m = Playing.find(e.name)!!.groupValues
                rows += row(t("{name} started {item}", "name" to m[1], "item" to itemLabel(e, m[2], items)), m[3], ActivityLevel.Info, ActivityCategory.Playback, m[1])
            }
            (e.type == "VideoPlaybackStopped" || e.type == "AudioPlaybackStopped") && Finished.find(e.name) != null -> {
                val m = Finished.find(e.name)!!.groupValues
                val item = itemLabel(e, m[2], items)
                val title = if (e.type == "AudioPlaybackStopped") t("{name} stopped listening to {item}", "name" to m[1], "item" to item) else t("{name} stopped watching {item}", "name" to m[1], "item" to item)
                rows += row(title, m[3], ActivityLevel.Info, ActivityCategory.Playback, m[1])
            }
            e.type == "UserDownloadingContent" && Downloading.find(e.name) != null -> {
                val m = Downloading.find(e.name)!!.groupValues
                val app = AppDevice.find(e.shortOverview.orEmpty())?.groupValues
                rows += row(t("{name} downloaded {item}", "name" to m[1], "item" to itemLabel(e, m[2], items)), if (app != null) "${app[2]} · ${app[1]}" else e.shortOverview.orEmpty(), ActivityLevel.Info, ActivityCategory.Playback, m[1])
            }
            Created.find(e.name) != null -> {
                val name = Created.group(e.name)!!
                rows += row(t("{name} was added", "name" to name), t("New profile"), ActivityLevel.Ok, ActivityCategory.People, name)
            }
            Deleted.find(e.name) != null -> {
                val name = Deleted.group(e.name)!!
                rows += row(t("{name} was deleted", "name" to name), t("Profile and its watch history removed"), ActivityLevel.Warn, ActivityCategory.People, name)
            }
            Password.find(e.name) != null -> {
                val name = Password.group(e.name)!!
                rows += row(t("{name}’s password was changed", "name" to name), e.shortOverview.orEmpty(), ActivityLevel.Info, ActivityCategory.People, name)
            }
            else -> {
                val version = VersionLine.group(e.shortOverview)
                val system: Row? = when {
                    e.type == "PluginUpdateInstalled" && Updated.find(e.name) != null -> {
                        val p = Updated.group(e.name)!!
                        row(if (version != null) t("{plugin} plugin updated to {version}", "plugin" to p, "version" to version) else t("{plugin} plugin updated", "plugin" to p), t("Takes effect after the next restart"), ActivityLevel.Info, ActivityCategory.System)
                    }
                    e.type == "PluginInstalled" && Installed.find(e.name) != null ->
                        row(t("{plugin} plugin installed", "plugin" to Installed.group(e.name)), joinDot(version?.let { t("Version {version}", "version" to it) }, t("Takes effect after the next restart")), ActivityLevel.Ok, ActivityCategory.System)
                    e.type == "PluginUninstalled" && Uninstalled.find(e.name) != null ->
                        row(t("{plugin} plugin removed", "plugin" to Uninstalled.group(e.name)), t("Takes effect after the next restart"), ActivityLevel.Info, ActivityCategory.System)
                    e.type == "InstallationFailed" && InstallFailed.find(e.name) != null ->
                        row(t("{plugin} couldn’t be installed", "plugin" to InstallFailed.group(e.name)), joinDot(version?.let { t("Version {version}", "version" to it) }, e.overview), ActivityLevel.Error, ActivityCategory.System)
                    e.type == "TaskFailed" && TaskFailedLine.find(e.name) != null -> {
                        val ran = RunningTime.group(e.shortOverview)
                        row(t("{task} failed", "task" to TaskFailedLine.group(e.name)), if (ran != null) t("Ran for {time}", "time" to ran) else e.shortOverview.orEmpty(), ActivityLevel.Error, ActivityCategory.System)
                    }
                    e.type == "SubtitleDownloadFailure" || e.type == "LyricDownloadFailure" ->
                        row(e.name, e.shortOverview.orEmpty(), ActivityLevel.Warn, ActivityCategory.System)
                    else -> null
                }
                rows += system ?: run {
                    val sev = when (e.severity) {
                        "Error", "Critical" -> ActivityLevel.Error
                        "Warning" -> ActivityLevel.Warn
                        else -> ActivityLevel.Info
                    }
                    val category = when {
                        e.type in PlayTypes -> ActivityCategory.Playback
                        Regex("^(Authentication|Session)").containsMatchIn(e.type) -> ActivityCategory.SignIn
                        e.type.startsWith("User") -> ActivityCategory.People
                        else -> ActivityCategory.System
                    }
                    row(e.name, e.shortOverview ?: e.overview.orEmpty(), sev, category, ip = ip)
                }
            }
        }
    }
    return collapse(rows, clock)
}

/** Repeats close together ("Sam reconnected" five times in ten minutes) read as one row with a count. */
private fun collapse(rows: List<Row>, clock: Clock): List<Row> {
    data class Acc(var row: Row, var times: Int = 1, var last: Instant? = null)
    val out = mutableListOf<Acc>()
    for (r in rows) {
        val prev = out.lastOrNull()
        if (prev != null && r.action == null && prev.row.action == null && prev.row.title == r.title && prev.row.detail == r.detail && prev.row.category == r.category &&
            (prev.last ?: prev.row.date).toEpochMilli() - r.date.toEpochMilli() <= 30 * 60_000 && day(prev.row.date, clock) == day(r.date, clock)
        ) {
            prev.times++
            prev.last = r.date
            continue
        }
        out += Acc(r)
    }
    return out.map { a ->
        if (a.times == 1) a.row
        else {
            val last = a.last!!
            val count = if (hhmm(last, clock) != hhmm(a.row.date, clock)) t("{n} times since {time}", "n" to a.times, "time" to hhmm(last, clock)) else t("{n} times", "n" to a.times)
            a.row.copy(detail = joinDot(a.row.detail, count))
        }
    }
}

private fun day(d: Instant, clock: Clock): LocalDate = d.atZone(clock.zone).toLocalDate()

private fun matches(r: Row, type: TypeFilter, level: LevelFilter, person: AdminUser?): Boolean {
    if (type != TypeFilter.All && r.category.name != type.name) return false
    if (level == LevelFilter.Error && r.level != ActivityLevel.Error) return false
    if (level == LevelFilter.Warn && r.level != ActivityLevel.Error && r.level != ActivityLevel.Warn) return false
    if (person != null && normId(r.userId) != normId(person.id) && (r.userName ?: "").lowercase() != person.name.lowercase()) return false
    return true
}

/** "Today · Mon 19 Oct", "Yesterday · Sun 18 Oct", "Fri 16 Oct". */
internal fun dayLabel(d: Instant, clock: Clock): String {
    val date = day(d, clock)
    val today = LocalDate.now(clock)
    val wd = weekdayDate(date) + if (date.year != today.year) " ${date.year}" else ""
    return when (java.time.temporal.ChronoUnit.DAYS.between(date, today)) {
        0L -> t("Today · {date}", "date" to wd)
        1L -> t("Yesterday · {date}", "date" to wd)
        else -> wd
    }
}

private const val Page = 100

class ActivityViewModel(
    private val session: UserSession,
    private val clock: Clock = Clock.systemDefaultZone(),
) : ViewModel(), ActivityActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(ActivityState(lede = t("Sign-ins, playback, changes and server events."), personLabel = t("Everyone")))
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private var entries: List<ActivityEntry>? = null
    private var total = 0
    private var today: List<ActivityEntry>? = null
    private var items = mapOf<String, ItemBrief>()
    private var users = listOf<AdminUser>()
    private var rows = listOf<Row>()
    private var fresh = setOf<Long>()

    fun load() {
        viewModelScope.launch {
            runCatching { http.getObject("/System/Configuration") }.onSuccess { cfg ->
                val days = (cfg["ActivityLogRetentionDays"] as? JsonPrimitive)?.intOrNull
                _state.update {
                    it.copy(
                        lede = if (days != null && days > 0) t("Sign-ins, playback, changes and server events. Kept for {days}.", "days" to tn(days, "{n} day", "{n} days"))
                        else t("Sign-ins, playback, changes and server events. Kept until you clear it."),
                    )
                }
            }.onFailure(::rethrowCancel)
        }
        viewModelScope.launch {
            runCatching { http.get<List<AdminUser>>("/Users") }.onSuccess { users = it; rebuild() }.onFailure(::rethrowCancel)
        }
        loadFirst()
    }

    private fun loadFirst() {
        _state.update { it.copy(error = null) }
        viewModelScope.launch {
            runCatching { http.get<ItemsResult<ActivityEntry>>("/System/ActivityLog/Entries", mapOf("startIndex" to 0, "limit" to Page)) }
                .onSuccess { r -> entries = r.items; total = r.totalRecordCount; rebuild(); fetchItems(r.items) }
                .onFailure { rethrowCancel(it); if (entries == null) _state.update { s -> s.copy(error = t("Couldn’t load the activity log") + ". " + errorText(it)) } }
        }
        loadToday()
    }

    private fun loadToday() {
        viewModelScope.launch {
            val start = LocalDate.now(clock).atStartOfDay(clock.zone).toInstant()
            runCatching { http.get<ItemsResult<ActivityEntry>>("/System/ActivityLog/Entries", mapOf("minDate" to start.toString(), "limit" to 2000)) }
                .onSuccess { today = it.items; publish() }
                .onFailure { rethrowCancel(it); if (today == null) { today = emptyList(); publish() } }
        }
    }

    /** Item names for playback rows ("Glass Harbor S1 E6"), fetched once per batch of entries. */
    private fun fetchItems(list: List<ActivityEntry>) {
        val ids = list.filter { !it.itemId.isNullOrEmpty() && it.type in PlayTypes }.map { normId(it.itemId) }.distinct().filter { it !in items }
        if (ids.isEmpty()) return
        for (chunk in ids.chunked(60)) {
            viewModelScope.launch {
                runCatching {
                    http.get<ItemsResult<ItemBrief>>(
                        "/Items",
                        mapOf("ids" to chunk.joinToString(","), "userId" to session.userId.toString(), "enableImages" to false, "enableUserData" to false, "fields" to "SeriesName"),
                    )
                }.onSuccess { r -> items = items + r.items.associateBy { normId(it.id) }; rebuild() }.onFailure(::rethrowCancel)
            }
        }
    }

    /** Polls for new entries every 10 s while Live is on, until cancelled. */
    suspend fun live() {
        while (true) {
            delay(10_000)
            if (_state.value.live) refresh()
        }
    }

    /** New entries since the newest one shown: prepended and briefly highlighted. */
    private fun refresh() {
        val cur = entries ?: return
        viewModelScope.launch {
            runCatching { http.get<ItemsResult<ActivityEntry>>("/System/ActivityLog/Entries", mapOf("startIndex" to 0, "limit" to 50)) }.onSuccess { r ->
                val known = cur.map { it.id }.toSet()
                val added = r.items.filter { it.id !in known }
                if (added.isEmpty()) return@onSuccess
                entries = added + (entries ?: emptyList())
                total = r.totalRecordCount
                fresh = added.map { it.id }.toSet()
                rebuild()
                fetchItems(added)
                loadToday()
                delay(2_500)
                fresh = emptySet()
                publish()
            }.onFailure(::rethrowCancel)
        }
    }

    private fun rebuild() {
        rows = entries?.let { buildRows(it, items, users, clock) } ?: emptyList()
        publish()
    }

    private fun person(): AdminUser? = users.firstOrNull { it.id == _state.value.person }

    private fun publish() {
        val list = entries
        _state.update { st ->
            val person = users.firstOrNull { it.id == st.person }
            val shown = rows.filter { matches(it, st.type, st.level, person) }
            val days = mutableListOf<ActivityDay>()
            for (r in shown) {
                val key = day(r.date, clock).toString()
                val row = present(r)
                val last = days.lastOrNull()
                if (last != null && last.key == key) days[days.lastIndex] = last.copy(rows = last.rows + row)
                else days += ActivityDay(key, dayLabel(r.date, clock), listOf(row))
            }
            val empty = list != null && days.isEmpty()
            st.copy(
                tiles = today?.let(::tiles),
                people = listOf(Choice("all", t("Everyone"))) + users.map { Choice(it.id, it.name) },
                personLabel = person?.name ?: t("Everyone"),
                days = if (list == null) null else days,
                emptyTitle = if (!empty) null else if (rows.isNotEmpty()) t("Nothing matches") else t("Nothing logged yet"),
                emptyText = if (!empty) null else when {
                    rows.isEmpty() -> t("Sign-ins, playback and server events show up here as they happen.")
                    list!!.size < total -> t("Nothing in the most recent activity matches these filters. Load earlier activity, or change the filters.")
                    else -> t("Try another type, person or level.")
                },
                canLoadMore = list != null && list.size < total,
            )
        }
    }

    private fun present(r: Row): ActivityRow = ActivityRow(
        key = r.key,
        time = hhmm(r.date, clock),
        title = r.title,
        tag = t(r.category.tag),
        detail = r.detail,
        level = r.level,
        action = when (r.action) {
            ActivityActionKind.Block -> r.ip?.let(::cleanIp)?.let { ip ->
                ActivityAction(
                    ActivityActionKind.Block, t("Block address"), t("Block {ip}?", "ip" to ip),
                    t("Connections from {ip} are refused from now on. It only affects connections from outside your home network, and you can undo it in Networking under Remote access.", "ip" to ip),
                    danger = true,
                )
            }
            ActivityActionKind.Enable -> {
                val name = r.userName?.takeIf { it.isNotEmpty() } ?: t("This person")
                ActivityAction(
                    ActivityActionKind.Enable, t("Re-enable"), t("Let {name} sign in again?", "name" to name),
                    t("{name} was locked out after too many wrong passwords. Re-enabling lets them try again straight away.", "name" to name),
                    danger = false,
                )
            }
            null -> null
        },
        fresh = r.entry.id in fresh,
    )

    private fun tiles(today: List<ActivityEntry>): List<ActivityTile> {
        val plays = today.count { it.type == "VideoPlayback" || it.type == "AudioPlayback" }
        val signins = today.count { it.type == "AuthenticationSucceeded" }
        val failed = today.filter { it.type == "AuthenticationFailed" }
        val addresses = failed.map { ipOf(it) ?: "?" }.toSet().size
        val errors = today.count { (it.severity == "Error" || it.severity == "Critical") && it.type != "AuthenticationFailed" && it.type != "UserLockedOut" }
        val st = _state.value
        return listOf(
            ActivityTile(formatNumber(plays), t("Plays today")),
            ActivityTile(formatNumber(signins), t("Sign-ins today")),
            if (failed.isNotEmpty()) ActivityTile(formatNumber(failed.size), tn(addresses, "Failed sign-ins, {n} address", "Failed sign-ins, {n} addresses"), alert = true, on = st.type == TypeFilter.SignIn && st.level == LevelFilter.Error)
            else ActivityTile("0", t("Failed sign-ins")),
            ActivityTile(formatNumber(errors), t("Errors today")),
        )
    }

    /* ── Filters ── */

    override fun setPerson(id: String) {
        _state.update { it.copy(person = id) }
        publish()
    }

    override fun setLevel(level: LevelFilter) {
        _state.update { it.copy(level = level) }
        publish()
    }

    override fun setType(type: TypeFilter) {
        _state.update { it.copy(type = type) }
        publish()
    }

    override fun toggleFailed() {
        _state.update {
            if (it.type == TypeFilter.SignIn && it.level == LevelFilter.Error) it.copy(type = TypeFilter.All, level = LevelFilter.All)
            else it.copy(type = TypeFilter.SignIn, level = LevelFilter.Error)
        }
        publish()
    }

    override fun setLive(on: Boolean) {
        _state.update { it.copy(live = on) }
        if (on) refresh()
    }

    override fun retry() = loadFirst()

    override fun messageShown() = _state.update { it.copy(message = null) }

    private fun say(text: String) = _state.update { it.copy(message = text) }

    /** Keeps fetching pages until there's something new to show for the current filters (or the log ends). */
    override fun loadMore() {
        val start = entries ?: return
        if (_state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            var list = start
            var tot = total
            val st = _state.value
            val person = person()
            val before = buildRows(list, items, users, clock).count { matches(it, st.type, st.level, person) }
            try {
                var n = 0
                while (n < 6 && list.size < tot) {
                    val r = http.get<ItemsResult<ActivityEntry>>("/System/ActivityLog/Entries", mapOf("startIndex" to list.size, "limit" to Page))
                    val known = list.map { it.id }.toSet()
                    list = list + r.items.filter { it.id !in known }
                    tot = r.totalRecordCount
                    fetchItems(r.items)
                    if (r.items.isEmpty()) {
                        tot = list.size
                        break
                    }
                    if (buildRows(list, items, users, clock).count { matches(it, st.type, st.level, person) } - before >= 20) break
                    n++
                }
            } catch (e: Throwable) {
                rethrowCancel(e)
                say(t("Couldn’t load earlier activity."))
            }
            entries = list
            total = tot
            _state.update { it.copy(loadingMore = false) }
            rebuild()
        }
    }

    /* ── Row actions ── */

    override fun act(key: String) {
        val r = rows.firstOrNull { it.key == key } ?: return
        when (r.action) {
            ActivityActionKind.Block -> r.ip?.let { block(cleanIp(it)) }
            ActivityActionKind.Enable -> r.userId?.let { reenable(it, r.userName?.takeIf { n -> n.isNotEmpty() } ?: t("This person")) }
            null -> Unit
        }
    }

    private fun block(ip: String) {
        if (ip.isEmpty()) return
        viewModelScope.launch {
            try {
                val net = http.getObject("/System/Configuration/network")
                val list = (net["RemoteIPFilter"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                val black = (net["IsRemoteIPFilterBlacklist"] as? JsonPrimitive)?.booleanOrNull == true
                val next: JsonObject
                if (black || list.isEmpty()) {
                    if (black && ip in list) return@launch say(t("{ip} is already blocked", "ip" to ip))
                    next = JsonObject(net + mapOf("IsRemoteIPFilterBlacklist" to JsonPrimitive(true), "RemoteIPFilter" to JsonArray(((if (black) list else emptyList()) + ip).map(::JsonPrimitive))))
                } else {
                    // An allow list is in use: blocking means taking the address off it.
                    if (ip !in list) return@launch say(t("Only listed addresses can connect, so {ip} is already refused", "ip" to ip))
                    // An empty allow list lets every address in, so the last entry can't be taken off here.
                    if (list.size == 1) return@launch say(t("{ip} is the only address allowed to connect. Removing it would let every address in; change the list in Networking instead.", "ip" to ip))
                    next = JsonObject(net + ("RemoteIPFilter" to JsonArray((list - ip).map(::JsonPrimitive))))
                }
                http.send("/System/Configuration/network", next)
                say(t("{ip} is blocked", "ip" to ip))
            } catch (e: Throwable) {
                rethrowCancel(e)
                say(t("Couldn’t block that address."))
            }
        }
    }

    private fun reenable(userId: String, name: String) {
        viewModelScope.launch {
            try {
                val user = http.getObject("/Users/$userId")
                val policy = user["Policy"] as? JsonObject ?: JsonObject(emptyMap())
                if ((policy["IsDisabled"] as? JsonPrimitive)?.booleanOrNull != true) return@launch say(t("{name} can already sign in", "name" to name))
                http.send("/Users/$userId/Policy", JsonObject(policy + ("IsDisabled" to JsonPrimitive(false))))
                say(t("{name} can sign in again", "name" to name))
            } catch (e: Throwable) {
                rethrowCancel(e)
                say(t("Couldn’t re-enable that profile."))
            }
        }
    }
}
