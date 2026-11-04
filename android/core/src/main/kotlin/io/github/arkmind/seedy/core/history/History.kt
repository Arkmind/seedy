package io.github.arkmind.seedy.core.history

import io.github.arkmind.seedy.core.format.TICKS_PER_SECOND
import io.github.arkmind.seedy.core.format.clock
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.max

/** One play in the person's history (GET /Seedy/Plays); [item] is gone when the title left the server. */
@Serializable
data class HistoryEntry(
    val id: String,
    val itemId: String = "",
    /** The movie, or the show for an episode: what hiding works on. */
    val titleId: String = "",
    val item: BaseItemDto? = null,
    val startedAt: String = "",
    val stoppedAt: String? = null,
    val positionTicks: Long = 0,
    val runtimeTicks: Long = 0,
    val finished: Boolean = false,
    val deviceName: String? = null,
    val client: String? = null,
    val hidden: Boolean = false,
)

@Serializable
data class HistoryPage(val items: List<HistoryEntry> = emptyList(), val total: Int = 0)

enum class HistoryFilter(val key: String, val label: String) { All("all", "All"), Movies("movies", "Movies"), Shows("shows", "Shows") }

/** Seedy's play log for the signed-in person (web/src/api/seedy.ts: getHistory and friends). */
class HistoryApi(private val api: ApiClient) {
    suspend fun page(start: Int, limit: Int, filter: HistoryFilter, search: String?, includeHidden: Boolean): HistoryPage =
        api.get<HistoryPage>(
            "/Seedy/Plays",
            queryParameters = mapOf(
                "startIndex" to start, "limit" to limit, "type" to filter.key,
                "search" to search?.takeIf { it.isNotBlank() }, "includeHidden" to if (includeHidden) true else null,
            ),
        ).content

    /** Hides a movie or a show (every play of it) from history, recommendations and the Top 10. */
    suspend fun hide(titleId: String, hidden: Boolean) {
        api.post<Unit>("/Seedy/Plays/Titles/{id}/Hidden", mapOf("id" to titleId), requestBody = buildJsonObject { put("hidden", hidden) })
    }

    suspend fun remove(id: String) {
        api.delete<Unit>("/Seedy/Plays/{id}", mapOf("id" to id))
    }

    suspend fun clear() {
        api.delete<Unit>("/Seedy/Plays")
    }
}

/** A day's or a period's plays, under its label ("Today · Mon 19 Oct", "Last week", "September"). */
data class HistoryGroup(val key: String, val label: String, val recent: Boolean, val items: List<HistoryEntry>)

/** The History board's copy (web/src/screens/browse/History.tsx). */
object HistoryFormat {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

    fun at(e: HistoryEntry, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime? =
        runCatching { Instant.parse(if (e.startedAt.endsWith("Z") || e.startedAt.contains('+')) e.startedAt else e.startedAt + "Z") }
            .getOrNull()?.atZone(zone)?.toLocalDateTime()

    fun title(e: HistoryEntry): String {
        val it = e.item ?: return t("Removed title")
        return if (it.type == BaseItemKind.EPISODE) it.seriesName ?: it.name.orEmpty() else it.name.orEmpty()
    }

    fun sub(e: HistoryEntry): String {
        val it = e.item ?: return t("No longer on the server")
        if (it.type == BaseItemKind.EPISODE) return join(it.episodeTag.takeIf { s -> s.isNotEmpty() }, it.name)
        val kind = when (it.type) {
            BaseItemKind.MOVIE -> t("Movie")
            BaseItemKind.MUSIC_VIDEO -> t("Music video")
            BaseItemKind.AUDIO -> t("Song")
            else -> t("Video")
        }
        return join(kind, it.productionYear)
    }

    /** "Finished", "Stopped at 18:15 · 34 min left". */
    fun state(e: HistoryEntry): String {
        if (e.finished) return t("Finished")
        val pos = ticksToSeconds(e.positionTicks)
        val run = ticksToSeconds(e.runtimeTicks.takeIf { it > 0 } ?: e.item?.runTimeTicks)
        val stopped = t("Stopped at {time}", "time" to clock((pos * 1000).toLong()))
        if (run <= 0) return stopped
        val left = max(0.0, run - pos)
        return stopped + " · " + if (left < 60) t("under a minute left") else timeLeft(left)
    }

    /** "21:14 · Living room TV" today and yesterday, "Sat 17 Oct · 15:20 · iPad" before. */
    fun whenOf(e: HistoryEntry, recent: Boolean, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = at(e, zone) ?: return e.deviceName ?: e.client.orEmpty()
        val device = e.deviceName?.takeIf { it.isNotEmpty() } ?: e.client
        return join(if (recent) null else weekdayDate(d.toLocalDate()), d.format(hhmm), device)
    }

    /** Where a play goes: Today and Yesterday by day, then the last two weeks, this month, then by month. */
    fun group(day: LocalDate, today: LocalDate): Pair<String, String> {
        val days = ChronoUnit.DAYS.between(day, today)
        return when {
            days <= 0 -> "today" to t("Today · {date}", "date" to weekdayDate(day))
            days == 1L -> "yesterday" to t("Yesterday · {date}", "date" to weekdayDate(day))
            days < 7 -> "week" to t("Last week")
            days < 14 -> "lastweek" to t("Two weeks ago")
            day.year == today.year && day.month == today.month -> "month" to t("Earlier this month")
            else -> {
                val pattern = if (day.year == today.year) "LLLL" else "LLLL yyyy"
                val month = day.format(DateTimeFormatter.ofPattern(pattern, I18n.locale))
                "m-${day.year}-${day.monthValue}" to month.replaceFirstChar { it.uppercase(I18n.locale) }
            }
        }
    }

    fun groups(items: List<HistoryEntry>, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<HistoryGroup> {
        val out = mutableListOf<HistoryGroup>()
        var key: String? = null
        var label = ""
        var list = mutableListOf<HistoryEntry>()
        for (e in items) {
            val (k, l) = group(at(e, zone)?.toLocalDate() ?: today, today)
            if (k != key) {
                if (key != null) out += HistoryGroup(key, label, key == "today" || key == "yesterday", list)
                key = k
                label = l
                list = mutableListOf()
            }
            list += e
        }
        if (key != null) out += HistoryGroup(key, label, key == "today" || key == "yesterday", list)
        return out
    }

    /** How far into the title the play stopped, 0–1. */
    fun fraction(e: HistoryEntry): Float {
        val run = e.runtimeTicks.takeIf { it > 0 } ?: e.item?.runTimeTicks ?: 0L
        return if (run > 0) (e.positionTicks.toFloat() / run).coerceIn(0f, 1f) else 0f
    }

    fun resumable(e: HistoryEntry): Boolean = !e.finished && e.positionTicks > 0

    /** "18:15", where the play stopped. */
    fun stoppedAt(e: HistoryEntry): String = clock(e.positionTicks / (TICKS_PER_SECOND / 1000))

    fun movieLike(e: HistoryEntry): Boolean = e.item != null && e.item.type != BaseItemKind.EPISODE
}
