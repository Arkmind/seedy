package io.github.arkmind.seedy.tv.nav

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** The pages the TV app opens over its rail places. */
sealed interface TvRoute {
    data class Details(val id: UUID) : TvRoute
    data class Library(val id: UUID) : TvRoute
    data class Player(val id: UUID) : TvRoute
    data object Settings : TvRoute
    /** A title that's on Seerr but not on the server. */
    data class SeerrTitle(val mediaType: String, val tmdbId: Int) : TvRoute
}

/** Moves between pages; every TV screen reaches it through [LocalTvNav]. */
class TvNav(private val stack: SnapshotStateList<Any>) {
    fun go(route: TvRoute) {
        stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Opens a title where it belongs: an episode opens its show, a library or folder its grid, a channel plays. */
    fun open(item: BaseItemDto) = when (item.type) {
        BaseItemKind.EPISODE, BaseItemKind.SEASON -> go(TvRoute.Details(item.seriesId ?: item.id))
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW, BaseItemKind.FOLDER -> go(TvRoute.Library(item.id))
        BaseItemKind.TV_CHANNEL -> go(TvRoute.Player(item.id))
        else -> go(TvRoute.Details(item.id))
    }

    fun play(item: BaseItemDto) = go(TvRoute.Player(item.id))
}

val LocalTvNav = staticCompositionLocalOf<TvNav> { error("No TV navigation here") }
