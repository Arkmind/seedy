package io.github.arkmind.seedy.mobile.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.BasicText
import io.github.arkmind.seedy.core.format.clock
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.PlayerState
import io.github.arkmind.seedy.core.settings.ResolvedStyle
import io.github.arkmind.seedy.ui.components.SubtitleLine
import io.github.arkmind.seedy.ui.components.SeekGlyph
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType

/** What the player's controls do. */
class PlayerActions(
    val onBack: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSeekTo: (Long) -> Unit,
    val onSkip: () -> Unit,
    val onNext: () -> Unit,
    val onLock: () -> Unit,
    val onPip: () -> Unit,
    val onCast: () -> Unit,
    val onPanel: (PlayerPanel?) -> Unit,
)

enum class PlayerPanel { Episodes, Tracks, Speed }

/** The board's bottom actions. */
private object PlayerIcons {
    val Episodes = IconDef(2f, "M5 8h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M6 5h12", "M9 2h6")
    val Tracks = IconDef(2f, "M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z", "M7 11h3M13 11h4", "M7 15h6M15.5 15H17")
    val Speed = IconDef(2f, "M12 21a9 9 0 1 1 9-9", "M12 12l4-4")
    val Next = IconDef(2f, listOf(IconPart("M6 5v14l10-7z", filled = true), IconPart("M18.5 5v14")))
    val Pip = IconDef(2f, "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2Z", "M13 11h4a1 1 0 0 1 1 1v3a1 1 0 0 1-1 1h-4a1 1 0 0 1-1-1v-3a1 1 0 0 1 1-1Z")
}

/**
 * Board "Android · Player": the video under a dimmed layer while the controls show — the title bar, seek and pause
 * in the middle, the episode's name, Skip intro, the scrubber with its marks and the four actions.
 */
@Composable
fun PhonePlayerScreen(
    state: PlayerState,
    controls: Boolean,
    locked: Boolean,
    seekBack: Int,
    seekForward: Int,
    actions: PlayerActions,
    onToggleControls: () -> Unit,
    video: @Composable () -> Unit,
    /** Settings · Subtitles: the text size, and whether subtitles rise above the controls. */
    subtitleSize: TextUnit = 18.sp,
    lift: Boolean = true,
    /** Settings · Subtitles › how they look on this phone (null: the player boards' look). */
    look: ResolvedStyle? = null,
    /** Settings · Gestures: a double tap on the left or right half skips back or forward. */
    doubleTapSeek: Boolean = false,
    panel: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video()
        Box(
            Modifier.fillMaxSize().pointerInput(doubleTapSeek, locked, seekBack, seekForward) {
                if (doubleTapSeek && !locked) {
                    detectTapGestures(
                        onTap = { onToggleControls() },
                        onDoubleTap = { o -> actions.onSeekBy(if (o.x < size.width / 2) -seekBack * 1000L else seekForward * 1000L) },
                    )
                } else {
                    detectTapGestures(onTap = { onToggleControls() })
                }
            },
        )
        if (look != null) {
            val up = controls && !locked
            LookSubtitles(state, look, lifted = up && look.position == "lifted")
        } else {
            Subtitles(state, lifted = controls && !locked && lift, subtitleSize = subtitleSize)
        }
        if (locked) {
            if (controls) {
                Box(Modifier.align(Alignment.TopEnd).padding(14.dp)) {
                    RoundIcon(WebIcons.Lock, t("Unlock controls"), actions.onLock, size = 20.dp, color = SeedyColors.White, bg = SeedyColors.Glass)
                }
            }
        } else if (controls) {
            Controls(state, seekBack, seekForward, actions)
        }
        if (!controls && state.skip != null && !locked) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 24.dp)) { SkipButton(state, actions.onSkip) }
        }
        panel()
    }
}

/** The board's seek and pause row: its 68 px buttons start 128 px down a 390 px screen. */
private val ControlsPlace = BiasAlignment(0f, 2f * 128f / (390f - 68f) - 1f)

