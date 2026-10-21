package io.github.arkmind.seedy.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.format.audioTrackLabel
import io.github.arkmind.seedy.core.format.episodeMeta
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.core.format.subtitleTrackLabel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.PlayerState
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalDate

/** Board "Android · Player episodes": the season's episodes in a 410 px card on the right; the season can change. */
@Composable
fun EpisodesPanel(
    state: PlayerState,
    episodes: List<BaseItemDto>,
    seasonId: java.util.UUID?,
    onSeason: (BaseItemDto) -> Unit,
    onEpisode: (BaseItemDto) -> Unit,
    onClose: () -> Unit,
) {
    val viewer = LocalViewer.current
    val accent = LocalAccent.current
    val season = state.seasons.firstOrNull { it.id == seasonId }
    PanelScrim(onClose) {
        Column(Modifier.fillMaxHeight().padding(12.dp).width(410.dp).align(Alignment.TopEnd).panelCard()) {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap { open = true }.padding(start = 14.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SText(season?.name ?: t("Season"), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                        SIcon(WebIcons.ChevronDown, size = 15.dp, color = SeedyColors.White, stroke = 2.4f)
                    }
                    DropdownMenu(open, { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                        for (s in state.seasons) {
                            DropdownMenuItem(
                                text = { SText(s.name.orEmpty(), size = 14.sp, weight = if (s.id == seasonId) 800 else 600, color = SeedyColors.White) },
                                onClick = { open = false; onSeason(s) },
                            )
                        }
                    }
                }
                val aired = episodes.count { e -> e.premiereDate?.toLocalDate()?.isAfter(LocalDate.now()) != true }
                SText(
                    if (aired < episodes.size) t("{episodes} · {aired} out", "episodes" to tn(episodes.size, "{n} episode", "{n} episodes"), "aired" to aired)
                    else tn(episodes.size, "{n} episode", "{n} episodes"),
                    Modifier.weight(1f), size = 12.sp, color = SeedyColors.Text3, maxLines = 1,
                )
                PanelClose(onClose)
            }
            Divider()
            LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                items(episodes, key = { it.id }) { e ->
                    val playing = e.id == state.item?.id
                    val future = e.premiereDate?.toLocalDate()?.isAfter(LocalDate.now()) == true
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (playing) SeedyColors.Surface else Color.Transparent)
                            .tap(enabled = !playing) { onEpisode(e) }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)).alpha(if (future) 0.35f else 1f)) {
                            Artwork(viewer.images?.landscape(e, 240, viewer.episodeImages), Modifier.matchParentSize())
                            if (e.progress > 0f && e.userData?.played != true) {
                                Box(Modifier.align(Alignment.BottomCenter).padding(5.dp).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                                    Box(Modifier.fillMaxHeight().fillMaxWidth(e.progress).background(Color.White))
                                }
                            }
                            if (e.userData?.played == true) {
                                Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp).background(SeedyColors.Scrim, CircleShape), contentAlignment = Alignment.Center) {
                                    SIcon(WebIcons.Check, size = 11.dp, color = Color.White, stroke = 3f)
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SText(
                                    "${e.indexNumber ?: ""} · ${e.name.orEmpty()}", Modifier.weight(1f, fill = false),
                                    size = 13.sp, weight = 700, color = if (future) SeedyColors.Text2 else SeedyColors.White, maxLines = 1,
                                )
                                if (playing) SText(t("Playing"), size = 11.sp, weight = 800, color = accent, uppercase = true)
                            }
                            SText(episodeMeta(e), size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Board "Android · Player subtitles and audio": audio and subtitles side by side, then subtitle timing. */
@Composable
fun TracksPanel(
    state: PlayerState,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int) -> Unit,
    onOffset: (Long) -> Unit,
    onStyle: () -> Unit,
    onClose: () -> Unit,
) {
    val accent = LocalAccent.current
    PanelScrim(onClose) {
        Column(Modifier.fillMaxHeight().padding(12.dp).width(580.dp).align(Alignment.TopEnd).panelCard()) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                SText(t("Subtitles and audio"), Modifier.weight(1f), size = 15.sp, weight = 800, color = SeedyColors.White)
                PanelClose(onClose)
            }
            Divider()
            Row(Modifier.fillMaxWidth().weight(1f)) {
                TrackColumn(t("Audio"), Modifier.weight(1f)) {
                    for (a in state.audio) {
                        val (label, sub) = audioTrackLabel(a)
                        TrackOption(label, sub, a.index == state.audioIndex) { onAudio(a.index) }
                    }
                }
                VerticalDivider()
                TrackColumn(t("Subtitles"), Modifier.weight(1f)) {
                    TrackOption(t("Off"), "", state.subtitleIndex < 0) { onSubtitle(-1) }
                    for (s in state.subtitles) {
                        val (label, sub) = subtitleTrackLabel(s)
                        TrackOption(label, sub, s.index == state.subtitleIndex) { onSubtitle(s.index) }
                    }
                }
            }
            Divider()
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val offset = state.subtitleOffsetMs
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SText(t("Subtitle timing"), size = 13.sp, weight = 700, color = SeedyColors.White)
                    SText(timingNote(offset), size = 11.sp, color = SeedyColors.Text3)
                }
                Row(Modifier.clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Box(Modifier.size(44.dp, 40.dp).clip(RoundedCornerShape(11.dp)).tap { onOffset(offset - 100) }, contentAlignment = Alignment.Center) {
                        SIcon(WebIcons.Minus, size = 18.dp, color = SeedyColors.White)
                    }
                    SText(offsetLabel(offset), Modifier.widthIn(min = 54.dp), size = 14.sp, weight = 800, color = accent, align = androidx.compose.ui.text.style.TextAlign.Center)
                    Box(Modifier.size(44.dp, 40.dp).clip(RoundedCornerShape(11.dp)).tap { onOffset(offset + 100) }, contentAlignment = Alignment.Center) {
                        SIcon(WebIcons.Plus, size = 18.dp, color = SeedyColors.White)
                    }
                }
                SText(t("Reset"), Modifier.clip(RoundedCornerShape(11.dp)).tap { onOffset(0) }.padding(horizontal = 10.dp, vertical = 10.dp), size = 13.sp, weight = 700, color = SeedyColors.Text2)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.height(40.dp).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface).tap(onClick = onStyle).padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SText(t("Subtitle style"), size = 13.sp, weight = 700, color = SeedyColors.White)
                    SIcon(WebIcons.ChevronRight, size = 14.dp, color = SeedyColors.White, stroke = 2.4f)
                }
            }
        }
    }
}

