package io.github.arkmind.seedy.tv.watchnext

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The pictures on Seedy's cards in the TV home screen. The launcher can't always load them from the server itself
 * (plain http at home, a self-signed certificate), so each sync saves them on the TV and the launcher reads them here.
 * It only hands out those saved pictures (artwork, nothing private), so any app may read them.
 */
class WatchNextArt : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? = nameOf(uri)?.let { mimeOf(it) }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("Seedy's pictures are read-only")
        val file = nameOf(uri)?.let { File(dir(context ?: throw FileNotFoundException()), it) }
        if (file == null || !file.isFile) throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val Name = Regex("[0-9a-f]{64}\\.(jpg|png|webp)")

        private fun dir(context: Context) = File(context.filesDir, "watch-next")

        private fun nameOf(uri: Uri): String? = uri.lastPathSegment?.takeIf { Name.matches(it) && uri.pathSegments.size == 1 }

        private fun mimeOf(name: String) = when (name.substringAfterLast('.')) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> "image/jpeg"
        }

        fun uri(context: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority(context.packageName + ".watchnext").appendPath(name).build()

        /**
         * Saves the picture at [url] (once: the address carries the image's tag, so a new picture is a new address).
         * Its name here, or null when the server didn't give it.
         */
        fun save(context: Context, url: String): String? {
            val hash = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val dir = dir(context).apply { mkdirs() }
            dir.listFiles()?.firstOrNull { it.name.startsWith("$hash.") }?.let { return it.name }
            return runCatching {
                val bytes = (URL(url).openConnection() as HttpURLConnection).run {
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    try {
                        if (responseCode == 200) inputStream.use { it.readBytes() } else null
                    } finally {
                        disconnect()
                    }
                } ?: return null
                val ext = extensionOf(bytes) ?: return null
                val name = "$hash.$ext"
                val part = File(dir, "$name.part")
                part.writeBytes(bytes)
                if (!part.renameTo(File(dir, name))) part.delete()
                name.takeIf { File(dir, it).isFile }
            }.getOrNull()
        }

        /** Forgets the pictures no card uses anymore. */
        fun keepOnly(context: Context, names: Set<String>) {
            dir(context).listFiles()?.filter { it.name !in names }?.forEach { it.delete() }
        }

        private fun extensionOf(b: ByteArray): String? = when {
            b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
            b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
            b.size > 12 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WEBP" -> "webp"
            else -> null
        }
    }
}
