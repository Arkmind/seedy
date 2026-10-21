package io.github.arkmind.seedy.tv.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import io.github.arkmind.seedy.core.format.audioTrackLabel
import io.github.arkmind.seedy.core.format.clock
import io.github.arkmind.seedy.core.format.episodeMeta
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.core.format.subtitleTrackLabel
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.player.PlayerState
import io.github.arkmind.seedy.player.Quality
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayMethod
import java.time.LocalDate
import java.util.UUID

private val PanelBg = Color(0xF7141414)
private val GapColor = Color(0xFF141414)

/** The dimmed video behind a panel. */
@Composable
private fun Scrim(content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
        content()
    }
}

/** A panel along the right edge (boards: 900 px for tracks, 680 px for settings) with its title and "Back closes". */
@Composable
private fun SidePanel(width: Int, title: String, headerGap: Int = 26, content: @Composable ColumnScope.() -> Unit) {
    Scrim {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(width.bp)
                .clip(RoundedCornerShape(topStart = 32.bp, bottomStart = 32.bp)).background(PanelBg)
                .padding(start = 48.bp, end = 48.bp, top = 54.bp, bottom = 48.bp),
        ) {
            Row(Modifier.fillMaxWidth().padding(bottom = headerGap.bp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                SText(title, size = 30.bsp, weight = 900, color = SeedyColors.White, maxLines = 1)
                SText(t("Back closes"), size = 17.bsp, color = SeedyColors.Text3)
            }
            content()
        }
    }
}

