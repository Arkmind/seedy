package io.github.arkmind.seedy.mobile.admin.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.admin.media.LibrariesActions
import io.github.arkmind.seedy.core.admin.media.LibrariesState
import io.github.arkmind.seedy.core.admin.media.LibrariesViewModel
import io.github.arkmind.seedy.core.admin.media.LibraryCard
import io.github.arkmind.seedy.core.admin.media.NewLibraryForm
import io.github.arkmind.seedy.core.admin.media.NewLibraryKinds
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.Bar
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.FootNote
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.Thumb
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Board "Android · Server · Libraries". */
@Composable
fun LibrariesRoute() {
    val session = currentSession()
    val nav = LocalMobileNav.current
    val vm = viewModel { LibrariesViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(Unit) { vm.live() }
    LaunchedEffect(state.open) {
        state.open?.let { nav.go(Route.Admin("library", it)); vm.opened() }
    }
    LibrariesScreen(state, vm, onBack = nav::back, open = { nav.go(Route.Admin("library", it)) })
}

@Composable
fun LibrariesScreen(state: LibrariesState, actions: LibrariesActions, onBack: () -> Unit, open: (String) -> Unit) {
    var refreshing by remember { mutableStateOf<LibraryCard?>(null) }
    SettingsPage(
        t("Libraries"),
        lede = state.lede ?: state.loadError ?: "…",
        onBack = onBack,
        overlay = {
            val picker = state.picker
            if (picker != null) FolderPickerSheet(picker, actions)
            else state.adding?.let { AddLibrarySheet(it, actions) }
            refreshing?.let { c ->
                ConfirmSheet(
                    t("Refresh metadata for {name}?", "name" to c.name),
                    t("Jellyfin looks up details and artwork for every title in this library again. Anything you edited by hand stays. On a big library this takes a while."),
                    t("Refresh metadata"), { actions.refreshMetadata(c.id) }, { refreshing = null },
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        PillRow {
            PillButton(if (state.scanning) t("Scanning…") else t("Scan all"), actions::scanAll, style = PillStyle.Light, enabled = !state.scanning && state.cards != null)
            PillButton(t("Add library"), actions::startAdd, enabled = state.cards != null)
        }
        val cards = state.cards
        when {
            cards == null && state.loadError != null -> Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                PillButton(t("Try again"), actions::retry)
            }
            cards == null -> Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(3) { Box(Modifier.fillMaxWidth().heightIn(min = 128.dp).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) }
            }
            cards.isEmpty() -> Empty(actions)
            else -> Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in cards) LibraryCardView(c, actions, open = { open(c.id) }, refresh = { refreshing = c })
            }
        }
        FootNote(t("Scans run on the server, so you can close the app"))
    }
}

@Composable
private fun LibraryCardView(c: LibraryCard, actions: LibrariesActions, open: () -> Unit, refresh: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.fillMaxWidth().tap(onClick = open), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Thumb(c.art, 96.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(c.name, size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SText(c.meta, size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SText(c.state, size = 12.sp, weight = 600, color = if (c.scanning) accent else SeedyColors.Text3, maxLines = 1)
            }
            SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
        }
        if (c.scanning) Bar(c.progress)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                c.canStop -> PillButton(t("Stop scan"), actions::stopScan)
                c.scanning -> PillButton(t("Scanning…"), {}, enabled = false)
                else -> PillButton(t("Scan"), { actions.scan(c.id) })
            }
            PillButton(t("Refresh metadata"), refresh)
        }
    }
}

@Composable
private fun Empty(actions: LibrariesActions) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(WebIcons.Folder, size = 26.dp, color = SeedyColors.Text2)
        SText(t("No libraries yet"), size = 15.sp, weight = 800, color = SeedyColors.White)
        SText(t("Add a library and point it at the folders with your movies, shows or music. Jellyfin scans them and fetches the artwork."), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2, align = TextAlign.Center)
        PillButton(t("Add library"), actions::startAdd, style = PillStyle.Light, leading = { SIcon(AdminIcons.Plus, size = 14.dp, color = SeedyColors.InkInverse) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddLibrarySheet(f: NewLibraryForm, actions: LibrariesActions) {
    ScrollSheet(t("Add library"), t("Point it at the folders on the server that hold this kind of media. Jellyfin scans them and fetches details and artwork."), actions::closeAdd) {
        FieldLabel(t("Kind"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in NewLibraryKinds) Chip(t(k.label), f.kind == k.value, { actions.setKind(k.value) })
        }
        FieldLabel(t("Name"))
        SeedyField(f.name, actions::setName)
        if (f.taken) SText(t("There’s already a library with this name."), size = 12.sp, color = SeedyColors.Danger)
        FieldLabel(t("Folders"))
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row)) {
            for (p in f.paths) {
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MonoText(p, Modifier.weight(1f), maxLines = 2)
                    IconDot(WebIcons.Close, { actions.removePath(p) })
                }
                Box(Modifier.fillMaxWidth().size(1.dp).background(SeedyColors.Line))
            }
            AddRow(t("Add folder"), actions::addPath)
        }
        f.error?.let { SText(it, size = 12.sp, color = SeedyColors.Danger) }
        SheetButtons(if (f.busy) t("Adding…") else t("Add library"), actions::submit, actions::closeAdd, enabled = f.name.isNotBlank() && !f.taken && f.paths.isNotEmpty() && !f.busy)
    }
}
