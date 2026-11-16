package io.github.arkmind.seedy.mobile.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.mobile.parts.blockTaps
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Overlay
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The remote boards' icons (24 px grid). */
internal object RemoteIcons {
    val Tv = IconDef(2.2f, "M3 5h18v12H3z", "M8 21h8", "M12 17v4")
    val Laptop = IconDef(2.2f, "M4 5h16v11H4z", "M2 19h20")
    val Phone = WebIcons.DevicePhone
    val Tablet = WebIcons.DeviceTablet
    val Remote = IconDef(2.4f, "M9 2.5h6a2 2 0 0 1 2 2v15a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2v-15a2 2 0 0 1 2-2z", "M10.2 8.5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0", "M12 14h.01", "M12 17.5h.01")
    val Sync = IconDef(2.2f, "M20 11a8 8 0 0 0-14.5-4.5L4 8", "M4 3v5h5", "M4 13a8 8 0 0 0 14.5 4.5L20 16", "M20 21v-5h-5")
    val Close = IconDef(2.4f, "M6 6l12 12", "M18 6L6 18")
    val Check = IconDef(3.2f, "M5 12.5l4.5 4.5L19 7.5")
    val Cast = IconDef(2f, "M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M3 12a8 8 0 0 1 8 8", "M3 16a4 4 0 0 1 4 4", "M3 20h.01")
    val Minimise = IconDef(2.2f, "M6 9l6 6 6-6")
    val More = IconDef(2.2f, listOf(IconPart("M3.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0", filled = true), IconPart("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0", filled = true), IconPart("M16.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0-3.4 0", filled = true)))
    val Plus = IconDef(2.4f, "M12 5v14", "M5 12h14")
    val Pause = IconDef(0f, listOf(IconPart("M7.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2v-12.6a1.2 1.2 0 0 1 1.2-1.2z", filled = true), IconPart("M15.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2v-12.6a1.2 1.2 0 0 1 1.2-1.2z", filled = true)))
    val Play = IconDef(0f, listOf(IconPart("M7 4.5v15l12-7.5z", filled = true)))
    val Up = IconDef(2.6f, "M6 15l6-6 6 6")
    val Down = IconDef(2.6f, "M6 9l6 6 6-6")
    val Left = IconDef(2.6f, "M15 6l-6 6 6 6")
    val Right = IconDef(2.6f, "M9 6l6 6-6 6")
    val Back = IconDef(2.2f, "M9 14l-5-5 5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    val Home = IconDef(2.2f, "M3.5 11l8.5-7 8.5 7", "M6 9.5V20h12V9.5")
    val Keyboard = IconDef(2.2f, "M4 6.5h16a1.5 1.5 0 0 1 1.5 1.5v8a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 16V8A1.5 1.5 0 0 1 4 6.5z", "M6.5 10h.01", "M10 10h.01", "M14 10h.01", "M17.5 10h.01", "M8 14h8")
    val Options = IconDef(2.2f, "M4 7h16", "M4 12h16", "M4 17h16")
    val VolumeDown = IconDef(2.2f, "M4 9.5h4l5-4v13l-5-4H4z", "M15 12h5")
    val VolumeUp = IconDef(2.2f, "M4 9.5h4l5-4v13l-5-4H4z", "M17 9v6", "M14 12h6")
    val Search = IconDef(2.2f, "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13", "M15.5 15.5L20 20")
    val Chevron = IconDef(2.2f, "M9 6l6 6-6 6")
    val Subtitles = IconDef(2.2f, "M3 5h18v14H3z", "M10.5 10.2a2.5 2.5 0 1 0 0 3.6", "M17 10.2a2.5 2.5 0 1 0 0 3.6")
    val Audio = IconDef(2.2f, "M4 14v-2a8 8 0 0 1 16 0v2", "M4 14h3v6H4z", "M17 14h3v6h-3z")
    val Fullscreen = IconDef(2.2f, "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5")
    val MiniPlayer = IconDef(2.2f, "M3 5h18v14H3z", "M12 12h7v5h-7z")
    val Volume = IconDef(2.2f, "M4 9v6h4l5 4V5L8 9z", "M16.5 8.5a5 5 0 0 1 0 7", "M19 6a8.5 8.5 0 0 1 0 12")
    val Stop = IconDef(2.2f, "M6 6h12v12H6z")
    val Unlink = IconDef(2.2f, "M6 6l12 12", "M18 6L6 18")
}

internal fun kindIcon(kind: DeviceKind): IconDef = when (kind) {
    DeviceKind.Tv -> RemoteIcons.Tv
    DeviceKind.Computer -> RemoteIcons.Laptop
    DeviceKind.Phone -> RemoteIcons.Phone
    DeviceKind.Tablet -> RemoteIcons.Tablet
}

internal object RemoteColors {
    val Sheet = Color(0xFF161616)
    val Tile = Color(0xFF141414)
    val Raised = Color(0xFF262626)
    val Track = Color(0xFF333333)
    val Dim = Color(0xFF3A3A3A)
    val Faint = Color(0xFF5A5A5A)
    val PlayingBg = Color(0x1F9FE0B0)
    val PausedBg = Color(0x14FFFFFF)
}

/** The status bar's height, at least the boards' 36 px. */
@Composable
internal fun statusInset(): Dp = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())

