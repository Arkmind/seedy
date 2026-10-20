package io.github.arkmind.seedy.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.arkmind.seedy.core.format.Images

/** Who is watching, as the screens need it: the header's avatar, the brand, a few profile settings, image addresses. */
data class Viewer(
    val name: String = "",
    val imageUrl: String? = null,
    val brand: String = "Seedy",
    /** Settings · Home screen › "Use episode pictures" (off: episodes show their show's artwork). */
    val episodeImages: Boolean = true,
    /** Requests are on: Seedy is installed and connected to Seerr. */
    val requests: Boolean = false,
    /** Null in previews and screenshot tests, which draw the artwork's placeholder. */
    val images: Images? = null,
)

val LocalViewer = staticCompositionLocalOf { Viewer() }
