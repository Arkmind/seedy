package io.github.arkmind.seedy.mobile.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.music.AlbumViewModel
import io.github.arkmind.seedy.core.music.codecOf
import io.github.arkmind.seedy.core.music.dot
import io.github.arkmind.seedy.core.music.lengthLabel
import io.github.arkmind.seedy.core.music.longDate
import io.github.arkmind.seedy.core.music.playlistCovers
import io.github.arkmind.seedy.core.music.releaseLabel
import io.github.arkmind.seedy.core.music.releaseOf
import io.github.arkmind.seedy.core.music.sizeLabel
import io.github.arkmind.seedy.core.music.songsLabel
import io.github.arkmind.seedy.core.music.yearOf
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.coverPalette
import io.github.arkmind.seedy.ui.components.coverUrl
import io.github.arkmind.seedy.ui.components.initialsOf
import io.github.arkmind.seedy.ui.components.webId
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

class AlbumActions(
    val onBack: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onArtist: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onQueue: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    /** Play, or pause/resume when it's what plays. */
    val onPlay: () -> Unit = {},
    val onTrack: (Int) -> Unit = {},
    val onTrackMenu: (BaseItemDto) -> Unit = {},
    /** More by the artist's cards. */
    val onOpen: (BaseItemDto) -> Unit = {},
    val onDiscography: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * Board "Android · Album" (an album, or a music playlist alike): the cover in its colour, the title, the artist and
 * "Album · 2024 · 12 songs · 51 min · FLAC"; favourite, download, add to queue, Shuffle and Play; the songs (the one
 * playing lit, with the bars); when it came out and was added; More by the artist.
 */
@Composable
fun AlbumScreen(
    state: AlbumViewModel.State,
    playing: Playing,
    tint: Color,
    actions: AlbumActions,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
) {
    val item = state.item
    val accent = LocalAccent.current
    val tracks = state.tracks.orEmpty()
    val playlist = state.playlist
    val here = item != null && playing.trackId != null &&
        (playing.isSource(item.id) || (!playlist && playing.albumId == item.id && playing.sourceKind == io.github.arkmind.seedy.player.audio.SourceKind.Album))
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item(key = "hero") {
            Column(
                Modifier.fillMaxWidth().drawBehind {
                    val h = 440.dp.toPx()
                    drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0f)), startY = 0f, endY = h), size = Size(size.width, h))
                },
            ) {
                MusicHeader(null, actions.onBack, top = topInset()) {
                    IconButton44(MusicIcons.Cast, actions.onCast, size = 21.dp)
                    IconButton44(MusicIcons.More, actions.onMore)
                }
                if (item == null) {
                    if (state.error) Message(t("Couldn’t load this title"), t("The server didn’t answer. Check your connection and try again."), t("Try again"), actions.onRetry)
                    else Skeleton(Modifier.padding(top = 6.dp).size(220.dp).align(Alignment.CenterHorizontally), radius = 14.dp)
                    return@Column
                }
                val coverMod = Modifier.padding(top = 6.dp).size(220.dp).align(Alignment.CenterHorizontally)
                    .boxShadow(Color(0x8C000000), blur = 50.dp, y = 20.dp, radius = 14.dp).clip(RoundedCornerShape(14.dp))
                val covers = if (playlist) playlistCovers(tracks) else emptyList()
                when {
                    playlist && covers.size >= 2 && item.imageTags.isNullOrEmpty() -> MosaicCover(covers, coverMod, width = 300)
                    playlist && item.imageTags.isNullOrEmpty() && covers.isNotEmpty() -> ItemCover(covers[0], coverMod, width = 600, title = item.name, titleSize = 22.sp)
                    else -> ItemCover(item, coverMod, width = 600, title = item.name, titleSize = 22.sp)
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SText(item.name.orEmpty(), size = 26.sp, weight = 900, lineHeight = 1.05f, color = SeedyColors.White, maxLines = 3)
                    val artist = state.artistRef
                    val artistName = artist?.name ?: item.albumArtist
                    if (!playlist && !artistName.isNullOrEmpty()) {
                        Row(
                            Modifier.then(if (artist != null) Modifier.tap(onClick = actions.onArtist) else Modifier),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ArtistAvatar(state.artist, artist?.id?.let(::webId), artistName)
                            SText(artistName, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                        }
                    }
                    val total = tracks.sumOf { it.runTimeTicks ?: 0L } / 10_000_000.0
                    val kind = if (playlist) t("Playlist") else releaseLabel(releaseOf(item, tracks.size.takeIf { it > 0 }, (total * 10_000_000).toLong().takeIf { it > 0 }))
                    val meta = dot(
                        kind, if (playlist) null else yearOf(item), if (tracks.isNotEmpty()) songsLabel(tracks.size) else null,
                        if (total > 0) lengthLabel(total) else null, if (playlist) null else codecOf(tracks),
                    )
                    SText(meta, size = 12.sp, color = SeedyColors.Text2, maxLines = 2)
                }
                Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    val fav = item.userData?.isFavorite == true
                    IconButton44(if (fav) MusicIcons.HeartOn else MusicIcons.Heart, actions.onFavorite, color = if (fav) accent else SeedyColors.TextHero)
                    if (state.canDownload && !playlist) IconButton44(MusicIcons.Download, actions.onDownload, size = 21.dp, color = SeedyColors.TextHero)
                    IconButton44(MusicIcons.Queue, actions.onQueue, size = 21.dp, color = SeedyColors.TextHero)
                    Spacer(Modifier.weight(1f))
                    SquareAction(MusicIcons.Shuffle, actions.onShuffle)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(17.dp)).background(SeedyColors.Inverse).tap(onClick = actions.onPlay), contentAlignment = Alignment.Center) {
                        SIcon(if (here && playing.playing) MusicIcons.Pause else MusicIcons.Play, size = 26.dp, color = SeedyColors.InkInverse)
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        if (item == null) return@LazyColumn
        when {
            state.tracks == null && state.tracksError -> item(key = "err") { Message(t("Couldn’t load the songs"), t("The server didn’t answer. Check your connection and try again."), t("Try again"), actions.onRetry) }
            state.tracks == null -> items(8) { Skeleton(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(40.dp)) }
            tracks.isEmpty() -> item(key = "empty") {
                if (playlist) Message(t("This playlist is empty"), t("Add songs from their ⋯ menu."))
                else Message(t("No songs"), t("This album has no playable songs on the server."))
            }
            else -> {
                val multiDisc = !playlist && tracks.map { it.parentIndexNumber ?: 1 }.toSet().size > 1
                var disc: Int? = null
                tracks.forEachIndexed { i, tk ->
                    val d = tk.parentIndexNumber ?: 1
                    if (multiDisc && d != disc) {
                        disc = d
                        item(key = "disc$d") {
                            SText(t("Disc {n}", "n" to d), Modifier.padding(start = 24.dp, top = if (i == 0) 0.dp else 14.dp, bottom = 4.dp), size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.Text3)
                        }
                    }
                    item(key = "t$i-${tk.id}") {
                        val current = playing.isTrack(tk.id)
                        if (playlist) {
                            CoverRow(tk, "${i + 1}", trackSub(tk), current, { actions.onTrack(i) }, { actions.onTrackMenu(tk) }, Modifier.padding(horizontal = 8.dp))
                        } else {
                            TrackRow("${tk.indexNumber ?: i + 1}", tk.name.orEmpty(), trackSub(tk), current, playing.playing, { actions.onTrack(i) }, { actions.onTrackMenu(tk) }, Modifier.padding(horizontal = 8.dp))
                        }
                    }
                }
                if (!playlist) item(key = "foot") { AlbumFoot(item, tracks) }
            }
        }
        val more = state.moreBy
        val artist = state.artistRef
        if (!playlist && artist != null && !more.isNullOrEmpty()) {
            item(key = "more-h") { SectionHead(t("More by {name}", "name" to artist.name.orEmpty()), link = t("Discography"), onLink = actions.onDiscography) }
            item(key = "more") {
                CardRow { items(more, key = { it.first.id }) { (a, sub) -> AlbumCard(a, sub, { actions.onOpen(a) }, Modifier.width(132.dp)) } }
            }
        }
    }
}

