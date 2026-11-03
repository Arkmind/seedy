package io.github.arkmind.seedy.core.photos

import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.post
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.yearApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Photos libraries (the web's screens/photos/api.ts). Jellyfin 12 keeps photos in "Home videos and photos" libraries
 * (the old photos type still shows on upgraded servers): Photo and Video items in PhotoAlbum folders (a folder with
 * photos directly in it) and plain folders. A photo carries what its EXIF says: date taken (premiere date, also the
 * date created), size, camera, exposure and GPS.
 */

fun isPhotoLibrary(type: CollectionType?): Boolean = type == CollectionType.PHOTOS || type == CollectionType.HOMEVIDEOS

val MediaTypes = listOf(BaseItemKind.PHOTO, BaseItemKind.VIDEO)
val FolderTypes = listOf(BaseItemKind.PHOTO_ALBUM, BaseItemKind.FOLDER)
val PhotoFields = listOf(
    ItemFields.DATE_CREATED, ItemFields.PRIMARY_IMAGE_ASPECT_RATIO, ItemFields.PATH, ItemFields.MEDIA_SOURCES, ItemFields.WIDTH, ItemFields.HEIGHT,
)

val BaseItemDto.isVideo: Boolean
    get() = type == BaseItemKind.VIDEO || mediaType == MediaType.VIDEO || type == BaseItemKind.MOVIE || type == BaseItemKind.MUSIC_VIDEO

/** Width over height, kept between 0.5 and 3 so panoramas and slivers stay readable in a row. */
fun ratioOf(item: BaseItemDto): Float {
    var w = item.width
    var h = item.height
    if (w == null || h == null || w == 0 || h == 0) {
        val v = (item.mediaStreams ?: item.mediaSources?.firstOrNull()?.mediaStreams)?.firstOrNull { it.type == org.jellyfin.sdk.model.api.MediaStreamType.VIDEO }
        w = v?.width
        h = v?.height
    }
    val r = if (w != null && h != null && w > 0 && h > 0) w.toFloat() / h else (item.primaryImageAspectRatio?.toFloat() ?: 1.5f)
    return r.coerceIn(0.5f, 3f)
}

/** When it was taken: the EXIF date (Jellyfin keeps the camera's clock as if it were UTC), else when the file was saved. */
fun takenAt(item: BaseItemDto): LocalDateTime? = item.premiereDate ?: item.dateCreated

/* ───────── Grouping ───────── */

/** One day of photos; [offset] is its first photo's place in the whole list. */
data class PhotoDay(val date: LocalDate, val items: List<BaseItemDto>, val offset: Int)
data class PhotoMonth(val year: Int, val month: Int, val days: List<PhotoDay>)

fun groupByDay(items: List<BaseItemDto>): List<PhotoMonth> {
    val months = mutableListOf<PhotoMonth>()
    var days = mutableListOf<PhotoDay>()
    var cur: Pair<Int, Int>? = null
    var day: LocalDate? = null
    var bucket = mutableListOf<BaseItemDto>()
    var start = 0
    fun closeDay() { if (day != null && bucket.isNotEmpty()) days.add(PhotoDay(day!!, bucket, start)) }
    fun closeMonth() { closeDay(); if (cur != null && days.isNotEmpty()) months.add(PhotoMonth(cur!!.first, cur!!.second, days)) }
    items.forEachIndexed { i, it ->
        val d = takenAt(it)?.toLocalDate() ?: LocalDate.of(1970, 1, 1)
        val m = d.year to d.monthValue
        if (m != cur) { closeMonth(); cur = m; days = mutableListOf(); day = null; bucket = mutableListOf() }
        if (d != day) { closeDay(); day = d; bucket = mutableListOf(); start = i }
        bucket.add(it)
    }
    closeMonth()
    return months
}

/* ───────── Words ───────── */

/** "14 photos", "9 photos, 1 video". */
fun countsLabel(photos: Int, videos: Int): String = buildList {
    if (photos > 0 || videos == 0) add(tn(photos, "{n} photo", "{n} photos"))
    if (videos > 0) add(tn(videos, "{n} video", "{n} videos"))
}.joinToString(", ")

/** "18,402 photos · 640 videos". */
fun countsLine(photos: Int, videos: Int): String = buildList {
    if (photos > 0 || videos == 0) add(tn(photos, "{n} photo", "{n} photos"))
    if (videos > 0) add(tn(videos, "{n} video", "{n} videos"))
}.joinToString(" · ")

/** "14 photos · Home": the counts, then the folder most of the day is in (unless it's the page's own). */
fun daySub(day: PhotoDay, own: String? = null): String {
    val photos = day.items.count { !it.isVideo }
    val counts = countsLabel(photos, day.items.size - photos)
    val top = day.items.mapNotNull { it.album?.takeIf { a -> a != own } }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    return if (top != null) "$counts · $top" else counts
}

/** "Sat 17 Oct". */
fun dayLabel(d: LocalDate): String = weekdayDate(d)

private fun cap(s: String) = s.replaceFirstChar { it.titlecase(I18n.locale) }

/** "October 2026". */
fun monthTitle(year: Int, month: Int): String = cap(java.time.Month.of(month).getDisplayName(TextStyle.FULL, I18n.locale)) + " $year"

/** "Sun 9 August 2026". */
fun longDay(d: LocalDate): String {
    val wd = d.dayOfWeek.getDisplayName(TextStyle.SHORT, I18n.locale).removeSuffix(".")
    val m = d.month.getDisplayName(TextStyle.FULL, I18n.locale)
    return cap(if (I18n.lang == Lang.Fr) "$wd. ${d.dayOfMonth} $m ${d.year}" else "$wd ${d.dayOfMonth} $m ${d.year}")
}

/** "19:42". */
fun timeOfDay(d: LocalDateTime): String = "%02d:%02d".format(d.hour, d.minute)

/** "9 to 16 August 2026", "28 July to 3 August 2026". */
fun dateRange(a: LocalDate?, b: LocalDate?): String {
    if (a == null || b == null) return ""
    val (from, to) = if (a <= b) a to b else b to a
    fun m(d: LocalDate) = d.month.getDisplayName(TextStyle.FULL, I18n.locale)
    val full = { d: LocalDate -> "${d.dayOfMonth} ${m(d)} ${d.year}" }
    if (from == to) return full(from)
    if (from.year != to.year) return t("{from} to {to}", "from" to full(from), "to" to full(to))
    if (from.month != to.month) return t("{from} to {to}", "from" to "${from.dayOfMonth} ${m(from)}", "to" to full(to))
    return t("{from} to {to}", "from" to "${from.dayOfMonth}", "to" to full(to))
}

fun yearsAgo(n: Int): String = tn(n, "{n} year ago", "{n} years ago")

/** "Fujifilm X100VI", "Nikon D4": the model usually repeats the make, else both. */
fun cameraName(item: BaseItemDto): String {
    val make = item.cameraMake.orEmpty()
        .replace(Regex("\\s+(CORPORATION|Corporation|Camera AG|CORP\\.?|Co\\.,? ?Ltd\\.?|IMAGING CORP\\.?|OPTICAL CO\\.,LTD)$", RegexOption.IGNORE_CASE), "").trim()
    val model = item.cameraModel.orEmpty().trim()
    if (model.isEmpty()) return tidy(make)
    if (make.isEmpty() || model.lowercase().startsWith(make.lowercase().split(' ').first())) return tidy(model)
    return "${tidy(make)} $model"
}

private fun tidy(s: String) = s.split(' ').joinToString(" ") { w ->
    if (w.length > 3 && w.all { it in 'A'..'Z' }) w.first() + w.substring(1).lowercase() else w
}

/** "23 mm · f/2.8 · 1/250 s · ISO 160". */
fun exposureLine(item: BaseItemDto): String = buildList {
    item.focalLength?.takeIf { it > 0 }?.let { add("${it.roundToInt()} mm") }
    item.aperture?.takeIf { it > 0 }?.let { add("f/" + trim1(it)) }
    item.exposureTime?.takeIf { it > 0 }?.let { add(if (it >= 1) "${trim1(it)} s" else "1/${(1 / it).roundToInt()} s") }
    item.isoSpeedRating?.takeIf { it > 0 }?.let { add("ISO $it") }
}.joinToString(" · ")

private fun trim1(v: Double): String = "%.1f".format(Locale.US, v).removeSuffix(".0")

/** "38.7105° N, 9.1335° W". */
fun coordsLabel(lat: Double, lon: Double): String =
    "%.4f° %s, %.4f° %s".format(Locale.US, abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W")

fun hasPlace(item: BaseItemDto): Boolean {
    val lat = item.latitude ?: return false
    val lon = item.longitude ?: return false
    return !(lat == 0.0 && lon == 0.0)
}

/** "18.4 MB". */
fun bytesLabel(n: Long?): String {
    if (n == null || n <= 0) return ""
    // French writes octets: "18,4 Mo".
    val units = if (I18n.lang == Lang.Fr) listOf("o", "ko", "Mo", "Go", "To") else listOf("B", "KB", "MB", "GB", "TB")
    var v = n.toDouble()
    var i = 0
    while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
    return (if (v >= 100 || i == 0) "${v.roundToInt()}" else "%.1f".format(I18n.locale, v)) + " " + units[i]
}

fun fileName(item: BaseItemDto): String = item.path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotEmpty() } ?: item.name.orEmpty()

/** "JPEG", "HEIC", "MP4". */
fun formatName(item: BaseItemDto, contentType: String? = null): String {
    val ext = item.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
    val map = mapOf("jpg" to "JPEG", "jpeg" to "JPEG", "png" to "PNG", "heic" to "HEIC", "heif" to "HEIF", "webp" to "WebP", "gif" to "GIF",
        "tif" to "TIFF", "tiff" to "TIFF", "avif" to "AVIF", "dng" to "DNG", "mp4" to "MP4", "mov" to "MOV", "mkv" to "MKV")
    return map[ext] ?: contentType?.substringAfter('/')?.uppercase() ?: ext.uppercase()
}

/* ───────── Data ───────── */

/** "On this day": photos from around today's date in an earlier year, one folder of them. */
data class Memory(val key: String, val years: Int, val title: String, val date: LocalDate, val items: List<BaseItemDto>)

/** A folder's counts, first photos (its cover) and dates. */
data class FolderInfo(val item: BaseItemDto, val count: Int, val photos: Int, val videos: Int, val covers: List<BaseItemDto>, val from: LocalDate?, val to: LocalDate?)

/** The photos calls (on the IO dispatcher) and image addresses. */
class PhotosApi(private val session: UserSession) {
    private val api get() = session.api
    private val userId get() = session.userId
    private val base get() = api.baseUrl.orEmpty().trimEnd('/')
    private val token get() = URLEncoder.encode(api.accessToken.orEmpty(), "UTF-8")

    /** A tile: the photo scaled to cover [h]×(h × its ratio) pixels. */
    fun thumbUrl(item: BaseItemDto, h: Int): String? {
        val tag = item.imageTags?.get(ImageType.PRIMARY) ?: return null
        val w = (h * ratioOf(item)).roundToInt()
        return "$base/Items/${item.id}/Images/Primary?tag=$tag&fillWidth=$w&fillHeight=$h&quality=85"
    }

    /** The viewer: the photo at screen size, or at its full size when [full] (zoomed in). */
    fun screenUrl(item: BaseItemDto, w: Int, h: Int, full: Boolean = false): String? {
        val tag = item.imageTags?.get(ImageType.PRIMARY) ?: return null
        if (full && item.width != null) return "$base/Items/${item.id}/Images/Primary?tag=$tag&maxWidth=${item.width}&quality=95"
        return "$base/Items/${item.id}/Images/Primary?tag=$tag&maxWidth=${minOf(w, item.width ?: w)}&maxHeight=${minOf(h, item.height ?: h)}&quality=90"
    }

    fun downloadUrl(id: UUID): String = "$base/Items/$id/Download?ApiKey=$token"

    suspend fun media(
        parentId: UUID, start: Int = 0, limit: Int? = 100, types: List<BaseItemKind> = MediaTypes, favorite: Boolean? = null,
        oldestFirst: Boolean = false, recursive: Boolean = true, byName: Boolean = false, random: Boolean = false,
        minDate: LocalDateTime? = null, maxDate: LocalDateTime? = null,
    ): BaseItemDtoQueryResult = io {
        val sort = when {
            random -> listOf(ItemSortBy.RANDOM)
            byName -> listOf(ItemSortBy.SORT_NAME)
            else -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)
        }
        val order = if (oldestFirst || byName) SortOrder.ASCENDING else SortOrder.DESCENDING
        api.libraryApi.getItems(
            userId = userId, parentId = parentId, recursive = recursive, includeItemTypes = types, isFavorite = favorite,
            sortBy = sort, sortOrder = List(sort.size) { order }, startIndex = start, limit = limit, fields = PhotoFields,
            enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1, enableUserData = true, enableTotalRecordCount = true,
            minPremiereDate = minDate, maxPremiereDate = maxDate,
        ).content
    }

    /** Looks for new and changed files in a library or folder (admins). */
    suspend fun scan(id: UUID) = io {
        api.post<Unit>(
            "/Items/$id/Refresh",
            queryParameters = mapOf("Recursive" to true, "MetadataRefreshMode" to "Default", "ImageRefreshMode" to "Default", "ReplaceAllMetadata" to false, "ReplaceAllImages" to false),
        )
        Unit
    }

    suspend fun count(parentId: UUID, types: List<BaseItemKind>, recursive: Boolean = true): Int = io {
        api.libraryApi.getItems(userId = userId, parentId = parentId, recursive = recursive, includeItemTypes = types, limit = 0, enableTotalRecordCount = true)
            .content.totalRecordCount
    }

    suspend fun item(id: UUID): BaseItemDto = io { api.libraryApi.getItem(itemId = id, userId = userId).content }

    suspend fun setFavorite(id: UUID, on: Boolean) = io {
        if (on) api.userDataApi.markFavoriteItem(itemId = id, userId = userId) else api.userDataApi.unmarkFavoriteItem(itemId = id, userId = userId)
        Unit
    }

    suspend fun years(parentId: UUID): List<Int> = io {
        api.yearApi.getYears(parentId = parentId, userId = userId, recursive = true, includeItemTypes = MediaTypes, enableImages = false)
            .content.items.mapNotNull { it.name?.toIntOrNull() }.filter { it > 1800 }.sortedDescending()
    }

    /** Photos taken within [days] days of [today]'s date in earlier years: one memory per year and folder, newest first. */
    suspend fun memories(parentId: UUID, today: LocalDate, days: Long = 3): List<Memory> = coroutineScope {
        val years = runCatching { years(parentId) }.getOrDefault(emptyList()).filter { it < today.year }.take(30)
        val lists = years.map { y ->
            async {
                val mid = runCatching { today.withYear(y) }.getOrElse { today.withYear(y).withDayOfMonth(28) }
                y to runCatching {
                    media(parentId, limit = 300, types = listOf(BaseItemKind.PHOTO), oldestFirst = true,
                        minDate = mid.minusDays(days).atStartOfDay(), maxDate = mid.plusDays(days + 1).atStartOfDay().minusSeconds(1)).items
                }.getOrDefault(emptyList())
            }
        }.awaitAll()
        lists.flatMap { (y, items) ->
            items.groupBy { it.albumId?.toString().orEmpty() }.map { (k, list) ->
                val date = takenAt(list.first())?.toLocalDate() ?: today.withYear(y)
                Memory("$y:$k", today.year - y, list.first().album ?: longDay(date), date, list)
            }
        }.sortedWith(compareBy<Memory> { it.years }.thenByDescending { it.items.size })
    }

    suspend fun folderInfo(item: BaseItemDto, recursive: Boolean = true): FolderInfo {
        val key = "${item.id}:$recursive"
        folderCache[key]?.takeIf { System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        val info = coroutineScope {
            val newest = async { media(item.id, limit = 3, recursive = recursive) }
            val oldest = async { media(item.id, limit = 3, recursive = recursive, oldestFirst = true) }
            val videos = async { count(item.id, listOf(BaseItemKind.VIDEO), recursive) }
            val n = newest.await()
            val o = oldest.await()
            val v = videos.await()
            val covers = (o.items + n.items.reversed()).distinctBy { it.id }.filter { !it.isVideo }
            FolderInfo(
                item, n.totalRecordCount, n.totalRecordCount - v, v, covers.ifEmpty { o.items },
                o.items.firstOrNull()?.let(::takenAt)?.toLocalDate(), n.items.firstOrNull()?.let(::takenAt)?.toLocalDate(),
            )
        }
        folderCache[key] = System.currentTimeMillis() to info
        return info
    }

    suspend fun subfolders(parentId: UUID): List<BaseItemDto> = io {
        api.libraryApi.getItems(
            userId = userId, parentId = parentId, recursive = false, includeItemTypes = FolderTypes, sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING), fields = listOf(ItemFields.DATE_CREATED, ItemFields.PATH), enableUserData = true,
        ).content.items
    }

    /** The folders from the library down to an item's parent: [library, its folder on disk, …, parent]. */
    suspend fun ancestors(id: UUID): List<BaseItemDto> = io {
        val list = api.libraryApi.getAncestors(itemId = id, userId = userId).content
        val top = list.indexOfFirst { it.type == BaseItemKind.COLLECTION_FOLDER || it.type == BaseItemKind.USER_VIEW }
        (if (top >= 0) list.subList(0, top + 1) else list).reversed()
    }

    /** The file's size and type, from one byte of it. */
    suspend fun fileInfo(id: UUID): Pair<Long?, String?> {
        fileCache[id]?.let { return it }
        val out = io {
            runCatching {
                val c = URL("$base/Items/$id/File?ApiKey=$token").openConnection() as HttpURLConnection
                c.setRequestProperty("Range", "bytes=0-0")
                c.connectTimeout = 15_000
                c.readTimeout = 15_000
                try {
                    val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                        ?: if (c.responseCode == 200) c.getHeaderField("Content-Length")?.toLongOrNull() else null
                    total to c.contentType
                } finally { c.disconnect() }
            }.getOrDefault(null to null)
        }
        if (out.first != null) fileCache[id] = out
        return out
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val folderCache = ConcurrentHashMap<String, Pair<Long, FolderInfo>>()
        private val fileCache = ConcurrentHashMap<UUID, Pair<Long?, String?>>()
    }
}
