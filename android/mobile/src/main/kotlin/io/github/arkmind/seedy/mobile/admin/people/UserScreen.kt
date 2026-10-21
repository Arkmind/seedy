package io.github.arkmind.seedy.mobile.admin.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.UserEditActions
import io.github.arkmind.seedy.core.admin.people.UserEditState
import io.github.arkmind.seedy.core.admin.people.UserTab
import io.github.arkmind.seedy.core.admin.people.bitrateOptions
import io.github.arkmind.seedy.core.admin.people.flag
import io.github.arkmind.seedy.core.admin.people.int
import io.github.arkmind.seedy.core.admin.people.labelOf
import io.github.arkmind.seedy.core.admin.people.lockOptions
import io.github.arkmind.seedy.core.admin.people.long
import io.github.arkmind.seedy.core.admin.people.streamOptions
import io.github.arkmind.seedy.core.admin.people.syncPlayOptions
import io.github.arkmind.seedy.core.admin.people.text
import io.github.arkmind.seedy.core.admin.people.withValue
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ActionSheet
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SaveBar
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.admin.TextSheet
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.CardRows
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.serialization.json.JsonPrimitive

/** Board "Android · Server · Edit user": the header, the tabs and the account tab; the other tabs in UserTabs.kt. */
@Composable
fun UserScreen(state: UserEditState, actions: UserEditActions, onBack: () -> Unit, onPickPhoto: () -> Unit, open: (String) -> Unit) {
    val sheet = rememberSheet()
    var confirm by remember { mutableStateOf<String?>(null) }
    var photoMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    // Leaving with unsaved changes asks first.
    BackHandler(enabled = state.changes > 0) { leaving = true }
    val back = { if (state.changes > 0) leaving = true else onBack() }
    PeoplePage(
        state.name.ifEmpty { if (state.error != null) t("User") else "…" },
        lede = if (state.loaded) state.lede else state.error?.let { t("Couldn’t load this user. {error}", "error" to it) } ?: "…",
        onBack = back,
        onTitle = if (state.loaded) ({ renaming = true }) else null,
        overlay = {
            SheetHost(sheet)
            if (state.changes > 0) {
                SaveBar(tn(state.changes, "{n} unsaved change", "{n} unsaved changes"), actions::discard, actions::save, saving = state.saving)
            }
            if (photoMenu) {
                ActionSheet(
                    t("Change photo"),
                    listOf(t("Upload a new photo") to onPickPhoto, t("Remove photo") to actions::removePhoto),
                    { photoMenu = false },
                    danger = setOf(1),
                )
            }
            if (renaming) {
                TextSheet(t("Rename {name}", "name" to state.name), null, t("Rename"), actions::rename, { renaming = false }, initial = state.name, placeholder = state.savedName)
            }
            when (confirm) {
                "signout" -> ConfirmSheet(
                    t("Sign {name} out everywhere?", "name" to state.savedName),
                    t("Every app and device {name} is signed in on has to sign in again.", "name" to state.savedName) + if (state.isMe) " " + t("This phone stays signed in.") else "",
                    t("Sign out everywhere"), actions::signOutEverywhere, { confirm = null },
                )
                "delete" -> ConfirmSheet(
                    t("Delete {name}?", "name" to state.savedName),
                    t("{name}’s account, watch history, favourites and settings are deleted. This can’t be undone.", "name" to state.savedName),
                    t("Delete user"), actions::delete, { confirm = null }, danger = true,
                )
                "removePassword" -> ConfirmSheet(
                    t("Remove {name}’s password?", "name" to state.savedName),
                    t("Anyone who can reach the sign-in screen can then sign in as {name} without a password.", "name" to state.savedName),
                    t("Remove password"), actions::removePassword, { confirm = null }, danger = true,
                )
            }
            if (leaving) {
                ConfirmSheet(
                    t("Leave without saving?"),
                    t("Your changes to {name}’s account haven’t been saved yet. If you leave now, they’re lost.", "name" to state.savedName),
                    t("Leave without saving"), { actions.discard(); onBack() }, { leaving = false }, danger = true,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(if (state.changes > 0) Alignment.Center else Alignment.BottomCenter))
        },
    ) {
        if (!state.loaded) return@PeoplePage
        Identity(state)
        Actions(state, onPhoto = { if (state.hasImage) photoMenu = true else onPickPhoto() }, onConfirm = { confirm = it })
        TabPills(state.tabs.map { it to t(it.label) }, state.tab, actions::tab)
        when (state.tab) {
            UserTab.Account -> AccountTab(state, actions, sheet)
            UserTab.Access -> AccessTab(state, actions)
            UserTab.Parental -> ParentalTab(state, actions, sheet)
            UserTab.Password -> PasswordTab(state, actions, onRemove = { confirm = "removePassword" })
            UserTab.Seedy -> SeedyTab(state, actions, open)
        }
        SaveBarRoom()
    }
}

/** The avatar with where the person is and how long they've had an account. */
@Composable
private fun Identity(state: UserEditState) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val disabled = state.policy.flag("IsDisabled")
        AdminAvatar(
            state.name, if (disabled) SeedyColors.Text3 else avatarColour(state.index), 64.dp, 28.sp,
            imageUrl = state.image, online = state.online, dot = 14.dp, dotInset = 0.dp, ring = SeedyColors.Bg,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(state.presence, size = 13.sp, weight = 600, color = if (state.online) SeedyColors.Ok else SeedyColors.Text2)
            if (state.meta.isNotEmpty()) SText(state.meta, size = 12.sp, color = SeedyColors.Text3)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Actions(state: UserEditState, onPhoto: () -> Unit, onConfirm: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(if (state.photoBusy) t("Uploading…") else t("Change photo"), onPhoto, enabled = !state.photoBusy)
        PillButton(t("Sign out everywhere"), { onConfirm("signout") })
        // Not the account you're signed in with.
        PillButton(t("Delete"), { onConfirm("delete") }, style = PillStyle.Danger, enabled = !state.isMe)
    }
}

/** "Changed · was off", or [help] when the row is as saved. */
internal fun helpFor(state: UserEditState, key: String, help: String, was: () -> String): String =
    if (state.changed(key)) t("Changed · was {value}", "value" to was()) else help

internal fun CardRows.switch(state: UserEditState, actions: UserEditActions, key: String, name: String, help: String, default: Boolean = false, enabled: Boolean = true) {
    row {
        val on = state.policy.flag(key, default)
        SwitchRow(name, helpFor(state, key, help) { if (state.saved.flag(key, default)) t("on") else t("off") }, on, enabled = enabled) { actions.setFlag(key, it) }
    }
}

internal fun <T> CardRows.pick(state: UserEditState, sheet: SheetState, key: String, name: String, help: String, options: List<Choice<T>>, value: T, was: T, onPick: (T) -> Unit) {
    row {
        val list = withValue(withValue(options, value), was)
        PickRow(name, helpFor(state, key, help) { labelOf(list, was) }, labelOf(list, value)) { sheet.open(name, list, value, onPick) }
    }
}

@Composable
private fun AccountTab(state: UserEditState, actions: UserEditActions, sheet: SheetState) {
    val p = state.policy
    val s = state.saved
    val name = state.name
    Section(t("Account")) {
        switch(state, actions, "IsAdministrator", t("Administrator"), t("Full access to the server pages and every library"), enabled = !state.isMe)
        switch(state, actions, "EnableRemoteAccess", t("Allow remote access"), t("Sign in from outside the home network"), default = true)
        switch(state, actions, "IsHidden", t("Hide from the sign-in screen"), t("{name} must type the name to sign in", "name" to name))
        // Not your own account; an administrator loses admin access first.
        switch(state, actions, "IsDisabled", t("Disable account"), t("Blocks sign-in without deleting anything"), enabled = !state.isMe && !(p.flag("IsAdministrator") && !p.flag("IsDisabled")))
        pick(state, sheet, "LoginAttemptsBeforeLockout", t("Lock after failed sign-ins"), t("Counts wrong passwords in a row"), lockOptions(p.flag("IsAdministrator")), p.int("LoginAttemptsBeforeLockout", -1), s.int("LoginAttemptsBeforeLockout", -1)) {
            actions.set("LoginAttemptsBeforeLockout", JsonPrimitive(it))
        }
        pick(state, sheet, "MaxActiveSessions", t("Simultaneous streams"), t("Devices playing at the same time"), streamOptions(), p.int("MaxActiveSessions"), s.int("MaxActiveSessions")) {
            actions.set("MaxActiveSessions", JsonPrimitive(it))
        }
    }
    Section(t("Playback")) {
        switch(state, actions, "EnableMediaPlayback", t("Allow playback"), t("Turn off to allow browsing only"), default = true)
        switch(state, actions, "EnableVideoPlaybackTranscoding", t("Allow video transcoding"), t("Needed for devices that can’t play the original"), default = true)
        switch(state, actions, "EnableAudioPlaybackTranscoding", t("Allow audio transcoding"), t("Converts audio a device can’t play"), default = true)
        switch(state, actions, "EnablePlaybackRemuxing", t("Allow remuxing"), t("Changes the container without re-encoding"), default = true)
        switch(state, actions, "ForceRemoteSourceTranscoding", t("Always transcode remote streams"), t("Even when the device could play the original"))
        pick(state, sheet, "RemoteClientBitrateLimit", t("Remote streaming limit"), t("Caps quality outside the home network"), bitrateOptions(), p.long("RemoteClientBitrateLimit"), s.long("RemoteClientBitrateLimit")) {
            actions.set("RemoteClientBitrateLimit", JsonPrimitive(it))
        }
        pick(state, sheet, "SyncPlayAccess", t("Watch together"), t("Synced playback with other people"), syncPlayOptions(), p.text("SyncPlayAccess") ?: "CreateAndJoinGroups", s.text("SyncPlayAccess") ?: "CreateAndJoinGroups") {
            actions.set("SyncPlayAccess", JsonPrimitive(it))
        }
    }
    Section(t("Content and control")) {
        switch(state, actions, "EnableContentDownloading", t("Download media"), t("Save files for offline viewing"), default = true)
        switch(state, actions, "EnableContentDeletion", t("Delete media"), t("Remove files from the server"))
        switch(state, actions, "EnableCollectionManagement", t("Manage collections"), t("Create and edit collections"))
        switch(state, actions, "EnableSubtitleManagement", t("Manage subtitles"), t("Search for and remove subtitles"))
        switch(state, actions, "EnableLiveTvAccess", t("Live TV"), t("Watch channels"), default = true)
        switch(state, actions, "EnableLiveTvManagement", t("Manage Live TV recordings"), t("Schedule and delete recordings"))
        switch(state, actions, "EnableRemoteControlOfOtherUsers", t("Control other people’s playback"), t("Send commands to someone else’s session"))
        switch(state, actions, "EnableSharedDeviceControl", t("Play to shared devices"), t("Smart TVs and speakers on the network"), default = true)
    }
}
