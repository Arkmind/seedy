package io.github.arkmind.seedy.mobile.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SearchViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.remote.NowOn
import io.github.arkmind.seedy.core.remote.RemoteControl
import io.github.arkmind.seedy.core.remote.VolumeKeys
import io.github.arkmind.seedy.core.remote.trackName
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.ui.components.LocalOverlayHost
import io.github.arkmind.seedy.ui.components.OverlayHost
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.CompositionLocalProvider

private enum class RemoteSheetKind { None, More, Subtitles, Audio, Picker }

private val Blank = IconDef(0f, emptyList())

/** The remote page for the screens picked in "Control a screen"; Minimise leaves them controlled. */
@Composable
fun RemoteRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val remote = remember(session) { RemoteControl.of(session) }
    DisposableEffect(remote) {
        val stop = remote.watch(1_000)
        onDispose { stop() }
    }
    val st by remote.state.collectAsStateWithLifecycle()
    // Every screen stopped being controlled (or closed): the remote goes.
    LaunchedEffect(st.controlled.isEmpty(), st.screens != null) { if (st.screens != null && st.controlled.isEmpty()) nav.back() }

    var mode by rememberSaveable { mutableStateOf(PadMode.Touchpad) }
    var typing by rememberSaveable { mutableStateOf(false) }
    var sheet by remember { mutableStateOf(RemoteSheetKind.None) }
    val screen = st.activeScreen

    // The clock the positions run on, ticking while something plays.
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(500)
        }
    }

    // A TV's remote: the phone's volume keys turn the TV up and down.
    val tv = screen?.isTv == true && !typing
    val latest by rememberUpdatedState(remote)
    DisposableEffect(tv) {
        if (tv) VolumeKeys.handler = { up -> if (up) latest.volumeUp() else latest.volumeDown(); true }
        onDispose { VolumeKeys.handler = null }
    }

    // Typing: the phone searches the same text, and sends it to the TV a moment after each change.
    val search = viewModel(key = "remote-search") { SearchViewModel(session) }
    val searchState by search.state.collectAsStateWithLifecycle()
    val sent = remember { MutableStateFlow("") }
    LaunchedEffect(Unit) { sent.drop(1).debounce(150).collect { remote.type(it) } }
    BackHandler(enabled = typing) { typing = false }
    LaunchedEffect(typing) { if (typing) { search.setText(screen?.text.orEmpty()); sent.value = screen?.text.orEmpty() } }

    val overlay = remember { OverlayHost() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalOverlayHost provides overlay) {
            RemotePage(
                state = st,
                now = now,
                mode = mode,
                typing = if (typing && screen != null) {
                    val r = searchState.results?.takeIf { searchState.text.isNotBlank() }
                    TypingUi(
                        text = searchState.text,
                        results = r?.let { (it.titles + it.episodes).sortedByDescending { h -> h.score }.map { h -> h.item }.take(5) },
                        focusedId = screen.focusedId,
                    )
                } else null,
                actions = RemoteActions(
                    onMinimise = { if (typing) typing = false else nav.back() },
                    onMore = { sheet = RemoteSheetKind.More },
                    onAdd = { sheet = RemoteSheetKind.Picker },
                    onTab = remote::select,
                    pad = PadActions(
                        onMove = { d ->
                            when (d) {
                                Dir.Up -> remote.up()
                                Dir.Down -> remote.down()
                                Dir.Left -> remote.left()
                                Dir.Right -> remote.right()
                            }
                        },
                        onSelect = { remote.select() },
                        onOptions = { remote.options() },
                        onMode = { mode = it },
                    ),
                    onBack = { remote.back() },
                    onHome = { remote.home() },
                    onType = { remote.startTyping(); typing = true },
                    onOptions = { remote.options() },
                    onVolumeUp = { remote.volumeUp() },
                    onVolumeDown = { remote.volumeDown() },
                    onPlayPause = remote::playPause,
                    onSeek = { remote.seekTo(it) },
                    onSkip = remote::skip,
                    onSubtitles = { sheet = RemoteSheetKind.Subtitles },
                    onAudio = { sheet = RemoteSheetKind.Audio },
                    onFullscreen = { remote.fullscreen() },
                    onMiniPlayer = { remote.miniPlayer() },
                    onVolume = { remote.setVolume(it) },
                    onText = { text -> search.setText(text); sent.value = text },
                    onShow = { item -> remote.show(item); typing = false },
                ),
            )
        }
        Toast(st.message, remote::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
        overlay.content?.invoke()
    }

    val n = screen?.now
    when (sheet) {
        RemoteSheetKind.None -> Unit
        RemoteSheetKind.Picker -> ControlScreenPicker(onControl = { sheet = RemoteSheetKind.None }, onDismiss = { sheet = RemoteSheetKind.None })
        RemoteSheetKind.More -> if (screen != null) {
            ItemMenuSheet(
                screen.name,
                buildList {
                    if (n != null) add(MenuEntry(RemoteIcons.Stop, t("Stop playing"), sub = n.title, run = { remote.stop() }))
                    add(MenuEntry(RemoteIcons.Unlink, t("Stop controlling {device}", "device" to screen.name), run = { remote.release(screen.deviceId) }))
                    if (st.controlled.size > 1) add(MenuEntry(RemoteIcons.Close, t("Stop controlling every screen"), run = { remote.releaseAll() }))
                },
            ) { sheet = RemoteSheetKind.None }
        }
        RemoteSheetKind.Subtitles -> if (n != null) TracksSheet(n, subtitles = true, onPick = remote::subtitle) { sheet = RemoteSheetKind.None }
        RemoteSheetKind.Audio -> if (n != null) TracksSheet(n, subtitles = false, onPick = remote::audio) { sheet = RemoteSheetKind.None }
    }
}

/** What the screen plays offers these subtitles (Off first) or audio tracks; the one on is ticked. */
@Composable
private fun TracksSheet(n: NowOn, subtitles: Boolean, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val entries = buildList {
        if (subtitles) {
            val on = (n.subtitleIndex ?: -1) < 0
            add(MenuEntry(if (on) RemoteIcons.Check else Blank, t("Off"), run = { onPick(-1) }))
            for (s in n.subtitles) add(MenuEntry(if (s.index == n.subtitleIndex) RemoteIcons.Check else Blank, trackName(s), run = { onPick(s.index) }))
        } else {
            for (a in n.audio) add(MenuEntry(if (a.index == n.audioIndex) RemoteIcons.Check else Blank, trackName(a), run = { onPick(a.index) }))
        }
    }
    // no-op: "Nothing to choose here" only says so; a tap closes the sheet.
    val none = MenuEntry(Blank, t("Nothing to choose here"), run = {})
    ItemMenuSheet(if (subtitles) t("Subtitles") else t("Audio"), entries.ifEmpty { listOf(none) }, onDismiss)
}
