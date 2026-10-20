package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.Filters
import io.github.arkmind.seedy.core.browse.Flag
import io.github.arkmind.seedy.core.browse.Hit
import io.github.arkmind.seedy.core.browse.SearchResults
import io.github.arkmind.seedy.core.browse.kindOf
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.feature.FavoritesViewModel
import io.github.arkmind.seedy.core.feature.LibrariesViewModel
import io.github.arkmind.seedy.core.feature.LibraryViewModel
import io.github.arkmind.seedy.core.feature.RequestSeasonsViewModel
import io.github.arkmind.seedy.core.feature.SearchViewModel
import io.github.arkmind.seedy.core.feature.SeasonsTarget
import io.github.arkmind.seedy.core.seedy.RequestOptions
import io.github.arkmind.seedy.core.seedy.RequestQuota
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.core.seedy.SeerrItem
import io.github.arkmind.seedy.core.seedy.SeerrSeason
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDateTime
import java.util.UUID

/** The browse boards' sample data (Series details, Request seasons, Libraries, Movies library, Search). */
object BrowseSamples {
    private const val MIN = 60L * 10_000_000L

    fun id(name: String): UUID = UUID.nameUUIDFromBytes(name.toByteArray())

    fun data(id: UUID, position: Long = 0, played: Boolean = false, favorite: Boolean = false) =
        UserItemDataDto(playbackPositionTicks = position, playCount = 0, isFavorite = favorite, played = played, key = id.toString(), itemId = id)

    val northbound = BaseItemDto(
        id = id("Northbound"), type = BaseItemKind.SERIES, name = "Northbound", productionYear = 2024, officialRating = "TV-MA",
        genres = listOf("Drama", "Thriller", "Mystery"), status = "Continuing", communityRating = 8.4f, childCount = 2,
        providerIds = mapOf("Tmdb" to "4242"),
        overview = "A search-and-rescue pilot follows a distress signal across the northern range, and finds a town that should not still be there. Season three picks up the winter after the crash, with the pass closing and the radio going quiet.",
        userData = data(id("Northbound"), favorite = true),
    )

    private fun season(n: Int) = BaseItemDto(id = id("Northbound S$n"), type = BaseItemKind.SEASON, name = "Season $n", indexNumber = n, seriesId = northbound.id)

    val seasons = listOf(season(1), season(3))

    private val episodeData = listOf(
        Triple("Thaw", 54, "2026-09-25"), Triple("The Pass", 52, "2026-10-02"), Triple("Whiteout", 49, "2026-10-09"),
        Triple("Relay", 55, "2026-10-16"), Triple("False Summit", 51, "2026-10-23"), Triple("Cold Open", 50, "2026-10-30"),
        Triple("Signal Fire", 53, "2026-11-06"), Triple("The Ridge", 48, "2026-11-13"), Triple("Last Light", 57, "2026-11-20"),
        Triple("Northbound", 62, "2026-11-27"),
    )

    val episodes = episodeData.mapIndexed { i, (name, min, day) ->
        val eid = id("Northbound S3E${i + 1}")
        BaseItemDto(
            id = eid, type = BaseItemKind.EPISODE, name = name, seriesName = "Northbound", seriesId = northbound.id, seasonId = seasons[1].id,
            parentIndexNumber = 3, indexNumber = i + 1, runTimeTicks = min * MIN, premiereDate = LocalDateTime.parse("${day}T00:00:00"),
            userData = data(eid, position = if (i == 1) 18 * MIN else 0, played = i == 0),
        )
    }

    val seerrNorthbound = SeerrDetails(
        tmdbId = 4242, title = "Northbound", year = "2024",
        seasons = listOf(
            SeerrSeason(1, "Season 1", 8, "2024-02-01", status = "available"),
            SeerrSeason(2, "Season 2", 8, "2025-02-01", status = "none"),
            SeerrSeason(3, "Season 3", 10, "2026-09-25", status = "available"),
            SeerrSeason(4, "Season 4", 0, "2027-03-01", status = "none"),
        ),
    )

    val details = DetailsViewModel.State(
        item = northbound, loading = false, seasons = seasons, selected = seasons[1].id, episodes = episodes, episodesFor = seasons[1].id,
        target = Browse.Target(episodes[1], resume = true), badges = listOf("4K", "HDR10", "Atmos", "CC"), seerr = seerrNorthbound, hasTrailer = true,
    )

    val harrowgate = RequestSeasonsViewModel.State(
        target = SeasonsTarget(tmdbId = 77, title = "The Harrowgate Files", year = "2021"),
        details = SeerrDetails(
            tmdbId = 77, title = "The Harrowgate Files", year = "2021", numberOfSeasons = 3,
            seasons = listOf(
                SeerrSeason(1, "Season 1", 8, "2021-03-01"),
                SeerrSeason(2, "Season 2", 8, "2022-03-01", status = "pending", requestedBy = "Sam"),
                SeerrSeason(3, "Season 3", 10, "2024-03-01"),
            ),
        ),
        options = RequestOptions(quota = RequestQuota(limit = 10, days = 7, used = 4, remaining = 6), autoApprove = false, can4k = true),
        picked = setOf(1, 3),
    )

    fun library(name: String, type: CollectionType?) = BaseItemDto(id = id("lib $name"), type = BaseItemKind.COLLECTION_FOLDER, name = name, collectionType = type)

