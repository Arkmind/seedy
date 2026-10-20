package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.home.Home
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto

/** Home for both screen sets: the hero's titles and the rows, each filling in as its titles arrive. */
class HomeViewModel(session: UserSession) : ViewModel() {
    private val home = Home(session)

    data class Row(
        val row: HomeRow,
        val title: String,
        /** Null while loading. */
        val items: List<BaseItemDto>? = null,
        /** Top 10 only: how many profiles watched each title. */
        val profiles: List<Int> = emptyList(),
    )

    data class State(
        val loading: Boolean = true,
        val hasHero: Boolean = true,
        val featured: List<FeaturedEntry>? = null,
        val rows: List<Row> = emptyList(),
        val libraries: List<BaseItemDto> = emptyList(),
    ) {
        /** Rows with titles to show (still-loading rows included, empty ones dropped). */
        val visibleRows: List<Row> get() = rows.filter { it.items == null || it.items.isNotEmpty() }
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
        }
    }

    private suspend fun fill(row: HomeRow, libraries: List<BaseItemDto>) {
        val filled = if (row.kind == "top10") {
            val entries = runCatching { home.top10() }.getOrDefault(emptyList())
            Row(row, Home.title(row), entries.map { it.item }, entries.map { it.profiles })
        } else {
            Row(row, Home.title(row), runCatching { home.items(row, libraries) }.getOrDefault(emptyList()))
        }
        _state.update { s -> s.copy(rows = s.rows.map { if (it.row.id == row.id) filled else it }) }
    }
}
