package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.parseDay
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.RequestBody
import io.github.arkmind.seedy.core.seedy.RequestOptions
import io.github.arkmind.seedy.core.seedy.RequestServer
import io.github.arkmind.seedy.core.seedy.SeerrApi
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.core.seedy.SeerrSeason
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import java.time.LocalDate

/** The series a request-seasons sheet is for: from the library (some seasons missing) or from Seerr (search, Discover…). */
data class SeasonsTarget(
    val tmdbId: Int,
    val title: String,
    val year: String? = null,
    /** The server's own art, for a series in the library; else Seerr's paths. */
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    /** True: the series is on the server and some seasons aren't ("Request missing seasons"). */
    val fromLibrary: Boolean = false,
    /** Season numbers Jellyfin has, so they count as on the server even before Seerr notices. */
    val onServer: Set<Int> = emptySet(),
    /** Header line under the title; default "2021 · 3 seasons · Network". */
    val meta: String? = null,
)

/** What a request did, for the page behind the sheet. */
data class SeasonsResult(val status: String, val seasons: List<Int>, val is4k: Boolean)

/**
 * Request seasons (boards "Android · Request seasons" and "Android TV · Request seasons"), shared by every place that
 * asks Seerr for a series: which seasons are on the server or already asked for, the rest to pick, the week's quota,
 * 4K, advanced options, and whether an admin approves it. [open] starts it afresh each time the sheet opens.
 */
class RequestSeasonsViewModel(session: UserSession) : ViewModel() {
    private val seerr = SeerrApi(session.api)

    enum class Kind { Server, Pending, Missing, Soon, Blocked }

    data class Row(
        val n: Int,
        val name: String,
        val sub: String,
        val kind: Kind,
        val status: String,
        val locked: Boolean,
        val thumb: String? = null,
    )

    /** Advanced choices (null: Seerr's default for the server). */
    data class Advanced(val serverId: Int? = null, val profileId: Int? = null, val rootFolder: String? = null, val userId: Int? = null)

