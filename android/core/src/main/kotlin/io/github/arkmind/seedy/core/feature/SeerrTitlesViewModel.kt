package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.requests.RequestsApi
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

/** A Discover row's "See all": its titles, a page more each time the grid nears its end. */
class SeerrTitlesViewModel(session: UserSession, val kind: TitlesKind) : ViewModel() {
    private val api = RequestsApi(session.api)

    data class State(val items: List<SeerrTitle>? = null, val page: Int = 0, val pages: Int = 1, val loading: Boolean = false, val failed: Boolean = false) {
        val more: Boolean get() = page < pages
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        more()
    }

    fun more() {
        val s = _state.value
        if (s.loading || !s.more) return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { api.titles(kind, s.page + 1) }.getOrNull() }
            _state.update { st ->
                if (r == null) {
                    st.copy(loading = false, failed = true, items = st.items ?: emptyList())
                } else {
                    // Seerr repeats a title across pages now and then.
                    val seen = st.items.orEmpty().map { it.mediaType to it.tmdbId }.toSet()
                    st.copy(items = st.items.orEmpty() + r.results.filter { (it.mediaType to it.tmdbId) !in seen }, page = st.page + 1, pages = r.pages, loading = false, failed = false)
                }
            }
        }
    }
}
