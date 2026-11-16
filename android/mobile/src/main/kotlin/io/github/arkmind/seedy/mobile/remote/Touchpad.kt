package io.github.arkmind.seedy.mobile.remote

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tc
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Dir { Up, Down, Left, Right }

/** The TV remote's pad: a touchpad to swipe on, or the arrow buttons around OK. */
enum class PadMode { Touchpad, Buttons }

class PadActions(
    val onMove: (Dir) -> Unit = {},
    val onSelect: () -> Unit = {},
    val onOptions: () -> Unit = {},
    val onMode: (PadMode) -> Unit = {},
)

private val PadShape = RoundedCornerShape(26.dp)

/**
 * Board "Android · Remote for a TV", the pad: dotted, lighter in the middle; Touchpad or Buttons at the top right.
 * Swiping moves the TV's focus one step per 40 px, a tap is OK, holding opens the TV's options.
 */
@Composable
fun RemotePad(mode: PadMode, actions: PadActions, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(PadShape)
            .drawBehind {
                // radial-gradient(ellipse 70% 60% at 50% 45%, #1b1b1b, #121212)
                val rx = size.width * 0.7f
                val ry = size.height * 0.6f
                val c = Offset(size.width / 2, size.height * 0.45f)
                scale(rx / ry, 1f, pivot = c) {
                    val w = size.width * ry / rx
                    drawRect(
                        Brush.radialGradient(listOf(Color(0xFF1B1B1B), Color(0xFF121212)), center = c, radius = ry),
                        topLeft = Offset(c.x - w, 0f), size = Size(w * 2, size.height),
                    )
                }
                // Dots every 22 px, centred in each tile.
                val step = 22.dp.toPx()
                val r = 1.25.dp.toPx()
                val dot = Color(0x12FFFFFF)
                var y = step / 2
                while (y < size.height) {
                    var x = step / 2
                    while (x < size.width) {
                        drawCircle(dot, r, Offset(x, y))
                        x += step
                    }
                    y += step
                }
            }
            .border(1.dp, Color(0x0FFFFFFF), PadShape),
    ) {
        if (mode == PadMode.Touchpad) Touchpad(actions) else Buttons(actions)
        ModeSwitch(mode, actions.onMode, Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

@Composable
private fun ModeSwitch(mode: PadMode, onMode: (PadMode) -> Unit, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(11.dp)).background(SeedyColors.Bg.copy(alpha = 0.8f)).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for ((m, label) in listOf(PadMode.Touchpad to t("Touchpad"), PadMode.Buttons to t("Buttons"))) {
            val on = m == mode
            Box(
                Modifier.height(28.dp).clip(RoundedCornerShape(8.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap(role = Role.RadioButton) { onMode(m) }.padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(label, size = 12.sp, weight = 800, color = if (on) SeedyColors.InkInverse else SeedyColors.Text2, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Touchpad(actions: PadActions) {
    val accent = LocalAccent.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var touch by remember { mutableStateOf<Offset?>(null) }
    var lit by remember { mutableStateOf<Dir?>(null) }
    var litCount by remember { mutableIntStateOf(0) }
    val ripple = remember { Animatable(0f) }
    var ripplePos by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(litCount) {
        if (lit != null) {
            delay(220)
            lit = null
        }
    }
    val move: (Dir) -> Unit = { d ->
        lit = d
        litCount++
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        actions.onMove(d)
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            val step = 40.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown()
                touch = down.position
                val first = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull false
                        touch = c.position
                        if (!c.pressed) return@withTimeoutOrNull true
                        if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull false
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                when (first) {
                    // Held still: the TV's options.
                    null -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        actions.onOptions()
                        while (awaitPointerEvent().changes.any { it.pressed }) Unit
                    }
                    // A tap: OK.
                    true -> {
                        ripplePos = down.position
                        scope.launch { ripple.snapTo(0f); ripple.animateTo(1f, tween(600)) }
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        actions.onSelect()
                    }
                    // A swipe: one step per 40 px along the way it goes.
                    false -> {
                        var origin = down.position
                        while (true) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            c.consume()
                            touch = c.position
                            val dx = c.position.x - origin.x
                            val dy = c.position.y - origin.y
                            if (abs(dx) >= step && abs(dx) >= abs(dy)) {
                                move(if (dx > 0) Dir.Right else Dir.Left)
                                origin = Offset(origin.x + step * (if (dx > 0) 1 else -1), c.position.y)
                            } else if (abs(dy) >= step && abs(dy) > abs(dx)) {
                                move(if (dy > 0) Dir.Down else Dir.Up)
                                origin = Offset(c.position.x, origin.y + step * (if (dy > 0) 1 else -1))
                            }
                        }
                    }
                }
                touch = null
            }
        },
    ) {
        val w = maxWidth
        val h = maxHeight
        Arrow(RemoteIcons.Up, lit == Dir.Up, Modifier.align(Alignment.TopCenter).padding(top = 54.dp), dy = -1)
        Arrow(RemoteIcons.Down, lit == Dir.Down, Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp), dy = 1)
        Arrow(RemoteIcons.Left, lit == Dir.Left, Modifier.align(Alignment.CenterStart).padding(start = 14.dp).offset(y = (-13).dp), dx = -1)
        Arrow(RemoteIcons.Right, lit == Dir.Right, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).offset(y = (-13).dp), dx = 1)
        // At rest, a faint ring where the thumb goes (50 % across, 20 px above the middle).
        val centre = with(density) { Offset(w.toPx() / 2, h.toPx() / 2 - 20.dp.toPx()) }
        if (touch == null && !ripple.isRunning) Ring(centre, 86.dp, 3.dp, accent.copy(alpha = 0.25f))
        // A tap's ripple: from .6 to 1.9 of 60 px, fading.
        ripplePos?.takeIf { ripple.isRunning }?.let { p ->
            val k = ripple.value
            Ring(p, 60.dp * (0.6f + 1.3f * k), 2.dp, accent.copy(alpha = 0.7f * (1f - k)))
        }
        // The thumb.
        touch?.let { p ->
            val px = with(density) { 22.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((p.x - px).roundToInt(), (p.y - px).roundToInt()) }.size(44.dp).clip(CircleShape)
                    .background(Color(0x29FFFFFF)).border(2.dp, Color(0x80FFFFFF), CircleShape),
            )
        }
        SText(
            t("Swipe to move around · Tap to select · Hold for options"),
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp, start = 12.dp, end = 12.dp),
            size = 12.sp, weight = 600, color = SeedyColors.Text3, align = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
private fun Ring(centre: Offset, diameter: androidx.compose.ui.unit.Dp, stroke: androidx.compose.ui.unit.Dp, color: Color) {
    val density = LocalDensity.current
    val r = with(density) { (diameter / 2).toPx() }
    Box(
        Modifier.offset { IntOffset((centre.x - r).roundToInt(), (centre.y - r).roundToInt()) }.size(diameter).border(stroke, color, CircleShape),
    )
}

/** A dim arrow at the pad's edge; it lights up in the accent and nudges out when a swipe goes that way. */
@Composable
private fun Arrow(icon: IconDef, lit: Boolean, modifier: Modifier, dx: Int = 0, dy: Int = 0) {
    val accent = LocalAccent.current
    val color by animateColorAsState(if (lit) accent else RemoteColors.Dim, tween(120), label = "arrow")
    val shift by animateDpAsState(if (lit) 5.dp else 0.dp, tween(120), label = "arrow-shift")
    SIcon(icon, modifier.offset(x = shift * dx, y = shift * dy), size = 26.dp, color = color)
}

/** No board: the pad as buttons, the TV remote's ring of arrows around OK (hold OK for the options). */
@Composable
private fun Buttons(actions: PadActions) {
    val haptic = LocalHapticFeedback.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.Center).offset(y = (-14).dp).size(236.dp).clip(CircleShape).background(SeedyColors.Row).border(1.dp, Color(0x0FFFFFFF), CircleShape)) {
            val press: (Dir) -> Unit = { d -> haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); actions.onMove(d) }
            PadKey(RemoteIcons.Up, tc("Up", "direction"), Modifier.align(Alignment.TopCenter)) { press(Dir.Up) }
            PadKey(RemoteIcons.Down, tc("Down", "direction"), Modifier.align(Alignment.BottomCenter)) { press(Dir.Down) }
            PadKey(RemoteIcons.Left, tc("Left", "direction"), Modifier.align(Alignment.CenterStart)) { press(Dir.Left) }
            PadKey(RemoteIcons.Right, tc("Right", "direction"), Modifier.align(Alignment.CenterEnd)) { press(Dir.Right) }
            Box(
                Modifier.align(Alignment.Center).size(96.dp).clip(CircleShape).background(RemoteColors.Raised)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); actions.onSelect() },
                            onLongPress = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); actions.onOptions() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                SText(t("OK"), size = 18.sp, weight = 800, color = SeedyColors.White)
            }
        }
        SText(
            t("Tap an arrow to move · OK to select · Hold OK for options"),
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = 14.dp, start = 12.dp, end = 12.dp),
            size = 12.sp, weight = 600, color = SeedyColors.Text3, align = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
private fun PadKey(icon: IconDef, label: String, modifier: Modifier, onClick: () -> Unit) {
    var down by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Box(
        modifier.size(70.dp).clip(CircleShape)
            .semantics { contentDescription = label; role = Role.Button }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = { down = true; tryAwaitRelease(); down = false },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        SIcon(icon, Modifier.scale(if (down) 0.9f else 1f), size = 30.dp, color = if (down) accent else SeedyColors.Text)
    }
}
