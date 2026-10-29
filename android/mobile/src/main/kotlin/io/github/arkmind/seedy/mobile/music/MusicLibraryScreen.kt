package io.github.arkmind.seedy.mobile.music

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.music.Mix
import io.github.arkmind.seedy.core.music.MusicLibraryViewModel
import io.github.arkmind.seedy.core.music.MusicSort
import io.github.arkmind.seedy.core.music.MusicSorts
import io.github.arkmind.seedy.core.music.MusicTab
import io.github.arkmind.seedy.core.music.Paged
import io.github.arkmind.seedy.core.music.albumArtistOf
import io.github.arkmind.seedy.core.music.albumsLabel
import io.github.arkmind.seedy.core.music.artistsOf
import io.github.arkmind.seedy.core.music.dot
import io.github.arkmind.seedy.core.music.playsLabel
import io.github.arkmind.seedy.core.music.songsLabel
import io.github.arkmind.seedy.core.music.yearOf
import io.github.arkmind.seedy.mobile.browse.ChipRow
import io.github.arkmind.seedy.mobile.browse.ToolButton
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.coverUrl
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

class MusicActions(
    val onBack: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onCast: () -> Unit = {},
    val onTab: (MusicTab) -> Unit = {},
    /** An album, playlist or artist card. */
    val onOpen: (BaseItemDto) -> Unit = {},
    val onMix: (Mix) -> Unit = {},
    /** Recently added's See all: the albums by date added. */
    val onRecentAll: () -> Unit = {},
    val onNewPlaylist: () -> Unit = {},
    /** A genre's tile (null: back to all genres). */
    val onGenre: (BaseItemDto?) -> Unit = {},
    val onGenrePlay: (BaseItemDto) -> Unit = {},
    val onGenreMix: (BaseItemDto) -> Unit = {},
    val onFavorites: (Boolean) -> Unit = {},
    val onSort: (MusicSort) -> Unit = {},
    val onMore: (MusicTab) -> Unit = {},
    /** A song of the Songs tab: play the list from it. */
    val onSong: (List<BaseItemDto>, Int) -> Unit = { _, _ -> },
    val onSongMenu: (BaseItemDto) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * Board "Android · Music": Back, the library's name, Search and Cast; the chips; Home (Jump back in, Made for you,
 * Recently added, then the web's Your playlists, Artists you play most and Genres) or a tab's list.
 */
@Composable
fun MusicLibraryScreen(
    state: MusicLibraryViewModel.State,
    playing: Playing,
    actions: MusicActions,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
) {
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item(key = "head") {
            MusicHeader(state.view?.name ?: t("Music"), actions.onBack, top = topInset()) {
                IconButton44(MusicIcons.Search, actions.onSearch)
                IconButton44(MusicIcons.Cast, actions.onCast, size = 21.dp)
            }
        }
        item(key = "chips") {
            ChipRow(Modifier.padding(top = 12.dp)) {
                items(MusicTab.entries, key = { it.name }) { tab -> MusicChip(t(tab.label), tab == state.tab, { actions.onTab(tab) }) }
            }
        }
        if (state.error) {
            item(key = "error") { Message(t("Couldn’t load the library."), t("The server didn’t answer. Check your connection and try again."), t("Try again"), actions.onRetry) }
            return@LazyColumn
        }
        when (state.tab) {
            MusicTab.Home -> home(state, playing, actions)
            MusicTab.Albums -> albums(state.albums, state, actions, MusicTab.Albums)
            MusicTab.Artists -> artists(state, actions)
            MusicTab.Songs -> songs(state, playing, actions)
            MusicTab.Playlists -> playlists(state, actions)
            MusicTab.Genres -> genres(state, actions)
        }
    }
}

/* ── Home ── */

