package io.github.arkmind.seedy.mobile.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.photos.FolderShow
import io.github.arkmind.seedy.core.photos.FolderSort
import io.github.arkmind.seedy.core.photos.PhotoFolderViewModel
import io.github.arkmind.seedy.core.photos.countsLine
import io.github.arkmind.seedy.core.photos.dateRange
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

class PhotoFolderActions(
    val onBack: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onSlideshow: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    /** Null: this profile can't download. */
    val onDownload: (() -> Unit)? = null,
    val onFolder: (BaseItemDto) -> Unit = {},
    val onShow: (FolderShow) -> Unit = {},
    val onSort: () -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * A folder of photos on the phone (the web's Photos · Folder board in the phone kit): back and ⋯ over three of its
 * photos, "Folder", its name, counts, dates and path, Slideshow, Shuffle and Download, the folders inside it, then
 * All · Photos · Videos · Favorites with the sort, and its photos by day.
 */
@Composable
fun PhotoFolderScreen(state: PhotoFolderViewModel.State, actions: PhotoFolderActions, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val gap = 3.dp
    BoxWithConstraints(modifier.fillMaxSize().background(SeedyColors.Bg)) {
        val density = LocalDensity.current
        val width = with(density) { (maxWidth - 40.dp).toPx() }
        val rowPx = with(density) { state.size.row.dp.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val name = state.item?.name.orEmpty()
        val lines = remember(state.items, state.sort, width, rowPx) {
            if (state.sort == FolderSort.Name) {
                justify(state.items, width, rowPx, gapPx).mapIndexed { i, r -> Line.Tiles(r, "n$i-${r.first}") }
            } else {
                val months = state.months
                timelineLines(months, width, rowPx, gapPx, own = name, headings = months.size > 1)
            }
        }
        val list = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 8 } == true } }
        LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && !state.done && !state.loading) actions.onLoadMore() }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()), verticalAlignment = Alignment.CenterVertically) {
                    HeaderButton(PhotoIcons.Back, actions.onBack)
                    Spacer(Modifier.weight(1f))
                    HeaderButton(PhotoIcons.More, actions.onMore)
                }
            }
            if (state.item == null) {
                item(key = "state") { if (state.error) EmptyNote(t("Couldn’t load the photos"), action = t("Try again"), onAction = actions.onRetry) else Loading() }
                return@LazyColumn
            }
            item(key = "hero") { Hero(state, actions) }
            val info = state.info
            if ((info?.count ?: 1) > 0) {
                item(key = "show") {
                    LazyRow(Modifier.fillMaxWidth().padding(top = 24.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(FolderShow.entries, key = { it.name }) { s -> PhotoChip(t(s.label), s == state.show) { actions.onShow(s) } }
                        item(key = "sort") { PhotoChip(t(state.sort.label), false, icon = PhotoIcons.Sort, onClick = actions.onSort) }
                    }
                }
            }
            when {
                state.items.isEmpty() && state.error -> item(key = "error") { EmptyNote(t("Couldn’t load the photos"), action = t("Try again"), onAction = actions.onRetry) }
                state.items.isEmpty() && state.loading -> item(key = "loading") { Loading() }
                state.items.isEmpty() && state.subfolders.isNotEmpty() && state.show == FolderShow.All -> {
                    item(key = "f-gap") { Spacer(Modifier.height(20.dp)) }
                    items(state.subfolders.chunked(2), key = { "f-" + it.first().id }) { pair ->
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (f in pair) FolderCard(f, null, Modifier.weight(1f)) { actions.onFolder(f) }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
                state.items.isEmpty() -> item(key = "empty") {
                    EmptyNote(if (state.show == FolderShow.Favorites) t("No favorite photos yet") else if (state.show == FolderShow.Videos) t("No videos here") else t("No photos here yet"))
                }
                else -> {
                    if (state.sort == FolderSort.Name) item(key = "n-gap") { Spacer(Modifier.height(18.dp)) }
                    items(lines, key = { it.key }, contentType = { it::class }) { line ->
                        when (line) {
                            is Line.Month -> MonthHead(line.title, line.first)
                            is Line.Day -> DayHead(line.title, line.sub)
                            is Line.Tiles -> TileLine(line.row, gap, actions.onOpen, Modifier.padding(start = 20.dp, end = 20.dp, bottom = gap))
                        }
                    }
                    if (state.loading) item(key = "more") { Loading(small = true) }
                }
            }
        }
    }
}

@Composable
private fun Hero(state: PhotoFolderViewModel.State, actions: PhotoFolderActions) {
    val item = state.item ?: return
    val info = state.info
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
        Mosaic(info?.covers.orEmpty(), Modifier.fillMaxWidth().height(230.dp), 20.dp, 230.dp)
        Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            SIcon(PhotoIcons.Folder, size = 14.dp, color = SeedyColors.TextHero)
            SText(t("Folder"), size = 12.sp, weight = 800, tracking = 0.12f, uppercase = true, color = SeedyColors.TextHero)
        }
        SText(item.name.orEmpty(), Modifier.padding(top = 10.dp), size = 32.sp, weight = 900, tracking = -0.02f, lineHeight = 1.0f, color = SeedyColors.White)
        val counts = info?.let { listOf(countsLine(it.photos, it.videos), dateRange(it.from, it.to)).filter { s -> s.isNotEmpty() }.joinToString(" · ") }
        SText(counts ?: " ", Modifier.padding(top = 10.dp), size = 14.sp, lineHeight = 1.45f, color = SeedyColors.TextHero)
        item.path?.let { SText(it, Modifier.padding(top = 4.dp), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3) }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PhotoButton(PhotoIcons.Slideshow, t("Slideshow"), primary = true, modifier = Modifier.weight(1f), onClick = actions.onSlideshow)
            PhotoButton(PhotoIcons.Shuffle, t("Shuffle"), modifier = Modifier.weight(1f), onClick = actions.onShuffle)
            actions.onDownload?.let { PhotoButton(PhotoIcons.Download, null, onClick = it) }
        }
        if (state.subfolders.isNotEmpty()) {
            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.subfolders, key = { it.id }) { f ->
                        PhotoChip(f.name.orEmpty(), false, icon = PhotoIcons.Folder, count = state.subCounts[f.id]?.toString()) { actions.onFolder(f) }
                    }
                }
            }
        }
    }
}
