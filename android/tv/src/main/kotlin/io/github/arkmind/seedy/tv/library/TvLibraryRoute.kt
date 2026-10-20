package io.github.arkmind.seedy.tv.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.browse.Filters
import io.github.arkmind.seedy.core.browse.Flag
import io.github.arkmind.seedy.core.browse.LibraryTab
import io.github.arkmind.seedy.core.browse.Letters
import io.github.arkmind.seedy.core.browse.Sort
import io.github.arkmind.seedy.core.browse.letterOf
import io.github.arkmind.seedy.core.feature.LibraryViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.tv.browse.FocusWhen
import io.github.arkmind.seedy.tv.browse.TvCardSkeleton
import io.github.arkmind.seedy.tv.browse.TvChoice
import io.github.arkmind.seedy.tv.browse.TvGridCard
import io.github.arkmind.seedy.tv.browse.TvMessage
import io.github.arkmind.seedy.tv.browse.TvPanelLabel
import io.github.arkmind.seedy.tv.browse.TvSidePanel
import io.github.arkmind.seedy.tv.browse.TvTab
import io.github.arkmind.seedy.tv.browse.TvToolButton
import io.github.arkmind.seedy.tv.browse.maybeFocus
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Board "Android TV · Movies library": one library's grid (also the rail's Movies and Shows). */
@Composable
fun TvLibraryRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalTvNav.current
    val vm = viewModel(key = "library:$id") { LibraryViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    var panel by remember { mutableStateOf<String?>(null) }
    var jumpTo by remember { mutableStateOf<Int?>(null) }
    Box(Modifier.fillMaxSize()) {
        TvLibraryScreen(
            state,
            TvLibraryActions(
                onSort = { panel = "sort" },
                onFilters = { vm.loadOptions(); panel = "filters" },
                onShuffle = { vm.shuffle(nav::play) },
                onTab = vm::setTab,
                onOpen = nav::open,
                onGenre = vm::openGenre,
                onMore = { vm.loadMore() },
                onLetter = { letter -> vm.jump(letter) { jumpTo = it } },
            ),
            jumpTo = jumpTo,
            onJumped = { jumpTo = null },
        )
        when (panel) {
            "sort" -> TvSortPanel(state.sort, state.descending, onPick = { s, d -> vm.setSort(s, d); panel = null }, onDismiss = { panel = null })
            "filters" -> TvFilterPanel(state, onChange = vm::setFilters, onDismiss = { panel = null })
        }
    }
}

class TvLibraryActions(
    val onSort: () -> Unit = {},
    val onFilters: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onTab: (LibraryTab) -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onGenre: (String) -> Unit = {},
    val onMore: () -> Unit = {},
    val onLetter: (Char) -> Unit = {},
)

/** "4K and Thriller", "4K, HD and Thriller". */
fun andList(parts: List<String>): String =
    if (parts.size <= 1) parts.joinToString("") else t("{list} and {last}", "list" to parts.dropLast(1).joinToString(", "), "last" to parts.last())

/**
 * The library's name and size, Sort, Filters and Shuffle; its views and what the filters keep; five cards a row with
 * the A–Z rail at the right (OK on a letter jumps there, in title order).
 */
