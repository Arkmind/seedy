package io.github.arkmind.seedy.mobile

import io.github.arkmind.seedy.mobile.player.SwipeSide
import io.github.arkmind.seedy.mobile.player.Swipe
import io.github.arkmind.seedy.mobile.player.StylePanel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.mobile.player.EpisodesPanel
import io.github.arkmind.seedy.mobile.player.PhonePlayerScreen
import io.github.arkmind.seedy.mobile.player.PlayerActions
import io.github.arkmind.seedy.mobile.player.SpeedPanel
import io.github.arkmind.seedy.mobile.player.StillWatchingScreen
import io.github.arkmind.seedy.player.StillWatchingAsk
import io.github.arkmind.seedy.ui.components.BoardScene
import androidx.compose.ui.geometry.Rect
import java.time.LocalTime
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

    private val actions = PlayerActions({}, {}, {}, {}, {}, {}, {}, {}, {})

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

    /** No board: Subtitle style, from the tracks panel (the Subtitles settings page in the panel's frame). */
    @Test fun style() = shot("AndroidPlayerStyle") { StylePanel(SettingsSamples.state, object : io.github.arkmind.seedy.core.settings.SettingsActions {}, {}, {}) }

    /** Board "Android · Still watching?": S3 E3 ended the third episode in a row, the stream stops in 14:32. */
    @Test
    fun stillWatching() {
        val finished = Samples.thePass.copy(indexNumber = 3)
        val next = Samples.thePass.copy(id = UUID.nameUUIDFromBytes("The Crossing".toByteArray()), name = "The Crossing", indexNumber = 4, runTimeTicks = 47 * 60 * 10_000_000L)
        compose.setContent {
            SeedyTheme {
                StillWatchingScreen(
                    item = finished, next = next, ask = StillWatchingAsk(3, 140, byTime = false), secondsLeft = 14 * 60 + 32, fraction = 1f,
                    onKeep = {}, onDone = {}, now = LocalTime.of(23, 34),
                    backdrop = { BoardScene(Modifier.fillMaxSize()) },
                    thumb = { BoardScene(Modifier.matchParentSize(), Rect(420f, 300f, 1320f, 806f)) },
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidPlayerStillWatching.png")
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
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(3.dp, androidx.compose.ui.Alignment.CenterVertically),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            for ((line, big) in listOf("Directed by" to false, "Ruth Calder" to true, "Music by" to false, "Lio Varga" to true)) {
                io.github.arkmind.seedy.ui.theme.SText(line, size = (if (big) 7 else 5).sp, weight = if (big) 700 else 500, tracking = .12f, uppercase = true, color = Color(0xFFC8C8C8))
            }
        }
    }

    /** No board (the web's PlayerEndMovie, laid out for a phone): the credits in the tile, the picks on the right. */
    @Test
    fun endOfMovie() {
        val runtime = 118 * 60_000L
        compose.setContent {
            SeedyTheme {
                io.github.arkmind.seedy.mobile.player.EndOfMovieScreen(
                    item = Samples.paperLanterns, picks = endPicks, positionMs = runtime - 3 * 60_000L + 20_000, durationMs = runtime,
                    left = 12, total = 15, onWatchCredits = {}, onBack = {}, onPlay = {},
                    video = { EndCredits() },
                    pickArt = { BoardScene(Modifier.matchParentSize()) },
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidPlayerEndMovie.png")
    }

    @Test
    fun gestures() {
        compose.setContent {
            SeedyTheme {
                PhonePlayerScreen(
                    state = state, controls = false, locked = false, seekBack = 10, seekForward = 30, actions = actions, onToggleControls = {},
                    video = { io.github.arkmind.seedy.ui.components.PassScene(Modifier.fillMaxSize()) {} },
                    swiping = Swipe(SwipeSide.Brightness, 0.6f),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/AndroidPlayerGestures.png")
    }
}
