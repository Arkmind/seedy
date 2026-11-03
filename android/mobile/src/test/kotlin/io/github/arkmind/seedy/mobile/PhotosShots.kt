package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.TabBar
import io.github.arkmind.seedy.mobile.photos.LocalPhotoArt
import io.github.arkmind.seedy.mobile.photos.PhotoDetails
import io.github.arkmind.seedy.mobile.photos.PhotoFolderActions
import io.github.arkmind.seedy.mobile.photos.PhotoFolderScreen
import io.github.arkmind.seedy.mobile.photos.PhotoViewerScreen
import io.github.arkmind.seedy.mobile.photos.PhotosActions
import io.github.arkmind.seedy.mobile.photos.PhotosScreen
import io.github.arkmind.seedy.mobile.photos.ViewerActions
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyTheme
import io.github.arkmind.seedy.ui.theme.Viewer
import io.github.arkmind.seedy.core.i18n.t
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The photos screens with the Photos boards' data (no Android boards: the web's, in the phone kit), at the phone boards'
 * size (390×844 at 1×), under the tab bar as the app shows them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-mdpi", sdk = [35])
class PhotosShots {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, tab: Tab? = Tab.Libraries, content: @Composable () -> Unit) {
        compose.setContent {
            SeedyTheme {
                CompositionLocalProvider(LocalViewer provides Viewer(name = "Arky", requests = true), LocalPhotoArt provides PhotosSamples.art) {
                    Column(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
                        Box(Modifier.weight(1f)) { content() }
                        if (tab != null) TabBar(Tab.entries, tab, {})
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** No board: the web's Photos · Timeline board on the phone. */
    @Test
    fun photos() = shot("AndroidPhotos") { PhotosScreen(PhotosSamples.library, PhotosActions(), bottomPadding = 0.dp) }

    /** No board: the Folders view. */
    @Test
    fun photosFolders() = shot("AndroidPhotosFolders") { PhotosScreen(PhotosSamples.folders, PhotosActions(), bottomPadding = 0.dp) }

    /** No board: the web's Photos · Folder board on the phone. */
    @Test
    fun photoFolder() = shot("AndroidPhotoFolder") { PhotoFolderScreen(PhotosSamples.folder, PhotoFolderActions(onDownload = {}), bottomPadding = 0.dp) }

    /** No board: the web's Photos · Viewer board on the phone. */
    @Test
    fun photoViewer() = shot("AndroidPhotoViewer", tab = null) {
        PhotoViewerScreen(PhotosSamples.viewer, 4, 226, playing = false, actions = ViewerActions(onDownload = {}))
    }

    /** No board: the viewer's Details sheet (the board's right column). */
    @Test
    fun photoDetails() = shot("AndroidPhotoDetails", tab = null) {
        val item = PhotosSamples.viewer[4]
        PhotoViewerScreen(PhotosSamples.viewer, 4, 226, playing = false, actions = ViewerActions(onDownload = {}))
        BottomSheet({}) {
            SheetHead(t("Details"))
            PhotoDetails(item, 18_400_000L to "image/jpeg", PhotosSamples.viewer[4], onMap = {}, onFolder = {})
        }
    }
}
