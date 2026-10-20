package io.github.arkmind.seedy.tv.favorites

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.FavoritesViewModel
import io.github.arkmind.seedy.core.format.episodeShort
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.libraryMetaLong
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.tv.browse.FocusWhen
import io.github.arkmind.seedy.tv.browse.TvCardSkeleton
import io.github.arkmind.seedy.tv.browse.TvGridCard
import io.github.arkmind.seedy.tv.browse.TvMessage
import io.github.arkmind.seedy.tv.browse.TvTab
import io.github.arkmind.seedy.tv.browse.maybeFocus
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** The rail's Favorites (no board: built from the library grid). */
@Composable
fun TvFavoritesPlace() {
    val session = currentSession()
    val nav = LocalTvNav.current
    val vm = viewModel { FavoritesViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    TvFavoritesScreen(state, onShow = vm::show, onOpen = nav::open)
}

/** As the TV library page: the title and count, the kinds as tabs, five cards a row by kind; people as portraits. */
@Composable
fun TvFavoritesScreen(state: FavoritesViewModel.State, onShow: (FavoritesViewModel.Group?) -> Unit, onOpen: (BaseItemDto) -> Unit) {
    var lastFocused by rememberSaveable { mutableStateOf<String?>(null) }
    val restore = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().background(SeedyColors.Bg).padding(start = RailInset, top = 64.bp), verticalArrangement = Arrangement.spacedBy(26.bp)) {
        // As tall as the library page's title row (its tool buttons), the title at the bottom.
        Box(Modifier.height(56.bp), contentAlignment = Alignment.BottomStart) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.bp)) {
                SText(t("Favorites"), Modifier.alignByBaseline(), size = 44.bsp, weight = 900, lineHeight = 1f, color = SeedyColors.White)
                if (!state.loading) SText(tn(state.total, "{n} favorite", "{n} favorites"), Modifier.alignByBaseline(), size = 19.bsp, weight = 600, color = SeedyColors.Text3)
            }
        }
        if (state.present.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                TvTab(t("All"), state.only == null, { onShow(null) })
                for ((g, list) in state.present) TvTab(t(g.label), state.only == g, { onShow(g) }, count = "${list.size}")
            }
        }
        if (!state.loading && state.present.isEmpty()) {
            TvMessage(t("No favorites yet"), t("Tap the heart on any title, episode or person and it will be kept here."))
            return@Column
        }
        LazyVerticalGrid(
            GridCells.Fixed(5),
            Modifier.fillMaxWidth().weight(1f).offset(x = (-10).bp, y = (-10).bp).padding(end = 110.bp),
            contentPadding = PaddingValues(start = 10.bp, end = 10.bp, top = 16.bp, bottom = 120.bp),
            horizontalArrangement = Arrangement.spacedBy(28.bp),
            verticalArrangement = Arrangement.spacedBy(26.bp),
        ) {
            if (state.loading) {
                items(10) { TvCardSkeleton() }
                return@LazyVerticalGrid
            }
            val firstKey = state.shown.firstOrNull()?.second?.firstOrNull()?.id?.toString()
            for ((group, list) in state.shown) {
                if (state.shown.size > 1) {
                    item(key = "h:$group", span = { GridItemSpan(maxLineSpan) }) {
                        SText(t(group.label), Modifier.padding(top = 8.bp), size = 24.bsp, weight = 800, color = SeedyColors.White)
                    }
                }
                items(list, key = { "${group.name}:${it.id}" }) { item ->
                    val key = item.id.toString()
                    val focus = Modifier.maybeFocus(if (key == (lastFocused ?: firstKey)) restore else null)
                    if (group == FavoritesViewModel.Group.People) {
                        TvPerson(item, { onOpen(item) }, focus, onFocus = { lastFocused = key })
                    } else {
                        TvGridCard(
                            item, { onOpen(item) }, focus,
                            meta = if (item.type == BaseItemKind.EPISODE) join(item.seriesName, item.episodeShort) else item.libraryMetaLong,
                            onFocus = { lastFocused = key },
                        )
                    }
                }
            }
        }
    }
    FocusWhen(!state.loading && state.present.isNotEmpty(), restore)
}

/** A person: their portrait in a circle (initials while it loads) and their name under it. */
@Composable
private fun TvPerson(person: BaseItemDto, onClick: () -> Unit, modifier: Modifier, onFocus: () -> Unit) {
    val images = LocalViewer.current.images
    TvPressable(onClick, modifier.then(Modifier.onFocused(onFocus))) { focused ->
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.bp)) {
            Box(Modifier.focusScale(focused, 1.04f).focusRing(focused, radius = 90.bp).size(180.bp).clip(CircleShape).background(Color(0xFF3D2436)), contentAlignment = Alignment.Center) {
                SText(person.name.orEmpty().split(' ').filter { it.isNotEmpty() }.take(2).joinToString("") { it.first().uppercase() }, size = 44.bsp, weight = 900, color = Color(0xFFFFB3E6))
                Artwork(images?.primary(person, 360), Modifier.matchParentSize(), placeholder = Color.Transparent)
            }
            SText(person.name.orEmpty(), size = 19.bsp, weight = if (focused) 800 else 700, color = SeedyColors.White, maxLines = 2, align = TextAlign.Center)
        }
    }
}

private fun Modifier.onFocused(block: () -> Unit): Modifier = this.onFocusChanged { if (it.isFocused) block() }
