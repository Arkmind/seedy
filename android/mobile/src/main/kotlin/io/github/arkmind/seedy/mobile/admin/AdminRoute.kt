package io.github.arkmind.seedy.mobile.admin

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.media.AdminLibrariesRoute
import io.github.arkmind.seedy.mobile.admin.media.AdminLibraryRoute
import io.github.arkmind.seedy.mobile.admin.media.ServerPlaybackRoute
import io.github.arkmind.seedy.mobile.admin.media.ServerSettingsRoute
import io.github.arkmind.seedy.mobile.admin.people.ApiKeysRoute
import io.github.arkmind.seedy.mobile.admin.people.DevicesRoute
import io.github.arkmind.seedy.mobile.admin.people.UserRoute
import io.github.arkmind.seedy.mobile.admin.people.UsersRoute
import io.github.arkmind.seedy.mobile.admin.seedy.AdminRequestsRoute
import io.github.arkmind.seedy.mobile.admin.seedy.BrandingRoute
import io.github.arkmind.seedy.mobile.admin.seedy.DefaultHomeRoute
import io.github.arkmind.seedy.mobile.admin.seedy.NetworkingRoute
import io.github.arkmind.seedy.mobile.admin.seedy.PlayLogRoute
import io.github.arkmind.seedy.mobile.admin.seedy.ServerLiveTvRoute
import io.github.arkmind.seedy.mobile.admin.system.ActivityRoute
import io.github.arkmind.seedy.mobile.admin.system.BackupsRoute
import io.github.arkmind.seedy.mobile.admin.system.LogsRoute
import io.github.arkmind.seedy.mobile.admin.system.PluginsRoute
import io.github.arkmind.seedy.mobile.admin.system.TasksRoute
import io.github.arkmind.seedy.ui.components.NotYet
import io.github.arkmind.seedy.mobile.manage.IdentifyRoute
import io.github.arkmind.seedy.mobile.manage.ImagesRoute
import io.github.arkmind.seedy.mobile.manage.MetadataRoute
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav

/**
 * The server pages (boards "Android · Server · …"); [page] is the key the hub's rows use, [id] the user or library a
 * page is about.
 */
@Composable
fun AdminRoute(page: String, id: String?) {
    when (page) {
        "server" -> ServerHubRoute()
        // People: users, devices, API keys.
        "users" -> UsersRoute()
        "user" -> UserRoute(id.orEmpty())
        "devices" -> DevicesRoute()
        "apikeys" -> ApiKeysRoute()
        // Media: libraries, playback (transcoding), server settings.
        "libraries" -> AdminLibrariesRoute()
        "library" -> AdminLibraryRoute(id.orEmpty())
        "playback" -> ServerPlaybackRoute()
        "settings" -> ServerSettingsRoute()
        // System: tasks, activity, plugins, backups, logs.
        "tasks" -> TasksRoute()
        "activity" -> ActivityRoute()
        "plugins" -> PluginsRoute()
        "backups" -> BackupsRoute()
        "logs" -> LogsRoute()
        // Networking, Live TV and Seedy's own pages.
        "networking" -> NetworkingRoute()
        "livetv" -> ServerLiveTvRoute()
        "branding" -> BrandingRoute()
        "homescreen" -> DefaultHomeRoute()
        "requests" -> AdminRequestsRoute()
        "playlog" -> PlayLogRoute()
        // A title's metadata, match and images (from its More menu).
        "metadata" -> MetadataRoute(id.orEmpty(), LocalMobileNav.current::back)
        "identify" -> IdentifyRoute(id.orEmpty(), LocalMobileNav.current::back)
        "images" -> ImagesRoute(id.orEmpty(), LocalMobileNav.current::back)
        else -> NotYet(t("Server"))
    }
}
