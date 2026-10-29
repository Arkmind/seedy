package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.mobile.audio.LocalMiniBar
import io.github.arkmind.seedy.mobile.audio.MiniBar
import io.github.arkmind.seedy.mobile.audio.MiniBarActions
import io.github.arkmind.seedy.mobile.audio.miniBarSpace
import io.github.arkmind.seedy.mobile.music.AlbumActions
import io.github.arkmind.seedy.mobile.music.AlbumScreen
import io.github.arkmind.seedy.mobile.music.ArtistActions
import io.github.arkmind.seedy.mobile.music.ArtistScreen
import io.github.arkmind.seedy.mobile.music.LocalMusicMotion
import io.github.arkmind.seedy.mobile.music.MusicActions
import io.github.arkmind.seedy.mobile.music.MusicLibraryScreen
import io.github.arkmind.seedy.mobile.music.NowPlayingActions
import io.github.arkmind.seedy.mobile.music.NowPlayingScreen
import io.github.arkmind.seedy.mobile.music.QueueSheet
import io.github.arkmind.seedy.mobile.music.LyricsPanel
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.ui.components.CoverPalettes
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The music pages with the boards' data, at the boards' size (390×844 at 1×), the mini player and tab bar under them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class MusicShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, bars: Boolean = true, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true), LocalMusicMotion provides false, LocalMiniBar provides bars) {
                    if (!bars) Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) { content() }
                    else Column(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                        // The shell's frame (MobileApp WithTabBar): the page, the mini player over its foot, the tab bar.
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            content()
                            MiniBar(MusicSamples.audio, MusicSamples.miniTime, MiniBarActions(), Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
                        }
                        TabBar(Tab.entries, Tab.Libraries, {})
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun music() = shot("AndroidMusic") {
        MusicLibraryScreen(MusicSamples.library, MusicSamples.playing, MusicActions(), bottomPadding = miniBarSpace())
    }

    @Test
    fun album() = shot("AndroidMusicAlbum") {
        AlbumScreen(MusicSamples.albumState, MusicSamples.playing, CoverPalettes[0][1], AlbumActions(), bottomPadding = miniBarSpace())
    }

    @Test
    fun artist() = shot("AndroidMusicArtist") {
        ArtistScreen(MusicSamples.artistState, MusicSamples.playing, CoverPalettes[0], ArtistActions(), bottomPadding = miniBarSpace())
    }

    @Test
    fun homeMore() = shot("AndroidMusicHomeMore") {
        MusicLibraryScreen(
            MusicSamples.homeMore, MusicSamples.playing, MusicActions(), bottomPadding = miniBarSpace(),
            listState = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = 8),
        )
    }

    @Test
    fun albumsTab() = shot("AndroidMusicAlbums") {
        MusicLibraryScreen(MusicSamples.albumsTab, MusicSamples.playing, MusicActions(), bottomPadding = miniBarSpace())
    }

    @Test
    fun songsTab() = shot("AndroidMusicSongs") {
        MusicLibraryScreen(MusicSamples.songsTab, MusicSamples.playing, MusicActions(), bottomPadding = miniBarSpace())
    }

    @Test
    fun playlist() = shot("AndroidMusicPlaylist") {
        AlbumScreen(MusicSamples.playlistState, MusicSamples.playing, CoverPalettes[6][1], AlbumActions(), bottomPadding = miniBarSpace())
    }

    @Test
    fun queue() = shot("AndroidMusicQueue", bars = false) {
        NowPlayingScreen(MusicSamples.queued, MusicSamples.time, MusicSamples.lyrics, "WH-1000XM5", CoverPalettes[0][1], NowPlayingActions())
        QueueSheet(MusicSamples.queued, {}, {}, { _, _, _ -> }, {}, {})
    }

    @Test
    fun lyrics() = shot("AndroidMusicLyrics", bars = false) {
        LyricsPanel("Northline", "Northline · Ada Vale", MusicSamples.lyrics, MusicSamples.time.position, CoverPalettes[0][1], {}, {})
    }

    @Test
    fun player() = shot("AndroidMusicPlayer", bars = false) {
        NowPlayingScreen(MusicSamples.audio, MusicSamples.time, MusicSamples.lyrics, "WH-1000XM5", CoverPalettes[0][1], NowPlayingActions())
    }
}
