package io.github.arkmind.seedy.tv

import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.Filters
import io.github.arkmind.seedy.core.browse.Flag
import io.github.arkmind.seedy.core.browse.Hit
import io.github.arkmind.seedy.core.browse.SearchResults
import io.github.arkmind.seedy.core.browse.kindOf
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.feature.FavoritesViewModel
import io.github.arkmind.seedy.core.feature.LibraryViewModel
import io.github.arkmind.seedy.core.feature.RequestSeasonsViewModel
import io.github.arkmind.seedy.core.feature.SearchViewModel
import io.github.arkmind.seedy.core.feature.SeasonsTarget
import io.github.arkmind.seedy.core.seedy.RequestAdvanced
import io.github.arkmind.seedy.core.seedy.RequestOptions
import io.github.arkmind.seedy.core.seedy.RequestProfile
import io.github.arkmind.seedy.core.seedy.RequestServer
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.core.seedy.SeerrSeason
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.util.UUID

/** The TV browse boards' sample data (Series details, Request seasons, Movies library, Search). */
object TvBrowseSamples {
    private const val MIN = 60L * 10_000_000L

    fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    private fun data(id: UUID, position: Long = 0, played: Boolean = false, favorite: Boolean = false) =
        UserItemDataDto(playbackPositionTicks = position, playCount = 0, isFavorite = favorite, played = played, key = id.toString(), itemId = id)

    val northbound = BaseItemDto(
        id = id("Northbound"), type = BaseItemKind.SERIES, name = "Northbound", productionYear = 2024, officialRating = "TV-MA",
        genres = listOf("Drama", "Thriller", "Mystery"), status = "Continuing", communityRating = 8.4f, childCount = 3,
        providerIds = mapOf("Tmdb" to "4242"),
        overview = "A search-and-rescue pilot follows a distress signal across the northern range, and finds a town that should not still be there. Season three picks up the winter after the crash, with the pass closing and the radio going quiet.",
        userData = data(id("Northbound"), favorite = true),
    )

    private fun season(n: Int, name: String = "Season $n") = BaseItemDto(id = id("Northbound S$n"), type = BaseItemKind.SEASON, name = name, indexNumber = n, seriesId = northbound.id)

    private val seasons = listOf(season(1), season(3), season(0, "Specials"))

    private val recent = LocalDateTime.now().minusDays(2)

    private fun ep(n: Int, name: String, min: Int, day: String, position: Long = 0, played: Boolean = false, added: LocalDateTime? = null, virtual: Boolean = false): BaseItemDto {
        val eid = id("Northbound S3E$n")
        return BaseItemDto(
            id = eid, type = BaseItemKind.EPISODE, name = name, seriesName = "Northbound", seriesId = northbound.id, seasonId = seasons[1].id,
            parentIndexNumber = 3, indexNumber = n, runTimeTicks = min * MIN, premiereDate = LocalDateTime.parse("${day}T00:00:00"),
            dateCreated = added ?: LocalDateTime.parse("2026-09-01T00:00:00"), locationType = if (virtual) LocationType.VIRTUAL else LocationType.FILE_SYSTEM,
            userData = data(eid, position = position, played = played),
        )
    }

    val episodes = listOf(
        ep(1, "Thaw", 54, "2026-09-25", played = true),
        ep(2, "The Pass", 52, "2026-10-02", position = 18 * MIN),
        ep(3, "Whiteout", 49, "2026-10-09", added = recent),
        ep(4, "Relay", 55, "2026-10-16", added = recent),
        ep(5, "False Summit", 51, "2026-10-23", virtual = true),
    )

    val details = DetailsViewModel.State(
        item = northbound, loading = false, seasons = seasons, selected = seasons[1].id, episodes = episodes, episodesFor = seasons[1].id,
        target = Browse.Target(episodes[1], resume = true), badges = listOf("4K", "HDR10", "Atmos", "CC"), hasTrailer = true,
        seerr = SeerrDetails(
            tmdbId = 4242, title = "Northbound",
            seasons = listOf(
                SeerrSeason(1, "Season 1", 8, "2024-02-01", status = "available"),
                SeerrSeason(2, "Season 2", 8, "2025-02-01"),
                SeerrSeason(3, "Season 3", 10, "2026-09-25", status = "available"),
                SeerrSeason(4, "Season 4", 0, "2027-03-01"),
            ),
        ),
    )

