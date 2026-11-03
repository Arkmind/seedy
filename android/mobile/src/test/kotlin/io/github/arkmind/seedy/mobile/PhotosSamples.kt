package io.github.arkmind.seedy.mobile

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import io.github.arkmind.seedy.core.photos.FolderInfo
import io.github.arkmind.seedy.core.photos.Memory
import io.github.arkmind.seedy.core.photos.PhotoFolderViewModel
import io.github.arkmind.seedy.core.photos.PhotosTab
import io.github.arkmind.seedy.core.photos.PhotosViewModel
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/** The Photos boards' sample data (web PhotosLibrary, PhotosAlbum, PhotosViewer), drawn the boards' way. */
object PhotosSamples {
    private val PP = listOf(
        listOf(0xFFF6B38A, 0xFFF3D6B5, 0xFFE9E2D6, 0xFF7A4B3A, 0xFF3C2A26, 0xFFFFF1D6), listOf(0xFF7FB6D9, 0xFFBFE0EF, 0xFFE8F4F7, 0xFF2F6F8F, 0xFF1C4A60, 0xFFFFFFFF),
        listOf(0xFF2A2F5A, 0xFF7C5A8C, 0xFFE09A8A, 0xFF1B1B2A, 0xFF0E0E18, 0xFFFFD9A0), listOf(0xFFA9C9A4, 0xFFDBE8CF, 0xFFEEF3E4, 0xFF3F6B45, 0xFF24402A, 0xFFFFFFFF),
        listOf(0xFFC9D8E8, 0xFFEEF3F8, 0xFFFFFFFF, 0xFF9FB2C6, 0xFF6E8398, 0xFFFFFFFF), listOf(0xFFF0C27A, 0xFFF6DFB0, 0xFFFBEED4, 0xFFC27C45, 0xFF8A4F2B, 0xFFFFF6E0),
        listOf(0xFF0D1630, 0xFF1C2A52, 0xFF3A4A78, 0xFF0A0F1F, 0xFF05070F, 0xFFF0F0F0), listOf(0xFF9FD4E6, 0xFFD6EEF2, 0xFFF6EFE0, 0xFFE8D3A8, 0xFF3F8FA8, 0xFFFFFFFF),
    )
    private val PG = listOf(
        listOf("M0 130 L60 100 L110 120 L170 80 L230 115 L300 90 V200 H0Z", "M0 160 L80 140 L150 158 L230 130 L300 150 V200 H0Z"),
        listOf("M0 120 Q75 100 150 125 T300 115 V200 H0Z", "M0 150 Q100 135 200 155 T300 148 V200 H0Z"),
        listOf("M0 140 H40 V100 H70 V130 H110 V80 H140 V120 H180 V95 H220 V135 H260 V110 H300 V200 H0Z", "M0 170 H300 V200 H0Z"),
        listOf("M0 110 L40 70 L80 115 L120 60 L160 110 L200 75 L240 115 L300 80 V200 H0Z", "M0 150 L300 140 V200 H0Z"),
    )
    private val arts = HashMap<UUID, Int>()
    private var n = 0

    /** Draws a sample photo: the boards' sky, sun and hills, cropped to the box ("slice"). */
    val art: @Composable (BaseItemDto, Modifier) -> Unit = { item, modifier ->
        val i = arts[item.id] ?: 0
        val c = PP[i % PP.size].map { Color(it) }
        val g = PG[(i * 3 + 1) % PG.size]
        val paths = remember(i) { g.map { PathParser().parsePathString(it).toPath() } }
        val sx = 60f + (i * 53) % 190
        val sy = 40f + (i * 17) % 40
        val sr = 10f + (i * 7) % 18
        val so = if (i % 3 == 0) 0f else 0.9f
        Canvas(modifier) {
            drawRect(Brush.verticalGradient(0f to c[0], 0.62f to c[1], 1f to c[2]))
            val k = max(size.width / 300f, size.height / 200f)
            clipRect {
                translate((size.width - 300f * k) / 2f, (size.height - 200f * k) / 2f) {
                    scale(k, k, pivot = Offset.Zero) {
                        drawCircle(c[5].copy(alpha = so), sr, Offset(sx, sy))
                        drawPath(paths[0], c[3])
                        drawPath(paths[1], c[4])
                    }
                }
            }
        }
    }

