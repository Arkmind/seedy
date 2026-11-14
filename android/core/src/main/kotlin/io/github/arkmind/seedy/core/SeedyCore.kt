package io.github.arkmind.seedy.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import io.github.arkmind.seedy.core.data.AppStore
import io.github.arkmind.seedy.core.data.DevicePrefs
import io.github.arkmind.seedy.core.server.Servers
import io.github.arkmind.seedy.core.session.Sessions
import io.github.arkmind.seedy.core.session.SignIn
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** The app's long-lived parts, created once by the Application. */
class SeedyCore(context: Context, appVersion: String) {
    val context: Context = context.applicationContext
    val jellyfin = createJellyfin {
        this.context = context.applicationContext
        clientInfo = ClientInfo(name = clientName(context), version = appVersion)
    }
    val store = AppStore(context.applicationContext)
    /** Settings marked "This phone" / "This TV". */
    val device = DevicePrefs.get(context)
    val servers = Servers(jellyfin, store)
    val sessions = Sessions(context.applicationContext, jellyfin, store)
    val signIn = SignIn(sessions)
}

/** The app's name on the server (Devices, Statistics): the TV and phone apps are told apart there. */
private fun clientName(context: Context): String {
    val tv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        context.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION
    return if (tv) "Seedy for Android TV" else "Seedy for Android"
}
