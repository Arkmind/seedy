package io.github.arkmind.seedy.tv.player

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.compose.ContentFrame
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.player.PlayerViewModel
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.parts.TvButton
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/**
 * Boards "Android TV · Player" and its panels. With the controls hidden, OK brings them back and left and right
 * jump back and forward; the remote's media keys work at any time; Back closes a panel, then the controls, then the
 * player.
 */
@Composable
fun TvPlayerRoute(id: UUID) {
    val session = currentSession()
    val context = LocalContext.current
    val nav = LocalTvNav.current
    val vm = viewModel { PlayerViewModel(context, session, id) }
    val player = vm.controller
    val state by player.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val activity = LocalActivity.current

    var controls by remember { mutableStateOf(true) }
    var tool by remember { mutableStateOf<TvTool?>(null) }
    // The tool whose panel is open, so its button gets the remote back when the panel closes.
    var opener by remember { mutableStateOf<TvTool?>(null) }
    var page by remember { mutableStateOf(SettingsPage.Main) }
    var picture by remember { mutableStateOf(PictureSize.Fit) }
    var info by remember { mutableStateOf(false) }
    var touched by remember { mutableIntStateOf(0) }
    var seasonId by remember { mutableStateOf<UUID?>(null) }
    var seasonEpisodes by remember { mutableStateOf<List<BaseItemDto>?>(null) }
    val focus = remember { TvPlayerFocus() }
    val root = remember { FocusRequester() }

    // Videos don't play in the background.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    // The controls fade after 5 s of playing untouched.
    LaunchedEffect(controls, state.playing, tool, touched) {
        if (controls && state.playing && tool == null) {
            delay(5_000)
            controls = false
        }
    }
    // Where the remote lands: the button whose panel just closed; else Skip when there's something to skip, else
    // Pause; the hidden-controls catcher when the controls are away.
    LaunchedEffect(controls, tool) {
        when {
            tool != null -> Unit
            controls -> {
                val back = opener?.let { focus.tools.getValue(it) }
                opener = null
                if (back == null || !back.focusSoon()) (if (state.skip != null) focus.skip else focus.play).focusSoon()
            }
            else -> root.focusSoon()
        }
    }
    LaunchedEffect(state.item?.id) {
        seasonId = state.item?.seasonId
        seasonEpisodes = null
    }

    BackHandler {
        when {
            state.problem != null -> nav.back()
            // A settings page goes back to the settings list; Chapters opened from its own button just closes.
            tool == TvTool.Settings && page != SettingsPage.Main -> page = SettingsPage.Main
            tool != null -> tool = null
            // Settings · Remote › "Back button closes the player".
            controls && !player.settings.backClosesPlayer -> controls = false
            else -> nav.back()
        }
    }

    val subtitleSize = when (session.seedy?.prefs?.subtitles?.size) {
        "S" -> 32.bsp
        "L" -> 48.bsp
        "XL" -> 56.bsp
        else -> 40.bsp
    }

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                touched++
                when (e.key) {
                    Key.MediaPlayPause -> { player.playPause(); return@onPreviewKeyEvent true }
                    Key.MediaPlay -> { if (!state.playing) player.playPause(); return@onPreviewKeyEvent true }
                    Key.MediaPause -> { if (state.playing) player.playPause(); return@onPreviewKeyEvent true }
                    Key.MediaFastForward -> { player.seekBy(player.seekForwardSeconds * 1000L); return@onPreviewKeyEvent true }
                    Key.MediaRewind -> { player.seekBy(-player.seekBackSeconds * 1000L); return@onPreviewKeyEvent true }
                    Key.MediaNext -> { player.playNext(); return@onPreviewKeyEvent true }
                }
                if (controls || tool != null) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.DirectionUp, Key.DirectionDown -> controls = true
                    Key.DirectionLeft -> { player.seekBy(-player.seekBackSeconds * 1000L); controls = true }
                    Key.DirectionRight -> { player.seekBy(player.seekForwardSeconds * 1000L); controls = true }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
            .focusRequester(root)
            .focusable(),
    ) {
        TvPlayerScreen(
            state = state,
            controls = controls && tool == null && state.problem == null,
            seekBack = player.seekBackSeconds,
            seekForward = player.seekForwardSeconds,
            subtitleSize = subtitleSize,
            look = player.subtitleLook,
            panelOpen = tool != null,
            focus = focus,
            actions = TvPlayerActions(
                onPlayPause = player::playPause,
                onSeekBy = player::seekBy,
                onSkip = { player.skip(); scope.launch { focus.play.focusSoon() } },
                onNext = player::playNext,
                onTool = { t ->
                    page = if (t == TvTool.Chapters) SettingsPage.Chapters else SettingsPage.Main
                    opener = t
                    tool = t
                },
                onActivity = { touched++ },
            ),
            video = { ContentFrame(player = player.exo, modifier = Modifier.fillMaxSize(), contentScale = picture.scale) },
            panel = {
                when (tool) {
                    TvTool.Subtitles, TvTool.Audio -> TvTracksPanel(
                        state = state,
                        startOnAudio = tool == TvTool.Audio,
                        onAudio = player::selectAudio,
                        onSubtitle = player::selectSubtitle,
                        onOffset = player::setSubtitleOffset,
                    )
                    TvTool.Episodes -> TvEpisodesPanel(
                        state = state,
                        seasonId = seasonId,
                        episodes = seasonEpisodes ?: state.episodes,
                        onSeason = { s ->
                            seasonId = s.id
                            scope.launch { seasonEpisodes = player.season(s.id) }
                        },
                        onEpisode = { e -> tool = null; player.play(e) },
                    )
                    TvTool.Settings, TvTool.Chapters -> TvSettingsPanel(
                        state = state,
                        page = page,
                        picture = picture,
                        info = info,
                        onPage = { page = it },
                        onQuality = { player.setQuality(it); page = SettingsPage.Main },
                        onSpeed = { player.setSpeed(it); page = SettingsPage.Main },
                        onPicture = { picture = it; page = SettingsPage.Main },
                        onChapter = { player.seekTo(it); tool = null },
                        onInfo = { info = it },
                    )
                    null -> if (info && controls) TvPlaybackInfo(state)
                }
            },
        )
        state.problem?.let { Problem(it, onBack = { nav.back() }) }
    }
}

