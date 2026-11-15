package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.MyDeviceRow
import io.github.arkmind.seedy.core.settings.MyDevicesActions
import io.github.arkmind.seedy.core.settings.MyDevicesState
import io.github.arkmind.seedy.mobile.admin.CardNote
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.IconTile
import io.github.arkmind.seedy.mobile.admin.LoadingDots
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

private val DangerTint = Color(0x24FF8A8A)
private val OkTint = Color(0x1F9FE0B0)
private val PendingTint = Color(0x24FFC98A)

/**
 * Settings › Profile › Signed in on (AndroidSettingsDevices board): this phone, then every other device signed in to
 * the profile with its Sign out, and Sign out everywhere else. This phone signs out from Settings.
 */
@Composable
fun MyDevicesPage(state: MyDevicesState, actions: MyDevicesActions, nav: SettingsNav) {
    var leaving by remember { mutableStateOf<MyDeviceRow?>(null) }
    var everywhere by remember { mutableStateOf(false) }
    val list = state.devices
    val here = list.orEmpty().filter { it.current }
    val others = list.orEmpty().filter { !it.current }
    SettingsPage(
        t("Signed in on"),
        t("Everywhere you’re signed in. A device you sign out stops right away and needs your password or a Quick Connect code to come back."),
        nav.back,
        overlay = {
            leaving?.let { d ->
                ConfirmSheet(
                    t("Sign out {name}?", "name" to d.name), t("It stops right away and needs a password or a Quick Connect code to get back in."),
                    t("Sign out"), { actions.signOut(d.id) }, { leaving = null }, danger = true,
                )
            }
            if (everywhere) {
                ConfirmSheet(t("Sign out everywhere else?"), state.everywhere, t("Sign out everywhere else"), actions::signOutOthers, { everywhere = false }, danger = true)
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        if (list == null) {
            SectionTitle(t("This phone"))
            DeviceCard {
                if (state.error != null) CardNote(state.error.orEmpty(), action = t("Try again"), onAction = actions::reload) else LoadingDots()
            }
        } else {
            if (here.isNotEmpty()) {
                SectionTitle(t("This phone"))
                DeviceCard { here.forEach { DeviceItem(it, null) } }
            }
            SectionTitle(if (others.isEmpty()) t("Other devices") else t("Other devices · {n}", "n" to others.size))
            DeviceCard {
                if (others.isEmpty()) CardNote(t("You’re not signed in anywhere else."))
                others.forEachIndexed { i, d ->
                    if (i > 0) RowLine()
                    DeviceItem(d) { leaving = d }
                }
            }
            if (others.isNotEmpty()) {
                CssColumn(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 26.dp), gap = 8.dp) {
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel)
                            .tap(enabled = !state.busy) { everywhere = true },
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(t("Sign out everywhere else"), size = 15.sp, weight = 800, color = SeedyColors.Danger, maxLines = 1)
                    }
                    SText(state.everywhere, Modifier.padding(horizontal = 6.dp).cssText(12, 1.45f), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
                }
            }
        }
        SText(
            t("Your admin can see these devices too, and sign them out."),
            Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 40.dp).cssText(12, 1.45f),
            size = 12.sp, lineHeight = 1.45f, color = Faint,
        )
    }
}

/** A card whose rows add up the CSS way, so the page under it keeps in step with the board. */
@Composable
private fun DeviceCard(content: @Composable () -> Unit) =
    CssColumn(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel), content = content)

private fun icon(kind: DeviceKind) = when (kind) {
    DeviceKind.Tv -> WebIcons.DeviceTv
    DeviceKind.Phone -> WebIcons.DevicePhone
    DeviceKind.Tablet -> WebIcons.DeviceTablet
    DeviceKind.Computer -> WebIcons.DeviceLaptop
}

/** A device: its kind, name and tags, app and network, what it plays, when it was used; Sign out (red when it's suspicious). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceItem(d: MyDeviceRow, onSignOut: (() -> Unit)?) {
    val text = remember { CssBox() }
    Row(Modifier.fillMaxWidth().padding(14.dp).cssRow(text, 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        IconTile(icon(d.kind), SeedyColors.Text, 40.dp, 20.dp, radius = 12.dp)
        CssColumn(Modifier.weight(1f), 3.dp, text) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SText(d.name, size = 14.sp, weight = 800, color = SeedyColors.White)
                if (d.current) Tag(t("This phone"), SeedyColors.Surface, SeedyColors.TextHero)
                if (d.playingNow) Tag(t("Playing now"), OkTint, SeedyColors.Ok, dot = true)
                if (d.isNew) Tag(t("New"), PendingTint, SeedyColors.Pending)
            }
            AppLine(d)
            d.playing?.let { SText(it, size = 12.sp, weight = 700, color = SeedyColors.Ok) }
            SText(d.status, size = 12.sp, color = if (d.current) SeedyColors.Ok else SeedyColors.Text2)
            d.warning?.let { Warning(it) }
        }
        if (onSignOut != null) {
            val red = d.warning != null
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(11.dp)).background(if (red) DangerTint else SeedyColors.Surface).tap(onClick = onSignOut).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(t("Sign out"), size = 12.sp, weight = 800, color = if (red) SeedyColors.Danger else SeedyColors.White, maxLines = 1)
            }
        }
    }
}

/** "Seedy web · Away · 82.64.113.20", the address in monospace. */
@Composable
private fun AppLine(d: MyDeviceRow) {
    val text = buildAnnotatedString {
        append(d.app)
        d.ip?.let { ip ->
            if (d.app.isNotEmpty()) append(" · ")
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(ip) }
        }
    }
    BasicText(text, Modifier.cssText(12, 1.4f), style = BaseText.copy(fontSize = 12.sp, lineHeight = 1.4.em, color = SeedyColors.Text3))
}

@Composable
private fun Tag(label: String, background: Color, color: Color, dot: Boolean = false) {
    // 2 + 15 + 2 as Chrome boxes 11 px Noto; Compose would make the text 16 tall.
    Row(
        Modifier.height(19.dp).clip(CircleShape).background(background).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot) Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        SText(label, size = 11.sp, weight = 800, color = color, maxLines = 1)
    }
}

@Composable
private fun Warning(text: String) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SIcon(WebIcons.Warn, size = 14.dp, color = SeedyColors.Pending, modifier = Modifier.padding(top = 2.dp))
        SText(text, Modifier.cssText(12, 1.45f), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Pending)
    }
}
