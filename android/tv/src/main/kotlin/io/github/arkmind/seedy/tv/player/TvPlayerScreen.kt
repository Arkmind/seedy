package io.github.arkmind.seedy.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import io.github.arkmind.seedy.core.format.clock
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.player.PlayerState
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.ui.components.SeekGlyph
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentType
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The TV player's tools, in the board's order. */
enum class TvTool { Subtitles, Audio, Chapters, Episodes, Settings }

internal object TvPlayerIcons {
    val Subtitles = IconDef(2f, "M6 5h12a3 3 0 0 1 3 3v8a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V8a3 3 0 0 1 3-3z", "M7 11h3M13 11h4", "M7 15h6M15.5 15H17")
    val Audio = IconDef(2f, "M4 10v4M8 7v10", "M12 4v16", "M16 8v8M20 11v2")
    val Chapters = IconDef(2f, "M9 6h11M9 12h11M9 18h11", "M4 6h.01M4 12h.01", "M4 18h.01")
    val Episodes = IconDef(2f, "M5 8h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z", "M6 5h12", "M9 2h6")
    val Settings = IconDef(2f, "M4 7h9M17 7h3", "M4 17h3M11 17h9", "M15 5a2 2 0 1 1 0 4a2 2 0 1 1 0-4zM9 15a2 2 0 1 1 0 4a2 2 0 1 1 0-4z")
    val Next = IconDef(2.2f, listOf(IconPart("M6 5v14l10-7z", filled = true), IconPart("M18.5 5v14")))
}

/** What the TV player's controls do. */
class TvPlayerActions(
    val onPlayPause: () -> Unit,
    val onSeekBy: (Long) -> Unit,
    val onSkip: () -> Unit,
    val onNext: () -> Unit,
    val onTool: (TvTool) -> Unit,
    /** Any key the controls handled: keeps them on screen. */
    val onActivity: () -> Unit,
)

/** Where the remote lands when the controls appear. */
class TvPlayerFocus {
    val skip = FocusRequester()
    val play = FocusRequester()
    val scrubber = FocusRequester()
}

/**
 * Board "Android TV · Player": the title and the stream's pill on top, subtitles, then the episode's name with
 * Skip intro, the scrubber, the times and the transport and tool buttons.
 */
@Composable
fun TvPlayerScreen(
    state: PlayerState,
    controls: Boolean,
    seekBack: Int,
    seekForward: Int,
    actions: TvPlayerActions,
    focus: TvPlayerFocus,
    video: @Composable () -> Unit,
    subtitleSize: TextUnit = 40.bsp,
    /** A panel is open: subtitles step aside for it. */
    panelOpen: Boolean = false,
    panel: @Composable () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        video()
        if (controls) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.4f to Color(0x000A0A0A))))
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.62f)
                    .background(Brush.verticalGradient(listOf(Color(0x000A0A0A), Color.Black.copy(alpha = 0.85f)))),
            )
        }
        if (!panelOpen) Subtitles(state, if (controls) 400.bp else 96.bp, subtitleSize)
        if (controls) Controls(state, seekBack, seekForward, actions, focus)
        panel()
    }
}

