package io.github.arkmind.seedy.core.mediarequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A Discover row's "See all" (web: MusicBrowse, BooksBrowse): the whole row as a grid. */
class MediaRowViewModel(session: UserSession, val music: Boolean, val row: String) : ViewModel() {
    val api = MediaRequestsApi(session.api)

    data class State(
        val albums: List<MusicRelease>? = null,
        val books: List<BookCard>? = null,
        val failed: Boolean = false,
        val asked: AskedState = AskedState(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val musicRow: MusicRow? = if (music) MusicRow.entries.firstOrNull { it.key == row } else null
    val bookRow: BookRow? = if (!music) BookRow.entries.firstOrNull { it.key == row } else null

    init {
        viewModelScope.launch { MediaAsked.state.collect { a -> _state.update { it.copy(asked = a) } } }
        load()
    }

    fun load() {
        _state.update { it.copy(failed = false) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        musicRow != null -> State(albums = api.musicRow(musicRow).results)
                        bookRow != null -> State(books = api.bookRow(bookRow).results)
                        else -> State(albums = emptyList(), books = emptyList())
                    }
                }
            }
            _state.update { s ->
                r.getOrNull()?.let { s.copy(albums = it.albums, books = it.books, failed = false) }
                    ?: s.copy(albums = s.albums ?: emptyList(), books = s.books ?: emptyList(), failed = true)
            }
        }
    }
}

/** Music or book search (web: MusicResults, BookResults): MusicBrainz artists and albums, or books, authors and series. */
class MediaSearchViewModel(session: UserSession, val music: Boolean) : ViewModel() {
    val api = MediaRequestsApi(session.api)

    data class State(
        val text: String = "",
        /** What the results are for; empty before the first search. */
        val query: String = "",
        val loading: Boolean = false,
        val failed: Boolean = false,
        val artists: List<MusicArtistRef> = emptyList(),
        val albums: List<MusicRelease> = emptyList(),
        val books: List<BookCard> = emptyList(),
        val asked: AskedState = AskedState(),
    ) {
        val empty: Boolean get() = artists.isEmpty() && albums.isEmpty() && books.isEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { MediaAsked.state.collect { a -> _state.update { it.copy(asked = a) } } }
    }

    /** The field changed: search once typing pauses (350 ms, as the web). */
    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        job?.cancel()
        val q = text.trim()
        if (q.isEmpty()) {
            _state.update { State(asked = it.asked) }
            return
        }
        job = viewModelScope.launch {
            delay(350)
            search(q)
        }
    }

    fun retry() {
        val q = _state.value.text.trim()
        if (q.isNotEmpty()) {
            job?.cancel()
            job = viewModelScope.launch { search(q) }
        }
    }

    private suspend fun search(q: String) {
        _state.update { it.copy(loading = true, failed = false, query = q) }
        val r = withContext(Dispatchers.IO) {
            runCatching {
                if (music) api.musicSearch(q).let { State(artists = it.artists, albums = it.albums) } else State(books = api.bookSearch(q).results)
            }
        }
        _state.update { s ->
            val found = r.getOrNull()
            if (found == null) s.copy(loading = false, failed = true, artists = emptyList(), albums = emptyList(), books = emptyList())
            else s.copy(loading = false, failed = false, artists = found.artists, albums = found.albums, books = found.books)
        }
    }
}