/** "+0.3 s", "−0.5 s", "0 s". */
fun offsetLabel(ms: Long): String {
    val s = ms / 1000.0
    return when {
        ms > 0 -> "+%.1f s".format(s)
        ms < 0 -> "−%.1f s".format(-s)
        else -> "0 s"
    }
}

/** "Shown 0.3 s later", "Shown 0.5 s earlier", "As in the file". */
fun timingNote(ms: Long): String = when {
    ms > 0 -> t("Shown {s} s later", "s" to "%.1f".format(ms / 1000.0))
    ms < 0 -> t("Shown {s} s earlier", "s" to "%.1f".format(-ms / 1000.0))
    else -> t("As in the file")
}

@Composable
private fun TrackColumn(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 10.dp, end = 10.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SText(title, Modifier.padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 6.dp), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        content()
    }
}

@Composable
private fun TrackOption(label: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(11.dp)).background(if (on) SeedyColors.Surface else Color.Transparent).tap(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(WebIcons.Check, Modifier.alpha(if (on) 1f else 0f), size = 18.dp, color = LocalAccent.current, stroke = 2.8f)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else SeedyColors.TextHero, maxLines = 1)
            if (sub.isNotEmpty()) SText(sub, size = 11.sp, weight = 500, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** Board "Android · Player speed": six speeds in one segmented row at the bottom. */
@Composable
fun SpeedPanel(speed: Float, onSpeed: (Float) -> Unit, onClose: () -> Unit) {
    PanelScrim(onClose) {
        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).width(560.dp).panelCard().padding(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                SText(t("Playback speed"), Modifier.weight(1f), size = 15.sp, weight = 800, color = SeedyColors.White)
                Box(Modifier.padding(end = 0.dp)) { PanelClose(onClose) }
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SeedyColors.Surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in Speeds) {
                    val on = s == speed
                    Box(
                        Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(11.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent).tap { onSpeed(s) },
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(speedLabel(s), size = 14.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text)
                    }
                }
            }
            SText(t("Voices keep their normal pitch. Speed resets when the episode ends."), size = 12.sp, color = SeedyColors.Text3)
        }
    }
}

val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
