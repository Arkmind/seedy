package io.github.arkmind.seedy.core.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.Locale
import java.util.UUID

/**
 * A bookmark, as the web reader keeps them in the reading settings saved with the place in a book
 * (reader/settings.ts: `{ cfi, page, label, at }`). In an audiobook the locator is seconds into the book, as its place's
 * is; [page] is the chapter (from 0) and [label] the chapter's name.
 */
@Serializable
data class Bookmark(val cfi: String, val page: Int, val label: String, val at: Long) {
    val seconds: Double get() = cfi.toDoubleOrNull() ?: 0.0
}

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/** The bookmarks in a place's settings (the web reader's format). */
fun bookmarksOf(reading: Reading?): List<Bookmark> {
    val list = reading?.settings?.get("bookmarks") as? JsonArray ?: return emptyList()
    return list.mapNotNull { runCatching { json.decodeFromJsonElement(Bookmark.serializer(), it) }.getOrNull() }
}

/**
 * The audiobook player's side of the server (board "Android · Audiobook"): the bookmarks saved with the place in the
 * book, and the same book as an e-book ("Read the EPUB from here").
 */
class AudiobookViewModel(private val session: UserSession) : ViewModel() {
    private val repo = BooksRepo(session)

    data class State(val bookId: UUID? = null, val bookmarks: List<Bookmark> = emptyList(), val ebook: BaseItemDto? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var settings: JsonObject? = null

    /** The book playing changed: its bookmarks and its e-book. */
    fun open(bookId: UUID, name: String, author: String?) {
        if (_state.value.bookId == bookId) return
        _state.value = State(bookId = bookId)
        settings = null
        viewModelScope.launch {
            val r = repo.reading(bookId)
            settings = r?.settings
            _state.update { if (it.bookId == bookId) it.copy(bookmarks = bookmarksOf(r).sortedBy { b -> b.seconds }) else it }
        }
        viewModelScope.launch {
            val ebook = repo.findEbook(name, author)
            _state.update { if (it.bookId == bookId) it.copy(ebook = ebook) else it }
        }
    }

    /**
     * Adds a bookmark at [seconds] (or removes the one there), saved with the place in the book: [place] is where the
     * player is, as it saves it.
     */
    fun add(seconds: Double, chapter: Int, label: String, place: Reading) {
        val list = (_state.value.bookmarks + Bookmark(String.format(Locale.ROOT, "%.1f", seconds), chapter, label, System.currentTimeMillis())).sortedBy { it.seconds }
        save(list, place)
    }

    fun remove(b: Bookmark, place: Reading) = save(_state.value.bookmarks - b, place)

    private fun save(list: List<Bookmark>, place: Reading) {
        val id = _state.value.bookId ?: return
        val before = _state.value.bookmarks
        _state.update { it.copy(bookmarks = list) }
        val merged = JsonObject((settings ?: JsonObject(emptyMap())) + ("bookmarks" to json.encodeToJsonElement(list)))
        viewModelScope.launch {
            val saved = runCatching { repo.saveReading(id, place.copy(settings = merged)) }.getOrNull()
            if (saved == null) _state.update { it.copy(bookmarks = before) } else settings = saved.settings ?: merged
        }
    }
}
