package io.github.arkmind.seedy.core

import android.content.Context
import io.github.arkmind.seedy.core.data.AppStore
import io.github.arkmind.seedy.core.server.Servers
import io.github.arkmind.seedy.core.session.Sessions
import io.github.arkmind.seedy.core.session.SignIn
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo

/** The app's long-lived parts, created once by the Application. */
class SeedyCore(context: Context, appVersion: String) {
    val jellyfin = createJellyfin {
        this.context = context.applicationContext
        clientInfo = ClientInfo(name = "Seedy", version = appVersion)
    }
    val store = AppStore(context.applicationContext)
    val servers = Servers(jellyfin, store)
    val sessions = Sessions(context.applicationContext, jellyfin, store)
    val signIn = SignIn(sessions)
}
