package io.github.arkmind.seedy.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SeerrTitlesViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.requests.TitlesKind
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.library.Message
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** A Discover row's "See all": its titles three to a row, more as the grid scrolls (no board: the row's cards). */
@Composable
fun SeerrTitlesRoute(kindKey: String) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val kind = TitlesKind.entries.firstOrNull { it.key == kindKey } ?: TitlesKind.Trending
    val vm = viewModel(key = "titles:$kindKey") { SeerrTitlesViewModel(session, kind) }
    val state by vm.state.collectAsStateWithLifecycle()
    SeerrTitlesScreen(kind, state, onBack = nav::back, onTitle = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) }, onMore = vm::more)
}

@Composable
fun SeerrTitlesScreen(kind: TitlesKind, state: SeerrTitlesViewModel.State, onBack: () -> Unit, onTitle: (SeerrTitle) -> Unit, onMore: () -> Unit) {
    val grid = rememberLazyGridState()
    LaunchedEffect(grid, state.items?.size) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (last >= grid.layoutInfo.totalItemsCount - 6) onMore()
        }
    }
    LazyVerticalGrid(
        GridCells.Fixed(3),
        Modifier.fillMaxSize().background(SeedyColors.Bg),
        state = grid,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "head", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(top = topInset() - 4.dp).offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(WebIcons.ArrowLeft, onBack, Modifier.padding(end = 4.dp))
                SText(t(kind.title), Modifier.weight(1f), size = 20.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
            }
        }
        val items = state.items
        when {
            items == null -> items(9) { Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface)) }
            items.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                if (state.failed) Message(t("Couldn't reach Seerr"), t("Try again in a moment."), t("Try again"), onMore)
                else Message(t("Nothing here right now"), t("Seerr has no titles for this list."))
            }
            else -> items(items, key = { "${it.mediaType}:${it.tmdbId}" }) { SeerrTitleCard(it, kind.upcoming, { onTitle(it) }, Modifier.fillMaxWidth()) }
        }
    }
}
