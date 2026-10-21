package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import io.github.arkmind.seedy.ui.theme.LocalViewer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.AddTarget
import io.github.arkmind.seedy.core.manage.AddToActions
import io.github.arkmind.seedy.core.manage.AddToState
import io.github.arkmind.seedy.core.manage.AddToViewModel
import io.github.arkmind.seedy.core.manage.Audience
import io.github.arkmind.seedy.core.manage.ListKind
import io.github.arkmind.seedy.core.manage.NewPlaylistActions
import io.github.arkmind.seedy.core.manage.NewPlaylistState
import io.github.arkmind.seedy.core.manage.NewPlaylistViewModel
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.RadioDot
import io.github.arkmind.seedy.mobile.admin.TextSheet
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/* ── Add to playlist / collection ── */

/** Add to playlist or Add to collection for [target]; [onNew] opens New playlist (or asks a collection's name). */
@Composable
fun AddToSheet(kind: ListKind, target: AddTarget, onDismiss: () -> Unit, onNew: () -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "add-to:$kind:" + System.identityHashCode(target)) { AddToViewModel(session, kind, target) }
    val state by vm.state.collectAsStateWithLifecycle()
    AddToContent(state, vm, target, onDismiss, onNew)
}

/** New collection: its name, then it's made with [target] in it. */
@Composable
fun NewCollectionSheet(target: AddTarget, onDismiss: () -> Unit, onMade: (String) -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "add-to:${ListKind.Collection}:" + System.identityHashCode(target)) { AddToViewModel(session, ListKind.Collection, target) }
    TextSheet(
        t("New collection"), t("Starts with {name}", "name" to target.name), t("Create collection"),
        { name -> vm.create(name); onMade(t("Made {name}", "name" to name.trim())) }, onDismiss, placeholder = t("Name it"),
    )
}

@Composable
fun AddToContent(state: AddToState, actions: AddToActions, target: AddTarget, onDismiss: () -> Unit, onNew: () -> Unit) {
    val playlist = state.kind == ListKind.Playlist
    BottomSheet(onDismiss) {
        SheetHead(if (playlist) t("Add to playlist") else t("Add to collection"), target.name, gap = 4.dp)
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp)) {
            SearchField(state.query, actions::search, if (playlist) t("Find a playlist") else t("Find a collection"))
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 8.dp, end = 8.dp, bottom = 24.dp)) {
            val rows = state.shown
            when {
                rows == null && state.failed -> Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SText(if (playlist) t("Couldn’t load your playlists.") else t("Couldn’t load the collections."), size = 13.sp, color = SeedyColors.Danger)
                    PillButton(t("Try again"), actions::retry)
                }
                rows == null -> repeat(3) { Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row)) }
                rows.isEmpty() && state.query.isNotBlank() -> SText(t("Nothing matches"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                rows.isEmpty() -> SText(if (playlist) t("No playlists yet. Make one with this title in it.") else t("No collections yet."), Modifier.padding(12.dp), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
                else -> for (r in rows) ListRowView(r.name, r.sub, r.added, r.busy, playlist) { actions.add(r.id) }
            }
            if (state.canCreate) {
                MenuLine()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onNew).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SIcon(WebIcons.Plus, size = 18.dp, color = SeedyColors.White, stroke = 2.4f)
                    SText(if (playlist) t("New playlist…") else t("New collection…"), size = 14.sp, weight = 700, color = SeedyColors.White)
                }
            }
        }
        Toast(state.message, actions::messageShown, Modifier.align(Alignment.CenterHorizontally))
    }
}

@Composable
private fun ListRowView(name: String, sub: String, added: Boolean, busy: Boolean, playlist: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).tap(role = Role.Checkbox, enabled = !added && !busy, onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(if (playlist) ManageIcons.PlaylistAdd else ManageIcons.Collection, size = 36.dp, iconSize = 17.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            added -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SIcon(WebIcons.Check, size = 13.dp, color = accent, stroke = 3f)
                SText(t("Added"), size = 12.sp, weight = 800, color = accent, maxLines = 1)
            }
            busy -> SText(t("Adding…"), size = 12.sp, weight = 700, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** A field with the magnifier in it (the menu's "Find a playlist"). */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Box(contentAlignment = Alignment.CenterStart) {
        BoardField(value, onChange, Modifier, placeholder = placeholder, size = 14, start = 38.dp)
        SIcon(ManageIcons.Search, Modifier.padding(start = 14.dp), size = 16.dp, color = SeedyColors.Text3)
    }
}

/* ── New playlist ── */

@Composable
fun NewPlaylistSheet(target: AddTarget, onDismiss: () -> Unit, onMade: (String) -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "new-playlist:" + System.identityHashCode(target)) { NewPlaylistViewModel(session, target) }
    val state by vm.state.collectAsStateWithLifecycle()
    NewPlaylistContent(state, vm, onDismiss) { message -> onDismiss(); onMade(message) }
}

