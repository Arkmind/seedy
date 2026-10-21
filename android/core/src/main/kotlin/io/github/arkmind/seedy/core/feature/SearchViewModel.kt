package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.HitKind
import io.github.arkmind.seedy.core.browse.SearchEngine
import io.github.arkmind.seedy.core.browse.SearchResults
import io.github.arkmind.seedy.core.seedy.SeerrItem
import io.github.arkmind.seedy.core.seedy.seerr
import io.github.arkmind.seedy.core.seedy.seerrInSearch
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * Search for both screen sets (boards "Android · Search" and "Android TV · Search"): results follow the typing, the
 * best match on top with what Play would start, then Seerr ("Not here? Find it on Seerr") when Seedy has it on.
 */
class SearchViewModel(private val session: UserSession, private val channels: Boolean = false) : ViewModel() {
    private val engine = SearchEngine(session)
    private val browse = Browse(session)

    /** What the best match's button plays: a show's episode to resume, or the title itself. */
    data class TopPlay(val target: BaseItemDto?, val resume: Boolean)

    data class Seerr(val loading: Boolean = true, val items: List<SeerrItem> = emptyList(), val error: Boolean = false)

    data class State(
        val text: String = "",
        /** The query the results are for (two letters or more, after a short pause in the typing). */
        val query: String = "",
        val results: SearchResults? = null,
        val error: Boolean = false,
        val filter: HitKind? = null,
        val top: TopPlay? = null,
        val personLines: Map<UUID, String> = emptyMap(),
        /** Seerr's answer for [query]; null until asked. */
        val seerr: Seerr? = null,
        /** The "Not here? Find it on Seerr" card shows (Seedy · Requests › "Request from search"). */
        val seerrOn: Boolean = false,
        val recent: List<String> = emptyList(),
        val genres: List<String> = emptyList(),
    ) {
        val idle: Boolean get() = text.trim().length < 2
        val searching: Boolean get() = !idle && (results == null || results.q != text.trim().replace(Regex("\\s+"), " ")) && !error
    }

    private val _state = MutableStateFlow(
        State(
            seerrOn = session.seerrInSearch,
            recent = session.seedy?.prefs?.custom?.get(RecentKey)?.let { el -> runCatching { el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull() }.orEmpty(),
        ),
    )
    val state: StateFlow<State> = _state.asStateFlow()
    private var job: Job? = null

    init {
        viewModelScope.launch { _state.update { it.copy(genres = engine.genreNames().take(12)) } }
    }

    fun setText(text: String) {
        _state.update { it.copy(text = text) }
        job?.cancel()
        val term = text.trim().replace(Regex("\\s+"), " ")
        if (term.length < 2) {
            _state.update { it.copy(query = "", results = null, error = false, top = null, seerr = null) }
            return
        }
        job = viewModelScope.launch {
            delay(250)
            run(term)
        }
    }

    fun retry() = setText(_state.value.text)

    private suspend fun run(term: String) {
        val r = runCatching { engine.search(term, channels = channels) }.getOrNull()
        if (r == null) {
            _state.update { it.copy(query = term, error = true) }
            return
        }
        _state.update { it.copy(query = term, results = r, error = false, top = null, seerr = null, filter = it.filter?.takeIf { k -> r.all.any { h -> h.kind == k } }) }
        // Nothing in the library: Seerr's answer comes straight away.
        if (r.isEmpty && _state.value.seerrOn) askSeerr()
        r.best?.let { best ->
            val top = when (best.item.type) {
                BaseItemKind.SERIES -> browse.seriesTarget(best.item.id)?.let { t -> TopPlay(t.item, t.resume) } ?: TopPlay(null, false)
                BaseItemKind.BOX_SET -> TopPlay(null, false)
                else -> TopPlay(best.item, best.item.userData?.let { it.playbackPositionTicks > 0 && !it.played } == true)
            }
            if (_state.value.query == term) _state.update { it.copy(top = top) }
        }
        for (p in r.people.take(8)) {
            val line = engine.personLine(p.item, short = channels)
            if (_state.value.query != term) return
            _state.update { it.copy(personLines = it.personLines + (p.item.id to line)) }
        }
    }

    fun setFilter(kind: HitKind?) = _state.update { it.copy(filter = kind) }

    /** "Not here? Find it on Seerr": Seerr's titles for the query (opening one goes to its Seerr page). */
    fun askSeerr() {
        val api = session.seerr ?: return
        val q = _state.value.query.ifBlank { return }
        _state.update { it.copy(seerr = Seerr()) }
        viewModelScope.launch {
            val r = runCatching { api.search(q) }.getOrNull()
            if (_state.value.query != q) return@launch
            _state.update { it.copy(seerr = if (r == null) Seerr(loading = false, error = true) else Seerr(loading = false, items = r.results)) }
        }
    }

    /** Remembers what was searched (newest first, at most 8), in the profile like the web's recent searches. */
    fun remember() {
        val term = _state.value.text.trim().replace(Regex("\\s+"), " ")
        if (term.length < 2 || session.seedyApi == null) return
        val list = _state.value.recent
        if (list.firstOrNull()?.equals(term, ignoreCase = true) == true) return
        val next = (listOf(term) + list.filterNot { it.equals(term, ignoreCase = true) }).take(8)
        _state.update { it.copy(recent = next) }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    session.api.post<JsonElement>("/Seedy/Me/Prefs", requestBody = PrefsPatch(mapOf(RecentKey to JsonArray(next.map { JsonPrimitive(it) }))))
                }
            }
        }
    }

    @Serializable
    private data class PrefsPatch(val custom: Map<String, JsonElement>)

    companion object {
        const val RecentKey = "search.recent"
    }
}
