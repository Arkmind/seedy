package io.github.arkmind.seedy.core.mediarequests

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The person's limit for this kind of request: -1 none, 0 can't request; [left] after what they used. */
private fun MediaOptions.left(): Int = if (limit < 0) Int.MAX_VALUE else maxOf(0, limit - used)

/**
 * Request an artist (web: music-dialog.tsx, board RequestMusicArtist): the whole discography, picked albums, or only
 * what comes out from now on. Albums on the server or already asked for are locked; the quality is the admin's; the
 * person's weekly album limit and whether an admin approves first are shown before sending.
 */
class MusicArtistViewModel(session: UserSession) : ViewModel() {
    private val api = MediaRequestsApi(session.api)

    data class Target(val mbid: String, val name: String? = null, val preselect: String? = null, val imageUrl: String? = null)

    enum class Mode(val label: String) { Artist("Whole artist"), Albums("Choose albums"), Future("New releases only") }

    data class State(
        val target: Target? = null,
        val artist: MusicArtist? = null,
        val options: MediaOptions? = null,
        val failed: Boolean = false,
        val mode: Mode = Mode.Albums,
        val type: String = "album",
        val picked: Set<String> = emptySet(),
        val keepNew: Boolean = true,
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val ready: Boolean get() = artist != null && options != null
        val name: String get() = artist?.artist?.name ?: target?.name ?: t("Loading…")
        val imageUrl: String? get() = artist?.artist?.imageUrl ?: target?.imageUrl
        val seed: String get() = artist?.artist?.mbid ?: target?.mbid.orEmpty()

        val counts: Map<String, Int> get() = artist?.releases.orEmpty().groupingBy { it.type }.eachCount()

        /** Studio releases first, newest first; live recordings and soundtracks after them. */
        val rows: List<MusicRelease> get() = artist?.releases.orEmpty().filter { it.type == type }.sortedBy { if (it.secondary.isNotEmpty()) 1 else 0 }
        val open: List<MusicRelease> get() = rows.filter { !locked(it) }
        val allOn: Boolean get() = open.isNotEmpty() && open.all { it.mbid in picked }

        /** What "Whole artist" asks for: the studio albums and EPs not on the server or asked for yet. */
        private val wholeCount: Int
            get() = artist?.releases.orEmpty().count { !locked(it) && !it.upcoming && it.secondary.isEmpty() && (it.type == "album" || it.type == "ep") }
        val units: Int get() = when (mode) { Mode.Albums -> picked.size; Mode.Artist -> maxOf(1, wholeCount); Mode.Future -> 0 }

        val over: Boolean get() = options?.let { it.limit >= 0 && units > it.left() } ?: false
        val blocked: Boolean get() = options?.let { !it.canRequest || it.limit == 0 } ?: false
        val canSend: Boolean get() = ready && !busy && !blocked && !over && (mode != Mode.Albums || units > 0)

        /** Whether a row's box is ticked: picked, or part of the whole discography. */
        fun on(r: MusicRelease): Boolean = !locked(r) && when (mode) {
            Mode.Artist -> (r.type == "album" || r.type == "ep") && !r.upcoming && r.secondary.isEmpty()
            else -> r.mbid in picked
        }

        /** "Artist · Lisbon · 5 albums, 3 EPs, 9 singles on MusicBrainz · 1 album on the server". */
        val meta: String
            get() {
                val a = artist ?: return ""
                val c = counts
                val found = listOfNotNull(
                    c["album"]?.let { tn(it, "{n} album", "{n} albums") },
                    c["ep"]?.let { tn(it, "{n} EP", "{n} EPs") },
                    c["single"]?.let { tn(it, "{n} single", "{n} singles") },
                ).joinToString(", ") + " " + t("on MusicBrainz")
                val parts = mutableListOf(if (a.artist.type == "Group") t("Band") else t("Artist"), a.artist.area.orEmpty(), found)
                if (a.albumsOnServer > 0) parts += tn(a.albumsOnServer, "{n} album on the server", "{n} albums on the server")
                return parts.filter { it.isNotBlank() }.joinToString(" · ")
            }

        val label: String
            get() = when {
                busy -> t("Requesting…")
                mode == Mode.Artist -> t("Request whole artist")
                mode == Mode.Future -> t("Request new releases")
                units > 0 -> tn(units, "Request {n} album", "Request {n} albums")
                else -> t("Request albums")
            }

        /** The foot's line and its dot: ok, pending or danger. */
        val note: Pair<String, String>
            get() {
                val o = options ?: return "" to "pending"
                return when {
                    blocked -> t("Music requests are switched off for your profile") to "danger"
                    over -> t("That’s more albums than you have left this week") to "danger"
                    o.autoApprove -> t("Approved automatically for you") to "ok"
                    else -> t("An admin approves requests before Lidarr searches") to "pending"
                }
            }

        companion object {
            fun locked(r: MusicRelease) = r.status != "none"
        }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun open(target: Target) {
        if (_state.value.target == target && (_state.value.ready || job?.isActive == true)) return
        _state.value = State(target = target)
        load()
    }

    fun retry() {
        _state.update { it.copy(failed = false) }
        load()
    }

    private fun load() {
        val target = _state.value.target ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val artist = async { api.musicArtist(target.mbid) }
                    val options = async { api.options("music") }
                    artist.await() to options.await()
                }
            }
            val (artist, options) = result.getOrNull() ?: run {
                _state.update { it.copy(failed = true) }
                return@launch
            }
            val pre = artist.releases.firstOrNull { it.mbid == target.preselect }
            val c = artist.releases.groupingBy { it.type }.eachCount()
            _state.update {
                it.copy(
                    artist = artist, options = options, failed = false,
                    type = pre?.type ?: if ((c["album"] ?: 0) > 0) "album" else if ((c["ep"] ?: 0) > 0) "ep" else "single",
                    picked = if (pre != null && !State.locked(pre)) setOf(pre.mbid) else emptySet(),
                )
            }
        }
    }

    fun setMode(mode: Mode) = _state.update { it.copy(mode = mode) }

    fun setType(type: String) = _state.update { it.copy(type = type) }

    fun toggle(mbid: String) = _state.update { s ->
        if (s.mode != Mode.Albums) s else s.copy(picked = if (mbid in s.picked) s.picked - mbid else s.picked + mbid)
    }

    fun toggleAll() = _state.update { s ->
        val ids = s.open.map { it.mbid }.toSet()
        s.copy(picked = if (s.allOn) s.picked - ids else s.picked + ids)
    }

    fun setKeepNew(on: Boolean) = _state.update { it.copy(keepNew = on) }

    /** Sends the request; [onDone] gets what the toast says. */
    fun submit(onDone: (String) -> Unit) {
        val s = _state.value
        val data = s.artist ?: return
        if (!s.canSend) return
        val a = data.artist
        val albums = if (s.mode == Mode.Albums) data.releases.filter { it.mbid in s.picked } else emptyList()
        val body = MediaCreate(
            kind = "music",
            mode = s.mode.name.lowercase(),
            foreignId = a.mbid,
            title = a.name,
            keepNew = s.mode != Mode.Albums || s.keepNew,
            coverUrl = albums.firstOrNull()?.coverUrl ?: s.imageUrl ?: data.releases.firstOrNull { it.type == "album" }?.coverUrl,
            albums = albums.map { AlbumRef(it.mbid, it.title, it.type, it.date?.take(4)?.toIntOrNull()) },
            albumCount = s.units,
        )
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { api.create(body) } }.getOrNull()
            if (res == null) {
                _state.update { it.copy(busy = false, error = t("Couldn’t send the request.")) }
                return@launch
            }
            _state.update { it.copy(busy = false) }
            MediaAsked.note(res)
            onDone(if (res.status == "pending") t("Requested. An admin approves it first.") else t("Requested. Lidarr is on it."))
        }
    }
}

