package io.github.arkmind.seedy.mobile.wrapped

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.wrapped.StoryKey
import io.github.arkmind.seedy.core.wrapped.Wrapped
import io.github.arkmind.seedy.core.wrapped.meduLine
import io.github.arkmind.seedy.core.wrapped.storiesOf
import io.github.arkmind.seedy.ui.wrapped.MeduSpot
import io.github.arkmind.seedy.ui.wrapped.StoryArea
import io.github.arkmind.seedy.ui.wrapped.WrappedInk
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.rememberLoopClock
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.wrapped.BoardCanvas
import io.github.arkmind.seedy.ui.wrapped.LocalStoryLoop
import io.github.arkmind.seedy.ui.wrapped.LocalStoryTime
import io.github.arkmind.seedy.ui.wrapped.StoryLook
import io.github.arkmind.seedy.ui.wrapped.StoryPlayer
import io.github.arkmind.seedy.ui.wrapped.Segments
import io.github.arkmind.seedy.ui.wrapped.WrappedIcons
import io.github.arkmind.seedy.ui.wrapped.loopSeconds
import io.github.arkmind.seedy.ui.wrapped.rememberStoryPlayer
import io.github.arkmind.seedy.ui.wrapped.storySlide
import io.github.arkmind.seedy.ui.wrapped.storyBackdrop
import io.github.arkmind.seedy.ui.wrapped.storyLook
import io.github.arkmind.seedy.ui.wrapped.storyTime
import kotlinx.coroutines.launch
import io.github.arkmind.seedy.core.wrapped.loverShows

/** Where a phone story's parts sit: below the status bar and the header, above the share button; [nav] is the
 * navigation bar's height under it. */
internal class StoryFrame(val top: Dp, val bottom: Dp, val nav: Dp)

/**
 * Boards "Seedy Wrapped · 1–12": the year's stories full screen, each for 16 s under its progress bar. A tap on the
 * right goes on, on the left back; holding a finger (or the pause button) holds the story; X or Back closes. "Share this
 * story" hands [onShare] the story as a picture (without the controls); the summary shares and saves its card.
 * [fixedProgress] holds the bar (screenshot tests); [message] is a toast (picture saved…).
 *
 * A story is never laid out shorter than the boards' 844 px, as Medu sits at its bottom under the text: on a shorter
 * screen it's drawn smaller (a lower density), still filling the screen.
 */
@Composable
fun WrappedScreen(
    wrapped: Wrapped,
    onClose: () -> Unit,
    onShare: (ImageBitmap) -> Unit,
    onSave: (ImageBitmap) -> Unit,
    start: Int = 0,
    fixedProgress: Float? = null,
    message: String? = null,
    onMessageShown: () -> Unit = {},
) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The board's height here: the screen without the navigation bar, a status bar taller than the board's 32 px
        // counting as 32. Both bars keep their real size at the lower density.
        val nav = WindowInsets.navigationBars.getBottom(density) / density.density
        val status = WindowInsets.statusBars.getTop(density) / density.density
        val room = maxHeight.value - nav
        val k = (if (status <= 32f * room / StoryHeight) room / StoryHeight else (room - status) / (StoryHeight - 32f)).coerceIn(.5f, 1f)
        CompositionLocalProvider(LocalDensity provides Density(density.density * k, density.fontScale)) {
            Stories(wrapped, onClose, onShare, onSave, start, fixedProgress, message, onMessageShown)
        }
    }
}

/** The phone boards' height, status bar included. */
private const val StoryHeight = 844f

