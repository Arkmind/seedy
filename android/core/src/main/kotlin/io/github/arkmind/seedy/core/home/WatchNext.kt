package io.github.arkmind.seedy.core.home

import io.github.arkmind.seedy.core.books.millisOf
import io.github.arkmind.seedy.core.browse.Browse
import io.github.arkmind.seedy.core.browse.continueWatching
import io.github.arkmind.seedy.core.data.io
import io.github.arkmind.seedy.core.session.UserSession
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID

/**
 * A title for the TV home screen's Continue watching row (Android TV's "Play next"). [next]: the next episode of a show
 * rather than something in progress. [lastEngaged] (epoch ms) orders the row: when the title, or the show's last
 * episode, was played.
 */
data class WatchNextEntry(val item: BaseItemDto, val next: Boolean, val lastEngaged: Long)

private val Videos = setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.MUSIC_VIDEO)

/**
 * What this person would find in the app's Continue watching ([inProgress] at most), then the next episode of up to
 * [upNext] other shows from Next up (following their Next up settings), most recent first.
 */
suspend fun UserSession.watchNext(inProgress: Int, upNext: Int): List<WatchNextEntry> = io {
    val resume = continueWatching(limit = inProgress, fields = Browse.CardFields).filter { it.type in Videos }
    val shows = resume.mapNotNull { it.seriesId }.toSet()
    val next = if (upNext <= 0) emptyList() else {
        runCatching { Home(this).nextUp(upNext + shows.size) }.getOrDefault(emptyList()).filter { it.seriesId !in shows }.take(upNext)
    }
    // A next episode hasn't been played: it takes its show's last play (one look at the episodes played lately).
    val showPlayed = if (next.isEmpty()) emptyMap() else lastPlayedShows()
    val entries = resume.map { WatchNextEntry(it, next = false, lastEngaged = millisOf(it.userData?.lastPlayedDate)) } +
        next.map { WatchNextEntry(it, next = true, lastEngaged = it.seriesId?.let(showPlayed::get) ?: 0L) }
    // Anything without a date goes after the rest, in the order the server gave.
    val floor = (entries.map { it.lastEngaged }.filter { it > 0 }.minOrNull() ?: System.currentTimeMillis()) - 60_000
    var missing = 0
    entries.map { if (it.lastEngaged > 0) it else it.copy(lastEngaged = floor - 60_000L * missing++) }.sortedByDescending { it.lastEngaged }
}

/** When each show had an episode played, from the 200 episodes played last. */
private suspend fun UserSession.lastPlayedShows(): Map<UUID, Long> = runCatching {
    api.libraryApi.getItems(
        userId = userId, recursive = true, includeItemTypes = listOf(BaseItemKind.EPISODE),
        sortBy = listOf(ItemSortBy.DATE_PLAYED), sortOrder = listOf(SortOrder.DESCENDING), limit = 200,
        enableUserData = true, enableImages = false, enableTotalRecordCount = false,
    ).content.items
}.getOrDefault(emptyList()).fold(mutableMapOf<UUID, Long>()) { map, ep ->
    val show = ep.seriesId
    val at = millisOf(ep.userData?.lastPlayedDate)
    if (show != null && at > 0 && show !in map) map[show] = at
    map
}
