package io.github.arkmind.seedy.core.books

import io.github.arkmind.seedy.core.i18n.I18n
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.Inflater

/*
 * The language an EPUB says it's in (dc:language), which Jellyfin doesn't keep (web epub-meta.ts). Read without
 * downloading the book: a range request for the end of the file (the zip's directory), then the two small entries that
 * matter (META-INF/container.xml and the package document). Remembered per book while the app runs.
 */

private class ZipEntry(val method: Int, val compressed: Int, val offset: Long)

private class Range(val bytes: ByteArray, val start: Long)

private fun range(url: String, spec: String): Range? {
    val c = URL(url).openConnection() as HttpURLConnection
    c.setRequestProperty("Range", "bytes=$spec")
    c.connectTimeout = 10_000
    c.readTimeout = 15_000
    return try {
        if (c.responseCode != 206) return null
        val start = Regex("bytes (\\d+)-").find(c.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        Range(c.inputStream.use { it.readBytes() }, start)
    } finally {
        c.disconnect()
    }
}

private fun directory(bytes: ByteArray, base: Long): Map<String, ZipEntry> {
    val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    // End of central directory: the last "PK\x05\x06".
    var eocd = -1
    for (i in bytes.size - 22 downTo 0) if (b.getInt(i) == 0x06054b50) { eocd = i; break }
    if (eocd < 0) return emptyMap()
    val count = b.getShort(eocd + 10).toInt() and 0xffff
    var p = ((b.getInt(eocd + 16).toLong() and 0xffffffffL) - base).toInt()
    val out = HashMap<String, ZipEntry>()
    var n = 0
    while (n < count && p >= 0 && p + 46 <= bytes.size) {
        if (b.getInt(p) != 0x02014b50) break
        val nameLen = b.getShort(p + 28).toInt() and 0xffff
        val extra = b.getShort(p + 30).toInt() and 0xffff
        val comment = b.getShort(p + 32).toInt() and 0xffff
        if (p + 46 + nameLen > bytes.size) break
        val name = String(bytes, p + 46, nameLen, Charsets.UTF_8)
        out[name] = ZipEntry(b.getShort(p + 10).toInt() and 0xffff, b.getInt(p + 20), b.getInt(p + 42).toLong() and 0xffffffffL)
        p += 46 + nameLen + extra + comment
        n++
    }
    return out
}

private fun read(url: String, e: ZipEntry): String? {
    val r = range(url, "${e.offset}-${e.offset + 30 + 1024 + e.compressed}") ?: return null
    val b = ByteBuffer.wrap(r.bytes).order(ByteOrder.LITTLE_ENDIAN)
    val start = 30 + (b.getShort(26).toInt() and 0xffff) + (b.getShort(28).toInt() and 0xffff)
    val len = minOf(e.compressed, r.bytes.size - start).coerceAtLeast(0)
    if (e.method == 0) return String(r.bytes, start, len, Charsets.UTF_8)
    val inflater = Inflater(true)
    return try {
        inflater.setInput(r.bytes, start, len)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!inflater.finished()) {
            val k = inflater.inflate(buf)
            if (k == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
            out.write(buf, 0, k)
        }
        out.toString(Charsets.UTF_8.name())
    } finally {
        inflater.end()
    }
}

private val langCache = ConcurrentHashMap<UUID, String>()

/** The EPUB's language code ("en"), or null when the file doesn't say (or can't be read in ranges). */
suspend fun epubLanguage(books: BooksApi, id: UUID): String? {
    langCache[id]?.let { return it.ifEmpty { null } }
    val lang = withContext(Dispatchers.IO) {
        runCatching {
            val url = books.fileUrl(id)
            val tail = range(url, "-65536") ?: return@runCatching null
            val files = directory(tail.bytes, tail.start)
            val container = files["META-INF/container.xml"]?.let { read(url, it) } ?: return@runCatching null
            val root = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return@runCatching null
            val opf = files[root]?.let { read(url, it) } ?: return@runCatching null
            Regex("<dc:language[^>]*>\\s*([^<\\s]+)\\s*</dc:language>", RegexOption.IGNORE_CASE).find(opf)?.groupValues?.get(1)
        }.getOrNull()
    }
    langCache[id] = lang.orEmpty()
    return lang
}

/** "English" for "en", in the app's language. */
fun languageName(code: String?): String {
    if (code.isNullOrBlank()) return ""
    val name = runCatching { Locale.forLanguageTag(code).getDisplayLanguage(I18n.locale) }.getOrNull().orEmpty()
    if (name.isEmpty() || name.equals(code, ignoreCase = true)) return ""
    return name.replaceFirstChar { it.titlecase(I18n.locale) }
}
