package io.github.arkmind.seedy.mobile.nav

import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/** The pages the phone app opens over its tabs. */
sealed interface Route {
    data class Details(val id: UUID) : Route
    data class Library(val id: UUID) : Route
    data object Search : Route
    data object LiveTv : Route
    data class Player(val id: UUID) : Route
    /** A title that's on Seerr but not on the server (search, Discover…). */
    data class SeerrTitle(val mediaType: String, val tmdbId: Int) : Route
}

/** Moves between pages; every screen reaches it through [LocalMobileNav]. */
class MobileNav(private val stack: SnapshotStateList<Any>) {
    fun go(route: Route) {
        stack.add(route)
    }

    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    /** Opens a title where it belongs: an episode opens its show, a library or folder its grid, a channel plays. */
    fun open(item: BaseItemDto) = when (item.type) {
        BaseItemKind.EPISODE, BaseItemKind.SEASON -> go(Route.Details(item.seriesId ?: item.id))
        BaseItemKind.COLLECTION_FOLDER, BaseItemKind.USER_VIEW, BaseItemKind.FOLDER -> go(Route.Library(item.id))
        BaseItemKind.TV_CHANNEL -> go(Route.Player(item.id))
        else -> go(Route.Details(item.id))
    }

    fun play(item: BaseItemDto) = go(Route.Player(item.id))
}

val LocalMobileNav = staticCompositionLocalOf<MobileNav> { error("No phone navigation here") }