@Composable
fun TvLibraryScreen(
    state: LibraryViewModel.State,
    actions: TvLibraryActions,
    jumpTo: Int? = null,
    onJumped: () -> Unit = {},
    grid: LazyGridState = rememberLazyGridState(),
    initialFocus: Int = 0,
) {
    // The card the remote was on comes back with it (from a title's page).
    var lastFocused by rememberSaveable { mutableIntStateOf(initialFocus) }
    val restore = remember { FocusRequester() }
    val jump = remember { FocusRequester() }
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > grid.layoutInfo.totalItemsCount - 15 } }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.isGrid) actions.onMore() }
    LaunchedEffect(jumpTo) {
        val i = jumpTo ?: return@LaunchedEffect
        grid.scrollToItem(i)
        jump.focusSoon()
        onJumped()
    }
    val current by remember { derivedStateOf { state.items.getOrNull(grid.firstVisibleItemIndex)?.let(::letterOf) } }

    Column(
        Modifier.fillMaxSize().background(SeedyColors.Bg).padding(start = RailInset, top = 64.bp),
        verticalArrangement = Arrangement.spacedBy(26.bp),
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 96.bp), verticalAlignment = Alignment.Bottom) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(16.bp)) {
                SText(state.view?.name.orEmpty(), Modifier.alignByBaseline(), size = 44.bsp, weight = 900, lineHeight = 1f, color = SeedyColors.White, maxLines = 1)
                state.total?.let { SText(tn(it, "{n} title", "{n} titles"), Modifier.alignByBaseline(), size = 19.bsp, weight = 600, color = SeedyColors.Text3) }
            }
            if (state.isGrid && state.view != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                    TvToolButton(WebIcons.Sort, t(state.sort.label), actions.onSort)
                    if (state.tab != LibraryTab.Collections) TvToolButton(WebIcons.Filters, t("Filters"), actions.onFilters, badge = state.filters.count)
                    TvToolButton(WebIcons.Shuffle, t("Shuffle"), actions.onShuffle)
                }
            }
        }
        if (state.view != null) {
            Row(Modifier.fillMaxWidth().padding(end = 96.bp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                    for ((tab, label) in state.tabs) TvTab(t(label), tab == state.tab, { actions.onTab(tab) })
                }
                if (state.filters.count > 0) SText(t("Filtered by {filters}", "filters" to andList(state.filters.labels)), size = 17.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
        }
        when {
            state.error -> TvMessage(t("Couldn't open this library"), t("The server didn’t answer. Check your connection and try again."))
            state.tab == LibraryTab.Suggestions -> Suggestions(state, actions)
            state.tab == LibraryTab.Genres -> Genres(state, actions)
            state.items.isEmpty() && !state.loading -> TvMessage(
                if (state.filters.count > 0) t("Nothing matches these filters") else t("{name} is empty", "name" to state.view?.name.orEmpty()),
                if (state.filters.count > 0) t("Try removing a filter or two.") else t("Once the server finds media in this library it will show up here."),
            )
            // 10 px of room around the cards keeps the focused card's ring inside the grid (it clips): the grid starts
            // 10 px up and left of the board's and pads its cards back into place.
            else -> Row(Modifier.fillMaxWidth().weight(1f).offset(x = (-10).bp, y = (-10).bp).padding(end = 54.bp), horizontalArrangement = Arrangement.spacedBy(14.bp)) {
                LazyVerticalGrid(
                    GridCells.Fixed(5),
                    Modifier.weight(1f).fillMaxHeight(),
                    state = grid,
                    contentPadding = PaddingValues(start = 10.bp, end = 10.bp, top = 16.bp, bottom = 120.bp),
                    horizontalArrangement = Arrangement.spacedBy(28.bp),
                    verticalArrangement = Arrangement.spacedBy(26.bp),
                ) {
                    if (state.items.isEmpty()) {
                        items(10) { TvCardSkeleton() }
                    } else {
                        itemsIndexed(state.items, key = { _, it -> it.id }) { i, item ->
                            TvGridCard(
                                item, { actions.onOpen(item) },
                                Modifier.maybeFocus(if (i == jumpTo) jump else if (i == lastFocused) restore else null),
                                onFocus = { lastFocused = i },
                            )
                        }
                    }
                }
                if (state.itemsTotal > 1 && state.items.isNotEmpty()) LetterRail(current, actions.onLetter)
            }
        }
    }
    FocusWhen(state.items.isNotEmpty() && jumpTo == null, restore)
}

