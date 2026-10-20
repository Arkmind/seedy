package io.github.arkmind.seedy.mobile.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.FavoritesViewModel
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.libraryMeta
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.browse.Chip
import io.github.arkmind.seedy.mobile.browse.ChipRow
import io.github.arkmind.seedy.mobile.browse.GridCard
import io.github.arkmind.seedy.mobile.browse.GridCardSkeleton
import io.github.arkmind.seedy.mobile.browse.Portrait
import io.github.arkmind.seedy.mobile.browse.TabHeader
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** The Favorites tab (no board: built from the library grid). */
@Composable
fun FavoritesTab() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { FavoritesViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    FavoritesScreen(state, onShow = vm::show, onOpen = nav::open, onSearch = { nav.go(Route.Search) }, bottomPadding = tabBarHeight())
}

/**
 * The Libraries tab's header, the library page's chips (All, Movies 12, Shows 3…) and its 2-column cards, one section
 * per kind; people sit three to a row as portraits.
 */
@Composable
fun FavoritesScreen(
    state: FavoritesViewModel.State,
    onShow: (FavoritesViewModel.Group?) -> Unit,
    onOpen: (BaseItemDto) -> Unit,
    onSearch: () -> Unit,
    bottomPadding: Dp,
) {
    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize().background(SeedyColors.Bg),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = bottomPadding + 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        full("top") {
            Column(Modifier.bleed(20.dp)) {
                TabHeader(t("Favorites"), onSearch, count = if (state.loading) null else tn(state.total, "{n} favorite", "{n} favorites"))
                if (state.present.size > 1) {
                    ChipRow(Modifier.padding(top = 12.dp)) {
                        item { Chip(t("All"), state.only == null, { onShow(null) }) }
                        items(state.present, key = { it.first }) { (g, list) -> Chip(t(g.label), state.only == g, { onShow(g) }, count = "${list.size}") }
                    }
                }
            }
        }
        when {
            state.loading -> items(6) { GridCardSkeleton() }
            state.present.isEmpty() -> full("empty") { Message(t("No favorites yet"), t("Tap the heart on any title, episode or person and it will be kept here.")) }
            else -> for ((group, list) in state.shown) section(group, list, onOpen)
        }
    }
}

private fun LazyGridScope.section(group: FavoritesViewModel.Group, list: List<BaseItemDto>, onOpen: (BaseItemDto) -> Unit) {
    full("head:$group") {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(t(group.label), size = 16.sp, weight = 800, color = SeedyColors.White)
            SText(
                if (group == FavoritesViewModel.Group.People) tn(list.size, "{n} person", "{n} people") else tn(list.size, "{n} title", "{n} titles"),
                size = 12.sp, color = SeedyColors.Text3,
            )
        }
    }
    if (group == FavoritesViewModel.Group.People) {
        // Three portraits a row.
        for (row in list.chunked(3)) {
            full("p:${row.first().id}") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (person in row) {
                        Column(Modifier.weight(1f).tap { onOpen(person) }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Portrait(person, 88.dp, letters = 26.sp)
                            SText(person.name.orEmpty(), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 2, align = TextAlign.Center)
                        }
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    } else {
        items(list, key = { "${group.name}:${it.id}" }) { item ->
            GridCard(item, { onOpen(item) }, meta = if (item.type == BaseItemKind.EPISODE) join(item.seriesName, item.episodeTag) else item.libraryMeta)
        }
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) = item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }

private fun Modifier.bleed(h: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = h.roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2))
    layout(p.width - extra * 2, p.height) { p.place(-extra, 0) }
}
