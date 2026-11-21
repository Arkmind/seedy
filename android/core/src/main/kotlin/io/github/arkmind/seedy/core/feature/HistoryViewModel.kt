package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.data.ioOrNull
import io.github.arkmind.seedy.core.data.plusNew
import io.github.arkmind.seedy.core.history.HistoryApi
import io.github.arkmind.seedy.core.history.HistoryEntry
import io.github.arkmind.seedy.core.history.HistoryFilter
import io.github.arkmind.seedy.core.history.HistoryFilters
import io.github.arkmind.seedy.core.history.HistoryFormat
import io.github.arkmind.seedy.core.history.HistoryOptions
import io.github.arkmind.seedy.core.history.HistorySort
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Viewing history: what the screens draw. [status] is "loading", "ready", "error" or "more" (loading earlier plays). */
data class HistoryState(
    /** The admin's "Viewing history page" switch for this profile (Seedy admin · Play log). */
    val enabled: Boolean = true,
    val filter: HistoryFilter = HistoryFilter.All,
    val text: String = "",
    /** The search the list shows (the typed text, once it settles). */
    val search: String = "",
    val sort: HistorySort = HistorySort.Date,
    val descending: Boolean = sort.descending,
    val filters: HistoryFilters = HistoryFilters(),
    /** What the Filters sheet offers; null until loaded, and on older plugins (which can't sort or filter: no tools then). */
    val options: HistoryOptions? = null,
    val items: List<HistoryEntry> = emptyList(),
    val total: Int = 0,
    val status: String = "loading",
    val message: String? = null,
) {
    val busy: Boolean get() = status == "loading"
    val showHidden: Boolean get() = filters.hidden
    val filtered: Boolean get() = search.isNotEmpty() || filter != HistoryFilter.All || filters.narrows
    val more: Boolean get() = items.isNotEmpty() && items.size < total

    /** Day groups when sorted by date (newest or oldest first); one list when sorted by title. */
    val byDay: Boolean get() = sort == HistorySort.Date
}

/** What the History screen can do; the view model does it, screenshot tests ignore it. */
interface HistoryActions {
    fun setFilter(filter: HistoryFilter) {}
    fun setText(text: String) {}
    fun setSort(sort: HistorySort, descending: Boolean) {}
    fun setFilters(filters: HistoryFilters) {}

    /** How many entries [filters] would show, for the Filters sheet's button; null when unknown. */
    suspend fun countFor(filters: HistoryFilters): Int? = null
    fun retry() {}
    fun loadMore() {}
    fun toggleHidden(entry: HistoryEntry) {}
    fun remove(entry: HistoryEntry) {}
    fun clear() {}
    fun messageShown() {}
}

/**
 * Viewing history (web/src/screens/browse/History.tsx): Seedy's play log for this profile, 30 at a time, by kind,
 * sorted, filtered and searched on the server. Hiding a title hides all its plays (and stops it shaping recommendations).
 */
class HistoryViewModel(private val session: UserSession) : ViewModel(), HistoryActions {
    private val api = HistoryApi(session.api)
    private val _state = MutableStateFlow(HistoryState(enabled = session.seedy?.historyEnabled == true))
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var request = 0
    private var typing: Job? = null

    init {
        load()
        loadOptions()
    }

    private suspend fun page(s: HistoryState, start: Int, limit: Int, filters: HistoryFilters = s.filters) =
        ioOrNull { api.page(start, limit, s.filter, s.search, filters, s.sort, s.descending) }

    private fun load() {
        val s = _state.value
        if (!s.enabled) return
        val id = ++request
        _state.update { it.copy(status = "loading") }
        viewModelScope.launch {
            val page = page(s, 0, PAGE)
            if (id != request) return@launch
            _state.update {
                if (page == null) it.copy(status = "error") else it.copy(items = page.items, total = page.total, status = "ready")
            }
        }
    }

    /** The years and devices to offer, for this kind; their absence (an older plugin) hides Sort and Filters. */
    private fun loadOptions() {
        val s = _state.value
        if (!s.enabled) return
        viewModelScope.launch {
            val options = ioOrNull { api.options(s.filter) } ?: return@launch
            _state.update { if (it.filter == s.filter) it.copy(options = options) else it }
        }
    }

    override fun setFilter(filter: HistoryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        load()
        loadOptions()
    }

    override fun setSort(sort: HistorySort, descending: Boolean) {
        val s = _state.value
        if (sort == s.sort && descending == s.descending) return
        _state.update { it.copy(sort = sort, descending = descending) }
        load()
    }

    override fun setFilters(filters: HistoryFilters) {
        if (filters == _state.value.filters) return
        _state.update { it.copy(filters = filters) }
        load()
    }

    override suspend fun countFor(filters: HistoryFilters): Int? = page(_state.value, 0, 0, filters)?.total

    /** The server searches once the typing stops for 300 ms. */
    override fun setText(text: String) {
        _state.update { it.copy(text = text) }
        typing?.cancel()
        typing = viewModelScope.launch {
            delay(300)
            val search = text.trim()
            if (search == _state.value.search) return@launch
            _state.update { it.copy(search = search) }
            load()
        }
    }

    override fun retry() = load()

    override fun loadMore() {
        val s = _state.value
        if (s.status != "ready") return
        val id = ++request
        _state.update { it.copy(status = "more") }
        viewModelScope.launch {
            val page = page(s, s.items.size, PAGE)
            if (id != request) return@launch
            if (page == null) return@launch _state.update { it.copy(status = "ready", message = t("Couldn't load earlier plays")) }
            _state.update { st -> st.copy(items = st.items.plusNew(page.items) { it.itemId }, total = page.total, status = "ready") }
        }
    }

    override fun toggleHidden(entry: HistoryEntry) {
        val next = !entry.hidden
        val name = HistoryFormat.title(entry)
        viewModelScope.launch {
            val ok = ioOrNull { api.hide(entry.titleId, next) } != null
            if (!ok) return@launch _state.update { it.copy(message = if (next) t("Couldn't hide {name}", "name" to name) else t("Couldn't unhide {name}", "name" to name)) }
            _state.update { s ->
                if (next && !s.showHidden) {
                    val gone = s.items.count { it.titleId == entry.titleId }
                    s.copy(items = s.items.filter { it.titleId != entry.titleId }, total = maxOf(0, s.total - gone))
                } else {
                    s.copy(items = s.items.map { if (it.titleId == entry.titleId) it.copy(hidden = next) else it })
                }.copy(
                    message = if (next) t("{name} hidden. It no longer shapes your recommendations.", "name" to name)
                    else t("{name} is back in your history", "name" to name),
                )
            }
        }
    }

    override fun remove(entry: HistoryEntry) {
        viewModelScope.launch {
            val ok = ioOrNull { api.remove(entry.id) } != null
            _state.update { s ->
                if (!ok) s.copy(message = t("Couldn't remove that play"))
                else s.copy(items = s.items.filter { it.id != entry.id }, total = maxOf(0, s.total - 1), message = t("Removed from history"))
            }
        }
    }

    override fun clear() {
        viewModelScope.launch {
            val ok = ioOrNull { api.clear() } != null
            _state.update { s -> if (!ok) s.copy(message = t("Couldn't clear history")) else s.copy(items = emptyList(), total = 0, message = t("History cleared")) }
        }
    }

    override fun messageShown() = _state.update { it.copy(message = null) }

    companion object {
        const val PAGE = 30
    }
}
