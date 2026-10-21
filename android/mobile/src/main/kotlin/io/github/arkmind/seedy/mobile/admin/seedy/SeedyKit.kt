package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.core.admin.seedy.FolderListing
import io.github.arkmind.seedy.core.admin.seedy.SaveState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.FolderItem
import io.github.arkmind.seedy.mobile.admin.FolderSheet
import io.github.arkmind.seedy.mobile.settings.Faint
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * What the Seedy and network pages share beyond the admin and settings kits: status tones, the foot line of a page
 * that saves as you go, choice lists from the server's values, and the server's folders as Seedy lists them.
 */

/** A dot's colour for a status tone (Normal is the grey "not yet"). */
@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Ok -> SeedyColors.Ok
    Tone.Pending -> SeedyColors.Pending
    Tone.Danger -> SeedyColors.Danger
    Tone.Accent -> LocalAccent.current
    Tone.Normal -> Faint
}

/** The foot line of a page that saves as you go: saving, saved, or the error. */
@Composable
fun AutoSaveLine(save: SaveState) {
    SaveLine(saving = save == SaveState.Saving, error = if (save == SaveState.Error) t("Couldn't save. Check the server log.") else null)
}

/** A choice list with [value] in it (in order) when the server has one the list doesn't. */
fun <T : Comparable<T>> withValue(list: List<T>, value: T): List<T> = if (value in list) list else (list + value).sorted()

fun <T> choices(values: List<T>, label: (T) -> String): List<Choice<T>> = values.map { Choice(it, label(it)) }

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
