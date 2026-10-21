package io.github.arkmind.seedy.core.admin.seedy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.format.Images
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/*
 * Seedy · Home screen (web/src/screens/admin/HomeScreen.tsx, board AdminHome): the rows new profiles start with and
 * how Because you watched, Top 10, genre rows and Featured pick their titles. Changes wait in a draft and save
 * together.
 */

/** A row kind's name and line, in English for t(); [seerr] rows need Seerr; [count] is the rows it may have. */
data class RowInfo(val name: String, val help: String, val seerr: Boolean = false, val count: IntRange? = null)

val HomeRowInfo = mapOf(
    "featured" to RowInfo("Featured", "Always first"),
    "continue" to RowInfo("Continue watching", "Always second when it has anything"),
    "nextup" to RowInfo("Next up", "Next unwatched episode of shows in progress"),
    "top10" to RowInfo("Top 10 this week", "Most watched on this server"),
    "because" to RowInfo("Because you watched", "One row per recently watched title", count = 1..10),
    "genres" to RowInfo("Genre rows", "Genres each person watches most", count = 1..8),
    "mymedia" to RowInfo("My media", "One tile per library"),
    "latest" to RowInfo("Latest in each library", "Newest additions"),
    "discover" to RowInfo("Discover", "Titles to request", seerr = true),
    "upcomingMovies" to RowInfo("Upcoming movies", "Requested movies not out yet", seerr = true),
    "upcomingShows" to RowInfo("Upcoming shows", "Next episodes of shows in the library"),
    "collections" to RowInfo("Collections", "One row per collection"),
)

/** Fields that differ between two home settings; reordering the rows counts once. */
fun countChanges(a: HomeConfig, b: HomeConfig): Int {
    var n = 0
    if (a.rows.map { it.kind } != b.rows.map { it.kind }) n++
    for (r in a.rows) {
        val o = b.rows.firstOrNull { it.kind == r.kind }
        if (o == null) { n++; continue }
        if (r.enabled != o.enabled) n++
        if ((r.count ?: 0) != (o.count ?: 0)) n++
    }
    val pairs = listOf(
        a.adaptOrder to b.adaptOrder,
        a.because.watchedWhen to b.because.watchedWhen,
        a.because.lookBackDays to b.because.lookBackDays,
        a.because.minTitles to b.because.minTitles,
        a.top10.periodDays to b.top10.periodDays,
        a.top10.minProfiles to b.top10.minProfiles,
        a.genres.windowDays to b.genres.windowDays,
        a.genres.starting to b.genres.starting,
        a.featured.mode to b.featured.mode,
        a.featured.picks to b.featured.picks,
    )
    return n + pairs.count { it.first != it.second }
}

/** A Featured pick: its title, "2024 · Movie" and a 16:9 picture. */
data class PickItem(val id: String, val title: String, val meta: String, val art: String?)

data class HomeAdminState(
    val saved: HomeConfig? = null,
    val draft: HomeConfig? = null,
    val loadError: String? = null,
    val saving: Boolean = false,
    /** Featured picks by id; null when the title is gone, missing while it loads. */
    val picks: Map<String, PickItem?> = emptyMap(),
    val message: String? = null,
) {
    val home: HomeConfig? get() = draft ?: saved
    val changes: Int get() = if (draft != null && saved != null) countChanges(draft, saved) else 0
}

interface HomeAdminActions {
    fun edit(fn: (HomeConfig) -> HomeConfig) {}
    fun discard() {}
    fun save() {}
    fun retry() {}
    fun messageShown() {}
    /** A title picked from the search: shown at once, without waiting for the list to load it. */
    fun remember(item: PickItem) {}
    /** The library's genres, or null when they couldn't be loaded. */
    suspend fun genres(): List<String>? = emptyList()
    /** Movies and shows matching [term], or the newest when it's empty; null when the search failed. */
    suspend fun titles(term: String): List<PickItem>? = emptyList()
}

class HomeAdminViewModel(private val session: UserSession) : ViewModel(), HomeAdminActions {
    private val http = AdminHttp(session.api)
    private val images = Images(session.api)
    private val store = SeedyConfigStore(http, viewModelScope)
    private val extra = MutableStateFlow(HomeAdminState())
    private var genreCache: List<String>? = null

    val state: StateFlow<HomeAdminState> = combine(store.config, store.loadError, extra) { c, err, x ->
        x.copy(saved = c?.home, loadError = err)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, extra.value)

    fun load() {
        viewModelScope.launch {
            store.load()
            loadPicks()
        }
    }

    override fun retry() = load()

    private fun pickOf(it: BaseItemDto) = PickItem(
        it.id.toString().replace("-", ""), it.name.orEmpty(),
        join(it.yearRange, if (it.type == BaseItemKind.SERIES) t("Show") else t("Movie")),
        images.landscape(it, 240, episodeImages = false),
    )

    private fun loadPicks() {
        val ids = (state.value.home?.featured?.picks.orEmpty()).filter { it !in extra.value.picks }
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val uuids = ids.mapNotNull { uuidOf(it) }
            runCatching {
                session.api.libraryApi.getItems(userId = session.userId, ids = uuids, fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES), limit = uuids.size).content.items
            }.onSuccess { items ->
                val found = items.map(::pickOf).associateBy { it.id }
                extra.update { s -> s.copy(picks = s.picks + ids.associateWith { found[it.replace("-", "")] }) }
            }
        }
    }

    override fun edit(fn: (HomeConfig) -> HomeConfig) {
        val s = state.value
        val cur = s.home ?: return
        val next = fn(cur)
        extra.update { it.copy(draft = if (s.saved != null && countChanges(next, s.saved) == 0) null else next) }
        loadPicks()
    }

    override fun discard() = extra.update { it.copy(draft = null) }

    override fun remember(item: PickItem) = extra.update { it.copy(picks = it.picks + (item.id to item)) }

    override fun save() {
        val draft = extra.value.draft ?: return
        viewModelScope.launch {
            extra.update { it.copy(saving = true) }
            try {
                store.commit { it.copy(home = draft) }
                extra.update { it.copy(draft = null, message = t("Home screen saved")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                extra.update { it.copy(message = t("Couldn’t save.") + " " + errorText(e)) }
            } finally {
                extra.update { it.copy(saving = false) }
            }
        }
    }

    override fun messageShown() = extra.update { it.copy(message = null) }

    override suspend fun genres(): List<String>? {
        genreCache?.let { return it }
        return try {
            session.api.genreApi.getGenres(userId = session.userId, sortBy = listOf(ItemSortBy.SORT_NAME), enableImages = false).content.items
                .mapNotNull { it.name?.takeIf(String::isNotEmpty) }.also { genreCache = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun titles(term: String): List<PickItem>? {
        val q = term.trim()
        return try {
            val types = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES)
            val fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES)
            val items = if (q.isNotEmpty()) session.api.libraryApi.getItems(userId = session.userId, searchTerm = q, includeItemTypes = types, recursive = true, limit = 20, fields = fields).content.items
            else session.api.libraryApi.getItems(userId = session.userId, includeItemTypes = types, recursive = true, sortBy = listOf(ItemSortBy.DATE_CREATED), sortOrder = listOf(SortOrder.DESCENDING), limit = 20, fields = fields).content.items
            items.map(::pickOf)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
