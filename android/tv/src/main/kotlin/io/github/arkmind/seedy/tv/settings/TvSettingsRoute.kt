package io.github.arkmind.seedy.tv.settings

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.tv.shell.tvPlaces
import io.github.arkmind.seedy.ui.components.LocalOverlayHost
import io.github.arkmind.seedy.ui.components.OverlayHost
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession

/** Settings on the TV (boards "Android TV · Settings · …"), with the rail lit on Settings. */
@Composable
fun TvSettingsRoute(appVersion: String, onPlace: (TvPlace) -> Unit = {}) {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalTvNav.current
    val vm = viewModel { SettingsViewModel(core, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load("profile", "homescreen", "playback", "subtitles") }
    val places = tvPlaces(LocalViewer.current.requests)
    // The page's panels (Ask “Are you still watching?”) are drawn over the rail too.
    val overlay = remember { OverlayHost() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalOverlayHost provides overlay) {
            TvSettingsScreen(state, vm, appVersion, onLeave = nav::back)
        }
        // The rail's places are the shell's: picking one leaves Settings for it.
        TvRail(places, current = null, onPlace = onPlace, onProfile = vm::switchProfile, onSettings = {}, settingsCurrent = true) // no-op: already on Settings
        overlay.content?.invoke()
    }
}
