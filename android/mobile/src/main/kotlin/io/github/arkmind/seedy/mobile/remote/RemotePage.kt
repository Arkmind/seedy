package io.github.arkmind.seedy.mobile.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.format.clock
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.remote.NowOn
import io.github.arkmind.seedy.core.remote.RemoteScreen
import io.github.arkmind.seedy.core.remote.RemoteState
import io.github.arkmind.seedy.ui.components.SeekGlyph
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.mobile.parts.tap
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.seasonsLabel
import java.util.UUID
import kotlin.math.roundToInt

/** Typing on the TV: the text, the titles matching it and the one the TV has focused. */
data class TypingUi(val text: String, val results: List<BaseItemDto>?, val focusedId: UUID? = null)

class RemoteActions(
    val onMinimise: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onTab: (String) -> Unit = {},
    val pad: PadActions = PadActions(),
    val onBack: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onType: () -> Unit = {},
    val onOptions: () -> Unit = {},
    val onVolumeUp: () -> Unit = {},
    val onVolumeDown: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onSeek: (Long) -> Unit = {},
    val onSkip: (Int) -> Unit = {},
    val onSubtitles: () -> Unit = {},
    val onAudio: () -> Unit = {},
    val onFullscreen: () -> Unit = {},
    val onMiniPlayer: () -> Unit = {},
    val onVolume: (Int) -> Unit = {},
    val onText: (String) -> Unit = {},
    val onShow: (BaseItemDto) -> Unit = {},
)

/**
 * The remote (boards "Android · Remote for a TV", "Remote for a computer", "Remote · typing on the TV"): the screen's
 * name under REMOTE, a tab per controlled screen and + for another; then a TV's pad and keys, or what a computer plays
 * with its transport. [typing] swaps the TV's pad for the text field and its results.
 */
@Composable
fun RemotePage(state: RemoteState, now: Long, mode: PadMode, typing: TypingUi?, actions: RemoteActions, still: Boolean = false) {
    val screen = state.activeScreen ?: return Box(Modifier.fillMaxSize().background(SeedyColors.Bg))
    Column(
        Modifier.fillMaxSize().background(SeedyColors.Bg).then(if (typing != null) Modifier.imePadding() else Modifier)
            .padding(start = 16.dp, end = 16.dp, top = statusInset() + 6.dp, bottom = if (typing != null) 16.dp else footInset()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header(screen, actions)
        when {
            typing != null -> TypingBody(screen, typing, actions)
            else -> {
                Tabs(state.controlledScreens, screen.deviceId, actions, still)
                if (screen.isTv) TvBody(screen, now, mode, actions) else ComputerBody(screen, now, actions)
            }
        }
    }
}

@Composable
private fun Header(screen: RemoteScreen, actions: RemoteActions) {
    val accent = LocalAccent.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RoundButton(RemoteIcons.Minimise, 22.dp, actions.onMinimise)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIcon(RemoteIcons.Remote, size = 14.dp, color = accent)
                SText(t("Remote"), size = 12.sp, weight = 800, tracking = 0.08f, color = accent, uppercase = true, maxLines = 1)
            }
            SText(screen.name, size = 15.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
        }
        RoundButton(RemoteIcons.More, 20.dp, actions.onMore)
    }
}

@Composable
private fun RoundButton(icon: IconDef, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = size, color = SeedyColors.White)
    }
}

/** The tabs share the row as CSS `flex: 1 1 auto` would: each its own width, plus an equal part of what's left. */
@Composable
private fun Tabs(screens: List<RemoteScreen>, active: String, actions: RemoteActions, still: Boolean) {
    Layout(
        content = {
            for (s in screens) Tab(s, s.deviceId == active, still) { actions.onTab(s.deviceId) }
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(onClick = actions.onAdd), contentAlignment = Alignment.Center) {
                SIcon(RemoteIcons.Plus, size = 18.dp, color = SeedyColors.White)
            }
        },
    ) { measurables, constraints ->
        val gap = 6.dp.roundToPx()
        val tabs = measurables.dropLast(1)
        val plus = measurables.last().measure(Constraints())
        val bases = tabs.map { it.maxIntrinsicWidth(40.dp.roundToPx()) }
        val room = constraints.maxWidth - plus.width - gap * tabs.size
        val total = bases.sum()
        val widths = if (tabs.isEmpty()) emptyList() else if (total <= room) {
            val extra = (room - total) / tabs.size.toFloat()
            bases.map { (it + extra).roundToInt() }
        } else bases.map { (room * it / total.toFloat()).roundToInt() }
        val placeables = tabs.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i].coerceAtLeast(0), 40.dp.roundToPx())) }
        layout(constraints.maxWidth, 40.dp.roundToPx()) {
            var x = 0
            for (p in placeables) {
                p.place(x, 0)
                x += p.width + gap
            }
            plus.place(x, 0)
        }
    }
}

