package io.github.arkmind.seedy.core.requests

import io.github.arkmind.seedy.core.feature.seasonList
import io.github.arkmind.seedy.core.format.daysFromToday
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.format.parseDay
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.SeerrItem
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.delete
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.api.client.extensions.post
import java.time.LocalDate

/** A request as Seedy lists it (web: api/seedy.ts SeerrRequest): the title, and where the request is. */
@Serializable
data class SeerrRequest(
    val requestId: Int,
    val tmdbId: Int,
    val mediaType: String = "movie",
    val title: String = "",
    val year: String? = null,
    val rating: Double? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    /** The title's status in Seerr: "available", "partial", "processing", "pending"… */
    val status: String = "none",
    val requestedBy: String? = null,
    val requestedByMe: Boolean = false,
    val jellyfinItemId: String? = null,
    val requestedAt: String? = null,
    /** The request's own status: "pending", "approved", "declined", "failed", "completed". */
    val requestStatus: String = "pending",
    val seasons: List<Int>? = null,
    val is4k: Boolean = false,
    val availableAt: String? = null,
    val updatedAt: String? = null,
    val download: Download? = null,
    val unaired: List<Int>? = null,
    val releaseDate: String? = null,
) {
    @Serializable
    data class Download(val fraction: Double = 0.0, val season: Int? = null)

    val asItem: SeerrItem
        get() = SeerrItem(tmdbId, mediaType, title, year, rating, posterPath, backdropPath, overview, status, requestedBy, requestedByMe, jellyfinItemId)
}

@Serializable
data class RequestSummary(val canViewAll: Boolean = false, val canManage: Boolean = false, val open: Int = 0, val pending: Int? = null)

@Serializable
data class MyRequests(val results: List<SeerrRequest> = emptyList(), val open: Int = 0)

@Serializable
data class RequestList(val results: List<SeerrRequest> = emptyList(), val total: Int = 0)

/** A Discover title (trending, upcoming…): a Seerr title, with its release date for the upcoming rows. */
@Serializable
data class SeerrTitle(
    val tmdbId: Int,
    val mediaType: String = "movie",
    val title: String = "",
    val year: String? = null,
    val rating: Double? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val overview: String? = null,
    val status: String = "none",
    val requestedBy: String? = null,
    val requestedByMe: Boolean = false,
    val jellyfinItemId: String? = null,
    val releaseDate: String? = null,
    /** Upcoming shows: the season that's coming. */
    val season: Int? = null,
) {
    val asItem: SeerrItem
        get() = SeerrItem(tmdbId, mediaType, title, year, rating, posterPath, backdropPath, overview, status, requestedBy, requestedByMe, jellyfinItemId)
}

@Serializable
data class SeerrTitles(val results: List<SeerrTitle> = emptyList(), val pages: Int = 1)

/** Discover's rows, in the web's order (the row kinds Seedy's /Requests/Titles knows). */
enum class TitlesKind(val key: String, val title: String) {
    Trending("trending", "Trending"),
    UpcomingMovies("upcoming-movies", "Upcoming movies"),
    UpcomingShows("upcoming-shows", "Upcoming shows"),
    PopularMovies("popular-movies", "Popular movies"),
    PopularShows("popular-shows", "Popular shows"),
    ;

    val upcoming: Boolean get() = this == UpcomingMovies || this == UpcomingShows
}

/** Seedy's request endpoints, called as the person (Seerr enforces who may see and manage what). */
class RequestsApi(private val api: ApiClient) {
    suspend fun summary(): RequestSummary = api.get<RequestSummary>("/Seedy/Requests/Summary").content

    suspend fun mine(): MyRequests = api.get<MyRequests>("/Seedy/Requests/Mine").content

    suspend fun list(scope: String, filter: String? = null, take: Int = 40, skip: Int = 0): RequestList =
        api.get<RequestList>(
            "/Seedy/Requests/List",
            queryParameters = mapOf("scope" to scope, "filter" to filter, "sort" to "added", "skip" to skip, "take" to take),
        ).content

    suspend fun titles(kind: TitlesKind, page: Int = 1): SeerrTitles =
        api.get<SeerrTitles>("/Seedy/Requests/Titles/{kind}", mapOf("kind" to kind.key), mapOf("page" to page)).content

    suspend fun approve(id: Int) = api.post<Unit>("/Seedy/Requests/{id}/Approve", mapOf("id" to id))

    suspend fun decline(id: Int) = api.post<Unit>("/Seedy/Requests/{id}/Decline", mapOf("id" to id))

