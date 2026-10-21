package io.github.arkmind.seedy.core.home

import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.requests.RequestsApi
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.core.seedy.HomeRow
import io.github.arkmind.seedy.core.seedy.Top10Entry
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDateTime
import java.util.UUID

/**
 * The home screen's content, the way the web app builds it: Seedy's rows (ordered for this person) when the server
 * runs the plugin, otherwise the basics from Jellyfin alone (Continue watching, Next up, My media, Latest per library).
 * Rows only describe what to show; [items] loads each one's titles from Jellyfin.
 */
class Home(private val session: UserSession) {
    private val api get() = session.api
    private val userId: UUID get() = session.userId

    suspend fun libraries(): List<BaseItemDto> = io {
        api.userViewApi.getUserViews(userId = userId).content.items
    }

    /** The rows to show, hidden ones (by the person or because a feature is off) already left out. */
    suspend fun rows(libraries: List<BaseItemDto>): List<HomeRow> = io {
        val fromSeedy = session.seedyApi?.let { runCatching { it.homeRows() }.getOrNull() }?.takeIf { it.isNotEmpty() }
        val rows = fromSeedy ?: defaultRows(libraries)
        val me = session.seedy
        val excludes = session.user.configuration?.latestItemsExcludes.orEmpty().map { it.toString() }
        rows.filter { r ->
            when {
                r.kind == "latest" && r.libraryId != null && r.libraryId in excludes -> false
                me != null && r.id in me.prefs.hiddenRows -> false
                r.kind == "because" && me != null && r.seedId in me.prefs.hiddenSeeds -> false
                r.kind == "discover" && me?.requests?.discover != true -> false
                r.kind == "upcomingMovies" && me?.requests?.upcomingMovies != true -> false
                else -> r.kind in Supported
            }
        }
    }

    /** The hero's titles: Seedy's picks, or the newest titles with backdrops. */
    suspend fun featured(): List<FeaturedEntry> = io {
        session.seedyApi?.let { s -> runCatching { s.featured() }.getOrNull() }?.takeIf { it.isNotEmpty() }?.take(5)
            ?: api.libraryApi.getItems(
                userId = userId,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                recursive = true,
                sortBy = listOf(ItemSortBy.DATE_CREATED),
                sortOrder = listOf(SortOrder.DESCENDING),
                limit = 12,
                fields = CardFields + ItemFields.GENRES,
                enableImageTypes = listOf(ImageType.BACKDROP, ImageType.LOGO, ImageType.THUMB, ImageType.PRIMARY),
            ).content.items.filter { !it.backdropImageTags.isNullOrEmpty() }.take(5).map { item ->
                val play = if (item.type == BaseItemKind.SERIES) nextUp(limit = 1, seriesId = item.id).firstOrNull() else null
                FeaturedEntry(item, reason = if (item.type == BaseItemKind.SERIES) "New episodes" else "Just added", play = play)
            }
    }

    suspend fun top10(): List<Top10Entry> = io {
        session.seedyApi?.let { s -> runCatching { s.top10() }.getOrNull() }?.takeUnless { it.quiet }?.items?.take(10).orEmpty()
    }