/** The artist's picture in a 22 px rounded square, else their initials on their drawn colours. */
@Composable
private fun ArtistAvatar(artist: BaseItemDto?, seed: String?, name: String) {
    val pal = coverPalette(seed)
    val url = coverUrl(artist, LocalViewer.current.images, 56)
    Box(Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(pal[0]), contentAlignment = Alignment.Center) {
        if (url != null) Artwork(url, Modifier.fillMaxSize(), placeholder = pal[0])
        else SText(initialsOf(name), size = 10.sp, weight = 900, color = pal[2], maxLines = 1)
    }
}

/** "Released 12 March 2024 · Label", "Added to the server 3 May 2025 · 412 MB". */
@Composable
private fun AlbumFoot(item: BaseItemDto, tracks: List<BaseItemDto>) {
    val released = item.premiereDate?.toLocalDate()
    val added = item.dateCreated?.toLocalDate()
    val label = item.studios?.firstOrNull()?.name
    val bytes = tracks.sumOf { it.mediaSources?.firstOrNull()?.size ?: 0L }
    val first = dot(released?.let { t("Released {date}", "date" to longDate(it)) }, label)
    val second = dot(added?.let { t("Added to the server {date}", "date" to longDate(it)) }, if (bytes > 0) sizeLabel(bytes) else null)
    if (first.isEmpty() && second.isEmpty()) return
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (first.isNotEmpty()) SText(first, size = 12.sp, color = SeedyColors.Text3)
        if (second.isNotEmpty()) SText(second, size = 12.sp, color = SeedyColors.Text3)
    }
}
