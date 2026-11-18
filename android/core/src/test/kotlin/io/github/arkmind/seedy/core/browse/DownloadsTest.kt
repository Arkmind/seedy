package io.github.arkmind.seedy.core.browse

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Where a saved movie or episode lands, and what the Download line says. */
class DownloadsTest {
    private fun video(type: BaseItemKind, name: String, path: String? = null, container: String? = null, series: String? = null) = BaseItemDto(
        id = UUID(0, 1), type = type, name = name, path = path, container = container, seriesName = series, mediaType = MediaType.VIDEO,
        locationType = LocationType.FILE_SYSTEM,
    )

    @Test
    fun movieKeepsTheServersFileName() {
        val m = video(BaseItemKind.MOVIE, "Sintel", path = "/media/movies/Sintel (2010)/Sintel (2010).mkv")
        assertEquals("Sintel (2010).mkv", Downloads.fileName(m))
        assertEquals("Sintel (2010).mkv", Downloads.path(m))
        assertEquals("Big Buck Bunny.mp4", Downloads.fileName(video(BaseItemKind.MOVIE, "x", path = "D:\\Films\\Big Buck Bunny.mp4")))
    }

    @Test
    fun withoutAPathTheTitleAndContainer() {
        assertEquals("Tears of Steel_ Director's Cut.mov", Downloads.fileName(video(BaseItemKind.MOVIE, "Tears of Steel: Director's Cut", container = "mov,mp4,m4a")))
        assertEquals("Sintel.mkv", Downloads.fileName(video(BaseItemKind.MOVIE, "Sintel")))
    }

    @Test
    fun episodesGoInTheirShowsFolder() {
        val e = video(BaseItemKind.EPISODE, "Thaw", path = "/tv/North/Season 1/North S01E01.mkv", series = "North: Again")
        assertEquals("North_ Again/North S01E01.mkv", Downloads.path(e))
    }

    @Test
    fun onlyVideosWithAFile() {
        assertTrue(Downloads.isVideoFile(video(BaseItemKind.MOVIE, "Sintel")))
        assertFalse(Downloads.isVideoFile(video(BaseItemKind.EPISODE, "Soon").copy(locationType = LocationType.VIRTUAL)))
        assertFalse(Downloads.isVideoFile(video(BaseItemKind.SEASON, "Season 1").copy(isFolder = true)))
        assertFalse(Downloads.isVideoFile(video(BaseItemKind.AUDIO, "Song").copy(mediaType = MediaType.AUDIO)))
    }

    @Test
    fun subNamesTheContainer() {
        assertEquals("MKV", Downloads.sub(video(BaseItemKind.MOVIE, "Sintel", container = "mkv,webm")))
        assertNull(Downloads.sub(video(BaseItemKind.MOVIE, "Sintel")))
    }
}