@Composable
private fun Stories(
    wrapped: Wrapped,
    onClose: () -> Unit,
    onShare: (ImageBitmap) -> Unit,
    onSave: (ImageBitmap) -> Unit,
    start: Int,
    fixedProgress: Float?,
    message: String?,
    onMessageShown: () -> Unit,
) {
    val stories = remember(wrapped) { storiesOf(wrapped) }
    if (stories.isEmpty()) return
    val player = rememberStoryPlayer(stories.size, start, fixedProgress)
    val story = stories[player.index.coerceIn(0, stories.lastIndex)]
    val accent = LocalAccent.current
    val look = storyLook(story, accent, wrapped.clock?.kind, loverShows(wrapped))
    val loop = rememberLoopClock()
    val statusTop = max(32.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val frame = StoryFrame(statusTop + 64.dp, 96.dp + navBottom, navBottom)
    val storyLayer = rememberGraphicsLayer()
    val cardLayer = rememberGraphicsLayer()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val share = { layer: GraphicsLayer, save: Boolean ->
        player.paused = true
        scope.launch { runCatching { layer.toImageBitmap() }.getOrNull()?.let { if (save) onSave(it) else onShare(it) } }
        Unit
    }
    BackHandler(onBack = onClose)
    StoryBars(pale = !look.dark)

    Box(
        Modifier.fillMaxSize().background(StoryGap)
            .pointerInput(player) {
                // A finger down holds the story; a quick tap goes on (back on the left third). A long hold only holds,
                // and a tap a button took (Let's go, Share…) isn't a tap on the story.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    player.holding = true
                    var up: PointerInputChange? = null
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            up = change
                            break
                        }
                    }
                    player.holding = false
                    if (up != null && !up.isConsumed && up.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis) {
                        if (up.position.x < size.width * .3f) player.previous() else player.next()
                    }
                }
            },
    ) {
        CompositionLocalProvider(LocalStoryTime provides player.storyTime(), LocalStoryLoop provides loop) {
            // What "Share this story" shares: the story with its brand, without the bar and the buttons.
            Box(Modifier.fillMaxSize().storySlide(player.index).record(storyLayer).background(look.bg)) {
                look.brush?.let { b -> Box(Modifier.fillMaxSize().background(b)) }
                BoardCanvas(Modifier.fillMaxSize()) { w, h -> storyBackdrop(story, accent, w, h, loop.loopSeconds(), wrapped.clock?.kind, showsWon = loverShows(wrapped), topShift = (statusTop - 32.dp).value) }
                Brand(wrapped.year, look, Modifier.padding(start = 14.dp, top = statusTop + 13.dp))
                key(player.index) {
                    Story(story, wrapped, look, frame, player, onShareCard = { share(cardLayer, false) }, onSaveCard = { share(cardLayer, true) }, cardLayer = cardLayer)
                }
            }
            Chrome(player, look, statusTop, onClose)
            if (story != StoryKey.Cover && story != StoryKey.Summary) {
                ShareStory(look, Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp + navBottom)) { share(storyLayer, false) }
            }
        }
        Toast(message, onMessageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = 80.dp + navBottom))
    }
}

/** What shows behind a story while the next one slides in. */
private val StoryGap = androidx.compose.ui.graphics.Color(0xFF0E0E0E)

/** Draws the content into [layer] as well, so it can be turned into a picture. */
internal fun Modifier.record(layer: GraphicsLayer): Modifier = drawWithContent {
    layer.record { this@drawWithContent.drawContent() }
    drawLayer(layer)
}

/** The story's header: little Medu, "Seedy Wrapped" and the year (40 px tall, beside the buttons). */
@Composable
private fun Brand(year: Int, look: StoryLook, modifier: Modifier) {
    Row(modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Medu(MeduMood.Idle, 22.dp, height = 29.dp, glow = true, bubbles = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            SText(t("Seedy Wrapped") + " ", size = 13.sp, weight = 900, tracking = .02f, color = look.ink, maxLines = 1)
            SText(year.toString(), size = 13.sp, weight = 700, tracking = .02f, color = look.ink.copy(alpha = .6f), maxLines = 1)
        }
    }
}

/** The progress bar (one segment a story, the current one filling) and the pause and close buttons. */
@Composable
private fun Chrome(player: StoryPlayer, look: StoryLook, statusTop: Dp, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = statusTop + 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Segments(player, look.ink, Modifier.fillMaxWidth().semantics { contentDescription = t("Story {n} of {total}", "n" to player.index + 1, "total" to player.count) })
        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer(Modifier.weight(1f))
            RoundButton(if (player.paused) WebIcons.PlayRound else WrappedIcons.Pause, look.ink, if (player.paused) t("Play") else t("Pause")) { player.paused = !player.paused }
            RoundButton(WrappedIcons.Close, look.ink, t("Close Wrapped"), onClose)
        }
    }
}

