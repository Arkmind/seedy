package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.clearResume
import io.github.arkmind.seedy.core.home.Home
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.wrapped.WrappedTeaser
import io.github.arkmind.seedy.core.wrapped.wrappedStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto

/** Home for both screen sets: the hero's titles and the rows, each filling in as its titles arrive. */
class HomeViewModel(private val session: UserSession) : ViewModel() {
    private val home = Home(session)

    data class Row(
        val row: HomeRow,
        val title: String,
        /** Null while loading. */
        val items: List<BaseItemDto>? = null,
        /** Top 10 only: how many profiles watched each title. */
        val profiles: List<Int> = emptyList(),
        /** Discover and Upcoming movies: Seerr's titles (null while loading). */
        val seerr: List<SeerrTitle>? = null,
    ) {
        val seerrRow: Boolean get() = row.kind in Home.SeerrKinds
    }

    data class State(
        val loading: Boolean = true,
        val hasHero: Boolean = true,
        val featured: List<FeaturedEntry>? = null,
        val rows: List<Row> = emptyList(),
        val libraries: List<BaseItemDto> = emptyList(),
        /** Pull to refresh is fetching everything again (the rows on screen stay until it's done). */
        val refreshing: Boolean = false,
        /** This year's Seedy Wrapped, offered under the hero while it's December (servers with Seedy). */
        val wrapped: WrappedTeaser? = null,
    ) {
        /** Rows with titles to show (still-loading rows included, empty ones dropped). */
        val visibleRows: List<Row>
            get() = rows.filter { if (it.seerrRow) it.seerr == null || it.seerr.isNotEmpty() else it.items == null || it.items.isNotEmpty() }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val libraries = runCatching { home.libraries() }.getOrDefault(emptyList())
            val rows = runCatching { home.rows(libraries) }.getOrDefault(emptyList())
            val hasHero = rows.any { it.kind == "featured" }
            val list = rows.filter { it.kind != "featured" }
            _state.update { State(loading = false, hasHero = hasHero, libraries = libraries, rows = list.map { Row(it, Home.title(it)) }) }
            if (hasHero) launch {
                val featured = runCatching { home.featured() }.getOrDefault(emptyList())
                _state.update { it.copy(featured = featured) }
            }
            for (row in list) launch { fill(row, libraries) }
            launch { loadWrapped() }
        }
    }

    private suspend fun loadWrapped() {
        val current = wrappedStatus(session)?.current
        _state.update { it.copy(wrapped = current) }
    }

    /** Pull to refresh: the rows and the hero again, swapped in all at once when they're in. */
    fun refresh() {
        if (_state.value.refreshing) return
        _state.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val libraries = runCatching { home.libraries() }.getOrDefault(_state.value.libraries)
            val rows = runCatching { home.rows(libraries) }.getOrNull()
            if (rows == null) {
                _state.update { it.copy(refreshing = false) }
                return@launch
            }
            val hasHero = rows.any { it.kind == "featured" }
            val list = rows.filter { it.kind != "featured" }
            val (featured, filled) = coroutineScope {
                val featured = async { if (hasHero) runCatching { home.featured() }.getOrDefault(emptyList()) else null }
                val filled = list.map { r -> async { rowFor(r, libraries) } }
                featured.await() to filled.awaitAll()
            }
            _state.update { State(loading = false, hasHero = hasHero, featured = featured, rows = filled, libraries = libraries, wrapped = it.wrapped) }
            loadWrapped()
        }
    }

    /**
     * A Continue watching card's menu: marks the title watched ([watched]), or takes it out of the row without
     * marking it (its resume point goes, so it starts over). The card leaves at once, then Continue watching and Next
     * up fill again. [done] says whether it worked.
     */
    fun finishWatching(item: BaseItemDto, watched: Boolean, done: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { if (watched) Browse(session).setPlayed(item.id, true) else session.clearResume(item.id) }
            }.isSuccess
            if (ok) {
                _state.update { s -> s.copy(rows = s.rows.map { r -> if (r.row.kind == "continue") r.copy(items = r.items?.filter { it.id != item.id }) else r }) }
                val libraries = _state.value.libraries
                for (r in _state.value.rows.filter { it.row.kind == "continue" || it.row.kind == "nextup" }) launch { fill(r.row, libraries) }
            }
            done(ok)
        }
    }

    private suspend fun fill(row: HomeRow, libraries: List<BaseItemDto>) {
        val filled = rowFor(row, libraries)
        _state.update { s -> s.copy(rows = s.rows.map { if (it.row.id == row.id) filled else it }) }
    }

    /** A row with its titles (empty when they can't be had). */
    private suspend fun rowFor(row: HomeRow, libraries: List<BaseItemDto>): Row =
        if (row.kind == "top10") {
            val entries = runCatching { home.top10() }.getOrDefault(emptyList())
            Row(row, Home.title(row), entries.map { it.item }, entries.map { it.profiles })
        } else if (row.kind in Home.SeerrKinds) {
            Row(row, Home.title(row), seerr = runCatching { home.seerr(row) }.getOrDefault(emptyList()))
        } else {
            Row(row, Home.title(row), runCatching { home.items(row, libraries) }.getOrDefault(emptyList()))
        }
}
