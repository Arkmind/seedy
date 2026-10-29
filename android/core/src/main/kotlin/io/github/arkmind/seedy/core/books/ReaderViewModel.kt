package io.github.arkmind.seedy.core.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.audio.AudioApi
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

/**
 * The reader (board "Android · Reader", web/src/screens/books/reader): opens a book as what it is — an EPUB (epub.js in
 * a web page), a comic (CBZ pages), a PDF (drawn here) — and keeps the place in it on the server, the same place the
 * web reader keeps, so reading goes on from one device to the other. [askedPercent] comes from the audiobook ("Read
 * from here"): the same share of the book.
 */
class ReaderViewModel(
    private val session: UserSession,
    val id: UUID,
    private val askedPercent: Double?,
    private val prefs: ReaderPrefs,
    private val cache: BookCache,
) : ViewModel() {
    enum class Kind { Epub, Comic, Pdf, Unsupported, Audiobook, NoPages }

    data class EpubState(
        val settings: EpubSettings,
        /** Where to open: the saved CFI, else a share of the book (from the audiobook, or a place saved without a CFI). */
        val startCfi: String?,
        val startPercent: Double?,
        val file: File? = null,
        /** The book couldn't be opened: why, in the engine's words (may be empty). */
        val error: String? = null,
        /** The server didn't send the file. */
        val unsent: Boolean = false,
        /** The web page has the book open. */
        val ready: Boolean = false,
        /** Pages counted the server's way (until then the server's count shows). */
        val counted: Boolean = false,
        val total: Int = 0,
        val toc: List<TocEntry> = emptyList(),
        val pos: EpubPos? = null,
    ) {
        fun where(info: BookInfo?): EpubWhere = epubWhere(pos, if (counted) total else info?.pages ?: 0, toc)
    }

    /** Comics and PDFs: one page at a time, the place is the page index (from 0). */
    data class PagedState(
        val count: Int,
        val index: Int,
        /** Comics only. */
        val settings: ComicSettings? = null,
        /** PDFs only: the file once here. */
        val pdf: PdfPages? = null,
        val failed: Boolean = false,
    )

    data class State(
        val loading: Boolean = true,
        val missing: Boolean = false,
        val item: BaseItemDto? = null,
        val info: BookInfo? = null,
        val kind: Kind? = null,
        val format: BookFormat? = null,
        val epub: EpubState? = null,
        val paged: PagedState? = null,
        val canDownload: Boolean = false,
    )

    private val audioApi = AudioApi(session)
    private val books = BooksApi(session)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val saver = ReadingSaver(viewModelScope) { audioApi.saveReading(id, it) }
    private var reading: Reading? = null

    /** Reopening a book where it was saves nothing until the page turns (a first opening does save). */
    private var moved = false
    private var openedAt: String? = null
    private var lastKey: Triple<String, Int, Int>? = null
    private var place: Reading? = null

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val itemQ = viewModelScope.async { runCatching { Browse(session).item(id) } }
        val infoQ = viewModelScope.async { runCatching { books.info(id) }.getOrNull() }
        val readingQ = viewModelScope.async { runCatching { audioApi.reading(id) }.getOrNull() }
        val item = itemQ.await().getOrElse {
            if (it is CancellationException) throw it
            _state.update { s -> s.copy(loading = false, missing = true) }
            return
        }
        val info = infoQ.await()
        val reading = readingQ.await()?.takeIf { it.itemId.isNotEmpty() || it.locator != null }
        this.reading = reading
        moved = reading == null || askedPercent != null
        val format = formatOf(item).let { f -> if (f == BookFormat.Audio) f else info?.bookFormat?.takeIf { it != BookFormat.Other } ?: f }
        val kind = when (format) {
            BookFormat.Audio -> Kind.Audiobook
            BookFormat.Epub -> Kind.Epub
            BookFormat.Cbz -> if ((info?.pages ?: 0) > 0) Kind.Comic else Kind.NoPages
            BookFormat.Pdf -> Kind.Pdf
            else -> Kind.Unsupported
        }
        val canDownload = session.user.policy?.enableContentDownloading == true
        _state.update { it.copy(loading = false, item = item, info = info, kind = kind, format = format, canDownload = canDownload) }
        when (kind) {
            Kind.Epub -> openEpub(item, reading)
            Kind.Comic -> openComic(info!!.pages, reading)
            Kind.Pdf -> openPdf(item, reading)
            else -> Unit
        }
    }

    /* ───────── EPUB ───────── */

    private suspend fun openEpub(item: BaseItemDto, reading: Reading?) {
        val cfi = reading?.locator?.takeIf { it.startsWith("epubcfi(") }
        val percent = askedPercent ?: reading?.takeIf { cfi == null && !it.finished && it.percent > 0 }?.percent
        _state.update {
            it.copy(epub = EpubState(settings = readEpubSettings(reading?.settings, prefs.epub()), startCfi = cfi, startPercent = percent))
        }
        val file = runCatching { cache.file(item, "epub") }.getOrElse {
            if (it is CancellationException) throw it
            _state.update { s -> s.copy(epub = s.epub?.copy(unsent = true)) }
            return
        }
        _state.update { it.copy(epub = it.epub?.copy(file = file)) }
    }

    private fun updateEpub(f: (EpubState) -> EpubState) {
        _state.update { s -> s.epub?.let { s.copy(epub = f(it)) } ?: s }
        epubPlaceChanged()
    }

    fun epubReady() = updateEpub { it.copy(ready = true) }
    fun epubError(message: String) = updateEpub { it.copy(error = message) }
    fun epubCounted(total: Int) = updateEpub { it.copy(counted = true, total = total) }
    fun epubToc(toc: List<TocEntry>) = updateEpub { it.copy(toc = toc) }
    fun epubRelocated(pos: EpubPos) = updateEpub { it.copy(pos = pos) }

    /** Save the place a moment after it stops moving (the web's effect on the CFI, page and chapter). */
    private fun epubPlaceChanged() {
        val s = _state.value
        val e = s.epub ?: return
        val pos = e.pos ?: return
        val w = e.where(s.info)
        if (w.page == 0) return
        val key = Triple(pos.cfi, w.page, w.chapterIndex)
        val p = Reading(
            locator = pos.cfi,
            percent = w.percent,
            chapter = w.chapter?.title,
            chapterIndex = if (w.chapterIndex >= 0) w.chapterIndex + 1 else null,
            chapterCount = e.toc.size.takeIf { it > 0 },
            finished = pos.atEnd,
        )
        place = p
        if (key == lastKey) return
        lastKey = key
        if (!moved) {
            if (openedAt == null) openedAt = pos.cfi
            if (pos.cfi == openedAt) return
            moved = true
        }
        saver.place(p)
    }

    fun changeEpub(patch: (EpubSettings) -> EpubSettings) {
        val e = _state.value.epub ?: return
        val next = patch(e.settings)
        if (next == e.settings) return
        prefs.rememberEpub(next)
        saver.settings(next.toJson(reading?.settings), place)
        _state.update { s -> s.copy(epub = s.epub?.copy(settings = next)) }
    }

    /** The bookmark on the page showing, if any. */
    fun bookmarkHere(): Bookmark? {
        val s = _state.value
        val e = s.epub ?: return null
        val page = e.where(s.info).page
        return e.settings.bookmarks.firstOrNull { it.page == page }
    }

    fun toggleBookmark() {
        val s = _state.value
        val e = s.epub ?: return
        val pos = e.pos ?: return
        val w = e.where(s.info)
        val marked = e.settings.bookmarks.firstOrNull { it.page == w.page }
        val list = if (marked != null) e.settings.bookmarks - marked
        else (e.settings.bookmarks + Bookmark(pos.cfi, w.page, w.chapter?.title ?: s.item?.name.orEmpty(), System.currentTimeMillis())).sortedBy { it.page }
        changeEpub { it.copy(bookmarks = list) }
    }

    /* ───────── Comics and PDFs ───────── */

    private fun startIndex(count: Int, reading: Reading?): Int {
        askedPercent?.let { p -> return ((p / 100 * count).roundToInt() - 1).coerceIn(0, (count - 1).coerceAtLeast(0)) }
        val saved = reading?.locator?.toDoubleOrNull()?.toInt()
        return if (saved != null && saved in 0 until count && reading.finished.not()) saved else 0
    }

    private fun openComic(count: Int, reading: Reading?) {
        val index = startIndex(count, reading)
        _state.update { it.copy(paged = PagedState(count, index, settings = readComicSettings(reading?.settings, prefs.comic()))) }
        pagedOpened(index)
    }

    private suspend fun openPdf(item: BaseItemDto, reading: Reading?) {
        _state.update { it.copy(paged = PagedState(count = it.info?.pages ?: 0, index = 0)) }
        val pdf = runCatching { cache.file(item, "pdf").let { f -> withContext(Dispatchers.IO) { PdfPages(f) } } }.getOrElse {
            if (it is CancellationException) throw it
            _state.update { s -> s.copy(paged = s.paged?.copy(failed = true)) }
            return
        }
        val index = startIndex(pdf.count, reading)
        _state.update { it.copy(paged = it.paged?.copy(count = pdf.count, index = index, pdf = pdf)) }
        pagedOpened(index)
    }

    private var pagedAt = -1

    private fun pagedOpened(index: Int) {
        pagedAt = index
        if (moved) saver.place(pagedPlace(), 1200)
    }

    private fun pagedPlace(): Reading {
        val p = _state.value.paged ?: return Reading()
        val n = p.index + 1
        return Reading(locator = p.index.toString(), percent = (n * 1000.0 / p.count.coerceAtLeast(1)).roundToInt() / 10.0, finished = n >= p.count)
    }

    fun setPage(index: Int) {
        val p = _state.value.paged ?: return
        val i = index.coerceIn(0, (p.count - 1).coerceAtLeast(0))
        if (i == p.index) return
        _state.update { s -> s.copy(paged = s.paged?.copy(index = i)) }
        if (!moved && i == pagedAt) return
        moved = true
        saver.place(pagedPlace(), 1200)
    }

    fun changeComic(patch: (ComicSettings) -> ComicSettings) {
        val cur = _state.value.paged?.settings ?: return
        val next = patch(cur)
        if (next == cur) return
        prefs.rememberComic(next)
        saver.settings(next.toJson(reading?.settings), pagedPlace())
        _state.update { s -> s.copy(paged = s.paged?.copy(settings = next)) }
    }

    /* ───────── Leaving ───────── */

    /** The reader is going away, or the app to the background: save now. */
    fun leave() = saver.flush(exit = true)

    override fun onCleared() {
        saver.flush(exit = true)
        _state.value.paged?.pdf?.close()
    }
}