private fun LazyListScope.home(state: MusicLibraryViewModel.State, playing: Playing, actions: MusicActions) {
    val home = state.home
    val jump = home.jump
    if (jump == null || jump.isNotEmpty()) {
        item(key = "jump-h") { SectionHead(t("Jump back in")) }
        item(key = "jump") {
            val curId = playing.sourceId ?: playing.albumId?.toString()
            val list = jump.orEmpty().take(6).let { l ->
                val at = l.indexOfFirst { Playing.sameId(curId, it.id) }
                if (at > 0) listOf(l[at]) + l.filterIndexed { i, _ -> i != at } else l
            }
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (jump == null) repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { repeat(2) { Skeleton(Modifier.weight(1f).height(56.dp)) } } }
                for (pair in list.chunked(2)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (it in pair) {
                            val current = playing.trackId != null && Playing.sameId(curId, it.id)
                            JumpTile(it, current, playing.playing, { actions.onOpen(it) }, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    val mixes = home.mixes
    if (mixes == null || mixes.isNotEmpty()) {
        item(key = "mix-h") { SectionHead(t("Made for you")) }
        item(key = "mix") {
            CardRow {
                if (mixes == null) items(3) { Skeleton(Modifier.size(236.dp, 124.dp), radius = 16.dp) }
                else items(mixes, key = { it.key }) { m ->
                    val images = LocalViewer.current.images
                    val palette = rememberNearestPalette(coverUrl(m.covers.firstOrNull(), images, 64), m.seed)
                    MixCard(m, palette, { actions.onMix(m) })
                }
            }
        }
    }
    val recent = home.recent
    if (recent == null || recent.isNotEmpty()) {
        item(key = "new-h") { SectionHead(t("Recently added"), link = t("See all"), onLink = actions.onRecentAll) }
        item(key = "new") {
            CardRow {
                if (recent == null) items(4) { CardSkeleton(132.dp) }
                else items(recent, key = { it.id }) { a -> AlbumCard(a, albumArtistOf(a), { actions.onOpen(a) }, Modifier.width(132.dp)) }
            }
        }
    }
    item(key = "pl-h") { SectionHead(t("Your playlists"), link = t("All playlists"), onLink = { actions.onTab(MusicTab.Playlists) }) }
    item(key = "pl") {
        CardRow {
            item(key = "new") { NewPlaylistTile(actions.onNewPlaylist, Modifier.width(132.dp)) }
            val pls = home.playlists
            if (pls == null) items(3) { CardSkeleton(132.dp) }
            else items(pls, key = { it.playlist.id }) { p -> PlaylistCard(p.playlist, p.covers, p.sub, { actions.onOpen(p.playlist) }, Modifier.width(132.dp)) }
        }
    }
    val top = home.top
    val played = !top.isNullOrEmpty()
    val artists = if (played) top!!.map { it.artist to playsLabel(it.plays) } else home.artists?.map { it to artistAlbums(it) }
    if (top == null || artists == null || artists.isNotEmpty()) {
        item(key = "ar-h") {
            SectionHead(if (played || top == null) t("Artists you play most") else t("Artists"), link = t("See all"), onLink = { actions.onTab(MusicTab.Artists) })
        }
        item(key = "ar") {
            CardRow {
                if (artists == null) items(4) { CardSkeleton(112.dp, round = true) }
                else items(artists, key = { it.first.id }) { (a, sub) -> ArtistCard(a, sub, { actions.onOpen(a) }, Modifier.width(112.dp)) }
            }
        }
    }
    val genres = home.genres
    if (genres == null || genres.isNotEmpty()) {
        item(key = "ge-h") { SectionHead(t("Genres"), link = t("All genres"), onLink = { actions.onGenre(null) }) }
        item(key = "ge") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (genres == null) repeat(2) { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(2) { Skeleton(Modifier.weight(1f).height(96.dp), radius = 16.dp) } } }
                else genres.chunked(2).forEachIndexed { r, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEachIndexed { c, g ->
                            GenreTile(g.genre.name.orEmpty(), genreCount(g.genre), r * 2 + c, g.sample, { actions.onGenre(g.genre) }, Modifier.weight(1f))
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

fun artistAlbums(a: BaseItemDto): String = (a.albumCount ?: a.childCount)?.takeIf { it > 0 }?.let { albumsLabel(it) }.orEmpty()

private fun genreCount(g: BaseItemDto): String = (g.albumCount ?: g.childCount)?.takeIf { it > 0 }?.let { albumsLabel(it) }.orEmpty()

@Composable
private fun CardSkeleton(width: Dp, round: Boolean = false) {
    Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Skeleton(Modifier.fillMaxWidth().aspectRatio(1f), radius = if (round) width / 2 else 11.dp)
        Skeleton(Modifier.width(width * 0.7f).height(10.dp), radius = 5.dp)
    }
}

/* ── Tabs ── */

/** The count on the left; Favorites and Sort on the right (web tabs.tsx Toolbar). */
@Composable
private fun Toolbar(count: String?, sorts: List<MusicSort>? = null, sort: MusicSort? = null, favorites: Boolean? = null, actions: MusicActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SText(count.orEmpty(), Modifier.weight(1f), size = 13.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
        if (favorites != null) {
            Row(
                Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(if (favorites) SeedyColors.Surface2 else SeedyColors.Row)
                    .tap { actions.onFavorites(!favorites) }.padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SIcon(if (favorites) MusicIcons.HeartOn else MusicIcons.Heart, size = 16.dp, color = if (favorites) LocalAccent.current else SeedyColors.Text)
                SText(t("Favorites"), size = 13.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
            }
        }
        if (sorts != null && sort != null) {
            Box {
                ToolButton(MusicIcons.Sort, t(sort.label), { menu = true })
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                    SText(t("Sort by"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
                    for (o in sorts) {
                        DropdownMenuItem(
                            text = { SText(t(o.label), size = 14.sp, weight = if (o.id == sort.id) 800 else 600, color = SeedyColors.White) },
                            trailingIcon = if (o.id == sort.id) ({ SIcon(MusicIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                            onClick = { menu = false; actions.onSort(o) },
                        )
                    }
                }
            }
        }
    }
}

private fun countOf(p: Paged, label: (Int) -> String): String? = if (p.loaded) label(p.total) else null

/** Loads the next page once the list nears its end. */
private fun LazyListScope.more(p: Paged, key: String, onMore: () -> Unit, onRetry: () -> Unit) {
    if (p.done) return
    item(key = "$key-more") {
        if (p.error && p.items.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { ToolButton(MusicIcons.Sort, t("Load more"), onRetry) }
        } else {
            LaunchedEffect(p.items.size) { onMore() }
            Spacer(Modifier.height(1.dp))
        }
    }
}

private fun LazyListScope.albums(p: Paged, state: MusicLibraryViewModel.State, actions: MusicActions, tab: MusicTab) {
    if (tab == MusicTab.Albums) item(key = "tools") { Toolbar(countOf(p) { tn(it, "{n} album", "{n} albums") }, MusicSorts.Albums, state.albumSort, state.favorites, actions) }
    when {
        p.error && p.items.isEmpty() -> item(key = "err") { Message(t("Couldn’t load the albums"), t("The server didn’t answer. Check your connection and try again."), t("Try again")) { actions.onMore(tab) } }
        !p.loaded -> gridSkeleton(2)
        p.items.isEmpty() -> item(key = "empty") {
            if (state.favorites) Message(t("No favourite albums yet"), t("Tap the heart on an album and it shows up here."))
            else Message(t("No albums yet"), t("Albums show up here once the server finds them."))
        }
        else -> grid(p.items, 2, "al") { a, m -> AlbumCard(a, dot(albumArtistOf(a), yearOf(a)), { actions.onOpen(a) }, m, coverTitle = 15.sp) }
    }
    more(p, "al", { actions.onMore(tab) }, { actions.onMore(tab) })
}

private fun LazyListScope.artists(state: MusicLibraryViewModel.State, actions: MusicActions) {
    val p = state.artists
    item(key = "tools") { Toolbar(countOf(p) { tn(it, "{n} artist", "{n} artists") }, MusicSorts.Artists, state.artistSort, state.favorites, actions) }
    when {
        p.error && p.items.isEmpty() -> item(key = "err") { Message(t("Couldn’t load the artists"), t("The server didn’t answer. Check your connection and try again."), t("Try again")) { actions.onMore(MusicTab.Artists) } }
        !p.loaded -> gridSkeleton(3, round = true)
        p.items.isEmpty() -> item(key = "empty") {
            Message(if (state.favorites) t("No favourite artists yet") else t("No artists yet"), t("Artists show up here once the server finds their albums."))
        }
        else -> grid(p.items, 3, "ar") { a, m -> ArtistCard(a, artistAlbums(a), { actions.onOpen(a) }, m) }
    }
    more(p, "ar", { actions.onMore(MusicTab.Artists) }, { actions.onMore(MusicTab.Artists) })
}

private fun LazyListScope.songs(state: MusicLibraryViewModel.State, playing: Playing, actions: MusicActions) {
    val p = state.songs
    item(key = "tools") { Toolbar(countOf(p) { songsLabel(it) }, MusicSorts.Songs, state.songSort, state.favorites, actions) }
    when {
        p.error && p.items.isEmpty() -> item(key = "err") { Message(t("Couldn’t load the songs"), t("The server didn’t answer. Check your connection and try again."), t("Try again")) { actions.onMore(MusicTab.Songs) } }
        !p.loaded -> items(8) { Skeleton(Modifier.padding(horizontal = 20.dp, vertical = 7.dp).fillMaxWidth().height(44.dp)) }
        p.items.isEmpty() -> item(key = "empty") {
            if (state.favorites) Message(t("No favourite songs yet"), t("Tap the heart on a song and it shows up here."))
            else Message(t("No songs yet"), t("Songs show up here once the server finds them."))
        }
        else -> itemsIndexed(p.items, key = { i, tk -> "s$i-${tk.id}" }) { i, tk ->
            CoverRow(tk, null, dot(artistsOf(tk), tk.album), playing.isTrack(tk.id), { actions.onSong(p.items, i) }, { actions.onSongMenu(tk) }, Modifier.padding(horizontal = 8.dp))
        }
    }
    more(p, "so", { actions.onMore(MusicTab.Songs) }, { actions.onMore(MusicTab.Songs) })
}

private fun LazyListScope.playlists(state: MusicLibraryViewModel.State, actions: MusicActions) {
    val list = state.playlists
    item(key = "tools") { Toolbar(list?.let { tn(it.size, "{n} playlist", "{n} playlists") }, actions = actions) }
    if (state.playlistsError && list == null) {
        item(key = "err") { Message(t("Couldn’t load your playlists."), t("The server didn’t answer. Check your connection and try again."), t("Try again")) { actions.onTab(MusicTab.Playlists) } }
        return
    }
    val cells: List<Any> = listOf("new") + (list ?: List(3) { "skel$it" })
    cells.chunked(2).forEachIndexed { r, pair ->
        item(key = "pl$r") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (x in pair) {
                    val m = Modifier.weight(1f)
                    when (x) {
                        "new" -> NewPlaylistTile(actions.onNewPlaylist, m)
                        is io.github.arkmind.seedy.core.music.PlaylistPreview -> PlaylistCard(x.playlist, x.covers, x.sub, { actions.onOpen(x.playlist) }, m)
                        else -> Column(m, verticalArrangement = Arrangement.spacedBy(9.dp)) { Skeleton(Modifier.fillMaxWidth().aspectRatio(1f)) }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

private fun LazyListScope.genres(state: MusicLibraryViewModel.State, actions: MusicActions) {
    val genre = state.genre
    if (genre != null) {
        item(key = "genre-head") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.tap { actions.onGenre(null) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SIcon(MusicIcons.Back, size = 16.dp, color = SeedyColors.Text2, stroke = 2.4f)
                    SText(t("All genres"), size = 13.sp, weight = 800, tracking = 0.08f, uppercase = true, color = SeedyColors.Text2)
                }
                SText(genre.name.orEmpty(), size = 30.sp, weight = 900, lineHeight = 1.05f, tracking = -0.02f, color = SeedyColors.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillAction(t("Play"), MusicIcons.Play, { actions.onGenrePlay(genre) }, light = true)
                    PillAction(t("Instant mix"), MusicIcons.Mix, { actions.onGenreMix(genre) }, iconSize = 19.dp)
                }
            }
        }
        item(key = "genre-gap") { Spacer(Modifier.height(16.dp)) }
        albums(state.genreAlbums, state, actions, MusicTab.Genres)
        return
    }
    val list = state.genres
    item(key = "tools") { Toolbar(list?.let { tn(it.size, "{n} genre", "{n} genres") }, actions = actions) }
    when {
        list == null -> items(3) { Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) { repeat(2) { Skeleton(Modifier.weight(1f).height(96.dp), radius = 16.dp) } } }
        list.isEmpty() -> item(key = "empty") { Message(t("No genres yet"), t("Genres come from each album’s tags.")) }
        else -> list.chunked(2).forEachIndexed { r, pair ->
            item(key = "g$r") {
                Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { c, g -> GenreTile(g.name.orEmpty(), genreCount(g), r * 2 + c, state.genreSamples[g.id], { actions.onGenre(g) }, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** Cards in rows of [columns], 20 px in, 12 px apart, 18 px between rows. */
private fun LazyListScope.grid(list: List<BaseItemDto>, columns: Int, key: String, card: @Composable (BaseItemDto, Modifier) -> Unit) {
    list.chunked(columns).forEachIndexed { r, row ->
        item(key = "$key$r-${row.first().id}") {
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (x in row) card(x, Modifier.weight(1f))
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun LazyListScope.gridSkeleton(columns: Int, round: Boolean = false) {
    items(3) {
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(columns) { Skeleton(Modifier.weight(1f).aspectRatio(1f), radius = if (round) 999.dp else 11.dp) }
        }
    }
}
