package io.github.arkmind.seedy.ui.theme

import androidx.compose.runtime.Composable
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
