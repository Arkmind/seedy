package io.github.arkmind.seedy.mobile.wrapped

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Hands Wrapped's pictures to other apps (Share this story, the summary's Share). */
class WrappedFiles : FileProvider()

/** Wrapped's pictures: shared through the system's share sheet, or saved to Pictures/Seedy. */
internal object WrappedPictures {
    private fun authority(context: Context) = "${context.packageName}.wrapped"

    /** Hardware bitmaps can't be written out: a software copy. */
    private fun ImageBitmap.software(): Bitmap {
        val b = asAndroidBitmap()
        return if (b.config == Bitmap.Config.ARGB_8888) b else b.copy(Bitmap.Config.ARGB_8888, false)
    }

    /** Writes [picture] to the cache and opens the share sheet for it; false when it couldn't be written. */
    suspend fun share(context: Context, picture: ImageBitmap, name: String, title: String): Boolean {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "wrapped").apply { mkdirs() }
                File(dir, "$name.png").also { f -> f.outputStream().use { picture.software().compress(Bitmap.CompressFormat.PNG, 100, it) } }
            }.getOrNull()
        } ?: return false
        val uri = runCatching { FileProvider.getUriForFile(context, authority(context), file) }.getOrNull() ?: return false
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK.takeIf { context !is android.app.Activity } ?: 0))
        }.isSuccess
    }

    /** Whether saving needs the storage permission first (Android 9 and older). */
    val needsPermission: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /** Saves [picture] as Pictures/Seedy/[name].png; false when it couldn't. */
    suspend fun save(context: Context, picture: ImageBitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = picture.software()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Seedy")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
                try {
                    checkNotNull(resolver.openOutputStream(uri)).use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Seedy").apply { mkdirs() }
                val file = File(dir, "$name.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
            }
        }.isSuccess
    }
}
