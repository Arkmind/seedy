package io.github.arkmind.seedy.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arkmind.seedy.core.seedy.SeedyMe
import io.github.arkmind.seedy.core.seedy.logoUrl
import io.github.arkmind.seedy.core.session.SessionState
import io.github.arkmind.seedy.core.settings.SettingsState.Companion.Keys
import io.github.arkmind.seedy.ui.components.LocalBrandLogo
import kotlinx.serialization.json.JsonPrimitive
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
private fun SeedyMe?.flag(key: String): Boolean =
    (this?.prefs?.custom?.get(key) as? JsonPrimitive)?.let { it.content != "false" } ?: true

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

/** The person's accent (Settings · Display) over the server's (Seedy admin · Branding, else [serverAccent]). */
fun accentOf(me: SeedyMe?, serverAccent: String?): Color? {
    val mine = (me?.prefs?.custom?.get(Keys.Accent) as? JsonPrimitive)?.contentOrNull
    return parseHexColor(mine) ?: parseHexColor(me?.branding?.accent ?: serverAccent)
}

/** [accentOf] the signed-in person, kept up to date. */
@Composable
fun rememberAccent(session: UserSession?, serverAccent: String?): Color? = accentOf(session?.seedyFlow?.collectAsState()?.value, serverAccent)

/**
 * The session's look around either app: the accent (the person's over the server's) and the brand logo, provided over
 * the app's background; [content] draws the session's state.
 */
@Composable
fun SessionTheme(state: SessionState, content: @Composable BoxScope.() -> Unit) {
    val signedIn = (state as? SessionState.SignedIn)?.session
    val accent = when (state) {
        is SessionState.SignedIn -> rememberAccent(signedIn, state.session.server.seedy?.accent)
        is SessionState.SignedOut -> parseHexColor(state.server.seedy?.accent)
        else -> null
    }
    val me = signedIn?.seedyFlow?.collectAsStateWithLifecycle()?.value
    val logo = when (state) {
        is SessionState.SignedIn -> (me?.branding ?: state.session.server.seedy)?.logoUrl(state.session.server.address)
        is SessionState.SignedOut -> state.server.seedy?.logoUrl(state.server.address)
        else -> null
    }
    SeedyTheme(accent = accent ?: SeedyColors.Accent) {
        CompositionLocalProvider(LocalBrandLogo provides logo) {
            Box(Modifier.fillMaxSize().background(SeedyColors.Bg), content = content)
        }
    }
}
