package io.github.arkmind.seedy.mobile.player

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.compose.ContentFrame
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.player.PlayerViewModel
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.util.UUID

/** Boards "Android · Player" and its panels: plays [id] full screen, sideways. */
@Composable
fun PlayerRoute(id: UUID) {
    val session = currentSession()
    val context = LocalContext.current
    val nav = LocalMobileNav.current
    val vm = viewModel { PlayerViewModel(context, session, id) }
    val player = vm.controller
    val state by player.state.collectAsStateWithLifecycle()
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

    BackHandler {
        when {
            state.problem != null -> nav.back()
            panel != null -> panel = null
            locked -> controls = true
            else -> nav.back()
        }
    }

    val subtitlePrefs = session.seedy?.prefs?.subtitles
    PhonePlayerScreen(
        state = state,
        controls = controls && !pip && panel == null && state.problem == null,
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
        look = player.subtitleLook,
        doubleTapSeek = player.settings.doubleTapSeek,
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
            onCast = {},
            onPanel = { panel = it },
        ),
        onToggleControls = { controls = !controls; touched++ },
        video = { ContentFrame(player = player.exo, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit) },
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
                    onStyle = {},
                    onClose = { panel = null },
                )
                PlayerPanel.Speed -> SpeedPanel(state.speed, onSpeed = { player.setSpeed(it); panel = null }, onClose = { panel = null })
                null -> Unit
            }
        },
    )

    state.problem?.let { problem -> Problem(problem, onBack = { nav.back() }) }
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
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 20.sp, weight = 900, color = SeedyColors.White, align = TextAlign.Center)
            SText(text, size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center, lineHeight = 1.45f)
            PrimaryButton(t("Back"), onBack, Modifier.padding(top = 8.dp))
        }
    }
}
