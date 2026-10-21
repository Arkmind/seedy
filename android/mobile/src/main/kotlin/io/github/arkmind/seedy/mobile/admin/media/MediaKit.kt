package io.github.arkmind.seedy.mobile.admin.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.media.ChoiceSetting
import io.github.arkmind.seedy.core.admin.media.FolderPicker
import io.github.arkmind.seedy.core.admin.media.FolderPickerActions
import io.github.arkmind.seedy.core.admin.media.LinkSetting
import io.github.arkmind.seedy.core.admin.media.NumberSetting
import io.github.arkmind.seedy.core.admin.media.Pair2
import io.github.arkmind.seedy.core.admin.media.SettingActions
import io.github.arkmind.seedy.core.admin.media.SettingsGroup
import io.github.arkmind.seedy.core.admin.media.SwitchSetting
import io.github.arkmind.seedy.core.admin.media.TextSetting
import io.github.arkmind.seedy.core.admin.media.numberText
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.FolderItem
import io.github.arkmind.seedy.mobile.admin.FolderSheet
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.RowLabel
import io.github.arkmind.seedy.mobile.settings.Section

/*
 * Parts the media pages share beside the admin and settings kits, for the media core's settings models: cards of
 * setting rows (switches, values picked from a list or typed as numbers, text typed in place), the number sheet, the
 * folder picker's state, a sheet for editing pairs (path substitutions, cast apps) and monospace paths.
 */

/** The page's sheets: lists (the settings kit's) and the number being typed. */
@Stable
class MediaSheets(val pick: SheetState) {
    var number by mutableStateOf<NumberSetting?>(null)
}

@Composable
fun rememberMediaSheets(): MediaSheets {
    val pick = rememberSheet()
    return remember { MediaSheets(pick) }
}

/** Draws the open list or number sheet; [onNumber] saves the number. */
@Composable
fun MediaSheetHost(sheets: MediaSheets, onNumber: (String, Double) -> Unit) {
    SheetHost(sheets.pick)
    sheets.number?.let { n -> NumberSheet(n, { onNumber(n.key, it) }, { sheets.number = null }) }
}

/** Cards of setting rows, a section each. */
@Composable
fun SettingCards(groups: List<SettingsGroup>, actions: SettingActions, sheets: MediaSheets) {
    for (g in groups) {
        Section(g.title, g.foot) {
            for (r in g.rows) row { SettingRow(r, actions, sheets) }
        }
    }
}

@Composable
fun SettingRow(r: io.github.arkmind.seedy.core.admin.media.Setting, actions: SettingActions, sheets: MediaSheets) {
    when (r) {
        is SwitchSetting -> SwitchRow(r.name, r.help, r.on, enabled = r.enabled) { actions.setSwitch(r.key, it) }
        is ChoiceSetting -> PickRow(r.name, r.help, r.value) { sheets.pick.open(r.name, r.options, r.selected) { actions.setChoice(r.key, it) } }
        is NumberSetting -> PickRow(r.name, r.help, r.value) { sheets.number = r }
        is LinkSetting -> PickRow(r.name, r.help, r.value) { actions.open(r.key) }
        is TextSetting -> TextRow(r) { actions.setText(r.key, it) }
    }
}

/** A text setting in a card: its name and help over a field. */
@Composable
fun TextRow(r: TextSetting, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (r.name.isNotEmpty()) RowLabel(r.name, r.help)
        var text by remember(r.key) { mutableStateOf(r.text) }
        BoardField(text, { text = it; onChange(it) }, placeholder = r.placeholder, mono = r.mono, lines = r.lines, autoCorrect = !r.mono)
    }
}

/** Types a number between the setting's bounds. */
@Composable
fun NumberSheet(n: NumberSetting, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember(n.key) { mutableStateOf(numberText(n.number).replace(' ', ' ')) }
    val value = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()
    val ok = value != null && value >= n.min && value <= n.max && (n.decimals || value % 1.0 == 0.0)
    val submit = { if (ok) { onSave(value!!); onDismiss() } }
    FormSheet(n.name, n.text ?: n.help, onDismiss, scroll = true) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeedyField(text, { text = it }, Modifier.weight(1f), keyboard = if (n.decimals) KeyboardType.Decimal else KeyboardType.Number, onDone = submit)
            if (!n.unit.isNullOrEmpty()) SText(n.unit!!, size = 15.sp, weight = 700, color = SeedyColors.Text2)
        }
        SText(
            t("Between {min} and {max}.", "min" to numberText(n.min), "max" to numberText(n.max)),
            size = 12.sp,
            color = if (text.isNotEmpty() && !ok) SeedyColors.Danger else SeedyColors.Text3,
        )
        SheetButtons(t("Save"), submit, onDismiss, Modifier.padding(top = 4.dp), enabled = ok)
    }
}

/** The folder picker's state in the shared folder sheet. */
@Composable
fun FolderPickerSheet(p: FolderPicker, actions: FolderPickerActions) {
    FolderSheet(
        p.title, p.path, p.parent, p.entries?.map { FolderItem(it.name, it.path, it.type == "File") }, p.error, p.files, p.action,
        onBrowse = actions::browse, onPick = actions::pickFolder, onDismiss = actions::closePicker,
    )
}

/** Edits a list of pairs (web PairsSheet): two fields a row, remove, add; Done hands back the filled rows. */
@Composable
fun PairsSheet(
    title: String,
    text: String,
    rows: List<Pair2>,
    labels: Pair<String, String>,
    placeholders: Pair<String, String>,
    addLabel: String,
    onDone: (List<Pair2>) -> Unit,
    onDismiss: () -> Unit,
) {
    var list by remember { mutableStateOf(rows) }
    FormSheet(title, text, onDismiss, scroll = true) {
        if (list.isEmpty()) SText(t("Nothing here yet."), size = 13.sp, color = SeedyColors.Text3)
        list.forEachIndexed { i, r ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Row).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText("${labels.first} · ${labels.second}", Modifier.weight(1f), size = 12.sp, weight = 700, color = SeedyColors.Text3)
                    RoundButton(WebIcons.Close, { list = list.filterIndexed { j, _ -> j != i } }, size = 32.dp, iconSize = 14.dp, tint = SeedyColors.Text2, dim = 0.4f)
                }
                BoardField(r.a, { v -> list = list.mapIndexed { j, x -> if (j == i) x.copy(a = v) else x } }, placeholder = placeholders.first, mono = true)
                BoardField(r.b, { v -> list = list.mapIndexed { j, x -> if (j == i) x.copy(b = v) else x } }, placeholder = placeholders.second, mono = true)
            }
        }
        Row { PillButton(addLabel, { list = list + Pair2("", "") }, leading = { SIcon(WebIcons.Plus, size = 14.dp, color = SeedyColors.White) }) }
        SheetButtons(t("Done"), { onDone(list.filter { it.a.isNotBlank() || it.b.isNotBlank() }); onDismiss() }, onDismiss, Modifier.padding(top = 4.dp))
    }
}
