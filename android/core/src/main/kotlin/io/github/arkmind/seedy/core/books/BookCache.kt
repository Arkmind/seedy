package io.github.arkmind.seedy.core.books

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Book files on this phone for the readers (the EPUB's web page, the PDF renderer): fetched from /Seedy/Books/{id}/File
 * into the app's cache, the last few kept so reopening a book is instant.
 */
class BookCache(context: Context, private val books: BooksApi) {
    private val dir = File(context.applicationContext.cacheDir, "books")

    suspend fun file(item: BaseItemDto, ext: String): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val size = fileSize(item)
        val file = File(dir, "${item.id}${size?.let { "-$it" }.orEmpty()}.$ext")
        if (size != null && file.length() == size) {
            file.setLastModified(System.currentTimeMillis())
            return@withContext file
        }
        val part = File(dir, file.name + ".part")
        val conn = URL(books.fileUrl(item.id)).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        if (!part.renameTo(file)) throw IOException("Couldn't keep the file")
        prune(keep = file)
        file
    }

    private fun prune(keep: File) {
        val files = dir.listFiles().orEmpty().filter { it != keep }.sortedByDescending { it.lastModified() }
        files.drop(KEEP - 1).forEach { it.delete() }
    }

    private companion object {
        const val KEEP = 6
    }
}

/** A PDF's pages as pictures (android.graphics.pdf), one at a time: the renderer takes one page open at once. */
class PdfPages(file: File) : AutoCloseable {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(fd)
    private val lock = Mutex()
    private var closed = false

    val count: Int = renderer.pageCount

    /** Page [index] (from 0), [width] pixels wide, on white. Null once closed. */
    suspend fun render(index: Int, width: Int): Bitmap? = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until count) return@withLock null
            renderer.openPage(index).use { page ->
                val w = width.coerceIn(200, 2400)
                val h = (w.toFloat() * page.height / page.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    /** The page's width over its height. */
    suspend fun ratio(index: Int): Float = withContext(Dispatchers.IO) {
        lock.withLock {
            if (closed || index !in 0 until count) return@withLock 0.707f
            renderer.openPage(index).use { it.width.toFloat() / it.height.coerceAtLeast(1) }
        }
    }

    override fun close() {
        // Closing waits for a page being drawn.
        Thread {
            kotlinx.coroutines.runBlocking {
                lock.withLock {
                    if (closed) return@withLock
                    closed = true
                    runCatching { renderer.close() }
                    runCatching { fd.close() }
                }
            }
        }.start()
    }
}
