package io.github.arkmind.seedy.mobile

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/** The boards' sample titles, for screenshot tests. */
object Samples {
    private const val MIN = 60L * 10_000_000L

    private fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    private fun data(id: UUID, position: Long = 0, played: Boolean = false) =
        UserItemDataDto(playbackPositionTicks = position, playCount = 0, isFavorite = false, played = played, key = id.toString(), itemId = id)

    val northbound = BaseItemDto(
        id = id("Northbound"), type = BaseItemKind.SERIES, name = "Northbound", productionYear = 2026, officialRating = "TV-MA",
        genres = listOf("Drama", "Thriller", "Mystery"), status = "Continuing",
    )

    val thePass = BaseItemDto(
        id = id("The Pass"), type = BaseItemKind.EPISODE, name = "The Pass", seriesName = "Northbound", seriesId = northbound.id,
        parentIndexNumber = 3, indexNumber = 2, runTimeTicks = 52 * MIN, userData = data(id("The Pass"), position = 18 * MIN),
    )

    val paperLanterns = BaseItemDto(
        id = id("Paper Lanterns"), type = BaseItemKind.MOVIE, name = "Paper Lanterns", productionYear = 2025,
        runTimeTicks = 118 * MIN, userData = data(id("Paper Lanterns"), position = 46 * MIN),
    )

    val glassHarbor = BaseItemDto(
        id = id("Glass Harbor"), type = BaseItemKind.EPISODE, name = "North Pier", seriesName = "Glass Harbor",
        parentIndexNumber = 1, indexNumber = 4, runTimeTicks = 48 * MIN, userData = data(id("Glass Harbor"), position = 28 * MIN),
    )
}