@Composable
private fun Controls(state: PlayerState, seekBack: Int, seekForward: Int, actions: PlayerActions) {
    val item = state.item
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(190.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)))),
        )
        // Title bar.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RoundIcon(WebIcons.ArrowLeft, t("Back"), actions.onBack, size = 22.dp, stroke = 2.2f, color = SeedyColors.White)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SText(heading(item), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                subheading(item)?.let { SText(it, size = 12.sp, color = SeedyColors.Text2, maxLines = 1) }
            }
            RoundIcon(WebIcons.Lock, t("Lock controls"), actions.onLock, size = 20.dp, color = SeedyColors.Inverse)
            RoundIcon(PlayerIcons.Pip, t("Picture in picture"), actions.onPip, size = 20.dp, color = SeedyColors.Inverse)
            RoundIcon(WebIcons.Cast, t("Cast to a device"), actions.onCast, size = 20.dp, color = SeedyColors.Inverse)
        }
        // Seek and pause: 128 px from the top of the board's 390 px, kept in that proportion on taller screens.
        Row(
            Modifier.fillMaxWidth().align(ControlsPlace),
            horizontalArrangement = Arrangement.spacedBy(44.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!state.live) Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Bg.copy(alpha = 0.45f)).tap { actions.onSeekBy(-seekBack * 1000L) },
                contentAlignment = Alignment.Center,
            ) { SeekGlyph(back = true, seconds = seekBack, size = 30.dp, color = SeedyColors.Inverse) }
            Box(
                Modifier.size(68.dp).clip(RoundedCornerShape(19.dp)).background(SeedyColors.Inverse).tap(onClick = actions.onPlayPause),
                contentAlignment = Alignment.Center,
            ) { SIcon(if (state.playing) WebIcons.Pause else WebIcons.Play, size = 28.dp, color = SeedyColors.InkInverse) }
            if (!state.live) Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Bg.copy(alpha = 0.45f)).tap { actions.onSeekBy(seekForward * 1000L) },
                contentAlignment = Alignment.Center,
            ) { SeekGlyph(back = false, seconds = seekForward, size = 30.dp, color = SeedyColors.Inverse) }
        }
        // Bottom: the episode, Skip, the scrubber and the actions.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    overline(item)?.let { SText(it, size = 11.sp, weight = 700, tracking = 0.08f, color = SeedyColors.Text2, uppercase = true, maxLines = 1) }
                    SText(if (state.live) state.program?.name ?: item?.name.orEmpty() else item?.name.orEmpty(), size = 18.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                }
                if (state.skip != null) SkipButton(state, actions.onSkip)
            }
            if (state.live) LiveProgress(state) else Scrubber(state, actions.onSeekTo)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val episode = item?.type == BaseItemKind.EPISODE
                if (episode) Action(PlayerIcons.Episodes, t("Episodes")) { actions.onPanel(PlayerPanel.Episodes) }
                Action(PlayerIcons.Tracks, t("Subtitles and audio"), dot = state.subtitleIndex >= 0) { actions.onPanel(PlayerPanel.Tracks) }
                if (!state.live) Action(PlayerIcons.Speed, t("Speed {speed}", "speed" to speedLabel(state.speed))) { actions.onPanel(PlayerPanel.Speed) }
                if (state.next != null) Action(PlayerIcons.Next, t("Next episode"), onClick = actions.onNext)
            }
        }
    }
}

fun speedLabel(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

private fun heading(item: BaseItemDto?): String =
    if (item?.type == BaseItemKind.TV_CHANNEL) io.github.arkmind.seedy.core.livetv.LiveTv.cleanName(item.name.orEmpty()) else item?.seriesName ?: item?.name.orEmpty()

private fun subheading(item: BaseItemDto?): String? = when {
    item == null -> null
    item.type == BaseItemKind.TV_CHANNEL -> item.currentProgram?.let(io.github.arkmind.seedy.core.livetv.LiveTv::timeRange)
    item.type == BaseItemKind.EPISODE && item.parentIndexNumber != null && item.indexNumber != null ->
        t("Season {s} · Episode {e}", "s" to item.parentIndexNumber!!, "e" to item.indexNumber!!)
    else -> item.productionYear?.toString()
}

private fun overline(item: BaseItemDto?): String? = when {
    item == null -> null
    item.type == BaseItemKind.TV_CHANNEL -> t("Live · {channel}", "channel" to io.github.arkmind.seedy.core.livetv.LiveTv.cleanName(item.name.orEmpty()))
    item.type == BaseItemKind.EPISODE -> "${item.seriesName.orEmpty()} · S${item.parentIndexNumber ?: 0} E${item.indexNumber ?: 0}"
    else -> null
}

@Composable
private fun RoundIcon(icon: IconDef, label: String, onClick: () -> Unit, size: Dp, color: Color, stroke: Float = icon.stroke, bg: Color = Color.Transparent) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(bg).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = size, color = color, stroke = stroke)
    }
}

