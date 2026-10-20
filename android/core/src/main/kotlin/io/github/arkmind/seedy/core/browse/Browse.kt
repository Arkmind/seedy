package io.github.arkmind.seedy.core.browse

import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * Jellyfin calls the browse screens share (details, libraries, favorites, search), on the IO dispatcher. Queries ask
 * for the fields the cards need ([CardFields]); details pages ask for more ([DetailFields]).
 */
class Browse(private val session: UserSession) {
    private val api get() = session.api
    private val userId: UUID get() = session.userId

    /** One title with every field (people, studios, trailers, provider ids…): GET /Items/{id} returns them all. */
    suspend fun item(id: UUID): BaseItemDto = io { api.libraryApi.getItem(itemId = id, userId = userId).content }

    /** A title with its media streams, for the "4K · HDR10 · Atmos · CC" badges. */
    suspend fun withStreams(id: UUID): BaseItemDto? = io {
        api.libraryApi.getItems(
            userId = userId, ids = listOf(id), fields = listOf(ItemFields.MEDIA_SOURCES, ItemFields.MEDIA_STREAMS), enableUserData = false,
            enableImageTypes = emptyList(), enableTotalRecordCount = false,
        ).content.items.firstOrNull()
    }

    suspend fun libraries(): List<BaseItemDto> = io { api.userViewApi.getUserViews(userId = userId, includeExternalContent = true).content.items }

    suspend fun items(q: ItemsQuery): BaseItemDtoQueryResult = io { q.run(session) }

    suspend fun seasons(seriesId: UUID): List<BaseItemDto> = io {
        api.showApi.getSeasons(
            seriesId = seriesId, userId = userId, enableUserData = true,
            fields = listOf(ItemFields.CHILD_COUNT, ItemFields.RECURSIVE_ITEM_COUNT, ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO),
        ).content.items
    }

    suspend fun episodes(seriesId: UUID, seasonId: UUID?, limit: Int? = null, isMissing: Boolean? = null): List<BaseItemDto> = io {
        api.showApi.getEpisodes(
            seriesId = seriesId, userId = userId, seasonId = seasonId, limit = limit, isMissing = isMissing, enableUserData = true,
            fields = listOf(ItemFields.OVERVIEW, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.DATE_CREATED),
        ).content.items
    }

    suspend fun resume(parentId: UUID): BaseItemDto? = io {
        api.libraryApi.getResumeItems(
            userId = userId, parentId = parentId, limit = 1, mediaTypes = listOf(MediaType.VIDEO), enableUserData = true,
            fields = CardFields, enableTotalRecordCount = false,
        ).content.items.firstOrNull()
    }

    suspend fun nextUp(seriesId: UUID): BaseItemDto? = io {
        api.showApi.getNextUp(
            userId = userId, seriesId = seriesId, limit = 1, enableUserData = true, enableResumable = false, enableRewatching = false,
            fields = CardFields, enableTotalRecordCount = false,
        ).content.items.firstOrNull()
    }

    /** What Play starts on a series: where the person stopped, their next episode, or the first one on the server. */
    suspend fun seriesTarget(seriesId: UUID): Target? {
        runCatching { resume(seriesId) }.getOrNull()?.let { return Target(it, resume = true) }
        runCatching { nextUp(seriesId) }.getOrNull()?.let { return Target(it, resume = false) }
        val eps = runCatching { episodes(seriesId, null, limit = 40, isMissing = false) }.getOrDefault(emptyList())
        val first = eps.firstOrNull { (it.parentIndexNumber ?: 1) > 0 && it.locationType != org.jellyfin.sdk.model.api.LocationType.VIRTUAL } ?: eps.firstOrNull()
        return first?.let { Target(it, resume = false) }
    }

    /** What Play means for any title: a show's episode, a collection's first title, the title itself. */
    suspend fun playable(item: BaseItemDto): BaseItemDto? = when (item.type) {
        BaseItemKind.SERIES -> seriesTarget(item.id)?.item
        BaseItemKind.SEASON -> runCatching { resume(item.id) }.getOrNull()
            ?: runCatching { episodes(item.seriesId ?: item.id, item.id, limit = 1, isMissing = false) }.getOrNull()?.firstOrNull()
        BaseItemKind.BOX_SET, BaseItemKind.PLAYLIST, BaseItemKind.FOLDER -> runCatching {
            items(
                ItemsQuery(
                    parentId = item.id, limit = 1, recursive = item.type != BaseItemKind.PLAYLIST,
                    sortBy = if (item.type == BaseItemKind.BOX_SET) listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME) else if (item.type == BaseItemKind.PLAYLIST) emptyList() else listOf(ItemSortBy.SORT_NAME),
                    excludeTypes = listOf(BaseItemKind.FOLDER), countTotal = false,
                ),
            ).items.firstOrNull()
        }.getOrNull()
        else -> item
    }

    /** One title picked at random among what [q] lists. */
    suspend fun random(q: ItemsQuery): BaseItemDto? = items(q.copy(sortBy = listOf(ItemSortBy.RANDOM), sortOrder = SortOrder.ASCENDING, startIndex = null, limit = 1, countTotal = false)).items.firstOrNull()

    suspend fun similar(id: UUID, limit: Int = 12): List<BaseItemDto> = io {
        api.libraryApi.getSimilarItems(itemId = id, userId = userId, limit = limit, fields = CardFields).content.items
    }

    suspend fun localTrailers(id: UUID): List<BaseItemDto> = io { api.libraryApi.getLocalTrailers(itemId = id, userId = userId).content }

    /** Seedy's trailer for a title (local file, or a YouTube video), or null; plain Jellyfin answers with nothing. */
    suspend fun seedyTrailer(id: UUID): TrailerInfo? = if (session.seedy == null) null else io {
        runCatching { api.get<TrailerInfo>("/Seedy/Items/{id}/Trailer", mapOf("id" to id)).content }.getOrNull()
    }

    suspend fun setFavorite(id: UUID, on: Boolean) = io {
        if (on) api.userDataApi.markFavoriteItem(itemId = id, userId = userId) else api.userDataApi.unmarkFavoriteItem(itemId = id, userId = userId)
        Unit
    }

    suspend fun setPlayed(id: UUID, on: Boolean) = io {
        if (on) api.userDataApi.markPlayedItem(itemId = id, userId = userId) else api.userDataApi.markUnplayedItem(itemId = id, userId = userId)
        Unit
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    data class Target(val item: BaseItemDto, val resume: Boolean)

    companion object {
        val CardFields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES, ItemFields.CHILD_COUNT, ItemFields.RECURSIVE_ITEM_COUNT,
            ItemFields.OVERVIEW, ItemFields.DATE_CREATED, ItemFields.SORT_NAME,
        )
        val DetailFields = listOf(
            ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.PEOPLE, ItemFields.STUDIOS, ItemFields.TAGLINES, ItemFields.MEDIA_SOURCES,
            ItemFields.MEDIA_STREAMS, ItemFields.REMOTE_TRAILERS, ItemFields.PROVIDER_IDS, ItemFields.CHILD_COUNT,
            ItemFields.RECURSIVE_ITEM_COUNT, ItemFields.DATE_CREATED, ItemFields.ORIGINAL_TITLE, ItemFields.LOCAL_TRAILER_COUNT,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.SORT_NAME, ItemFields.PRODUCTION_LOCATIONS,
        )
        val AllImages = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)
    }
}

/** GET /Seedy/Items/{id}/Trailer: a local trailer's item id, or a YouTube video's key. */
@Serializable
data class TrailerInfo(val source: String = "", val itemId: String? = null, val key: String? = null, val name: String? = null)