/** The page's foot over the gesture bar, at least the boards' 26 px. */
@Composable
internal fun footInset(): Dp = max(26.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp)

/** A dot that breathes (the boards' `.live`: 1 → .35 → 1 over 1.6 s). */
@Composable
internal fun LiveDot(size: Dp, color: Color = SeedyColors.Ok, still: Boolean = false) {
    val alpha = if (still) 1f else {
        val t = rememberInfiniteTransition(label = "live")
        val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "live-a")
        a
    }
    Box(Modifier.size(size).alpha(alpha).clip(CircleShape).background(color))
}

/** "Playing" (green, its dot breathing) or "Paused". */
@Composable
internal fun StatePill(playing: Boolean, still: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(999.dp)).background(if (playing) RemoteColors.PlayingBg else RemoteColors.PausedBg).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (playing) LiveDot(6.dp, still = still)
        SText(
            if (playing) io.github.arkmind.seedy.core.i18n.t("Playing") else io.github.arkmind.seedy.core.i18n.t("Paused"),
            size = 11.sp, weight = 800, color = if (playing) SeedyColors.Ok else SeedyColors.TextHero, maxLines = 1,
        )
    }
}

/** The boards' 3 px progress line. */
@Composable
internal fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, height: Dp = 3.dp, track: Color = RemoteColors.Track) {
    Box(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(RoundedCornerShape(2.dp)).background(SeedyColors.White))
    }
}

/** The picker's switch: 44×26, a 20 px knob 3 px in; the accent when on. */
@Composable
internal fun RemoteSwitch(on: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(44.dp, 26.dp).alpha(if (enabled) 1f else 0.5f).clip(CircleShape).background(if (on) accent else SeedyColors.Surface2)
            .tap(role = Role.Switch, enabled = enabled) { onChange(!on) },
    ) {
        Box(Modifier.offset(x = if (on) 21.dp else 3.dp, y = 3.dp).size(20.dp).clip(CircleShape).background(if (on) SeedyColors.InkInverse else SeedyColors.Text3))
    }
}

/** The picker's tick box: 24 px, the accent with a dark tick when ticked, an outline otherwise. */
@Composable
internal fun TickBox(on: Boolean) {
    val accent = LocalAccent.current
    Box(
        Modifier.size(24.dp).clip(RoundedCornerShape(7.dp))
            .then(if (on) Modifier.background(accent) else Modifier.border(2.dp, RemoteColors.Faint, RoundedCornerShape(7.dp))),
        contentAlignment = Alignment.Center,
    ) {
        if (on) SIcon(RemoteIcons.Check, size = 14.dp, color = SeedyColors.InkInverse)
    }
}

/**
 * The picker's sheet (board "Android · Control a screen"): over the page dimmed to 62 % black, 28 px top corners, the
 * grabber; Back or a tap on the dimmed page closes it.
 */
@Composable
internal fun RemoteSheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) = Overlay {
    BackHandler(onBack = onDismiss)
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color(0x9E000000)).tap(onClick = onDismiss))
        AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it / 3 } + fadeIn()) {
            val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.fillMaxWidth().padding(top = 96.dp).shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape)
                    .background(RemoteColors.Sheet).blockTaps().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = max(30.dp, footInset() + 4.dp)),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(RemoteColors.Dim))
                content()
            }
        }
    }
}
