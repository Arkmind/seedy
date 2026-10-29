package io.github.arkmind.seedy.core.books

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.audio.AudioPart
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.audio.authorsOf
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * A book's page (board "Android · Book details", web BookDetails.tsx): the book or audiobook, the library it's in (for
 * the other formats of the work, its series and more by its author), its length and chapters, this profile's place,
 * the formats' file sizes and the EPUB's language. An audiobook uses the same page with Listen first.
 */
class BookViewModel(private val session: UserSession, private val id: UUID) : ViewModel() {
    private val repo = BooksRepo(session)
    private val browse = Browse(session)

    data class State(
        val item: BaseItemDto? = null,
        /** An audiobook in several files: its files. */
        val parts: List<AudioPart>? = null,
        val loading: Boolean = true,
        val error: Boolean = false,
        val libraryId: UUID? = null,
        val data: BooksData? = null,
        val info: BookInfo? = null,
        val reading: Reading? = null,
        /** On an audiobook's page: the place in the book it reads out. */
        val twinReading: Reading? = null,
        val sizes: Map<UUID, Long> = emptyMap(),
        /** The EPUB's language code ("en"). */
        val language: String? = null,
        /** Whether this profile may download files (the policy; Jellyfin's per-title CanDownload wins). */
        val policyDownload: Boolean = false,
    ) {
        val audio: Boolean get() = item?.let(::isAudioBook) == true

        /** Every file of this work in the library: the EPUB, the audiobook, the PDF… in that order. */
        val work: List<BaseItemDto> get() {
            val item = item ?: return emptyList()
            val all = data?.all.orEmpty()
            val key = normalize(item.name)
            val authors = authorsOf(item).map { normalize(it.first) }.toSet()
            val same = all.filter { o ->
                normalize(o.name) == key && (authors.isEmpty() || authorsOf(o).isEmpty() || authorsOf(o).any { normalize(it.first) in authors })
            }.toMutableList()
            if (same.none { it.id == item.id }) same.add(0, item)
            return same.sortedBy { FormatOrder.indexOf(formatOf(it)) }
        }

        val audioTwin: BaseItemDto? get() = if (audio) item else work.firstOrNull(::isAudioBook)

        /** What Read opens: the book itself, or (an audiobook's page) the EPUB of the work, else any other file of it. */
        val readable: BaseItemDto? get() = if (audio) work.firstOrNull { formatOf(it) == BookFormat.Epub } ?: work.firstOrNull { !isAudioBook(it) } else item

        /** An audiobook takes its series (and publisher) from the book it reads out. */
        val source: BaseItemDto? get() = if (audio) readable ?: item else item

        val seriesName: String? get() = source?.let(::seriesOf)

        val series: SeriesGroup? get() = data?.seriesNamed(seriesName)

        /** The item as the library lists it (with its file's path and size). */
        val self: BaseItemDto? get() = item?.let { i -> data?.all?.firstOrNull { it.id == i.id } ?: i }

        val canDownload: Boolean get() = self?.canDownload ?: policyDownload

        /** More by the first author: books outside this series, one per title, newest first. */
        val more: List<BaseItemDto> get() {
            val item = item ?: return emptyList()
            val author = authorsOf(item).firstOrNull()?.first ?: return emptyList()
            val k = normalize(author)
            val inSeries = series?.volumes?.map { it.id }?.toSet().orEmpty()
            val seen = hashSetOf(normalize(item.name))
            return data?.all.orEmpty()
                .filter { o -> !isAudioBook(o) && o.id !in inSeries && authorsOf(o).any { normalize(it.first) == k } }
                .filter { seen.add(normalize(it.name)) }
                .sortedByDescending { yearOfBook(it) ?: 0 }
        }

        /** ✓: books follow the reader's record (the server marks them played); audiobooks Jellyfin's played state. */
        val played: Boolean get() = if (audio) item?.userData?.played == true else reading?.finished == true || item?.userData?.played == true
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        load()
    }

