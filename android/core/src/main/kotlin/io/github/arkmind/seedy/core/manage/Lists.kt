package io.github.arkmind.seedy.core.manage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.listing
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.ItemsQuery
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID

/*
 * Add to playlist / Add to collection (board DetailsMore's submenu) and New playlist (board PlaylistNew). A playlist
 * says how many items it holds and who sees it; one that already holds the title says "Added".
 */

/** What's being added: its ids, its name and kind (for "Starts with …"). [kind] is Jellyfin's type ("Series"…). */
data class AddTarget(val ids: List<String>, val name: String, val kind: String? = null, val art: String? = null)

enum class ListKind { Playlist, Collection }

data class ListRow(val id: String, val name: String, val sub: String, val added: Boolean, val busy: Boolean = false)

data class AddToState(
    val kind: ListKind = ListKind.Playlist,
    /** Null while loading. */
    val rows: List<ListRow>? = null,
    val failed: Boolean = false,
    val query: String = "",
    /** Collections: may this person make one? */
    val canCreate: Boolean = true,
    val message: String? = null,
) {
    val shown: List<ListRow>? get() = rows?.let { r -> if (query.isBlank()) r else r.filter { it.name.contains(query.trim(), ignoreCase = true) } }
}

interface AddToActions {
    fun search(text: String) {}
    fun add(id: String) {}
    /** A new collection with this name (playlists have their own sheet). */
    fun create(name: String) {}
    fun retry() {}
    fun messageShown() {}
}

class AddToViewModel(session: UserSession, private val kind: ListKind, private val target: AddTarget) : ViewModel(), AddToActions {
    private val api = ManageApi(session)
    private val _state = MutableStateFlow(AddToState(kind, canCreate = kind == ListKind.Playlist || api.canManageCollections))
    val state: StateFlow<AddToState> = _state.asStateFlow()
    private val targets = target.ids.map(::normId).toSet()

    init {
        load()
    }

    private fun load() {
        _state.update { it.copy(failed = false) }
        viewModelScope.launch {
            val result = runCatching { if (kind == ListKind.Playlist) playlists() else collections() }
            result.onSuccess { rows -> _state.update { it.copy(rows = rows) } }
                .onFailure { e -> if (e is CancellationException) throw e; _state.update { it.copy(failed = true) } }
        }
    }

    private suspend fun playlists(): List<ListRow> {
        val lists = api.playlists()
        val people = runCatching { api.shareWith() }.getOrNull().orEmpty().associate { normId(it.id) to it.name }
        return kotlinx.coroutines.coroutineScope {
            lists.map { p ->
                async {
                    val info = runCatching { api.playlist(p.id) }.getOrNull()
                    val held = info?.itemIds.orEmpty().map(::normId).toSet()
                    // A series in a playlist is its episodes: those carry the series' id.
                    val added = targets.all { it in held } || (held.isNotEmpty() && run {
                        val refs = runCatching { api.playlistItems(p.id) }.getOrNull().orEmpty()
                        targets.all { t -> t in held || refs.any { normId(it.seriesId) == t || normId(it.seasonId) == t } }
                    })
                    ListRow(p.id, p.name, playlistSub(p.childCount ?: info?.itemIds?.size ?: 0, info, people), added)
                }
            }.awaitAll()
        }
    }

    private suspend fun collections(): List<ListRow> {
        val lists = api.collections()
        return kotlinx.coroutines.coroutineScope {
            lists.map { c ->
                async {
                    val held = runCatching { api.collectionItems(c.id) }.getOrNull().orEmpty().map(::normId).toSet()
                    ListRow(c.id, c.name, tn(c.childCount ?: held.size, "{n} item", "{n} items"), targets.all { it in held })
                }
            }.awaitAll()
        }
    }

    override fun search(text: String) = _state.update { it.copy(query = text) }