@Composable
fun NewPlaylistContent(state: NewPlaylistState, actions: NewPlaylistActions, onDismiss: () -> Unit, onMade: (String) -> Unit = {}) {
    ActionSheet(
        onDismiss,
        footer = {
            state.error?.let { SText(it, size = 12.sp, weight = 600, color = SeedyColors.Danger) }
            BigButton(if (state.busy) t("Creating…") else t("Create playlist"), { actions.create(onMade) }, enabled = state.canCreate)
        },
    ) {
        SText(t("New playlist"), size = 20.sp, weight = 900, color = SeedyColors.White)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel(t("Name"))
            BoardField(state.name, actions::setName, placeholder = t("Name it"), autoCorrect = true)
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(state.art, Modifier.size(34.dp, 50.dp).clip(RoundedCornerShape(7.dp)), placeholder = SeedyColors.Surface)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(state.startsWith, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2)
                if (state.startsSub.isNotEmpty()) SText(state.startsSub, size = 13.sp, color = SeedyColors.Text3, maxLines = 2)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FieldLabel(t("Who can see it"))
            AudienceOption(ManageIcons.Me, t("Only me"), t("Nobody else sees it"), state.audience == Audience.OnlyMe) { actions.setAudience(Audience.OnlyMe) }
            if (state.canPick) {
                AudienceOption(ManageIcons.People, t("People I pick"), t("They see it in their Playlists"), state.audience == Audience.People) { actions.setAudience(Audience.People) }
                if (state.audience == Audience.People) People(state, actions)
            }
            AudienceOption(ManageIcons.Globe, t("Everyone on this server"), t("Shows up for every profile"), state.audience == Audience.Everyone) { actions.setAudience(Audience.Everyone) }
        }
    }
}

@Composable
private fun AudienceOption(icon: io.github.arkmind.seedy.ui.icons.IconDef, name: String, sub: String, on: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SeedyColors.Row).then(if (on) Modifier.border(2.dp, accent, shape) else Modifier)
            .tap(role = Role.RadioButton, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconTile(icon, if (on) accent else SeedyColors.Text, size = 38.dp, iconSize = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(name, size = 15.sp, weight = 700, color = SeedyColors.White)
            SText(sub, size = 13.sp, color = SeedyColors.Text3)
        }
        RadioDot(on)
    }
}

/** The people a playlist is shared with: Can edit or Can watch, and Add someone. */
@Composable
private fun People(state: NewPlaylistState, actions: NewPlaylistActions) {
    val accent = LocalAccent.current
    val images = LocalViewer.current.images
    run {
        Column(
            Modifier.fillMaxWidth().padding(start = 2.dp).drawBehind { drawRect(SeedyColors.Surface2, size = Size(2.dp.toPx(), size.height)) }.padding(start = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((i, shared) in state.people.withIndex()) {
                val p = shared.person
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val index = state.everyone.orEmpty().indexOfFirst { it.id == p.id }.coerceAtLeast(i)
                    AdminAvatar(p.name, avatarColour(index + 1), 30.dp, 13.sp, imageUrl = if (p.hasImage) images?.userPicture(p.id, 96) else null)
                    SText(p.name, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    var open by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            Modifier.height(34.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface).tap { open = true }.padding(start = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            SText(if (shared.canEdit) t("Can edit") else t("Can watch"), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                            SIcon(WebIcons.ChevronDown, size = 14.dp, color = SeedyColors.White, stroke = 2.4f)
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                            for ((edit, label) in listOf(true to t("Can edit"), false to t("Can watch"))) {
                                DropdownMenuItem(
                                    text = { SText(label, size = 14.sp, weight = if (edit == shared.canEdit) 800 else 600, color = SeedyColors.White) },
                                    trailingIcon = if (edit == shared.canEdit) ({ SIcon(WebIcons.Check, size = 16.dp, color = accent) }) else null,
                                    onClick = { open = false; actions.setCanEdit(p.id, edit) },
                                )
                            }
                        }
                    }
                    Box(Modifier.size(34.dp).clip(CircleShape).tap { actions.removePerson(p.id) }, contentAlignment = Alignment.Center) {
                        SIcon(WebIcons.Close, size = 14.dp, color = SeedyColors.Text2, stroke = 2.4f)
                    }
                }
            }
            val others = state.others
            if (others.isNotEmpty()) {
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(
                        Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(10.dp)).tap { open = true }.padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SIcon(WebIcons.Plus, size = 15.dp, color = SeedyColors.White, stroke = 2.6f)
                        SText(t("Add someone"), size = 14.sp, weight = 700, color = SeedyColors.White)
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                        for (p in others) {
                            DropdownMenuItem(
                                text = { SText(p.name, size = 14.sp, weight = 600, color = SeedyColors.White) },
                                onClick = { open = false; actions.addPerson(p.id) },
                            )
                        }
                    }
                }
            } else if (state.people.isEmpty()) {
                SText(t("There’s nobody else on this server yet."), Modifier.padding(10.dp), size = 13.sp, color = SeedyColors.Text3)
            }
        }
    }
}

