package io.github.arkmind.seedy.mobile.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.runtime.rememberCoroutineScope
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.manage.BulkViewModel
import io.github.arkmind.seedy.mobile.manage.ManagePerms
import io.github.arkmind.seedy.mobile.manage.ManageSheetHost
import io.github.arkmind.seedy.mobile.manage.SelectionActions
import io.github.arkmind.seedy.mobile.manage.SelectionBar
import io.github.arkmind.seedy.mobile.manage.rememberManageSheets
import io.github.arkmind.seedy.mobile.parts.Toast
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.browse.Filters
import io.github.arkmind.seedy.core.browse.LibraryTab
import io.github.arkmind.seedy.core.browse.Sort
import io.github.arkmind.seedy.core.feature.LibraryViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.browse.Chip
import io.github.arkmind.seedy.mobile.browse.ChipRow
import io.github.arkmind.seedy.mobile.browse.GridCard
import io.github.arkmind.seedy.mobile.browse.GridCardSkeleton
import io.github.arkmind.seedy.mobile.browse.ToolButton
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.CollectionType
import java.util.UUID

/** Board "Android · Movies library": one library's grid; a long press picks titles (board LibrarySelect). */
@Composable
fun LibraryRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "library:$id") { LibraryViewModel(session, id) }
    val bulk = viewModel(key = "library-bulk:$id") { BulkViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val bulkState by bulk.state.collectAsStateWithLifecycle()
    var filtering by remember { mutableStateOf(false) }
    val perms = remember(session) { ManagePerms.of(session) }
    val sheets = rememberManageSheets()
    val scope = rememberCoroutineScope()
    // The picked titles, in the order they were picked; null while not picking.
    var picked by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    BackHandler(enabled = picked != null) { picked = null }
    fun toggle(item: BaseItemDto) {
        val now = picked.orEmpty()
        picked = if (now.any { it.id == item.id }) now.filter { it.id != item.id } else now + item
    }
    Box(Modifier.fillMaxSize()) {
        LibraryScreen(
            state,
            LibraryActions(
                onPick = { item -> if (picked == null) picked = listOf(item) else toggle(item) },
                onBack = nav::back,
                onSwitch = { view ->
                    nav.back()
                    if (view.collectionType == CollectionType.LIVETV) nav.go(Route.LiveTv) else nav.go(Route.Library(view.id))
                },
                onSearch = { nav.go(Route.Search) },
                onShuffle = { vm.shuffle(nav::play) },
                onTab = vm::setTab,
                onSort = vm::setSort,
                onFilters = { vm.loadOptions(); filtering = true },
                onClearFilters = { vm.setFilters(Filters()) },
                onOpen = { item -> if (picked != null) toggle(item) else nav.open(item) },
                onGenre = vm::openGenre,
                onMore = { vm.loadMore() },
            ),
            bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            selection = picked?.map { it.id }?.toSet(),
        )
        if (filtering) FilterSheet(state, count = vm::countFor, onApply = { vm.setFilters(it); filtering = false }, onDismiss = { filtering = false })
        picked?.let { list ->
            val ids = list.map { it.id.toString() }
            val target = AddTarget(ids, list.singleOrNull()?.name ?: tn(list.size, "{n} title", "{n} titles"), list.singleOrNull()?.type?.serialName)
            SelectionBar(
                list.size, state.itemsTotal.takeIf { state.isGrid && it > 0 },
                SelectionActions(
                    onClose = { picked = null },
                    onSelectAll = { scope.launch { picked = vm.allItems() } },
                    onPlaylist = { sheets.show("playlist", target) },
                    onWatched = { bulk.markWatched(ids) { vm.markedPlayed(list.map { it.id }.toSet()) } },
                    onRefresh = if (perms.admin) ({ bulk.refresh(ids) }) else null,
                    onDelete = if (list.isNotEmpty() && list.all { it.canDelete == true }) ({ sheets.show("delete", target) }) else null,
                    onCollection = if (perms.collections) ({ sheets.show("collection", target) }) else null,
                ),
                Modifier.align(Alignment.TopCenter),
                busy = bulkState.busy,
            )
        }
        ManageSheetHost(sheets, say = bulk::say) { gone, message ->
            vm.removed(gone.mapNotNull { runCatching { UUID.fromString(dashed(it)) }.getOrNull() }.toSet())
            picked = null
            bulk.say(message)
        }
        Toast(bulkState.message, bulk::messageShown, Modifier.align(Alignment.BottomCenter))
    }
}

/** Jellyfin's 32-character ids as UUIDs. */
private fun dashed(id: String): String =
    if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

