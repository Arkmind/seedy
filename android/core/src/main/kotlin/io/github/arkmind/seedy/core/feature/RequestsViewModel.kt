package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.requests.RequestSummary
import io.github.arkmind.seedy.core.requests.RequestsApi
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.requests.TitlesKind
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Requests for both screen sets (web: screens/requests): Discover's rows of Seerr titles, the person's own requests
 * and, for those who may, everyone's (with approve and decline).
 */
class RequestsViewModel(session: UserSession) : ViewModel() {
    private val api = RequestsApi(session.api)

    enum class Tab { Discover, Mine, All }

    data class Row(val kind: TitlesKind, val items: List<SeerrTitle>? = null)

    data class State(
        val tab: Tab = Tab.Discover,
        val summary: RequestSummary? = null,
        val mine: List<SeerrRequest>? = null,
        val all: List<SeerrRequest>? = null,
        val rows: List<Row> = TitlesKind.entries.map { Row(it) },
        /** Requests with an approve, decline or retry on its way. */
        val busy: Set<Int> = emptySet(),
        val message: String? = null,
    ) {
        /** Open requests of the person's: not on the server yet and not declined. */
        val open: List<SeerrRequest>
            get() = mine.orEmpty().filter { it.status != "available" && it.requestStatus != "declined" }
        val waiting: Int get() = open.count { it.requestStatus == "pending" }
        val downloading: Int get() = open.count { it.download != null }
        val ready: Int get() = mine.orEmpty().count { it.status == "available" }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val summary = io { api.summary() }
            _state.update { it.copy(summary = summary ?: it.summary) }
        }
        viewModelScope.launch { loadMine() }
        for (kind in TitlesKind.entries) {
            viewModelScope.launch {
                val titles = io { api.titles(kind).results }.orEmpty()
                _state.update { s -> s.copy(rows = s.rows.map { if (it.kind == kind) it.copy(items = titles) else it }) }
            }
        }
        if (_state.value.tab == Tab.All) viewModelScope.launch { loadAll() }
    }

    private suspend fun loadMine() {
        val mine = io { api.mine().results }
        _state.update { it.copy(mine = mine ?: it.mine ?: emptyList()) }
    }

    private suspend fun loadAll() {
        val all = io { api.list(scope = "all").results }
        _state.update { it.copy(all = all ?: it.all ?: emptyList()) }
    }

    fun setTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab == Tab.All && _state.value.all == null) viewModelScope.launch { loadAll() }
    }

    fun approve(r: SeerrRequest) = act(r, t("Approved {title}", "title" to r.title), t("Couldn’t approve {title}", "title" to r.title)) { api.approve(it) }

    fun decline(r: SeerrRequest) = act(r, t("Declined {title}", "title" to r.title), t("Couldn’t decline {title}", "title" to r.title)) { api.decline(it) }

    fun retry(r: SeerrRequest) = act(r, t("Asked Seerr again for {title}", "title" to r.title), t("Couldn’t retry {title}", "title" to r.title)) { api.retry(it) }

    fun remove(r: SeerrRequest) = act(r, t("Removed the request for {title}", "title" to r.title), t("Couldn’t remove the request for {title}", "title" to r.title)) { api.remove(it) }

    private fun act(r: SeerrRequest, done: String, failed: String, call: suspend (Int) -> Unit) {
        _state.update { it.copy(busy = it.busy + r.requestId) }
        viewModelScope.launch {
            val ok = io { call(r.requestId); true } ?: false
            _state.update { it.copy(busy = it.busy - r.requestId, message = if (ok) done else failed) }
            loadMine()
            if (_state.value.all != null) loadAll()
            io { api.summary() }?.let { s -> _state.update { it.copy(summary = s) } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    private suspend fun <T> io(block: suspend () -> T): T? = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
}