    private fun sdk(d: LocalDateTime): LocalDateTime = d.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

    fun photo(
        art: Int, ar: Float, taken: LocalDateTime, album: String? = null, fav: Boolean = false, dur: Int? = null,
        name: String = "Photo", path: String? = null, more: (BaseItemDto) -> BaseItemDto = { it },
    ): BaseItemDto {
        val id = UUID.fromString("00000000-0000-4000-8000-%012d".format(++n))
        arts[id] = art
        return more(
            BaseItemDto(
                id = id, type = if (dur != null) BaseItemKind.VIDEO else BaseItemKind.PHOTO, mediaType = if (dur != null) MediaType.VIDEO else MediaType.PHOTO,
                // As the SDK reads Jellyfin's dates: the camera's clock (stored as UTC) in this machine's time zone.
                name = name, width = (ar * 1000).roundToInt(), height = 1000, premiereDate = sdk(taken), dateCreated = sdk(taken), album = album,
                albumId = album?.let { UUID.nameUUIDFromBytes(it.toByteArray()) }, path = path, runTimeTicks = dur?.let { it * 10_000_000L },
                userData = UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = fav, played = false, key = id.toString(), itemId = id),
            ),
        )
    }

    private fun day(y: Int, m: Int, d: Int, album: String?, list: List<Triple<Int, Float, Map<String, Int>>>) =
        list.mapIndexed { k, (i, ar, x) -> photo(i, ar, LocalDateTime.of(y, m, d, 20 - k / 2, 59 - (k % 2) * 30), album, fav = x["fav"] == 1, dur = x["dur"]) }

    private fun p(i: Int, ar: Float, vararg x: Pair<String, Int>) = Triple(i, ar, mapOf(*x))

    val library: PhotosViewModel.State by lazy {
        val items = day(2026, 10, 17, "Home", listOf(p(0, 1.5f), p(3, 0.75f), p(6, 1.5f), p(9, 1.33f), p(12, 1.5f), p(15, 0.67f), p(18, 1.5f), p(21, 1.33f), p(24, 0.75f), p(27, 0.67f))) +
            day(2026, 10, 11, "Halden trip", listOf(p(7, 1.78f), p(10, 1.5f, "fav" to 1), p(13, 0.75f), p(16, 1.78f, "dur" to 42), p(19, 1.5f), p(22, 1.33f), p(25, 0.75f), p(28, 1.5f), p(31, 1.33f))) +
            day(2026, 10, 2, null, listOf(p(14, 1.33f), p(17, 1.5f), p(20, 0.8f), p(23, 1.5f), p(26, 1.33f))) +
            day(2026, 8, 16, "Lisbon, August 2026", listOf(p(2, 1.5f), p(5, 1.5f), p(8, 0.67f), p(11, 1.5f, "fav" to 1), p(14, 1.33f), p(17, 0.75f), p(20, 1.5f), p(23, 1.5f)))
        val memories = listOf(
            Triple(0, "Sintra", 2025 to 64), Triple(4, "First snow at Halden", 2023 to 28), Triple(2, "Rooftop, late summer", 2021 to 41), Triple(3, "Orchard weekend", 2018 to 19),
        ).map { (art, title, yc) ->
            val (y, count) = yc
            val list = List(count) { k -> photo(if (k == 0) art else art + k, 0.8f, LocalDateTime.of(y, 10, 18, 12, 0), title) }
            Memory("$y:$title", 2026 - y, title, LocalDate.of(y, 10, 18), list)
        }
        PhotosViewModel.State(name = "Photos", photos = 18402, videos = 640, tab = PhotosTab.Timeline, items = items, total = items.size, done = true, loading = false, memories = memories)
    }

    val folders: PhotosViewModel.State by lazy {
        val names = listOf("Lisbon, August 2026" to 0, "Halden trip" to 4, "Home" to 3, "Rooftop, late summer" to 2, "Orchard weekend" to 6, "First snow at Halden" to 1)
        val list = names.map { (name, _) -> BaseItemDto(id = UUID.nameUUIDFromBytes(name.toByteArray()), type = BaseItemKind.PHOTO_ALBUM, name = name) }
        val info = list.zip(names).associate { (f, nm) ->
            val covers = listOf(photo(nm.second, 1.5f, LocalDateTime.of(2026, 8, 9, 10, 0)), photo(nm.second + 7, 1.5f, LocalDateTime.of(2026, 8, 9, 11, 0)), photo(nm.second + 5, 1.5f, LocalDateTime.of(2026, 8, 9, 12, 0)))
            f.id to FolderInfo(f, 226, 212, 14, covers, LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 16))
        }
        library.copy(tab = PhotosTab.Folders, folders = list, folderInfo = info)
    }

    val lisbon = BaseItemDto(id = UUID.nameUUIDFromBytes("Lisbon".toByteArray()), type = BaseItemKind.PHOTO_ALBUM, name = "Lisbon, August 2026", path = "/media/photos/2026/Lisbon, August 2026")

    val folder: PhotoFolderViewModel.State by lazy {
        val items = day(2026, 8, 9, "Lisbon, August 2026", listOf(p(1, 1.5f), p(4, 0.67f), p(7, 1.5f), p(10, 1.5f, "fav" to 1), p(13, 1.78f), p(16, 0.75f), p(19, 1.78f), p(22, 1.5f), p(25, 1.5f), p(28, 1.33f))) +
            day(2026, 8, 10, "Lisbon, August 2026", listOf(p(8, 0.75f), p(11, 1.5f), p(14, 1.78f, "dur" to 66), p(17, 1.5f), p(20, 0.67f), p(23, 1.5f), p(26, 1.33f), p(29, 0.8f), p(32, 1.5f), p(35, 1.78f)))
        val covers = listOf(photo(0, 1.5f, LocalDateTime.of(2026, 8, 9, 8, 0)), photo(7, 1.5f, LocalDateTime.of(2026, 8, 9, 9, 0)), photo(5, 1.5f, LocalDateTime.of(2026, 8, 9, 10, 0)))
        val subs = listOf(
            BaseItemDto(id = UUID.nameUUIDFromBytes("Sintra".toByteArray()), type = BaseItemKind.PHOTO_ALBUM, name = "Sintra day trip"),
            BaseItemDto(id = UUID.nameUUIDFromBytes("Phone".toByteArray()), type = BaseItemKind.PHOTO_ALBUM, name = "Phone"),
        )
        PhotoFolderViewModel.State(
            item = lisbon, path = listOf(BaseItemDto(id = UUID.nameUUIDFromBytes("lib".toByteArray()), type = BaseItemKind.COLLECTION_FOLDER, name = "Photos")),
            info = FolderInfo(lisbon, 226, 212, 14, covers, LocalDate.of(2026, 8, 9), LocalDate.of(2026, 8, 16)),
            subfolders = subs, subCounts = mapOf(subs[0].id to 48, subs[1].id to 61), items = items, total = 226, done = true, loading = false,
        )
    }

    /** The viewer board: eleven photos, the fifth one open (DSCF4182.JPG). */
    val viewer: List<BaseItemDto> by lazy {
        val ars = listOf(1.5f, 0.67f, 1.5f, 1.5f, 1.5f, 0.75f, 1.33f, 1.5f, 0.67f, 1.5f, 1.5f)
        ars.mapIndexed { i, a ->
            if (i == 4) {
                photo(0, a, LocalDateTime.of(2026, 8, 9, 19, 42), "Lisbon, August 2026", name = "DSCF4182", path = "/media/photos/2026/Lisbon, August 2026/DSCF4182.JPG") {
                    it.copy(width = 6240, height = 4160, cameraMake = "FUJIFILM", cameraModel = "X100VI", focalLength = 23.0, aperture = 2.8, exposureTime = 0.004, isoSpeedRating = 160, latitude = 38.7105, longitude = -9.1335)
                }
            } else {
                photo(i * 3 + 1, a, LocalDateTime.of(2026, 8, 9, 19, 30 + i), "Lisbon, August 2026")
            }
        }
    }
}