/** A choice in a panel list: a check in the accent when chosen; light with dark text when the remote is on it. */
@Composable
private fun Choice(label: String, sub: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    TvPressable(onClick, modifier) { focused ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.bp).focusRing(focused, radius = 16.bp, gap = 4.bp, ring = 4.bp, gapColor = GapColor)
                .clip(RoundedCornerShape(16.bp))
                .background(if (focused) SeedyColors.Inverse else if (on) SeedyColors.Surface else Color.Transparent)
                .padding(horizontal = 18.bp, vertical = 8.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.bp),
        ) {
            SIcon(WebIcons.Check, Modifier.alpha(if (on) 1f else 0f), size = 24.bp, color = if (focused) SeedyColors.InkInverse else accent, stroke = 2.8f)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(
                    label, size = 20.bsp, weight = if (on || focused) 800 else 600, maxLines = 1,
                    color = if (focused) SeedyColors.InkInverse else if (on) SeedyColors.White else SeedyColors.TextHero,
                )
                if (sub.isNotEmpty()) SText(sub, size = 15.bsp, color = if (focused) Color(0xFF4A4A4A) else SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** Board "Android TV · Player subtitles and audio": the two lists, then subtitle timing (left and right adjust it). */
@Composable
fun TvTracksPanel(
    state: PlayerState,
    startOnAudio: Boolean,
    onAudio: (Int) -> Unit,
    onSubtitle: (Int) -> Unit,
    onOffset: (Long) -> Unit,
) {
    val first = remember { FocusRequester() }
    SidePanel(900, t("Subtitles and audio"), headerGap = 20 - 8) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(28.bp)) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.bp), contentPadding = PaddingValues(8.bp)) {
                item { ColumnTitle(t("Audio")) }
                items(state.audio, key = { it.index }) { a ->
                    val (label, sub) = audioTrackLabel(a)
                    val on = a.index == state.audioIndex
                    Choice(label, sub, on, { onAudio(a.index) }, if (on && startOnAudio) Modifier.focusRequester(first) else Modifier)
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.bp), contentPadding = PaddingValues(8.bp)) {
                item { ColumnTitle(t("Subtitles")) }
                item(key = "off") {
                    val on = state.subtitleIndex < 0
                    Choice(t("Off"), "", on, { onSubtitle(-1) }, if (on && !startOnAudio) Modifier.focusRequester(first) else Modifier)
                }
                items(state.subtitles, key = { it.index }) { s ->
                    val (label, sub) = subtitleTrackLabel(s)
                    val on = s.index == state.subtitleIndex
                    Choice(label, sub, on, { onSubtitle(s.index) }, if (on && !startOnAudio) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        Timing(state.subtitleOffsetMs, onOffset)
    }
    LaunchedEffect(Unit) { first.focusSoon() }
}

@Composable
private fun ColumnTitle(text: String) {
    SText(text, Modifier.padding(start = 18.bp, end = 18.bp, bottom = 8.bp), size = 15.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

@Composable
private fun Timing(offset: Long, onOffset: (Long) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.bp)).background(SeedyColors.Row).padding(24.bp), verticalArrangement = Arrangement.spacedBy(14.bp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(t("Subtitle timing"), size = 21.bsp, weight = 800, color = SeedyColors.White)
                SText(timingSentence(offset), size = 16.bsp, color = SeedyColors.Text3)
            }
            val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
            Row(
                Modifier.focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 4.bp, gapColor = GapColor)
                    .clip(RoundedCornerShape(18.bp)).background(if (focused) SeedyColors.Inverse else SeedyColors.Surface2)
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (e.key) {
                            Key.DirectionLeft -> { onOffset(offset - 100); true }
                            Key.DirectionRight -> { onOffset(offset + 100); true }
                            else -> false
                        }
                    }
                    .focusable(interactionSource = interaction).padding(6.bp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.bp),
            ) {
                Box(Modifier.size(52.bp).clip(RoundedCornerShape(13.bp)).background(Color.Black.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    SIcon(WebIcons.Minus, size = 24.bp, color = ink, stroke = 2.6f)
                }
                SText(offsetText(offset), Modifier.widthIn(min = 92.bp), size = 24.bsp, weight = 900, color = ink, align = TextAlign.Center)
                Box(Modifier.size(52.bp).clip(RoundedCornerShape(13.bp)).background(Color.Black.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                    SIcon(WebIcons.Plus, size = 24.bp, color = ink, stroke = 2.6f)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            SText(t("Left and right move 0.1 s · hold to move faster"), size = 17.bsp, color = SeedyColors.Text3)
            TvPressable({ onOffset(0) }) { f ->
                Box(
                    Modifier.height(48.bp).focusRing(f, radius = 14.bp, gap = 4.bp, ring = 4.bp, gapColor = SeedyColors.Row)
                        .clip(RoundedCornerShape(14.bp)).background(if (f) SeedyColors.Inverse else Color.White.copy(alpha = 0.08f)).padding(horizontal = 18.bp),
                    contentAlignment = Alignment.Center,
                ) { SText(t("Reset to 0"), size = 17.bsp, weight = 700, color = if (f) SeedyColors.InkInverse else SeedyColors.White) }
            }
        }
    }
}

private fun offsetText(ms: Long): String = when {
    ms > 0 -> "+%.1f s".format(ms / 1000.0)
    ms < 0 -> "−%.1f s".format(-ms / 1000.0)
    else -> "0 s"
}

private fun timingSentence(ms: Long): String = when {
    ms > 0 -> t("Subtitles show {s} s later than the file says", "s" to "%.1f".format(ms / 1000.0))
    ms < 0 -> t("Subtitles show {s} s earlier than the file says", "s" to "%.1f".format(-ms / 1000.0))
    else -> t("Subtitles show when the file says")
}

/**
 * Board "Android TV · Player episodes": the show's seasons as tabs and the season's episodes in a row; the one the
 * remote is on grows and shows its summary.
 */
@Composable
fun TvEpisodesPanel(
    state: PlayerState,
    seasonId: UUID?,
    episodes: List<BaseItemDto>,
    onSeason: (BaseItemDto) -> Unit,
    onEpisode: (BaseItemDto) -> Unit,
) {
    val viewer = LocalViewer.current
    val accent = LocalAccent.current
    val current = remember { FocusRequester() }
    val first = remember { FocusRequester() }
    // While the next episode loads the list can empty for a moment: keep showing the last one, so the remote stays put.
    val last = remember { object { var list = episodes } }
    val shown = if (episodes.isNotEmpty()) episodes.also { last.list = it } else last.list
    var inside by remember { mutableStateOf(false) }
    val seasons = rememberLazyListState()
    Scrim {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 32.bp, topEnd = 32.bp)).background(PanelBg)
                .onFocusChanged { inside = it.hasFocus }
                // The season row keeps RingRoom around its tabs for the focus ring: taken from the padding and the gap.
                .padding(start = 96.bp, end = 0.bp, top = 40.bp - RingRoom, bottom = 54.bp),
            verticalArrangement = Arrangement.spacedBy(26.bp - RingRoom),
        ) {
            Row(Modifier.fillMaxWidth().padding(end = 96.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.bp - RingRoom)) {
                SText(state.item?.seriesName.orEmpty(), Modifier.widthIn(max = 560.bp), size = 30.bsp, weight = 900, color = SeedyColors.White, maxLines = 1)
                // A long-running show has more seasons than the bar holds: they scroll, and the hint keeps its line.
                LazyRow(Modifier.weight(1f), state = seasons, horizontalArrangement = Arrangement.spacedBy(10.bp), contentPadding = PaddingValues(RingRoom)) {
                    items(state.seasons, key = { it.id }) { s ->
                        val on = s.id == seasonId
                        TvPressable({ onSeason(s) }) { f ->
                            Box(
                                Modifier.height(52.bp).focusRing(f, radius = 15.bp, gap = 4.bp, ring = 4.bp, gapColor = GapColor).clip(RoundedCornerShape(15.bp))
                                    .background(if (f) SeedyColors.Inverse else Color.White.copy(alpha = if (on) 0.18f else 0.06f)).padding(horizontal = 22.bp),
                                contentAlignment = Alignment.Center,
                            ) { SText(s.name.orEmpty(), size = 19.bsp, weight = if (on || f) 800 else 600, color = if (f) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1) }
                        }
                    }
                }
                SText(t("Up for seasons · Back closes"), Modifier.padding(start = RingRoom), size = 17.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
            // Room on the left too, so the first card's ring isn't cut.
            LazyRow(
                Modifier.offset(x = -RingRoom),
                horizontalArrangement = Arrangement.spacedBy(28.bp),
                contentPadding = PaddingValues(start = RingRoom, top = 10.bp, bottom = 10.bp, end = 96.bp),
                verticalAlignment = Alignment.Top,
            ) {
                itemsIndexed(shown, key = { _, e -> e.id }) { i, e ->
                    val playing = e.id == state.item?.id
                    val future = e.premiereDate?.toLocalDate()?.isAfter(LocalDate.now()) == true
                    val requesters = (if (playing) Modifier.focusRequester(current) else Modifier).then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                    TvPressable({ onEpisode(e) }, requesters) { f ->
                        val w by animateDpAsState(if (f) 380.bp else 330.bp, label = "episode")
                        // The playing episode shows where playback is now, not where its last report left it.
                        val progress = if (playing && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else e.progress
                        Column(Modifier.width(w), verticalArrangement = Arrangement.spacedBy(12.bp)) {
                            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(f, radius = 18.bp, gap = 4.bp, ring = 5.bp, gapColor = GapColor).clip(RoundedCornerShape(18.bp)).alpha(if (future) 0.35f else 1f)) {
                                Artwork(viewer.images?.landscape(e, 640, viewer.episodeImages), Modifier.matchParentSize())
                                if (playing) {
                                    SText(
                                        t("Playing"), Modifier.padding(12.bp).clip(RoundedCornerShape(9.bp)).background(SeedyColors.Scrim).padding(horizontal = 11.bp, vertical = 4.bp),
                                        size = 15.bsp, weight = 800, color = accent, uppercase = true,
                                    )
                                }
                                if (progress > 0f && (playing || e.userData?.played != true)) {
                                    Box(Modifier.align(Alignment.BottomCenter).padding(12.bp).fillMaxWidth().height(5.bp).clip(RoundedCornerShape(3.bp)).background(Color.White.copy(alpha = 0.24f))) {
                                        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Color.White))
                                    }
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(3.bp)) {
                                SText("${e.indexNumber ?: ""} · ${e.name.orEmpty()}", size = 19.bsp, weight = 700, color = if (future) SeedyColors.Text2 else SeedyColors.White, maxLines = 1)
                                val meta = when {
                                    playing && state.durationMs > 0 -> join(episodeMeta(e).substringBefore(" · "), timeLeft((state.durationMs - state.positionMs).coerceAtLeast(0) / 1000.0))
                                    e.userData?.played == true -> "${episodeMeta(e).substringBefore(" · ")} · ${t("Watched")}"
                                    else -> episodeMeta(e)
                                }
                                SText(meta, size = 15.bsp, color = SeedyColors.Text3, maxLines = 1)
                                if (f) e.overview?.let { SText(it, Modifier.padding(top = 4.bp), size = 16.bsp, lineHeight = 1.45f, color = SeedyColors.TextHero, maxLines = 3) }
                            }
                        }
                    }
                }
            }
        }
    }
    // The current season's tab in view.
    LaunchedEffect(seasonId, state.seasons.size) {
        val i = state.seasons.indexOfFirst { it.id == seasonId }
        if (i > 0) seasons.scrollToItem(i - 1)
    }
    // On the playing episode when the panel opens, and back on it whenever the remote has lost its place (the next
    // episode started and the list reloaded under it).
    val ids = shown.map { it.id }
    LaunchedEffect(state.item?.id, ids) {
        withFrameNanos { }
        if (!inside && !current.focusSoon()) first.focusSoon()
    }
}

