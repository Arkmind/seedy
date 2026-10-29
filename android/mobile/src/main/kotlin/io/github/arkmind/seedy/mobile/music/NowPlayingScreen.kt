package io.github.arkmind.seedy.mobile.music

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.music.Lyrics
import io.github.arkmind.seedy.core.music.SongFacts
import io.github.arkmind.seedy.core.music.artistsOf
import io.github.arkmind.seedy.core.music.clockOf
import io.github.arkmind.seedy.core.music.longDate
import io.github.arkmind.seedy.core.music.qualityOf
import io.github.arkmind.seedy.core.music.sizeLabel
import io.github.arkmind.seedy.core.music.trackTime
import io.github.arkmind.seedy.mobile.audio.OutputChip
import io.github.arkmind.seedy.mobile.audio.TrackCover
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.audio.AudioState
import io.github.arkmind.seedy.player.audio.AudioTime
import io.github.arkmind.seedy.player.audio.Entry
import io.github.arkmind.seedy.player.audio.Repeat
import io.github.arkmind.seedy.player.audio.SourceKind
import io.github.arkmind.seedy.ui.components.Overlay
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.PersonKind
import kotlin.math.roundToInt

class NowPlayingActions(
    val onClose: () -> Unit = {},
    /** "Playing from …": opens the album, playlist, artist or library. */
    val onSource: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onArtist: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onSeek: (Double) -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onPrevious: () -> Unit = {},
    val onToggle: () -> Unit = {},
    val onNext: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onOutput: () -> Unit = {},
    val onLyrics: () -> Unit = {},
    val onQueue: () -> Unit = {},
)

private val Overlines = mapOf(
    SourceKind.Album to "Playing from album", SourceKind.Playlist to "Playing from playlist", SourceKind.Artist to "Playing from artist",
    SourceKind.Mix to "Playing from mix", SourceKind.Library to "Playing from library", SourceKind.Genre to "Playing from genre",
    SourceKind.Folder to "Playing from folder", SourceKind.Songs to "Playing songs",
)

/**
 * Board "Android · Now playing": "Playing from album · Northline" (tap: the source), the cover, the song and its
 * artist with the heart, the seek bar and its times, Shuffle · Previous · Play · Next · Repeat, the output chip,
 * Lyrics and Queue, and the lyrics' peek at the foot. The backdrop takes the cover's colour; a swipe down closes it.
 */
@Composable
fun NowPlayingScreen(state: AudioState, time: AudioTime, lyrics: Lyrics, output: String, tint: Color, actions: NowPlayingActions) {
    val tk = state.current?.track ?: return
    val accent = LocalAccent.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val drag = remember { Animatable(0f) }
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        Modifier.fillMaxSize().offset { IntOffset(0, drag.value.roundToInt()) }.background(Color(0xFF121212))
            .cssGradient(180f, listOf(tint, Color(0xFF121212), Color(0xFF121212)), listOf(0f, 0.72f, 1f))
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (drag.value > with(density) { 120.dp.toPx() }) actions.onClose()
                        else scope.launch { drag.animateTo(0f) }
                    },
                    onDragCancel = { scope.launch { drag.animateTo(0f) } },
                ) { _, dy -> scope.launch { drag.snapTo((drag.value + dy).coerceAtLeast(0f)) } }
            },
    ) {
        // The cover takes what the rest leaves (342 px on the board's 390 × 844).
        val cover = minOf(maxWidth - 48.dp, maxHeight - 502.dp - nav).coerceAtLeast(160.dp)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton44(MusicIcons.Down, actions.onClose)
                Column(Modifier.weight(1f).tap(onClick = actions.onSource), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    val src = state.source
                    SText(t(src?.let { Overlines[it.kind] } ?: "Now playing"), size = 11.sp, weight = 800, tracking = 0.1f, uppercase = true, color = Color(0xB3FFFFFF), maxLines = 1)
                    SText(src?.name ?: tk.album.orEmpty(), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                }
                IconButton44(MusicIcons.More, actions.onMore)
            }
            SongCover(
                tk, Modifier.padding(top = 16.dp).size(cover).align(Alignment.CenterHorizontally)
                    .boxShadow(Color(0x80000000), blur = 60.dp, y = 24.dp, radius = 16.dp).clip(RoundedCornerShape(16.dp)),
                width = 720, title = tk.album ?: tk.name, titleSize = 34.sp,
            )
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(tk.name.orEmpty(), size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
                    SText(artistsOf(tk), Modifier.tap(onClick = actions.onArtist), size = 15.sp, color = SeedyColors.TextHero, maxLines = 1)
                }
                val fav = tk.userData?.isFavorite == true
                IconButton44(if (fav) MusicIcons.HeartOn else MusicIcons.Heart, actions.onFavorite, size = 24.dp, color = if (fav) accent else SeedyColors.TextHero, box = 48.dp)
            }
            SeekBar(time, actions.onSeek, Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton44(MusicIcons.Shuffle, actions.onShuffle, color = if (state.shuffle) accent else SeedyColors.TextHero, box = 48.dp)
                IconButton44(MusicIcons.Previous, actions.onPrevious, size = 28.dp, box = 56.dp)
                Box(Modifier.size(76.dp).clip(RoundedCornerShape(23.dp)).background(SeedyColors.Inverse).tap(onClick = actions.onToggle), contentAlignment = Alignment.Center) {
                    SIcon(if (state.playing) MusicIcons.Pause else MusicIcons.Play, size = 30.dp, color = SeedyColors.InkInverse)
                }
                IconButton44(MusicIcons.Next, actions.onNext, size = 28.dp, box = 56.dp)
                Box(contentAlignment = Alignment.Center) {
                    IconButton44(MusicIcons.Repeat, actions.onRepeat, color = if (state.repeat != Repeat.Off) accent else SeedyColors.TextHero, box = 48.dp)
                    if (state.repeat == Repeat.One) {
                        SText("1", Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = 6.dp).clip(RoundedCornerShape(5.dp)).background(accent).padding(horizontal = 3.dp), size = 9.sp, weight = 900, color = SeedyColors.InkInverse)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutputChip(output, actions.onOutput)
                Spacer(Modifier.weight(1f))
                IconButton44(MusicIcons.Lyrics, actions.onLyrics, size = 21.dp, color = SeedyColors.Text)
                IconButton44(MusicIcons.Queue, actions.onQueue, size = 21.dp, color = SeedyColors.Text)
            }
        }
        if (!lyrics.lines.isNullOrEmpty()) LyricsPeek(lyrics, time.position, nav, actions.onLyrics, Modifier.align(Alignment.BottomCenter))
    }
}