@Composable
private fun Action(icon: IconDef, label: String, dot: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(icon, size = 20.dp, color = SeedyColors.White)
        SText(label, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
        if (dot) Box(Modifier.size(6.dp).background(LocalAccent.current, CircleShape))
    }
}

/** "Skip intro", "Skip recap", "Skip credits". */
@Composable
private fun SkipButton(state: PlayerState, onSkip: () -> Unit) {
    val label = when (state.skip?.type) {
        MediaSegmentType.INTRO -> t("Skip intro")
        MediaSegmentType.RECAP, MediaSegmentType.PREVIEW -> t("Skip recap")
        MediaSegmentType.OUTRO -> t("Skip credits")
        else -> t("Skip")
    }
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Inverse).tap(onClick = onSkip).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SText(label, size = 14.sp, weight = 800, color = SeedyColors.InkInverse)
        SIcon(WebIcons.Skip, size = 17.dp, color = SeedyColors.InkInverse, stroke = 2.4f)
    }
}

/**
 * The board's scrubber: times either side of a 6 px track with what's buffered, the intro and credits in the accent,
 * chapter marks, and a 14 px knob. Drag or tap to move.
 */
@Composable
private fun Scrubber(state: PlayerState, onSeekTo: (Long) -> Unit) {
    val accent = LocalAccent.current
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = if (dragging) dragFraction else (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val chapters = state.item?.chapters.orEmpty().map { (it.startPositionTicks / 10_000f) / duration }.filter { it > 0.01f && it < 0.99f }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeText(clock(if (dragging) (dragFraction * duration).toLong() else state.positionMs))
        BoxWithConstraints(
            Modifier.weight(1f).height(28.dp)
                .pointerInput(duration) {
                    detectTapGestures { o -> onSeekTo((o.x / size.width * duration).toLong()) }
                }
                .pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> dragging = true; dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging = false; onSeekTo((dragFraction * duration).toLong()) },
                        onDragCancel = { dragging = false },
                    ) { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val w = maxWidth
            Box(
                Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))
                    .drawBehind {
                        val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
                        if (buffered > 0f) drawRect(Color.White.copy(alpha = 0.34f), size = size.copy(width = size.width * buffered))
                        for (s in state.segments) {
                            val a = (s.startTicks / 10_000f) / duration
                            val b = (s.endTicks / 10_000f) / duration
                            val alpha = if (s.type == MediaSegmentType.OUTRO) 0.45f else 0.6f
                            if (s.type in setOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO, MediaSegmentType.RECAP)) {
                                drawRect(accent.copy(alpha = alpha), Offset(size.width * a, 0f), size.copy(width = size.width * (b - a)))
                            }
                        }
                        drawRect(Color.White, size = size.copy(width = size.width * fraction))
                        for (c in chapters) drawRect(Color(0xFF0A0A0A), Offset(size.width * c, 0f), size.copy(width = 2.dp.toPx()))
                    },
            )
            Box(
                Modifier.offset(x = w * fraction - 7.dp).size(14.dp)
                    .drawBehind { drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 + 4.dp.toPx()) }
                    .background(Color.White, CircleShape),
            )
        }
        TimeText(clock(state.durationMs))
    }
}

/** A live channel's programme: how far into it, with its start and end on either side. */
@Composable
private fun LiveProgress(state: PlayerState) {
    val p = state.program
    val now = java.time.LocalDateTime.now()
    Row(Modifier.height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TimeText(io.github.arkmind.seedy.core.livetv.LiveTv.clockOf(p?.startDate))
        Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.2f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(io.github.arkmind.seedy.core.livetv.LiveTv.fraction(p, now)).background(Color(0xFFD63C3C)))
        }
        TimeText(io.github.arkmind.seedy.core.livetv.LiveTv.clockOf(p?.endDate))
    }
}

