package io.github.arkmind.seedy.mobile.settings

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Board "Android · Settings · Profile and sign-in". */
@Composable
fun ProfilePage(state: SettingsState, actions: SettingsActions, nav: SettingsNav) {
    val sheet = rememberSheet()
    val accent = LocalAccent.current
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { actions.uploadPicture(it, mime) }
        }
    }
    SettingsPage(
        t("Profile and sign-in"),
        t("Your picture, password, requests and privacy. These follow your profile to every device."),
        nav.back,
        overlay = {
            SheetHost(sheet)
            if (sheet.form == "password") PasswordSheet(state, actions, sheet::close)
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ProfileSquare(state.name, state.imageUrl, 0, size = 96.dp, radius = 48.dp, letterSize = 42.sp)
            Box(Modifier.height(40.dp).tap { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                SText(t("Change picture"), size = 13.sp, weight = 700, color = accent)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(state.name, size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
                if (state.isAdmin) AdminPill()
            }
            SText(signedInOn(state), size = 12.sp, color = SeedyColors.Text3, align = TextAlign.Center)
            SText(t("Only an admin can change your name."), size = 12.sp, color = Faint, align = TextAlign.Center)
        }

        SectionTitle(t("Sign in a TV with a code"))
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(16.dp)) {
            CodeForm(state, actions)
        }

        Section(t("Password")) {
            row {
                PickRow(t("Password"), state.passwordChanged ?: t("The same password for every Jellyfin app you use"), t("Change"), valueColor = accent) {
                    actions.resetPasswordForm()
                    sheet.form = "password"
                }
            }
        }

        if (state.requests.connected) {
            Section(t("Requests"), t("Your Seerr account is used when you request a movie or show.")) {
                row {
                    val account = state.requests.account
                    val help = when {
                        account?.linkedBy == "manual" -> t("Linked by your admin")
                        account != null || state.requests.canRequest -> t("Linked through your Jellyfin sign-in")
                        else -> t("Not linked yet · ask your admin to link your Seerr account")
                    }
                    PickRow(t("Seerr account"), help, (account?.name ?: state.name).lowercase(), onClick = nav.requests)
                }
                row {
                    SwitchRow(t("Tell me when a request is ready"), t("A notification on this phone when something you asked for arrives"), state.device.notifyRequests, device = true) { on ->
                        actions.setDevice { it.copy(notifyRequests = on) }
                    }
                }
            }
        }

        if (state.seedy) {
            Section(t("Privacy"), t("Your admin can turn these off for everyone.")) {
                val p = state.prefs.privacy
                row { SwitchRow(t("Personal recommendations"), t("Because you watched, genre rows and row order learn from your plays"), p.personalRecs) { actions.setPrivacy(it, p.countInTop10) } }
                row { SwitchRow(t("Count my plays in the Top 10"), t("Others see totals only, never who watched"), p.countInTop10) { actions.setPrivacy(p.personalRecs, it) } }
                // Viewing history has no page in the app yet.
                if (state.historyEnabled) row { PickRow(t("Viewing history"), t("See, hide or clear what you watched"), t("Open")) {} }
            }
        }

        Section(t("Devices")) {
            row {
                PickRow(t("Signed in on"), t("Sign out the ones you don’t use any more"), state.devices?.let { tn(it, "{n} device", "{n} devices") } ?: "") {}
            }
        }

        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            WideButton(t("Switch profile"), SeedyColors.White, actions::switchProfile)
            WideButton(t("Sign out"), SeedyColors.Danger, actions::signOut)
        }
        SaveLine(state.saving, state.error)
    }
}

/** "Signed in on Pixel 9 · Seedy for Android". */
internal fun signedInOn(state: SettingsState): String =
    if (state.deviceName.isEmpty()) t("Seedy for Android") else t("Signed in on {device} · {app}", "device" to state.deviceName, "app" to t("Seedy for Android"))

@Composable
internal fun WideButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(50.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SText(label, size = 14.sp, weight = 800, color = color, maxLines = 1)
    }
}

/** Quick Connect: the line, the code field and Approve, then what happened. */
@Composable
internal fun CodeForm(state: SettingsState, actions: SettingsActions) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val c = state.code
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SText(t("Choose Quick Connect on the TV, then type the code it shows. The TV signs in as you."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.TextHero)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Surface)
                    .then(if (focused || c.code.isNotEmpty()) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = c.code,
                    onValueChange = actions::setCode,
                    singleLine = true,
                    interactionSource = interaction,
                    textStyle = BaseText.copy(fontSize = 22.sp, fontWeight = FontWeight(800), color = SeedyColors.White, letterSpacing = 0.3.em),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { actions.approveCode() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Box(
                Modifier.height(52.dp).clip(CircleShape).background(SeedyColors.Inverse).tap(enabled = !c.busy, onClick = actions::approveCode).padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(if (c.busy) t("Signing in…") else t("Approve"), size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
            }
        }
        c.message?.let { SText(it, size = 13.sp, weight = 600, lineHeight = 1.45f, color = if (c.ok == true) SeedyColors.Ok else SeedyColors.Danger) }
    }
}

/** "Approve a TV" on the settings home: the same form in a sheet. */
@Composable
internal fun CodeSheet(state: SettingsState, actions: SettingsActions, onClose: () -> Unit) {
    FormSheet(t("Sign in a TV with a code"), null, onClose) { CodeForm(state, actions) }
}

/** Current password, then the new one twice; an empty new password removes it. */
@Composable
private fun PasswordSheet(state: SettingsState, actions: SettingsActions, onClose: () -> Unit) {
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var mismatch by remember { mutableStateOf(false) }
    LaunchedEffect(state.password.done) { if (state.password.done) onClose() }
    FormSheet(t("Change password"), t("The same password for every Jellyfin app you use"), onClose) {
        FieldLabel(t("Current password"))
        SeedyField(current, { current = it }, password = true, imeAction = ImeAction.Next)
        FieldLabel(t("New password"))
        SeedyField(new, { new = it; mismatch = false }, password = true, imeAction = ImeAction.Next)
        FieldLabel(t("Repeat new password"))
        SeedyField(again, { again = it; mismatch = false }, password = true)
        val error = if (mismatch) t("The new passwords don't match.") else state.password.error
        error?.let { SText(it, size = 13.sp, weight = 600, color = SeedyColors.Danger) }
        PrimaryButton(if (state.password.busy) t("Changing…") else t("Change password"), {
            if (new != again) mismatch = true else actions.changePassword(current, new)
        }, enabled = !state.password.busy)
    }
}
