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
import androidx.compose.foundation.lazy.LazyListScope
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
import io.github.arkmind.seedy.core.photos.Memory
import io.github.arkmind.seedy.core.photos.PhotosTab
import io.github.arkmind.seedy.core.photos.PhotosViewModel
import io.github.arkmind.seedy.core.photos.countsLine
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

class PhotosActions(
    val onBack: () -> Unit = {},
    val onSlideshow: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onTab: (PhotosTab) -> Unit = {},
    val onCamera: (String) -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onMemory: (Memory) -> Unit = {},
    val onFolder: (BaseItemDto) -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * A photos library on the phone (the web's Photos · Timeline board in the phone kit): back, the library's name and
 * counts, Slideshow and ⋯; the views as chips; then Timeline ("On this day", then months and days in justified rows),
 * Folders, Videos, Favorites or Cameras.
 */
@Composable
fun PhotosScreen(state: PhotosViewModel.State, actions: PhotosActions, bottomPadding: Dp, modifier: Modifier = Modifier) {
    val gap = 3.dp
    BoxWithConstraints(modifier.fillMaxSize().background(SeedyColors.Bg)) {
        val density = LocalDensity.current
        val width = with(density) { (maxWidth - 40.dp).toPx() }
        val rowPx = with(density) { state.size.row.dp.toPx() }
        val gapPx = with(density) { gap.toPx() }
        val months = remember(state.items, state.cameras, state.camera, state.tab) { state.months }
        val lines = remember(months, width, rowPx) { timelineLines(months, width, rowPx, gapPx) }
        val list = rememberLazyListState()
        val nearEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 8 } == true } }
        LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && !state.done && !state.loading) actions.onLoadMore() }
        LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
            item(key = "head") { Header(state, actions) }
            item(key = "chips") {
                LazyRow(Modifier.fillMaxWidth().padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(PhotosTab.entries, key = { it.name }) { tb -> PhotoChip(t(tb.label), tb == state.tab) { actions.onTab(tb) } }
                }
            }
            when (state.tab) {
                PhotosTab.Folders -> folders(state, actions)
                PhotosTab.Cameras -> {
                    val cams = state.cameras
                    when {
                        cams == null && state.error -> item(key = "error") { EmptyNote(t("Couldn’t load the photos"), action = t("Try again"), onAction = actions.onRetry) }
                        cams == null -> item(key = "loading") { Loading() }
                        cams.isEmpty() -> item(key = "empty") { EmptyNote(t("No photos here yet")) }
                        else -> {
                            val current = state.camera ?: cams.first().first
                            item(key = "cams") {
                                LazyRow(Modifier.fillMaxWidth().padding(top = 14.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(cams, key = { it.first }) { (name, items) ->
                                        PhotoChip(name.ifEmpty { noCamera() }, name == current, icon = PhotoIcons.Camera, count = items.size.toString()) { actions.onCamera(name) }
                                    }
                                }
                            }
                            lines(lines, gap, actions)
                        }
                    }
                }
                else -> {
                    if (state.tab == PhotosTab.Timeline && state.memories.isNotEmpty()) memories(state.memories, actions)
                    when {
                        state.items.isEmpty() && state.error -> item(key = "error") { EmptyNote(t("Couldn’t load the photos"), action = t("Try again"), onAction = actions.onRetry) }
                        state.items.isEmpty() && state.loading -> item(key = "loading") { Loading() }
                        state.items.isEmpty() -> item(key = "empty") {
                            when (state.tab) {
                                PhotosTab.Favorites -> EmptyNote(t("No favorite photos yet"), t("Tap the heart on a photo to keep it here."))
                                PhotosTab.Videos -> EmptyNote(t("No videos here"))
                                else -> EmptyNote(t("No photos here yet"), t("Photos added to this library’s folders show up here, newest first."))
                            }
                        }
                        else -> {
                            lines(lines, gap, actions)
                            if (state.loading) item(key = "more") { Loading(small = true) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(state: PhotosViewModel.State, actions: PhotosActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderButton(PhotoIcons.Back, actions.onBack)
        Column(Modifier.weight(1f).padding(start = 6.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(state.name, size = 22.sp, weight = 900, lineHeight = 1.15f, color = SeedyColors.White, maxLines = 1)
            state.photos?.let { p -> SText(countsLine(p, state.videos ?: 0), size = 12.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1) }
        }
        HeaderButton(PhotoIcons.Slideshow, actions.onSlideshow)
        HeaderButton(PhotoIcons.More, actions.onMore)
    }
}

private fun LazyListScope.memories(list: List<Memory>, actions: PhotosActions) {
    item(key = "mem-head") {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 12.dp)) {
            SText(t("On this day"), size = 17.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        }
    }
    item(key = "mem") {
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.key }) { m -> MemoryCard(m) { actions.onMemory(m) } }
        }
    }
}

private fun LazyListScope.lines(lines: List<Line>, gap: Dp, actions: PhotosActions) {
    items(lines, key = { it.key }, contentType = { it::class }) { line ->
        when (line) {
            is Line.Month -> MonthHead(line.title, line.first)
            is Line.Day -> DayHead(line.title, line.sub)
            is Line.Tiles -> TileLine(line.row, gap, actions.onOpen, Modifier.padding(start = 20.dp, end = 20.dp, bottom = gap))
        }
    }
}

private fun LazyListScope.folders(state: PhotosViewModel.State, actions: PhotosActions) {
    val folders = state.folders
    when {
        folders == null && state.error -> item(key = "error") { EmptyNote(t("Couldn’t load the photos"), action = t("Try again"), onAction = actions.onRetry) }
        folders == null -> item(key = "loading") { Loading() }
        folders.isEmpty() -> item(key = "empty") { EmptyNote(t("No folders here"), t("Photos sitting in the library’s own folder show on the timeline.")) }
        else -> {
            item(key = "f-gap") { Spacer(Modifier.height(20.dp)) }
            items(folders.chunked(2), key = { "f-" + it.first().id }) { pair ->
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (f in pair) FolderCard(f, state.folderInfo[f.id], Modifier.weight(1f)) { actions.onFolder(f) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
internal fun Loading(small: Boolean = false) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = if (small) 12.dp else 24.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val rows = if (small) 1 else 4
        repeat(rows) {
            Row(Modifier.fillMaxWidth().height(112.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Skeleton(Modifier.weight(1.5f).fillMaxSize())
                Skeleton(Modifier.weight(0.75f).fillMaxSize())
                Skeleton(Modifier.weight(1.33f).fillMaxSize())
            }
        }
    }
}