/** Why playback stopped, in words, and a way back. */
@Composable
private fun Problem(code: String, onBack: () -> Unit) {
    val (title, text) = when (code) {
        "NotAllowed" -> t("Not allowed on this profile") to t("Your profile isn't allowed to play this title. Ask the server's admin if that's unexpected.")
        "NoCompatibleStream" -> t("Can't play this here") to t("This device can't play the file and the server couldn't convert it.")
        "RateLimitExceeded" -> t("Server is busy") to t("Too many streams are playing on this server right now. Try again in a moment.")
        "network" -> t("Can't reach the server") to t("The stream stopped loading. Check your connection and try again.")
        "media" -> t("Playback stopped") to t("The video couldn't be decoded on this device.")
        "serverError" -> t("Couldn't start playback") to t("The server couldn't prepare this title for playback. Try again in a moment.")
        "liveUnavailable" -> t("This channel isn't available right now") to t("The server couldn't open the channel's stream. Try another channel or come back later.")
        else -> t("Couldn't start playback") to code
    }
    val back = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.width(900.bp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.bp)) {
            SText(title, size = 44.bsp, weight = 900, color = SeedyColors.White, align = TextAlign.Center)
            SText(text, size = 22.bsp, color = SeedyColors.Text2, align = TextAlign.Center, lineHeight = 1.45f)
            TvButton(t("Back"), onBack, Modifier.focusRequester(back), primary = true)
        }
    }
    LaunchedEffect(Unit) { back.focusSoon() }
}
