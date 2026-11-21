package io.github.arkmind.seedy.mobile.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.player.problemText
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.player.PlayerViewModel
import io.github.arkmind.seedy.player.StillWatchingStopMs
import io.github.arkmind.seedy.ui.components.VideoSpinner
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Boards "Android · Player" and its panels: plays [id] full screen, sideways. */
@OptIn(UnstableApi::class)
@Composable
fun PlayerRoute(id: UUID, startTicks: Long? = null, maxBitrate: Int? = null) {
    val session = currentSession()
    val context = LocalContext.current
    val nav = LocalMobileNav.current
    val vm = viewModel { PlayerViewModel(context, session, id, startTicks, maxBitrate) }
    val player = vm.controller
    val state by player.state.collectAsStateWithLifecycle()
    val core = checkNotNull(LocalCore.current)
    // This phone's settings as they change: the Subtitle style panel changes the look while the video plays.
    val device by core.device.flow.collectAsStateWithLifecycle()
    val settings = viewModel { SettingsViewModel(core, session) }
    val settingsState by settings.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as? ComponentActivity
    val scope = rememberCoroutineScope()

    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<PlayerPanel?>(null) }
    var touched by remember { mutableIntStateOf(0) }
    var pip by remember { mutableStateOf(false) }
    var seasonId by remember { mutableStateOf<UUID?>(null) }
    var seasonEpisodes by remember { mutableStateOf<List<BaseItemDto>?>(null) }

    FullScreenSideways(activity)
    // Settings · Gestures › swipe for brightness and volume; the screen's own brightness comes back on leaving.
    val swipeLevels = remember(activity) { if (player.settings.swipeControls) activity?.let(::PhoneSwipeLevels) else null }
    DisposableEffect(swipeLevels) { onDispose { swipeLevels?.restore() } }
    // Videos stop in the background (a picture-in-picture window keeps the app started until it's closed), unless
    // Settings · Playback › Keep the sound going with the screen off is on.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (!player.playsInBackground) player.pause() }
    // Settings · Playback › Picture in picture: going to the home screen while a video plays keeps it in a window.
    val autoPip = player.settings.pictureInPicture && state.playing
    DisposableEffect(activity, autoPip) {
        val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (android.os.Build.VERSION.SDK_INT >= 31) activity?.setPictureInPictureParams(params.setAutoEnterEnabled(autoPip).build())
        val leaving = Runnable { if (autoPip && android.os.Build.VERSION.SDK_INT < 31) runCatching { activity?.enterPictureInPictureMode(params.build()) } }
        activity?.addOnUserLeaveHintListener(leaving)
        onDispose {
            activity?.removeOnUserLeaveHintListener(leaving)
            if (android.os.Build.VERSION.SDK_INT >= 31) activity?.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { pip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }

    // The controls fade after 4 s of playing untouched (not while a panel is open).
    LaunchedEffect(controls, state.playing, panel, touched) {
        if (controls && state.playing && panel == null) {
            delay(4_000)
            controls = false
        }
    }
    LaunchedEffect(state.item?.id) {
        seasonId = state.item?.seasonId
        seasonEpisodes = null
    }
    // "Still watching?" covers the player: no panel stays open under it.
    LaunchedEffect(state.ask, state.endShown) { if (state.ask != null || state.endShown) panel = null }
    // "I’m done for now", Back to browse, Back: leaving from the credits counts the title as watched.
    val leave = { player.doneForNow(); nav.back() }
    // The video: full screen (without the black bars baked into its frame), or in the end-of-movie tile (a new surface
    // there, so it lays out afresh each time).
    val videoFrame: @Composable () -> Unit = { CroppedVideo(player.exo, state.crop.takeIf { !state.endShown && !pip }) }

    BackHandler {
        when {
            state.problem != null -> nav.back()
            state.ask != null || state.endShown -> leave()
            panel == PlayerPanel.Style -> panel = PlayerPanel.Tracks
            panel != null -> panel = null
            locked -> controls = true
            else -> nav.back()
        }
    }

    val subtitlePrefs = session.seedy?.prefs?.subtitles
    // Any touch on the player, or a key (the volume), says someone is there ("Are you still watching?"); watched,
    // never taken. The player holds the focus so the keys come through here.
    val keys = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(player) {
                awaitPointerEventScope {
                    while (true) {
                        if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) player.touched()
                    }
                }
            }
            .onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) player.touched(); false }
            .focusRequester(keys)
            .focusable(),
    ) {
        PhonePlayerScreen(
            state = state,
            controls = controls && !pip && panel == null && state.problem == null && !state.endShown,
            locked = locked,
            seekBack = player.seekBackSeconds,
            seekForward = player.seekForwardSeconds,
            subtitleSize = when (subtitlePrefs?.size) {
                "S" -> 15.sp
                "L" -> 22.sp
                "XL" -> 26.sp
                else -> 18.sp
            },
            lift = subtitlePrefs?.lift ?: true,
            look = player.subtitleLook(device),
            doubleTapSeek = player.settings.doubleTapSeek,
            swipeLevels = swipeLevels,
            actions = PlayerActions(
                onBack = { nav.back() },
                onPlayPause = { touched++; player.playPause() },
                onSeekBy = { touched++; player.seekBy(it) },
                onSeekTo = { touched++; player.seekTo(it) },
                onSkip = player::skip,
                onNext = player::playNext,
                onLock = { locked = !locked; controls = true; touched++ },
                onPip = {
                    activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
                },
                onPanel = { panel = it },
            ),
            onToggleControls = { controls = !controls; touched++ },
            // In a picture-in-picture window the movie stays full size.
            video = { if (!state.endShown || pip) videoFrame() },
            panel = {
                when (panel) {
                    PlayerPanel.Episodes -> EpisodesPanel(
                        state = state,
                        episodes = seasonEpisodes ?: state.episodes,
                        seasonId = seasonId,
                        onSeason = { s ->
                            seasonId = s.id
                            scope.launch { seasonEpisodes = player.season(s.id) }
                        },
                        onEpisode = { e -> panel = null; player.play(e) },
                        onClose = { panel = null },
                    )
                    PlayerPanel.Tracks -> TracksPanel(
                        state = state,
                        onAudio = player::selectAudio,
                        onSubtitle = player::selectSubtitle,
                        onOffset = player::setSubtitleOffset,
                        onStyle = { settings.load("subtitles"); panel = PlayerPanel.Style },
                        onClose = { panel = null },
                    )
                    PlayerPanel.Speed -> SpeedPanel(state.speed, onSpeed = { player.setSpeed(it); panel = null }, onClose = { panel = null })
                    PlayerPanel.Style -> StylePanel(settingsState, settings, onBack = { panel = PlayerPanel.Tracks }, onClose = { panel = null })
                    null -> Unit
                }
            },
        )

        // The picture held up (opening, the stream running dry, reconnecting): the web player's spinner, so a frozen
        // frame doesn't pass for a pause. Not over the controls' play button, nor over a panel.
        val controlsUp = controls && !pip && panel == null && state.problem == null && !state.endShown
        if (state.waiting && !controlsUp && panel == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { VideoSpinner(if (pip) 24.dp else 40.dp) }
        }
        state.problem?.let { problem -> Problem(problem, onBack = { nav.back() }) }
        val item = state.item
        if (state.endShown && item != null && !pip) {
            EndOfMovieScreen(
                item = item, picks = state.picks, positionMs = state.positionMs, durationMs = state.durationMs,
                left = state.pickLeft, total = player.pickSeconds,
                onWatchCredits = { if (state.ended) leave() else player.watchCredits() },
                onBack = leave,
                onPlay = player::playPick,
                video = videoFrame,
            )
        }
        val ask = state.ask
        val next = state.asked ?: state.next
        if (ask != null && item != null && next != null) {
            // Nobody answers: the player closes when the countdown ends, freeing the server.
            val left by produceState((StillWatchingStopMs / 1000).toInt(), ask) {
                val end = SystemClock.elapsedRealtime() + StillWatchingStopMs
                while (true) {
                    val ms = end - SystemClock.elapsedRealtime()
                    value = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
                    if (ms <= 0) break
                    delay(1000 - ms % 1000)
                }
                leave()
            }
            StillWatchingScreen(
                item = item, next = next, ask = ask, secondsLeft = left,
                fraction = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 1f,
                onKeep = player::keepWatching,
                onDone = leave,
            )
        }
    }
}

/** Sideways, edge to edge, system bars hidden and the screen kept on while the player is open. */
@Composable
private fun FullScreenSideways(activity: ComponentActivity?) {
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val before = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.requestedOrientation = before ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller?.show(WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Why playback stopped, in words (the web's copy), and a way back. */
@Composable
private fun Problem(code: String, onBack: () -> Unit) {
    val (title, text) = problemText(code)
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 20.sp, weight = 900, color = SeedyColors.White, align = TextAlign.Center)
            SText(text, size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center, lineHeight = 1.45f)
            PrimaryButton(t("Back"), onBack, Modifier.padding(top = 8.dp))
        }
    }
}