/**
 * Request a book (web: book-dialog.tsx, board RequestBook): the ebook, the audiobook or both (a comic for comics), the
 * language, the person's monthly limit (an ebook and its audiobook count as two), the libraries it lands in, and
 * whether an admin approves it first.
 */
class BookRequestViewModel(session: UserSession) : ViewModel() {
    val api = MediaRequestsApi(session.api)

    data class Target(val card: BookCard, val row: BookRow? = null)

    data class Language(val code: String, val name: String, val ol: List<String>)

    data class State(
        val target: Target? = null,
        val details: BookCard? = null,
        val options: MediaOptions? = null,
        val failed: Boolean = false,
        val picked: Set<String> = emptySet(),
        val lang: String = "",
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val book: BookCard? get() = details ?: target?.card
        val loading: Boolean get() = details == null && !failed

        /** The formats this server can request, and where each goes. */
        val available: List<MediaFormatRoute> get() = options?.formats.orEmpty().filter { it.format != "music" }
        val offered: List<String>
            get() = (if (book?.content == "comic") listOf("comic") else listOf("ebook", "audiobook")).filter { f -> available.any { it.format == f } }

        val units: Int get() = picked.size
        val over: Boolean get() = options?.let { it.limit >= 0 && units > it.left() } ?: false
        val blocked: Boolean get() = options?.let { !it.canRequest || it.limit == 0 } ?: false
        val canSend: Boolean get() = options != null && !busy && !blocked && !over && units > 0

        val original: Language? get() = book?.let { b -> Languages.firstOrNull { l -> b.languages.any { it in l.ol } } }

        /** "Coldwater Mysteries, book 7 · 2026 · 384 pages". */
        val meta: String
            get() {
                val b = book ?: return ""
                return listOfNotNull(
                    b.series?.let { t("{series}, book {n}", "series" to it, "n" to MediaFormat.num(b.seriesIndex)) },
                    b.year?.toString(),
                    b.pages?.let { t("{n} pages", "n" to it) },
                ).joinToString(" · ")
            }

        /** The description, and "You've read books 1 to 6 on Seedy." */
        val description: String
            get() {
                val b = book ?: return ""
                val read = if (b.readTo != null && b.readFrom != null) {
                    if (b.readFrom == b.readTo) t("You’ve read book {n} on Seedy.", "n" to MediaFormat.num(b.readTo))
                    else t("You’ve read books {from} to {to} on Seedy.", "from" to MediaFormat.num(b.readFrom), "to" to MediaFormat.num(b.readTo))
                } else ""
                return listOf(b.description.orEmpty(), read).filter { it.isNotEmpty() }.joinToString(" ")
            }

        private val services: List<String> get() = picked.mapNotNull { f -> available.firstOrNull { it.format == f }?.service }.distinct()
        val libraries: List<String> get() = picked.mapNotNull { f -> available.firstOrNull { it.format == f }?.library?.takeIf { it.isNotEmpty() } }.distinct()

        /** "Seedy scans the library when Shelfarr finishes." */
        val scanLine: String?
            get() = if (options?.scanOnReady == true && services.isNotEmpty()) {
                t("Seedy scans the library when {app} finishes.", "app" to services.joinToString(t(" and ")) { MediaFormat.serviceName(it) })
            } else null

        val label: String
            get() = when {
                busy -> t("Requesting…")
                "ebook" in picked && "audiobook" in picked -> t("Request ebook and audiobook")
                "audiobook" in picked -> t("Request audiobook")
                "comic" in picked -> t("Request comic")
                else -> t("Request ebook")
            }

        val note: Pair<String, String>
            get() {
                val o = options ?: return "" to "pending"
                return when {
                    blocked -> t("Book requests are switched off for your profile") to "danger"
                    over -> tn(o.left(), "You have {n} book request left this month", "You have {n} book requests left this month") to "danger"
                    o.autoApprove -> t("Approved automatically for you") to "ok"
                    else -> t("An admin approves requests before {app} searches", "app" to MediaFormat.serviceName(services.firstOrNull() ?: "shelfarr")) to "pending"
                }
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    fun open(target: Target) {
        if (_state.value.target == target && (_state.value.details != null || job?.isActive == true)) return
        _state.value = State(target = target)
        load()
    }

    fun retry() {
        _state.update { it.copy(failed = false) }
        load()
    }

    private fun load() {
        val target = _state.value.target ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val details = async { api.bookDetails(target.card) }
                    val options = async { api.options("book") }
                    details.await() to options.await()
                }
            }
            val (book, options) = result.getOrNull() ?: run {
                _state.update { it.copy(failed = true) }
                return@launch
            }
            _state.update { s ->
                val next = s.copy(details = book, options = options, failed = false)
                // Start with what the row suggests: the audiobook on "Audiobooks of books you've read", else every format not on the server.
                val start = next.offered.filter { it !in book.onServer && (target.row != BookRow.Audiobooks || it == "audiobook") }.toSet()
                next.copy(picked = start, lang = next.original?.code ?: if (I18n.lang == Lang.Fr) "fr" else "en")
            }
        }
    }

    fun toggle(format: String) = _state.update { s ->
        if (format in s.book?.onServer.orEmpty()) s else s.copy(picked = if (format in s.picked) s.picked - format else s.picked + format)
    }

    fun setLang(code: String) = _state.update { it.copy(lang = code) }

    fun submit(onDone: (String) -> Unit) {
        val s = _state.value
        val book = s.book ?: return
        if (!s.canSend) return
        val body = MediaCreate(
            kind = "book",
            mode = "book",
            foreignId = book.workId,
            title = book.title,
            keepNew = false,
            subtitle = book.author,
            coverUrl = book.coverUrl,
            formats = s.offered.filter { it in s.picked },
            content = book.content,
            series = book.series,
            seriesIndex = book.seriesIndex,
            year = book.year,
            language = s.lang.ifEmpty { null },
        )
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { runCatching { api.create(body) } }.getOrNull()
            if (res == null) {
                _state.update { it.copy(busy = false, error = t("Couldn’t send the request.")) }
                return@launch
            }
            _state.update { it.copy(busy = false) }
            MediaAsked.note(res)
            onDone(if (res.status == "pending") t("Requested. An admin approves it first.") else t("Requested. {app} is on it.", "app" to MediaFormat.serviceName(res.service)))
        }
    }

    companion object {
        val Languages = listOf(
            Language("en", "English", listOf("eng")),
            Language("fr", "French", listOf("fre", "fra")),
            Language("de", "German", listOf("ger", "deu")),
            Language("es", "Spanish", listOf("spa")),
            Language("it", "Italian", listOf("ita")),
            Language("nl", "Dutch", listOf("dut", "nld")),
            Language("pt", "Portuguese", listOf("por")),
            Language("ja", "Japanese", listOf("jpn")),
        )
    }
}
