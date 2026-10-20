package io.github.arkmind.seedy.core.format

import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/* Item labels, as the web app writes them (web/src/lib/format.ts). */

const val TICKS_PER_SECOND = 10_000_000L

fun ticksToSeconds(ticks: Long?): Double = (ticks ?: 0L) / TICKS_PER_SECOND.toDouble()

/** "52 min", "1 h 05 min". Anything that runs at all is at least "1 min"; "0 min" reads as broken. */
fun duration(seconds: Double): String {
    val totalMin = if (seconds > 0) max(1, (seconds / 60).roundToInt()) else 0
    val h = totalMin / 60
    val m = totalMin % 60
    return if (h == 0) "$m min" else "$h h ${m.toString().padStart(2, '0')} min"
}

val BaseItemDto.runtime: String get() = runTimeTicks?.let { duration(ticksToSeconds(it)) }.orEmpty()

val BaseItemDto.remainingSeconds: Double
    get() = max(0.0, ticksToSeconds(runTimeTicks) - ticksToSeconds(userData?.playbackPositionTicks))

/** "34 min left". */
fun timeLeft(seconds: Double): String = t("{time} left", "time" to duration(seconds))

/** How far into the title this person is, 0–1. */
val BaseItemDto.progress: Float
    get() {
        userData?.playedPercentage?.takeIf { it > 0 }?.let { return min(1.0, it / 100).toFloat() }
        val total = runTimeTicks ?: 0L
        val pos = userData?.playbackPositionTicks ?: 0L
        return if (total > 0) min(1f, pos.toFloat() / total) else 0f
    }

/** "S3 E2", "Special 1", "E4". */
val BaseItemDto.episodeTag: String
    get() {
        val s = parentIndexNumber
        val e = indexNumber ?: return ""
        val end = indexNumberEnd?.let { "–$it" }.orEmpty()
        return when (s) {
            null -> "E$e"
            0 -> t("Special {n}", "n" to "$e$end")
            else -> t("S{s} E{e}", "s" to s, "e" to "$e$end")
        }
    }

/** "S3 · E2", the boards' badge and button form. */
val BaseItemDto.episodeShort: String
    get() {
        val e = indexNumber ?: return ""
        val s = parentIndexNumber ?: return "E$e"
        return if (s == 0) t("Special {n}", "n" to e) else "S$s · E$e"
    }

/** "Season 3 · Episode 2". */
val BaseItemDto.seasonEpisodeLong: String
    get() = if (parentIndexNumber == 0) t("Special · Episode {e}", "e" to (indexNumber ?: ""))
    else t("Season {s} · Episode {e}", "s" to (parentIndexNumber ?: ""), "e" to (indexNumber ?: ""))

val BaseItemDto.year: Int? get() = productionYear ?: premiereDate?.year

/** "2024–2026" for a running or finished series, the year for anything else. */
val BaseItemDto.yearRange: String
    get() {
        val start = year ?: return ""
        if (type != BaseItemKind.SERIES) return start.toString()
        val end = endDate?.year
        if (status == "Continuing") {
            val now = LocalDate.now().year
            return if (now > start) "$start–$now" else start.toString()
        }
        return if (end != null && end != start) "$start–$end" else start.toString()
    }

private val ratingCountry = Regex(
    "^(GB|UK|IE|DE|AT|CH|FR|BE|NL|LU|ES|PT|IT|SE|NO|DK|FI|IS|PL|CZ|HU|RO|BG|GR|TR|RU|UA|AU|NZ|BR|MX|AR|CL|CO|JP|KR|CN|HK|TW|IN|SG|ZA|CA|US)-",
)

/** "TV-MA", without a country prefix ("FR-12" → "12"), as on the web. */
val BaseItemDto.rating: String get() = officialRating?.trim()?.replace(ratingCountry, "").orEmpty()

/** Joins the parts that aren't blank: join("2026", "", "Drama") → "2026 · Drama". */
fun join(vararg parts: Any?, separator: String = " · "): String =
    parts.mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }.joinToString(separator)

/** "Resume S3 · E2", "Play", "Resume" — the hero's and details' main button. */
fun playLabel(play: BaseItemDto): String {
    val verb = if ((play.userData?.playbackPositionTicks ?: 0L) > 0L) t("Resume") else t("Play")
    if (play.type != BaseItemKind.EPISODE || play.indexNumber == null) return verb
    return "$verb ${play.episodeShort}"
}

/** The hero's line under the title: "2026 · TV-MA · Drama · Thriller · Mystery". */
val BaseItemDto.heroMeta: String
    get() = join(year, rating, *genres.orEmpty().take(3).toTypedArray())

/** A card's title for anything: an episode shows its series' name. */
val BaseItemDto.cardTitle: String get() = (if (type == BaseItemKind.EPISODE) seriesName else null) ?: name.orEmpty()

/** "3 seasons": a series' ChildCount is its season count. */
val BaseItemDto.seasonsLabel: String
    get() = childCount?.takeIf { it > 0 }?.let { tn(it, "{n} season", "{n} seasons") }.orEmpty()

/** "New": an unwatched episode added to the server in the last 7 days. */
val BaseItemDto.isNewEpisode: Boolean
    get() {
        if (userData?.played == true) return false
        val added = dateCreated ?: return false
        return daysFromToday(added.toLocalDate()) >= -7
    }

/** Started and not finished. */
val BaseItemDto.inProgress: Boolean get() = progress > 0f && userData?.played != true

/** A library card's line under the title, as on the web: the year for films, the years for shows. */
val BaseItemDto.libraryMeta: String
    get() = when (type) {
        BaseItemKind.SERIES -> yearRange
        BaseItemKind.EPISODE -> join(seriesName, episodeTag)
        BaseItemKind.BOX_SET -> childCount?.takeIf { it > 0 }?.let { tn(it, "{n} title", "{n} titles") }.orEmpty()
        BaseItemKind.MUSIC_ALBUM -> join(albumArtist, year)
        else -> year?.toString().orEmpty()
    }

/** The TV grid's longer line: "2026 · 1 h 54 min", "2025 · 1 h 12 min left", "2024–2026 · 3 seasons". */
val BaseItemDto.libraryMetaLong: String
    get() = when (type) {
        BaseItemKind.SERIES -> join(yearRange, seasonsLabel)
        BaseItemKind.MOVIE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO -> join(year, if (inProgress) timeLeft(remainingSeconds) else runtime)
        else -> libraryMeta
    }

/** The score shown with a star ("8.4"), or null. */
val BaseItemDto.score: String? get() = communityRating?.takeIf { it > 0f }?.let { String.format(Locale.US, "%.1f", it) }

/** An episode's number in its season: "2", "2–3". */
val BaseItemDto.episodeNumber: String
    get() = indexNumber?.let { n -> indexNumberEnd?.let { "$n–$it" } ?: "$n" }.orEmpty()

/** Jellyfin's placeholder for an episode or season it knows about but has no file for. */
val BaseItemDto.isVirtual: Boolean get() = locationType == LocationType.VIRTUAL

/** The day a title premiered (Jellyfin's premiere dates are calendar days). */
val BaseItemDto.premiereDay: LocalDate? get() = premiereDate?.toLocalDate()
