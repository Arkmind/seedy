package io.github.arkmind.seedy.mobile.admin.media

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.media.PathChange
import io.github.arkmind.seedy.core.admin.media.ServerSettingsActions
import io.github.arkmind.seedy.core.admin.media.ServerSettingsState
import io.github.arkmind.seedy.core.admin.media.ServerSettingsViewModel
import io.github.arkmind.seedy.core.admin.media.SettingActions
import io.github.arkmind.seedy.core.admin.media.SplashState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.FieldBlock
import io.github.arkmind.seedy.mobile.admin.FieldSection
import io.github.arkmind.seedy.mobile.admin.MonoText
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Board "Android · Server · Settings", and its Expert settings page. */
@Composable
fun ServerSettingsPageRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val context = LocalContext.current
    val vm = viewModel { ServerSettingsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    var expert by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/png"
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { vm.uploadSplash(it, mime) }
        }
    }
    LaunchedEffect(Unit) { vm.load() }
    BackHandler(enabled = expert) { expert = false }
    ServerSettingsScreen(
        state, vm, expert,
        onBack = { if (expert) expert = false else nav.back() },
        openExpert = { expert = true },
        pickSplash = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
    )
}

private enum class SettingsSheet { None, Splash, PathSubs, CastApps }

@Composable
fun ServerSettingsScreen(
    state: ServerSettingsState,
    actions: ServerSettingsActions,
    expert: Boolean,
    onBack: () -> Unit,
    openExpert: () -> Unit,
    pickSplash: () -> Unit,
) {
    val sheets = rememberMediaSheets()
    var open by remember { mutableStateOf(SettingsSheet.None) }
    val rows = object : SettingActions by actions {
        override fun open(key: String) = when (key) {
            "expert" -> openExpert()
            "splash" -> open = SettingsSheet.Splash
            "pathSubs" -> open = SettingsSheet.PathSubs
            "castApps" -> open = SettingsSheet.CastApps
            else -> actions.open(key)
        }
    }
    SettingsPage(
        if (expert) t("Expert settings") else t("Settings"),
        if (expert) t("Settings Jellyfin only exposes in its config file. Change them with care.") else t("Server name, paths, sign-in, performance and logging. Jellyfin’s General and Branding pages in one place."),
        onBack = onBack,
        overlay = {
            MediaSheetHost(sheets, actions::setNumber)
            state.picker?.let { FolderPickerSheet(it, actions) }
            state.pathChange?.let { PathSheet(it, actions) }
            when (open) {
                SettingsSheet.Splash -> SplashSheet(state.splash, actions, pickSplash) { open = SettingsSheet.None }
                SettingsSheet.PathSubs -> PairsSheet(
                    t("Path substitution"),
                    t("When an app reads files directly, it sees the path on the right instead of the server’s path on the left."),
                    state.pathSubs, t("Server path") to t("Path apps see"), "/media/movies" to "\\\\nas\\movies", t("Add a rule"),
                    { actions.setPairs("pathSubs", it) }, { open = SettingsSheet.None },
                )
                SettingsSheet.CastApps -> PairsSheet(
                    t("Cast receiver apps"),
                    t("The Chromecast apps people can choose from when they cast. Each needs its Google Cast application ID."),
                    state.castApps, t("Name") to t("Application ID"), "Stable" to "F007D354", t("Add an app"),
                    { actions.setPairs("castApps", it) }, { open = SettingsSheet.None },
                )
                SettingsSheet.None -> Unit
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        when {
            !state.loaded && state.loadError != null -> {
                SText(state.loadError.orEmpty(), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), size = 13.sp, color = SeedyColors.Danger)
                PillRow { PillButton(t("Try again"), actions::retry) }
            }
            !state.loaded -> SText("…", Modifier.padding(20.dp), size = 13.sp, color = SeedyColors.Text3)
            expert -> SettingCards(state.expert, rows, sheets)
            else -> {
                ServerCard(state, actions)
                SettingCards(state.groups, rows, sheets)
            }
        }
        SaveLine(state.saving, state.error)
    }
}

/** The board's Server card: the name and the sign-in message, typed in place. */
@Composable
private fun ServerCard(state: ServerSettingsState, actions: ServerSettingsActions) {
    FieldSection(t("Server")) {
        FieldBlock(t("Server name"), state.serverName, actions::setServerName, help = t("Shown to apps when they find this server"), placeholder = state.serverNamePlaceholder, autoCorrect = true, helpRatio = null)
        FieldBlock(t("Sign-in message"), state.signInMessage, actions::setSignInMessage, help = t("Shown under the sign-in form"), lines = 2, autoCorrect = true, helpRatio = null)
    }
}

/** A folder picked for a path: it's used after a restart, so ask whether to restart now. */
@Composable
private fun PathSheet(change: PathChange, actions: ServerSettingsActions) {
    FormSheet(t("Save and restart the server?"), null, actions::cancelPath) {
        MonoText(change.path, color = SeedyColors.White)
        SText(t("Everyone watching is interrupted for a minute while Jellyfin restarts. Seedy reconnects on its own when the server is back."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        SheetButtons(Modifier.padding(top = 4.dp)) {
            PillButton(t("Cancel"), actions::cancelPath)
            PillButton(t("Save only"), { actions.savePath(false) })
            PillButton(t("Save and restart"), { actions.savePath(true) }, style = PillStyle.Light)
        }
    }
}

/** The splash screen: the image apps show while they load, on or off, and a picture of your own. */
@Composable
private fun SplashSheet(s: SplashState, actions: ServerSettingsActions, pick: () -> Unit, onDismiss: () -> Unit) {
    FormSheet(t("Splash screen"), t("Image shown while apps load"), onDismiss, scroll = true) {
        Artwork(s.imageUrl, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)), placeholder = SeedyColors.Surface)
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(t("Show a splash screen"), Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
            BoardSwitch(s.enabled, actions::setSplash)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(if (s.uploading) t("Uploading…") else t("Upload image"), pick, style = PillStyle.Light, enabled = !s.uploading)
            if (s.custom) PillButton(t("Use the default"), actions::resetSplash)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton(t("Done"), onDismiss) }
    }
}
