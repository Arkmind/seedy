package io.github.arkmind.seedy.tv

import io.github.arkmind.seedy.core.feature.RequestsViewModel
import io.github.arkmind.seedy.core.requests.RequestSummary
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.requests.TitlesKind
import java.time.LocalDate

/** Board "Android TV · Requests" sample data. */
object TvRequestsSamples {
    val today: LocalDate = LocalDate.of(2026, 10, 21)

    private fun day(offset: Long) = today.plusDays(offset).toString()

    val title = io.github.arkmind.seedy.core.feature.SeerrTitleViewModel.State(
        loading = false,
        details = io.github.arkmind.seedy.core.seedy.SeerrDetails(
            tmdbId = 11, mediaType = "tv", title = "The Harrowgate Files", year = "2021", rating = 7.0, status = "pending", requestedBy = "Sam",
            overview = "A records clerk in a drowned market town finds the files nobody was meant to read, and the people still looking for them.",
            genres = listOf("Drama", "Mystery"), certification = "TV-14", numberOfSeasons = 3, networks = listOf("Northlight"),
            cast = listOf(io.github.arkmind.seedy.core.seedy.SeerrCast(1, "Ines Northey", "Maud"), io.github.arkmind.seedy.core.seedy.SeerrCast(2, "Tom Aske", "Ellis")),
            crew = listOf(io.github.arkmind.seedy.core.seedy.SeerrCrew("Ada Price", "Creator")),
            releaseDate = "2021-03-01",
            seasons = listOf(
                io.github.arkmind.seedy.core.seedy.SeerrSeason(1, "Season 1", 8, "2021-03-01"),
                io.github.arkmind.seedy.core.seedy.SeerrSeason(2, "Season 2", 8, "2022-03-01", status = "pending", requestedBy = "Sam"),
                io.github.arkmind.seedy.core.seedy.SeerrSeason(3, "Season 3", 10, "2024-03-01"),
            ),
        ),
    )

    val requests = RequestsViewModel.State(
        summary = RequestSummary(open = 2),
        latest = listOf(
            SeerrRequest(1, 501, "tv", "The Harrowgate Files", requestStatus = "pending", seasons = listOf(1, 3), requestedAt = day(0)),
            SeerrRequest(
                2, 502, "tv", "Northbound", requestStatus = "approved", status = "processing", seasons = listOf(2, 4),
                download = SeerrRequest.Download(0.62, season = 2),
            ),
            SeerrRequest(3, 503, "movie", "Meridian Line", requestStatus = "completed", status = "available", requestedAt = "2026-10-17"),
        ),
        rows = listOf(
            RequestsViewModel.Row(
                TitlesKind.Trending,
                listOf(
                    SeerrTitle(11, "tv", "The Harrowgate Files", "2021", 7.0, status = "pending"),
                    SeerrTitle(12, "movie", "Harrowgate", "2026", 7.4),
                    SeerrTitle(13, "tv", "Low Tide", "2025", 8.0),
                    SeerrTitle(14, "movie", "Meridian Line", "2026", 6.8, status = "available"),
                    SeerrTitle(15, "movie", "Glass Sky", "2026", 7.1),
                    SeerrTitle(16, "tv", "Westerly", "2024", 7.7),
                    SeerrTitle(17, "movie", "Undertow", "2021", 6.5),
                ),
            ),
            RequestsViewModel.Row(
                TitlesKind.UpcomingMovies,
                listOf(
                    SeerrTitle(21, "movie", "Glass Sky", releaseDate = "2026-10-30"),
                    SeerrTitle(22, "movie", "The Ninth Hour", releaseDate = "2026-11-28"),
                    SeerrTitle(23, "movie", "Paper Moon", releaseDate = "2026-12-12"),
                    SeerrTitle(24, "movie", "Cold Front", releaseDate = "2027-04-02"),
                    SeerrTitle(25, "movie", "Saltmarsh", releaseDate = "2027-05-02"),
                    SeerrTitle(26, "movie", "Long Light", releaseDate = "2027-06-02"),
                    SeerrTitle(27, "movie", "High Water", releaseDate = "2027-07-02"),
                ),
            ),
        ),
    )
}