/** Room kept around a row of focusable tabs so the focus ring (8 px outside) isn't clipped. */
private val RingRoom = 9.bp

/** The settings panel's pages. */
enum class SettingsPage { Main, Quality, Speed, Picture, Chapters }

/** How the picture fills the screen (Playback settings › Picture size). */
enum class PictureSize(val label: String, val scale: ContentScale) {
    Fit("Fit", ContentScale.Fit),
    Fill("Fill", ContentScale.Crop),
    Stretch("Stretch", ContentScale.FillBounds),
}

private object SettingsIcons {
    val Quality = IconDef(2f, "M3 6h18v12H3z", "M7 10v4M11 10v4M15 10h2v4h-2")
    val Speed = IconDef(2f, "M12 21a9 9 0 1 1 9-9", "M12 12l4-4")
    val Picture = IconDef(2f, "M4 9V4h5M20 9V4h-5", "M4 15v5h5M20 15v5h-5")
    val Chapters = IconDef(2f, "M9 6h11M9 12h11M9 18h11", "M4 6h.01M4 12h.01M4 18h.01")
    val Info = IconDef(2f, "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z", "M12 11v5M12 8h.01")
}

/** Board "Android TV · Player settings": quality, speed, picture size, chapters and playback info, each with its page. */
@Composable
fun TvSettingsPanel(
    state: PlayerState,
    page: SettingsPage,
    picture: PictureSize,
    info: Boolean,
    onPage: (SettingsPage) -> Unit,
    onQuality: (Quality) -> Unit,
    onSpeed: (Float) -> Unit,
    onPicture: (PictureSize) -> Unit,
    onChapter: (Long) -> Unit,
    onInfo: (Boolean) -> Unit,
) {
    val first = remember(page) { FocusRequester() }
    val chapters = state.item?.chapters.orEmpty()
    val currentChapter = chapters.lastOrNull { it.startPositionTicks / 10_000 <= state.positionMs }
    when (page) {
        SettingsPage.Main -> SidePanel(680, t("Playback settings")) {
            Column(verticalArrangement = Arrangement.spacedBy(10.bp)) {
                val method = when (state.method) {
                    PlayMethod.DIRECT_PLAY -> t("Direct play from the server")
                    PlayMethod.DIRECT_STREAM -> t("Repackaged by the server")
                    PlayMethod.TRANSCODE -> t("Converted by the server")
                    null -> ""
                }
                val resolution = state.streamLabel.split(" · ").getOrNull(1).orEmpty()
                val quality = if (state.quality == Quality.Auto) listOf(t("Original"), resolution.substringBefore(' ')).filter { it.isNotBlank() }.joinToString(" · ") else t(state.quality.label)
                SettingRow(SettingsIcons.Quality, t("Quality"), method, quality, Modifier.focusRequester(first)) { onPage(SettingsPage.Quality) }
                SettingRow(SettingsIcons.Speed, t("Speed"), t("Voices keep their normal pitch"), speedText(state.speed)) { onPage(SettingsPage.Speed) }
                SettingRow(SettingsIcons.Picture, t("Picture size"), t("Fit the whole picture on screen").takeIf { picture == PictureSize.Fit } ?: t("Fill the screen, cropping the edges").takeIf { picture == PictureSize.Fill } ?: t("Stretch the picture to the screen"), t(picture.label)) { onPage(SettingsPage.Picture) }
                if (chapters.isNotEmpty()) {
                    SettingRow(SettingsIcons.Chapters, t("Chapters"), tn(chapters.size, "{n} chapter", "{n} chapters"), currentChapter?.name.orEmpty()) { onPage(SettingsPage.Chapters) }
                }
                SettingRow(SettingsIcons.Info, t("Playback info"), t("Bitrate, codecs and why it transcodes"), if (info) t("On") else t("Off")) { onInfo(!info) }
            }
        }
        SettingsPage.Quality -> SidePanel(680, t("Quality"), headerGap = 26 - 8) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.bp), contentPadding = PaddingValues(8.bp)) {
                items(Quality.entries) { q ->
                    val on = q == state.quality
                    Choice(t(q.label), if (q == Quality.Auto) t("Plays the file as it is when it can") else "", on, { onQuality(q) }, if (on) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        SettingsPage.Speed -> SidePanel(680, t("Speed"), headerGap = 26 - 8) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.bp), contentPadding = PaddingValues(8.bp)) {
                items(TvSpeeds) { s ->
                    val on = s == state.speed
                    Choice(speedText(s), "", on, { onSpeed(s) }, if (on) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        SettingsPage.Picture -> SidePanel(680, t("Picture size"), headerGap = 26 - 8) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.bp), contentPadding = PaddingValues(8.bp)) {
                items(PictureSize.entries) { p ->
                    val on = p == picture
                    Choice(t(p.label), "", on, { onPicture(p) }, if (on) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
        SettingsPage.Chapters -> SidePanel(680, t("Chapters"), headerGap = 26 - 8) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.bp), contentPadding = PaddingValues(8.bp)) {
                items(chapters) { c ->
                    val on = c == currentChapter
                    Choice(c.name ?: clock(c.startPositionTicks / 10_000), clock(c.startPositionTicks / 10_000), on, { onChapter(c.startPositionTicks / 10_000) }, if (on) Modifier.focusRequester(first) else Modifier)
                }
            }
        }
    }
    LaunchedEffect(page) { first.focusSoon() }
}

val TvSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun speedText(speed: Float): String = (if (speed % 1f == 0f) speed.toInt().toString() else speed.toString().trimEnd('0')) + "×"

@Composable
private fun SettingRow(icon: IconDef, label: String, sub: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TvPressable(onClick, modifier) { f ->
        val ink = if (f) SeedyColors.InkInverse else SeedyColors.White
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.bp).focusRing(f, radius = 18.bp, gap = 4.bp, ring = 4.bp, gapColor = GapColor)
                .clip(RoundedCornerShape(18.bp)).background(if (f) SeedyColors.Inverse else Color.Transparent).padding(horizontal = 22.bp, vertical = 10.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.bp),
        ) {
            SIcon(icon, size = 28.bp, color = ink)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(label, size = 21.bsp, weight = 800, color = ink, maxLines = 1)
                SText(sub, size = 16.bsp, color = if (f) Color(0xFF4A4A4A) else SeedyColors.Text3, maxLines = 1)
            }
            SText(value, size = 19.bsp, weight = 700, color = if (f) SeedyColors.InkInverse else SeedyColors.TextHero, maxLines = 1)
            SIcon(WebIcons.ChevronRight, Modifier.alpha(0.6f), size = 22.bp, color = ink, stroke = 2.4f)
        }
    }
}

/** Playback settings › Playback info: what's playing and how, over the picture's top left. */
@Composable
fun TvPlaybackInfo(state: PlayerState, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(start = 96.bp, top = 140.bp).clip(RoundedCornerShape(18.bp)).background(SeedyColors.Scrim).padding(24.bp),
        verticalArrangement = Arrangement.spacedBy(6.bp),
    ) {
        val audio = state.audio.firstOrNull { it.index == state.audioIndex }
        val lines = listOfNotNull(
            t("Stream") to state.streamLabel,
            audio?.let { t("Audio") to listOfNotNull(it.codec?.uppercase(), it.channelLayout, it.bitRate?.let { b -> "${b / 1000} kbps" }).joinToString(" · ") },
            state.subtitles.firstOrNull { it.index == state.subtitleIndex }?.let { t("Subtitles") to (it.codec?.uppercase() ?: "") },
            t("Position") to "${clock(state.positionMs)} / ${clock(state.durationMs)}",
        )
        for ((k, v) in lines) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.bp)) {
                SText(k, Modifier.width(140.bp), size = 17.bsp, weight = 700, color = SeedyColors.Text2)
                SText(v, size = 17.bsp, color = SeedyColors.White)
            }
        }
    }
}
