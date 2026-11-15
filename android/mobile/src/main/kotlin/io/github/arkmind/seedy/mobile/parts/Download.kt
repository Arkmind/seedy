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
