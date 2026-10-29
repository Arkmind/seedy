package io.github.arkmind.seedy.mobile.books.reader

import android.app.Activity
import android.view.View
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.audio.authorLine
import io.github.arkmind.seedy.core.books.BookCache
import io.github.arkmind.seedy.core.books.BookFormat
import io.github.arkmind.seedy.core.books.BooksApi
import io.github.arkmind.seedy.core.books.EpubSettings
import io.github.arkmind.seedy.core.books.PageTheme
import io.github.arkmind.seedy.core.books.ReaderPrefs
import io.github.arkmind.seedy.core.books.ReaderViewModel
import io.github.arkmind.seedy.core.books.ReaderViewModel.Kind
import io.github.arkmind.seedy.core.books.chapterName
import io.github.arkmind.seedy.core.books.epubStatus
import io.github.arkmind.seedy.core.books.pagedStatus
import io.github.arkmind.seedy.core.books.sameWork
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.system.downloadFromServer
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.UUID
import kotlin.math.roundToInt

private enum class Sheet { Settings, Contents }

/**
 * Board "Android · Reader": EPUB (epub.js, the web reader's engine, so the place is the same CFI everywhere), comics
 * (CBZ) and PDF, and a clear "download it" for the rest. [percent] comes from the audiobook ("Read from here"): the
 * reader opens at the same share of the book, and the audiobook pauses.
 */
