package io.github.arkmind.seedy.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.player.PlayerState
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.player.PictureSize
import io.github.arkmind.seedy.tv.player.SettingsPage
import io.github.arkmind.seedy.tv.player.TvEpisodesPanel
import io.github.arkmind.seedy.tv.player.TvPlayerActions
import io.github.arkmind.seedy.tv.player.TvPlayerFocus
import io.github.arkmind.seedy.tv.player.TvPlayerScreen
import io.github.arkmind.seedy.tv.player.TvSettingsPanel
import io.github.arkmind.seedy.tv.player.TvTracksPanel
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.util.UUID

/** The TV player and its panels with the boards' data at 1920×1080 px. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w960dp-h540dp-land-television-xhdpi", sdk = [35])
class TvPlayerShots {
    @get:Rule val compose = createComposeRule()

    private val runtime = 3_130_000L
    private val ticks = runtime * 10_000
    private val item = Samples.thePass.copy(
        chapters = listOf(0.18, 0.37, 0.61, 0.82, 0.96).map { ChapterInfo(startPositionTicks = (ticks * it).toLong(), imageDateModified = LocalDateTime.now()) },
    )

    private fun stream(index: Int, type: MediaStreamType, language: String, title: String? = null, channels: Int? = null, default: Boolean = false, sdh: Boolean = false) =
        MediaStream(
            index = index, type = type, language = language, title = title, channels = channels, isDefault = default,
            isHearingImpaired = sdh, isInterlaced = false, isForced = false, isOriginal = false, isExternal = false,
            isTextSubtitleStream = type == MediaStreamType.SUBTITLE, supportsExternalStream = true,
        )

    private val state = PlayerState(
        item = item, loading = false, playing = true, positionMs = 108_000, durationMs = runtime, bufferedMs = (runtime * 0.24).toLong(),
        method = PlayMethod.DIRECT_PLAY, streamLabel = "Direct play · 4K HDR10 · 5.1",
        lines = listOf("We won't make the pass before dark."),
        segments = listOf(
            MediaSegmentDto(id = UUID.randomUUID(), itemId = item.id, type = MediaSegmentType.INTRO, startTicks = (ticks * 0.01).toLong(), endTicks = (ticks * 0.04).toLong()),
            MediaSegmentDto(id = UUID.randomUUID(), itemId = item.id, type = MediaSegmentType.OUTRO, startTicks = (ticks * 0.96).toLong(), endTicks = ticks),
        ),
        audio = listOf(
            stream(1, MediaStreamType.AUDIO, "eng", channels = 6, default = true).copy(isOriginal = true, audioSpatialFormat = org.jellyfin.sdk.model.api.AudioSpatialFormat.DOLBY_ATMOS),
            stream(2, MediaStreamType.AUDIO, "eng", title = "Audio description", channels = 2),
            stream(3, MediaStreamType.AUDIO, "fre", channels = 6),
            stream(4, MediaStreamType.AUDIO, "spa", channels = 2),
        ),
        audioIndex = 1,
        subtitles = listOf(
            stream(5, MediaStreamType.SUBTITLE, "eng", default = true, sdh = true),
            stream(6, MediaStreamType.SUBTITLE, "eng"),
            stream(7, MediaStreamType.SUBTITLE, "fre"),
            stream(8, MediaStreamType.SUBTITLE, "spa"),
        ),
        subtitleIndex = 5,
        subtitleOffsetMs = 300,
        next = Samples.glassHarbor,
        seasons = listOf("Season 1", "Season 2", "Season 3", "Specials").map { Samples.northbound.copy(id = UUID.nameUUIDFromBytes(it.toByteArray()), name = it) },
        episodes = listOf(Samples.thePass),
    ).let { it.copy(skip = it.segments.first()) }

    private val actions = TvPlayerActions({}, {}, {}, {}, {}, {})

    private fun shot(name: String, panel: (@Composable () -> Unit)? = null) {
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            val focus = remember { TvPlayerFocus() }
            SeedyTheme {
                TvPlayerScreen(
                    state = state, controls = panel == null, seekBack = 10, seekForward = 30, actions = actions, focus = focus,
                    video = { Box(Modifier.fillMaxSize().background(Color(0xFF1A232E))) }, panelOpen = panel != null, panel = { panel?.invoke() },
                )
            }
            if (panel == null) LaunchedEffect(Unit) { focus.skip.focusSoon() }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun player() = shot("TVPlayer")

    @Test fun tracks() = shot("TVPlayerTracks") { TvTracksPanel(state, startOnAudio = false, onAudio = {}, onSubtitle = {}, onOffset = {}) }

    @Test fun episodes() = shot("TVPlayerEpisodes") { TvEpisodesPanel(state, state.seasons[2].id, listOf(Samples.thePass, Samples.glassHarbor), {}, {}) }

    @Test fun settings() = shot("TVPlayerSettings") {
        TvSettingsPanel(state, SettingsPage.Main, PictureSize.Fit, false, {}, {}, {}, {}, {}, {})
    }
}