    fun load(force: Boolean = false) {
        viewModelScope.launch {
            val (item, parts) = runCatching { repo.book(id) }.getOrElse {
                _state.update { s -> s.copy(loading = false, error = s.item == null) }
                return@launch
            }
            _state.update { it.copy(item = item, parts = parts, loading = false, error = false, policyDownload = session.user.policy?.enableContentDownloading == true) }
            val audio = isAudioBook(item)
            launch {
                if (!audio) {
                    val info = runCatching { repo.books.info(item.id) }.getOrNull()
                    _state.update { it.copy(info = info) }
                }
            }
            launch { if (!audio) _state.update { it.copy(reading = repo.reading(item.id)) } }
            if (_state.value.libraryId == null) repo.libraryOf(item.id)?.let { v -> _state.update { it.copy(libraryId = v.id) } }
            val libraryId = _state.value.libraryId ?: return@launch
            val lib = runCatching { repo.library(libraryId, force) }.getOrNull() ?: return@launch
            val data = booksData(lib.items, repo.readings(), lib.parts)
            _state.update { it.copy(data = data) }
            val s = _state.value
            if (audio) s.readable?.let { r -> _state.update { it.copy(twinReading = data.reading[r.id]) } }
            // The language comes from the EPUB of this work (the PDF and the audiobook share it).
            val epub = (if (s.self?.let(::formatOf) == BookFormat.Epub) s.self else s.work.firstOrNull { formatOf(it) == BookFormat.Epub })
            launch { epub?.let { e -> epubLanguage(repo.books, e.id)?.let { code -> _state.update { it.copy(language = code) } } } }
            for (w in s.work.filter { !isAudioBook(it) }) launch {
                repo.sizeOf(w)?.let { size -> _state.update { it.copy(sizes = it.sizes + (w.id to size)) } }
            }
        }
    }

    /** Back on the page (from the reader or the player): the place may have moved. */
    fun refresh() {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val (fresh, parts) = runCatching { repo.book(id) }.getOrNull() ?: return@launch
            val reading = if (isAudioBook(fresh)) null else repo.reading(item.id)
            _state.update { it.copy(item = fresh, parts = parts, reading = reading) }
        }
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val on = item.userData?.isFavorite != true
        patch { it.copy(isFavorite = on) }
        viewModelScope.launch {
            if (runCatching { browse.setFavorite(item.id, on) }.isFailure) {
                patch { it.copy(isFavorite = !on) }
                _message.value = t("Couldn’t update {name}", "name" to item.name.orEmpty())
            }
        }
    }

    /** Marks the book read (or unread); an audiobook listened (or not). */
    fun togglePlayed() {
        val s = _state.value
        val item = s.item ?: return
        val on = !s.played
        val r = s.reading
        patch { it.copy(played = on) }
        viewModelScope.launch {
            val ok = runCatching {
                if (s.audio) {
                    browse.setPlayed(item.id, on)
                    // Seedy keeps its own place in audiobooks.
                    if (on) repo.saveReading(item.id, Reading(locator = "", percent = 100.0, finished = true)) else repo.deleteReading(item.id)
                } else if (on) {
                    repo.saveReading(item.id, Reading(locator = r?.locator.orEmpty(), percent = 100.0, chapter = r?.chapter, chapterIndex = r?.chapterIndex, chapterCount = r?.chapterCount, finished = true))
                } else {
                    repo.deleteReading(item.id)
                }
            }.isSuccess
            if (!ok) {
                patch { it.copy(played = !on) }
                _message.value = t("Couldn’t update {name}", "name" to item.name.orEmpty())
                return@launch
            }
            _message.value = if (on) t("Marked {name} as read", "name" to item.name.orEmpty()) else t("Marked {name} as unread", "name" to item.name.orEmpty())
            BooksRepo.invalidate()
            if (!s.audio) _state.update { it.copy(reading = repo.reading(item.id)) }
            load(force = true)
        }
    }

    /** Back to the first page (the reader's settings and bookmarks stay), then [open] the book. */
    fun startOver(open: (BaseItemDto) -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            runCatching { repo.saveReading(item.id, Reading(locator = "", percent = 0.0, finished = false)) }
            _state.update { it.copy(reading = repo.reading(item.id)) }
            BooksRepo.invalidate()
            open(item)
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun say(text: String) {
        _message.value = text
    }

    private fun patch(f: (org.jellyfin.sdk.model.api.UserItemDataDto) -> org.jellyfin.sdk.model.api.UserItemDataDto) = _state.update { s ->
        val item = s.item ?: return@update s
        val ud = item.userData ?: return@update s
        s.copy(item = item.copy(userData = f(ud)))
    }

    companion object {
        val FormatOrder = listOf(BookFormat.Epub, BookFormat.Audio, BookFormat.Pdf, BookFormat.Cbz, BookFormat.Cbr, BookFormat.Other)
    }
}
