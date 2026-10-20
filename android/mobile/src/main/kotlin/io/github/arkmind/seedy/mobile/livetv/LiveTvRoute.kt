package io.github.arkmind.seedy.mobile.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.LiveTvViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.livetv.LiveChannel
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.parts.tabBarHeight
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import org.jellyfin.sdk.model.api.BaseItemDto

/** Boards "Android · Live TV" and "· Live TV guide", reached from the Libraries tab (which stays lit below). */
@Composable
fun LiveTvRoute() {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalMobileNav.current
    val vm = viewModel { LiveTvViewModel(session, core.store) }
    val state by vm.state.collectAsStateWithLifecycle()
    var view by rememberSaveable { mutableStateOf(LiveView.OnNow) }
    var selected by remember { mutableStateOf<Pair<LiveChannel, BaseItemDto>?>(null) }
    val watch: (LiveChannel) -> Unit = { c -> vm.watched(c.id); nav.go(Route.Player(c.id)) }
    LaunchedEffect(view) { if (view == LiveView.Recordings) vm.loadRecordings() }

    LiveTvScreen(
        state = state,
        view = view,
        selected = selected,
        onView = { view = it },
        onBack = { nav.back() },
        onChannelNumber = {},
        onFavoritesOnly = vm::setFavoritesOnly,
        onDay = vm::setDay,
        onSelect = { c, p -> selected = c to p },
        onRecord = vm::toggleRecord,
        onFavorite = vm::toggleFavorite,
        onWatch = watch,
        onPlayRecording = { nav.play(it) },
        onTab = { nav.back() },
    )
}

/** The Live TV page: title bar, the views' pills, the view itself and the tab bar. */
@Composable
fun LiveTvScreen(
    state: LiveTvViewModel.State,
    view: LiveView,
    selected: Pair<LiveChannel, BaseItemDto>?,
    onView: (LiveView) -> Unit,
    onBack: () -> Unit,
    onChannelNumber: () -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onDay: (java.time.LocalDate) -> Unit,
    onSelect: (LiveChannel, BaseItemDto) -> Unit,
    onRecord: (BaseItemDto) -> Unit,
    onFavorite: (LiveChannel) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onPlayRecording: (BaseItemDto) -> Unit,
    onTab: (Tab) -> Unit,
) {
    val viewer = LocalViewer.current
    val bottom = tabBarHeight()
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Column(Modifier.fillMaxSize()) {
            val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = top), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
                SText(t("Live TV"), Modifier.weight(1f).padding(start = 6.dp), size = 22.sp, weight = 900, color = SeedyColors.White)
                HeaderButton(AppIcons.ChannelNumber, onChannelNumber, stroke = 2.2f)
                HeaderButton(WebIcons.Cast, {})
            }
            LiveTabs(view, state.recordingNow > 0, onView)
            Box(Modifier.weight(1f)) {
                when (view) {
                    LiveView.OnNow -> OnNowView(state, bottom, onFavoritesOnly, onWatch)
                    LiveView.Guide -> GuideView(state, bottom, selected, onSelect, onDay, onFavoritesOnly, onRecord, onWatch)
                    LiveView.Channels -> ChannelsView(state, bottom, onFavorite, onWatch)
                    LiveView.Recordings -> RecordingsView(state, bottom, onPlayRecording)
                }
            }
        }
        val tabs = if (viewer.requests) Tab.entries else Tab.entries - Tab.Requests
        TabBar(tabs, Tab.Libraries, onTab, Modifier.align(Alignment.BottomCenter))
    }
}
