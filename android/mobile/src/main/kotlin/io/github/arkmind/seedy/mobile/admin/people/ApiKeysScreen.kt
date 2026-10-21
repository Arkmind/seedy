package io.github.arkmind.seedy.mobile.admin.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.ApiKeysActions
import io.github.arkmind.seedy.core.admin.people.ApiKeysState
import io.github.arkmind.seedy.core.admin.people.KeyRow
import io.github.arkmind.seedy.core.admin.people.StaleDays
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.CardNote
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.FormError
import io.github.arkmind.seedy.mobile.admin.Mono
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.admin.StatusLine
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.FootNote
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Server › API keys, from the web board "Server · API keys" in the phone kit. */
@Composable
fun ApiKeysScreen(state: ApiKeysState, actions: ApiKeysActions, onBack: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<KeyRow?>(null) }
    SettingsPage(
        t("API keys"),
        t("For apps that talk to this server without signing in, like Seerr, Sonarr or a monitoring tool. Each key has full admin access."),
        onBack,
        overlay = {
            if (adding) NewKeySheet(state, actions) { adding = false; actions.clearCreateError() }
            revoking?.let { k ->
                ConfirmSheet(
                    t("Revoke the key for {app}?", "app" to k.app),
                    t("{app} is cut off immediately. To connect it again you’ll have to give it a new key.", "app" to k.app),
                    t("Revoke key"), { actions.revoke(k.token) }, { revoking = null }, danger = true,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        PillRow {
            PillButton(t("New key"), { adding = true }, style = PillStyle.Light, leading = { SIcon(WebIcons.Plus, size = 15.dp, color = SeedyColors.InkInverse) })
        }
        state.created?.let { (app, key) -> Created(app, key, actions) }
        val keys = state.keys
        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            when {
                keys == null && state.error != null -> CardNote(t("Couldn’t load the keys.") + " " + state.error, action = t("Try again"), onAction = actions::reload)
                keys == null -> SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                keys.isEmpty() -> CardNote(t("No keys yet. Create one for each app that needs to reach the server."))
                else -> keys.forEachIndexed { i, k ->
                    if (i > 0) RowLine()
                    KeyLine(k, busy = state.busy == k.token) { revoking = k }
                }
            }
        }
        FootNote(t("Revoking a key cuts that app off immediately. Keys unused for {n} days are flagged so you can clean them up.", "n" to StaleDays))
    }
}

@Composable
private fun KeyLine(k: KeyRow, busy: Boolean, onRevoke: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(k.app, size = 15.sp, weight = 800, color = SeedyColors.White)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                BasicText(k.masked, style = Mono.copy(fontSize = 12.sp, color = SeedyColors.Text2), maxLines = 1)
                if (k.created.isNotEmpty()) {
                    SText("·", size = 12.sp, color = SeedyColors.Text3)
                    SText(k.created, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
            StatusLine(k.used, if (k.stale) SeedyColors.Pending else SeedyColors.Ok, color = if (k.stale) SeedyColors.Pending else SeedyColors.Text2, weight = 600)
        }
        PillButton(if (busy) t("Revoking…") else t("Revoke"), onRevoke, style = PillStyle.Danger, enabled = !busy, height = 36.dp)
    }
}

/** The key just made, in full this once, with a copy button. */
@Composable
private fun Created(app: String, key: String, actions: ApiKeysActions) {
    val accent = LocalAccent.current
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)
            .border(1.5.dp, accent, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SIcon(AdminIcons.Check, size = 18.dp, color = SeedyColors.Ok)
            SText(t("Key created for {app}", "app" to app), Modifier.weight(1f), size = 15.sp, weight = 800, color = SeedyColors.White)
        }
        SText(t("Copy it now. It won't be shown in full again."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SeedyColors.Bg).border(1.dp, SeedyColors.Surface2, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
            SelectionContainer { BasicText(key, style = Mono.copy(fontSize = 13.sp, color = SeedyColors.White)) }
        }
        PillButton(t("Copy key"), {
            clipboard.setText(AnnotatedString(key))
            actions.say(t("Key copied"))
        }, style = PillStyle.Light)
    }
}

@Composable
private fun NewKeySheet(state: ApiKeysState, actions: ApiKeysActions, onClose: () -> Unit) {
    var app by remember { mutableStateOf("") }
    var sent by remember { mutableStateOf(false) }
    // Closes once the key is made.
    LaunchedEffect(state.creating, state.createError) { if (sent && !state.creating && state.createError == null) onClose() }
    FormSheet(t("New key"), t("The key gives full admin access to this server. Name it after the app that will use it, so you know which one to revoke later."), onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(t("App name"))
            SeedyField(app, { app = it.take(64); sent = false; actions.clearCreateError() }, placeholder = "Home Assistant", onDone = { sent = true; actions.create(app) })
        }
        state.createError?.let { FormError(it) }
        SheetButtons(if (state.creating) t("Creating…") else t("Create key"), { sent = true; actions.create(app) }, onClose, enabled = !state.creating)
    }
}