@Composable
private fun Tab(s: RemoteScreen, on: Boolean, still: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(role = Role.Tab, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val ink = if (on) SeedyColors.InkInverse else SeedyColors.Text
        SIcon(kindIcon(s.kind), size = 17.dp, color = ink)
        SText(s.name, Modifier.weight(1f, fill = false), size = 13.sp, weight = 800, color = ink, maxLines = 1)
        if (s.playing) LiveDot(7.dp, still = still)
    }
}

/* ── A TV ── */

@Composable
private fun ColumnScope.TvBody(screen: RemoteScreen, now: Long, mode: PadMode, actions: RemoteActions) {
    screen.now?.let { NowCard(screen, it, now, actions) }
    RemotePad(mode, actions.pad, Modifier.weight(1f).fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        KeyTile(RemoteIcons.Back, t("Back"), actions.onBack)
        KeyTile(RemoteIcons.Home, t("Home"), actions.onHome)
        KeyTile(RemoteIcons.Keyboard, t("Type"), actions.onType)
        KeyTile(RemoteIcons.Options, t("Options"), actions.onOptions)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clip(RoundedCornerShape(16.dp)).background(RemoteColors.Tile), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp, 46.dp).tap(onClick = actions.onVolumeDown), contentAlignment = Alignment.Center) { SIcon(RemoteIcons.VolumeDown, size = 20.dp, color = SeedyColors.White) }
            Box(Modifier.width(1.dp).height(26.dp).background(Color(0xFF2E2E2E)))
            Box(Modifier.size(54.dp, 46.dp).tap(onClick = actions.onVolumeUp), contentAlignment = Alignment.Center) { SIcon(RemoteIcons.VolumeUp, size = 20.dp, color = SeedyColors.White) }
        }
        SText(t("Your phone’s volume buttons control the TV while this is open"), Modifier.weight(1f), size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
    }
}

/** What the TV plays: its picture, name and time left, Pause, and where it is (drag to move). */
@Composable
private fun NowCard(screen: RemoteScreen, n: NowOn, now: Long, actions: RemoteActions) {
    val accent = LocalAccent.current
    val viewer = LocalViewer.current
    val drag = remember { SeekDrag() }
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RemoteColors.Tile)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.width(84.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0D141C))) {
                    viewer.images?.landscape(n.item, 320, viewer.episodeImages)?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(n.title, size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    val left = if (n.durationMs > 0) timeLeft((n.durationMs - n.positionAt(now)) / 1000.0) else null
                    SText(listOfNotNull((if (n.heading != n.title) n.heading else n.kicker).ifEmpty { null }, left).joinToString(" · "), size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
                }
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(accent).tap(onClick = actions.onPlayPause), contentAlignment = Alignment.Center) {
                    SIcon(if (n.paused) RemoteIcons.Play else RemoteIcons.Pause, size = 22.dp, color = SeedyColors.InkInverse)
                }
            }
            SeekVisual(drag.at ?: n.fractionAt(now), 4.dp, RemoteColors.Track, thumb = false)
        }
        // The line's touch strip: from 8 px above it to the card's foot.
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp).seekInput(drag, n.durationMs, n.canSeek, 12.dp, actions.onSeek))
    }
}

