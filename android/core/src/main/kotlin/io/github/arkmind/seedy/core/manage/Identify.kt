package io.github.arkmind.seedy.core.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/*
 * Identify (board ItemIdentify): search the metadata providers by name, year and ids
 * (POST /Items/RemoteSearch/{kind}), pick the right match and apply it (POST /Items/RemoteSearch/Apply/{id}). The match
 * the title has now says "Current match"; the closest one comes first.
 */

data class Match(
    val title: String,
    /** "2024 · Halden". */
    val meta: String,
    /** "TMDb 219408 · TVDb 402117". */
    val source: String,
    val art: String?,
    val current: Boolean,
    val result: JsonObject,
)

data class IdentifyState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val type: String = "",
    val poster: String? = null,
    val name: String = "",
    val year: String = "",
    /** The ids to search by (TMDb, TVDb, IMDb). */
    val ids: List<ProviderField> = emptyList(),
    val searching: Boolean = false,
    /** Null before the first search. */
    val matches: List<Match>? = null,
    val picked: Int? = null,
    val replaceImages: Boolean = true,
    val applying: Boolean = false,
    val error: String? = null,
) {
    /** "Which show is this?" */
    val question: String get() = when (type) {
        "Series" -> t("Which show is this?")
        "Movie" -> t("Which movie is this?")
        "BoxSet" -> t("Which collection is this?")
        "MusicAlbum" -> t("Which album is this?")
        "MusicArtist" -> t("Which artist is this?")
        "Person" -> t("Who is this?")
        else -> t("Which title is this?")
    }
    val canApply: Boolean get() = picked != null && !applying
    val summary: String get() = matches?.let { tn(it.size, "{n} match", "{n} matches") }.orEmpty()
}

interface IdentifyActions {
    fun setName(v: String) {}
    fun setYear(v: String) {}
    fun setId(key: String, v: String) {}
    fun search() {}
    fun pick(index: Int) {}
    fun setReplaceImages(on: Boolean) {}
    fun retry() {}
    /** Applies the picked match; [onDone] gets what to say. */
    fun apply(onDone: (String) -> Unit) {}
}