    override fun retry() = load()

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun add(id: String) {
        val row = _state.value.rows?.firstOrNull { it.id == id } ?: return
        if (row.added || row.busy) return
        mark(id) { it.copy(busy = true) }
        viewModelScope.launch {
            val result = runCatching { if (kind == ListKind.Playlist) api.addToPlaylist(id, target.ids) else api.addToCollection(id, target.ids) }
            result.onSuccess {
                mark(id) { it.copy(busy = false, added = true) }
                _state.update { it.copy(message = t("Added to {name}", "name" to row.name)) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                mark(id) { it.copy(busy = false) }
                _state.update { it.copy(message = t("Couldn’t add it. {error}", "error" to errorText(e))) }
            }
        }
    }

    override fun create(name: String) {
        if (name.isBlank() || kind != ListKind.Collection) return
        viewModelScope.launch {
            val result = runCatching { api.createCollection(name.trim(), target.ids) }
            result.onSuccess { newId ->
                _state.update { s -> s.copy(rows = listOf(ListRow(newId, name.trim(), tn(target.ids.size, "{n} item", "{n} items"), true)) + s.rows.orEmpty(), message = t("Added to {name}", "name" to name.trim())) }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(message = t("Couldn’t make the collection. {error}", "error" to errorText(e))) }
            }
        }
    }

    private fun mark(id: String, change: (ListRow) -> ListRow) =
        _state.update { s -> s.copy(rows = s.rows?.map { if (it.id == id) change(it) else it }) }

    companion object {
        /** "6 items · only you", "4 items · shared with Léa", "9 items · everyone on the server". */
        fun playlistSub(items: Int, info: PlaylistInfo?, people: Map<String, String>): String {
            val count = tn(items, "{n} item", "{n} items")
            val who = when {
                info == null -> null
                info.openAccess -> t("everyone on the server")
                info.shares.isEmpty() -> t("only you")
                else -> {
                    val names = info.shares.mapNotNull { people[normId(it.userId)] }
                    if (names.isEmpty() || names.size > 3) tn(info.shares.size, "shared with {n} person", "shared with {n} people")
                    else t("shared with {names}", "names" to listing(names))
                }
            }
            return listOfNotNull(count, who).joinToString(" · ")
        }
    }
}

/* ── New playlist ── */

enum class Audience { OnlyMe, People, Everyone }

data class SharedWith(val person: SharePerson, val canEdit: Boolean)

data class NewPlaylistState(
    val name: String = "",
    /** "Starts with Northbound". */
    val startsWith: String = "",
    /** "20 episodes on the server, in order". */
    val startsSub: String = "",
    val art: String? = null,
    val audience: Audience = Audience.OnlyMe,
    /** Null without the plugin: then it's only you or everyone. */
    val everyone: List<SharePerson>? = null,
    val people: List<SharedWith> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
) {
    val canPick: Boolean get() = everyone != null
    val canCreate: Boolean get() = name.isNotBlank() && !busy && (audience != Audience.People || people.isNotEmpty())
    /** People not picked yet. */
    val others: List<SharePerson> get() = everyone.orEmpty().filter { p -> people.none { it.person.id == p.id } }
}

interface NewPlaylistActions {
    fun setName(text: String) {}
    fun setAudience(a: Audience) {}
    fun addPerson(id: String) {}
    fun removePerson(id: String) {}
    fun setCanEdit(id: String, canEdit: Boolean) {}
    /** Creates it; [onDone] gets what to say. */
    fun create(onDone: (String) -> Unit) {}
}

