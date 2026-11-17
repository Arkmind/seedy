package io.github.arkmind.seedy.tv.watchnext

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A card on the TV home screen: play [itemId] as the profile [user] of [server] (from where they left off). */
data class WatchNextLink(val server: String, val user: String, val itemId: UUID)

/** The home screen's cards waiting for the app: the activity hands them over, the signed-in TV app plays them. */
object WatchNextLinks {
    private const val Scheme = "seedy"
    private const val Host = "watch-next"

    private val _pending = MutableStateFlow<WatchNextLink?>(null)
    val pending: StateFlow<WatchNextLink?> = _pending.asStateFlow()

    fun clear() {
        _pending.value = null
    }

    /** The activity was opened (or brought back) by a card: keeps where it leads, once (a recreate doesn't replay it). */
    fun take(intent: Intent?) {
        val uri = intent?.data?.takeIf { it.scheme == Scheme && it.host == Host } ?: return
        intent.data = null
        val server = uri.getQueryParameter("server") ?: return
        val user = uri.getQueryParameter("user") ?: return
        val item = uri.getQueryParameter("item")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return
        _pending.value = WatchNextLink(server, user, item)
    }

    /** What the home screen starts for [link]: this app's TV activity on it. */
    fun intentUri(context: Context, link: WatchNextLink): String {
        val pm = context.packageManager
        val launch = pm.getLeanbackLaunchIntentForPackage(context.packageName) ?: pm.getLaunchIntentForPackage(context.packageName) ?: Intent()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            component = launch.component
            data = Uri.Builder().scheme(Scheme).authority(Host)
                .appendQueryParameter("server", link.server)
                .appendQueryParameter("user", link.user)
                .appendQueryParameter("item", link.itemId.toString())
                .build()
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return intent.toUri(Intent.URI_INTENT_SCHEME)
    }
}
