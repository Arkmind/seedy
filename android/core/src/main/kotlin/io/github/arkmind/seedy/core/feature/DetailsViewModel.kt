package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.ItemsQuery
import io.github.arkmind.seedy.core.browse.techBadges
import io.github.arkmind.seedy.core.format.isVirtual
import io.github.arkmind.seedy.core.format.parseDay
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.SeerrApi
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.core.seedy.canRequest
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
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDate
import java.util.UUID

/**
 * A title's page for both screen sets (boards "Android · Series details" and "Android TV · Series details"): the
 * series with its seasons and episodes, what Play starts, the format badges, and the seasons Seerr knows that aren't
 * on the server (Request). Movies are the same page without seasons; collections and people list their titles.
 */
class DetailsViewModel(private val session: UserSession, private val id: UUID) : ViewModel() {
    private val browse = Browse(session)

    /** A season Seerr knows that isn't on the server: a dashed tab that opens the request sheet. */
    data class MissingSeason(val n: Int, val name: String, val year: Int?, val requested: Boolean)

    /** One season tab: a season on the server, or one that's missing. */
    sealed interface SeasonTab {
        val number: Int

        data class OnServer(val season: BaseItemDto) : SeasonTab {
            override val number: Int get() = season.indexNumber ?: 0
        }

        data class Missing(val season: MissingSeason) : SeasonTab {
            override val number: Int get() = season.n
        }
    }

    /** The seasons the server lacks, and what the "are missing" card says about them. */
    data class Gaps(
        val missing: List<MissingSeason> = emptyList(),
        /** Seasons that can still be requested. */
        val open: List<Int> = emptyList(),
        /** "Seasons 2 and 4 are missing" (phone card). */
        val title: String = "",
        /** "Seasons 2 and 4 aren't on the server" (TV line). */
        val line: String = "",
        /** "Season 4 is announced for 2027", "Season 2 is already requested". */
        val sub: String = "",
        /** Seasons Seerr knows of in all. */
        val total: Int = 0,
    )

    /** What the Trailer button plays: a trailer on the server, or a video to open (YouTube). */
    sealed interface Trailer {
        data class Local(val item: BaseItemDto) : Trailer
        data class Url(val url: String) : Trailer
    }

