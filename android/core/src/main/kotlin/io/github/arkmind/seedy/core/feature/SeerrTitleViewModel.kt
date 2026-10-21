package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.parseDay
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.RequestBody
import io.github.arkmind.seedy.core.seedy.SeerrApi
import io.github.arkmind.seedy.core.seedy.SeerrDetails
import io.github.arkmind.seedy.core.seedy.canRequest
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

/**
 * A title that isn't (fully) on the server, from Seerr (web: screens/browse/SeerrDetails): what it is, where it is
 * with Seerr, and Request. A movie is requested from here; a series opens the request-seasons sheet.
 */
class SeerrTitleViewModel(private val session: UserSession, val mediaType: String, val tmdbId: Int) : ViewModel() {
    private val seerr = SeerrApi(session.api)

    data class State(
        val details: SeerrDetails? = null,
        val loading: Boolean = true,
        /** Seerr answered 404: the link is old or TMDB dropped the title. */
        val missing: Boolean = false,
        /** "std" or "4k" while that request is on its way. */
        val busy: String? = null,
        val message: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val canRequest: Boolean get() = session.canRequest
    val allow4k: Boolean get() = session.seedy?.requests?.allow4k == true && canRequest
    val seerrUrl: String? get() = session.seedy?.requests?.seerrUrl?.trimEnd('/')?.let { "$it/$mediaType/$tmdbId" }

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { seerr.details(mediaType, tmdbId) } }
            _state.update {
                it.copy(
                    details = r.getOrNull() ?: it.details,
                    loading = false,
                    missing = (r.exceptionOrNull() as? InvalidStatusException)?.status == 404,
                )
            }
        }
    }

    /** Movies only: a series goes through the seasons sheet, so the person sees what they're asking for. */
    fun request(is4k: Boolean = false) {
        val d = _state.value.details ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = if (is4k) "4k" else "std") }
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { seerr.request(RequestBody(mediaType = d.mediaType, tmdbId = d.tmdbId, is4k = is4k)) } }
            val result = r.getOrNull()
            _state.update { s ->
                val details = s.details?.let { prev ->
                    when {
                        result == null -> prev
                        is4k -> prev.copy(status4k = result.status)
                        else -> prev.copy(status = result.status, requestedByMe = true, requestedBy = null)
                    }
                }
                val message = when {
                    result != null -> if (is4k) t("Requested {title} in 4K", "title" to d.title) else t("Requested {title}", "title" to d.title)
                    (r.exceptionOrNull() as? InvalidStatusException)?.status == 403 -> t("Your profile can't request titles")
                    else -> t("Couldn't request {title}", "title" to d.title)
                }
                s.copy(details = details, busy = null, message = message)
            }
        }
    }

    /** After the seasons sheet: those seasons are asked for (a partly available show stays partly available). */
    fun markRequested(result: SeasonsResult) {
        _state.update { s ->
            s.copy(
                details = s.details?.let { prev ->
                    val d = RequestSeasonsViewModel.markRequested(prev, result.seasons, result.is4k, result.status)
                    if (result.is4k) {
                        d.copy(status4k = if (prev.status4k == "partial") "partial" else result.status)
                    } else {
                        d.copy(status = if (prev.status == "partial") "partial" else result.status, requestedByMe = true, requestedBy = null)
                    }
                },
            )
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun requestable(status: String?) = status == null || status == "none" || status == "declined"

        fun requested(status: String?) = status == "pending" || status == "processing"

        /** The chip next to the type pill (web: STATUS_PILL); null for "none". */
        fun statusPill(status: String?): Pair<String, String>? = when (status) {
            "pending" -> t("Requested") to "pending"
            "processing" -> t("Approved") to "pending"
            "partial" -> t("Partly available") to "ok"
            "available" -> t("Available") to "ok"
            "declined" -> t("Declined") to "danger"
            "blocklisted" -> t("Blocked") to "danger"
            else -> null
        }

        /** "Requested by Mia · waiting for approval", "In your library"… */
        fun statusLine(d: SeerrDetails): String {
            val who = if (d.requestedByMe) t("you") else d.requestedBy
            return when (d.status) {
                "pending" -> if (who != null) t("Requested by {name} · waiting for approval", "name" to who) else t("Requested · waiting for approval")
                "processing" -> if (who != null) t("Requested by {name} · approved, on its way to the server", "name" to who) else t("Requested · approved, on its way to the server")
                "available" -> t("In your library")
                "partial" -> t("Some seasons are in your library")
                "declined" -> t("Declined in Seerr")
                "blocklisted" -> t("Blocked in Seerr, so it can't be requested")
                else -> t("Not requested yet")
            }
        }

        /** The info list under the hero (web: .seerr-info), label to value. */
        fun info(d: SeerrDetails): List<Pair<String, String>> {
            val tv = d.mediaType == "tv"
            val seasons = d.seasons.orEmpty().filter { it.seasonNumber > 0 }
            val episodes = seasons.sumOf { it.episodeCount }
            return buildList {
                val directors = d.crew.filter { it.job == "Director" }.map { it.name }
                val creators = d.crew.filter { it.job == "Creator" }.map { it.name }
                if (!tv && directors.isNotEmpty()) add(t("Directed by") to directors.take(2).joinToString(", "))
                if (tv && creators.isNotEmpty()) add(t("Created by") to creators.take(2).joinToString(", "))
                d.cast.take(3).map { it.name }.takeIf { it.isNotEmpty() }?.let { add(t("Starring") to it.joinToString(", ")) }
                if (tv && !d.networks.isNullOrEmpty()) add(t("Network") to d.networks.take(2).joinToString(", "))
                parseDay(d.releaseDate)?.let { add((if (tv) t("First aired") else t("Release date")) to fullDate(it)) }
                if (tv && seasons.isNotEmpty()) {
                    add(t("Seasons") to join(tn(seasons.size, "{n} season", "{n} seasons"), episodes.takeIf { it > 0 }?.let { tn(it, "{n} episode", "{n} episodes") }))
                }
                add(t("Status") to statusLine(d))
            }
        }
    }
}