class LibraryActions(
    val onBack: () -> Unit = {},
    val onSwitch: (BaseItemDto) -> Unit = {},
    val onSearch: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onTab: (LibraryTab) -> Unit = {},
    val onSort: (Sort, Boolean) -> Unit = { _, _ -> },
    val onFilters: () -> Unit = {},
    val onClearFilters: () -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onGenre: (String) -> Unit = {},
    val onMore: () -> Unit = {},
    /** A long press: starts picking titles, or picks one more. */
    val onPick: ((BaseItemDto) -> Unit)? = null,
)

/** The library page: its name (a menu of the other libraries), Search and Shuffle; the views; sort and filters; the grid. */
@Composable
fun LibraryScreen(
    state: LibraryViewModel.State,
    actions: LibraryActions,
    bottomPadding: Dp,
    grid: LazyGridState = rememberLazyGridState(),
    /** The picked titles while picking (board LibrarySelect). */
    selection: Set<UUID>? = null,
) {
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > grid.layoutInfo.totalItemsCount - 12 } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.isGrid) actions.onMore() }
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize().background(SeedyColors.Bg),
        state = grid,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header, views and tools are one line of the grid: the grid's 16 px gaps only apply under them.
        full("top") {
            Column {
                Header(state, actions)
                if (state.view != null) {
                    ChipRow(Modifier.bleed(20.dp, 20.dp).padding(top = 12.dp)) {
                        items(state.tabs, key = { it.first }) { (tab, label) -> Chip(t(label), tab == state.tab, { actions.onTab(tab) }) }
                    }
                    if (state.isGrid) Tools(state, actions)
                }
            }
        }
        when {
            state.view == null && !state.error -> items(8) { GridCardSkeleton() }
            state.error -> full("error") { Message(t("Couldn't open this library"), t("The server didn’t answer. Check your connection and try again.")) }
            state.tab == LibraryTab.Suggestions -> suggestions(state, actions)
            state.tab == LibraryTab.Genres -> genres(state, actions)
            else -> {
                if (state.items.isEmpty() && state.loading) items(8) { GridCardSkeleton() }
                else if (state.items.isEmpty()) full("empty") { Empty(state, actions) }
                items(state.items, key = { it.id }) { item ->
                    GridCard(item, { actions.onOpen(item) }, selected = selection?.contains(item.id), onLongClick = actions.onPick?.let { pick -> { pick(item) } })
                }
            }
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

/** Widens a full-line item past the grid's side padding. */
private fun Modifier.bleed(start: Dp, end: Dp): Modifier = this.layout { measurable, constraints ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + s + e, maxWidth = constraints.maxWidth + s + e))
    layout(p.width - s - e, p.height) { p.place(-s, 0) }
}

@Composable
private fun Header(state: LibraryViewModel.State, actions: LibraryActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.bleed(12.dp, 8.dp).fillMaxWidth().padding(top = topInset()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderButton(WebIcons.ArrowLeft, actions.onBack)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.height(44.dp).tap { menu = true }.padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SText(state.view?.name.orEmpty(), Modifier.weight(1f, fill = false), size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
                SIcon(WebIcons.ChevronDown, Modifier.alpha(0.7f), size = 18.dp, color = SeedyColors.White, stroke = 2.4f)
                state.total?.let { SText("$it", Modifier.padding(start = 4.dp), size = 13.sp, weight = 600, color = SeedyColors.Text3) }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                for (lib in state.libraries) {
                    val here = lib.id == state.view?.id
                    DropdownMenuItem(
                        text = { SText(lib.name.orEmpty(), size = 14.sp, weight = if (here) 800 else 600, color = SeedyColors.White) },
                        leadingIcon = { SIcon(libraryIcon(lib.collectionType), size = 18.dp, color = SeedyColors.Text2) },
                        trailingIcon = if (here) ({ SIcon(WebIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                        onClick = { menu = false; if (!here) actions.onSwitch(lib) },
                    )
                }
            }
        }
        HeaderButton(WebIcons.Search, actions.onSearch, stroke = 2.2f)
        Box(Modifier.width(44.dp).height(44.dp).clip(RoundedCornerShape(13.dp)).tap(onClick = actions.onShuffle), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.Shuffle, size = 21.dp, color = SeedyColors.White)
        }
    }
}

@Composable
private fun Tools(state: LibraryViewModel.State, actions: LibraryActions) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            ToolButton(WebIcons.Sort, t(state.sort.label), { menu = true })
            SortMenu(menu, state.sort, state.descending, onDismiss = { menu = false }) { s, d -> menu = false; actions.onSort(s, d) }
        }
        if (state.tab != LibraryTab.Collections) ToolButton(WebIcons.Filters, t("Filters"), actions.onFilters, badge = state.filters.count)
        Spacer(Modifier.weight(1f))
        SText(state.filters.labels.joinToString(" · "), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
    }
}

