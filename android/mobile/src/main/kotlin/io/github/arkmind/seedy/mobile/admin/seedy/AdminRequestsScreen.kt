package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.seedy.AdminRequestsActions
import io.github.arkmind.seedy.core.admin.seedy.AdminRequestsState
import io.github.arkmind.seedy.core.admin.seedy.SeerrAccount
import io.github.arkmind.seedy.core.admin.seedy.SeerrUser
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.Dot
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.FieldBlock
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.MenuItem
import io.github.arkmind.seedy.mobile.admin.MenuSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.RadioRow
import io.github.arkmind.seedy.mobile.admin.TextSheet
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.mobile.settings.settingsRow
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.launch

private val Shows = listOf(
    Triple("showDiscover", "Discover row", "Popular titles not in the library"),
    Triple("showUpcomingMovies", "Upcoming movies row", "Requested movies not out yet"),
    Triple("showRequestButtons", "Request buttons", "On Seerr posters and details pages"),
    Triple("showRequestInSearch", "Request it in search", "When nothing in the library matches"),
)

private fun upcomingLabel(v: String) = if (v == "requestsAndRadarr") t("Seerr requests and Radarr calendar") else t("Seerr requests")

private fun roleOf(a: SeerrAccount): String = when {
    a.isAdmin -> t("Administrator")
    !a.maxParentalRating.isNullOrEmpty() -> t("Maturity limit: {rating}", "rating" to a.maxParentalRating)
    else -> t("User")
}