@Composable
private fun Controls(state: PlayerState, seekBack: Int, seekForward: Int, actions: TvPlayerActions, focus: TvPlayerFocus) {
    val item = state.item
    Box(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 96.bp, end = 96.bp, top = 54.bp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(item?.seriesName ?: item?.name.orEmpty(), size = 20.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                if (item?.type == BaseItemKind.EPISODE) {
                    SText(t("Season {s} · Episode {e}", "s" to (item.parentIndexNumber ?: 0), "e" to (item.indexNumber ?: 0)), size = 17.bsp, color = SeedyColors.Text2)
                }
            }
            if (state.streamLabel.isNotEmpty()) {
                Row(
                    Modifier.height(42.bp).clip(RoundedCornerShape(999.bp)).background(SeedyColors.Tray).padding(horizontal = 16.bp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.bp),
                ) {
                    Box(Modifier.size(8.bp).background(SeedyColors.Ok, CircleShape))
                    SText(streamLabel(state.streamLabel), size = 16.bsp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
                }
            }
        }
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 96.bp, end = 96.bp, bottom = 54.bp),
            verticalArrangement = Arrangement.spacedBy(14.bp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.bp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.bp)) {
                    if (state.live) {
                        SText(
                            t("Live · {channel}", "channel" to io.github.arkmind.seedy.core.livetv.LiveTv.cleanName(item?.name.orEmpty())),
                            size = 17.bsp, weight = 700, tracking = 0.08f, color = Color(0xFFFF5C5C), uppercase = true, maxLines = 1,
                        )
                    } else if (item?.type == BaseItemKind.EPISODE) {
                        SText(
                            "${item.seriesName.orEmpty()} · S${item.parentIndexNumber ?: 0} E${item.indexNumber ?: 0}",
                            size = 17.bsp, weight = 700, tracking = 0.08f, color = SeedyColors.Text2, uppercase = true, maxLines = 1,
                        )
                    }
                    SText(if (state.live) state.program?.name ?: item?.name.orEmpty() else item?.name.orEmpty(), size = 48.bsp, weight = 800, lineHeight = 1.05f, color = SeedyColors.White, maxLines = 1)
                    SText(if (state.live) liveLine(state) else endsLine(state), size = 18.bsp, color = SeedyColors.Text2)
                }
                if (state.skip != null) SkipButton(state, actions.onSkip, focus.skip)
            }
            if (state.live) {
                val p = state.program
                Box(Modifier.fillMaxWidth().height(32.bp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(10.bp).clip(RoundedCornerShape(5.bp)).background(Color.White.copy(alpha = 0.2f))) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(io.github.arkmind.seedy.core.livetv.LiveTv.fraction(p, java.time.LocalDateTime.now())).background(Color(0xFFD63C3C)))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeText(io.github.arkmind.seedy.core.livetv.LiveTv.clockOf(p?.startDate))
                    TimeText(io.github.arkmind.seedy.core.livetv.LiveTv.clockOf(p?.endDate))
                }
            } else {
                Scrubber(state, seekBack, seekForward, actions, focus.scrubber)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TimeText(clock(state.positionMs))
                    TimeText(clock(state.durationMs))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.bp), verticalAlignment = Alignment.CenterVertically) {
                    TvPressable(actions.onPlayPause, Modifier.focusRequester(focus.play)) { focused ->
                        Box(
                            Modifier.size(68.bp).focusRing(focused, radius = 17.bp, gap = 4.bp, ring = 5.bp, gapColor = Color.Black)
                                .clip(RoundedCornerShape(17.bp)).background(SeedyColors.Inverse),
                            contentAlignment = Alignment.Center,
                        ) { SIcon(if (state.playing) WebIcons.Pause else WebIcons.Play, size = 28.bp, color = SeedyColors.InkInverse) }
                    }
                    if (!state.live) {
                        ToolButton({ actions.onSeekBy(-seekBack * 1000L) }) { ink -> SeekGlyph(back = true, seconds = seekBack, size = 34.bp, color = ink) }
                        ToolButton({ actions.onSeekBy(seekForward * 1000L) }) { ink -> SeekGlyph(back = false, seconds = seekForward, size = 34.bp, color = ink) }
                    }
                    if (state.next != null) ToolButton(actions.onNext) { ink -> SIcon(TvPlayerIcons.Next, size = 30.bp, color = ink) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.bp), verticalAlignment = Alignment.CenterVertically) {
                    ToolButton({ actions.onTool(TvTool.Subtitles) }, dot = state.subtitleIndex >= 0) { ink -> SIcon(TvPlayerIcons.Subtitles, size = 30.bp, color = ink) }
                    if (state.audio.size > 1) ToolButton({ actions.onTool(TvTool.Audio) }) { ink -> SIcon(TvPlayerIcons.Audio, size = 30.bp, color = ink) }
                    if (!item?.chapters.isNullOrEmpty()) ToolButton({ actions.onTool(TvTool.Chapters) }) { ink -> SIcon(TvPlayerIcons.Chapters, size = 30.bp, color = ink) }
                    if (item?.type == BaseItemKind.EPISODE) ToolButton({ actions.onTool(TvTool.Episodes) }) { ink -> SIcon(TvPlayerIcons.Episodes, size = 30.bp, color = ink) }
                    ToolButton({ actions.onTool(TvTool.Settings) }) { ink -> SIcon(TvPlayerIcons.Settings, size = 30.bp, color = ink) }
                }
            }
        }
    }
}

