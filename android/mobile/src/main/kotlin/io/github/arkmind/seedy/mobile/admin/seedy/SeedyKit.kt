package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.seedy.FolderListing
import io.github.arkmind.seedy.core.admin.seedy.SaveState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.Dot
import io.github.arkmind.seedy.mobile.admin.FolderItem
import io.github.arkmind.seedy.mobile.admin.FolderSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.mobile.settings.settingsRow
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * What the Seedy and network pages share beyond the admin and settings kits: a list's row when it couldn't load, the
 * foot line of a page that saves as you go, and the server's folders as Seedy lists them.
 */

/** A list's row when it couldn't load: a red dot, [text] and Try again. */
@Composable
fun RetryRow(text: String, onRetry: () -> Unit) {
    Row(Modifier.settingsRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Dot(SeedyColors.Danger)
        SText(text, Modifier.weight(1f), size = 13.sp, color = SeedyColors.Text2)
        PillButton(t("Try again"), onRetry, height = 36.dp)
    }
}

/** The foot line of a page that saves as you go: saving, saved, or the error. */
@Composable
fun AutoSaveLine(save: SaveState) {
    SaveLine(saving = save == SaveState.Saving, error = if (save == SaveState.Error) t("Couldn't save. Check the server log.") else null)
}

/**
 * The server's folders in the shared folder sheet, listed as Seedy lists them: starts at [start] (its folder, for a
 * file), and with [files] a file is picked by tapping it.
 */
@Composable
fun ServerFolderSheet(
    title: String,
    start: String,
    files: Boolean,
    list: suspend (String, Boolean) -> FolderListing,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var seq by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(if (files) start.trim().replace(Regex("[\\\\/][^\\\\/]*$"), "") else start.trim()) }
    LaunchedEffect(target) {
        val n = ++seq
        listing = null
        val l = list(target, files)
        if (n == seq) listing = l
    }
    val l = listing
    FolderSheet(
        title, l?.path ?: target, l?.parent, l?.entries?.map { FolderItem(it.name, it.path, it.type == "File") }, l?.error, files, t("Use this folder"),
        onBrowse = { target = it },
        onPick = { onPick(it); onDismiss() },
        onDismiss = onDismiss,
    )
}