/** Seedy › Requests (no phone board: the server boards' kit with the web board AdminRequests' content). */
@Composable
fun AdminRequestsScreen(state: AdminRequestsState, actions: AdminRequestsActions, onBack: () -> Unit, openUrl: (String) -> Unit) {
    val sheet = rememberSheet()
    var keySheet by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf<SeerrAccount?>(null) }
    var menu by remember { mutableStateOf<SeerrAccount?>(null) }
    val scope = rememberCoroutineScope()
    SettingsPage(
        t("Requests"),
        t("Connect Seerr to show Discover, Upcoming movies and Request buttons. Approval rules and request limits stay in Seerr."),
        onBack,
        overlay = {
            SheetHost(sheet)
            if (keySheet) {
                val replacing = !state.config?.requests?.apiKeyHint.isNullOrEmpty()
                TextSheet(
                    if (replacing) t("Replace API key") else t("Add API key"),
                    t("Copy the key from Settings › General in Seerr. Seedy keeps it on the server and only shows its last four characters."),
                    t("Save key"), { v -> if (v.isNotBlank()) scope.launch { actions.saveKey(v)?.let(actions::say) } }, { keySheet = false },
                    placeholder = t("Seerr API key"), password = true, required = false,
                )
            }
            menu?.let { a ->
                MenuSheet(a.name, listOf(MenuItem(t("Link account")) { linking = a }, MenuItem(t("Unlink"), danger = true) { actions.unlink(a.userId) }), { menu = null })
            }
            linking?.let { a -> LinkSheet(a, state.accounts.orEmpty(), actions) { linking = null } }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        val c = state.config
        when {
            c == null && state.loadError != null -> ErrorCard(t("Couldn’t load Seedy’s settings"), actions::retry, Modifier.padding(top = 16.dp), detail = state.loadError.orEmpty())
            c == null -> LoadingCards(3)
            else -> {
                val r = c.requests
                Section(t("Seerr connection")) {
                    row {
                        var url by remember(r.url) { mutableStateOf(r.url) }
                        FieldBlock(
                            t("Address"), url, { url = it },
                            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                            help = t("As this Jellyfin server reaches it"), placeholder = "http://seerr.local:5055", keyboard = KeyboardType.Uri,
                            onDone = { actions.setUrl(url) }, onBlur = { actions.setUrl(url) },
                        )
                    }
                    row {
                        PickRow(
                            t("API key"), t("Stored on the server, never sent to browsers"),
                            if (!r.apiKeyHint.isNullOrEmpty()) t("Saved · ends in {hint}", "hint" to r.apiKeyHint) else t("No key saved"),
                        ) { keySheet = true }
                    }
                    row { Connection(state, actions) { openUrl(state.publicUrl ?: r.url) } }
                }
                Section(t("What Seedy shows")) {
                    for ((key, name, help) in Shows) {
                        val on = when (key) {
                            "showDiscover" -> r.showDiscover
                            "showUpcomingMovies" -> r.showUpcomingMovies
                            "showRequestButtons" -> r.showRequestButtons
                            else -> r.showRequestInSearch
                        }
                        row {
                            SwitchRow(t(name), t(help), on) { v ->
                                actions.set {
                                    when (key) {
                                        "showDiscover" -> it.copy(showDiscover = v)
                                        "showUpcomingMovies" -> it.copy(showUpcomingMovies = v)
                                        "showRequestButtons" -> it.copy(showRequestButtons = v)
                                        else -> it.copy(showRequestInSearch = v)
                                    }
                                }
                            }
                        }
                    }
                    row {
                        PickRow(t("Upcoming movies come from"), t("Upcoming shows always come from Jellyfin"), upcomingLabel(r.upcomingSource)) {
                            sheet.open(t("Upcoming movies come from"), choices(listOf("requests", "requestsAndRadarr"), ::upcomingLabel), r.upcomingSource) { v -> actions.set { it.copy(upcomingSource = v) } }
                        }
                    }
                }
                Section(t("Who can request"), t("Requests are made as the signed-in person, so their Seerr limits and auto-approval apply.")) {
                    row { RadioRow(t("Everyone with a Seerr account"), t("Profiles matched below can request."), r.who != "selected", onClick = { actions.setWho("everyone") }) }
                    row { RadioRow(t("Only profiles I switch on"), t("Others still see Discover, without Request buttons."), r.who == "selected", onClick = { actions.setWho("selected") }) }
                    row { SwitchRow(t("Allow 4K requests"), t("Needs a 4K server set up in Seerr"), r.allow4k) { v -> actions.set { it.copy(allow4k = v) } } }
                }
            }
        }

        SectionTitle(t("Accounts"))
        Accounts(state, actions, onLink = { linking = it }, onMenu = { menu = it })
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(t("Matched automatically when Seerr uses Jellyfin sign-in."), Modifier.weight(1f), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
            PillButton(if (state.matching) t("Matching…") else t("Match again"), actions::matchAgain, enabled = !state.matching, height = 36.dp)
        }
        if (c != null) AutoSaveLine(state.save)
    }
}

@Composable
private fun Connection(state: AdminRequestsState, actions: AdminRequestsActions, onOpen: () -> Unit) {
    val line = state.conn
    val hasUrl = !state.config?.requests?.url.isNullOrEmpty()
    Column(Modifier.settingsRow(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.padding(top = 6.dp)) { Dot(toneColor(line.tone)) }
            val text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight(800), color = SeedyColors.White)) { append(line.lead) }
                if (line.rest.isNotEmpty()) append(" " + line.rest)
            }
            androidx.compose.foundation.text.BasicText(
                text, Modifier.weight(1f),
                style = BaseText.copy(fontSize = 13.sp, lineHeight = (13 * 1.45).sp, color = SeedyColors.Text2),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (state.testing) t("Testing…") else t("Test connection"), actions::test, style = PillStyle.Light, enabled = hasUrl && !state.testing)
            PillButton(t("Open Seerr"), onOpen, enabled = hasUrl, trailing = { SIcon(WebIcons.External, size = 15.dp, color = SeedyColors.White) })
        }
    }
}