/** "Direct play · 4K HDR10 · 5.1", in the viewer's language. */
private fun streamLabel(label: String): String =
    label.split(" · ").joinToString(" · ") { part -> if (part in setOf("Direct play", "Direct stream", "Transcoding", "Stereo", "Mono")) t(part) else part }

/** "Ends at 22:41 · 50 min left" (allowing for the playback speed). */
private fun endsLine(state: PlayerState): String {
    val leftMs = ((state.durationMs - state.positionMs) / state.speed.coerceAtLeast(0.25f)).toLong().coerceAtLeast(0)
    val ends = LocalTime.now().plusSeconds(leftMs / 1000).format(DateTimeFormatter.ofPattern("HH:mm"))
    return join(t("Ends at {time}", "time" to ends), t("{time} left", "time" to duration(leftMs / 1000.0)))
}

/** A live programme's line: "20:45 – 22:45 · 55 min left". */
private fun liveLine(state: PlayerState): String {
    val p = state.program ?: return ""
    return join(io.github.arkmind.seedy.core.livetv.LiveTv.timeRange(p), io.github.arkmind.seedy.core.livetv.LiveTv.leftLabel(p, java.time.LocalDateTime.now()))
}

@Composable
private fun ToolButton(onClick: () -> Unit, dot: Boolean = false, icon: @Composable (Color) -> Unit) {
    TvPressable(onClick) { focused ->
        Box(
            Modifier.size(64.bp).clip(RoundedCornerShape(17.bp)).background(if (focused) SeedyColors.Inverse else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            icon(if (focused) SeedyColors.InkInverse else SeedyColors.Inverse)
            if (dot) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 8.bp).size(7.bp).background(LocalAccent.current, CircleShape))
        }
    }
}

@Composable
private fun SkipButton(state: PlayerState, onSkip: () -> Unit, requester: FocusRequester) {
    val label = when (state.skip?.type) {
        MediaSegmentType.INTRO -> t("Skip intro")
        MediaSegmentType.RECAP, MediaSegmentType.PREVIEW -> t("Skip recap")
        MediaSegmentType.OUTRO -> t("Skip credits")
        else -> t("Skip")
    }
    TvPressable(onSkip, Modifier.focusRequester(requester)) { focused ->
        Row(
            Modifier.height(64.bp).focusRing(focused, radius = 17.bp, gap = 4.bp, ring = 5.bp, gapColor = Color.Black)
                .clip(RoundedCornerShape(17.bp)).background(SeedyColors.Inverse).padding(horizontal = 38.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.bp),
        ) {
            SText(label, size = 21.bsp, weight = 800, color = SeedyColors.InkInverse)
            SIcon(WebIcons.Skip, size = 26.bp, color = SeedyColors.InkInverse, stroke = 2.4f)
        }
    }
}

/**
 * The 10 px scrubber with the intro and credits in the accent and the chapters marked. With the remote on it, left
 * and right move back and forward.
 */
