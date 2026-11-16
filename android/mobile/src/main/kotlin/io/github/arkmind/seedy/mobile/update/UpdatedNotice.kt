package io.github.arkmind.seedy.mobile.update

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.notify.CoreOwner
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.update.UpdateStore
import io.github.arkmind.seedy.mobile.R
import io.github.arkmind.seedy.mobile.notify.RequestAlerts
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.meduBitmap
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After the APK updater replaced the app, Android stops it and shows the home screen; the new version can't open itself
 * from the background, so it says it's ready in a notification ("Seedy is up to date", tap to open), when notifications
 * are allowed. Gone as soon as Seedy opens.
 */
object UpdatedNotice {
    private const val Channel = "updates"
    private const val Id = 0x5eed_0001

    @SuppressLint("MissingPermission") // RequestAlerts.allowed checks it.
    fun show(context: Context) {
        if (!RequestAlerts.allowed(context)) return
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(Channel) == null) {
            nm.createNotificationChannel(NotificationChannel(Channel, t("Updates"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        val note = NotificationCompat.Builder(context, Channel)
            .setSmallIcon(R.drawable.ic_stat_seedy)
            .setColor(SeedyColors.Accent.toArgb())
            .setLargeIcon(meduBitmap(MeduMood.Yay, 192, 192, SeedyColors.Accent).asAndroidBitmap())
            .setContentTitle(t("Seedy is up to date"))
            .setContentText(t("Version {version} is installed. Tap to open it.", "version" to info.versionName.orEmpty()))
            .setContentIntent(PendingIntent.getActivity(context, Id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(Id, note)
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(Id)
}

/** The new version, just installed over the old one: the notice, when the APK updater did it. */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        if (!UpdateStore(context).takeInstalled(code)) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // A fresh process: the notice speaks the language the app showed last.
                val lang = (context.applicationContext as? CoreOwner)?.core?.store?.current()?.lang?.let(I18n::resolve)
                if (lang != null && lang != I18n.lang) I18n.load(context, lang)
                UpdatedNotice.show(context)
            } finally {
                pending.finish()
            }
        }
    }
}