@Composable
private fun SortMenu(open: Boolean, sort: Sort, descending: Boolean, onDismiss: () -> Unit, onPick: (Sort, Boolean) -> Unit) {
    DropdownMenu(expanded = open, onDismissRequest = onDismiss, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
        SText(t("Sort by"), Modifier.padding(horizontal = 16.dp, vertical = 8.dp), size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        for (s in Sort.entries) {
            DropdownMenuItem(
                text = { SText(t(s.label), size = 14.sp, weight = if (s == sort) 800 else 600, color = SeedyColors.White) },
                trailingIcon = if (s == sort) ({ SIcon(WebIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                onClick = { onPick(s, if (s == sort) descending else s.descending) },
            )
        }
        if (sort != Sort.Random) {
            HorizontalDivider(color = SeedyColors.Surface2)
            for (d in listOf(false, true)) {
                DropdownMenuItem(
                    text = { SText(if (d) t("Descending") else t("Ascending"), size = 14.sp, weight = if (d == descending) 800 else 600, color = SeedyColors.White) },
                    leadingIcon = { SIcon(WebIcons.SortEpisodes, size = 16.dp, color = SeedyColors.Text2) },
                    trailingIcon = if (d == descending) ({ SIcon(WebIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                    onClick = { onPick(sort, d) },
                )
            }
        }
    }
}

@Composable
private fun Empty(state: LibraryViewModel.State, actions: LibraryActions) {
    val name = state.view?.name.orEmpty()
    when {
        state.filters.count > 0 -> Message(t("Nothing matches these filters"), t("Try removing a filter or two."), t("Clear all"), actions.onClearFilters)
        state.tab == LibraryTab.Favorites -> Message(t("No favorites in {name} yet", "name" to name), t("Tap the heart on any title and it will show up here."))
        state.tab == LibraryTab.Collections -> Message(t("No collections yet"), t("Collections group related titles, like a film series. Create them in Jellyfin and they'll show up here."))
        else -> Message(t("{name} is empty", "name" to name), t("Once the server finds media in this library it will show up here."))
    }
}

@Composable
fun Message(title: String, text: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SText(title, size = 16.sp, weight = 800, color = SeedyColors.White, align = androidx.compose.ui.text.style.TextAlign.Center)
        SText(text, size = 13.sp, color = SeedyColors.Text2, align = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            SText(
                action, Modifier.padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Inverse).tap(onClick = onAction).padding(horizontal = 16.dp, vertical = 10.dp),
                size = 13.sp, weight = 800, color = SeedyColors.InkInverse,
            )
        }
    }
}

private fun LazyGridScope.suggestions(state: LibraryViewModel.State, actions: LibraryActions) {
    val rows = state.suggestions
    if (rows == null) {
        items(6) { GridCardSkeleton() }
        return
    }
    if (rows.isEmpty()) full("none") { Message(t("Nothing to suggest yet"), t("Watch a few titles and suggestions will show up here.")) }
    for (row in rows) {
        full("row:${row.title}") {
            Column(Modifier.bleed(20.dp, 20.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SText(row.title, Modifier.weight(1f, fill = false), size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    if (row.sub.isNotEmpty()) SText(row.sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(row.items, key = { it.id }) { GridCard(it, { actions.onOpen(it) }, Modifier.width(200.dp)) }
                }
            }
        }
    }
}

private fun LazyGridScope.genres(state: LibraryViewModel.State, actions: LibraryActions) {
    val tiles = state.genres
    if (tiles == null) {
        items(8) { Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) }
        return
    }
    if (tiles.isEmpty()) full("none") { Message(t("No genres yet"), t("Genres come from each title's metadata.")) }
    items(tiles, key = { it.name }) { tile -> GenreTile(tile) { actions.onGenre(tile.name) } }
}

@Composable
private fun GenreTile(tile: LibraryViewModel.GenreTile, onClick: () -> Unit) {
    val images = LocalViewer.current.images
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick)) {
        Artwork(tile.art?.let { images?.backdrop(it, 480) ?: images?.landscape(it, 480) }, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        SText(tile.name, Modifier.align(Alignment.BottomStart).padding(10.dp), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 2)
    }
}