@Composable
private fun Scrubber(state: PlayerState, seekBack: Int, seekForward: Int, actions: TvPlayerActions, requester: FocusRequester) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val duration = state.durationMs.coerceAtLeast(1)
    val fraction = (state.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val buffered = (state.bufferedMs.toFloat() / duration).coerceIn(0f, 1f)
    val chapters = state.item?.chapters.orEmpty().map { (it.startPositionTicks / 10_000f) / duration }.filter { it > 0.01f && it < 0.995f }
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(32.bp).focusRequester(requester)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { actions.onSeekBy(-seekBack * 1000L); true }
                    Key.DirectionRight -> { actions.onSeekBy(seekForward * 1000L); true }
                    Key.DirectionCenter, Key.Enter -> { actions.onPlayPause(); true }
                    else -> false
                }
            }
            .focusable(interactionSource = interaction),
        contentAlignment = Alignment.CenterStart,
    ) {
        val w = maxWidth
        val bar = if (focused) 14.bp else 10.bp
        Box(
            Modifier.fillMaxWidth().height(bar).clip(RoundedCornerShape(bar / 2)).background(Color.White.copy(alpha = 0.2f))
                .drawBehind {
                    drawRect(Color.White.copy(alpha = 0.34f), size = size.copy(width = size.width * buffered))
                    for (s in state.segments) {
                        if (s.type !in setOf(MediaSegmentType.INTRO, MediaSegmentType.OUTRO, MediaSegmentType.RECAP)) continue
                        val a = (s.startTicks / 10_000f) / duration
                        val b = (s.endTicks / 10_000f) / duration
                        drawRect(accent.copy(alpha = if (s.type == MediaSegmentType.OUTRO) 0.45f else 0.6f), Offset(size.width * a, 0f), size.copy(width = size.width * (b - a)))
                    }
                    drawRect(Color.White, size = size.copy(width = size.width * fraction))
                    for (c in chapters) drawRect(Color(0xFF0A0A0A), Offset(size.width * c, 0f), size.copy(width = 4.bp.toPx()))
                },
        )
        val knob = if (focused) 26.bp else 20.bp
        Box(
            Modifier.offset(x = w * fraction - knob / 2).size(knob)
                .drawBehind { drawCircle(Color.White.copy(alpha = 0.18f), radius = size.minDimension / 2 + 7.bp.toPx()) }
                .background(Color.White, CircleShape),
        )
    }
}

@Composable
private fun TimeText(text: String) {
    BasicText(text, style = BaseText.copy(fontSize = 18.bsp, fontWeight = FontWeight(600), color = SeedyColors.Text, fontFeatureSettings = "tnum"))
}

/** Text subtitles, [bottom] above the screen's bottom edge; picture subtitles where the file puts them. */
@Composable
private fun Subtitles(state: PlayerState, bottom: androidx.compose.ui.unit.Dp, size: TextUnit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        for (cue in state.pictureCues) {
            val bitmap = cue.bitmap ?: continue
            val width = if (cue.size > 0f) w * cue.size else w * 0.5f
            val height = if (cue.bitmapHeight > 0f) h * cue.bitmapHeight else width * (bitmap.height.toFloat() / bitmap.width)
            val x = if (cue.position >= 0f) w * cue.position - width * (if (cue.positionAnchor == 1) 0.5f else if (cue.positionAnchor == 2) 1f else 0f) else (w - width) / 2
            val y = if (cue.line >= 0f && cue.lineType == 0) h * cue.line else h - height - 48.bp
            androidx.compose.foundation.Image(bitmap.asImageBitmap(), null, Modifier.offset(x, y).size(width, height))
        }
        if (state.lines.isNotEmpty()) {
            BasicText(
                state.lines.joinToString("\n"),
                Modifier.align(Alignment.BottomCenter).padding(bottom = bottom).fillMaxWidth(0.8f).widthIn(max = 1600.bp),
                style = BaseText.copy(
                    fontSize = size, fontWeight = FontWeight(600), color = Color.White, textAlign = TextAlign.Center, lineHeight = 1.3.em,
                    shadow = Shadow(Color.Black.copy(alpha = 0.9f), Offset(0f, 3f), blurRadius = 10f),
                ),
            )
        }
    }
}