    suspend fun retry(id: Int) = api.post<Unit>("/Seedy/Requests/{id}/Retry", mapOf("id" to id))

    suspend fun remove(id: Int) = api.delete<Unit>("/Seedy/Requests/{id}", mapOf("id" to id))
}

/** Where a request is now, for its chip (and its bar while it downloads). */
data class RequestState(val text: String, val tone: Tone, val fraction: Float? = null) {
    enum class Tone { Ok, Pending, Accent, Muted, Danger }
}

object RequestFormat {
    private fun date(iso: String?): LocalDate? = parseDay(iso)

    /** "asked Sat 17 Oct", "approved today", "asked by Mia yesterday". */
    private fun act(act: String, iso: String?, name: String = ""): String {
        val d = date(iso) ?: return ""
        val ago = -daysFromToday(d)
        val day = if (d.year == LocalDate.now().year) weekdayDate(d) else fullDate(d)
        val forms = when (act) {
            "approved" -> listOf("approved today", "approved yesterday", "approved {date}")
            "declined" -> listOf("declined today", "declined yesterday", "declined {date}")
            "askedBy" -> listOf("asked by {name} today", "asked by {name} yesterday", "asked by {name} {date}")
            else -> listOf("asked today", "asked yesterday", "asked {date}")
        }
        return t(forms[if (ago <= 0) 0 else if (ago == 1L) 1 else 2], "date" to day, "name" to name)
    }

    /** The line under a request's title: "Seasons 2 and 4 · approved Mon 19 Oct · Season 4 downloads when it airs". */
    fun line(r: SeerrRequest, everyone: Boolean): String {
        val parts = mutableListOf(if (r.mediaType == "tv") (r.seasons?.takeIf { it.isNotEmpty() }?.let(::seasonList) ?: t("All seasons")) else t("Movie"))
        if (r.is4k) parts += "4K"
        parts += when {
            everyone -> act("askedBy", r.requestedAt, if (r.requestedByMe) t("you") else r.requestedBy ?: t("someone"))
            r.requestStatus == "declined" -> act("declined", r.updatedAt ?: r.requestedAt)
            (r.requestStatus == "approved" || r.requestStatus == "completed") && r.status != "available" && r.updatedAt != null -> act("approved", r.updatedAt)
            else -> act("asked", r.requestedAt)
        }
        val unaired = r.unaired.orEmpty()
        if (unaired.isNotEmpty() && unaired.size < (r.seasons?.size ?: 0)) {
            parts += t(if (unaired.size == 1) "{seasons} downloads when it airs" else "{seasons} download when they air", "seasons" to seasonList(unaired))
        }
        date(r.releaseDate)?.let { parts += t("out {date}", "date" to weekdayDate(it)) }
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun state(r: SeerrRequest): RequestState = when {
        r.status == "available" -> RequestState(t("Ready to watch"), RequestState.Tone.Ok)
        r.requestStatus == "declined" -> RequestState(t("Declined"), RequestState.Tone.Danger)
        r.requestStatus == "failed" -> RequestState(t("Couldn’t be added"), RequestState.Tone.Danger)
        r.requestStatus == "pending" -> RequestState(t("Waiting for approval"), RequestState.Tone.Pending)
        r.download != null -> {
            val p = (r.download.fraction * 100).toInt()
            val text = r.download.season?.let { t("Season {n} downloading · {p}%", "n" to it, "p" to p) } ?: t("Downloading · {p}%", "p" to p)
            RequestState(text, RequestState.Tone.Accent, r.download.fraction.toFloat())
        }
        r.status == "partial" -> RequestState(t("Partly on the server"), RequestState.Tone.Ok)
        r.releaseDate != null -> RequestState(t("Not out yet"), RequestState.Tone.Muted)
        r.unaired?.isNotEmpty() == true && r.unaired.size >= (r.seasons?.size ?: 0) -> RequestState(t("Not aired yet"), RequestState.Tone.Muted)
        else -> RequestState(t("Searching · not found yet"), RequestState.Tone.Muted)
    }

    /** An upcoming title's date line: "Fri 31 Oct", "Nov 2026", "2027"; soon ones are lit (accent). */
    fun upcoming(title: SeerrTitle, today: LocalDate = LocalDate.now()): Pair<String, Boolean> {
        val d = date(title.releaseDate) ?: return (title.year ?: "") to false
        val days = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        return when {
            days in 0..30 -> weekdayDate(d) to (days <= 14)
            d.year == today.year || days < 365 -> io.github.arkmind.seedy.core.format.monthYear(d) to false
            else -> d.year.toString() to false
        }
    }
}
