package io.github.arkmind.seedy.core.browse

import io.github.arkmind.seedy.core.session.UserSession
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import java.util.UUID

/**
 * Continue watching, most recent first. The Seedy plugin's list leaves out the episodes a person moved past (a later
 * episode of the show played since) and marks anything left in its credits watched; without the plugin, or with one
 * from before that list, it's Jellyfin's resume list. A show or season as [parentId] gives where it stands.
 */
suspend fun UserSession.continueWatching(limit: Int, parentId: UUID? = null, fields: List<ItemFields> = emptyList()): List<BaseItemDto> {
    seedyApi?.let { seedy ->
        runCatching { seedy.continueWatching(limit, parentId, fields) }.getOrNull()?.let { return it }
    }
    return api.libraryApi.getResumeItems(
        userId = userId, parentId = parentId, limit = limit, mediaTypes = listOf(MediaType.VIDEO), enableUserData = true,
        imageTypeLimit = 1, enableImageTypes = listOf(ImageType.BACKDROP, ImageType.LOGO, ImageType.THUMB, ImageType.PRIMARY),
        fields = fields, enableTotalRecordCount = false,
    ).content.items
}

/**
 * Takes a title out of Continue watching without marking it watched: its resume point goes, so it starts over. The
 * plugin also clears the show's other episodes in progress (hidden behind this one, they'd take its place).
 */
suspend fun UserSession.clearResume(itemId: UUID) {
    seedyApi?.let { seedy ->
        if (runCatching { seedy.removeFromContinue(itemId) }.isSuccess) return
    }
    api.userDataApi.updateItemUserData(itemId, userId, UpdateUserItemDataDto(playbackPositionTicks = 0))
}
