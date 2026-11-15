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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import io.github.arkmind.seedy.tv.player.TvStillWatching
import io.github.arkmind.seedy.player.StillWatchingAsk
import io.github.arkmind.seedy.ui.components.BoardScene
import androidx.compose.ui.geometry.Rect
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
                    video = { Box(Modifier.fillMaxSize().background(Color(0xFF1A232E))) }, now = { java.time.LocalTime.of(21, 51) }, panelOpen = panel != null, panel = { panel?.invoke() },
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

    /** No board: a long-running show's twenty seasons scroll in the bar and leave the episodes where they are. */
    @Test fun episodesManySeasons() = shot("TVPlayerEpisodesManySeasons") {
        val many = state.copy(seasons = (1..20).map { n -> Samples.northbound.copy(id = UUID.nameUUIDFromBytes("Season $n".toByteArray()), name = "Season $n") })
        TvEpisodesPanel(many, many.seasons[2].id, listOf(Samples.thePass, Samples.glassHarbor), {}, {})
    }

    /** Board "Android TV · Still watching?": S3 E3 ended the third episode in a row, the stream stops in 14:32. */
    @Test
    fun stillWatching() {
        val finished = Samples.thePass.copy(indexNumber = 3)
        val next = Samples.thePass.copy(id = UUID.nameUUIDFromBytes("The Crossing".toByteArray()), name = "The Crossing", indexNumber = 4, runTimeTicks = 47 * 60 * 10_000_000L)
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme {
                TvStillWatching(
                    item = finished, next = next, ask = StillWatchingAsk(3, 140, byTime = false), secondsLeft = 14 * 60 + 32, fraction = 1f,
                    onKeep = {}, onDone = {}, now = java.time.LocalTime.of(23, 34),
                    backdrop = { BoardScene(Modifier.fillMaxSize()) },
                    thumb = { BoardScene(Modifier.matchParentSize(), Rect(420f, 300f, 1320f, 806f)) },
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/TVPlayerStillWatching.png")
    }


    /** The web board's end of Paper Lanterns: three minutes of credits left, Night Ferry playing in 12 s. */
    private val endPicks = listOf(
        Triple("Night Ferry", 2025, "Drama" to 123) to 7.8f,
        Triple("Halcyon", 2026, "Romance" to 101) to null,
        Triple("Riverlight", 2024, "Drama" to 112) to null,
    ).map { (t, score) ->
        org.jellyfin.sdk.model.api.BaseItemDto(
            id = UUID.nameUUIDFromBytes(t.first.toByteArray()), type = org.jellyfin.sdk.model.api.BaseItemKind.MOVIE, name = t.first,
            productionYear = t.second, genres = listOf(t.third.first), runTimeTicks = t.third.second * 60 * 10_000_000L, communityRating = score,
        )
    }

    @Composable
    private fun EndCredits() {
        androidx.compose.foundation.layout.Column(
            Modifier.fillMaxSize().background(Color.Black),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(5.dp, androidx.compose.ui.Alignment.CenterVertically),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            for ((line, big) in listOf("Directed by" to false, "Ruth Calder" to true, "Music by" to false, "Lio Varga" to true)) {
                io.github.arkmind.seedy.ui.theme.SText(line, size = (if (big) 8.5 else 6.5).sp, weight = if (big) 700 else 500, tracking = .12f, uppercase = true, color = Color(0xFFC8C8C8))
            }
        }
    }

    /** No board (the web's PlayerEndMovie at TV size): the credits in the tile, the remote on Play. */
    @Test
    fun endOfMovie() {
        val runtime = 118 * 60_000L
        compose.setContent {
            val inputMode = LocalInputModeManager.current
            SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
            SeedyTheme {
                io.github.arkmind.seedy.tv.player.TvEndOfMovie(
                    item = Samples.paperLanterns, picks = endPicks, positionMs = runtime - 3 * 60_000L + 20_000, durationMs = runtime,
                    left = 12, total = 15, onWatchCredits = {}, onBack = {}, onPlay = {},
                    video = { EndCredits() },
                    pickArt = { BoardScene(Modifier.matchParentSize()) },
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/TVPlayerEndMovie.png")
    }

    @Test fun settings() = shot("TVPlayerSettings") {
        TvSettingsPanel(state, SettingsPage.Main, PictureSize.Fit, false, {}, {}, {}, {}, {}, {})
    }
}
