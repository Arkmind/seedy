package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.ItemsQuery
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.personApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/** Favorites (phone tab and TV place): what the person hearted, grouped as on the web (movies, shows, episodes…). */
class FavoritesViewModel(private val session: UserSession) : ViewModel() {
    private val browse = Browse(session)

    /** A group's label is English, for t(). */
    enum class Group(val label: String, val types: List<BaseItemKind>) {
        Movies("Movies", listOf(BaseItemKind.MOVIE)),
        Shows("Shows", listOf(BaseItemKind.SERIES)),
        Episodes("Episodes", listOf(BaseItemKind.EPISODE)),
        Collections("Collections", listOf(BaseItemKind.BOX_SET)),
        Albums("Albums", listOf(BaseItemKind.MUSIC_ALBUM)),
        People("People", emptyList()),
    }

    data class State(
        val loading: Boolean = true,
        val groups: Map<Group, List<BaseItemDto>> = emptyMap(),
        /** Null: every group. */
        val only: Group? = null,
    ) {
        val present: List<Pair<Group, List<BaseItemDto>>> get() = Group.entries.mapNotNull { g -> groups[g]?.takeIf { it.isNotEmpty() }?.let { g to it } }
        val shown: List<Pair<Group, List<BaseItemDto>>> get() = present.filter { only == null || it.first == only }.ifEmpty { present }
        val total: Int get() = present.sumOf { it.second.size }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val lists = Group.entries.map { g ->
                async {
                    g to runCatching {
                        if (g == Group.People) {
                            withContext(Dispatchers.IO) {
                                session.api.personApi.getPersons(userId = session.userId, isFavorite = true, limit = 200, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1).content.items
                            }
                        } else {
                            browse.items(ItemsQuery(includeTypes = g.types, isFavorite = true, recursive = true, sortBy = listOf(ItemSortBy.SORT_NAME), sortOrder = SortOrder.ASCENDING, limit = 200)).items
                        }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll()
            _state.update { it.copy(loading = false, groups = lists.toMap()) }
        }
    }

    fun show(group: Group?) = _state.update { it.copy(only = group) }
}
