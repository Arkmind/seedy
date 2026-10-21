package io.github.arkmind.seedy.core.admin

import io.github.arkmind.seedy.core.i18n.t
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToLong

/*
 * How the Scheduled tasks board names and groups Jellyfin's tasks (web/src/screens/server/tasks.ts). Jellyfin
 * localises task names on the server, so known tasks are matched by key; anything else keeps the server's wording.
 */

private class TaskCopy(val name: String, val desc: String)

private val Copy = mapOf(
    "RefreshLibrary" to TaskCopy("Scan media library", "Finds new, changed and removed files"),
    "RefreshTrickplayImages" to TaskCopy("Generate trickplay images", "Seek previews for new videos"),
    "TaskExtractMediaSegments" to TaskCopy("Media segment scan", "Intro, recap and credits markers"),
    "RefreshChapterImages" to TaskCopy("Extract chapter images", "Thumbnails for chapter lists"),
    "AudioNormalization" to TaskCopy("Audio normalization", "Measures loudness so tracks play at an even volume"),
    "DownloadLyrics" to TaskCopy("Download missing lyrics", "Finds lyrics for songs that have none"),
    "DownloadSubtitles" to TaskCopy("Download missing subtitles", "Searches for subtitles in each library’s languages"),
    "KeyframeExtraction" to TaskCopy("Extract keyframes", "Makes seeking in transcoded streams more precise"),
    "RefreshPeople" to TaskCopy("Refresh people", "Updates cast and crew details"),
    "TmdbRefreshUpcomingEpisodes" to TaskCopy("Find upcoming episodes", "Adds announced episodes from TheMovieDb"),
    "DeleteTranscodeFiles" to TaskCopy("Clean transcode folder", "Deletes leftover transcoding files"),
    "DeleteCacheFiles" to TaskCopy("Clean cache folder", "Deletes cache files older than a month"),
    "CleanLogFiles" to TaskCopy("Clean log folder", "Deletes logs past their retention"),
    "OptimizeDatabaseTask" to TaskCopy("Optimize database", "Compacts the library database"),
    "CleanActivityLog" to TaskCopy("Clean activity log", "Deletes activity entries past their retention"),
    "MoveTrickplayImages" to TaskCopy("Move trickplay images", "Applies each library’s trickplay location"),
    "CleanupUserDataTask" to TaskCopy("Clean up user data", "Removes watch data for media that’s gone"),
    "PluginUpdates" to TaskCopy("Check for plugin updates", "Looks for new versions in your repositories"),
    "RefreshGuide" to TaskCopy("Refresh guide", "Downloads programme data for Live TV"),
    "RefreshInternetChannels" to TaskCopy("Refresh channels", "Updates what channel plugins offer"),
)

private val LibraryTasks = listOf("RefreshLibrary", "RefreshTrickplayImages", "TaskExtractMediaSegments", "RefreshChapterImages", "AudioNormalization", "DownloadLyrics", "DownloadSubtitles", "KeyframeExtraction", "RefreshPeople", "TmdbRefreshUpcomingEpisodes")
private val MaintenanceTasks = listOf("DeleteTranscodeFiles", "DeleteCacheFiles", "CleanLogFiles", "OptimizeDatabaseTask", "CleanActivityLog", "MoveTrickplayImages", "CleanupUserDataTask")
private val TaskOrder = LibraryTasks.take(4) + MaintenanceTasks.take(4) + "PluginUpdates"

fun taskName(task: TaskInfo): String = Copy[task.key]?.let { t(it.name) } ?: sentenceCase(task.name)
fun taskDesc(task: TaskInfo): String = Copy[task.key]?.let { t(it.desc) } ?: task.description.removeSuffix(".")

/** The board's groups, in English (show them with t()). */
val TaskGroups = listOf("Library", "Maintenance", "Live TV", "Channels", "Plugins")

fun taskGroup(task: TaskInfo): String = when {
    task.key in LibraryTasks -> "Library"
    task.key in MaintenanceTasks -> "Maintenance"
    task.key == "RefreshGuide" -> "Live TV"
    task.key == "RefreshInternetChannels" -> "Channels"
    task.key == "PluginUpdates" -> "Plugins"
    task.category == "Library" || task.category == "Maintenance" || task.category == "Live TV" -> task.category
    else -> "Plugins"
}