    data class State(
        val item: BaseItemDto? = null,
        val loading: Boolean = true,
        /** 404/400: the title isn't there, or this profile can't see it. */
        val missing: Boolean = false,
        val error: Boolean = false,
        /** Regular seasons, then specials; Jellyfin's empty placeholders give way to Seerr's dashed tabs. */
        val seasons: List<BaseItemDto>? = null,
        val selected: UUID? = null,
        val episodes: List<BaseItemDto>? = null,
        /** The season [episodes] belong to (another season's list never shows while one loads). */
        val episodesFor: UUID? = null,
        val target: Browse.Target? = null,
        val badges: List<String> = emptyList(),
        val seerr: SeerrDetails? = null,
        val similar: List<BaseItemDto>? = null,
        /** A collection's titles, a person's filmography. */
        val children: List<BaseItemDto>? = null,
        val hasTrailer: Boolean = false,
        val busy: Boolean = false,
    ) {
        val isSeries: Boolean get() = item?.type == BaseItemKind.SERIES
        val tmdbId: Int get() = item?.providerIds?.entries?.firstOrNull { it.key.equals("Tmdb", ignoreCase = true) }?.value?.toIntOrNull() ?: 0

        /** Season numbers with files on the server. */
        val onServer: Set<Int> get() = seasons.orEmpty().filter { !it.isVirtual && (it.indexNumber ?: 0) > 0 }.mapNotNull { it.indexNumber }.toSet()

        val gaps: Gaps get() = gapsFor(seasons.orEmpty(), seerr)

        /** The season tabs: the server's seasons, with Seerr's missing ones in number order (specials last). */
        val tabs: List<SeasonTab> get() {
            val g = gaps.missing
            val away = g.map { it.n }.toSet()
            // Jellyfin's placeholder seasons give way to the dashed tab for the same season.
            val list = seasons.orEmpty().filter { !(it.isVirtual && it.indexNumber in away) }
            if (g.isEmpty()) return list.map { SeasonTab.OnServer(it) }
            val specials = list.filter { it.indexNumber == 0 }.map { SeasonTab.OnServer(it) }
            val rest = list.filter { it.indexNumber != 0 }.map { SeasonTab.OnServer(it) } + g.map { SeasonTab.Missing(it) }
            return rest.sortedBy { it.number } + specials
        }

        val selectedSeason: BaseItemDto? get() = seasons?.firstOrNull { it.id == selected }
        val shownEpisodes: List<BaseItemDto>? get() = episodes?.takeIf { episodesFor == selected }

        /** "4 seasons": Seerr's count when it knows more seasons than the server has. */
        val seasonsLabel: String
            get() {
                val regular = seasons.orEmpty().count { (it.indexNumber ?: 1) > 0 && !it.isVirtual }
                val n = maxOf(gaps.total, regular).takeIf { it > 0 } ?: item?.childCount ?: 0
                return if (n > 0) tn(n, "{n} season", "{n} seasons") else ""
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var episodesJob: Job? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val item = try {
                browse.item(id)
            } catch (e: InvalidStatusException) {
                _state.update { it.copy(loading = false, missing = e.status == 404 || e.status == 400, error = true) }
                return@launch
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = true) }
                return@launch
            }
            _state.update { it.copy(item = item, loading = false, error = false, hasTrailer = (item.localTrailerCount ?: 0) > 0 || !item.remoteTrailers.isNullOrEmpty()) }
            when (item.type) {
                BaseItemKind.SERIES -> loadSeries(item)
                BaseItemKind.SEASON -> item.seriesId?.let { sid -> runCatching { browse.item(sid) }.getOrNull() }?.let { series ->
                    _state.update { it.copy(selected = item.id) }
                    loadSeries(series)
                }
                BaseItemKind.BOX_SET, BaseItemKind.PLAYLIST, BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER -> loadChildren(item)
                BaseItemKind.PERSON -> loadFilmography(item)
                else -> {
                    val streams = if (!item.mediaStreams.isNullOrEmpty() || !item.mediaSources.isNullOrEmpty()) item else runCatching { browse.withStreams(item.id) }.getOrNull()
                    _state.update { it.copy(badges = techBadges(streams)) }
                    launch { val s = runCatching { browse.similar(item.id) }.getOrDefault(emptyList()); _state.update { it.copy(similar = s) } }
                }
            }
            if (!_state.value.hasTrailer && (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.SERIES)) {
                launch { if (browse.seedyTrailer(item.id) != null) _state.update { it.copy(hasTrailer = true) } }
            }
        }
    }

    private var shown = false

    /**
     * The page is back on screen (from the player, say): watched marks, progress and what Play starts may have
     * changed. The first call (the page opening) does nothing; [load] runs then.
     */
    fun refresh() {
        if (!shown) {
            shown = true
            return
        }
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val fresh = runCatching { browse.item(item.id) }.getOrNull() ?: return@launch
            _state.update { it.copy(item = fresh) }
            if (fresh.type == BaseItemKind.SERIES) refreshSeries()
        }
    }

    private suspend fun loadSeries(series: BaseItemDto) {
        _state.update { it.copy(item = series) }
        viewModelScope.launch {
            val target = browse.seriesTarget(series.id)
            _state.update { it.copy(target = target) }
            val streams = target?.let { runCatching { browse.withStreams(it.item.id) }.getOrNull() }
            _state.update { it.copy(badges = techBadges(streams)) }
            if (_state.value.selected == null) pickSeason()
        }
        viewModelScope.launch {
            val tmdb = _state.value.tmdbId
            if (session.canRequest && tmdb > 0) {
                val d = runCatching { withContext(Dispatchers.IO) { SeerrApi(session.api).details("tv", tmdb) } }.getOrNull()
                _state.update { it.copy(seerr = d) }
            }
        }
        viewModelScope.launch {
            val s = runCatching { browse.similar(series.id) }.getOrDefault(emptyList())
            _state.update { it.copy(similar = s) }
        }
        val seasons = runCatching { browse.seasons(series.id) }.getOrDefault(emptyList())
        _state.update { it.copy(seasons = seasons.filter { (it.indexNumber ?: 1) != 0 } + seasons.filter { it.indexNumber == 0 }) }
        pickSeason()
    }

    /** The season to open on: the one asked for, the one Play is in, else the first regular season. */
    private fun pickSeason() {
        val s = _state.value
        val list = s.seasons ?: return
        val shown = list.filter { !(it.isVirtual && it.indexNumber in s.gaps.missing.map { m -> m.n }) }
        val pick = s.selected?.takeIf { id -> shown.any { it.id == id } }
            ?: s.target?.item?.seasonId?.takeIf { id -> shown.any { it.id == id } }
            ?: shown.firstOrNull { (it.indexNumber ?: 1) > 0 }?.id
            ?: shown.firstOrNull()?.id
        if (pick != null && (pick != s.selected || s.episodesFor != pick)) selectSeason(pick)
    }

    fun selectSeason(seasonId: UUID) {
        val series = _state.value.item ?: return
        _state.update { it.copy(selected = seasonId) }
        episodesJob?.cancel()
        episodesJob = viewModelScope.launch {
            val eps = runCatching { browse.episodes(series.id, seasonId) }.getOrNull() ?: return@launch
            _state.update { it.copy(episodes = eps, episodesFor = seasonId) }
        }
    }

    private suspend fun loadChildren(item: BaseItemDto) {
        val q = ItemsQuery(
            parentId = item.id, recursive = false, limit = 300,
            sortBy = if (item.type == BaseItemKind.PLAYLIST) emptyList() else listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
        )
        val kids = runCatching { browse.items(q).items }.getOrDefault(emptyList())
        _state.update { it.copy(children = kids) }
    }

    private suspend fun loadFilmography(person: BaseItemDto) {
        val q = ItemsQuery(
            personIds = listOf(person.id), includeTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES), limit = 200,
            sortBy = listOf(ItemSortBy.PREMIERE_DATE, ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME), sortOrder = SortOrder.DESCENDING,
        )
        val films = runCatching { browse.items(q).items }.getOrDefault(emptyList())
        _state.update { it.copy(children = films) }
    }

    /** What the main button plays: the series' episode, or the title (a collection's first title). */
    fun play(onPlay: (BaseItemDto) -> Unit) {
        val s = _state.value
        val item = s.item ?: return
        s.target?.let { return onPlay(it.item) }
        viewModelScope.launch { browse.playable(item)?.let(onPlay) }
    }

    fun shuffle(onPlay: (BaseItemDto) -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            val q = when (item.type) {
                BaseItemKind.SERIES -> ItemsQuery(parentId = item.id, includeTypes = listOf(BaseItemKind.EPISODE), isMissing = false)
                else -> ItemsQuery(parentId = item.id, excludeTypes = listOf(BaseItemKind.FOLDER, BaseItemKind.BOX_SET, BaseItemKind.SERIES, BaseItemKind.SEASON), isMissing = false)
            }
            runCatching { browse.random(q) }.getOrNull()?.let(onPlay)
        }
    }

    fun toggleFavorite() {
        val item = _state.value.item ?: return
        val on = item.userData?.isFavorite != true
        patch(item) { it.copy(isFavorite = on) }
        viewModelScope.launch {
            if (runCatching { browse.setFavorite(item.id, on) }.isFailure) patch(item) { it.copy(isFavorite = !on) }
        }
    }

    fun togglePlayed() {
        val item = _state.value.item ?: return
        val on = item.userData?.played != true
        patch(item) { it.copy(played = on, playbackPositionTicks = 0, playedPercentage = 0.0) }
        viewModelScope.launch {
            if (runCatching { browse.setPlayed(item.id, on) }.isFailure) {
                patch(item) { it.copy(played = !on) }
            } else if (item.type == BaseItemKind.SERIES) {
                refreshSeries()
            }
        }
    }

    /** Marks the open season watched (or unwatched when it already is). */
    fun toggleSeasonPlayed() {
        val season = _state.value.selectedSeason ?: return
        val eps = _state.value.shownEpisodes.orEmpty().filter { !it.isVirtual }
        val on = !(eps.isNotEmpty() && eps.all { it.userData?.played == true })
        viewModelScope.launch {
            if (runCatching { browse.setPlayed(season.id, on) }.isSuccess) refreshSeries()
        }
    }

    private suspend fun refreshSeries() {
        val item = _state.value.item ?: return
        val target = browse.seriesTarget(item.id)
        _state.update { it.copy(target = target) }
        _state.value.selected?.let { selectSeason(it) }
    }

    private fun patch(item: BaseItemDto, change: (UserItemDataDto) -> UserItemDataDto) {
        val data = item.userData ?: UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = false, played = false, key = item.id.toString(), itemId = item.id)
        _state.update { s -> s.copy(item = s.item?.copy(userData = change(s.item.userData ?: data))) }
    }

    /** Seasons just requested show as requested straight away. */
    fun markRequested(result: SeasonsResult) {
        _state.update { s -> s.copy(seerr = s.seerr?.let { RequestSeasonsViewModel.markRequested(it, result.seasons, result.is4k, result.status) }) }
    }

    /** The Trailer button: a trailer on the server plays; else Jellyfin's or Seedy's YouTube trailer opens. */
    fun trailer(onTrailer: (Trailer) -> Unit) {
        val item = _state.value.item ?: return
        viewModelScope.launch {
            if ((item.localTrailerCount ?: 0) > 0) {
                runCatching { browse.localTrailers(item.id) }.getOrNull()?.firstOrNull()?.let { return@launch onTrailer(Trailer.Local(it)) }
            }
            item.remoteTrailers?.firstNotNullOfOrNull { it.url?.takeIf(String::isNotBlank) }?.let { return@launch onTrailer(Trailer.Url(it)) }
            val seedy = browse.seedyTrailer(item.id) ?: return@launch
            when {
                seedy.source == "local" && seedy.itemId != null -> runCatching { browse.item(UUID.fromString(seedy.itemId.let(::dashed))) }.getOrNull()?.let { onTrailer(Trailer.Local(it)) }
                seedy.key != null -> onTrailer(Trailer.Url("https://www.youtube.com/watch?v=${seedy.key}"))
            }
        }
    }

    /** The target for the request sheet, from this series. */
    fun seasonsTarget(posterUrl: String?, backdropUrl: String?): SeasonsTarget? {
        val s = _state.value
        val item = s.item ?: return null
        if (s.tmdbId <= 0) return null
        return SeasonsTarget(
            tmdbId = s.tmdbId, title = item.name.orEmpty(), posterUrl = posterUrl, backdropUrl = backdropUrl,
            posterPath = s.seerr?.posterPath, backdropPath = s.seerr?.backdropPath, fromLibrary = true, onServer = s.onServer,
            meta = io.github.arkmind.seedy.core.format.join(item.yearRange, s.gaps.total.takeIf { it > 0 }?.let { tn(it, "{n} season", "{n} seasons") }, item.studios?.firstOrNull()?.name),
        )
    }

    companion object {
        private fun dashed(id: String): String =
            if (id.length == 32) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

        /** The web's useMissingSeasons: seasons Seerr knows that aren't on the server and aren't blocked. */
        fun gapsFor(seasons: List<BaseItemDto>, seerr: SeerrDetails?): Gaps {
            if (seerr == null) return Gaps()
            val today = LocalDate.now()
            val onServer = seasons.filter { !it.isVirtual && (it.indexNumber ?: 0) > 0 }.mapNotNull { it.indexNumber }.toSet()
            val known = seerr.seasons.orEmpty().filter { it.seasonNumber > 0 }
            val away = known.filter { it.seasonNumber !in onServer && it.status !in setOf("available", "partial", "blocklisted") }
            fun unaired(day: LocalDate?) = day == null || day.isAfter(today)
            val missing = away.map { s ->
                val day = parseDay(s.airDate)
                MissingSeason(s.seasonNumber, s.name.ifBlank { t("Season {n}", "n" to s.seasonNumber) }, if (unaired(day)) day?.year else null, s.status == "pending" || s.status == "processing")
            }
            val open = missing.filter { !it.requested }
            val requested = missing.filter { it.requested }
            val soon = away.filter { it.status != "pending" && it.status != "processing" && unaired(parseDay(it.airDate)) }
            val nums = open.map { it.n }
            val title = when {
                open.isEmpty() -> ""
                open.size == 1 -> t("{season} is missing", "season" to seasonList(nums))
                open.size <= 4 -> t("{seasons} are missing", "seasons" to seasonList(nums))
                else -> t("{n} seasons are missing", "n" to open.size)
            }
            val line = when {
                open.isEmpty() -> ""
                open.size == 1 -> t("{season} isn't on the server", "season" to seasonList(nums))
                open.size <= 4 -> t("{seasons} aren't on the server", "seasons" to seasonList(nums))
                else -> t("{n} seasons aren't on the server", "n" to open.size)
            }
            val soonDay = soon.singleOrNull()?.let { parseDay(it.airDate) }
            val sub = when {
                soon.size == 1 && soonDay != null -> t("{season} is announced for {year}", "season" to seasonList(listOf(soon[0].seasonNumber)), "year" to soonDay.year)
                soon.isNotEmpty() -> tn(soon.size, "{season} is announced", "{season} are announced", "season" to seasonList(soon.map { it.seasonNumber }))
                requested.isNotEmpty() -> tn(requested.size, "{season} is already requested", "{season} are already requested", "season" to seasonList(requested.map { it.n }))
                else -> ""
            }
            return Gaps(missing, nums, title, line, sub, known.size)
        }
    }
}