@Composable
private fun TimeText(text: String) {
    BasicText(text, style = BaseText.copy(fontSize = 12.sp, fontWeight = FontWeight(600), color = SeedyColors.Text, fontFeatureSettings = "tnum"))
}

/** Text subtitles (Seedy draws them, so their timing can move) and picture subtitles from the file. */
@Composable
private fun Subtitles(state: PlayerState, lifted: Boolean, subtitleSize: TextUnit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        for (cue in state.pictureCues) {
            val bitmap = cue.bitmap ?: continue
            val width = if (cue.size > 0f) w * cue.size else w * 0.5f
            val height = if (cue.bitmapHeight > 0f) h * cue.bitmapHeight else width * (bitmap.height.toFloat() / bitmap.width)
            val x = if (cue.position >= 0f) w * cue.position - width * (if (cue.positionAnchor == 1) 0.5f else if (cue.positionAnchor == 2) 1f else 0f) else (w - width) / 2
            val y = if (cue.line >= 0f && cue.lineType == 0) h * cue.line else h - height - 24.dp
            Image(bitmap.asImageBitmap(), null, Modifier.offset(x, y).size(width, height))
        }
        if (state.lines.isNotEmpty()) {
            BasicText(
                state.lines.joinToString("\n"),
                Modifier.align(Alignment.BottomCenter).padding(bottom = if (lifted) 150.dp else 24.dp, start = 48.dp, end = 48.dp).widthIn(max = 720.dp),
                style = BaseText.copy(
                    fontSize = subtitleSize, fontWeight = FontWeight(600), color = Color.White, textAlign = TextAlign.Center,
                    shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 2f), blurRadius = 6f), lineHeight = 1.3.em,
                ),
            )
        }
    }
}

/** Subtitles in this phone's look: size, font, colours, outline, and where they sit (bottom, above the controls, top). */
@Composable
private fun LookSubtitles(state: PlayerState, look: ResolvedStyle, lifted: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        for (cue in state.pictureCues) {
            val bitmap = cue.bitmap ?: continue
            val width = if (cue.size > 0f) w * cue.size else w * 0.5f
            val height = if (cue.bitmapHeight > 0f) h * cue.bitmapHeight else width * (bitmap.height.toFloat() / bitmap.width)
            val x = if (cue.position >= 0f) w * cue.position - width * (if (cue.positionAnchor == 1) 0.5f else if (cue.positionAnchor == 2) 1f else 0f) else (w - width) / 2
            val y = if (cue.line >= 0f && cue.lineType == 0) h * cue.line else h - height - 24.dp
            Image(bitmap.asImageBitmap(), null, Modifier.offset(x, y).size(width, height))
        }
        if (state.lines.isNotEmpty()) {
            val size = when (look.size) {
                "small" -> 15.sp
                "large" -> 22.sp
                "xlarge" -> 26.sp
                else -> 18.sp
            }
            val place = if (look.position == "top") Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
            else Modifier.align(Alignment.BottomCenter).padding(bottom = if (lifted) 150.dp else 24.dp)
            SubtitleLine(state.lines.joinToString("\n"), look, size, place.padding(horizontal = 48.dp).widthIn(max = 720.dp), preview = false)
        }
    }
}

/** The panels' shared frame: 20 px corners on a near-opaque card over a darker video. */
@Composable
fun PanelScrim(onClose: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.58f)).pointerInput(Unit) { detectTapGestures { onClose() } })
        content()
    }
}

/** A panel card: rgba(20,20,20,.97) with a faint outline. */
fun Modifier.panelCard(): Modifier = this
    .clip(RoundedCornerShape(20.dp))
    .background(Color(0xF7141414))
    .border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(20.dp))
    .pointerInput(Unit) { detectTapGestures { } }

@Composable
fun PanelClose(onClose: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClose), contentAlignment = Alignment.Center) {
        SIcon(WebIcons.Close, size = 18.dp, color = SeedyColors.TextHero, stroke = 2.4f)
    }
}

@Composable
internal fun Divider() {
    Spacer(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF222222)))
}

@Composable
internal fun VerticalDivider() {
    Spacer(Modifier.width(1.dp).fillMaxHeight().background(Color(0xFF222222)))
}