@Composable
private fun RoundButton(icon: IconDef, ink: Color, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).tap(onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { SIcon(icon, size = 22.dp, color = ink) }
}

/** "Share this story": dark on light stories, light on dark ones. */
@Composable
private fun ShareStory(look: StoryLook, modifier: Modifier, onClick: () -> Unit) {
    val bg = if (look.dark) Color(0xFFF0F0F0) else Color(0xFF1E1E1E)
    val ink = if (look.dark) Color(0xFF1E1E1E) else Color.White
    Row(
        modifier.height(46.dp).clip(RoundedCornerShape(999.dp)).background(bg).tap(onClick = onClick).padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(WrappedIcons.Share, size = 18.dp, color = ink)
        SText(t("Share this story"), size = 15.sp, weight = 800, color = ink, maxLines = 1)
    }
}

/** Where Medu sits on a story (on the left, or the right) and when she pops in; her bubble follows .3 s later. */
private class Spot(val left: Boolean, val at: Float)

private val Spots = mapOf(
    StoryKey.Hours to Spot(false, 1.5f), StoryKey.Lover to Spot(true, 1.8f), StoryKey.Genres to Spot(false, 1.3f), StoryKey.Shows to Spot(true, 1.5f),
    StoryKey.Movie to Spot(false, 1.7f), StoryKey.Binge to Spot(true, 2f), StoryKey.Clock to Spot(false, 1.6f), StoryKey.Requests to Spot(true, 1.7f),
    StoryKey.Oddest to Spot(false, 1.8f), StoryKey.Personality to Spot(true, 1.6f),
)

/** The story itself, in the space between the header and the share button. */
@Composable
private fun Story(
    story: StoryKey,
    w: Wrapped,
    look: StoryLook,
    frame: StoryFrame,
    player: StoryPlayer,
    onShareCard: () -> Unit,
    onSaveCard: () -> Unit,
    cardLayer: GraphicsLayer,
) {
    val spot = Spots[story]
    // With Medu at the bottom, the story keeps clear of her (the boards' 214 px).
    val area = Modifier.fillMaxSize().padding(top = frame.top, bottom = if (spot != null) 214.dp + frame.nav else frame.bottom)
    val content: @Composable () -> Unit = {
        when (story) {
            StoryKey.Cover -> CoverStory(w, player.count, onGo = player::next)
            StoryKey.Hours -> HoursStory(w)
            StoryKey.Lover -> LoverStory(w)
            StoryKey.Genres -> GenresStory(w)
            StoryKey.Shows -> ShowsStory(w)
            StoryKey.Movie -> MovieStory(w)
            StoryKey.Binge -> BingeStory(w, look)
            StoryKey.Clock -> ClockStory(w, look)
            StoryKey.Requests -> RequestsStory(w)
            StoryKey.Oddest -> OddestStory(w)
            StoryKey.Personality -> PersonalityStory(w)
            StoryKey.Summary -> SummaryStory(w, cardLayer)
        }
    }
    // The cover, the hours, Shows or movies? and the summary lay themselves out in the space; the others start at its
    // top, drop their last line when it has no room (IfRoom), and run on behind Medu if they still must, as on the boards.
    val fixed = story == StoryKey.Cover || story == StoryKey.Hours || story == StoryKey.Lover || story == StoryKey.Summary
    if (fixed) Box(area) { content() } else StoryArea(area, content)
    val line = if (spot != null) meduLine(story, w) else null
    if (spot != null && line != null) {
        // Medu 84 px above the bottom, 14 px from her side: a dark bubble on light stories, a white one on dark.
        Box(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, bottom = 84.dp + frame.nav)) {
            MeduSpot(
                line, meduFirst = spot.left, bubbleBg = if (look.dark) Color.White else WrappedInk, bubbleInk = if (look.dark) WrappedInk else Color.White,
                at = spot.at, modifier = Modifier.align(if (spot.left) Alignment.BottomStart else Alignment.BottomEnd),
            )
        }
    }
    if (story == StoryKey.Summary) {
        Box(Modifier.fillMaxSize().padding(bottom = frame.bottom - 70.dp)) {
            SummaryButtons(Modifier.align(Alignment.BottomCenter), onShareCard, onSaveCard, onAgain = { player.paused = false; player.go(0) })
        }
    }
}
