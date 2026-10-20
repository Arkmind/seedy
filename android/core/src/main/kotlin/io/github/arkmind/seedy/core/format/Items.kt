package io.github.arkmind.seedy.core.format

import io.github.arkmind.seedy.core.i18n.t
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
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

private fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val locale = java.util.Locale.forLanguageTag(code)
    val name = locale.getDisplayLanguage(io.github.arkmind.seedy.core.i18n.I18n.locale)
    return name.takeIf { it.isNotBlank() && it != code }?.replaceFirstChar { it.titlecase(io.github.arkmind.seedy.core.i18n.I18n.locale) } ?: code
}

/** An audio track as the player lists it: "English" over "5.1 · Atmos · Original" or "Audio description · 2.0". */
fun audioTrackLabel(s: org.jellyfin.sdk.model.api.MediaStream): Pair<String, String> {
    val language = languageName(s.language)
    val name = language ?: s.title ?: s.displayTitle ?: t("Track {n}", "n" to s.index)
    val extra = s.title?.takeIf { language != null && !it.contains(Regex("\\b(5\\.1|7\\.1|2\\.0|stereo|mono)\\b", RegexOption.IGNORE_CASE)) }
    val spatial = when (s.audioSpatialFormat) {
        org.jellyfin.sdk.model.api.AudioSpatialFormat.DOLBY_ATMOS -> "Atmos"
        org.jellyfin.sdk.model.api.AudioSpatialFormat.DTSX -> "DTS:X"
        else -> null
    }
    return name to join(extra, audioChannels(s.channels), spatial, if (s.isOriginal) t("Original") else null)
}

/** "5.1", "7.1", "2.0", "1.0": how the boards write a track's channels. */
private fun audioChannels(channels: Int?): String? = when (channels) {
    null, 0 -> null
    1 -> "1.0"
    2 -> "2.0"
    6 -> "5.1"
    8 -> "7.1"
    else -> t("{n} channels", "n" to channels)
}

/** A subtitle track: "English (SDH)" over "Default", "Forced" or the track's own title. */
fun subtitleTrackLabel(s: org.jellyfin.sdk.model.api.MediaStream): Pair<String, String> {
    val base = languageName(s.language) ?: s.title ?: s.displayTitle ?: t("Track {n}", "n" to s.index)
    val name = when {
        s.isHearingImpaired -> "$base (SDH)"
        s.isForced -> t("{language} (forced)", "language" to base)
        else -> base
    }
    val title = s.title?.takeIf { languageName(s.language) != null && !it.equals(base, true) }
    return name to join(title, if (s.isDefault) t("Default") else null)
}

/** "01:48", "1:02:03". */
fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/* Short dates as on the boards: three-letter English ("19 Oct", "Fri 23 Oct"), the usual French abbreviations. */
private val MonthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val DaysEn = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
private val MonthsFr = listOf("janv.", "févr.", "mars", "avr.", "mai", "juin", "juil.", "août", "sept.", "oct.", "nov.", "déc.")
private val DaysFr = listOf("lun.", "mar.", "mer.", "jeu.", "ven.", "sam.", "dim.")

private fun fr() = io.github.arkmind.seedy.core.i18n.I18n.lang == io.github.arkmind.seedy.core.i18n.Lang.Fr

/** "25 Sep" / "25 sept.". */
fun dayMonth(d: java.time.LocalDate): String = "${d.dayOfMonth} ${(if (fr()) MonthsFr else MonthsEn)[d.monthValue - 1]}"

/** "Fri 23 Oct" / "ven. 23 oct.". */
fun weekdayDayMonth(d: java.time.LocalDate): String = "${(if (fr()) DaysFr else DaysEn)[d.dayOfWeek.value - 1]} ${dayMonth(d)}"

/**
 * An episode's line in lists: "54 min · 25 Sep" once aired, "52 min · 50 min left" when started, "Airs Fri 23 Oct"
 * when it's still to come.
 */
fun episodeMeta(e: BaseItemDto): String {
    val date = e.premiereDate?.toLocalDate()
    if (date != null && date.isAfter(LocalDate.now())) return t("Airs {date}", "date" to weekdayDayMonth(date))
    val started = e.progress > 0f && e.userData?.played != true
    return join(e.runtime, if (started) timeLeft(e.remainingSeconds) else date?.let(::dayMonth))
}
