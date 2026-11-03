package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.history.HistoryApi
import io.github.arkmind.seedy.core.history.HistoryEntry
import io.github.arkmind.seedy.core.history.HistoryFilter
import io.github.arkmind.seedy.core.history.HistoryFormat
import io.github.arkmind.seedy.core.i18n.t
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

/** Viewing history: what the screens draw. [status] is "loading", "ready", "error" or "more" (loading earlier plays). */
data class HistoryState(
    /** The admin's "Viewing history page" switch for this profile (Seedy admin · Play log). */
    val enabled: Boolean = true,
    val filter: HistoryFilter = HistoryFilter.All,
    val text: String = "",
    /** The search the list shows (the typed text, once it settles). */
    val search: String = "",
    val showHidden: Boolean = false,
    val items: List<HistoryEntry> = emptyList(),
    val total: Int = 0,
    val status: String = "loading",
    val message: String? = null,
) {
    val busy: Boolean get() = status == "loading"
    val filtered: Boolean get() = search.isNotEmpty() || filter != HistoryFilter.All
    val more: Boolean get() = items.isNotEmpty() && items.size < total
}

/** What the History screen can do; the view model does it, screenshot tests ignore it. */
interface HistoryActions {
    fun setFilter(filter: HistoryFilter) {}
    fun setText(text: String) {}
    fun setShowHidden(on: Boolean) {}
    fun retry() {}
    fun loadMore() {}
    fun toggleHidden(entry: HistoryEntry) {}
    fun remove(entry: HistoryEntry) {}
    fun clear() {}
    fun messageShown() {}
}

/**
 * Viewing history (web/src/screens/browse/History.tsx): Seedy's play log for this profile, newest first, 30 at a time,
 * by kind and searched on the server. Hiding a title hides all its plays (and stops it shaping recommendations).
 */
class HistoryViewModel(private val session: UserSession) : ViewModel(), HistoryActions {
    private val api = HistoryApi(session.api)
    private val _state = MutableStateFlow(HistoryState(enabled = session.seedy?.historyEnabled == true))
    val state: StateFlow<HistoryState> = _state.asStateFlow()
    private var request = 0
    private var typing: Job? = null

    init {
        load()
    }

    private fun load() {
        val s = _state.value
        if (!s.enabled) return
        val id = ++request
        _state.update { it.copy(status = "loading") }
        viewModelScope.launch {
            val page = io { api.page(0, PAGE, s.filter, s.search, s.showHidden) }
            if (id != request) return@launch
            _state.update {
                if (page == null) it.copy(status = "error") else it.copy(items = page.items, total = page.total, status = "ready")
            }
        }
    }

    override fun setFilter(filter: HistoryFilter) {
        if (filter == _state.value.filter) return
        _state.update { it.copy(filter = filter) }
        load()
    }

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

    override fun setShowHidden(on: Boolean) {
        _state.update { it.copy(showHidden = on) }
        load()
    }

    override fun retry() = load()

    override fun loadMore() {
        val s = _state.value
        if (s.status != "ready") return
        val id = ++request
        _state.update { it.copy(status = "more") }
        viewModelScope.launch {
            val page = io { api.page(s.items.size, PAGE, s.filter, s.search, s.showHidden) }
            if (id != request) return@launch
            if (page == null) return@launch _state.update { it.copy(status = "ready", message = t("Couldn't load earlier plays")) }
            _state.update { st ->
                val seen = st.items.map { it.id }.toSet()
                st.copy(items = st.items + page.items.filter { it.id !in seen }, total = page.total, status = "ready")
            }
        }
    }

    override fun toggleHidden(entry: HistoryEntry) {
        val next = !entry.hidden
        val name = HistoryFormat.title(entry)
        viewModelScope.launch {
            val ok = io { api.hide(entry.titleId, next) } != null
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
            val ok = io { api.remove(entry.id) } != null
            _state.update { s ->
                if (!ok) s.copy(message = t("Couldn't remove that play"))
                else s.copy(items = s.items.filter { it.id != entry.id }, total = maxOf(0, s.total - 1), message = t("Removed from history"))
            }
        }
    }

    override fun clear() {
        viewModelScope.launch {
            val ok = io { api.clear() } != null
            _state.update { s -> if (!ok) s.copy(message = t("Couldn't clear history")) else s.copy(items = emptyList(), total = 0, message = t("History cleared")) }
        }
    }

    override fun messageShown() = _state.update { it.copy(message = null) }

    private suspend fun <T> io(block: suspend () -> T): T? = withContext(Dispatchers.IO) { runCatching { block() }.getOrNull() }

    companion object {
        const val PAGE = 30
    }
}
