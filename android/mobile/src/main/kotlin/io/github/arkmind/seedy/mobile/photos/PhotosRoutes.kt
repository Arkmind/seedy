package io.github.arkmind.seedy.mobile.photos

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.photos.FolderSort
import io.github.arkmind.seedy.core.photos.PhotoFolderViewModel
import io.github.arkmind.seedy.core.photos.PhotoQueue
import io.github.arkmind.seedy.core.photos.PhotosApi
import io.github.arkmind.seedy.core.photos.PhotosViewModel
import io.github.arkmind.seedy.core.photos.TileSize
import io.github.arkmind.seedy.core.photos.fileName
import io.github.arkmind.seedy.core.photos.isVideo
import io.github.arkmind.seedy.core.photos.withFavorite
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManagePerms
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.PickOption
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** A photos library (Home videos and photos): Timeline, Folders, Videos, Favorites, Cameras. */
@Composable
fun PhotosRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel(key = "photos:$id") { PhotosViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val api = remember(session) { PhotosApi(session) }
    val admin = remember(session) { ManagePerms.of(session).admin }
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    fun view(list: List<BaseItemDto>, at: BaseItemDto, play: Boolean = false, paged: Boolean = true) {
        vm.share(list, paged)
        nav.go(Route.PhotoViewer(at.id, play))
    }
    CompositionLocalProvider(LocalPhotosApi provides api) {
        Box(Modifier.fillMaxSize()) {
            PhotosScreen(
                state,
                PhotosActions(
                    onBack = nav::back,
                    onSlideshow = {
                        val first = state.list.firstOrNull { !it.isVideo }
                        if (first != null) view(state.list, first, play = true) else message = t("No photos here yet")
                    },
                    onMore = { menu = true },
                    onTab = { vm.open(it) },
                    onCamera = vm::setCamera,
                    onOpen = { view(state.list, it) },
                    onMemory = { m -> view(m.items, m.items.first(), paged = false) },
                    onFolder = { nav.go(Route.PhotoFolder(it.id)) },
                    onLoadMore = vm::loadMore,
                    onRetry = vm::refresh,
                ),
                bottomPadding = 0.dp,
            )
            if (menu) {
                val entries = buildList {
                    add(MenuEntry(PhotoIcons.Grid, t("Tile size"), t(state.size.label), more = true, run = { sizing = true }))
                    add(MenuEntry(PhotoIcons.Refresh, t("Refresh"), run = { vm.refresh() }))
                    if (admin) {
                        add(MenuEntry(PhotoIcons.Refresh, t("Scan library files"), t("Look for new and changed media"), line = true, run = {
                            scope.launch {
                                val ok = runCatching { api.scan(id) }.isSuccess
                                message = if (ok) t("Scanning {name}", "name" to state.name) else t("Couldn’t start the scan")
                            }
                        }))
                    }
                }
                ItemMenuSheet(state.name, entries) { menu = false }
            }
            if (sizing) SizeSheet(state.size, vm::setSize) { sizing = false }
            Toast(message, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

/** A folder of photos; [photo] opens the viewer on it straight away (a link to a photo). */
@Composable
fun PhotoFolderRoute(id: UUID, photo: UUID? = null) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel(key = "photo-folder:$id") { PhotoFolderViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val api = remember(session) { PhotosApi(session) }
    val canDownload = session.user.policy?.enableContentDownloading == true
    var menu by remember { mutableStateOf(false) }
    var sorting by remember { mutableStateOf(false) }
    var sizing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(photo, state.items.isNotEmpty()) {
        if (photo != null && !opened && (state.items.isNotEmpty() || state.done)) {
            opened = true
            vm.share()
            nav.go(Route.PhotoViewer(photo, false))
        }
    }
    CompositionLocalProvider(LocalPhotosApi provides api) {
        Box(Modifier.fillMaxSize()) {
            PhotoFolderScreen(
                state,
                PhotoFolderActions(
                    onBack = nav::back,
                    onMore = { menu = true },
                    onSlideshow = {
                        val first = state.items.firstOrNull { !it.isVideo }
                        if (first != null) { vm.share(); nav.go(Route.PhotoViewer(first.id, true)) } else message = t("No photos here yet")
                    },
                    onShuffle = { vm.shuffled { list -> nav.go(Route.PhotoViewer(list.first().id, true)) } },
                    onDownload = if (canDownload) ({ vm.all { list -> message = downloadAll(context, api, list) } }) else null,
                    onFolder = { nav.go(Route.PhotoFolder(it.id)) },
                    onShow = vm::setShow,
                    onSort = { sorting = true },
                    onOpen = { vm.share(); nav.go(Route.PhotoViewer(it.id, false)) },
                    onLoadMore = vm::loadMore,
                    onRetry = vm::load,
                ),
                bottomPadding = 0.dp,
            )
            if (menu) {
                val entries = buildList {
                    add(MenuEntry(PhotoIcons.Sort, t("Sort by"), t(state.sort.label), more = true, run = { sorting = true }))
                    add(MenuEntry(PhotoIcons.Grid, t("Tile size"), t(state.size.label), more = true, run = { sizing = true }))
                    state.library?.let { lib -> add(MenuEntry(PhotoIcons.Grid, lib.name.orEmpty(), t("Back to {name}", "name" to lib.name.orEmpty()), run = { nav.go(Route.Photos(lib.id)) })) }
                    add(MenuEntry(PhotoIcons.Refresh, t("Refresh"), run = { vm.load() }))
                }
                ItemMenuSheet(state.item?.name.orEmpty(), entries) { menu = false }
            }
            if (sorting) {
                BottomSheet({ sorting = false }) {
                    SheetHead(t("Sort by"))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
                        for (s in FolderSort.entries) PickOption(t(s.label), s == state.sort) { vm.setSort(s); sorting = false }
                    }
                }
            }
            if (sizing) SizeSheet(state.size, vm::setSize) { sizing = false }
            Toast(message, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
}

@Composable
private fun SizeSheet(current: TileSize, onPick: (TileSize) -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose) {
        SheetHead(t("Tile size"))
        Column(Modifier.weight(1f, fill = false).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (s in TileSize.entries) PickOption(t(s.label), s == current) { onPick(s); onClose() }
        }
    }
}

/** The full-screen viewer over the list the page handed it ([PhotoQueue]); [play] starts the slideshow. */
@Composable
fun PhotoViewerRoute(id: UUID, play: Boolean) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val api = remember(session) { PhotosApi(session) }
    val scope = rememberCoroutineScope()
    val queue by PhotoQueue.state.collectAsStateWithLifecycle()
    var alone by remember { mutableStateOf<BaseItemDto?>(null) }
    var playing by remember { mutableStateOf(play) }
    var info by remember { mutableStateOf<BaseItemDto?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val listed = queue?.items?.takeIf { l -> l.any { it.id == id } }
    LaunchedEffect(id, listed == null) { if (listed == null) alone = runCatching { api.item(id) }.getOrNull() }
    val items = listed ?: listOfNotNull(alone)
    val start = remember(items.isNotEmpty()) { items.indexOfFirst { it.id == id }.coerceAtLeast(0) }
    val canDownload = session.user.policy?.enableContentDownloading == true
    CompositionLocalProvider(LocalPhotosApi provides api) {
        Box(Modifier.fillMaxSize()) {
            if (items.isNotEmpty()) {
                PhotoViewerScreen(
                    items, start, if (listed != null) queue?.total ?: items.size else 1, playing,
                    ViewerActions(
                        onClose = nav::back,
                        onFavorite = { item ->
                            val on = item.userData?.isFavorite != true
                            val next = item.withFavorite(on)
                            if (listed != null) PhotoQueue.patch(next) else alone = next
                            scope.launch {
                                if (runCatching { api.setFavorite(item.id, on) }.isFailure) {
                                    if (listed != null) PhotoQueue.patch(item) else alone = item
                                    message = t("Couldn’t update {name}", "name" to item.name.orEmpty())
                                }
                            }
                        },
                        onDownload = if (canDownload) ({ item -> message = download(context, api.downloadUrl(item.id), fileName(item)) }) else null,
                        onPlayVideo = { nav.go(Route.Player(it.id)) },
                        onSlideshow = { playing = it },
                        onInfo = { info = it },
                        onNearEnd = { if (queue?.done == false) PhotoQueue.more?.invoke() },
                    ),
                )
            }
            info?.let { item ->
                var file by remember(item.id) { mutableStateOf<Pair<Long?, String?>?>(null) }
                var cover by remember(item.albumId) { mutableStateOf<BaseItemDto?>(null) }
                LaunchedEffect(item.id) { file = api.fileInfo(item.id) }
                LaunchedEffect(item.albumId) {
                    item.albumId?.let { a -> cover = runCatching { api.folderInfo(BaseItemDto(id = a, type = BaseItemKind.PHOTO_ALBUM), recursive = false).covers.firstOrNull() }.getOrNull() }
                }
                BottomSheet({ info = null }) {
                    // The sheet is drawn by the page's overlay host, outside this route's providers: give it the image addresses again.
                    CompositionLocalProvider(LocalPhotosApi provides api) {
                    SheetHead(t("Details"))
                    PhotoDetails(
                        items.firstOrNull { it.id == item.id } ?: item, file, cover,
                        onMap = {
                            val lat = item.latitude ?: 0.0
                            val lon = item.longitude ?: 0.0
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                .onFailure { message = t("Couldn’t open a map") }
                        },
                        onFolder = { info = null; item.albumId?.let { nav.go(Route.PhotoFolder(it)) } },
                    )
                    }
                }
            }
            Toast(message, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
        }
    }
}

/** Downloads a file into the phone's Downloads (Android 10+ through the download manager, older ones the browser). */
private fun download(context: Context, url: String, name: String): String? {
    if (Build.VERSION.SDK_INT >= 29) {
        val ok = runCatching {
            context.getSystemService(DownloadManager::class.java).enqueue(
                DownloadManager.Request(Uri.parse(url)).setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name),
            )
        }.isSuccess
        if (ok) return t("Downloading {name}", "name" to name)
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }.getOrElse { t("Couldn’t download {name}", "name" to name) }
}

/** A folder's photos and videos, each its own download (Android 10+). */
private fun downloadAll(context: Context, api: PhotosApi, items: List<BaseItemDto>): String {
    if (Build.VERSION.SDK_INT < 29) return t("Couldn’t download these")
    val dm = context.getSystemService(DownloadManager::class.java)
    val ok = items.count { item ->
        runCatching {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(api.downloadUrl(item.id))).setTitle(fileName(item))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName(item)),
            )
        }.isSuccess
    }
    return if (ok == 0) t("Couldn’t download these") else tn(ok, "Downloading {n} photo", "Downloading {n} photos")
}
