package io.github.arkmind.seedy.core.mediarequests

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A card as a request made here left it: [status] requested, waiting, downloading or none; [by] who asked (null: the person). */
data class Asked(val status: String, val by: String?)

/**
 * Requests made, approved or dropped since the app started (web: media-parts askedStore). Cards follow them at once
 * (Requested, Waiting, or Request again) and the lists reload ([n] counts the changes), without fetching every row
 * again. Keyed by a book's work id, an album's id, or "artist:" + an artist's id for a whole discography.
 */
data class AskedState(val n: Int = 0, val cards: Map<String, Asked> = emptyMap()) {
    private fun hit(vararg keys: String?): Asked? = keys.firstNotNullOfOrNull { k -> k?.let { cards[it] } }

    /** An album as it stands after the requests made here; what's on the server stays so. */
    fun release(r: MusicRelease): MusicRelease {
        val h = hit(r.mbid, if (!r.upcoming && r.artistMbid != null) "artist:" + r.artistMbid else null) ?: return r
        return if (r.status == "server") r else r.copy(status = h.status, requestedBy = h.by)
    }

    fun book(b: BookCard): BookCard {
        val h = hit(b.workId) ?: return b
        return if (b.status == "server") b else b.copy(status = h.status, requestedBy = h.by)
    }
}

object MediaAsked {
    private val _state = MutableStateFlow(AskedState())
    val state: StateFlow<AskedState> = _state.asStateFlow()

    /** [r] was asked for, approved or dropped; [next] is the request after it (null once it's gone). */
    fun note(r: MediaRequest, next: MediaRequest? = r) {
        val status = when (next?.status) {
            null, "declined", "cancelled" -> "none"
            "pending" -> "waiting"
            "downloading" -> "downloading"
            else -> "requested"
        }
        val keys = when {
            !r.music -> listOf(r.foreignId)
            r.mode == "artist" -> listOf("artist:" + r.foreignId)
            else -> r.albums.map { it.mbid }
        }
        val card = Asked(status, if (r.requestedByMe) null else r.requestedBy)
        _state.update { s -> s.copy(n = s.n + 1, cards = s.cards + keys.associateWith { card }) }
    }
}
