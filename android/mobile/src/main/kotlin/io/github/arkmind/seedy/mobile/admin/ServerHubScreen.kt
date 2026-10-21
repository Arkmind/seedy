package io.github.arkmind.seedy.mobile.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.HubAlert
import io.github.arkmind.seedy.core.admin.HubLink
import io.github.arkmind.seedy.core.admin.NowPlaying
import io.github.arkmind.seedy.core.admin.PlayKind
import io.github.arkmind.seedy.core.admin.ServerHubActions
import io.github.arkmind.seedy.core.admin.ServerHubState
import io.github.arkmind.seedy.core.admin.ServerHubViewModel
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.core.admin.label
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import io.github.arkmind.seedy.mobile.settings.FootNote
import io.github.arkmind.seedy.mobile.settings.CssColumn
import io.github.arkmind.seedy.mobile.settings.cssExcess
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import kotlin.math.roundToInt

/** Board "Android · Server": the admin pages' hub, opened from Settings › Server. */
@Composable
fun ServerHubRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { ServerHubViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { vm.live() }
    ServerHubScreen(state, vm, onBack = nav::back, open = { page -> nav.go(Route.Admin(page)) })
}

private val MethodDots = mapOf(PlayKind.Direct to SeedyColors.Ok, PlayKind.Stream to Color(0xFF9FD3FF), PlayKind.Transcode to SeedyColors.Pending)

@Composable
fun ServerHubScreen(state: ServerHubState, actions: ServerHubActions, onBack: () -> Unit, open: (String) -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    var sessionMenu by remember { mutableStateOf<NowPlaying?>(null) }
    var messageTo by remember { mutableStateOf<NowPlaying?>(null) }
    var stopping by remember { mutableStateOf<NowPlaying?>(null) }
    val accent = LocalAccent.current
    SettingsPage(
        t("Server"),
        lede = null,
        onBack = onBack,
        overlay = {
            when (confirm) {
                "restart" -> ConfirmSheet(
                    t("Restart the server?"),
                    t("Everyone watching is interrupted for a minute while Jellyfin restarts. Seedy reconnects on its own when the server is back."),
                    t("Restart"), actions::restart, { confirm = null },
                )
                "shutdown" -> ConfirmSheet(
                    t("Shut down the server?"),
                    t("Playback stops for everyone, and nobody can use Seedy or Jellyfin until the server is started again on the machine it runs on."),
                    t("Shut down"), actions::shutdown, { confirm = null }, danger = true,
                )
            }
            sessionMenu?.let { s ->
                val items = buildList {
                    if (s.canMessage) add(MenuItem(t("Send a message")) { messageTo = s })
                    if (s.canStop) add(MenuItem(t("Stop playback"), danger = true) { stopping = s })
                }
                MenuSheet(t("{name} on {device}", "name" to s.user, "device" to s.device), items, { sessionMenu = null })
            }
            messageTo?.let { s ->
                TextSheet(
                    t("Message {name}", "name" to s.user),
                    t("Shows on {device} over what’s playing, for a few seconds.", "device" to s.device.ifEmpty { t("their screen") }),
                    t("Send"), { actions.sendMessage(s.sessionId, it) }, { messageTo = null },
                    placeholder = t("Dinner’s ready in 10 minutes"),
                )
            }
            stopping?.let { s ->
                ConfirmSheet(
                    t("Stop {name}’s playback?", "name" to s.user),
                    t("{title} stops on {device}. {name} can pick it up again from where it stopped.", "title" to s.title, "device" to s.device.ifEmpty { t("their device") }, "name" to s.user),
                    t("Stop playback"), { actions.stopPlayback(s.sessionId) }, { stopping = null }, danger = true,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        DotLede(state.lede ?: "…", if (state.offline) SeedyColors.Danger else if (state.restarting) SeedyColors.Pending else SeedyColors.Ok)
        PillRow {
            PillButton(if (state.scanning) t("Scanning…") else t("Scan all libraries"), actions::scanAll, enabled = !state.scanning && !state.restarting)
            PillButton(if (state.restarting) t("Restarting…") else t("Restart"), { confirm = "restart" }, enabled = !state.restarting)
            PillButton(t("Shut down"), { confirm = "shutdown" }, style = PillStyle.Danger, enabled = !state.restarting)
        }
        if (state.alerts.isNotEmpty()) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in state.alerts) Alert(a, accent) { if (a.open == "restart") confirm = "restart" else a.open?.let(open) }
            }
        }

        Playing(state, onMenu = { sessionMenu = it })

        SectionTitle(t("Running tasks"))
        Card(Modifier.padding(horizontal = 16.dp)) {
            val tasks = state.tasks
            when {
                tasks == null -> TaskLine("…", "", 0f)
                tasks.isEmpty() -> Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(t("Nothing running"), size = 13.sp, weight = 700, color = SeedyColors.White)
                    SText(
                        state.lastScan?.let { t("The last library scan finished {when}.", "when" to it) } ?: t("Library scans and other jobs show up here while they run."),
                        size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3,
                    )
                }
                else -> tasks.forEachIndexed { i, task ->
                    if (i > 0) RowLine()
                    TaskLine(task.name, task.state, task.progress)
                }
            }
        }

        SectionTitle(t("Library"))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val counts = state.counts
            if (counts == null) repeat(3) { StatTile("…", "", Modifier.weight(1f)) }
            else for (c in counts) StatTile(c.n, c.label, Modifier.weight(1f))
        }

        for (g in state.groups) {
            SectionTitle(g.title)
            Card(Modifier.padding(horizontal = 16.dp)) {
                g.links.forEachIndexed { i, link ->
                    if (i > 0) RowLine()
                    Link(link) { open(link.page) }
                }
            }
        }
        FootNote(state.signedInAs)
    }
}