val TaskComparator = Comparator<TaskInfo> { a, b ->
    val ia = TaskOrder.indexOf(a.key)
    val ib = TaskOrder.indexOf(b.key)
    if (ia != -1 || ib != -1) (if (ia == -1) 99 else ia) - (if (ib == -1) 99 else ib)
    else taskName(a).compareTo(taskName(b), ignoreCase = true)
}

/* ── Triggers ── */

private const val TicksPerMinute = 600_000_000L
fun minutesOf(ticks: Long?): Long = ((ticks ?: 0L) / TicksPerMinute.toDouble()).roundToLong()
fun ticksOfMinutes(m: Long): Long = m * TicksPerMinute

/** "03:00". */
fun timeOfDay(ticks: Long?): String {
    val m = minutesOf(ticks)
    return "%02d:%02d".format((m / 60) % 24, m % 60)
}

fun intervalText(ticks: Long?): String {
    val m = minutesOf(ticks)
    if (m <= 0) return t("Every so often")
    if (m < 60) return t("Every {n} minutes", "n" to m)
    if (m % 60 == 0L) {
        val h = m / 60
        if (h == 1L) return t("Every hour")
        if (h == 168L) return t("Every week")
        if (h % 24 == 0L && h > 24) return t("Every {n} days", "n" to h / 24)
        return t("Every {n} hours", "n" to h)
    }
    return t("Every {h} h {m} min", "h" to m / 60, "m" to m % 60)
}

/** "Daily at 03:00", "Sundays at 04:00", "Every 12 hours", "On startup". */
fun triggerText(tr: TaskTrigger): String = when (tr.type) {
    "DailyTrigger" -> t("Daily at {time}", "time" to timeOfDay(tr.timeOfDayTicks))
    "WeeklyTrigger" -> weeklyText(tr.dayOfWeek ?: "Sunday", timeOfDay(tr.timeOfDayTicks))
    "IntervalTrigger" -> intervalText(tr.intervalTicks)
    "StartupTrigger" -> t("On startup")
    else -> t("Custom")
}

/** "Sundays at 04:00" (the phone board's way of saying a weekly trigger). */
fun weeklyText(day: String, time: String): String = when (day) {
    "Monday" -> t("Mondays at {time}", "time" to time)
    "Tuesday" -> t("Tuesdays at {time}", "time" to time)
    "Wednesday" -> t("Wednesdays at {time}", "time" to time)
    "Thursday" -> t("Thursdays at {time}", "time" to time)
    "Friday" -> t("Fridays at {time}", "time" to time)
    "Saturday" -> t("Saturdays at {time}", "time" to time)
    else -> t("Sundays at {time}", "time" to time)
}

/** "Every 12 hours", "Daily at 03:00", "Every 24 hours and on startup", "After each scan", "Not scheduled". */
fun scheduleText(task: TaskInfo): String {
    val triggers = task.triggers
    if (triggers.isEmpty()) return if (task.key == "TaskExtractMediaSegments") t("After each scan") else t("Not scheduled")
    val timed = triggers.filter { it.type != "StartupTrigger" }.map(::triggerText)
    val startup = triggers.any { it.type == "StartupTrigger" }
    if (timed.isEmpty()) return t("On startup")
    val first = if (timed.size > 1) t("{schedule} · {n} more", "schedule" to timed[0], "n" to timed.size - 1) else timed[0]
    return if (startup) t("{schedule} and on startup", "schedule" to first) else first
}

/**
 * When a task runs next, where that can be known. Daily and weekly triggers run on the server's clock:
 * [serverOffsetMinutes] is its UTC offset (null: assume this device's).
 */
