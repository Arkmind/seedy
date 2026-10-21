package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.Filters
import io.github.arkmind.seedy.core.browse.ItemsQuery
import io.github.arkmind.seedy.core.browse.LibraryTab
import io.github.arkmind.seedy.core.browse.Sort
import io.github.arkmind.seedy.core.browse.baseQuery
import io.github.arkmind.seedy.core.browse.playableTypesFor
import io.github.arkmind.seedy.core.browse.sorted
import io.github.arkmind.seedy.core.browse.tabsFor
import io.github.arkmind.seedy.core.browse.typesFor
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.jellyfin.sdk.api.client.extensions.filterApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * One library's page for both screen sets (boards "Android · Movies library" and "Android TV · Movies library"): its
 * tabs, the sorted and filtered grid (a page at a time), the A–Z jump, Shuffle, and the Suggestions and Genres tabs.
 */
class LibraryViewModel(session: UserSession, private val id: UUID) : ViewModel() {
    private val browse = Browse(session)
    private val api = session.api
    private val userId = session.userId

    data class SuggestionRow(val title: String, val sub: String = "", val items: List<BaseItemDto>)
    data class GenreTile(val name: String, val art: BaseItemDto? = null)
    data class FilterOptions(val genres: List<String> = emptyList(), val decades: List<Int> = emptyList(), val ratings: List<String> = emptyList())

    data class State(
        val view: BaseItemDto? = null,
        /** The person's other libraries (the phone title's menu). */
        val libraries: List<BaseItemDto> = emptyList(),
        val error: Boolean = false,
        /** Titles in the whole library, not the filtered view. */
        val total: Int? = null,
        val tab: LibraryTab = LibraryTab.All,
        val sort: Sort = Sort.DateAdded,
        val descending: Boolean = true,
        val filters: Filters = Filters(),
        val items: List<BaseItemDto> = emptyList(),
        val itemsTotal: Int = 0,
        val loading: Boolean = true,
        val done: Boolean = false,
        val pageError: Boolean = false,
        val suggestions: List<SuggestionRow>? = null,
        val genres: List<GenreTile>? = null,
        val options: FilterOptions? = null,
    ) {
        val tabs: List<Pair<LibraryTab, String>> get() = tabsFor(view?.collectionType)
        val isGrid: Boolean get() = tab != LibraryTab.Suggestions && tab != LibraryTab.Genres
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var pageJob: Job? = null
    private var generation = 0

    init {
        viewModelScope.launch {
            val libraries = runCatching { browse.libraries() }.getOrDefault(emptyList())
            val view = libraries.firstOrNull { it.id == id } ?: runCatching { browse.item(id) }.getOrNull()
            if (view == null) {
                _state.update { it.copy(error = true, loading = false) }
                return@launch
            }
            _state.update { it.copy(view = view, libraries = libraries, tab = tabsFor(view.collectionType).first().first) }
            reload()
            // A title deleted elsewhere (its details page) leaves this grid too.
            launch {
                io.github.arkmind.seedy.core.manage.Removals.flow.collect { gone ->
                    removed(_state.value.items.filter { io.github.arkmind.seedy.core.admin.normId(it.id.toString()) in gone }.map { it.id }.toSet())
                }
            }
            launch {
                val n = runCatching { browse.items(ItemsQuery(parentId = view.id, includeTypes = typesFor(view.collectionType), limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount }.getOrNull()
                _state.update { it.copy(total = n) }
            }
        }
    }

    fun setTab(tab: LibraryTab) {
        if (tab == _state.value.tab) return
        val sort = if (tab == LibraryTab.Collections) Sort.Title else _state.value.sort
        _state.update { it.copy(tab = tab, sort = sort, descending = if (tab == LibraryTab.Collections) false else it.descending) }
        reload()
    }

    fun setSort(sort: Sort, descending: Boolean = sort.descending) {
        _state.update { it.copy(sort = sort, descending = descending) }
        reload()
    }

    fun setFilters(filters: Filters) {
        val s = _state.value
        // Filters belong to the grid: from Suggestions or Genres they open the library's first tab.
        _state.update { it.copy(filters = filters, tab = if (s.isGrid) s.tab else s.tabs.first().first) }
        reload()
    }

    /** Genres tab: a genre's tile lists that genre in the grid. */
    fun openGenre(name: String) = setFilters(Filters(genres = listOf(name)))

    private fun query(s: State = _state.value): ItemsQuery? {
        val view = s.view ?: return null
        val q = s.filters.applyTo(baseQuery(view, s.tab)).sorted(s.sort, s.descending)
        // Whether each title may be deleted (the phone's selection bar offers Delete then).
        return q.copy(fields = q.fields + ItemFields.CAN_DELETE)
    }

    /* ── Picking titles (the phone's selection bar) ── */

    /** Every title of this view, loading the rest of the pages first ("Select all 248"). */
    suspend fun allItems(): List<BaseItemDto> {
        while (_state.value.isGrid && !_state.value.done && !_state.value.pageError) {
            pageJob?.join()
            if (_state.value.done) break
            loadMore(pageSize = 200)
            if (pageJob?.isActive != true) break
            pageJob?.join()
        }
        return _state.value.items
    }

    /** Titles just marked watched show it at once. */
    fun markedPlayed(ids: Set<UUID>) = _state.update { s ->
        s.copy(items = s.items.map { if (it.id in ids) it.copy(userData = it.userData?.copy(played = true, playbackPositionTicks = 0, playedPercentage = 0.0)) else it })
    }

    /** Titles just deleted leave the grid. */
    fun removed(ids: Set<UUID>) = _state.update { s ->
        val left = s.items.filter { it.id !in ids }
        s.copy(items = left, itemsTotal = (s.itemsTotal - (s.items.size - left.size)).coerceAtLeast(0), total = s.total?.let { (it - (s.items.size - left.size)).coerceAtLeast(0) })
    }

    private fun reload() {
        generation++
        pageJob?.cancel()
        _state.update { it.copy(items = emptyList(), itemsTotal = 0, loading = true, done = false, pageError = false) }
        val s = _state.value
        when (s.tab) {
            LibraryTab.Suggestions -> loadSuggestions()
            LibraryTab.Genres -> loadGenres()
            else -> loadMore()
        }
    }

    /** The next page of the grid (60 titles; Random is one shuffled page, as Jellyfin's random order isn't stable). */
    fun loadMore(pageSize: Int = 60) {
        val s = _state.value
        if (!s.isGrid || (s.done && s.items.isNotEmpty()) || pageJob?.isActive == true) return
        val q = query(s) ?: return
        val gen = generation
        pageJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, pageError = false) }
            val random = s.sort == Sort.Random
            val result = runCatching {
                browse.items(if (random) q.copy(startIndex = 0, limit = 120) else q.copy(startIndex = s.items.size, limit = pageSize))
            }
            if (gen != generation) return@launch
            result.onSuccess { r ->
                _state.update { cur ->
                    val seen = cur.items.mapTo(HashSet()) { it.id }
                    val fresh = r.items.filter { it.id !in seen }
                    val items = cur.items + fresh
                    val total = if (random) items.size else r.totalRecordCount
                    cur.copy(items = items, itemsTotal = total, loading = false, done = random || items.size >= total || fresh.isEmpty())
                }
            }.onFailure { _state.update { it.copy(loading = false, pageError = true) } }
        }
    }