@Composable
private fun Alert(a: HubAlert, accent: Color, onAction: () -> Unit) {
    NoticeCard(
        a.title, a.help, a.action,
        dot = if (a.danger) SeedyColors.Danger else if (a.key == "updates") accent else SeedyColors.Pending,
        background = if (a.danger) SeedyColors.Danger.copy(alpha = 0.08f) else SeedyColors.Panel,
        onAction = onAction,
    )
}

@Composable
private fun Link(link: HubLink, onClick: () -> Unit) {
    val color = when (link.tone) {
        Tone.Danger -> SeedyColors.Danger
        Tone.Pending -> SeedyColors.Pending
        Tone.Ok -> SeedyColors.Ok
        Tone.Accent -> LocalAccent.current
        Tone.Normal -> SeedyColors.Text3
    }
    LinkRow(link.name, link.sub, onClick, color, link.badge)
}

@Composable
private fun TaskLine(name: String, state: String, progress: Float) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(name, Modifier.weight(1f), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SText(state, size = 13.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
        }
        Bar(progress)
    }
}

@Composable
private fun Playing(state: ServerHubState, onMenu: (NowPlaying) -> Unit) {
    val playing = state.playing
    SectionTitle(if (playing == null) t("Playing now") else t("Playing now · {n}", "n" to playing.size))
    CssColumn(Modifier.padding(horizontal = 16.dp), gap = 10.dp) {
        when {
            playing == null -> Card(padding = 12.dp) { SText("…", size = 13.sp, color = SeedyColors.Text3) }
            playing.isEmpty() -> EmptyCard(t("Nothing playing"), t("When someone starts watching, it shows up here with how it’s playing on their device."), Modifier)
            else -> for (s in playing) SessionCard(s) { onMenu(s) }
        }
    }
}

@Composable
private fun SessionCard(s: NowPlaying, onMenu: () -> Unit) {
    // The 16:9 picture is 51.75 px on the board, and its 12 px lines (the method pill's too) are 16.8 px: 52 and 17 here.
    var lines by remember { mutableIntStateOf(1) }
    Card(Modifier.cssExcess(0.25f + 0.2f * lines), padding = 12.dp, gap = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumb(s.art, 92.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(s.title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AdminAvatar(s.user, avatarColour(s.userIndex), 18.dp, 10.sp, imageUrl = s.userImage)
                    SpacedDots(listOf(s.user, s.device), 12.sp, SeedyColors.Text2)
                }
            }
            if (s.canMessage || s.canStop) {
                RoundButton(AdminIcons.Dots, onMenu, iconSize = 18.dp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 8.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Dot(MethodDots.getValue(s.kind), 6.dp)
                SText(t(s.kind.label()), size = 12.sp, weight = 700, lineHeight = 1.4f, color = SeedyColors.Text, maxLines = 1)
            }
            SText(s.detail, Modifier.weight(1f).onSizeChanged { lines = maxOf(1, (it.height / 17f).roundToInt()) }, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
        Bar(s.progress, color = SeedyColors.White, height = 3.dp)
    }
}
