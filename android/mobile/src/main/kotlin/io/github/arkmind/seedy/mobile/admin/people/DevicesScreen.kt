package io.github.arkmind.seedy.mobile.admin.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.DeviceRow
import io.github.arkmind.seedy.core.admin.people.DevicesActions
import io.github.arkmind.seedy.core.admin.people.DevicesState
import io.github.arkmind.seedy.core.admin.people.filtered
import io.github.arkmind.seedy.core.admin.people.signOutOthersText
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.ActionSheet
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.FootNote
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SpacedDots
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.DeviceTag
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/* Busy servers collect hundreds of devices; show them a page at a time. */
private const val Page = 40

/** Server › Devices, from the web board "Server · Devices" in the phone kit. */
@Composable
fun DevicesScreen(state: DevicesState, actions: DevicesActions, onBack: () -> Unit, open: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    var who by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableIntStateOf(Page) }
    var menu by remember { mutableStateOf<DeviceRow?>(null) }
    var renaming by remember { mutableStateOf<DeviceRow?>(null) }
    var removing by remember { mutableStateOf<DeviceRow?>(null) }
    var signingOut by remember { mutableStateOf(false) }
    SettingsPage(
        t("Devices"),
        t("Every app that has signed in. Removing one signs it out; it comes back the next time someone signs in with it."),
        onBack,
        overlay = {
            menu?.let { d ->
                ActionSheet(d.name, listOf(t("Rename") to { renaming = d }, t("Remove") to { if (d.mine) actions.remove(d.id) else removing = d }), { menu = null }, danger = setOf(1))
            }
            renaming?.let { d -> RenameSheet(d, { actions.rename(d.id, it) }) { renaming = null } }
            removing?.let { d ->
                ConfirmSheet(
                    t("Remove {name}?", "name" to d.name),
                    if (d.playing != null) t("{name} is signed out and its playback stops. It comes back the next time someone signs in with it.", "name" to d.name)
                    else t("{name} is signed out. It comes back the next time someone signs in with it.", "name" to d.name),
                    t("Remove device"), { actions.remove(d.id) }, { removing = null }, danger = true,
                )
            }
            if (signingOut) {
                ConfirmSheet(t("Sign out all other devices?"), signOutOthersText(state), t("Sign out all other devices"), actions::signOutOthers, { signingOut = false }, danger = true)
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        PillRow {
            PillButton(
                t("Sign out all other devices"),
                { if (state.devices.orEmpty().any { !it.mine }) signingOut = true else actions.signOutOthers() },
                style = PillStyle.Danger,
                enabled = !state.busy && state.devices != null,
            )
        }
        SeedyField(query, { query = it; shown = Page }, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), placeholder = t("Search devices or apps"))
        if (state.people.isNotEmpty()) {
            TabPills(listOf<Pair<String?, String>>(null to t("Everyone")) + state.people, who, { who = it; shown = Page }, top = 12.dp)
        }
        val rows = state.filtered(who, query)
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
            when {
                state.devices == null && state.error != null -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SText(t("Couldn’t load the devices") + ". " + state.error, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
                    PillButton(t("Try again"), actions::reload)
                }
                state.devices == null -> SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                rows.isEmpty() -> SText(
                    if (query.isNotBlank() || who != null) t("No devices match. Try another name or person.") else t("No devices have signed in yet."),
                    Modifier.padding(16.dp), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2,
                )
                else -> rows.take(shown).forEachIndexed { i, d ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
                    DeviceLine(d) { menu = d }
                }
            }
        }
        if (rows.size > shown) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.Center) {
                PillButton(t("Show more"), { shown += Page }, trailing = {
                    SText(t("{n} more", "n" to formatNumber(rows.size - shown)), size = 13.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
                })
            }
        }
        FootNote(t("To stop a person signing in from a device, use their profile's Libraries and devices tab in {users}.", "users" to t("Users")), onClick = { open("users") })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceLine(d: DeviceRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(PeopleIcons.of(d.kind), size = 20.dp, color = SeedyColors.TextHero)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SText(d.name, size = 15.sp, weight = 800, color = SeedyColors.White)
                if (d.mine) DeviceTag(t("This device"))
            }
            if (d.playing != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).background(SeedyColors.Ok, CircleShape))
                    SText(d.playing.orEmpty(), size = 12.sp, weight = 600, color = SeedyColors.Ok, maxLines = 1)
                }
            } else {
                SText(d.last, size = 12.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (d.user.isNotEmpty()) AdminAvatar(d.user, avatarColour(d.userIndex), 16.dp, 9.sp, imageUrl = d.userImage)
                SpacedDots(listOf(d.user, d.app), 12.sp, SeedyColors.Text3)
            }
        }
        SIcon(AdminIcons.Dots, size = 18.dp, color = SeedyColors.Text3)
    }
}

/** Renaming only changes how it shows in the admin lists; empty goes back to the name the app reports. */
@Composable
private fun RenameSheet(d: DeviceRow, onSave: (String) -> Unit, onClose: () -> Unit) {
    var name by remember { mutableStateOf(d.name) }
    FormSheet(t("Rename device"), t("Only changes how it shows here and in other admin lists."), onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(t("Name"))
            SeedyField(name, { name = it.take(64) }, placeholder = d.reported, onDone = { onSave(name); onClose() })
            SText(
                if (d.reported.isNotEmpty()) t("Leave it empty to use the name the app reports ({name}).", "name" to d.reported) else t("Leave it empty to use the name the app reports."),
                size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            PillButton(t("Cancel"), onClose)
            PillButton(t("Rename"), { onSave(name); onClose() }, style = PillStyle.Light)
        }
    }
}