    val libraries = LibrariesViewModel.State(
        loading = false,
        tiles = listOf(
            LibrariesViewModel.Tile(library("Movies", CollectionType.MOVIES), "248 movies"),
            LibrariesViewModel.Tile(library("Shows", CollectionType.TVSHOWS), "61 shows · 4 new episodes"),
            LibrariesViewModel.Tile(library("Collections", CollectionType.BOXSETS), "18 collections"),
            LibrariesViewModel.Tile(library("Home videos", CollectionType.HOMEVIDEOS), "1,204 videos and photos"),
            LibrariesViewModel.Tile(library("Live TV", CollectionType.LIVETV), "301 channels"),
            LibrariesViewModel.Tile(library("Playlists", CollectionType.PLAYLISTS), "7 playlists"),
        ),
    )

    private val movieList = listOf(
        "Saltwater" to 2026, "Halcyon" to 2026, "Second Sun" to 2026, "The Last Signal" to 2026, "Night Ferry" to 2025,
        "Paper Lanterns" to 2025, "Long Way Down" to 2025, "Static Bloom" to 2025, "Low Orbit" to 2025, "The Long Quiet" to 2025,
        "Ember & Ash" to 2024, "The Quiet Engine" to 2024, "Kingfisher" to 2024, "Riverlight" to 2024, "Wildfire Season" to 2024,
    )
    private val runtimes = listOf(114, 110, 126, 101, 115, 116, 98, 107, 121, 104, 119, 96, 112, 105, 130)

    val movies = movieList.mapIndexed { i, (name, year) ->
        movie(name, year, runtimes[i], played = i == 3 || i == 9, position = if (i == 5) 44L else 0L)
    }

    val moviesLibrary = LibraryViewModel.State(
        view = library("Movies", CollectionType.MOVIES), total = 248, filters = Filters(flags = setOf(Flag.UltraHd), genres = listOf("Thriller")),
        items = movies, itemsTotal = 248, loading = false,
    )

    private fun hit(item: BaseItemDto, match: String = "north") = Hit(kindOf(item), item, 50.0, match)

    val nowhere = BaseItemDto(
        id = id("North of Nowhere"), type = BaseItemKind.MOVIE, name = "North of Nowhere", productionYear = 2021, genres = listOf("Drama"),
        runTimeTicks = 109 * MIN, userData = data(id("North of Nowhere"), played = true),
    )
    val northwind = BaseItemDto(
        id = id("Northwind"), type = BaseItemKind.MOVIE, name = "Northwind", productionYear = 2019, genres = listOf("Thriller"),
        runTimeTicks = 97 * MIN, userData = data(id("Northwind")),
    )
    val northPier = BaseItemDto(
        id = id("North Pier"), type = BaseItemKind.EPISODE, name = "North Pier", seriesName = "Glass Harbor", parentIndexNumber = 1, indexNumber = 2,
        runTimeTicks = 48 * MIN, userData = data(id("North Pier")),
    )
    val ines = BaseItemDto(id = id("Ines Northey"), type = BaseItemKind.PERSON, name = "Ines Northey")

    val search = SearchViewModel.State(
        text = "north", query = "north",
        results = SearchResults(
            q = "north", best = hit(northbound), titles = listOf(hit(northbound), hit(nowhere), hit(northwind)),
            episodes = listOf(hit(northPier)), people = listOf(hit(ines)),
        ),
        top = SearchViewModel.TopPlay(episodes[1].copy(seriesName = "Northbound"), resume = true),
        personLines = mapOf(ines.id to "Actor · 4 titles on the server"),
        seerrOn = true,
    )

    val searchNothing = SearchViewModel.State(
        text = "Harrowgate", query = "Harrowgate",
        results = SearchResults(q = "Harrowgate", best = null, titles = emptyList(), episodes = emptyList(), people = emptyList()),
        seerrOn = true,
        seerr = SearchViewModel.Seerr(loading = false, items = listOf(SeerrItem(1, "movie", "Harrowgate", "2026"), SeerrItem(77, "tv", "The Harrowgate Files", "2021"))),
    )

    val movieDetails = DetailsViewModel.State(
        item = movie("Paper Lanterns", 2025, 116, position = 44).copy(
            officialRating = "PG-13", communityRating = 7.6f, genres = listOf("Drama"),
            overview = "Two sisters reopen their late father's lantern workshop for one last festival, and find the letters he never sent.",
        ),
        loading = false, badges = listOf("1080p", "CC"), hasTrailer = false, similar = movies.drop(6).take(4),
    )

    val filterOptions = LibraryViewModel.FilterOptions(
        genres = listOf("Action", "Comedy", "Drama", "Thriller", "Science Fiction"), decades = listOf(2020, 2010, 2000), ratings = listOf("PG", "PG-13", "R"),
    )

    val favorites = FavoritesViewModel.State(
        loading = false,
        groups = mapOf(
            FavoritesViewModel.Group.Movies to movies.take(4),
            FavoritesViewModel.Group.Shows to listOf(northbound),
            FavoritesViewModel.Group.People to listOf(ines, BaseItemDto(id = id("Sam Okafor"), type = BaseItemKind.PERSON, name = "Sam Okafor")),
        ),
    )

    fun movie(name: String, year: Int, minutes: Int = 110, played: Boolean = false, position: Long = 0) = BaseItemDto(
        id = id(name), type = BaseItemKind.MOVIE, name = name, productionYear = year, runTimeTicks = minutes * MIN,
        userData = data(id(name), position = position * MIN, played = played),
    )
}