    data class State(
        val target: SeasonsTarget? = null,
        val details: SeerrDetails? = null,
        val failed: Boolean = false,
        /** Null while loading (or when it couldn't load). */
        val options: RequestOptions? = null,
        val is4k: Boolean = false,
        val picked: Set<Int> = emptySet(),
        val advanced: Advanced = Advanced(),
        val busy: Boolean = false,
        val error: String? = null,
    ) {
        val rows: List<Row> get() = details?.seasons?.let { rowsFor(it, is4k, target?.onServer.orEmpty()) }.orEmpty()
        val open: List<Int> get() = rows.filter { !it.locked }.map { it.n }
        val count: Int get() = open.count { it in picked }
        val all: Boolean get() = open.isNotEmpty() && open.all { it in picked }
        val some: Boolean get() = !all && open.any { it in picked }
        val can4k: Boolean get() = options?.can4k == true
        val over: Int get() = options?.quota?.let { maxOf(0, count - it.remaining) } ?: 0
        val approve: Boolean? get() = options?.let { if (is4k) it.autoApprove4k else it.autoApprove }
        val canSend: Boolean get() = !busy && count > 0 && over == 0 && details != null

        val title: String get() = target?.title?.ifBlank { null } ?: details?.title.orEmpty()
        val meta: String
            get() = target?.meta ?: join(
                target?.year ?: details?.year,
                (details?.numberOfSeasons ?: details?.seasons?.count { it.seasonNumber > 0 })?.takeIf { it > 0 }?.let { tn(it, "{n} season", "{n} seasons") },
                details?.networks?.firstOrNull(),
            )
        val poster: String? get() = target?.posterUrl ?: tmdbImage(target?.posterPath ?: details?.posterPath, "w185")
        val backdrop: String? get() = target?.backdropUrl ?: tmdbImage(target?.backdropPath ?: details?.backdropPath, "w780")

        /** The servers for this kind of request (4K or not) and the one it goes to. */
        val servers: List<RequestServer> get() = options?.advanced?.servers.orEmpty().filter { it.is4k == is4k }
        val server: RequestServer? get() = servers.firstOrNull { it.id == advanced.serverId } ?: servers.firstOrNull { it.isDefault } ?: servers.firstOrNull()
        val profileId: Int? get() = advanced.profileId ?: server?.profileId
        val rootFolder: String? get() = advanced.rootFolder ?: server?.rootFolder
        val forUser: Int? get() = advanced.userId ?: options?.advanced?.me
        val hasAdvanced: Boolean get() = options?.advanced != null && (servers.isNotEmpty() || options.advanced.users.size > 1)

        /** "HD-1080p · /data/tv · as you". */
        val advancedSummary: String
            get() {
                val adv = options?.advanced
                val name = adv?.users?.firstOrNull { it.id == forUser }?.displayName
                return join(
                    server?.profiles?.firstOrNull { it.id == profileId }?.name,
                    rootFolder,
                    if (forUser == adv?.me || name == null) t("as you") else t("for {name}", "name" to name),
                )
            }

        /** The footer's note: approved straight away, or an admin's approval first. */
        val note: String
            get() = when (approve) {
                null -> ""
                true -> {
                    val unaired = rows.filter { it.kind == Kind.Soon && it.n in picked }.map { it.n }
                    join(
                        t("Approved automatically for you"),
                        if (unaired.isEmpty()) null else tn(unaired.size, "{season} downloads once it airs.", "{season} download once they air.", "season" to seasonList(unaired)),
                        separator = ". ",
                    )
                }
                false -> t("An admin approves requests before they download")
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var loading: Job? = null

    /**
     * Starts the sheet for [target]: every open season ticked (or [initial]'s), 4K when the server already has the
     * series in 4K. [details] is Seerr's answer when the page behind already has it.
     */
    fun open(target: SeasonsTarget, details: SeerrDetails? = null, initial: List<Int>? = null) {
        loading?.cancel()
        _state.value = State(target = target, details = details)
        loading = viewModelScope.launch {
            launch {
                val opts = runCatching { options() }.getOrNull()
                _state.update { it.copy(options = opts) }
                if (opts?.can4k == true && _state.value.details?.seasons.orEmpty().any { s -> s.status4k == "available" || s.status4k == "partial" }) {
                    _state.update { it.copy(is4k = true) }
                }
                pick(initial)
            }
            if (details == null) {
                val d = runCatching { withContext(Dispatchers.IO) { seerr.details("tv", target.tmdbId) } }.getOrNull()
                _state.update { it.copy(details = d, failed = d == null) }
                pick(initial)
            } else {
                pick(initial)
            }
        }
    }

    fun retry() {
        val s = _state.value
        s.target?.let { open(it, null) }
    }

    /** The ticked seasons: [initial] if it names open ones, else all of them; never more than the quota allows. */
    private fun pick(initial: List<Int>?) {
        _state.update { s ->
            val open = s.open
            val start = initial?.filter { it in open }?.takeIf { it.isNotEmpty() } ?: open
            val left = s.options?.quota?.remaining
            s.copy(picked = (if (left != null) start.take(left) else start).toSet())
        }
    }

    fun toggle(n: Int) = _state.update { s ->
        if (s.rows.firstOrNull { it.n == n }?.locked != false) s else s.copy(picked = if (n in s.picked) s.picked - n else s.picked + n)
    }

    fun toggleAll() = _state.update { s -> s.copy(picked = if (s.all) emptySet() else s.open.toSet()) }

    fun set4k(on: Boolean) {
        _state.update { it.copy(is4k = on, advanced = Advanced(userId = it.advanced.userId)) }
        pick(null)
    }

    fun setServer(id: Int) = _state.update { it.copy(advanced = Advanced(serverId = id, userId = it.advanced.userId)) }
    fun setProfile(id: Int) = _state.update { it.copy(advanced = it.advanced.copy(profileId = id)) }
    fun setFolder(path: String) = _state.update { it.copy(advanced = it.advanced.copy(rootFolder = path)) }
    fun setUser(id: Int) = _state.update { it.copy(advanced = it.advanced.copy(userId = id)) }

    /** Sends the request; [onDone] gets what was asked for (the sheet closes then). */
    fun submit(onDone: (SeasonsResult) -> Unit) {
        val s = _state.value
        val target = s.target ?: return
        if (!s.canSend) return
        val list = s.open.filter { it in s.picked }
        val adv = s.advanced
        val body = RequestBody(
            mediaType = "tv", tmdbId = target.tmdbId, seasons = list, is4k = s.is4k,
            serverId = adv.serverId, profileId = adv.profileId, rootFolder = adv.rootFolder,
            userId = adv.userId?.takeIf { it != s.options?.advanced?.me },
        )
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { seerr.request(body) } }
            r.onSuccess { res ->
                optionsCache = null
                _state.update { it.copy(busy = false, details = it.details?.let { d -> markRequested(d, list, s.is4k, res.status) }) }
                onDone(SeasonsResult(res.status, list, s.is4k))
            }.onFailure { e ->
                val msg = if ((e as? InvalidStatusException)?.status == 403) t("Your profile can't request titles") else t("Couldn't request {title}", "title" to s.title)
                _state.update { it.copy(busy = false, error = msg) }
            }
        }
    }