    val harrowgate = RequestSeasonsViewModel.State(
        target = SeasonsTarget(tmdbId = 77, title = "The Harrowgate Files", year = "2021"),
        details = SeerrDetails(
            tmdbId = 77, title = "The Harrowgate Files", year = "2021", numberOfSeasons = 3, networks = listOf("Halden"),
            seasons = listOf(
                SeerrSeason(1, "Season 1", 8, "2021-03-01"),
                SeerrSeason(2, "Season 2", 8, "2022-03-01", status = "pending", requestedBy = "Sam"),
                SeerrSeason(3, "Season 3", 10, "2024-03-01"),
            ),
        ),
        options = RequestOptions(
            autoApprove = true, can4k = true,
            advanced = RequestAdvanced(
                servers = listOf(RequestServer(0, "Sonarr", isDefault = true, profileId = 4, rootFolder = "/data/tv", profiles = listOf(RequestProfile(4, "HD-1080p")))),
                me = 1,
            ),
        ),
        picked = setOf(1, 3),
    )

    private val movieList = listOf(
        "Saltwater" to 2026, "Halcyon" to 2026, "Second Sun" to 2026, "The Last Signal" to 2026, "Night Ferry" to 2025,
        "Paper Lanterns" to 2025, "Long Way Down" to 2025, "Static Bloom" to 2025, "Low Orbit" to 2025, "The Long Quiet" to 2025,
        "Ember & Ash" to 2024, "The Quiet Engine" to 2024, "Kingfisher" to 2024, "Riverlight" to 2024, "Wildfire Season" to 2024,
    )
    private val runtimes = listOf(114, 110, 126, 101, 115, 116, 98, 107, 121, 104, 119, 96, 112, 105, 130)

    val movies = movieList.mapIndexed { i, (name, year) ->
        BaseItemDto(
            id = id(name), type = BaseItemKind.MOVIE, name = name, sortName = name, productionYear = year, runTimeTicks = runtimes[i] * MIN,
            userData = data(id(name), position = if (i == 5) 44 * MIN else 0, played = i == 3 || i == 9),
        )
    }

    val moviesLibrary = LibraryViewModel.State(
        view = BaseItemDto(id = id("lib Movies"), type = BaseItemKind.COLLECTION_FOLDER, name = "Movies", collectionType = CollectionType.MOVIES),
        total = 248, filters = Filters(flags = setOf(Flag.UltraHd), genres = listOf("Thriller")), items = movies, itemsTotal = 248, loading = false,
    )

    private fun hit(item: BaseItemDto) = Hit(kindOf(item), item, 50.0, "north")

    private val nowhere = BaseItemDto(
        id = id("North of Nowhere"), type = BaseItemKind.MOVIE, name = "North of Nowhere", productionYear = 2021, runTimeTicks = 109 * MIN,
        userData = data(id("North of Nowhere"), played = true),
    )
    private val northwind = BaseItemDto(id = id("Northwind"), type = BaseItemKind.MOVIE, name = "Northwind", productionYear = 2019, runTimeTicks = 97 * MIN, userData = data(id("Northwind")))
    private val pier = BaseItemDto(
        id = id("North Pier"), type = BaseItemKind.EPISODE, name = "North Pier", seriesName = "Glass Harbor", parentIndexNumber = 1, indexNumber = 2,
        runTimeTicks = 48 * MIN, userData = data(id("North Pier")),
    )
    private val channel = BaseItemDto(
        id = id("Northline One"), type = BaseItemKind.TV_CHANNEL, name = "Northline One", channelNumber = "1",
        currentProgram = BaseItemDto(id = id("The Glass House"), type = BaseItemKind.PROGRAM, name = "The Glass House"),
    )
    private val ines = BaseItemDto(id = id("Ines Northey"), type = BaseItemKind.PERSON, name = "Ines Northey")

    val search = SearchViewModel.State(
        text = "north", query = "north",
        results = SearchResults(
            q = "north", best = hit(northbound), titles = listOf(hit(northbound), hit(nowhere).copy(score = 49.0), hit(northwind).copy(score = 48.0)),
            episodes = listOf(hit(pier)), people = listOf(hit(ines).copy(score = 40.0)), channels = listOf(hit(channel)),
        ),
        top = SearchViewModel.TopPlay(episodes[1], resume = true),
        personLines = mapOf(ines.id to "Actor · 4 titles"),
        seerrOn = true,
    )

    val favorites = FavoritesViewModel.State(
        loading = false,
        groups = mapOf(FavoritesViewModel.Group.Movies to movies.take(5), FavoritesViewModel.Group.Shows to listOf(northbound), FavoritesViewModel.Group.People to listOf(ines)),
    )
}
