package io.github.arkmind.seedy.core.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID
import kotlin.math.roundToInt

/** What a Continue card is: a book or comic in the reader, or an audiobook. */
enum class ContinueKind { Book, Comic, Audio }

/**
 * A Continue card (board "Android · Books"): the kind, where in it ("Chapter 12 · Frost", "Page 12 of 32"), how far
 * and the foot ("46% · about 3 h left", "58% · 4 h 12 min left", "20 pages left"). [at] orders the cards (epoch millis).
 */
data class ContinueEntry(
    val item: BaseItemDto,
    val kind: ContinueKind,
    val where: String?,
    val fraction: Float,
    val foot: String,
    val at: Long = 0,
)

/** The views of a books library: the board's chips, then the web's Authors. */
enum class BooksView(val label: String) { All("All"), Ebooks("Ebooks"), Audiobooks("Audiobooks"), Comics("Comics"), Series("Series"), Authors("Authors") }

/**
 * A books library (board "Android · Books", web BooksLibrary.tsx): everything in it loads once and the views sort and
 * group it. All shows Continue, the series being read, Recently added, Audiobooks and Comics; the other views are full
 * grids or grouped lists, Series and Authors opening one series or author.
 */
class BooksViewModel(private val session: UserSession, private val libraryId: UUID, view: BooksView = BooksView.All, focus: String? = null) : ViewModel() {
    private val repo = BooksRepo(session)

    data class State(
        val name: String = "",
        val data: BooksData? = null,
        val cont: List<ContinueEntry> = emptyList(),
        val loading: Boolean = true,
        val error: Boolean = false,
        val view: BooksView = BooksView.All,
        /** Null: the view's own (newest first, authors by name). */
        val sort: BookSort? = null,
        /** The series or author opened in the Series or Authors view. */
        val focus: String? = null,
        /** The audiobook playing on this phone (its Continue card says "listening"). */
        val playing: UUID? = null,
        /** The search field's text while searching (null: not searching). */
        val query: String? = null,
    ) {
        val sortOrDefault: BookSort get() = sort ?: if (view == BooksView.Authors) BookSort.Author else BookSort.Added
    }

    private val _state = MutableStateFlow(State(view = view, focus = focus))
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null
    private var loadedAt = 0L

    init {
        load(force = false)
    }

    /** Loads the library again (its items come from a minute-long cache unless [force]); the places always fresh. */
    fun load(force: Boolean = true) {
        loadedAt = System.currentTimeMillis()
        job?.cancel()
        job = viewModelScope.launch {
            _state.update { it.copy(error = false) }
            if (_state.value.name.isEmpty()) launch { runCatching { repo.item(libraryId) }.getOrNull()?.let { v -> _state.update { it.copy(name = v.name.orEmpty()) } } }
            val lib = runCatching { repo.library(libraryId, force) }.getOrNull()
            if (lib == null) {
                _state.update { it.copy(loading = false, error = it.data == null) }
                return@launch
            }
            val data = booksData(lib.items, repo.readings(), lib.parts)
            _state.update { it.copy(data = data, loading = false) }
            val entries = runCatching { continueEntries(data, repo.books) }.getOrDefault(emptyList())
            _state.update { it.copy(cont = ordered(entries, it.playing)) }
        }
    }

    /** Back on the page: places may have moved (the page opening loads them already). */
    fun refresh() {
        if (System.currentTimeMillis() - loadedAt > 3_000) load(force = false)
    }

    fun setView(view: BooksView) = _state.update { it.copy(view = view, focus = null) }

    fun setSort(sort: BookSort) = _state.update { it.copy(sort = sort) }

    fun open(view: BooksView, focus: String?) = _state.update { it.copy(view = view, focus = focus) }

    fun setPlaying(id: UUID?) = _state.update { it.copy(playing = id, cont = ordered(it.cont, id)) }

    fun search(query: String?) = _state.update { it.copy(query = query) }

    /** Jellyfin rescans the library's files (admins). */
    fun scan(done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.scan(libraryId) }.isSuccess
            done(ok)
        }
    }

    private fun ordered(list: List<ContinueEntry>, playing: UUID?): List<ContinueEntry> =
        list.sortedByDescending { if (it.item.id == playing) Long.MAX_VALUE else it.at }
}