@Composable
private fun RowScope.KeyTile(icon: IconDef, label: String, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(RemoteColors.Tile).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
    ) {
        SIcon(icon, size = 22.dp, color = SeedyColors.White)
        SText(label, size = 11.sp, weight = 700, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** A seek line being dragged: where the finger is, 0–1 (null when it isn't). */
private class SeekDrag {
    var at by mutableStateOf<Float?>(null)
}

/** The seek line as drawn: [height] tall, the thumb (4×16) over it when [thumb]; it lays out at the line's height. */
@Composable
private fun SeekVisual(fraction: Float, height: Dp, track: Color, thumb: Boolean) {
    val f = fraction.coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxWidth().height(height), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2)).background(track)) {
            Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(height / 2)).background(SeedyColors.White))
        }
        if (thumb) Box(Modifier.offset(x = maxWidth * f - 2.dp).requiredSize(4.dp, 16.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.White))
    }
}

/** Taps and drags over this strip move the screen there (on release); [inset] is the line's margin on each side. */
private fun Modifier.seekInput(drag: SeekDrag, durationMs: Long, enabled: Boolean, inset: Dp, onSeek: (Long) -> Unit): Modifier =
    if (!enabled || durationMs <= 0) this else this
        .pointerInput(durationMs) {
            val pad = inset.toPx()
            detectTapGestures { p -> onSeek(((p.x - pad) / (size.width - 2 * pad) * durationMs).toLong().coerceIn(0, durationMs)) }
        }
        .pointerInput(durationMs) {
            val pad = inset.toPx()
            fun at(x: Float) = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
            detectHorizontalDragGestures(
                onDragStart = { p -> drag.at = at(p.x) },
                onDragEnd = { drag.at?.let { onSeek((it * durationMs).toLong()) }; drag.at = null },
                onDragCancel = { drag.at = null },
            ) { change, _ -> drag.at = at(change.position.x) }
        }

/* ── A computer ── */

@Composable
private fun ColumnScope.ComputerBody(screen: RemoteScreen, now: Long, actions: RemoteActions) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val n = screen.now
        if (n == null) Idle(screen) else Playing(screen, n, now, actions)
        Hint(screen)
    }
}

@Composable
private fun Art(screen: RemoteScreen, n: NowOn?) {
    val viewer = LocalViewer.current
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(Color(0xFF2A1A22))) {
        n?.item?.let { item -> (viewer.images?.backdrop(item, 960) ?: viewer.images?.landscape(item, 960)) }?.let {
            AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
        if (n != null) {
            Row(
                Modifier.align(Alignment.BottomStart).padding(12.dp).clip(RoundedCornerShape(999.dp)).background(SeedyColors.Bg.copy(alpha = 0.8f)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SIcon(kindIcon(screen.kind), size = 14.dp, color = SeedyColors.Text)
                SText(
                    if (n.paused) t("Paused on the {device}", "device" to screen.name) else t("Playing on the {device}", "device" to screen.name),
                    size = 12.sp, weight = 700, color = SeedyColors.Text, maxLines = 1,
                )
            }
        }
        if (screen.chip.isNotEmpty()) {
            SText(
                screen.chip, Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(999.dp)).background(SeedyColors.Bg.copy(alpha = 0.8f)).padding(horizontal = 9.dp, vertical = 4.dp),
                size = 11.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1,
            )
        }
    }
}

