package io.github.arkmind.seedy.mobile.music

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.music.ArtistViewModel
import io.github.arkmind.seedy.core.music.Release
import io.github.arkmind.seedy.core.music.albumArtistOf
import io.github.arkmind.seedy.core.music.dot
import io.github.arkmind.seedy.core.music.playsLabel
import io.github.arkmind.seedy.core.music.releaseLabel
import io.github.arkmind.seedy.core.music.releaseOf
import io.github.arkmind.seedy.core.music.yearOf
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

class ArtistActions(
    val onBack: () -> Unit = {},
    val onMore: () -> Unit = {},
    /** Play, or pause/resume when the queue plays from them. */
    val onPlay: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onRadio: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    /** A Popular song: plays the five from it. */
    val onSong: (Int) -> Unit = {},
    val onSongMenu: (BaseItemDto) -> Unit = {},
    /** An album, a video or a similar artist. */
    val onOpen: (BaseItemDto) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * Board "Android · Artist": the name over their art, "1,240 plays from you · 4 albums on the server"; Play, Shuffle,
 * Radio and favourite; Popular; their albums (singles and EPs after), albums they appear on, music videos; About;
 * similar artists on the server.
 */
@Composable
fun ArtistScreen(
    state: ArtistViewModel.State,
    playing: Playing,
    /** The hero's colours (the drawn palette nearest their latest cover). */
    palette: List<Color>,
    actions: ArtistActions,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
) {
    val item = state.item
    val accent = LocalAccent.current
    var allAlbums by rememberSaveable { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item(key = "hero") {
            Box(Modifier.fillMaxWidth().height(330.dp).clipToBounds()) {
                if (item != null) ArtistArt(item, palette, Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().cssGradient(
                        0f, listOf(SeedyColors.Bg, Color(0x330E0E0E), Color(0x73000000)), listOf(0f, 0.55f, 1f),
                    ),
                )
                MusicHeader(null, actions.onBack, top = topInset()) { IconButton44(MusicIcons.More, actions.onMore) }
                if (item != null) {
                    Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val name = item.name.orEmpty()
                        SText(
                            name, size = if (name.length > 22) 30.sp else if (name.length > 13) 36.sp else 44.sp, weight = 900, lineHeight = 0.9f, tracking = -0.02f,
                            uppercase = true, color = SeedyColors.White, maxLines = 2,
                        )
                        val owned = state.disc?.owned
                        val meta = dot(
                            if (state.plays > 0) tn(state.plays, "{n} play from you", "{n} plays from you") else null,
                            owned?.takeIf { it > 0 }?.let { tn(it, "{n} album on the server", "{n} albums on the server") },
                            item.productionLocations?.firstOrNull(),
                        )
                        SText(meta, size = 13.sp, color = SeedyColors.TextHero, maxLines = 2)
                    }
                }
            }
        }
        if (item == null) {
            if (state.error) item(key = "err") { Message(t("Couldn’t load this artist."), t("The server didn’t answer. Check your connection and try again."), t("Try again"), actions.onRetry) }
            return@LazyColumn
        }
        item(key = "actions") {
            val here = playing.isSource(item.id)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillAction(if (here && playing.playing) t("Pause") else t("Play"), if (here && playing.playing) MusicIcons.Pause else MusicIcons.Play, actions.onPlay, light = true)
                SquareAction(MusicIcons.Shuffle, actions.onShuffle)
                PillAction(t("Radio"), MusicIcons.Radio, actions.onRadio, iconSize = 19.dp)
                Spacer(Modifier.weight(1f))
                val fav = item.userData?.isFavorite == true
                IconButton44(if (fav) MusicIcons.HeartOn else MusicIcons.Heart, actions.onFavorite, color = if (fav) accent else SeedyColors.TextHero)
            }
        }
        val songs = state.songs
        val popular = state.popular
        if (songs == null || popular.isNotEmpty()) {
            item(key = "pop-h") { SectionHead(t("Popular"), top = 20.dp, bottom = 6.dp) }
            if (songs == null) items(5) { Skeleton(Modifier.padding(horizontal = 20.dp, vertical = 7.dp).fillMaxWidth().height(44.dp)) }
            else itemsIndexed(popular, key = { _, tk -> "p${tk.id}" }) { i, tk ->
                CoverRow(tk, "${i + 1}", playsLabel(tk.userData?.playCount).ifEmpty { tk.album.orEmpty() }, playing.isTrack(tk.id), { actions.onSong(i) }, { actions.onSongMenu(tk) }, Modifier.padding(horizontal = 8.dp))
            }
        }
        val d = state.disc
        if (d == null) {
            item(key = "disc-h") { SectionHead(t("Albums")) }
            item(key = "disc") { CardRow { items(4) { Skeleton(Modifier.width(124.dp).aspectRatio(1f)) } } }
        } else {
            val releases = d.albums.map { it to yearOf(it) } + d.singles.map { it to dot(releaseLabel(releaseOf(it).takeIf { r -> r != Release.Album } ?: Release.Single), yearOf(it)) }
            if (releases.isNotEmpty()) {
                item(key = "disc-h") {
                    SectionHead(t("Albums"), link = if (releases.size > 2) (if (allAlbums) t("Show less") else t("See all")) else null, onLink = { allAlbums = !allAlbums })
                }
                if (allAlbums) releaseGrid(releases, actions)
                else item(key = "disc") { CardRow { items(releases, key = { it.first.id }) { (a, sub) -> AlbumCard(a, sub, { actions.onOpen(a) }, Modifier.width(124.dp)) } } }
            } else {
                item(key = "disc-none") { SText(t("No albums by {name} on the server yet.", "name" to item.name.orEmpty()), Modifier.padding(horizontal = 20.dp, vertical = 12.dp), size = 14.sp, color = SeedyColors.Text2) }
            }
            if (d.appears.isNotEmpty()) {
                item(key = "app-h") { SectionHead(t("Appears on")) }
                item(key = "app") { CardRow { items(d.appears, key = { it.id }) { a -> AlbumCard(a, dot(albumArtistOf(a), yearOf(a)), { actions.onOpen(a) }, Modifier.width(124.dp)) } } }
            }
            if (d.videos.isNotEmpty()) {
                item(key = "vid-h") { SectionHead(t("Music videos")) }
                item(key = "vid") { CardRow { items(d.videos, key = { it.id }) { v -> VideoCard(v) { actions.onOpen(v) } } } }
            }
        }
        about(item, d)
        val similar = state.similar
        if (!similar.isNullOrEmpty()) {
            item(key = "sim-h") { SectionHead(t("Similar artists on the server")) }
            item(key = "sim") { CardRow { items(similar, key = { it.id }) { a -> ArtistCard(a, artistAlbums(a), { actions.onOpen(a) }, Modifier.width(112.dp)) } } }
        }
    }
}