@Composable
private fun Accounts(state: AdminRequestsState, actions: AdminRequestsActions, onLink: (SeerrAccount) -> Unit, onMenu: (SeerrAccount) -> Unit) {
    val list = state.accounts
    Card(Modifier.padding(horizontal = 16.dp)) {
        when {
            list == null && state.accountsError != null -> Row(Modifier.settingsRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(SeedyColors.Danger)
                SText(t("Couldn’t load the accounts.") + " " + state.accountsError, Modifier.weight(1f), size = 13.sp, color = SeedyColors.Text2)
                PillButton(t("Try again"), actions::retryAccounts, height = 36.dp)
            }
            list == null -> repeat(3) { i ->
                if (i > 0) RowLine()
                Box(Modifier.settingsRow().heightIn(min = 60.dp))
            }
            else -> list.forEachIndexed { i, a ->
                if (i > 0) RowLine()
                val tap = when {
                    !a.linked -> Modifier.tap { onLink(a) }
                    a.matchedBy == "manual" -> Modifier.tap { onMenu(a) }
                    else -> Modifier
                }
                Row(
                    Modifier.fillMaxWidth().then(tap).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AdminAvatar(a.name, avatarColour(i), 44.dp, 18.sp, imageUrl = state.avatars[a.userId])
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        SText(a.name, size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Dot(if (a.linked) SeedyColors.Ok else SeedyColors.Pending, 6.dp)
                            SText(
                                if (a.linked) (a.seerrName?.takeIf { it.isNotEmpty() } ?: t("Seerr user {id}", "id" to a.seerrUserId.toString())) + " · " +
                                    (if (a.matchedBy == "manual") t("Linked by an admin") else t("Matched by Jellyfin sign-in"))
                                else t("No Seerr account") + " · " + t("Link account"),
                                size = 12.sp, weight = 600, color = if (a.linked) SeedyColors.Ok else SeedyColors.Pending, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        SText(roleOf(a), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                    }
                    BoardSwitch(a.linked && a.canRequest, { actions.setCanRequest(a.userId, it) }, enabled = a.linked)
                }
            }
        }
    }
}

/** Picks the Seerr account a profile requests as. */
@Composable
private fun LinkSheet(account: SeerrAccount, accounts: List<SeerrAccount>, actions: AdminRequestsActions, onDismiss: () -> Unit) {
    var users by remember { mutableStateOf<List<SeerrUser>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    LaunchedEffect(account.userId) {
        actions.seerrUsers().onSuccess { users = it }.onFailure { error = it.message ?: t("Seerr did not answer.") }
    }
    BottomSheet(onDismiss) {
        SheetHead(t("Link {name}", "name" to account.name), t("Pick the Seerr account {name} requests as. Their Seerr limits and auto-approval apply.", "name" to account.name))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            val list = users
            when {
                error != null -> SText(t("Couldn’t load Seerr’s users.") + " " + error, Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Danger)
                list == null -> repeat(3) { Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) }
                list.isEmpty() -> SText(t("Seerr has no users yet."), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                else -> list.forEachIndexed { i, u ->
                    val other = accounts.firstOrNull { it.seerrUserId == u.id && it.userId != account.userId }?.name
                    val current = account.seerrUserId == u.id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 58.dp).clip(RoundedCornerShape(12.dp)).background(if (current) SeedyColors.Surface else Color.Transparent)
                            .tap(enabled = !busy) {
                                busy = true
                                scope.launch {
                                    val err = actions.link(account.userId, u.id)
                                    busy = false
                                    if (err != null) actions.say(err) else onDismiss()
                                }
                            }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AdminAvatar(u.displayName, avatarColour(i), 36.dp, 15.sp, imageUrl = u.avatar)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SText(u.displayName, size = 15.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                            SText(other?.let { t("Linked to {name}", "name" to it) } ?: u.email?.takeIf { it.isNotEmpty() } ?: t("Not linked to a profile"), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                        }
                        if (current) SIcon(WebIcons.Check, size = 20.dp, color = accent, stroke = 2.6f)
                    }
                }
            }
        }
    }
}
