package io.github.arkmind.seedy.mobile.settings

import androidx.compose.runtime.getValue
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Tab
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.SettingsIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Settings, the list of pages (board "Android · Settings"); the shell shows it with the tab bar. */
@Composable
fun SettingsRoute(appVersion: String) {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { SettingsViewModel(core, session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load("home") }
    SettingsHomeScreen(
        state = state,
        appVersion = appVersion,
        actions = vm,
        nav = settingsNav(state, nav, context),
        requests = LocalViewer.current.requests,
    )
}

/** Where settings lead: their pages, the Requests tab, the server's Seedy admin in the browser. */
internal fun settingsNav(state: SettingsState, nav: io.github.arkmind.seedy.mobile.nav.MobileNav, context: android.content.Context) = SettingsNav(
    back = nav::back,
    open = { nav.go(Route.SettingsPage(it)) },
    requests = { nav.toTab(Tab.Requests) },
    admin = {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.serverAddress.trimEnd('/') + "/admin")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    },
)

@Composable
fun SettingsHomeScreen(state: SettingsState, appVersion: String, actions: SettingsActions, nav: SettingsNav, requests: Boolean) {
    val sheet = rememberSheet()
    val accent = LocalAccent.current
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            PageHeader(t("Settings"), nav.back)
            ProfileCard(state) { nav.open("profile") }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(t("Switch profile"), SettingsIcons.Switch, 15, Modifier.weight(1f), actions::switchProfile)
                PillButton(t("Approve a TV"), SettingsIcons.Phone, 15, Modifier.weight(1f)) {
                    sheet.form = "code"
                }
            }

            Group(t("Account")) {
                item(t("Profile and sign-in"), t("Picture, password, requests and privacy"), SettingsIcons.User) { nav.open("profile") }
            }
            Group(t("Watching")) {
                item(t("Home screen"), state.homeSummary(), SettingsIcons.Home) { nav.open("home") }
                item(t("Playback"), state.playbackSummary(), SettingsIcons.Play) { nav.open("playback") }
                item(t("Subtitles"), state.subtitlesSummary(), SettingsIcons.Subtitles, ink = accent) { nav.open("subtitles") }
            }
            Group(t("This app")) {
                item(t("Display"), state.displaySummary(), SettingsIcons.Display) { nav.open("display") }
                item(t("Gestures"), state.gesturesSummary(), SettingsIcons.Gesture) { nav.open("gestures") }
            }
            Group(t("More")) {
                // Viewing history has no page in the app yet.
                item(t("Viewing history"), state.lastPlayed?.let { t("Last: {title}", "title" to it) } ?: t("See what you watched"), SettingsIcons.History) {}
                if (requests) {
                    item(t("My requests"), state.openRequests?.let { tn(it, "{n} open", "{n} open") } ?: t("What you asked for"), SettingsIcons.Requests, onClick = nav.requests)
                }
                if (state.isAdmin) item(t("Admin dashboard"), t("Opens Seedy on the web"), SettingsIcons.Admin, onClick = nav.admin)
                item(t("About Seedy"), state.aboutSummary(appVersion), SettingsIcons.Info) { nav.open("about") }
            }

            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel).tap(onClick = actions::signOut),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    SIcon(SettingsIcons.SignOut, size = 18.dp, color = SeedyColors.Danger)
                    SText(t("Sign out of {server}", "server" to state.serverName), size = 14.sp, weight = 800, color = SeedyColors.Danger, maxLines = 1)
                }
            }
        }
        SheetHost(sheet)
        if (sheet.form == "code") CodeSheet(state, actions, sheet::close)
    }
}

/** The profile's card: picture, name with Admin, where it's signed in. */
@Composable
private fun ProfileCard(state: SettingsState, onClick: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)
            .tap(onClick = onClick).padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ProfileSquare(state.name, state.imageUrl, 0, size = 56.dp, radius = 28.dp, letterSize = 24.sp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(state.name, Modifier.weight(1f, fill = false), size = 17.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                if (state.isAdmin) AdminPill()
            }
            SText(listOf(state.serverName, state.deviceName, t("Seedy for Android")).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp, color = SeedyColors.Text3)
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

@Composable
internal fun AdminPill() {
    Box(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(horizontal = 8.dp, vertical = 1.dp)) {
        SText(t("Admin"), size = 10.sp, weight = 800, tracking = 0.06f, color = SeedyColors.TextHero, uppercase = true, maxLines = 1)
    }
}

@Composable
private fun PillButton(label: String, icon: IconDef, iconSize: Int, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(CircleShape).background(SeedyColors.Panel).tap(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        SIcon(icon, size = iconSize.dp, color = SeedyColors.White)
        SText(label, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
    }
}

private class GroupItems {
    val items = mutableListOf<@Composable () -> Unit>()
    fun item(name: String, sub: String, icon: IconDef, ink: Color? = null, onClick: () -> Unit) {
        items += { PageItem(name, sub, icon, ink ?: SeedyColors.White, onClick) }
    }
}

/** A group of pages: its title and a card of rows. */
@Composable
private fun Group(title: String, build: GroupItems.() -> Unit) {
    val items = GroupItems().apply(build).items
    SectionTitle(title)
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
        items.forEachIndexed { i, item ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
            item()
        }
    }
}

@Composable
private fun PageItem(name: String, sub: String, icon: IconDef, ink: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 12.dp, end = 14.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(icon, size = 19.dp, color = ink)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}
