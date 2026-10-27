package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.format.daysFromToday
import io.github.arkmind.seedy.core.format.parseDay
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

    /** My requests or All requests, a page at a time as the list scrolls (web: the Sentinel under the list). */
    data class Page(val items: List<SeerrRequest>? = null, val total: Int = 0, val loading: Boolean = false) {
        val more: Boolean get() = items != null && items.size < total
    }

    data class State(
        val tab: Tab = Tab.Discover,
        val summary: RequestSummary? = null,
        /** The person's latest requests (Seedy's Mine: up to 60), for the in-progress card and TV's request cards. */
        val latest: List<SeerrRequest>? = null,
        val mine: Page = Page(),
        val all: Page = Page(),
        val rows: List<Row> = TitlesKind.entries.map { Row(it) },
        /** Requests with an approve, decline or retry on its way. */
        val busy: Set<Int> = emptySet(),
        val message: String? = null,
    ) {
        /** Open requests of the person's: not on the server yet and not declined. */
        val open: List<SeerrRequest>
            get() = latest.orEmpty().filter { it.status != "available" && it.requestStatus != "declined" }
        val waiting: Int get() = open.count { it.requestStatus == "pending" }
        val downloading: Int get() = open.count { it.download != null }
        /** Approved, and Seerr hasn't found a download yet. */
        val searching: Int get() = open.count { it.requestStatus != "pending" && it.download == null }

        /** On the server in the last week, so the card still shows them. */
        val recent: List<SeerrRequest>
            get() = latest.orEmpty().filter { r ->
                r.status == "available" && (parseDay(r.availableAt ?: r.updatedAt)?.let { -daysFromToday(it) <= 7 } ?: false)
            }
        val ready: Int get() = recent.size

        /** The requests the in-progress card counts: the open ones and the ones that just arrived. */
        val inProgress: List<SeerrRequest> get() = open + recent

        fun page(tab: Tab): Page = if (tab == Tab.All) all else mine
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
        viewModelScope.launch { loadLatest() }
        for (kind in TitlesKind.entries) {
            viewModelScope.launch {
                val titles = io { api.titles(kind).results }.orEmpty()
                _state.update { s -> s.copy(rows = s.rows.map { if (it.kind == kind) it.copy(items = titles) else it }) }
            }
        }
        reloadPages()
    }

    private suspend fun loadLatest() {
        val latest = io { api.mine().results }
        _state.update { it.copy(latest = latest ?: it.latest ?: emptyList()) }
    }

    /** The open lists again from the top, as many as were already shown (after a change, or on coming back). */
    private fun reloadPages() {
        val s = _state.value
        for (tab in listOf(Tab.Mine, Tab.All)) {
            if (s.page(tab).items != null || s.tab == tab) load(tab, reset = true)
        }
    }

    fun setTab(tab: Tab) {
        _state.update { it.copy(tab = tab) }
        if (tab != Tab.Discover && _state.value.page(tab).items == null) load(tab, reset = true)
    }

    /** The next page of [tab]'s list, when there is one. */
    fun loadMore(tab: Tab) = load(tab, reset = false)

    private fun load(tab: Tab, reset: Boolean) {
        val page = _state.value.page(tab)
        if (page.loading || (!reset && !page.more)) return
        val shown = page.items.orEmpty().size
        val skip = if (reset) 0 else shown
        val take = if (reset) shown.coerceIn(PageSize, 100) else PageSize
        setPage(tab) { it.copy(loading = true) }
        viewModelScope.launch {
            val r = io { api.list(scope = if (tab == Tab.All) "all" else "mine", take = take, skip = skip) }
            setPage(tab) { p ->
                when {
                    r == null -> p.copy(loading = false, items = p.items ?: emptyList())
                    reset -> Page(r.results, r.total, loading = false)
                    else -> {
                        // A request can move between pages while the list is open: don't show it twice.
                        val seen = p.items.orEmpty().map { it.requestId }.toSet()
                        Page(p.items.orEmpty() + r.results.filter { it.requestId !in seen }, r.total, loading = false)
                    }
                }
            }
        }
    }

    private fun setPage(tab: Tab, change: (Page) -> Page) =
        _state.update { if (tab == Tab.All) it.copy(all = change(it.all)) else it.copy(mine = change(it.mine)) }

    fun approve(r: SeerrRequest) = act(r, t("Approved {title}", "title" to r.title), t("Couldn’t approve {title}", "title" to r.title)) { api.approve(it) }

    fun decline(r: SeerrRequest) = act(r, t("Declined {title}", "title" to r.title), t("Couldn’t decline {title}", "title" to r.title)) { api.decline(it) }

    fun retry(r: SeerrRequest) = act(r, t("Asked Seerr again for {title}", "title" to r.title), t("Couldn’t retry {title}", "title" to r.title)) { api.retry(it) }

    fun remove(r: SeerrRequest) = act(r, t("Removed the request for {title}", "title" to r.title), t("Couldn’t remove the request for {title}", "title" to r.title)) { api.remove(it) }

    private fun act(r: SeerrRequest, done: String, failed: String, call: suspend (Int) -> Unit) {
        _state.update { it.copy(busy = it.busy + r.requestId) }
        viewModelScope.launch {
            val ok = io { call(r.requestId); true } ?: false
            _state.update { it.copy(busy = it.busy - r.requestId, message = if (ok) done else failed) }
            loadLatest()
            reloadPages()
            io { api.summary() }?.let { s -> _state.update { it.copy(summary = s) } }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        private const val PageSize = 30
    }

    private suspend fun <T> io(block: suspend () -> T): T? = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }
}
