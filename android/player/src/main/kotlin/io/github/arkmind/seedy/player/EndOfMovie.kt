package io.github.arkmind.seedy.player

import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.runtime
import io.github.arkmind.seedy.core.i18n.t
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.math.ceil

/* The end of a movie (the web's PlayerEndMovie board, web/src/player/EndOfMovie.tsx): at the credits the video
   shrinks into a tile and similar titles come up, the first one counting down to play. */

/** The end-of-movie screen: not yet (or back before the credits), on screen, or put away with Watch credits. */
enum class EndFlow { Idle, Shown, Dismissed }

/** When the picks come up: at the credits, else 10 % before the end, between 20 s and 2 min (the web's rule). */
fun moviePicksAt(durationMs: Long, creditsMs: Long?): Long =
    creditsMs ?: (durationMs - minOf(120_000L, maxOf(20_000L, durationMs / 10)))

/** Where the flow goes at [positionMs]: up past [triggerMs]; back to idle 2 s before it, after a seek back. */
fun endFlowAt(flow: EndFlow, positionMs: Long, triggerMs: Long): EndFlow = when {
    positionMs >= triggerMs -> if (flow == EndFlow.Idle) EndFlow.Shown else flow
    positionMs < triggerMs - 2_000 -> EndFlow.Idle
    else -> flow
}

/** One second of the picks' countdown: it runs while the movie plays or once it has ended, and holds while paused. */
fun countdownStep(left: Int, playing: Boolean, ended: Boolean): Int = if (playing || ended) left - 1 else left

/** The similar titles worth offering: never the movie itself, a folder or a whole show; three at most. */
fun moviePicks(similar: List<BaseItemDto>, self: BaseItemDto): List<BaseItemDto> =
    similar.filter { it.id != self.id && it.isFolder != true && it.type != BaseItemKind.SERIES }.take(3)

/** "2025", "Drama", "2 h 03 min": a pick's year, first genre and length. */
fun pickMeta(it: BaseItemDto): List<String> = listOfNotNull(it.productionYear?.toString(), it.genres?.firstOrNull(), it.runtime.ifEmpty { null })

/** "Credits · 3 min left" (whole minutes, rounded up). */
fun creditsLeft(leftMs: Long): String = t("Credits · {time} left", "time" to duration(ceil(leftMs.coerceAtLeast(0) / 60_000.0) * 60))