@Composable
private fun Playing(screen: RemoteScreen, n: NowOn, now: Long, actions: RemoteActions) {
    Art(screen, n)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (n.kicker.isNotEmpty()) SText(n.kicker, size = 12.sp, weight = 700, tracking = 0.06f, color = SeedyColors.Text2, uppercase = true, maxLines = 1)
        SText(n.title, size = 24.sp, weight = 900, color = SeedyColors.White, maxLines = 2)
    }
    val drag = remember { SeekDrag() }
    Box {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SeekVisual(drag.at ?: n.fractionAt(now), 4.dp, Color(0x47FFFFFF), thumb = true)
            Row(Modifier.fillMaxWidth()) {
                val pos = drag.at?.let { (it * n.durationMs).toLong() } ?: n.positionAt(now)
                SText(clock(pos), Modifier.weight(1f), size = 12.sp, weight = 600, color = SeedyColors.White.copy(alpha = 0.75f), maxLines = 1)
                if (n.durationMs > 0) SText("−" + clock(n.durationMs - pos), size = 12.sp, weight = 600, color = SeedyColors.White.copy(alpha = 0.75f), maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().height(18.dp).seekInput(drag, n.durationMs, n.canSeek, 0.dp, actions.onSeek))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Surface).tap { actions.onSkip(-10) }, contentAlignment = Alignment.Center) {
            SeekGlyph(back = true, seconds = 10, size = 30.dp, color = SeedyColors.White)
        }
        Box(Modifier.size(76.dp).clip(RoundedCornerShape(24.dp)).background(SeedyColors.Inverse).tap(onClick = actions.onPlayPause), contentAlignment = Alignment.Center) {
            SIcon(if (n.paused) RemoteIcons.Play else RemoteIcons.Pause, size = 32.dp, color = SeedyColors.InkInverse)
        }
        Box(Modifier.size(58.dp).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Surface).tap { actions.onSkip(30) }, contentAlignment = Alignment.Center) {
            SeekGlyph(back = false, seconds = 30, size = 30.dp, color = SeedyColors.White)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ToggleTile(RemoteIcons.Subtitles, t("Subtitles"), (n.subtitleIndex ?: -1) >= 0 && n.subtitles.isNotEmpty(), actions.onSubtitles)
        ToggleTile(RemoteIcons.Audio, t("Audio"), false, actions.onAudio)
        ToggleTile(RemoteIcons.Fullscreen, t("Full screen"), screen.fullscreen, actions.onFullscreen)
        ToggleTile(RemoteIcons.MiniPlayer, t("Mini player"), screen.miniPlayer, actions.onMiniPlayer)
    }
    n.volume?.let { VolumeSlider(it, actions.onVolume) }
}

@Composable
private fun RowScope.ToggleTile(icon: IconDef, label: String, on: Boolean, onClick: () -> Unit) {
    val ink = if (on) SeedyColors.InkInverse else SeedyColors.White
    Column(
        Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(16.dp)).background(if (on) SeedyColors.Inverse else RemoteColors.Tile).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        SIcon(icon, size = 20.dp, color = ink)
        SText(label, size = 11.sp, weight = 700, color = ink, maxLines = 1)
    }
}

/** Seedy web's own volume (the computer's stays as it is): drag, it's sent when let go. */
@Composable
private fun VolumeSlider(level: Int, onVolume: (Int) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) drag else level / 100f
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(RemoteColors.Tile).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SIcon(RemoteIcons.Volume, size = 20.dp, color = SeedyColors.Text)
        BoxWithConstraints(
            Modifier.weight(1f).height(18.dp)
                .pointerInput(Unit) { detectTapGestures { p -> onVolume((p.x / size.width * 100).roundToInt().coerceIn(0, 100)) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { p -> dragging = true; drag = (p.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging = false; onVolume((drag * 100).roundToInt()) },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> drag = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(SeedyColors.Surface2)) {
                Box(Modifier.fillMaxWidth(shown.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(SeedyColors.White))
            }
            Box(Modifier.offset(x = maxWidth * shown.coerceIn(0f, 1f) - 9.dp).size(18.dp).clip(CircleShape).background(SeedyColors.White))
        }
        SText(t("Seedy only"), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
    }
}

/** No board: nothing plays on the computer yet. */
@Composable
private fun Idle(screen: RemoteScreen) {
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.dp)).background(RemoteColors.Tile), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SIcon(kindIcon(screen.kind), size = 34.dp, color = SeedyColors.Text3)
            SText(t("Nothing playing on the {device}", "device" to screen.name), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            if (screen.chip.isNotEmpty()) SText(screen.chip, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

@Composable
private fun Hint(screen: RemoteScreen) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, RemoteColors.Raised, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SIcon(kindIcon(screen.kind), size = 22.dp, color = accent)
        val bold = t("Browse here, watch there.")
        val rest = t("Anything you play on this phone goes to the {device} while it’s selected.", "device" to screen.name)
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(color = SeedyColors.White, fontWeight = FontWeight.ExtraBold)) { append(bold) }
                append(" ")
                append(rest)
            },
            Modifier.weight(1f), style = BaseText.copy(fontSize = 12.sp, lineHeight = (12 * 1.45).sp, color = SeedyColors.Text2),
        )
    }
}

