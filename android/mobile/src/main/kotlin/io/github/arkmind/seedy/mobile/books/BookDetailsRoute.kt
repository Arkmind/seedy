package io.github.arkmind.seedy.mobile.books

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.books.BookFormat
import io.github.arkmind.seedy.core.books.BookViewModel
import io.github.arkmind.seedy.core.books.BooksApi
import io.github.arkmind.seedy.core.books.BooksView
import io.github.arkmind.seedy.core.books.formatOf
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.manage.BulkViewModel
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.ManageIcons
import io.github.arkmind.seedy.mobile.manage.ManagePerms
import io.github.arkmind.seedy.mobile.manage.ManageSheetHost
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.manage.rememberManageSheets
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Board "Android · Book details": a book or an audiobook. */
@Composable
fun BookDetailsRoute(id: UUID) {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val audio = LocalAudio.current
    val context = LocalContext.current
    val images = LocalViewer.current.images
    val vm = viewModel(key = "book:$id") { BookViewModel(session, id) }
    val bulk = viewModel(key = "bulk:$id") { BulkViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val bulkState by bulk.state.collectAsStateWithLifecycle()
    val perms = remember(session) { ManagePerms.of(session) }
    val sheets = rememberManageSheets()
    val books = remember(session) { BooksApi(session) }
    var menu by remember { mutableStateOf(false) }
    // Back from the reader or the player: the place may have moved.
    LaunchedEffect(Unit) { vm.refresh() }
    val download = { w: BaseItemDto -> download(context, books.downloadUrl(w.id), fileName(w))?.let(vm::say) ?: Unit }
    val listen = { w: BaseItemDto ->
        audio?.listenTo(w.id)
        nav.go(Route.AudiobookPlayer)
    }
    Box(Modifier.fillMaxSize()) {
        val item = state.item
        BookScreen(
            state,
            BookActions(
                onBack = nav::back,
                // A book in several files has no one file to download.
                onDownload = if (item != null && state.canDownload && state.parts == null) ({ download(item) }) else null,
                onMore = {
                    if (item?.canDelete == true) bulk.loadDeleteSub(listOf(item.id.toString()))
                    menu = true
                },
                onRead = { nav.go(Route.Reader(it.id)) },
                onListen = listen,
                onFormat = { w ->
                    when (formatOf(w)) {
                        BookFormat.Audio -> listen(w)
                        BookFormat.Epub, BookFormat.Pdf, BookFormat.Cbz -> nav.go(Route.Reader(w.id))
                        else -> if (state.canDownload) download(w)
                    }
                },
                onAuthor = { name -> state.libraryId?.let { nav.go(Route.Books(it, BooksView.Authors.name, name)) } },
                onSeries = { name -> state.libraryId?.let { nav.go(Route.Books(it, BooksView.Series.name, name)) } },
                onOpen = { nav.go(Route.Book(it.id)) },
            ),
            bottomPadding = miniBarSpace(),
            now = System.currentTimeMillis(),
        )
        if (menu && item != null) {
            val add = AddTarget(listOf(item.id.toString()), item.name.orEmpty(), item.type.serialName, images?.poster(item, 120))
            val entries = buildList {
                val played = state.played
                val label = if (state.audio) (if (played) t("Mark as not listened") else t("Mark as listened")) else if (played) t("Mark as unread") else t("Mark as read")
                add(MenuEntry(BookIcons.Check, label, run = vm::togglePlayed))
                val fav = item.userData?.isFavorite == true
                add(MenuEntry(if (fav) BookIcons.HeartFilled else BookIcons.Heart, if (fav) t("Remove from favorites") else t("Add to favorites"), run = vm::toggleFavorite))
                if (state.canDownload && state.parts == null) {
                    add(MenuEntry(BookIcons.Download, t("Download {format}", "format" to formatLabel(item)), run = { download(item) }))
                }
                if (perms.collections) add(MenuEntry(ManageIcons.Collection, t("Add to collection"), more = true, run = { sheets.show("collection", add) }))
                if (perms.admin) {
                    val sid = item.id.toString()
                    add(MenuEntry(ManageIcons.Edit, t("Edit metadata"), group = t("Manage"), line = true, run = { nav.go(Route.Admin("metadata", sid)) }))
                    add(MenuEntry(ManageIcons.Identify, t("Identify"), t("Fix a wrong match"), run = { nav.go(Route.Admin("identify", sid)) }))
                    add(MenuEntry(ManageIcons.Image, t("Edit images"), run = { nav.go(Route.Admin("images", sid)) }))
                    add(MenuEntry(ManageIcons.Refresh, t("Refresh metadata"), t("New info and artwork from providers"), run = { bulk.refresh(listOf(sid)) }))
                }
                if (item.canDelete == true) add(MenuEntry(ManageIcons.Trash, t("Delete"), bulkState.deleteSub, danger = true, line = true, run = { sheets.show("delete", add) }))
            }
            ItemMenuSheet(item.name.orEmpty(), entries) { menu = false }
        }
        ManageSheetHost(sheets, say = { bulk.say(it) }, onDeleted = { _, _ -> nav.back() })
        Toast(message ?: bulkState.message, { vm.messageShown(); bulk.messageShown() }, Modifier.align(Alignment.BottomCenter).padding(bottom = miniBarSpace()))
    }
}

private fun formatLabel(item: BaseItemDto): String = when (val f = formatOf(item)) {
    BookFormat.Audio -> t("Audiobook")
    BookFormat.Other -> t("File")
    else -> f.name.uppercase()
}

/** The file's own name (from its path), else the title with its format's extension. */
private fun fileName(item: BaseItemDto): String {
    val path = item.path ?: item.mediaSources?.firstOrNull()?.path
    path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.contains('.') }?.let { return it }
    val ext = when (val f = formatOf(item)) {
        BookFormat.Audio -> item.container ?: "m4b"
        BookFormat.Other -> item.container ?: "bin"
        else -> f.name.lowercase()
    }
    return "${item.name.orEmpty().replace(Regex("[\\\\/:*?\"<>|]"), "_")}.$ext"
}

/**
 * Downloads the file into the phone's Downloads (Android 10+ via the download manager, older ones through the browser).
 * Returns what to tell the person, or null.
 */
private fun download(context: Context, url: String, name: String): String? {
    if (Build.VERSION.SDK_INT >= 29) {
        val ok = runCatching {
            val dm = context.getSystemService(DownloadManager::class.java)
            dm.enqueue(
                DownloadManager.Request(Uri.parse(url)).setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name),
            )
        }.isSuccess
        if (ok) return t("Downloading {name}", "name" to name)
    }
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        t("Couldn’t download {name}", "name" to name)
    }
}
