package io.github.arkmind.seedy.mobile.admin.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import io.github.arkmind.seedy.core.admin.system.BackupRow
import io.github.arkmind.seedy.core.admin.system.BackupsActions
import io.github.arkmind.seedy.core.admin.system.BackupsState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.EmptyCard
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.NameTag
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.admin.SkeletonCard
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

private val KeepChoices = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 16, 20, 26, 52)

/**
 * Backups, built from the phone boards' kit with the web board "Server · Backups": what a new backup holds and
 * Create backup, Seedy's weekly backup, and the archives on the server (download, restore, delete).
 */
@Composable
fun BackupsScreen(state: BackupsState, actions: BackupsActions, onBack: () -> Unit, download: (BackupRow) -> Unit, copyPath: (String) -> Unit) {
    val sheet = rememberSheet()
    var restoring by remember { mutableStateOf<BackupRow?>(null) }
    var deleting by remember { mutableStateOf<BackupRow?>(null) }
    var pathOf by remember { mutableStateOf<BackupRow?>(null) }
    SettingsPage(
        t("Backups"),
        lede = t("Snapshots taken while the server runs. Restoring one replaces the current server state and restarts it."),
        onBack = onBack,
        overlay = {
            SheetHost(sheet)
            restoring?.let { b -> ConfirmSheet(b.restoreTitle, b.restoreText, t("Restore and restart"), { actions.restore(b.path) }, { restoring = null }, danger = true) }
            deleting?.let { b -> ConfirmSheet(b.deleteTitle, t("The archive is deleted from the server. This can’t be undone."), t("Delete backup"), { actions.delete(b.path) }, { deleting = null }, danger = true) }
            pathOf?.let { b ->
                FormSheet(t("Copy it from the server"), t("Jellyfin doesn’t serve backups over the web yet. The archive is in the server’s data folder; copy it from there to keep it somewhere else."), { pathOf = null }) {
                    SText(
                        b.path,
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).padding(horizontal = 14.dp, vertical = 12.dp),
                        size = 13.sp, color = SeedyColors.Text,
                    )
                    SheetButtons(t("Copy path"), { copyPath(b.path); pathOf = null }, { pathOf = null }, cancel = t("Close"))
                }
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        Section(t("New backup")) {
            for (p in state.parts) row { SwitchRow(p.name, p.help, p.on) { on -> actions.setPart(p.key, on) } }
        }
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val err = state.createError
            SText(
                err ?: if (state.anyPart) t("Saved on the server. Download a copy to keep it somewhere else.") else t("Check at least one part to back up."),
                Modifier.weight(1f), size = 12.sp, lineHeight = 1.45f, color = if (err != null) SeedyColors.Danger else SeedyColors.Text3,
            )
            PillButton(if (state.creating) t("Creating backup…") else t("Create backup"), actions::create, style = PillStyle.Light, enabled = !state.creating && state.anyPart)
        }

        val autoOff = state.supported == false
        Section(t("Automatic backups")) {
            row { SwitchRow(t("Back up every week"), state.autoHelp, state.autoOn, enabled = !autoOff && state.scheduleLoaded) { actions.setAuto(it) } }
            row {
                PickRow(t("Keep"), t("Older automatic backups are deleted"), "${t("The last")} ${state.keep}") {
                    if (!autoOff && state.scheduleLoaded) {
                        sheet.open(t("Keep"), (KeepChoices + state.keep).distinct().sorted().map { Choice(it, "${t("The last")} $it") }, state.keep, actions::setKeep)
                    }
                }
            }
        }

        val rows = state.rows
        SectionTitle(if (rows != null) "${t("On this server")} · ${rows.size}" else t("On this server"))
        when {
            rows == null && state.listError != null -> ErrorCard(state.listError!!, actions::retry)
            rows == null -> SkeletonCard(2)
            rows.isEmpty() -> EmptyCard(t("No backups yet"), t("Create one above before changing big settings or upgrading Jellyfin."))
            else -> Card(Modifier.padding(horizontal = 16.dp)) {
                rows.forEachIndexed { i, b ->
                    if (i > 0) RowLine()
                    BackupLine(b, state.supported == true, onDownload = { if (state.supported == true) download(b) else pathOf = b }, onRestore = { restoring = b }, onDelete = { deleting = b })
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun BackupLine(b: BackupRow, supported: Boolean, onDownload: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(b.whenText, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                NameTag(b.kind)
            }
            Quiet(b.parts, maxLines = 2)
            Quiet(b.meta)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(t("Download"), onDownload)
            PillButton(t("Restore"), onRestore)
            Box(Modifier.weight(1f))
            RoundButton(WebIcons.Trash, onDelete, iconSize = 17.dp, bare = true, enabled = supported, tint = SeedyColors.Text2)
        }
    }
}