    /** Loads pages until [index] is in the grid. */
    suspend fun ensure(index: Int) {
        while (_state.value.items.size <= index && !_state.value.done && !_state.value.pageError) {
            pageJob?.join()
            if (_state.value.items.size > index || _state.value.done) break
            loadMore(pageSize = (index - _state.value.items.size + 60).coerceAtLeast(60))
            pageJob?.join()
        }
    }

    /**
     * The A–Z rail: where the first title filed under [letter] is, by title order. Other sorts switch to Title first.
     * Calls [onIndex] once that title is loaded.
     */
    fun jump(letter: Char, onIndex: (Int) -> Unit) {
        viewModelScope.launch {
            if (_state.value.sort != Sort.Title || _state.value.descending) {
                setSort(Sort.Title, false)
            }
            pageJob?.join()
            val q = query() ?: return@launch
            val index = if (letter == '#') 0 else runCatching {
                browse.items(q.copy(nameLessThan = letter.toString(), startIndex = 0, limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount
            }.getOrDefault(0)
            ensure(index)
            val items = _state.value.items
            if (items.isNotEmpty()) onIndex(index.coerceAtMost(items.lastIndex))
        }
    }

    /** Shuffle: a title from what this tab shows (filters included); shows give one of their episodes. */
    fun shuffle(onPick: (BaseItemDto) -> Unit) {
        val s = _state.value
        val view = s.view ?: return
        viewModelScope.launch {
            val q = if (s.isGrid) query(s)!! else s.filters.applyTo(ItemsQuery(parentId = view.id, includeTypes = playableTypesFor(view.collectionType), isMissing = false))
            val pick = runCatching { browse.random(q) }.getOrNull() ?: return@launch
            val target = if (pick.type == BaseItemKind.SERIES || pick.type == BaseItemKind.BOX_SET) browse.playable(pick) else pick
            target?.let(onPick)
        }
    }

    /** The filters sheet's genres, decades and ratings for this library. */
    fun loadOptions() {
        val view = _state.value.view ?: return
        if (_state.value.options != null) return
        viewModelScope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    api.filterApi.getQueryFiltersLegacy(userId = userId, parentId = view.id, includeItemTypes = typesFor(view.collectionType)).content
                }
            }.getOrNull()
            val decades = r?.years.orEmpty().map { it / 10 * 10 }.distinct().sortedDescending()
            _state.update { it.copy(options = FilterOptions(r?.genres.orEmpty(), decades, r?.officialRatings.orEmpty())) }
        }
    }

    /** How many titles [filters] would show (the sheet's "Show 12 titles"). */
    suspend fun countFor(filters: Filters): Int? {
        val s = _state.value
        val view = s.view ?: return null
        val tab = if (s.isGrid) s.tab else s.tabs.first().first
        return runCatching { browse.items(filters.applyTo(baseQuery(view, tab)).copy(limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount }.getOrNull()
    }

    private fun loadSuggestions() {
        val view = _state.value.view ?: return
        val gen = generation
        val types = typesFor(view.collectionType)
        pageJob = viewModelScope.launch {
            val seeds = runCatching {
                if (view.collectionType == org.jellyfin.sdk.model.api.CollectionType.TVSHOWS) {
                    browse.items(ItemsQuery(parentId = view.id, includeTypes = listOf(BaseItemKind.EPISODE), sortBy = listOf(ItemSortBy.DATE_PLAYED), sortOrder = SortOrder.DESCENDING, filters = listOf(ItemFilter.IS_PLAYED), limit = 30, countTotal = false))
                        .items.mapNotNull { e -> e.seriesId?.let { it to e.seriesName.orEmpty() } }.distinctBy { it.first }.take(2)
                } else {
                    browse.items(ItemsQuery(parentId = view.id, includeTypes = types, sortBy = listOf(ItemSortBy.DATE_PLAYED), sortOrder = SortOrder.DESCENDING, filters = listOf(ItemFilter.IS_PLAYED), limit = 2, countTotal = false))
                        .items.map { it.id to it.name.orEmpty() }
                }
            }.getOrDefault(emptyList())
            val rows = mutableListOf<SuggestionRow>()
            for ((seed, name) in seeds) {
                val similar = runCatching { browse.similar(seed, 16) }.getOrDefault(emptyList())
                if (similar.isNotEmpty()) rows += SuggestionRow(t("Because you watched {title}", "title" to name), items = similar)
            }
            suspend fun row(title: String, sub: String, q: ItemsQuery) {
                val items = runCatching { browse.items(q.copy(countTotal = false)).items }.getOrDefault(emptyList())
                if (items.isNotEmpty()) rows += SuggestionRow(title, sub, items)
            }
            row(t("Top rated"), t("Not watched yet"), ItemsQuery(parentId = view.id, includeTypes = types, filters = listOf(ItemFilter.IS_UNPLAYED), sortBy = listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), sortOrder = SortOrder.DESCENDING, limit = 16))
            row(t("Recently added"), t("Not watched yet"), ItemsQuery(parentId = view.id, includeTypes = types, filters = listOf(ItemFilter.IS_UNPLAYED), sortBy = listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME), sortOrder = SortOrder.DESCENDING, limit = 16))
            row(t("Something different"), t("Picked at random"), ItemsQuery(parentId = view.id, includeTypes = types, sortBy = listOf(ItemSortBy.RANDOM), limit = 16))
            if (gen == generation) _state.update { it.copy(suggestions = rows, loading = false, done = true) }
        }
    }

    private fun loadGenres() {
        val view = _state.value.view ?: return
        val gen = generation
        val types = typesFor(view.collectionType)
        pageJob = viewModelScope.launch {
            val names = runCatching {
                withContext(Dispatchers.IO) {
                    api.genreApi.getGenres(userId = userId, parentId = view.id, includeItemTypes = types, sortBy = listOf(ItemSortBy.SORT_NAME), enableImages = false).content.items
                }.mapNotNull { it.name }
            }.getOrDefault(emptyList())
            if (gen != generation) return@launch
            _state.update { it.copy(genres = names.map { n -> GenreTile(n) }, loading = false, done = true) }
            // Each tile's picture comes from a title in that genre, avoiding one another tile already uses.
            val used = HashSet<UUID>()
            for (name in names) {
                val pick = runCatching {
                    browse.items(ItemsQuery(parentId = view.id, genres = listOf(name), includeTypes = types, sortBy = listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME), sortOrder = SortOrder.DESCENDING, limit = 12, countTotal = false, images = listOf(ImageType.BACKDROP, ImageType.THUMB, ImageType.PRIMARY)))
                        .items.let { list -> list.filter { !it.backdropImageTags.isNullOrEmpty() || it.imageTags?.containsKey(ImageType.THUMB) == true }.let { art -> art.firstOrNull { it.id !in used } ?: art.firstOrNull() ?: list.firstOrNull() } }
                }.getOrNull() ?: continue
                used += pick.id
                if (gen != generation) return@launch
                _state.update { s -> s.copy(genres = s.genres?.map { if (it.name == name) it.copy(art = pick) else it }) }
            }
        }
    }
}