fun nextRun(task: TaskInfo, clock: Clock, serverOffsetMinutes: Int? = null): Instant? {
    val now = clock.instant()
    val zone = serverOffsetMinutes?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: clock.zone
    var best: Instant? = null
    fun consider(d: Instant?) {
        if (d != null && d > now && (best == null || d < best)) best = d
    }
    val serverNow = ZonedDateTime.ofInstant(now, zone)
    for (tr in task.triggers) {
        when (tr.type) {
            "DailyTrigger", "WeeklyTrigger" -> {
                val m = minutesOf(tr.timeOfDayTicks)
                var d = serverNow.withHour(((m / 60) % 24).toInt()).withMinute((m % 60).toInt()).withSecond(0).withNano(0)
                if (tr.type == "DailyTrigger") {
                    if (!d.isAfter(serverNow)) d = d.plusDays(1)
                } else {
                    val want = WeekdaysEn.indexOf(tr.dayOfWeek ?: "Sunday").coerceAtLeast(0)
                    var add = (want - d.dayOfWeek.value % 7 + 7) % 7
                    if (add == 0 && !d.isAfter(serverNow)) add = 7
                    d = d.plusDays(add.toLong())
                }
                consider(d.toInstant())
            }
            "IntervalTrigger" -> realDate(task.lastExecutionResult?.endTimeUtc)?.let { consider(it.plus(Duration.ofMinutes(minutesOf(tr.intervalTicks)))) }
        }
    }
    return best
}

/** "at 03:00" within a day, else "Sunday at 04:00". */
fun nextText(d: Instant, clock: Clock): String =
    if (Duration.between(clock.instant(), d) < Duration.ofHours(24)) t("at {time}", "time" to hhmm(d, clock)) else dayAt(d, clock)

/** What a task did last time, as the phone board says it: "Today, 22 min", "Yesterday, 3 s", "Failed on Sunday". */
data class LastRun(val text: String, val failed: Boolean = false, val running: Boolean = false, val error: String? = null)

fun lastRun(task: TaskInfo, clock: Clock, startedAt: Instant? = null): LastRun {
    if (task.state == "Cancelling") return LastRun(t("Stopping…"), running = true)
    if (task.state == "Running") return LastRun(if (startedAt != null) t("Running since {time}", "time" to hhmm(startedAt, clock)) else t("Running now"), running = true)
    val r = task.lastExecutionResult
    val start = realDate(r?.startTimeUtc)
    val end = realDate(r?.endTimeUtc) ?: start
    if (r == null || start == null || end == null) return LastRun(t("Never run"))
    val took = runLength(Duration.between(start, end).toMillis())
    return when (r.status) {
        "Failed" -> LastRun(t("Failed {when}", "when" to onDay(end, clock)), failed = true, error = r.errorMessage)
        "Cancelled" -> LastRun(t("Stopped {when} · after {time}", "when" to onDay(start, clock), "time" to took))
        "Aborted" -> LastRun(t("Interrupted {when}", "when" to onDay(start, clock)))
        else -> LastRun(t("{day}, {time}", "day" to dayWord(start, clock), "time" to took))
    }
}

/** "Today", "Yesterday", "Sunday", "12 Oct". */
fun dayWord(d: Instant, clock: Clock): String {
    val days = dayDiff(d, clock)
    return when {
        days == 0L -> t("Today")
        days == 1L -> t("Yesterday")
        days in 2..6 -> weekdayLabel(WeekdaysEn[d.atZone(clock.zone).dayOfWeek.value % 7])
        else -> shortDate(d, clock)
    }
}

/** Jellyfin's daily log for a date: log_20261019.log. */
fun logNameFor(d: Instant): String = d.atZone(ZoneOffset.UTC).let { "log_%04d%02d%02d.log".format(it.year, it.monthValue, it.dayOfMonth) }

/** "Scan Media Library" → "Scan media library"; names like TheMovieDb keep their capitals. */
fun sentenceCase(name: String): String =
    name.split(' ').mapIndexed { i, w -> if (i > 0 && Regex("^[A-Z][a-z]+$").matches(w)) w.lowercase() else w }.joinToString(" ")

/**
 * When the server started, from the two startup tasks that run together at start. After their 24 h interval runs
 * they drift apart, and the start time can't be known from the API any more.
 */
fun serverStartFromTasks(tasks: List<TaskInfo>): Instant? {
    val starts = tasks.filter { t -> t.triggers.any { it.type == "StartupTrigger" } }.mapNotNull { realDate(it.lastExecutionResult?.startTimeUtc) }
    if (starts.size < 2) return null
    val min = starts.min()
    val max = starts.max()
    return if (Duration.between(min, max).toMillis() < 25) min else null
}
