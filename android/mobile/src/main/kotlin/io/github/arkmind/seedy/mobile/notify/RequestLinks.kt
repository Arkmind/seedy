package io.github.arkmind.seedy.mobile.notify

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where a request notification leads, for the profile it was for: [Open] the title (its page on the server, or its
 * Seerr page), [Play] it, or the [Requests] tab.
 */
data class RequestLink(
    val server: String,
    val user: String,
    val action: String,
    val itemId: String? = null,
    val mediaType: String? = null,
    val tmdbId: Int? = null,
) {
    companion object {
        const val Open = "open"
        const val Play = "play"
        const val Requests = "requests"
    }
}

/** The notification taps waiting for the app: the activity hands them over, the signed-in app acts on them. */
object RequestLinks {
    private const val Prefix = "io.github.arkmind.seedy.link."

    private val _pending = MutableStateFlow<RequestLink?>(null)
    val pending: StateFlow<RequestLink?> = _pending.asStateFlow()

    /** Done with the waiting link. */
    fun clear() {
        _pending.value = null
    }

    /** The activity was opened (or brought back) by a notification: keeps where it leads, and the notification goes. */
    fun take(context: Context, intent: Intent?) {
        val i = intent ?: return
        val action = i.getStringExtra(Prefix + "action") ?: return
        // A button doesn't close its notification by itself (a tap on the notification does).
        i.getIntExtra(Prefix + "note", 0).takeIf { it != 0 }?.let { NotificationManagerCompat.from(context).cancel(it) }
        RequestAlerts.tidyGroup(context, except = i.getIntExtra(Prefix + "note", 0))
        _pending.value = RequestLink(
            server = i.getStringExtra(Prefix + "server").orEmpty(),
            user = i.getStringExtra(Prefix + "user").orEmpty(),
            action = action,
            itemId = i.getStringExtra(Prefix + "item"),
            mediaType = i.getStringExtra(Prefix + "type"),
            tmdbId = i.getIntExtra(Prefix + "tmdb", 0).takeIf { it > 0 },
        )
        i.removeExtra(Prefix + "action")
    }

    /** Opens the app on [link]; [code] tells this notification's buttons apart, [note] is the notification. */
    fun pendingIntent(context: Context, link: RequestLink, code: Int, note: Int): PendingIntent {
        val intent = (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent()).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // A distinct address per button, so Android keeps each one's extras.
            data = "seedy://request/$code/${link.action}".toUri()
            putExtra(Prefix + "action", link.action)
            putExtra(Prefix + "note", note)
            putExtra(Prefix + "server", link.server)
            putExtra(Prefix + "user", link.user)
            link.itemId?.let { putExtra(Prefix + "item", it) }
            link.mediaType?.let { putExtra(Prefix + "type", it) }
            link.tmdbId?.let { putExtra(Prefix + "tmdb", it) }
        }
        return PendingIntent.getActivity(context, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