class NewPlaylistViewModel(session: UserSession, private val target: AddTarget) : ViewModel(), NewPlaylistActions {
    private val api = ManageApi(session)
    private val _state = MutableStateFlow(NewPlaylistState(startsWith = t("Starts with {name}", "name" to target.name), art = target.art))
    val state: StateFlow<NewPlaylistState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val people = runCatching { api.shareWith() }.getOrNull()
            _state.update { it.copy(everyone = people) }
        }
        viewModelScope.launch {
            val sub = when {
                target.ids.size > 1 -> tn(target.ids.size, "{n} title, in the order you picked", "{n} titles, in the order you picked")
                target.kind == "Series" || target.kind == "Season" -> runCatching { api.episodeCount(target.ids.first()) }.getOrNull()
                    ?.let { tn(it, "{n} episode on the server, in order", "{n} episodes on the server, in order") }.orEmpty()
                target.kind == "Movie" -> t("Movie")
                target.kind == "Episode" -> t("Episode")
                else -> ""
            }
            _state.update { it.copy(startsSub = sub) }
        }
    }

    override fun setName(text: String) = _state.update { it.copy(name = text, error = null) }

    override fun setAudience(a: Audience) = _state.update { it.copy(audience = a) }

    override fun addPerson(id: String) = _state.update { s ->
        val p = s.everyone?.firstOrNull { it.id == id } ?: return@update s
        if (s.people.any { it.person.id == id }) s else s.copy(people = s.people + SharedWith(p, canEdit = false), audience = Audience.People)
    }

    override fun removePerson(id: String) = _state.update { s -> s.copy(people = s.people.filter { it.person.id != id }) }

    override fun setCanEdit(id: String, canEdit: Boolean) =
        _state.update { s -> s.copy(people = s.people.map { if (it.person.id == id) it.copy(canEdit = canEdit) else it }) }

    override fun create(onDone: (String) -> Unit) {
        val s = _state.value
        if (!s.canCreate) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val people = if (s.audience == Audience.People) s.people.map { it.person.id to it.canEdit } else emptyList()
            val result = runCatching { api.createPlaylist(s.name.trim(), target.ids, people, s.audience == Audience.Everyone) }
            result.onSuccess {
                _state.update { it.copy(busy = false) }
                onDone(t("Made {name}", "name" to s.name.trim()))
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(busy = false, error = t("Couldn’t make the playlist. {error}", "error" to errorText(e))) }
            }
        }
    }
}

/* ── One-off actions: watched, refresh ── */

interface BulkActions {
    fun markWatched(ids: List<String>, onDone: () -> Unit = {}) {}
    fun refresh(ids: List<String>) {}
    fun messageShown() {}
}

data class BulkState(
    val busy: Boolean = false,
    val message: String? = null,
    /** The menu's Delete line ("2 seasons · 252 GB"), once the preview is in. */
    val deleteSub: String? = null,
)

class BulkViewModel(private val session: UserSession) : ViewModel(), BulkActions {
    private val api = ManageApi(session)
    private val _state = MutableStateFlow(BulkState())
    val state: StateFlow<BulkState> = _state.asStateFlow()

    /** Fills [BulkState.deleteSub] for the menu (Seedy's preview; nothing without the plugin). */
    fun loadDeleteSub(ids: List<String>) {
        if (_state.value.deleteSub != null) return
        viewModelScope.launch {
            val p = runCatching { api.deletePreview(ids) }.getOrNull() ?: return@launch
            _state.update { it.copy(deleteSub = DeleteViewModel.describe(p).sub) }
        }
    }

    /** "Play from the first episode": the series' first episode on the server. */
    fun firstEpisode(seriesId: UUID, onPlay: (BaseItemDto) -> Unit) {
        viewModelScope.launch {
            val q = ItemsQuery(
                parentId = seriesId, includeTypes = listOf(BaseItemKind.EPISODE), isMissing = false, limit = 1,
                sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER), countTotal = false,
            )
            runCatching { Browse(session).items(q).items.firstOrNull() }.getOrNull()?.let(onPlay)
        }
    }

    override fun messageShown() = _state.update { it.copy(message = null) }

    /** Shows [text] in the page's toast (a sheet's outcome). */
    fun say(text: String) = _state.update { it.copy(message = text) }

    override fun markWatched(ids: List<String>, onDone: () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val failed = ids.count { id -> runCatching { api.markPlayed(id) }.isFailure }
            _state.update {
                it.copy(busy = false, message = if (failed > 0) t("Couldn’t mark {n} of them", "n" to failed) else tn(ids.size, "Marked {n} title watched", "Marked {n} titles watched"))
            }
            onDone()
        }
    }

    override fun refresh(ids: List<String>) {
        viewModelScope.launch {
            val failed = ids.count { id -> runCatching { api.refresh(id) }.isFailure }
            _state.update {
                it.copy(message = if (failed > 0) t("Couldn’t refresh {n} of them", "n" to failed) else tn(ids.size, "Refreshing the metadata", "Refreshing the metadata of {n} titles"))
            }
        }
    }
}