@Composable
fun ReaderRoute(id: UUID, percent: Double? = null) {
    val session = currentSession()
    val context = LocalContext.current
    val nav = LocalMobileNav.current
    val audio = LocalAudio.current
    val vm = viewModel(key = "reader:$id:$percent") {
        ReaderViewModel(session, id, percent, ReaderPrefs(context), BookCache(context, BooksApi(session)))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    var shown by rememberSaveable { mutableStateOf(true) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    // The place is saved on the way out, and when the app goes to the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.leave() }
    DisposableEffect(vm) { onDispose { vm.leave() } }

    // Reading takes over from listening: the audiobook of the same book pauses (its place is kept).
    LaunchedEffect(Unit) { if (percent != null) audio?.pause() }
    LaunchedEffect(state.item?.id) {
        val item = state.item ?: return@LaunchedEffect
        val s = audio?.state?.value ?: return@LaunchedEffect
        val book = s.book
        if (s.isBook && s.playing && book != null && item.id.toString() != book.id && sameWork(book.name, book.author, item.name, authorLine(item))) audio.pause()
    }

    val theme = state.epub?.settings?.theme ?: state.paged?.settings?.theme ?: if (state.kind == Kind.Comic || state.kind == Kind.NoPages) PageTheme.Black else PageTheme.Dark
    val reading = state.kind == Kind.Epub || state.kind == Kind.Comic || state.kind == Kind.Pdf
    ReaderBars(hidden = reading && !shown && sheet == null, pale = theme.pale)

    val back = { nav.back() }
    val item = state.item
    Box(Modifier.fillMaxSize()) {
        when {
            state.loading -> ReaderScreen(ReaderChrome(title = ""), true, ReaderActions(back)) { ReaderLoading(it, PageTheme.Dark) }
            state.missing || item == null -> ReaderScreen(ReaderChrome(title = t("Book")), true, ReaderActions(back)) {
                ReaderMessage(PageTheme.Dark, t("This book isn’t here"), t("It may have been removed from the server, or it’s outside what this profile can see."))
            }
            state.kind == Kind.Epub -> EpubReader(vm, state, shown, { shown = it }, sheet, { sheet = it })
            state.kind == Kind.Comic -> ComicReader(vm, state, shown, { shown = it }, sheet, { sheet = it }, BooksApi(session))
            state.kind == Kind.Pdf -> PdfReader(vm, state, shown) { shown = it }
            state.kind == Kind.NoPages -> ReaderScreen(ReaderChrome(title = item.name.orEmpty(), theme = PageTheme.Black), true, ReaderActions(back)) {
                ReaderMessage(PageTheme.Black, t("This comic has no pages"), t("Seedy found no images in the file."))
            }
            state.kind == Kind.Audiobook -> ReaderScreen(ReaderChrome(title = item.name.orEmpty()), true, ReaderActions(back)) {
                ReaderMessage(PageTheme.Dark, t("This is an audiobook"), t("Listen to it from its page.")) {
                    ReaderButton(t("Go to the audiobook"), { nav.back(); nav.go(Route.Book(item.id)) })
                }
            }
            else -> ReaderScreen(ReaderChrome(title = item.name.orEmpty()), true, ReaderActions(back)) {
                val f = state.format ?: BookFormat.Other
                val ext = Regex("\\.([A-Za-z0-9]+)$").find(item.path.orEmpty())?.groupValues?.get(1)?.lowercase()
                val name = when (f) {
                    BookFormat.Cbr -> "CBR"
                    BookFormat.Other -> t("this kind of file")
                    else -> f.name.uppercase()
                }
                ReaderMessage(
                    PageTheme.Dark,
                    t("Seedy can’t open {format} on this phone", "format" to name),
                    if (f == BookFormat.Cbr) t("CBR comics are RAR archives, which Seedy can’t read. Download the file and open it in a comic reader app, or ask an admin for a CBZ copy.")
                    else t("Download the file and open it in an app that reads it."),
                ) {
                    if (state.canDownload) {
                        val label = t("Download {format}", "format" to if (f == BookFormat.Other) t("file") else f.name.uppercase())
                        ReaderButton(label, {
                            val file = item.path?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: (item.name.orEmpty() + (ext?.let { ".$it" } ?: ""))
                            val mime = if (f == BookFormat.Cbr) "application/vnd.comicbook-rar" else "application/octet-stream"
                            val ok = downloadFromServer(context, session, "/Items/${item.id}/Download", emptyMap(), file, mime)
                            toast = if (ok) t("Downloading {file}…", "file" to file) else t("Couldn’t start the download.")
                        }, ReaderIcons.Download)
                    } else {
                        SText(t("Downloading is turned off for this profile. An admin can turn it on."), size = 13.sp, color = paletteOf(PageTheme.Dark).sub)
                    }
                }
            }
        }
        Toast(toast, { toast = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
    }
}

@Composable
private fun EpubReader(
    vm: ReaderViewModel,
    state: ReaderViewModel.State,
    shown: Boolean,
    setShown: (Boolean) -> Unit,
    sheet: Sheet?,
    setSheet: (Sheet?) -> Unit,
) {
    val nav = LocalMobileNav.current
    val item = state.item ?: return
    val epub = state.epub ?: return
    val controller = remember { EpubController() }
    val w = epub.where(state.info)
    val (left, right) = epubStatus(w, epub.pos?.atEnd == true, state.info?.minutes ?: 0)
    val s = epub.settings
    val chrome = ReaderChrome(
        title = item.name.orEmpty(),
        sub = w.chapter?.let { chapterName(it, w.chapterIndex) } ?: authorLine(item),
        theme = s.theme,
        page = w.page.coerceAtLeast(1),
        total = if (epub.ready) w.total.coerceAtLeast(1) else 0,
        left = left,
        right = right,
        settings = true,
        bookmark = w.page > 0 && s.bookmarks.any { it.page == w.page },
        contents = true,
    )

    // The page gets the settings it opened with, then each change (bookmarks aren't the page's business).
    var applied by remember { mutableStateOf<EpubSettings?>(null) }
    LaunchedEffect(epub.file) {
        if (epub.file == null || applied != null) return@LaunchedEffect
        controller.open(s, epub.startCfi, epub.startPercent, state.info?.chapters.orEmpty())
        applied = s.copy(bookmarks = emptyList())
    }
    val look = s.copy(bookmarks = emptyList())
    LaunchedEffect(look) {
        if (applied != null && applied != look) {
            controller.settings(s)
            applied = look
        }
    }

    val actions = ReaderActions(
        onBack = { nav.back() },
        onSettings = { setSheet(Sheet.Settings) },
        onBookmark = vm::toggleBookmark,
        onContents = { setSheet(Sheet.Contents) },
        onSeek = controller::goToPage,
        tip = { p -> pageTip(p, w.total) },
    )
    ReaderScreen(chrome, shown || sheet != null, actions) { padding ->
        when {
            epub.unsent -> ReaderMessage(s.theme, t("Couldn’t open this book"), t("The server didn’t send the file. Try again in a moment."))
            epub.error != null -> ReaderMessage(
                s.theme, t("Couldn’t open this book"),
                epub.error.let { m -> if (!m.isNullOrEmpty()) t("The file may be damaged or not an EPUB. ({message})", "message" to m) else t("The file may be damaged or not an EPUB.") },
            )
            else -> {
                epub.file?.let { file ->
                    EpubPage(file, controller, paletteOf(s.theme).bg.toArgb(), padding) { e ->
                        when (e) {
                            EpubEvent.Ready -> vm.epubReady()
                            is EpubEvent.Counted -> vm.epubCounted(e.total)
                            is EpubEvent.Contents -> vm.epubToc(e.toc)
                            is EpubEvent.Relocated -> vm.epubRelocated(e.pos)
                            EpubEvent.Tap -> setShown(!shown)
                            EpubEvent.Turn -> setShown(false)
                            is EpubEvent.Failed -> vm.epubError(e.message)
                        }
                    }
                }
                if (!epub.ready) ReaderLoading(padding, s.theme)
            }
        }
    }
    when (sheet) {
        Sheet.Settings -> EpubSettingsSheet(s, vm::changeEpub) { setSheet(null) }
        Sheet.Contents -> ContentsSheet(epub.toc, w.chapterIndex, epub.counted, s.bookmarks, onPick = { target -> setSheet(null); controller.goTo(target) }) { setSheet(null) }
        null -> Unit
    }
}

@Composable
private fun ComicReader(
    vm: ReaderViewModel,
    state: ReaderViewModel.State,
    shown: Boolean,
    setShown: (Boolean) -> Unit,
    sheet: Sheet?,
    setSheet: (Sheet?) -> Unit,
    books: BooksApi,
) {
    val nav = LocalMobileNav.current
    val item = state.item ?: return
    val p = state.paged ?: return
    val s = p.settings ?: return
    val series = item.seriesName?.let { name -> listOfNotNull(name, item.indexNumber?.let { t("No. {n}", "n" to it) }).joinToString(" · ") }
    val (left, right) = pagedStatus(p.index, p.count)
    val chrome = ReaderChrome(
        title = item.name.orEmpty(),
        sub = listOf(authorLine(item), series.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "),
        theme = s.theme, page = p.index + 1, total = p.count, left = left, right = right, rtl = s.rtl, settings = true,
    )
    val actions = ReaderActions(onBack = { nav.back() }, onSettings = { setSheet(Sheet.Settings) }, onSeek = { vm.setPage(it - 1) }, tip = { pageTip(it, p.count) })
    ReaderScreen(chrome, shown || sheet != null, actions) {
        ComicPages(p.count, p.index, s, { n -> books.pageUrl(item.id, n) }, vm::setPage, onTap = { setShown(!shown) }, onTurn = { setShown(false) })
    }
    if (sheet == Sheet.Settings) ComicSettingsSheet(s, vm::changeComic) { setSheet(null) }
}

@Composable
private fun PdfReader(vm: ReaderViewModel, state: ReaderViewModel.State, shown: Boolean, setShown: (Boolean) -> Unit) {
    val nav = LocalMobileNav.current
    val item = state.item ?: return
    val p = state.paged ?: return
    val (left, right) = pagedStatus(p.index, p.count)
    val chrome = ReaderChrome(
        title = item.name.orEmpty(), sub = t("PDF"), page = p.index + 1, total = if (p.pdf != null) p.count else 0, left = left, right = right,
    )
    val actions = ReaderActions(onBack = { nav.back() }, onSeek = { vm.setPage(it - 1) }, tip = { pageTip(it, p.count) })
    ReaderScreen(chrome, shown, actions) { padding ->
        val pdf = p.pdf
        when {
            p.failed -> ReaderMessage(PageTheme.Dark, t("Couldn’t open this PDF"), t("The server didn’t send the file. Try again in a moment."))
            pdf == null -> ReaderLoading(padding, PageTheme.Dark)
            else -> PdfPagesView(pdf, p.index, vm::setPage, onTap = { setShown(!shown) }, onTurn = { setShown(false) })
        }
    }
}

private fun pageTip(page: Int, total: Int): String =
    t("Page {n} of {total} · {percent}%", "n" to page, "total" to total, "percent" to if (total > 0) (page * 100.0 / total).roundToInt() else 0)

/** Reading full screen: the system bars hide with the reader's own, and their icons follow the page colour. */
@Composable
private fun ReaderBars(hidden: Boolean, pale: Boolean) {
    val activity: Activity? = LocalActivity.current
    val view: View = LocalView.current
    val window = activity?.window ?: return
    val controller = remember(window) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val light = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = light
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    LaunchedEffect(hidden) {
        if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    LaunchedEffect(pale) {
        controller.isAppearanceLightStatusBars = pale
        controller.isAppearanceLightNavigationBars = pale
    }
}