/** The board's seek bar: a 4 px track, the white fill and a 6 × 16 thumb; the times under it. Seeks on release. */
@Composable
private fun SeekBar(time: AudioTime, onSeek: (Double) -> Unit, modifier: Modifier = Modifier) {
    var held by remember { mutableStateOf<Float?>(null) }
    val dur = time.duration
    val f = held ?: time.fraction
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(18.dp)
                .pointerInput(dur) {
                    detectTapGestures { o -> if (dur > 0) onSeek((o.x / size.width).coerceIn(0f, 1f) * dur) }
                }
                .pointerInput(dur) {
                    detectHorizontalDragGestures(
                        onDragStart = { o -> held = (o.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { held?.let { if (dur > 0) onSeek(it * dur) }; held = null },
                        onDragCancel = { held = null },
                    ) { change, _ -> held = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val w = maxWidth
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x33FFFFFF))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(2.dp)).background(SeedyColors.White))
            }
            Box(Modifier.offset(x = w * f - 3.dp).size(6.dp, 16.dp).clip(RoundedCornerShape(3.dp)).background(SeedyColors.White))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SText(clockOf(held?.let { it * dur } ?: time.position), size = 12.sp, weight = 600, color = SeedyColors.Text2)
            SText(clockOf(dur), size = 12.sp, weight = 600, color = SeedyColors.Text2)
        }
    }
}

/** The lyrics' peek at the player's foot: the line sung now and the next one; tapping opens them all. */
@Composable
private fun LyricsPeek(lyrics: Lyrics, position: Double, nav: Dp, onOpen: () -> Unit, modifier: Modifier) {
    val lines = lyrics.lines.orEmpty().filter { it.text.isNotBlank() || it.start != null }
    val cur = lyrics.currentAt(position)
    val first = if (lyrics.synced) lines.getOrNull(cur.coerceAtLeast(0)) else lines.firstOrNull()
    val second = if (lyrics.synced) lines.getOrNull(cur.coerceAtLeast(0) + 1) else lines.getOrNull(1)
    Column(
        modifier.padding(horizontal = 12.dp).fillMaxWidth().height(104.dp + nav)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)).background(Color(0x14FFFFFF)).tap(onClick = onOpen)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SText(t("Lyrics"), size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = Color(0xB3FFFFFF), maxLines = 1)
        SText(first?.text?.ifBlank { "♪" } ?: "", size = 20.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        if (second != null) SText(second.text.ifBlank { "♪" }, size = 17.sp, weight = 700, color = Color(0x80FFFFFF), maxLines = 1)
    }
}

/* ── Lyrics ── */