    private suspend fun options(): RequestOptions {
        optionsCache?.let { (at, v) -> if (System.currentTimeMillis() - at < 60_000) return v }
        return withContext(Dispatchers.IO) { seerr.options("tv") }.also { optionsCache = System.currentTimeMillis() to it }
    }

    companion object {
        /** The person's options rarely change; kept a minute so opening the sheet again is instant. */
        @Volatile
        private var optionsCache: Pair<Long, RequestOptions>? = null

        fun rowsFor(seasons: List<SeerrSeason>, is4k: Boolean, onServer: Set<Int>): List<Row> {
            val today = LocalDate.now()
            return seasons.filter { it.seasonNumber > 0 }.map { s ->
                val st = if (is4k) s.status4k else s.status
                val who = if (is4k) (if (s.requestedByMe4k) t("you") else s.requestedBy4k) else (if (s.requestedByMe) t("you") else s.requestedBy)
                val airs = parseDay(s.airDate)
                val unaired = airs == null || airs.isAfter(today)
                val name = s.name.ifBlank { t("Season {n}", "n" to s.seasonNumber) }
                val sub = when {
                    unaired -> when {
                        airs == null -> t("Announced")
                        airs.isBefore(today.plusDays(120)) -> t("Airs {date}", "date" to fullDate(airs))
                        else -> t("Announced for {year}", "year" to airs.year)
                    }
                    s.airedEpisodes != null && s.airedEpisodes < s.episodeCount ->
                        join(t("{n} of {total} episodes aired", "n" to s.airedEpisodes, "total" to s.episodeCount), airs?.year)
                    else -> join(s.episodeCount.takeIf { it > 0 }?.let { tn(it, "{n} episode", "{n} episodes") }, airs?.year)
                }
                val thumb = tmdbImage(s.posterPath, "w185")
                when {
                    st == "available" || st == "partial" || (!is4k && s.seasonNumber in onServer) -> Row(s.seasonNumber, name, sub, Kind.Server, t("On the server"), true, thumb)
                    st == "pending" || st == "processing" -> Row(s.seasonNumber, name, sub, Kind.Pending, if (who != null) t("Requested by {name}", "name" to who) else t("Requested"), true, thumb)
                    st == "blocklisted" -> Row(s.seasonNumber, name, sub, Kind.Blocked, t("Blocked"), true, thumb)
                    unaired -> Row(s.seasonNumber, name, sub, Kind.Soon, t("Not aired yet"), false, thumb)
                    else -> Row(s.seasonNumber, name, sub, Kind.Missing, t("Not on the server"), false, thumb)
                }
            }
        }

        /** The seasons just asked for, as Seerr will show them on the next load. */
        fun markRequested(d: SeerrDetails, seasons: List<Int>, is4k: Boolean, status: String): SeerrDetails {
            val next = when (status) {
                "available" -> "available"
                "processing" -> "processing"
                else -> "pending"
            }
            return d.copy(
                seasons = d.seasons?.map { s ->
                    when {
                        s.seasonNumber !in seasons -> s
                        is4k -> s.copy(status4k = next, requestedBy4k = null, requestedByMe4k = true)
                        else -> s.copy(status = next, requestedBy = null, requestedByMe = true)
                    }
                },
            )
        }
    }
}

/** "Season 4", "Seasons 2 and 4", "Seasons 2, 3 and 5". */
fun seasonList(nums: List<Int>): String {
    val n = nums.sorted().map { it.toString() }
    if (n.size <= 1) return t("Season {n}", "n" to (n.firstOrNull() ?: ""))
    return t("Seasons {list} and {last}", "list" to n.dropLast(1).joinToString(", "), "last" to n.last())
}
