package io.github.arkmind.seedy.mobile.admin.people

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.UserFilter
import io.github.arkmind.seedy.core.admin.people.UserRow
import io.github.arkmind.seedy.core.admin.people.UsersActions
import io.github.arkmind.seedy.core.admin.people.UsersState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.CardNote
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.FloatingPill
import io.github.arkmind.seedy.mobile.admin.FormError
import io.github.arkmind.seedy.mobile.admin.MenuItem
import io.github.arkmind.seedy.mobile.admin.MenuSheet
import io.github.arkmind.seedy.mobile.admin.NameTag
import io.github.arkmind.seedy.mobile.admin.RadioRow
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.Segmented
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Board "Android · Server · Users". */
@Composable
fun UsersScreen(state: UsersState, actions: UsersActions, onBack: () -> Unit, open: (String) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<UserRow?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, UserRow>?>(null) }
    androidx.compose.runtime.LaunchedEffect(state.opened) { if (state.opened != null) adding = false }
    SettingsPage(
        t("Users"),
        lede = state.lede ?: "…",
        onBack = onBack,
        overlay = {
            if (state.users != null) FloatingPill(t("Add user"), WebIcons.Plus) { adding = true }
            if (adding) AddUserSheet(state, actions) { adding = false; actions.clearAddError() }
            menu?.let { u -> UserMenu(u, onEdit = { open(u.id) }, onEnable = { actions.setDisabled(u.id, false) }, onConfirm = { confirm = it to u }) { menu = null } }
            confirm?.let { (what, u) -> UserConfirm(what, u, actions) { confirm = null } }
            // Over the Add user button.
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp))
        },
    ) {
        val filters = listOf(Choice(UserFilter.All, t("All")), Choice(UserFilter.Admins, t("Admins")), Choice(UserFilter.Disabled, t("Disabled")))
        Segmented(filters, state.filter, actions::filter, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), size = 13, count = { state.count(it).toString() })
        val shown = state.shown
        Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            when {
                shown == null && state.error != null -> CardNote(t("Couldn’t load the users. {error}", "error" to state.error.orEmpty()), action = t("Try again"), onAction = actions::reload)
                shown == null -> CardNote("…")
                shown.isEmpty() -> CardNote(
                    when (state.filter) {
                        UserFilter.Admins -> t("No administrators.")
                        UserFilter.Disabled -> t("No disabled accounts. Everyone can sign in.")
                        UserFilter.All -> t("No users yet.")
                    },
                )
                else -> shown.forEachIndexed { i, u ->
                    if (i > 0) RowLine()
                    UserLine(u, onClick = { open(u.id) }, onMenu = { menu = u })
                }
            }
        }
        // Room for the Add user button over the last row.
        Spacer(Modifier.height(110.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun UserLine(u: UserRow, onClick: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().combinedClickable(interactionSource = null, indication = null, role = Role.Button, onLongClick = onMenu, onClick = onClick)
            .alpha(if (u.disabled) 0.55f else 1f).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AdminAvatar(u.name, if (u.disabled) SeedyColors.Text3 else avatarColour(u.index), 44.dp, 18.sp, imageUrl = u.image, online = u.online)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SText(u.name, size = 15.sp, weight = 800, color = SeedyColors.White)
                for (tag in u.tags) NameTag(tag)
            }
            SText(u.seen, size = 12.sp, weight = 600, color = if (u.online) SeedyColors.Ok else SeedyColors.Text2)
            SText(u.access, size = 12.sp, color = SeedyColors.Text3)
        }
        SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
    }
}

/** A long press on a row: what the web's row menu does. */
@Composable
private fun UserMenu(u: UserRow, onEdit: () -> Unit, onEnable: () -> Unit, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val items = buildList {
        add(MenuItem(t("Edit"), run = onEdit))
        // Not your own account, and an administrator loses admin access before being disabled.
        if (!u.isMe && (u.disabled || !u.isAdmin)) add(if (u.disabled) MenuItem(t("Enable account"), run = onEnable) else MenuItem(t("Disable account")) { onConfirm("disable") })
        add(MenuItem(t("Sign out everywhere")) { onConfirm("signout") })
        if (!u.isMe) add(MenuItem(t("Delete user"), danger = true) { onConfirm("delete") })
    }
    MenuSheet(u.name, items, onDismiss)
}

@Composable
private fun UserConfirm(what: String, u: UserRow, actions: UsersActions, onDismiss: () -> Unit) {
    when (what) {
        "disable" -> ConfirmSheet(
            t("Disable {name}?", "name" to u.name),
            t("{name} is signed out everywhere and can’t sign in until you enable the account again. Nothing is deleted.", "name" to u.name),
            t("Disable account"), { actions.setDisabled(u.id, true) }, onDismiss, danger = true,
        )
        "signout" -> ConfirmSheet(
            t("Sign {name} out everywhere?", "name" to u.name),
            t("Every app and device {name} is signed in on has to sign in again.", "name" to u.name) + if (u.isMe) " " + t("This phone stays signed in.") else "",
            t("Sign out everywhere"), { actions.signOutEverywhere(u.id) }, onDismiss,
        )
        "delete" -> ConfirmSheet(
            t("Delete {name}?", "name" to u.name),
            t("{name}’s account, watch history, favourites and settings are deleted. This can’t be undone.", "name" to u.name),
            t("Delete user"), { actions.delete(u.id) }, onDismiss, danger = true,
        )
    }
}

/** Name, password, and whose access to start from; the new user's page opens when done. */
@Composable
private fun AddUserSheet(state: UsersState, actions: UsersActions, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var copyFrom by remember { mutableStateOf<String?>(null) }
    val users = state.users.orEmpty()
    val taken = name.isNotBlank() && users.any { it.name.trim().equals(name.trim(), ignoreCase = true) }
    FormSheet(t("Add user"), t("They can sign in on any app with this name. You can change what they can watch and do next."), onClose) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(t("Name"))
                SeedyField(name, { name = it.take(64); actions.clearAddError() })
                if (taken) FormError(t("Someone already has this name."))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                FieldLabel(t("Password") + " · " + t("optional"))
                SeedyField(password, { password = it }, password = true)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FieldLabel(t("Copy settings from"))
                SText(t("Libraries, devices, playback limits and parental controls. Never admin access."), size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
                Column(Modifier.padding(top = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row).heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    RadioRow(t("Nobody · start with the defaults"), null, copyFrom == null, { copyFrom = null })
                    for (u in users) {
                        RadioRow(if (u.isAdmin) t("{name} (access only, not admin)", "name" to u.name) else u.name, null, copyFrom == u.id, { copyFrom = u.id })
                    }
                }
            }
            state.addError?.let { FormError(it) }
        }
        SheetButtons(
            if (state.adding) t("Adding…") else t("Add user"),
            { actions.addUser(name, password, copyFrom) },
            onClose,
            enabled = name.isNotBlank() && !taken && !state.adding,
        )
    }
}