/* ── Typing on the TV ── */

@Composable
private fun ColumnScope.TypingBody(screen: RemoteScreen, typing: TypingUi, actions: RemoteActions) {
    val accent = LocalAccent.current
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = 0.1f)).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            SIcon(RemoteIcons.Keyboard, size = 20.dp, color = accent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(t("The TV is asking for text"), size = 14.sp, weight = 800, color = SeedyColors.White)
            SText(t("Type here, it shows up in Search on {device}", "device" to screen.name), size = 12.sp, lineHeight = 1.35f, color = SeedyColors.Text2)
        }
    }
    Row(
        Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Surface).border(2.dp, accent, RoundedCornerShape(16.dp))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(RemoteIcons.Search, size = 20.dp, color = SeedyColors.Text2)
        // The caret stays after the text, also when it's cleared or comes back.
        var field by remember { mutableStateOf(TextFieldValue(typing.text, TextRange(typing.text.length))) }
        if (field.text != typing.text) field = TextFieldValue(typing.text, TextRange(typing.text.length))
        BasicTextField(
            value = field,
            onValueChange = { field = it; if (it.text != typing.text) actions.onText(it.text) },
            modifier = Modifier.weight(1f).focusRequester(focus),
            singleLine = true,
            textStyle = BaseText.copy(color = SeedyColors.White, fontSize = 18.sp, fontWeight = FontWeight(600)),
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        )
        if (typing.text.isNotEmpty()) {
            Box(Modifier.size(38.dp).clip(CircleShape).tap { actions.onText("") }, contentAlignment = Alignment.Center) {
                SIcon(RemoteIcons.Close, size = 18.dp, color = SeedyColors.White)
            }
        }
    }
    val results = typing.results
    if (!results.isNullOrEmpty()) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 4.dp)) {
                SText(
                    t("On the TV · {n} results", "n" to results.size).let { if (results.size == 1) t("On the TV · 1 result") else it },
                    Modifier.weight(1f), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true, maxLines = 1,
                )
                SText(t("Tap one to open it there"), size = 11.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
            }
            for (item in results) ResultRow(item, item.id == typing.focusedId) { actions.onShow(item) }
        }
    }
}

@Composable
private fun ResultRow(item: BaseItemDto, onTv: Boolean, onClick: () -> Unit) {
    val viewer = LocalViewer.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .then(if (onTv) Modifier.background(SeedyColors.Surface).border(2.dp, Color(0xD9FFFFFF), RoundedCornerShape(14.dp)) else Modifier)
            .tap(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(72.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF24313D))) {
            (viewer.images?.backdrop(item, 320) ?: viewer.images?.landscape(item, 320))?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(item.name.orEmpty(), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(resultLine(item), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        if (onTv) SText(t("On TV"), size = 11.sp, weight = 800, color = SeedyColors.TextHero, maxLines = 1)
        else SIcon(RemoteIcons.Chevron, size = 18.dp, color = RemoteColors.Faint)
    }
}

/** "Series · 2 seasons", "Movie · 2024 · 1 h 52", "Episode · Northbound · S1 E3". */
internal fun resultLine(item: BaseItemDto): String {
    val run = item.runTimeTicks?.let { ticks ->
        val min = (ticks / 600_000_000L).toInt()
        if (min >= 60) "${min / 60} h ${(min % 60).toString().padStart(2, '0')}" else "$min min"
    }
    return when (item.type) {
        BaseItemKind.SERIES -> listOf(t("Series"), item.seasonsLabel).filter { it.isNotEmpty() }.joinToString(" · ")
        BaseItemKind.MOVIE -> listOfNotNull(t("Movie"), item.productionYear?.toString(), run).joinToString(" · ")
        BaseItemKind.EPISODE -> listOfNotNull(t("Episode"), item.seriesName, item.episodeTag.ifEmpty { null }).joinToString(" · ")
        BaseItemKind.BOX_SET -> t("Collection")
        BaseItemKind.TV_CHANNEL -> t("Live TV")
        else -> listOfNotNull(item.productionYear?.toString(), run).joinToString(" · ")
    }
}
