package io.github.arkmind.seedy.player

import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.hourMinute
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.runtime
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.StillWatchingRules
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * "Still watching?" instead of what would play next: [count] episodes (or [movies]) played in a row, the one that just
 * ended included, and [minutes] since anyone touched the player; [byTime] when the time asked, not the run.
 */
data class StillWatchingAsk(val count: Int, val minutes: Int, val byTime: Boolean, val movies: Boolean = false)

/** With nobody answering "Still watching?", the player closes after this long. */
const val StillWatchingStopMs = 15 * 60_000L

/**
 * Whether to ask before auto-playing what comes next (the web's rule): [rules] are Settings · Playback's, [movie] says
 * what just ended (a movie, else an episode), [run] how many of those were auto-played in a row before it, [idleMs]
 * the time since the last real input. Any ticked rule met asks; null plays on.
 */
fun stillWatchingAsk(rules: StillWatchingRules, movie: Boolean, run: Int, idleMs: Long): StillWatchingAsk? {
    val minutes = idleMs / 60_000.0
    val byCount = if (movie) rules.moviesOn && run + 1 >= rules.movies else rules.episodesOn && run + 1 >= rules.episodes
    val byTime = rules.minutesOn && rules.minutes > 0 && minutes >= rules.minutes
    if (!byCount && !byTime) return null
    return StillWatchingAsk(run + 1, minutes.roundToInt(), byTime = byTime && !byCount, movies = movie)
}

/** "Northbound · S3 E3 finished". */
fun finishedLine(item: BaseItemDto): String {
    val what = if (item.type == BaseItemKind.EPISODE) join(item.seriesName, item.episodeTag) else item.name.orEmpty()
    return t("{what} finished", "what" to what)
}

/** "S3 E4 · 47 min · Ends at 00:21", from [now]. */
fun nextLine(next: BaseItemDto, now: LocalTime): String {
    val ends = next.runTimeTicks?.takeIf { it > 0 }?.let { t("Ends at {time}", "time" to hourMinute(now.plusSeconds(ticksToSeconds(it).roundToInt().toLong()))) }
    return join(if (next.type == BaseItemKind.EPISODE) next.episodeTag else next.productionYear, next.runtime, ends)
}

/** Why Seedy paused: the TV's (and web's) sentence, or the phone's shorter one ([short]). */
fun askReason(ask: StillWatchingAsk, short: Boolean): String = when {
    ask.byTime -> {
        val time = duration(ask.minutes * 60.0)
        if (short) t("{time} without a tap, so Seedy paused before the next one.", "time" to time)
        else t("You’ve been watching for {time} without touching anything, so Seedy paused before the next one.", "time" to time)
    }
    ask.movies && ask.count == 1 ->
        if (short) t("A whole movie without a tap, so Seedy paused before the next one.")
        else t("You’ve watched a whole movie without touching anything, so Seedy paused before the next one.")
    ask.movies ->
        if (short) t("{n} movies in a row without a tap, so Seedy paused before the next one.", "n" to ask.count)
        else t("You’ve watched {n} movies in a row without touching anything, so Seedy paused before the next one.", "n" to ask.count)
    ask.count == 1 ->
        if (short) t("A whole episode without a tap, so Seedy paused before the next one.")
        else t("You’ve watched a whole episode without touching anything, so Seedy paused before the next one.")
    else ->
        if (short) t("{n} episodes in a row without a tap, so Seedy paused before the next one.", "n" to ask.count)
        else t("You’ve watched {n} episodes in a row without touching anything, so Seedy paused before the next one.", "n" to ask.count)
}

/** "14:32": what's left before the stream stops. */
fun countdown(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
