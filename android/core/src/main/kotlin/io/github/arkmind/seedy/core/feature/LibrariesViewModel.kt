package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.audio.AudioApi
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.ItemsQuery
import io.github.arkmind.seedy.core.browse.countLabel
import io.github.arkmind.seedy.core.browse.typesFor
import io.github.arkmind.seedy.core.format.daysFromToday
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/** The Libraries tab (board "Android · Libraries"): each library with how much it holds and a picture from inside it. */
class LibrariesViewModel(session: UserSession) : ViewModel() {
    private val browse = Browse(session)
    private val audio = AudioApi(session)

    data class Tile(
        val view: BaseItemDto,
        /** "248 movies", "61 shows · 4 new episodes"; empty while counting. */
        val count: String = "",
        /** A title from inside the library whose backdrop stands for it (Jellyfin's own library art carries the name). */
        val art: BaseItemDto? = null,
    )

    data class State(val loading: Boolean = true, val tiles: List<Tile> = emptyList())

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val views = runCatching { browse.libraries() }.getOrDefault(emptyList())
            _state.update { State(loading = false, tiles = views.map { Tile(it) }) }
            for (view in views) {
                launch { count(view) }
                launch { art(view) }
            }
        }
    }

    private suspend fun count(view: BaseItemDto) {
        val type = view.collectionType
        val types = typesFor(type)
        val n = runCatching {
            browse.items(ItemsQuery(parentId = if (type == CollectionType.LIVETV) null else view.id, includeTypes = types, limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount
        }.getOrNull() ?: return
        var label = countLabel(type, n)
        if (type == CollectionType.MUSIC) {
            // "186 albums · 2,140 songs".
            val songs = runCatching { browse.items(ItemsQuery(parentId = view.id, includeTypes = listOf(BaseItemKind.AUDIO), limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount }.getOrNull()
            if (songs != null && songs > 0) label = join(label, tn(songs, "{n} song", "{n} songs"))
        }
        if (type == CollectionType.BOOKS) {
            // "412 books · 96 audiobooks": an audiobook in several files counts once.
            label = runCatching {
                val books = browse.items(ItemsQuery(parentId = view.id, includeTypes = listOf(BaseItemKind.BOOK), limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount
                val files = browse.items(ItemsQuery(parentId = view.id, includeTypes = listOf(BaseItemKind.AUDIO_BOOK), limit = 0, images = emptyList(), fields = emptyList())).totalRecordCount
                val grouped = if (files > 0) audio.audiobooks(view.id) else emptyList()
                val audiobooks = files - grouped.sumOf { it.parts.size } + grouped.size
                join(tn(books, "{n} book", "{n} books"), if (audiobooks > 0) tn(audiobooks, "{n} audiobook", "{n} audiobooks") else null)
            }.getOrDefault(label)
        }
        if (type == CollectionType.TVSHOWS) {
            // "· 4 new episodes": unwatched episodes added this week.
            val fresh = runCatching {
                browse.items(
                    ItemsQuery(
                        parentId = view.id, includeTypes = listOf(BaseItemKind.EPISODE), filters = listOf(ItemFilter.IS_UNPLAYED),
                        sortBy = listOf(ItemSortBy.DATE_CREATED), sortOrder = SortOrder.DESCENDING, limit = 50, countTotal = false, images = emptyList(),
                        fields = listOf(org.jellyfin.sdk.model.api.ItemFields.DATE_CREATED),
                    ),
                ).items.count { e -> e.dateCreated?.let { daysFromToday(it.toLocalDate()) >= -7 } == true }
            }.getOrDefault(0)
            if (fresh > 0) label = join(label, tn(fresh, "{n} new episode", "{n} new episodes"))
        }
        update(view.id) { it.copy(count = label) }
    }

    private suspend fun art(view: BaseItemDto) {
        val types = when (view.collectionType) {
            CollectionType.TVSHOWS -> listOf(BaseItemKind.SERIES)
            CollectionType.MOVIES -> listOf(BaseItemKind.MOVIE)
            CollectionType.MUSIC -> listOf(BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST)
            CollectionType.BOOKS -> listOf(BaseItemKind.BOOK, BaseItemKind.AUDIO_BOOK)
            CollectionType.LIVETV -> return
            else -> emptyList()
        }
        // Albums and books seldom have backdrops: their covers stand in.
        val covers = view.collectionType == CollectionType.MUSIC || view.collectionType == CollectionType.BOOKS
        val pick = runCatching {
            browse.items(
                ItemsQuery(
                    parentId = view.id, includeTypes = types, sortBy = listOf(ItemSortBy.RANDOM), limit = 12, countTotal = false,
                    images = listOf(ImageType.BACKDROP, ImageType.PRIMARY), fields = emptyList(),
                ),
            ).items.let { items ->
                items.firstOrNull { !it.backdropImageTags.isNullOrEmpty() } ?: if (covers) items.firstOrNull { it.imageTags?.containsKey(ImageType.PRIMARY) == true } else null
            }
        }.getOrNull() ?: return
        update(view.id) { it.copy(art = pick) }
    }

    private fun update(id: UUID, change: (Tile) -> Tile) = _state.update { s -> s.copy(tiles = s.tiles.map { if (it.view.id == id) change(it) else it }) }
}
