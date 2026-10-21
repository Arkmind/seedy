package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.mobile.player.EpisodesPanel
import io.github.arkmind.seedy.mobile.player.PhonePlayerScreen
import io.github.arkmind.seedy.mobile.player.PlayerActions
import io.github.arkmind.seedy.mobile.player.SpeedPanel
import io.github.arkmind.seedy.mobile.player.TracksPanel
import io.github.arkmind.seedy.player.PlayerState
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import org.jellyfin.sdk.model.api.ChapterInfo
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime
import java.util.UUID

/** The phone player and its panels with the boards' data, at 844×390 (a phone on its side). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w844dp-h390dp-land-mdpi", sdk = [35])
class PlayerShots {
    @get:Rule val compose = createComposeRule()

    private val runtime = 3_130_000L
    private val ticks = runtime * 10_000
    private val item = Samples.thePass.copy(
        chapters = listOf(0.18, 0.37, 0.61, 0.82).map { ChapterInfo(startPositionTicks = (ticks * it).toLong(), imageDateModified = LocalDateTime.now()) },
    )

    private fun stream(index: Int, type: MediaStreamType, language: String, title: String? = null, channels: Int? = null, codec: String? = null, default: Boolean = false, sdh: Boolean = false) =
        MediaStream(
            index = index, type = type, language = language, title = title, channels = channels, codec = codec, isDefault = default,
            isHearingImpaired = sdh, isInterlaced = false, isForced = false, isOriginal = false, isExternal = false,
            isTextSubtitleStream = type == MediaStreamType.SUBTITLE, supportsExternalStream = true,
        )

    private val state = PlayerState(
        item = item, loading = false, playing = true, positionMs = 108_000, durationMs = runtime, bufferedMs = (runtime * 0.24).toLong(),
        segments = listOf(
            MediaSegmentDto(id = UUID.randomUUID(), itemId = item.id, type = MediaSegmentType.INTRO, startTicks = (ticks * 0.01).toLong(), endTicks = (ticks * 0.04).toLong()),
            MediaSegmentDto(id = UUID.randomUUID(), itemId = item.id, type = MediaSegmentType.OUTRO, startTicks = (ticks * 0.96).toLong(), endTicks = ticks),
        ),
        audio = listOf(
            stream(1, MediaStreamType.AUDIO, "eng", channels = 6, codec = "eac3", default = true).copy(isOriginal = true, audioSpatialFormat = org.jellyfin.sdk.model.api.AudioSpatialFormat.DOLBY_ATMOS),
            stream(2, MediaStreamType.AUDIO, "eng", title = "Audio description", channels = 2, codec = "aac"),
            stream(3, MediaStreamType.AUDIO, "fre", channels = 6, codec = "ac3"),
            stream(4, MediaStreamType.AUDIO, "spa", channels = 2, codec = "aac"),
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
        seasons = listOf(Samples.northbound.copy(id = UUID.nameUUIDFromBytes("s3".toByteArray()), name = "Season 3")),
        episodes = listOf(Samples.thePass),
    ).let { it.copy(skip = it.segments.first()) }

    private val actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {})

    private fun shot(name: String, panel: @Composable () -> Unit = {}) {
        compose.setContent {
            SeedyTheme {
                PhonePlayerScreen(
                    state = state, controls = name == "AndroidPlayer", locked = false, seekBack = 10, seekForward = 30, actions = actions,
                    onToggleControls = {}, video = { Box(Modifier.fillMaxSize().background(Color(0xFF1A232E))) }, panel = panel,
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test fun player() = shot("AndroidPlayer")

    @Test fun tracks() = shot("AndroidPlayerTracks") { TracksPanel(state, {}, {}, {}, {}, {}) }

    @Test fun speed() = shot("AndroidPlayerSpeed") { SpeedPanel(1f, {}, {}) }

    @Test fun episodes() = shot("AndroidPlayerEpisodes") { EpisodesPanel(state, state.episodes, state.seasons.first().id, {}, {}, {}) }
}
