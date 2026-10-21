package io.github.arkmind.seedy.core.format

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/** Image addresses for items and people. Jellyfin serves images without a token; the tag makes them cacheable. */
class Images(private val api: ApiClient) {
    fun item(id: UUID, type: ImageType, tag: String, maxWidth: Int, index: Int? = null): String =
        api.imageApi.getItemImageUrl(itemId = id, imageType = type, tag = tag, maxWidth = maxWidth, quality = 90, imageIndex = index)

    /** Portrait art: the item's poster, or its show's for an episode or season without one. */
    fun poster(item: BaseItemDto, maxWidth: Int): String? =
        item.imageTags?.get(ImageType.PRIMARY)?.let { item(item.id, ImageType.PRIMARY, it, maxWidth) }
            ?: item.seriesPrimaryImageTag?.let { tag -> item.seriesId?.let { item(it, ImageType.PRIMARY, tag, maxWidth) } }

    fun backdrop(item: BaseItemDto, maxWidth: Int): String? =
        item.backdropImageTags?.firstOrNull()?.let { item(item.id, ImageType.BACKDROP, it, maxWidth, 0) }
            ?: item.parentBackdropImageTags?.firstOrNull()?.let { tag -> item.parentBackdropItemId?.let { item(it, ImageType.BACKDROP, tag, maxWidth, 0) } }

    /**
     * Landscape art for 16:9 cards: an episode's own picture (unless the person turned episode pictures off), then the
     * title's thumb, its show's thumb, a backdrop, and the poster as a last resort.
     */
    fun landscape(item: BaseItemDto, maxWidth: Int, episodeImages: Boolean = true): String? {
        if (item.type == BaseItemKind.EPISODE && episodeImages) {
            item.imageTags?.get(ImageType.PRIMARY)?.let { return item(item.id, ImageType.PRIMARY, it, maxWidth) }
        }
        item.imageTags?.get(ImageType.THUMB)?.let { return item(item.id, ImageType.THUMB, it, maxWidth) }
        item.parentThumbImageTag?.let { tag -> item.parentThumbItemId?.let { return item(it, ImageType.THUMB, tag, maxWidth) } }
        item.seriesThumbImageTag?.let { tag -> item.seriesId?.let { return item(it, ImageType.THUMB, tag, maxWidth) } }
        return backdrop(item, maxWidth) ?: poster(item, maxWidth)
    }

    fun logo(item: BaseItemDto, maxWidth: Int): String? =
        item.imageTags?.get(ImageType.LOGO)?.let { item(item.id, ImageType.LOGO, it, maxWidth) }
            ?: item.parentLogoImageTag?.let { tag -> item.parentLogoItemId?.let { item(it, ImageType.LOGO, tag, maxWidth) } }

    fun user(userId: UUID, tag: String?): String? = tag?.let { api.imageApi.getUserImageUrl(userId = userId, tag = it) }

    /** Someone's picture by their id alone (Seedy's people list says whether they have one). */
    fun userPicture(userId: String, maxWidth: Int): String =
        api.baseUrl.orEmpty().trimEnd('/') + "/Users/$userId/Images/Primary?quality=90&maxWidth=$maxWidth"

    /** A person's portrait, a channel's logo, a library's own picture: the item's primary image. */
    fun primary(item: BaseItemDto, maxWidth: Int): String? =
        item.imageTags?.get(ImageType.PRIMARY)?.let { item(item.id, ImageType.PRIMARY, it, maxWidth) }

    /**
     * Art for the 16:9 library cards, as the web's cardArt: a thumb (it carries the title), else a backdrop with the
     * logo set on it (or the title written on it when there's no logo), else the poster contained over its own blur.
     */
    fun cardArt(item: BaseItemDto, maxWidth: Int, episodeImages: Boolean = true): CardArt {
        if (item.type == BaseItemKind.EPISODE) {
            val own = if (episodeImages) item.imageTags?.get(ImageType.PRIMARY)?.let { item(item.id, ImageType.PRIMARY, it, maxWidth) } else null
            val src = own ?: item.parentThumbImageTag?.let { tag -> item.parentThumbItemId?.let { item(it, ImageType.THUMB, tag, maxWidth) } }
                ?: backdrop(item, maxWidth)
            return CardArt(src, titleOnArt = src == null)
        }
        if (item.type == BaseItemKind.MUSIC_ALBUM || item.type == BaseItemKind.AUDIO || item.type == BaseItemKind.MUSIC_ARTIST || item.type == BaseItemKind.PLAYLIST) {
            val p = primary(item, maxWidth)
            return CardArt(p, contain = p != null, titleOnArt = p == null)
        }
        item.imageTags?.get(ImageType.THUMB)?.let { return CardArt(item(item.id, ImageType.THUMB, it, maxWidth)) }
        backdrop(item, maxWidth)?.let { bd ->
            val logo = logo(item, (maxWidth * 0.6f).toInt())
            return CardArt(bd, logo = logo, titleOnArt = logo == null)
        }
        val p = primary(item, maxWidth)
        if (p != null) return CardArt(p, contain = (item.primaryImageAspectRatio ?: 0.67) < 1.2, titleOnArt = true)
        return CardArt(null, titleOnArt = true)
    }
}

/** What a 16:9 card draws: the picture, a logo on it, or the title written on it (the boards' stand-in). */
data class CardArt(val src: String?, val logo: String? = null, val contain: Boolean = false, val titleOnArt: Boolean = false)
