package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.core.wrapped.MeduLine
import io.github.arkmind.seedy.core.wrapped.WrappedMovie
import io.github.arkmind.seedy.core.wrapped.WrappedOddest
import io.github.arkmind.seedy.core.wrapped.WrappedTitle
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.rememberStillMotion
import io.github.arkmind.seedy.ui.theme.LocalViewer

/** How long a story stays up before the next one (16 s, twice the boards' progress bars). */
val StorySeconds = io.github.arkmind.seedy.core.wrapped.StorySeconds.toFloat()

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

/** The oddest request's poster (the library's, else TMDB's) over the board's drawn retro one (which writes its title). */
@Composable
fun OddestArt(odd: WrappedOddest, modifier: Modifier = Modifier) {
    val images = LocalViewer.current.images
    val url = odd.item?.let { images?.poster(it, 600) } ?: tmdbImage(odd.posterPath, "w342")
    Box(modifier) {
        RetroPoster(odd.name, Modifier.matchParentSize())
        if (url != null) AsyncImage(url, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
    }
}

/** The web's mood names (core's MeduLine) as Medu's. */
fun meduMood(name: String): MeduMood = when (name) {
    "wave" -> MeduMood.Wave
    "search" -> MeduMood.Search
    "sleepy" -> MeduMood.Sleepy
    "yay" -> MeduMood.Yay
    "think" -> MeduMood.Think
    "oops" -> MeduMood.Oops
    "love" -> MeduMood.Love
    "popcorn" -> MeduMood.Popcorn
    "sad" -> MeduMood.Sad
    "party" -> MeduMood.Party
    else -> MeduMood.Idle
}

/**
 * Medu and what she says beside her (the boards' Medu spot): Medu [meduWidth] wide, popping in at [at]; her bubble,
 * its tail towards her, centred on a [bubbleBox]-tall box from her top (her bell) and following .3 s later.
 * [meduFirst]: Medu on the left. Sizes are the phone boards'.
 */
@Composable
fun MeduSpot(
    line: MeduLine,
    meduFirst: Boolean,
    bubbleBg: Color,
    bubbleInk: Color,
    at: Float,
    modifier: Modifier = Modifier,
    meduWidth: Dp = 88.dp,
    bubbleBox: Dp = 82.dp,
    gap: Dp = 12.dp,
    sayDelay: Float = at + .3f,
    textSize: TextUnit = 14.sp,
    maxWidth: Dp = 206.dp,
    padX: Dp = 14.dp,
    padY: Dp = 10.dp,
    radius: Dp = 18.dp,
    tail: Dp = 14.dp,
    tailOut: Dp = 5.dp,
    tailRadius: Dp = 3.dp,
) {
    if (line.text.isEmpty()) return
    Row(modifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(gap)) {
        val medu: @Composable () -> Unit = {
            Medu(meduMood(line.mood), meduWidth, Modifier.storyPop(at), height = meduWidth * 1.32f, torch = false)
        }
        val say: @Composable () -> Unit = {
            Box(Modifier.height(bubbleBox), contentAlignment = Alignment.Center) {
                Say(
                    line.text, bubbleBg, bubbleInk, tailEnd = !meduFirst,
                    modifier = Modifier.wrapContentHeight(Alignment.CenterVertically, unbounded = true).storySay(sayDelay, fromEnd = !meduFirst),
                    size = textSize, maxWidth = maxWidth, padX = padX, padY = padY, radius = radius, tail = tail, tailOut = tailOut, tailRadius = tailRadius,
                )
            }
        }
        if (meduFirst) {
            medu()
            say()
        } else {
            say()
            medu()
        }
    }
}

/**
 * The move between stories: the new one slides in a third of the way from the side it comes from as it fades up
 * (0.45 s, the web viewer's phone slide). [index] is the story showing; the first one just appears.
 */
@Composable
fun Modifier.storySlide(index: Int): Modifier {
    val progress = remember { Animatable(1f) }
    var last by remember { mutableIntStateOf(index) }
    var dir by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) {
        if (index == last) return@LaunchedEffect
        dir = if (index > last) 1 else -1
        progress.snapTo(0f)
        last = index
        progress.animateTo(1f, tween(450, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)))
    }
    return graphicsLayer {
        // Until the effect starts, the new story waits off to its side rather than flashing in place.
        val waiting = index != last
        val p = if (waiting) 0f else progress.value
        val d = if (waiting) (if (index > last) 1 else -1) else dir
        translationX = (1f - p) * .35f * size.width * d
        alpha = p
    }
}