/**
 * Continue: books and comics open in the reader (from /Seedy/Reading), audiobooks from their places. One card per work
 * (the format read furthest), the six latest.
 */
suspend fun continueEntries(data: BooksData, books: BooksApi): List<ContinueEntry> = kotlinx.coroutines.coroutineScope {
    val byId = data.all.associateBy { it.id }
    val opened = data.reading.values.filter { !it.finished }.mapNotNull { r -> uuidOf(r.itemId)?.let(byId::get)?.takeIf { !isAudioBook(it) }?.let { r to it } }
    val best = LinkedHashMap<String, Pair<Reading, BaseItemDto>>()
    for (x in opened) {
        val k = workKey(x.second)
        val cur = best[k]
        if (cur == null || x.first.percent > cur.first.percent || (x.first.percent == cur.first.percent && millisOf(x.first.updatedAt) > millisOf(cur.first.updatedAt))) best[k] = x
    }
    val listening = data.audio.filter { it.userData?.played != true && (it.userData?.playbackPositionTicks ?: 0) > 0 }
    val cands: List<Pair<Long, suspend () -> ContinueEntry>> =
        best.values.map { (r, item) -> millisOf(r.updatedAt) to suspend { readingEntry(item, r, runCatching { books.info(item.id) }.getOrNull()) } } +
            listening.map { item -> millisOf(item.userData?.lastPlayedDate) to suspend { audioEntry(item) } }
    cands.sortedByDescending { it.first }.take(6).map { async { it.second() } }.awaitAll()
}

private fun pct(fraction: Double) = "${(fraction * 100).roundToInt()}%"

fun readingEntry(item: BaseItemDto, r: Reading, info: BookInfo?): ContinueEntry {
    val fraction = (r.percent / 100).coerceIn(0.0, 1.0)
    if (isComic(item)) {
        val pages = info?.pages ?: 0
        val page = minOf(pages, (r.locator?.toIntOrNull() ?: 0) + 1)
        return ContinueEntry(
            item, ContinueKind.Comic,
            where = if (pages > 0) t("Page {n} of {total}", "n" to page, "total" to pages) else null,
            fraction = fraction.toFloat(),
            foot = if (pages > 0) tn(maxOf(0, pages - page), "{n} page left", "{n} pages left") else pct(fraction),
            at = millisOf(r.updatedAt),
        )
    }
    val chapter = if ((r.chapterIndex ?: 0) > 0 && !r.chapter.isNullOrBlank()) t("Chapter {n} · {title}", "n" to r.chapterIndex, "title" to r.chapter) else r.chapter
    val minutes = info?.minutes?.takeIf { it > 0 }?.let { it * (1 - fraction) } ?: 0.0
    return ContinueEntry(
        item, ContinueKind.Book, where = chapter?.ifBlank { null }, fraction = fraction.toFloat(),
        foot = listOfNotNull(pct(fraction), if (minutes > 0) t("about {time} left", "time" to roughTime(minutes)) else null).joinToString(" · "),
        at = millisOf(r.updatedAt),
    )
}

private val NumberedChapter = Regex("^chapter\\s*\\d+$", RegexOption.IGNORE_CASE)

fun audioEntry(item: BaseItemDto): ContinueEntry {
    val pos = item.userData?.playbackPositionTicks ?: 0L
    val total = item.runTimeTicks ?: 0L
    val fraction = if (total > 0) pos.toDouble() / total else 0.0
    val chapters = item.chapters.orEmpty().sortedBy { it.startPositionTicks }
    val ci = chapters.indexOfLast { it.startPositionTicks <= pos }
    val ch = chapters.getOrNull(ci)
    val where = ch?.let {
        val name = it.name?.trim().orEmpty()
        if (name.isNotEmpty() && !NumberedChapter.matches(name)) t("Chapter {n} · {title}", "n" to ci + 1, "title" to name) else t("Chapter {n}", "n" to ci + 1)
    }
    return ContinueEntry(
        item, ContinueKind.Audio, where, fraction.toFloat(),
        foot = listOfNotNull(pct(fraction), if (total > 0) timeLeft(ticksToSeconds(total - pos)) else null).joinToString(" · "),
        at = millisOf(item.userData?.lastPlayedDate),
    )
}
