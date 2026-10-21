package io.github.arkmind.seedy.tv.settings

import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.shell.TvPlace
import io.github.arkmind.seedy.tv.shell.TvRail
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.currentSession

/** Settings on the TV (boards "Android TV · Settings · …"), with the rail lit on Settings. */
@Composable
fun TvSettingsRoute(appVersion: String) {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalTvNav.current
    val vm = viewModel { SettingsViewModel(core, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load("profile", "homescreen", "playback", "subtitles") }
    val places = if (LocalViewer.current.requests) TvPlace.entries else TvPlace.entries - TvPlace.Requests
    Box(Modifier.fillMaxSize()) {
        TvSettingsScreen(state, vm, appVersion, onLeave = nav::back)
        // The rail's places are the shell's: picking one goes back to it.
        TvRail(places, current = null, onPlace = { nav.back() }, onProfile = vm::switchProfile, onSettings = {}, settingsCurrent = true)
    }
}