    /** A row's titles (empty rows are hidden by the screens). */
    suspend fun items(row: HomeRow, libraries: List<BaseItemDto>): List<BaseItemDto> = io {
        when (row.kind) {
            "continue" -> api.libraryApi.getResumeItems(
                userId = userId, limit = 16, mediaTypes = listOf(MediaType.VIDEO), enableUserData = true, imageTypeLimit = 1,
                enableImageTypes = CardImages, fields = CardFields, enableTotalRecordCount = false,
            ).content.items
            "nextup" -> nextUp(limit = 16)
            "because" -> libraryUuid(row.seedId)?.let { seed ->
                api.libraryApi.getSimilarItems(itemId = seed, userId = userId, limit = 16, fields = CardFields).content.items
            }.orEmpty()
            "genres" -> row.genre?.let { genre ->
                api.libraryApi.getItems(
                    userId = userId, genres = listOf(genre), includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    recursive = true, sortBy = listOf(ItemSortBy.RANDOM), limit = 16, fields = CardFields, enableTotalRecordCount = false,
                ).content.items
            }.orEmpty()
            "mymedia" -> libraries.filter { it.collectionType in Browsable }
            "latest" -> libraryUuid(row.libraryId)?.let { lib ->
                api.libraryApi.getLatestMedia(
                    userId = userId, parentId = lib, limit = 16, enableUserData = true, imageTypeLimit = 1,
                    groupItems = true, enableImageTypes = CardImages, fields = CardFields,
                ).content
            }.orEmpty()
            "collections" -> libraryUuid(row.collectionId)?.let { id ->
                api.libraryApi.getItems(
                    userId = userId, parentId = id, limit = 20,
                    sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME), fields = CardFields,
                ).content.items
            }.orEmpty()
            "upcomingShows" -> api.showApi.getUpcomingEpisodes(
                userId = userId, limit = 60, enableUserData = true, imageTypeLimit = 1,
                enableImageTypes = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB),
                fields = listOf(ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.OVERVIEW),
            ).content.items
            else -> emptyList()
        }
    }

    /** Discover and Upcoming movies: Seerr's titles, to request (they come from Seerr, not the library). */
    suspend fun seerr(row: HomeRow): List<SeerrTitle> = io {
        val requests = RequestsApi(api)
        when (row.kind) {
            "discover" -> requests.discover().results
            "upcomingMovies" -> requests.upcoming().results
            else -> emptyList()
        }
    }

    /** Next up, following Settings · Home screen: shows dropped after N days, rewatches included or not. */
    private suspend fun nextUp(limit: Int, seriesId: UUID? = null): List<BaseItemDto> {
        val custom = session.seedy?.prefs?.custom.orEmpty()
        val days = custom["home.nextUpDays"]?.jsonPrimitive?.intOrNull ?: 365
        val rewatching = custom["home.rewatching"]?.jsonPrimitive?.booleanOrNull ?: false
        return api.showApi.getNextUp(
            userId = userId, limit = limit, seriesId = seriesId, enableUserData = true, imageTypeLimit = 1,
            enableResumable = false, enableRewatching = rewatching,
            nextUpDateCutoff = if (days > 0) LocalDateTime.now().minusDays(days.toLong()) else null,
            enableImageTypes = CardImages, fields = CardFields + ItemFields.DATE_LAST_MEDIA_ADDED, enableTotalRecordCount = false,
        ).content.items
    }

    private fun defaultRows(libraries: List<BaseItemDto>): List<HomeRow> =
        listOf(HomeRow("featured", "featured"), HomeRow("continue", "continue"), HomeRow("nextup", "nextup"), HomeRow("mymedia", "mymedia")) +
            libraries.filter { it.collectionType in LatestTypes }.map {
                HomeRow(id = "latest:${it.id}", kind = "latest", libraryId = it.id.toString(), libraryName = it.name, collectionType = it.collectionType?.serialName)
            }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Row kinds the apps show (all of Seedy's). */
        val Supported = setOf(
            "featured", "continue", "nextup", "top10", "because", "genres", "mymedia", "latest", "collections", "upcomingShows",
            "discover", "upcomingMovies",
        )

        /** Rows of Seerr titles rather than library items. */
        val SeerrKinds = setOf("discover", "upcomingMovies")

        val CardFields = listOf(
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.GENRES, ItemFields.CHILD_COUNT, ItemFields.RECURSIVE_ITEM_COUNT,
            ItemFields.OVERVIEW, ItemFields.DATE_CREATED,
        )
        val CardImages = listOf(ImageType.PRIMARY, ImageType.BACKDROP, ImageType.THUMB, ImageType.LOGO)

        private val Browsable = setOf(
            CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.MUSIC, CollectionType.BOXSETS, CollectionType.HOMEVIDEOS,
            CollectionType.MUSICVIDEOS, CollectionType.PLAYLISTS, null,
        )
        private val LatestTypes = setOf(
            CollectionType.MOVIES, CollectionType.TVSHOWS, CollectionType.MUSIC, CollectionType.HOMEVIDEOS, CollectionType.MUSICVIDEOS, null,
        )

        /** A row's heading, as on the web. */
        fun title(row: HomeRow): String = row.title ?: when (row.kind) {
            "because" -> t("Because you watched {title}", "title" to (row.seedName ?: ""))
            "latest" -> t("Latest {library}", "library" to (row.libraryName ?: "").lowercase())
            "genres" -> row.genre.orEmpty()
            "continue" -> t("Continue watching")
            "nextup" -> t("Next up")
            "top10" -> t("Top 10 this week")
            "mymedia" -> t("My media")
            "discover" -> t("Discover")
            "upcomingMovies" -> t("Upcoming movies")
            "upcomingShows" -> t("Upcoming shows")
            else -> t("Collection")
        }
    }
}
