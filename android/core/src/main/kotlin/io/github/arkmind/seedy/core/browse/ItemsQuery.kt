package io.github.arkmind.seedy.core.browse

import io.github.arkmind.seedy.core.session.UserSession
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/** The /Items query the browse screens build (the web's ItemsQuery): what to list, how to sort it, which page. */
data class ItemsQuery(
    val parentId: UUID? = null,
    val includeTypes: List<BaseItemKind> = emptyList(),
    val excludeTypes: List<BaseItemKind> = emptyList(),
    val recursive: Boolean = true,
    val sortBy: List<ItemSortBy> = emptyList(),
    val sortOrder: SortOrder? = null,
    val startIndex: Int? = null,
    val limit: Int? = null,
    val fields: List<ItemFields> = Browse.CardFields,
    val filters: List<ItemFilter> = emptyList(),
    val genres: List<String> = emptyList(),
    val years: List<Int> = emptyList(),
    val officialRatings: List<String> = emptyList(),
    val personIds: List<UUID> = emptyList(),
    val isFavorite: Boolean? = null,
    val isHd: Boolean? = null,
    val is4k: Boolean? = null,
    val hasSubtitles: Boolean? = null,
    val isMissing: Boolean? = null,
    val searchTerm: String? = null,
    val nameLessThan: String? = null,
    val countTotal: Boolean = true,
    val images: List<ImageType> = Browse.AllImages,
) {
    internal suspend fun run(session: UserSession): BaseItemDtoQueryResult = session.api.libraryApi.getItems(
        userId = session.userId,
        parentId = parentId,
        includeItemTypes = includeTypes,
        excludeItemTypes = excludeTypes,
        recursive = recursive,
        sortBy = sortBy,
        sortOrder = sortOrder?.let { o -> List(sortBy.size.coerceAtLeast(1)) { o } }.orEmpty(),
        startIndex = startIndex,
        limit = limit,
        fields = fields,
        filters = filters,
        genres = genres,
        years = years,
        officialRatings = officialRatings,
        personIds = personIds,
        isFavorite = isFavorite,
        isHd = isHd,
        is4k = is4k,
        hasSubtitles = hasSubtitles,
        isMissing = isMissing,
        searchTerm = searchTerm,
        nameLessThan = nameLessThan,
        enableTotalRecordCount = countTotal,
        enableImageTypes = images,
        imageTypeLimit = 1,
        enableUserData = true,
    ).content
}
