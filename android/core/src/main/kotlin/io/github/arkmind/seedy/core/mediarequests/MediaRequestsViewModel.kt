package io.github.arkmind.seedy.core.mediarequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Which list a service's Requests tab shows. */
enum class MediaTab { Discover, Mine, All }

/** The requests a Discover summary card counts: the open ones and the ones that just arrived. */
data class MediaProgress(val requests: List<MediaRequest>, val waiting: Int, val downloading: Int, val searching: Int, val ready: Int)

/**
 * The Requests tab's Music and Books (web: screens/requests/music.tsx, books.tsx, mine.tsx): Discover's rows, the
 * person's requests (and everyone's for those who manage them) with approve, decline, search again, accept a lesser
 * quality and cancel.
 */
class MediaRequestsViewModel(session: UserSession) : ViewModel() {
    val api = MediaRequestsApi(session.api)

    data class MusicRowState(val row: MusicRow, val items: List<MusicRelease>? = null)

    data class BookRowState(val row: BookRow, val items: List<BookCard>? = null, val source: String? = null)

    /** One kind's request lists; null while loading. */
    data class Lists(val mine: List<MediaRequest>? = null, val all: List<MediaRequest>? = null, val failed: Boolean = false)

    data class State(
        val summary: MediaSummary? = null,
        val musicRows: List<MusicRowState> = MusicRow.entries.map { MusicRowState(it) },
        val bookRows: List<BookRowState> = BookRow.entries.map { BookRowState(it) },
        val music: Lists = Lists(),
        val books: Lists = Lists(),
        val tab: MediaTab = MediaTab.Discover,
        val show: MediaShow = MediaShow.All,
        val asked: AskedState = AskedState(),
        /** Requests with an action on its way. */
        val busy: Set<String> = emptySet(),
        val message: String? = null,
    ) {
        fun lists(music: Boolean): Lists = if (music) this.music else books

        val canManage: Boolean get() = summary?.canManage == true

        fun counts(music: Boolean): MediaKindSummary? = summary?.let { if (music) it.music else it.books }

        /** The open requests of the person's, and the ones that arrived this last week. */
        fun progress(music: Boolean, today: LocalDate): MediaProgress {
            val mine = lists(music).mine.orEmpty()
            val open = mine.filter { it.status !in listOf("ready", "declined", "cancelled") }
            val recent = mine.filter { r ->
                r.status == "ready" && (MediaFormat.day(r.readyAt ?: r.updatedAt)?.let { ChronoUnit.DAYS.between(it, today) <= 7 } ?: false)
            }
            return MediaProgress(
                open + recent,
                waiting = open.count { it.status == "pending" },
                downloading = open.count { it.status == "downloading" },
                searching = open.count { it.status in listOf("approved", "searching", "failed") },
                ready = recent.size,
            )
        }

        /** The list a tab shows, as the Show filter has it. */
        fun shown(music: Boolean, tab: MediaTab): List<MediaRequest>? {
            val l = lists(music)
            val list = (if (tab == MediaTab.All) l.all else l.mine) ?: return null
            return list.filter { MediaFormat.matches(it, show) }
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val rowsLoaded = mutableSetOf<Boolean>()

    init {
        viewModelScope.launch {
            MediaAsked.state.collect { a -> _state.update { it.copy(asked = a) } }
        }
        // A request made in a sheet (or a change elsewhere): the lists and counts follow.
        viewModelScope.launch {
            MediaAsked.state.drop(1).collect { reloadLists() }
        }
    }

    /** Coming to [music] or books: the counts and lists again, the rows the first time. */
    fun open(music: Boolean) {
        loadSummary()
        loadLists(music)
        if (rowsLoaded.add(music)) loadRows(music)
    }

    private fun loadSummary() {
        viewModelScope.launch {
            io { api.summary() }?.let { s -> _state.update { it.copy(summary = s) } }
        }
    }

    private fun reloadLists() {
        loadSummary()
        val s = _state.value
        if (s.music.mine != null || s.music.all != null) loadLists(true)
        if (s.books.mine != null || s.books.all != null) loadLists(false)
    }

    private fun loadLists(music: Boolean) {
        val kind = if (music) "music" else "book"
        viewModelScope.launch {
            val mine = io { api.list("mine", kind).results }
            setLists(music) { it.copy(mine = mine ?: it.mine ?: emptyList(), failed = mine == null && it.mine == null) }
        }
        if (_state.value.tab == MediaTab.All || _state.value.lists(music).all != null) loadAll(music)
    }

    private fun loadAll(music: Boolean) {
        viewModelScope.launch {
            val all = io { api.list("all", if (music) "music" else "book").results }
            setLists(music) { it.copy(all = all ?: it.all ?: emptyList()) }
        }
    }

    private fun loadRows(music: Boolean) {
        if (music) {
            for (row in MusicRow.entries) viewModelScope.launch {
                val items = io { api.musicRow(row).results }.orEmpty()
                _state.update { s -> s.copy(musicRows = s.musicRows.map { if (it.row == row) it.copy(items = items) else it }) }
            }
        } else {
            for (row in BookRow.entries) viewModelScope.launch {
                val r = io { api.bookRow(row) }
                _state.update { s -> s.copy(bookRows = s.bookRows.map { if (it.row == row) it.copy(items = r?.results.orEmpty(), source = r?.source) else it }) }
            }
        }
    }

    fun setTab(music: Boolean, tab: MediaTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == MediaTab.All && _state.value.lists(music).all == null) loadAll(music)
    }

    fun setShow(show: MediaShow) = _state.update { it.copy(show = show) }

    private fun setLists(music: Boolean, change: (Lists) -> Lists) =
        _state.update { if (music) it.copy(music = change(it.music)) else it.copy(books = change(it.books)) }

    // ── Actions ──

    fun approve(r: MediaRequest) = run(r, t("Approved {title}", "title" to MediaFormat.name(r)), t("Couldn’t approve {title}", "title" to MediaFormat.name(r))) { api.approve(r.id) }

    fun decline(r: MediaRequest) = run(r, t("Declined {title}", "title" to MediaFormat.name(r)), t("Couldn’t decline {title}", "title" to MediaFormat.name(r))) { api.decline(r.id) }

    fun searchNow(r: MediaRequest) =
        run(r, t("Searching for {title} again", "title" to MediaFormat.name(r)), t("Couldn’t search for {title}", "title" to MediaFormat.name(r))) { api.searchNow(r.id) }

    fun acceptLower(r: MediaRequest) = run(
        r,
        t("{quality} is fine for {title} now", "title" to MediaFormat.name(r), "quality" to r.lowerQuality.orEmpty()),
        t("Couldn’t change the quality for {title}", "title" to MediaFormat.name(r)),
    ) { api.acceptLower(r.id) }

    /** Cancels the person's own waiting request, or removes any (cancelling it in its app) for those who manage them. */
    fun remove(r: MediaRequest) {
        val own = r.requestedByMe && r.status == "pending"
        run(r, if (own) t("Request cancelled") else t("Request removed"), t("Couldn’t remove {title}", "title" to MediaFormat.name(r))) {
            api.remove(r.id)
            null
        }
    }

    private fun run(r: MediaRequest, done: String, failed: String, call: suspend () -> MediaRequest?) {
        _state.update { it.copy(busy = it.busy + r.id) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { call() } }
            if (result.isSuccess) {
                val next = result.getOrNull()
                setLists(r.music) { l ->
                    fun swap(list: List<MediaRequest>?) = list?.flatMap { if (it.id != r.id) listOf(it) else listOfNotNull(next) }
                    l.copy(mine = swap(l.mine), all = swap(l.all))
                }
                _state.update { it.copy(busy = it.busy - r.id, message = done) }
                MediaAsked.note(r, next)
            } else {
                _state.update { it.copy(busy = it.busy - r.id, message = failed) }
            }
        }
    }

    fun say(message: String?) = _state.update { it.copy(message = message) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun <T> io(block: suspend () -> T): T? = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
}
