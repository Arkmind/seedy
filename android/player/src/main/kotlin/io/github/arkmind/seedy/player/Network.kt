package io.github.arkmind.seedy.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.core.settings.Choices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.systemApi
import java.net.URI

/** Where this device is, for Settings · Playback › Quality: at home with the server, away, or on mobile data. */
enum class NetworkPlace { Home, Away, Mobile }

object Network {
    /** Mobile data: a cellular network with no Wi-Fi or Ethernet. */
    fun onMobileData(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** A private or local server address means home; a public one may still be (split DNS): the server says. */
    suspend fun place(context: Context, session: UserSession): NetworkPlace {
        if (onMobileData(context)) return NetworkPlace.Mobile
        val host = runCatching { URI(session.server.address).host }.getOrNull().orEmpty()
        if (host.isNotEmpty() && isPrivateHost(host)) return NetworkPlace.Home
        val inNetwork = withContext(Dispatchers.IO) {
            runCatching { session.api.systemApi.getEndpointInfo().content.let { it.isInNetwork || it.isLocal } }.getOrDefault(false)
        }
        return if (inNetwork) NetworkPlace.Home else NetworkPlace.Away
    }

    /** What Auto stands for here: this device's choice for where it is, else the most the admin allows away from home. */
    fun autoBitrate(device: DeviceSettings, place: NetworkPlace, remoteLimit: Int): Int {
        val chosen = when (place) {
            NetworkPlace.Home -> device.qualityHome
            NetworkPlace.Away -> device.qualityAway
            NetworkPlace.Mobile -> device.qualityMobile
        }
        if (chosen > 0) return chosen
        return if (place != NetworkPlace.Home && remoteLimit > 0) minOf(Choices.AutoBitrate, remoteLimit) else Choices.AutoBitrate
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        if (h == "localhost" || h.endsWith(".localhost") || h == "::1") return true
        if (Regex("""\.(local|lan|home|internal|home\.arpa|localdomain)$""").containsMatchIn(h)) return true
        val v4 = Regex("""^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$""").find(h)
        if (v4 != null) {
            val a = v4.groupValues[1].toInt()
            val b = v4.groupValues[2].toInt()
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h) return Regex("^f[cd][0-9a-f]{2}:").containsMatchIn(h) || h.startsWith("fe80:")
        // A single-label name ("nas", "jellyfin") only resolves on a local network.
        return '.' !in h
    }
}
