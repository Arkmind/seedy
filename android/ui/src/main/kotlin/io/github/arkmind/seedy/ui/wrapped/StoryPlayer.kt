package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.wrapped.WrappedMovie
import io.github.arkmind.seedy.core.wrapped.WrappedTitle
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.rememberStillMotion
import io.github.arkmind.seedy.ui.theme.LocalViewer

/** How long a story stays up before the next one, as on the boards' progress bars (8 s). */
const val StorySeconds = 8f

/**
 * The stories' clock, for the phone and the TV: which story is up, how far its bar has filled, the time since it came
 * up (entrances), and whether it's held. Each story runs [StorySeconds], then the next one comes; the last one stays.
 * [fixed] holds the bar at that fill (screenshot tests).
 */
class StoryPlayer internal constructor(val count: Int, private val indexState: androidx.compose.runtime.MutableIntState, val fixed: Float?) {
    val index: Int get() = indexState.intValue
    internal val fill = mutableFloatStateOf(fixed ?: 0f)
    internal val time = mutableFloatStateOf(0f)

    /** How far the story's bar has filled, 0..1; read it while drawing. */
    val progress: FloatState get() = fill

    /** The pause button (or OK on the TV). */
    var paused by mutableStateOf(false)

    /** A finger held on the story. */
    var holding by mutableStateOf(false)

    fun go(to: Int) {
        indexState.intValue = to.coerceIn(0, count - 1)
        fill.floatValue = fixed ?: 0f
        time.floatValue = 0f
    }

    fun next() {
        if (index < count - 1) go(index + 1)
    }

    /** Back a story; on the first one, it starts over. */
    fun previous() = go(index - 1)
}

/** Runs [StoryPlayer]: the bar fills while nothing holds it, and the story's entrances play from when it came up. */
@Composable
fun rememberStoryPlayer(count: Int, start: Int = 0, fixed: Float? = null): StoryPlayer {
    val index = rememberSaveable { mutableIntStateOf(start.coerceIn(0, (count - 1).coerceAtLeast(0))) }
    val player = remember(count) { StoryPlayer(count, index, fixed) }
    val running = fixed == null && !player.paused && !player.holding
    LaunchedEffect(player.index, running) {
        if (!running) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (player.fill.floatValue < 1f) {
            withFrameNanos { now ->
                player.fill.floatValue = (player.fill.floatValue + (now - last) / (StorySeconds * 1e9f)).coerceAtMost(1f)
                last = now
            }
        }
        player.next()
    }
    val still = rememberStillMotion()
    LaunchedEffect(player.index, still) {
        if (still) return@LaunchedEffect
        val begin = withFrameNanos { it }
        while (true) withFrameNanos { player.time.floatValue = (it - begin) / 1e9f }
    }
    return player
}

/** The story's own clock for [LocalStoryTime]: null with motion off (entrances done). */
@Composable
fun StoryPlayer.storyTime(): FloatState? = if (rememberStillMotion()) null else time

/**
 * A show's picture over its drawn stand-in: the series' backdrop when [wide] (the #1 card, else its thumb or poster),
 * its thumb (else a backdrop) for the small ones. The drawn landscape [variant] shows while it loads or without one.
 */
@Composable
fun ShowArt(title: WrappedTitle?, variant: Int, wide: Boolean, modifier: Modifier = Modifier) {
    val images = LocalViewer.current.images
    val item = title?.item
    val url = if (images == null || item == null) null
    else if (wide) images.backdrop(item, 1280) ?: images.landscape(item, 1280) ?: images.primary(item, 1280)
    else images.landscape(item, 400)
    Box(modifier) {
        Landscape(variant, Modifier.matchParentSize())
        if (url != null) AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/** The movie's poster over the board's drawn one (which writes its title). */
@Composable
fun MovieArt(movie: WrappedMovie, modifier: Modifier = Modifier) {
    val images = LocalViewer.current.images
    val url = movie.item?.let { images?.poster(it, 600) }
    Box(modifier) {
        PosterArt(movie.name, Modifier.matchParentSize())
        if (url != null) AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/** The web's mood names (core's clockMood) as Medu's. */
fun meduMood(name: String): MeduMood = when (name) {
    "sleepy" -> MeduMood.Sleepy
    "popcorn" -> MeduMood.Popcorn
    "party" -> MeduMood.Party
    else -> MeduMood.Wave
}
