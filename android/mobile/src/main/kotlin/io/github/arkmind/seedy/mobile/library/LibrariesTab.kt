package io.github.arkmind.seedy.mobile.library

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.LibrariesViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.browse.TabHeader
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType

/** Board "Android · Libraries" (the Libraries tab). */
@Composable
fun LibrariesTab() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { LibrariesViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LibrariesScreen(
        state,
        onOpen = nav::openLibrary,
        onSearch = { nav.go(Route.Search) },
        // Viewing history and "Order and hide libraries" are Settings pages the phone doesn't have yet.
        onHistory = {},
        onOrder = {},
        bottomPadding = tabBarHeight(),
    )
}

@Composable
fun LibrariesScreen(
    state: LibrariesViewModel.State,
    onOpen: (BaseItemDto) -> Unit,
    onSearch: () -> Unit,
    onHistory: () -> Unit,
    onOrder: () -> Unit,
    bottomPadding: Dp,
) {
    LazyColumn(Modifier.fillMaxSize().background(SeedyColors.Bg), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item(key = "head") { TabHeader(t("Libraries"), onSearch) }
        item(key = "gap") { Spacer(Modifier.padding(top = 18.dp)) }
        items(state.tiles.chunked(2), key = { it.first().view.id }) { pair ->
            Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (tile in pair) LibraryTile(tile, { onOpen(tile.view) }, Modifier.weight(1f))
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        item(key = "links") {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LinkRow(AppIcons.History, t("Viewing history"), onHistory)
                LinkRow(AppIcons.Reorder, t("Order and hide libraries"), onOrder)
            }
        }
    }
}

/** A library: a picture from inside it under a light shade, its kind's icon, its name and how much it holds. */
@Composable
private fun LibraryTile(tile: LibrariesViewModel.Tile, onClick: () -> Unit, modifier: Modifier) {
    val images = LocalViewer.current.images
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(13.dp))) {
            Artwork(tile.art?.let { images?.backdrop(it, 480) }, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().background(Color(0x590A0A0A)))
            Box(
                Modifier.align(Alignment.BottomStart).padding(10.dp).size(34.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Bg.copy(alpha = 0.8f)),
                contentAlignment = Alignment.Center,
            ) {
                SIcon(libraryIcon(tile.view.collectionType), size = 18.dp, color = SeedyColors.White, stroke = 2.2f)
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(tile.view.name.orEmpty(), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(tile.count.ifEmpty { " " }, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** The icon for a kind of library (Libraries board). */
fun libraryIcon(type: CollectionType?): IconDef = when (type) {
    CollectionType.MOVIES -> AppIcons.Movies
    CollectionType.TVSHOWS -> AppIcons.Shows
    CollectionType.BOXSETS -> AppIcons.Layers
    CollectionType.HOMEVIDEOS, CollectionType.PHOTOS -> AppIcons.Camera
    CollectionType.LIVETV -> AppIcons.LiveTv
    CollectionType.PLAYLISTS -> AppIcons.Playlist
    CollectionType.MUSIC, CollectionType.MUSICVIDEOS -> WebIcons.Music
    CollectionType.BOOKS -> WebIcons.Book
    else -> WebIcons.Folder
}

@Composable
private fun LinkRow(icon: IconDef, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SIcon(icon, size = 20.dp, color = SeedyColors.Text2)
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
        SIcon(AppIcons.Chevron, size = 16.dp, color = Color(0xFF6A6A6A))
    }
}
