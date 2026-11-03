package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.DeviceRow
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.MyDevicesActions
import io.github.arkmind.seedy.core.settings.MyDevicesState
import io.github.arkmind.seedy.mobile.admin.CardNote
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SpacedDots
import io.github.arkmind.seedy.mobile.admin.people.deviceIcon
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * Settings › Profile › Signed in on (the server Devices board's rows, for one person): every app signed in to this
 * profile, newest first, each with Sign out; this phone is marked and stays.
 */
@Composable
fun MyDevicesPage(state: MyDevicesState, actions: MyDevicesActions, nav: SettingsNav) {
    var leaving by remember { mutableStateOf<DeviceRow?>(null) }
    var others by remember { mutableStateOf(false) }
    val list = state.devices
    SettingsPage(
        t("Signed in on"),
        t("Apps signed in to your profile. Signing one out ends its session there; it needs your password to come back."),
        nav.back,
        overlay = {
            leaving?.let { d ->
                ConfirmSheet(
                    t("Sign out {name}?", "name" to d.name), t("Whoever uses it signs in again to keep watching."),
                    t("Sign out"), { actions.signOut(d.id) }, { leaving = null }, danger = true,
                )
            }
            if (others) {
                val n = list.orEmpty().count { !it.mine }
                ConfirmSheet(
                    t("Sign out all other devices?"), tn(n, "{n} device is signed out. This phone stays signed in.", "{n} devices are signed out. This phone stays signed in."),
                    t("Sign out all other devices"), actions::signOutOthers, { others = false }, danger = true,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        if (list.orEmpty().any { !it.mine }) {
            PillRow(top = 16.dp) {
                PillButton(t("Sign out all other devices"), { others = true }, style = PillStyle.Danger, enabled = !state.busy)
            }
        }
        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            when {
                list == null && state.error != null -> CardNote(state.error.orEmpty(), action = t("Try again"), onAction = actions::reload)
                list == null -> SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                list.isEmpty() -> CardNote(t("No devices are signed in to your profile."))
                else -> list.forEachIndexed { i, d ->
                    if (i > 0) RowLine()
                    DeviceItem(d) { leaving = d }
                }
            }
        }
        FootNote(t("To sign out this phone, use Sign out in Settings."))
    }
}

/** A device: its kind, its name (this phone tagged), when it was last used and with which app; Sign out. */
@Composable
private fun DeviceItem(d: DeviceRow, onSignOut: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(deviceIcon(d.kind), size = 20.dp, color = SeedyColors.TextHero)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SText(d.name, size = 15.sp, weight = 800, color = SeedyColors.White)
                if (d.mine) DeviceTag()
            }
            SpacedDots(listOf(d.last, d.app), 12.sp, SeedyColors.Text3)
        }
        if (!d.mine) PillButton(t("Sign out"), onSignOut, style = PillStyle.Danger, height = 36.dp)
    }
}
