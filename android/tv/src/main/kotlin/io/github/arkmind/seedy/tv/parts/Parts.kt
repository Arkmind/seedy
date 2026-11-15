package io.github.arkmind.seedy.tv.parts

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay

/*
 * The TV boards are drawn at 1920×1080 px, which is 960×540 dp on a 1080p television: a board pixel is half a dp.
 * These let the TV screens use the boards' numbers as they are: 96.bp, 22.bsp.
 */
val Int.bp: Dp get() = (this / 2f).dp
val Float.bp: Dp get() = (this / 2f).dp
val Double.bp: Dp get() = (this / 2f).dp
val Int.bsp: TextUnit get() = (this / 2f).sp
val Double.bsp: TextUnit get() = (this / 2f).sp

/**
 * The TV boards' focus: the element grows a little and a white ring sits outside it, [gap] away, with the page's
 * colour in between (CSS `box-shadow: 0 0 0 gap bg, 0 0 0 gap+ring #fff`).
 */
fun Modifier.focusRing(
    focused: Boolean,
    radius: Dp,
    gap: Dp = 4.bp,
    ring: Dp = 5.bp,
    gapColor: Color = SeedyColors.Bg,
): Modifier = this.drawBehind {
    if (!focused) return@drawBehind
    val g = gap.toPx()
    val r = ring.toPx()
    val rad = radius.toPx()
    drawRoundRect(SeedyColors.White, Offset(-g - r, -g - r), Size(size.width + 2 * (g + r), size.height + 2 * (g + r)), CornerRadius(rad + g + r))
    drawRoundRect(gapColor, Offset(-g, -g), Size(size.width + 2 * g, size.height + 2 * g), CornerRadius(rad + g))
}

/** Grows to [scale] while focused, smoothly. */
@Composable
fun Modifier.focusScale(focused: Boolean, scale: Float): Modifier {
    val s by animateFloatAsState(if (focused) scale else 1f, label = "focus-scale")
    return this.graphicsLayer { scaleX = s; scaleY = s }
}

/** Something the remote can land on and press: OK (or Enter) calls [onClick]. [content] gets whether it's focused. */
@Composable
fun TvPressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interaction: MutableInteractionSource = remember { MutableInteractionSource() },
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focused by interaction.collectIsFocusedAsState()
    Box(modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)) { content(focused) }
}

/**
 * The TV boards' pill button (68 px, 20 px corners): translucent white at rest, solid light with dark text when
 * focused.
 */
@Composable
fun TvButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: IconDef? = null, primary: Boolean = false) {
    TvPressable(onClick, modifier) { focused ->
        val bg = when {
            focused -> SeedyColors.Inverse
            primary -> SeedyColors.Inverse.copy(alpha = 0.92f)
            else -> Color.White.copy(alpha = 0.1f)
        }
        val ink = if (focused || primary) SeedyColors.InkInverse else SeedyColors.White
        Row(
            Modifier.focusScale(focused, 1.04f).height(68.bp).clip(RoundedCornerShape(20.bp)).background(bg).padding(horizontal = 30.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            if (icon != null) SIcon(icon, size = 26.bp, color = ink)
            SText(label, size = 22.bsp, weight = 700, color = ink, maxLines = 1)
        }
    }
}

/**
 * Moves the remote's focus here as soon as the element exists. Lazy rows lay out their items after the first
 * composition, so a plain requestFocus() on launch can miss; this waits a frame at a time (up to half a second).
 */
suspend fun FocusRequester.focusSoon(): Boolean {
    repeat(30) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.getOrDefault(false)) return true
    }
    return false
}

/** Focuses [requester] once [ready]. */
@Composable
fun FocusWhen(ready: Boolean, requester: FocusRequester) {
    LaunchedEffect(ready) { if (ready) requester.focusSoon() }
}

/** Attaches [requester] when there is one. */
fun Modifier.maybeFocus(requester: FocusRequester?): Modifier = if (requester != null) focusRequester(requester) else this

/** Lets a list ignore focus: the screen scrolls it to the row in use itself. */
@OptIn(ExperimentalFoundationApi::class)
internal object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** Space kept around a card row so the focused card's ring isn't clipped (ring 9 px, plus the focused card's growth). */
internal val CardRowRingRoom = 12.bp

/** The pages' message chip, bottom left [start] from the edge, gone after 3.2 s. */
@Composable
fun BoxScope.TvToast(message: String?, onDone: () -> Unit, start: Dp) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(3200)
        onDone()
    }
    SText(
        message,
        Modifier.align(Alignment.BottomStart).padding(start = start, bottom = 48.bp).clip(RoundedCornerShape(16.bp)).background(SeedyColors.Surface)
            .padding(horizontal = 24.bp, vertical = 16.bp),
        size = 20.bsp, weight = 600, color = SeedyColors.Text,
    )
}

/**
 * The Medu pages' 80 px button (22 px corners, 27 px label): translucent at rest, light when [primary]; focused it's
 * light, lifts 6 % and casts the boards' shadow.
 */
@Composable
fun TvBigButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: IconDef? = null, iconSize: Dp = 30.bp, primary: Boolean = false) {
    TvPressable(onClick, modifier) { focused ->
        val light = focused || primary
        val ink = if (light) SeedyColors.InkInverse else SeedyColors.Text
        Row(
            Modifier.focusScale(focused, 1.06f)
                .then(if (focused) Modifier.boxShadow(Color.Black.copy(alpha = .55f), 44.bp, 20.bp, 22.bp) else Modifier)
                .height(80.bp).clip(RoundedCornerShape(22.bp)).background(if (light) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 42.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.bp),
        ) {
            if (icon != null) SIcon(icon, size = iconSize, color = ink, stroke = 2.2f)
            SText(label, size = 27.bsp, weight = 800, color = ink, maxLines = 1)
        }
    }
}
