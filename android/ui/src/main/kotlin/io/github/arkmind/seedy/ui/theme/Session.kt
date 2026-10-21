package io.github.arkmind.seedy.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.serialization.json.contentOrNull
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.arkmind.seedy.core.SeedyCore
import io.github.arkmind.seedy.core.session.UserSession

/** The signed-in session, for screens that make their own view models (null outside the signed-in app and in tests). */
val LocalSession = staticCompositionLocalOf<UserSession?> { null }

/** The app's core, for screens that need more than the session (servers, sign-out…). */
val LocalCore = staticCompositionLocalOf<SeedyCore?> { null }

/** The signed-in session; only call from screens shown while someone is signed in. */
@Composable
fun currentSession(): UserSession = checkNotNull(LocalSession.current) { "No signed-in session here" }

/** A prefs.custom flag of the person's (Settings · Display, Home screen), on when unset. */
private fun io.github.arkmind.seedy.core.seedy.SeedyMe?.flag(key: String): Boolean =
    (this?.prefs?.custom?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.let { it.content != "false" } ?: true

/**
 * Who's watching and how they like it, kept up to date as they change things in Settings (name, picture, backdrops,
 * episode pictures).
 */
@Composable
fun rememberViewer(session: UserSession, images: io.github.arkmind.seedy.core.format.Images): Viewer {
    val me by session.seedyFlow.collectAsState()
    val user by session.userFlow.collectAsState()
    return androidx.compose.runtime.remember(session, me, user) {
        Viewer(
            name = user.name.orEmpty(),
            imageUrl = images.user(session.userId, user.primaryImageTag),
            brand = me?.branding?.name ?: "Seedy",
            episodeImages = me.flag("home.episodeImages"),
            backdrops = me.flag("display.backdrops"),
            requests = session.hasRequests,
            images = images,
        )
    }
}

/** The person's accent (Settings · Display) over the server's (Seedy admin · Branding). */
@Composable
fun rememberAccent(session: UserSession?, serverAccent: String?): androidx.compose.ui.graphics.Color? {
    val me = session?.seedyFlow?.collectAsState()?.value
    val mine = (me?.prefs?.custom?.get("display.accent") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
    return parseHexColor(mine) ?: parseHexColor(me?.branding?.accent ?: serverAccent)
}