/** All the lyrics over the player: synced lines follow the song (tap one to go there), the others read top to bottom. */
@Composable
fun LyricsPanel(title: String, sub: String, lyrics: Lyrics, position: Double, tint: Color, onSeek: (Double) -> Unit, onClose: () -> Unit) = Overlay {
    BackHandler(onBack = onClose)
    val list = rememberLazyListState()
    val cur = lyrics.currentAt(position)
    var hold by remember { mutableStateOf(0L) }
    LaunchedEffect(cur) { if (cur >= 0 && System.currentTimeMillis() > hold) list.animateScrollToItem((cur - 2).coerceAtLeast(0)) }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF121212)).cssGradient(180f, listOf(tint, Color(0xFF121212), Color(0xFF121212)), listOf(0f, 0.72f, 1f)).tap {},
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton44(MusicIcons.Down, onClose)
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SText(t("Lyrics"), size = 11.sp, weight = 800, tracking = 0.1f, uppercase = true, color = Color(0xB3FFFFFF), maxLines = 1)
                SText(sub.ifEmpty { title }, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            }
            Spacer(Modifier.size(44.dp))
        }
        val lines = lyrics.lines
        when {
            lines == null -> Spacer(Modifier.weight(1f))
            lines.isEmpty() -> Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(t("No lyrics for this song"), size = 24.sp, weight = 800, lineHeight = 1.25f, color = Color(0x8CFFFFFF))
                SText(t("Lyrics show here when the server has them, from a .lrc file next to the song or its tags."), size = 14.sp, lineHeight = 1.5f, color = SeedyColors.Text2)
            }
            else -> {
                if (lyrics.synced) SText(t("Synced · tap a line to jump"), Modifier.padding(start = 24.dp, top = 8.dp), size = 12.sp, weight = 700, color = SeedyColors.Text3)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { detectDragGestures { _, _ -> hold = System.currentTimeMillis() + 4000 } },
                    state = list,
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(lines) { i, l ->
                        val color = when {
                            !lyrics.synced -> Color(0xCCFFFFFF)
                            i == cur -> SeedyColors.White
                            i < cur -> Color(0x59FFFFFF)
                            else -> Color(0x8CFFFFFF)
                        }
                        SText(
                            l.text.ifBlank { "♪" },
                            Modifier.then(if (l.start != null) Modifier.tap { hold = 0; onSeek(l.start!!) } else Modifier),
                            size = if (i == cur) 28.sp else 24.sp, weight = 800, lineHeight = 1.25f, tracking = -0.01f, color = color,
                        )
                    }
                }
            }
        }
    }
}

/* ── Up next ── */

private val RowStep = 60.dp

/**
 * Up next (web UpNext): the songs added by you, then the rest of the source; tap one to play it, × to take it out,
 * drag the handle to move it; Clear queue.
 */
@Composable
fun QueueSheet(state: AudioState, onJump: (String) -> Unit, onRemove: (String) -> Unit, onMove: (Boolean, Int, Int) -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    val added = state.added
    val rest = state.context.drop(state.pos + 1).take(200)
    val empty = added.isEmpty() && rest.isEmpty()
    val from = state.source?.name ?: state.current?.track?.album.orEmpty()
    BottomSheet(onDismiss) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(t("Up next"), Modifier.weight(1f), size = 17.sp, weight = 800, color = SeedyColors.White)
            if (!empty) SText(t("Clear queue"), Modifier.tap(onClick = onClear), size = 13.sp, weight = 700, color = SeedyColors.Text2)
        }
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            if (empty) SText(t("Nothing else is queued. Pick Play next or Add to queue on any song."), Modifier.padding(12.dp), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
            if (added.isNotEmpty()) {
                QueueLabel(t("Added by you"))
                QueueList(added, true, onJump, onRemove, onMove)
            }
            if (rest.isNotEmpty()) {
                QueueLabel(if (from.isNotEmpty()) t("Next from {name}", "name" to from) else t("Next up"), top = if (added.isNotEmpty()) 14.dp else 4.dp)
                QueueList(rest, false, onJump, onRemove, onMove)
            }
        }
    }
}

@Composable
private fun QueueLabel(text: String, top: Dp = 4.dp) {
    SText(text, Modifier.padding(start = 12.dp, end = 12.dp, top = top, bottom = 6.dp), size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.Text3, maxLines = 1)
}