class IdentifyViewModel(session: UserSession, private val id: String) : ViewModel(), IdentifyActions {
    private val api = ManageApi(session)
    private val _state = MutableStateFlow(IdentifyState())
    val state: StateFlow<IdentifyState> = _state.asStateFlow()
    private var current: Map<String, String> = emptyMap()
    private var searchJob: Job? = null

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(loading = true, failed = false) }
        viewModelScope.launch {
            val result = runCatching { api.item(id) }
            result.onSuccess { item ->
                val type = (item["Type"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                current = (item["ProviderIds"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k.lowercase() to it } }.toMap()
                val keys = when (type) {
                    "Series" -> listOf("Tmdb", "Tvdb", "Imdb")
                    "Movie", "BoxSet", "Trailer" -> listOf("Tmdb", "Imdb")
                    "Person" -> listOf("Tmdb", "Imdb")
                    "MusicAlbum" -> listOf("MusicBrainzAlbum")
                    "MusicArtist" -> listOf("MusicBrainzArtist")
                    else -> listOf("Tmdb", "Imdb")
                }
                val year = (item["ProductionYear"] as? JsonPrimitive)?.intOrNull?.toString().orEmpty()
                val tag = ((item["ImageTags"] as? JsonObject)?.get("Primary") as? JsonPrimitive)?.contentOrNull
                _state.value = IdentifyState(
                    loading = false,
                    type = type,
                    poster = tag?.let { api.http.api.baseUrl.orEmpty().trimEnd('/') + "/Items/$id/Images/Primary?tag=$it&maxWidth=200&quality=90" },
                    name = (item["Name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    year = year,
                    // The ids start empty, as on the board: name and year find the title; an id narrows it down.
                    ids = keys.map { ProviderField(it, MetadataViewModel.providerLabel(it, it), "") },
                )
                search()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(loading = false, failed = true, error = t("Couldn’t load this title. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun retry() = load()
    override fun setName(v: String) = _state.update { it.copy(name = v) }
    override fun setYear(v: String) = _state.update { it.copy(year = v.filter(Char::isDigit).take(4)) }
    override fun setId(key: String, v: String) = _state.update { s -> s.copy(ids = s.ids.map { if (it.key == key) it.copy(value = v) else it }) }
    override fun pick(index: Int) = _state.update { it.copy(picked = index) }
    override fun setReplaceImages(on: Boolean) = _state.update { it.copy(replaceImages = on) }

    override fun search() {
        val s = _state.value
        val kind = kindPath(s.type) ?: return
        searchJob?.cancel()
        _state.update { it.copy(searching = true, error = null) }
        searchJob = viewModelScope.launch {
            val body = buildJsonObject {
                put("SearchInfo", buildJsonObject {
                    put("Name", s.name.trim())
                    s.year.toIntOrNull()?.let { put("Year", it) }
                    put("ProviderIds", buildJsonObject { s.ids.filter { it.value.isNotBlank() }.forEach { put(it.key, it.value.trim()) } })
                })
                put("ItemId", id)
                put("IncludeDisabledProviders", true)
            }
            val result = runCatching { api.remoteSearch(kind, body) }
            result.onSuccess { list ->
                val matches = rank(list.map(::match), s.name, s.year.toIntOrNull())
                _state.update { it.copy(searching = false, matches = matches, picked = if (matches.isNotEmpty()) 0 else null) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(searching = false, matches = it.matches ?: emptyList(), error = t("The search didn’t work. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun apply(onDone: (String) -> Unit) {
        val s = _state.value
        val m = s.picked?.let { s.matches?.getOrNull(it) } ?: return
        _state.update { it.copy(applying = true, error = null) }
        viewModelScope.launch {
            val result = runCatching { api.applyMatch(id, m.result, s.replaceImages) }
            result.onSuccess {
                _state.update { it.copy(applying = false) }
                onDone(t("Now matched to {name}", "name" to m.title))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(applying = false, error = t("Couldn’t use this match. {error}", "error" to errorText(e))) }
            }
        }
    }

    private fun match(r: JsonObject): Match {
        fun text(k: String) = (r[k] as? JsonPrimitive)?.contentOrNull
        val ids = (r["ProviderIds"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { k to it } }
        val year = (r["ProductionYear"] as? JsonPrimitive)?.intOrNull ?: text("PremiereDate")?.take(4)?.toIntOrNull()
        val meta = listOfNotNull(year?.toString(), text("AlbumArtist"), text("SearchProviderName")?.let { providerName(it) }).distinct().joinToString(" · ")
        return Match(
            title = text("Name").orEmpty(),
            meta = meta,
            source = ids.filter { (k, _) -> k.lowercase() in setOf("tmdb", "tvdb", "imdb") }.sortedBy { listOf("tmdb", "tvdb", "imdb").indexOf(it.first.lowercase()) }
                .joinToString(" · ") { (k, v) -> "${MetadataViewModel.providerLabel(k, k)} $v" },
            art = text("ImageUrl")?.takeIf { it.isNotBlank() }?.let { api.remoteImageUrl(it, text("SearchProviderName")) },
            current = ids.any { (k, v) -> current[k.lowercase()] == v },
            result = r,
        )
    }

    companion object {
        fun kindPath(type: String): String? = when (type) {
            "Movie", "Series", "BoxSet", "Person", "MusicAlbum", "MusicArtist", "Book", "MusicVideo", "Trailer" -> type
            else -> null
        }

        private fun providerName(name: String): String = when (name) {
            "TheMovieDb" -> "TMDb"
            "TheTVDB", "TheTvdb" -> "TVDb"
            else -> name
        }

        /** Closest first: the same name, then the same year, then the order the providers gave. */
        fun rank(list: List<Match>, name: String, year: Int?): List<Match> {
            val n = name.trim().lowercase()
            return list.withIndex().sortedWith(compareBy({ m ->
                val title = m.value.title.lowercase()
                val y = m.value.meta.take(4).toIntOrNull()
                -((if (title == n) 4 else if (title.startsWith(n) || n.startsWith(title)) 2 else 0) + (if (year != null && y == year) 3 else if (year != null && y != null && kotlin.math.abs(y - year) <= 1) 1 else 0))
            }, { it.index })).map { it.value }
        }
    }
}

private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
