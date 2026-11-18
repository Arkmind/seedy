package io.github.arkmind.seedy.mobile.parts

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import androidx.core.net.toUri
import io.github.arkmind.seedy.core.i18n.t

/**
 * Downloads [url] as [name] into the phone's Downloads (Android 10+ through the download manager, older ones the
 * browser). Returns what to tell the person, or null.
 */
fun downloadFile(context: Context, url: String, name: String): String? {
    if (Build.VERSION.SDK_INT >= 29) {
        val ok = runCatching {
            context.getSystemService(DownloadManager::class.java).enqueue(
                DownloadManager.Request(url.toUri()).setTitle(name)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name),
            )
        }.isSuccess
        if (ok) return t("Downloading {name}", "name" to name)
    }
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    }.getOrElse { t("Couldn’t download {name}", "name" to name) }
}

/** A file to save: its address, where it goes under the folder ("Show/file.mkv") and the notification's title. */
class SaveFile(val url: String, val path: String, val title: String)

/**
 * Saves [files] through the download manager into the phone's [dir] (Movies, Music…; before Android 10, the app's own
 * copy of it, which needs no permission). Returns how many started.
 */
fun downloadFiles(context: Context, files: List<SaveFile>, dir: String): Int {
    val manager = context.getSystemService(DownloadManager::class.java) ?: return 0
    return files.count { f ->
        runCatching {
            val request = DownloadManager.Request(f.url.toUri()).setTitle(f.title)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            if (Build.VERSION.SDK_INT >= 29) request.setDestinationInExternalPublicDir(dir, f.path)
            else request.setDestinationInExternalFilesDir(context, dir, f.path)
            manager.enqueue(request)
        }.isSuccess
    }
}