@Composable
private fun QueueList(entries: List<Entry>, added: Boolean, onJump: (String) -> Unit, onRemove: (String) -> Unit, onMove: (Boolean, Int, Int) -> Unit) {
    val density = LocalDensity.current
    val step = with(density) { RowStep.toPx() }
    var from by remember { mutableStateOf(-1) }
    var dy by remember { mutableFloatStateOf(0f) }
    val to = if (from >= 0) (from + (dy / step).roundToInt()).coerceIn(0, entries.size - 1) else -1
    Column {
        entries.forEachIndexed { i, e ->
            val shift = when {
                from < 0 -> 0f
                i == from -> dy
                from < to && i in (from + 1)..to -> -step
                from > to && i in to until from -> step
                else -> 0f
            }
            Row(
                Modifier.fillMaxWidth().height(RowStep).graphicsLayer { translationY = shift }
                    .then(if (i == from) Modifier.clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface) else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).tap { onJump(e.key) }.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TrackCover(e.track, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), width = 88)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        SText(e.track.name.orEmpty(), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                        SText(artistsOf(e.track), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                    }
                    if (!added) SText(trackTime(e.track), size = 12.sp, weight = 600, color = SeedyColors.Text3)
                }
                QueueButton(MusicIcons.Close, 14.dp) { onRemove(e.key) }
                Box(
                    Modifier.size(40.dp).pointerInput(entries.size, i) {
                        detectVerticalDragGestures(
                            onDragStart = { from = i; dy = 0f },
                            onDragEnd = {
                                val target = (i + (dy / step).roundToInt()).coerceIn(0, entries.size - 1)
                                from = -1; dy = 0f
                                if (target != i) onMove(added, i, target)
                            },
                            onDragCancel = { from = -1; dy = 0f },
                        ) { change, d -> change.consume(); dy += d }
                    },
                    contentAlignment = Alignment.Center,
                ) { SIcon(MusicIcons.Handle, size = 18.dp, color = SeedyColors.Text2) }
            }
        }
    }
}

@Composable
private fun QueueButton(icon: IconDef, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = size, color = SeedyColors.Text2)
    }
}

/* ── About the song ── */

/** About the song (web AboutSong): the album, track, release, genres, writers, plays, length, format and size. */
@Composable
fun AboutSongSheet(facts: SongFacts?, onAlbum: () -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        val it = facts?.track
        val al = facts?.album
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (it == null) {
                Skeleton(Modifier.fillMaxWidth(0.6f).height(22.dp))
                Skeleton(Modifier.fillMaxWidth(0.4f).height(14.dp))
                return@Column
            }
            SText(it.name.orEmpty(), size = 20.sp, weight = 900, color = SeedyColors.White)
            SText(listOfNotNull(artistsOf(it).ifEmpty { null }, al?.name).joinToString(" · "), size = 14.sp, color = SeedyColors.Text2)
            (it.overview ?: al?.overview)?.takeIf { o -> o.isNotBlank() }?.let { o -> SText(o, Modifier.padding(top = 6.dp), size = 14.sp, lineHeight = 1.5f, color = SeedyColors.TextHero) }
            val people = { k: PersonKind -> it.people.orEmpty().filter { p -> p.type == k }.mapNotNull { p -> p.name }.joinToString(", ") }
            val released = al?.premiereDate?.toLocalDate()?.let(::longDate) ?: it.productionYear?.toString()
            val size = it.mediaSources?.firstOrNull()?.size
            val rows = listOf(
                t("Album") to it.album,
                t("Track") to it.indexNumber?.let { n -> if ((it.parentIndexNumber ?: 1) > 1) t("Disc {d}, track {n}", "d" to it.parentIndexNumber, "n" to n) else t("Track {n}", "n" to n) },
                t("Released") to released,
                t("Genres") to (it.genres?.takeIf { g -> g.isNotEmpty() } ?: al?.genres).orEmpty().joinToString(", ").ifEmpty { null },
                t("Written by") to (people(PersonKind.COMPOSER).ifEmpty { people(PersonKind.LYRICIST) }).ifEmpty { null },
                t("Plays") to (it.userData?.playCount?.takeIf { n -> n > 0 }?.let { n -> tn(n, "{n} play", "{n} plays") } ?: t("Not played yet")),
                t("Length") to trackTime(it).ifEmpty { null },
                t("Format") to qualityOf(it).ifEmpty { null },
                t("File size") to size?.takeIf { s -> s > 0 }?.let(::sizeLabel),
            ).filter { r -> !r.second.isNullOrEmpty() }
            Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((k, v) in rows) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SText(k, Modifier.width(96.dp), size = 13.sp, weight = 600, color = SeedyColors.Text3)
                        SText(
                            v!!, Modifier.weight(1f).then(if (k == t("Album") && it.albumId != null) Modifier.tap(onClick = onAlbum) else Modifier),
                            size = 13.sp, weight = 600, color = SeedyColors.White, align = TextAlign.Start,
                        )
                    }
                }
            }
        }
    }
}