private fun LazyListScope.releaseGrid(releases: List<Pair<BaseItemDto, String>>, actions: ArtistActions) {
    releases.chunked(2).forEachIndexed { r, row ->
        item(key = "dg$r") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for ((a, sub) in row) AlbumCard(a, sub, { actions.onOpen(a) }, Modifier.weight(1f), coverTitle = 15.sp)
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun VideoCard(video: BaseItemDto, onClick: () -> Unit) {
    val images = LocalViewer.current.images
    Column(Modifier.width(220.dp).tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Artwork(images?.landscape(video, 480), Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(11.dp)))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(video.name.orEmpty(), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val year = yearOf(video)
            if (year.isNotEmpty()) SText(year, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** About: their biography (five lines, Read more), genres, where the info came from. */
private fun LazyListScope.about(artist: BaseItemDto, d: io.github.arkmind.seedy.core.music.Discography?) {
    val genres = artist.genres?.takeIf { it.isNotEmpty() } ?: d?.let { (it.albums + it.singles).flatMap { a -> a.genres.orEmpty() }.distinct() }.orEmpty()
    val bio = artist.overview?.takeIf { it.isNotBlank() }
    if (bio == null && genres.isEmpty()) return
    item(key = "about-h") { SectionHead(t("About")) }
    item(key = "about") {
        var open by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val long = (bio?.length ?: 0) > 280
            SText(
                bio ?: t("No biography for {name} yet.", "name" to artist.name.orEmpty()), size = 14.sp, lineHeight = 1.5f, color = SeedyColors.TextHero,
                maxLines = if (long && !open) 5 else Int.MAX_VALUE,
            )
            if (long) SText(if (open) t("Show less") else t("Read more"), Modifier.tap { open = !open }, size = 13.sp, weight = 800, color = SeedyColors.White)
            if (genres.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (g in genres.take(4)) {
                        SText(g, Modifier.clip(RoundedCornerShape(999.dp)).background(SeedyColors.Surface).padding(horizontal = 12.dp, vertical = 6.dp), size = 12.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
                    }
                }
            }
            val ids = artist.providerIds.orEmpty()
            val mb = ids.keys.any { it.startsWith("MusicBrainzArtist") || it.startsWith("MusicBrainzAlbumArtist") }
            val adb = ids.keys.any { it.startsWith("AudioDbArtist") || it.startsWith("AudioDbAlbumArtist") }
            val src = when {
                mb && adb -> t("Info from MusicBrainz and TheAudioDB")
                mb -> t("Info from MusicBrainz")
                adb -> t("Info from TheAudioDB")
                else -> null
            }
            if (src != null) SText(src, size = 12.sp, color = SeedyColors.Text3)
        }
    }
}

private val Wave = PathParser().parsePathString("M0 140 Q50 110 100 140 T200 140 V200 H0Z").toPath()

/**
 * The hero's art: their backdrop when the server has one; otherwise the boards' sun over a wave in [palette] (the one
 * nearest their latest cover), with their photo in the sun when they have one.
 */
@Composable
private fun ArtistArt(artist: BaseItemDto, palette: List<Color>, modifier: Modifier) {
    val images = LocalViewer.current.images
    val backdrop = images?.backdrop(artist, 1280)
    if (backdrop != null) {
        AsyncImage(model = backdrop, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.background(palette[0]))
        return
    }
    val photo = artist.imageTags?.get(ImageType.PRIMARY)?.let { images?.item(artist.id, ImageType.PRIMARY, it, 520) }
    val (c0, c1, c2, c3) = palette
    val i = io.github.arkmind.seedy.ui.components.CoverPalettes.indexOf(palette).coerceAtLeast(0)
    val s = i % 4
    val by = when (s) { 1 -> 120f; 3 -> 0f; else -> 200f }
    val bh = when (s) { 1 -> 80f; 3 -> 30f; else -> 0f }
    val cx = listOf(70f, 130f, 100f, 60f)[s]
    val cy = listOf(80f, 70f, 95f, 120f)[s]
    val r = listOf(46f, 30f, 58f, 22f)[s]
    Box(modifier.background(c0)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = maxOf(size.width, size.height) / 200f
            translate((size.width - 200f * k) / 2f, (size.height - 200f * k) / 2f) {
                scale(k, k, pivot = Offset.Zero) {
                    drawRect(c0, size = Size(200f, 200f))
                    if (bh > 0f) drawRect(c1, topLeft = Offset(0f, by), size = Size(200f, bh))
                    drawCircle(c2, radius = r, center = Offset(cx, cy))
                    drawCircle(c3.copy(alpha = listOf(0.5f, 0.35f, 0.6f, 0.4f)[s]), radius = listOf(70f, 52f, 82f, 40f)[s], center = Offset(cx, cy), style = Stroke(width = listOf(3f, 8f, 2f, 6f)[s]))
                    drawPath(Wave, c1.copy(alpha = 0.9f))
                }
            }
        }
        if (photo != null) {
            // The photo in the sun, where the canvas draws it.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val k = maxOf(maxWidth.value, maxHeight.value) / 200f
                val left = (maxWidth.value - 200f * k) / 2f + (cx - r) * k
                val top = (maxHeight.value - 200f * k) / 2f + (cy - r) * k
                AsyncImage(
                    model = photo, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(start = left.dp.coerceAtLeast(0.dp), top = top.dp.coerceAtLeast(0.dp))
                        .width((2 * r * k).dp).aspectRatio(1f).clip(androidx.compose.foundation.shape.CircleShape),
                )
            }
        }
    }
}
