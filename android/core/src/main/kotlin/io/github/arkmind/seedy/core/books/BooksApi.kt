package io.github.arkmind.seedy.core.books

import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jellyfin.sdk.api.client.extensions.get
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What a book file is: epub, pdf, cbz, cbr, other — or "audio" for an audiobook. */
enum class BookFormat { Epub, Pdf, Cbz, Cbr, Other, Audio }

@Serializable
data class BookChapter(
    @SerialName("Title") val title: String = "",
    @SerialName("Href") val href: String = "",
    /** The page it starts on, from 0 (EPUB pages are 1600 characters). */
    @SerialName("Page") val page: Int = 0,
)

/** /Seedy/Books/{id}/Info: the book's format, length in pages, reading time and chapters. */
@Serializable
data class BookInfo(
    @SerialName("Format") val format: String = "other",
    @SerialName("Pages") val pages: Int = 0,
    @SerialName("Words") val words: Int = 0,
    @SerialName("Minutes") val minutes: Int = 0,
    @SerialName("Chapters") val chapters: List<BookChapter> = emptyList(),
) {
    val bookFormat: BookFormat get() = formatNamed(format)
}

/**
 * The reader's side of the Seedy API (BooksController, web/src/screens/books/api.ts): the book file, its length and
 * chapters, and comic pages. Places in books are in [io.github.arkmind.seedy.core.audio.AudioApi] (reading…).
 */
class BooksApi(private val session: UserSession) {
    private val api get() = session.api
    private val base get() = api.baseUrl.orEmpty().trimEnd('/')
    private val token get() = URLEncoder.encode(api.accessToken.orEmpty(), "UTF-8")

    /** Book info rarely changes: one request per book while the app runs. */
    suspend fun info(id: UUID): BookInfo = infoCache[id] ?: withContext(Dispatchers.IO) {
        api.get<BookInfo>("/Seedy/Books/$id/Info").content
    }.also { infoCache[id] = it }

    /** The book file (EPUB, PDF, CBZ…), with the token in the address. */
    fun fileUrl(id: UUID): String = "$base/Seedy/Books/$id/File?ApiKey=$token"

    /** One page of a comic (CBZ), from 0. */
    fun pageUrl(id: UUID, index: Int): String = "$base/Seedy/Books/$id/Pages/$index?ApiKey=$token"

    /** Jellyfin's download of the original file. */
    fun downloadUrl(id: UUID): String = "$base/Items/$id/Download?ApiKey=$token"

    private companion object {
        val infoCache = ConcurrentHashMap<UUID, BookInfo>()
    }
}

private fun formatNamed(s: String?): BookFormat = when (s?.lowercase()) {
    "epub" -> BookFormat.Epub
    "pdf" -> BookFormat.Pdf
    "cbz" -> BookFormat.Cbz
    "cbr" -> BookFormat.Cbr
    else -> BookFormat.Other
}

/** What a library item is, from its file's extension (an AudioBook is [BookFormat.Audio]). */
fun formatOf(item: BaseItemDto): BookFormat {
    if (item.type == BaseItemKind.AUDIO_BOOK) return BookFormat.Audio
    val path = item.path ?: item.mediaSources?.firstOrNull()?.path.orEmpty()
    val ext = Regex("\\.([a-z0-9]+)$", RegexOption.IGNORE_CASE).find(path)?.groupValues?.get(1) ?: item.container.orEmpty()
    return formatNamed(ext)
}

fun isComic(item: BaseItemDto): Boolean = formatOf(item).let { it == BookFormat.Cbz || it == BookFormat.Cbr }

/** The file's size in bytes, when Jellyfin knows it. */
fun fileSize(item: BaseItemDto): Long? = item.mediaSources?.firstOrNull()?.size