/** The A–Z rail: the letter of the first title in view in the accent; the remote can walk it and jump. */
@Composable
private fun LetterRail(current: Char?, onLetter: (Char) -> Unit) {
    val accent = LocalAccent.current
    Column(Modifier.width(32.bp).padding(top = 6.bp + 10.bp), verticalArrangement = Arrangement.spacedBy(2.bp), horizontalAlignment = Alignment.CenterHorizontally) {
        for (ch in Letters) {
            TvPressable({ onLetter(ch) }) { focused ->
                Box(
                    Modifier.width(32.bp).height(24.bp).clip(RoundedCornerShape(8.bp)).background(if (focused) SeedyColors.Inverse else Color.Transparent),
                    contentAlignment = Alignment.Center,
                ) {
                    SText(
                        ch.toString(), size = 14.bsp, weight = 800, lineHeight = 24f / 14f,
                        color = when {
                            focused -> SeedyColors.InkInverse
                            ch == current -> accent
                            else -> SeedyColors.Text3
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Suggestions(state: LibraryViewModel.State, actions: TvLibraryActions) {
    val rows = state.suggestions
    val first = remember { FocusRequester() }
    if (rows == null) return
    if (rows.isEmpty()) {
        TvMessage(t("Nothing to suggest yet"), t("Watch a few titles and suggestions will show up here."))
        return
    }
    LazyColumn(Modifier.fillMaxSize().offset(x = (-10).bp), contentPadding = PaddingValues(start = 10.bp, bottom = 120.bp), verticalArrangement = Arrangement.spacedBy(20.bp)) {
        items(rows, key = { it.title }) { row ->
            Column(verticalArrangement = Arrangement.spacedBy(4.bp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.bp)) {
                    SText(row.title, size = 24.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    if (row.sub.isNotEmpty()) SText(row.sub, Modifier.padding(bottom = 2.bp), size = 17.bsp, color = SeedyColors.Text3, maxLines = 1)
                }
                LazyRow(Modifier.offset(x = (-10).bp), contentPadding = PaddingValues(start = 10.bp, end = 96.bp, top = 14.bp, bottom = 10.bp), horizontalArrangement = Arrangement.spacedBy(28.bp)) {
                    items(row.items, key = { it.id }) { item ->
                        TvGridCard(item, { actions.onOpen(item) }, Modifier.width(302.bp).maybeFocus(if (row == rows.first() && item == row.items.first()) first else null))
                    }
                }
            }
        }
    }
    FocusWhen(true, first)
}

@Composable
private fun Genres(state: LibraryViewModel.State, actions: TvLibraryActions) {
    val tiles = state.genres ?: return
    val images = LocalViewer.current.images
    val first = remember { FocusRequester() }
    if (tiles.isEmpty()) {
        TvMessage(t("No genres yet"), t("Genres come from each title's metadata."))
        return
    }
    LazyVerticalGrid(
        GridCells.Fixed(5), Modifier.fillMaxSize().offset(x = (-10).bp, y = (-10).bp).padding(end = 54.bp + 46.bp),
        contentPadding = PaddingValues(start = 10.bp, end = 10.bp, top = 16.bp, bottom = 120.bp),
        horizontalArrangement = Arrangement.spacedBy(28.bp), verticalArrangement = Arrangement.spacedBy(28.bp),
    ) {
        itemsIndexed(tiles, key = { _, it -> it.name }) { i, tile ->
            TvPressable({ actions.onGenre(tile.name) }, if (i == 0) Modifier.focusRequester(first) else Modifier) { focused ->
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(focused, radius = 16.bp).clip(RoundedCornerShape(16.bp))) {
                    Artwork(tile.art?.let { images?.backdrop(it, 640) ?: images?.landscape(it, 640) }, Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
                    SText(tile.name, Modifier.align(Alignment.BottomStart).padding(18.bp), size = 24.bsp, weight = 800, color = SeedyColors.White, maxLines = 2)
                }
            }
        }
    }
    FocusWhen(true, first)
}

/** Sort: the orders, then ascending or descending. */
@Composable
fun TvSortPanel(sort: Sort, descending: Boolean, onPick: (Sort, Boolean) -> Unit, onDismiss: () -> Unit) {
    TvSidePanel(t("Sort by"), onDismiss) { first ->
        for (s in Sort.entries) TvChoice(t(s.label), s == sort, { onPick(s, if (s == sort) descending else s.descending) }, if (s == sort) Modifier.focusRequester(first) else Modifier)
        if (sort != Sort.Random) {
            TvPanelLabel(t("Order"))
            TvChoice(t("Ascending"), !descending, { onPick(sort, false) })
            TvChoice(t("Descending"), descending, { onPick(sort, true) })
        }
    }
}

/** Filters: each choice applies at once (the grid behind follows). */
@Composable
fun TvFilterPanel(state: LibraryViewModel.State, onChange: (Filters) -> Unit, onDismiss: () -> Unit) {
    val f = state.filters
    TvSidePanel(t("Filters"), onDismiss) { first ->
        TvChoice(t("Clear all"), false, { onChange(Filters()) }, Modifier.focusRequester(first), sub = if (f.count > 0) andList(f.labels) else null)
        TvPanelLabel(t("Show only"))
        for (flag in Flag.entries.take(4)) TvChoice(t(flag.label), flag in f.flags, { onChange(f.toggle(flag)) })
        TvPanelLabel(t("Quality"))
        for (flag in Flag.entries.drop(4)) TvChoice(t(flag.label), flag in f.flags, { onChange(f.toggle(flag)) })
        val o = state.options ?: return@TvSidePanel
        if (o.genres.isNotEmpty()) {
            TvPanelLabel(t("Genres"))
            for (g in o.genres) TvChoice(g, g in f.genres, { onChange(f.toggleGenre(g)) })
        }
        if (o.decades.isNotEmpty()) {
            TvPanelLabel(t("Decades"))
            for (d in o.decades) TvChoice("${d}s", d in f.decades, { onChange(f.toggleDecade(d)) })
        }
        if (o.ratings.isNotEmpty()) {
            TvPanelLabel(t("Parental rating"))
            for (r in o.ratings) TvChoice(r, r in f.ratings, { onChange(f.toggleRating(r)) })
        }
    }
}
