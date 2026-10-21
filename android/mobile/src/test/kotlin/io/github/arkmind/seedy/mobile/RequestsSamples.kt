package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.core.feature.RequestsViewModel
import io.github.arkmind.seedy.core.requests.RequestSummary
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.requests.TitlesKind
import java.time.LocalDate

/** Board "Android · Requests" sample data. */
object RequestsSamples {
    /** The board's day: Glass Sky (Fri 30 Oct) is nine days off, so it's lit. */
    val today: LocalDate = LocalDate.of(2026, 10, 21)

    val title = io.github.arkmind.seedy.core.feature.SeerrTitleViewModel.State(
        loading = false,
        details = io.github.arkmind.seedy.core.seedy.SeerrDetails(
            tmdbId = 11, mediaType = "movie", title = "Harrowgate", year = "2026", rating = 7.4, runtime = 118, certification = "PG-13",
            tagline = "Some doors stay shut for a reason.",
            overview = "A records clerk in a drowned market town finds the files nobody was meant to read, and the people still looking for them.",
            genres = listOf("Thriller", "Mystery"), releaseDate = "2026-04-10",
            cast = listOf(io.github.arkmind.seedy.core.seedy.SeerrCast(1, "Ines Northey", "Maud"), io.github.arkmind.seedy.core.seedy.SeerrCast(2, "Tom Aske", "Ellis"), io.github.arkmind.seedy.core.seedy.SeerrCast(3, "Ruth Vale", "Ada")),
            crew = listOf(io.github.arkmind.seedy.core.seedy.SeerrCrew("Ada Price", "Director")),
        ),
    )

    private val mine = listOf(
        SeerrRequest(1, 501, "tv", "Low Tide", requestStatus = "pending"),
        SeerrRequest(2, 502, "movie", "Meridian Line", requestStatus = "approved", download = SeerrRequest.Download(0.4)),
        // Arrived today (by the clock: the card only shows how many).
        SeerrRequest(3, 503, "movie", "Paper Moon", requestStatus = "completed", status = "available", availableAt = LocalDate.now().toString()),
    )

    val discover = RequestsViewModel.State(
        summary = RequestSummary(open = 3),
        latest = mine,
        rows = listOf(
            RequestsViewModel.Row(
                TitlesKind.Trending,
                listOf(
                    SeerrTitle(11, "movie", "Harrowgate", "2026", 7.4),
                    SeerrTitle(12, "tv", "The Harrowgate Files", "2021", 7.0, status = "pending"),
                    SeerrTitle(13, "tv", "Low Tide", "2025", 8.0),
                    SeerrTitle(14, "movie", "Meridian Line", "2026", 6.8, status = "available"),
                ),
            ),
            RequestsViewModel.Row(
                TitlesKind.UpcomingMovies,
                listOf(
                    SeerrTitle(21, "movie", "Glass Sky", releaseDate = "2026-10-30"),
                    SeerrTitle(22, "movie", "The Ninth Hour", releaseDate = "2026-11-28"),
                    SeerrTitle(23, "movie", "Paper Moon", releaseDate = "2026-12-12"),
                    SeerrTitle(24, "movie", "Cold Front", releaseDate = "2027-04-02"),
                ),
            ),
        ),
    )
}
