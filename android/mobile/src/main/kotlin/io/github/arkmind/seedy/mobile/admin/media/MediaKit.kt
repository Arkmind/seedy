package io.github.arkmind.seedy.mobile.admin.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * Parts the media pages share beside the admin and settings kits: cards of setting rows (switches, values picked from
 * a list or typed as numbers, text typed in place), the number sheet, the server's folder picker, a sheet for editing
 * pairs (path substitutions, cast apps), chips and monospace paths.
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
        if (r.name.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(r.name, size = 14.sp, weight = 700, color = SeedyColors.White)
                if (!r.help.isNullOrEmpty()) SText(r.help!!, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
            }
        }
        var text by remember(r.key) { mutableStateOf(r.text) }
        BoardField(text, { text = it; onChange(it) }, placeholder = r.placeholder, mono = r.mono, lines = r.lines)
    }
}

/**
 * The boards' 46 px field (#1f1f1f, 13 px corners, 15 px semibold text); [lines] above 1 makes it a text area (14 px
 * text, 1.4 line height, 12 × 14 px padding).
 */
@Composable
fun BoardField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    mono: Boolean = false,
    lines: Int = 1,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val accent = LocalAccent.current
    val multi = lines > 1
    val style = BaseText.copy(
        fontSize = if (multi || mono) 14.sp else 15.sp,
        fontWeight = FontWeight(if (multi) 400 else 600),
        color = SeedyColors.White,
        lineHeight = if (multi) 1.4.em else TextUnit.Unspecified,
        fontFamily = if (mono) FontFamily.Monospace else BaseText.fontFamily,
    )
    val shape = RoundedCornerShape(13.dp)
    Box(
        modifier.fillMaxWidth().then(if (multi) Modifier.heightIn(min = (14 * 1.4f * lines + 24).dp) else Modifier.height(46.dp))
            .clip(shape).background(SeedyColors.Surface)
            .then(if (focused) Modifier.border(2.dp, accent, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = if (multi) 12.dp else 0.dp),
        contentAlignment = if (multi) Alignment.TopStart else Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) BasicTextFieldPlaceholder(placeholder, style)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = !multi,
            minLines = if (multi) lines else 1,
            interactionSource = interaction,
            textStyle = style,
            cursorBrush = SolidColor(accent),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (multi) ImeAction.Default else ImeAction.Done, autoCorrectEnabled = !mono),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun BasicTextFieldPlaceholder(text: String, style: TextStyle) {
    androidx.compose.foundation.text.BasicText(text, style = style.copy(color = SeedyColors.Text3), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** A path in monospace ("/media/movies"). */
@Composable
fun MonoText(text: String, modifier: Modifier = Modifier, size: TextUnit = 13.sp, color: Color = SeedyColors.Text, maxLines: Int = Int.MAX_VALUE) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        style = BaseText.copy(fontSize = size, fontFamily = FontFamily.Monospace, fontWeight = FontWeight(500), color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A 34 px pill that's on (light, with a tick) or off (dark); the tick keeps its room when off. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tick: Boolean = true, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.height(34.dp).clip(CircleShape).background(if (on) SeedyColors.Inverse else SeedyColors.Surface).tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (on) SeedyColors.InkInverse else SeedyColors.TextHero
        if (tick) SIcon(AdminIcons.Check, Modifier.alpha(if (on) 1f else 0f), size = 13.dp, color = ink)
        SText(label, size = 12.sp, weight = 700, color = ink, maxLines = 1)
        trailing?.invoke()
    }
}

/** A round 32 px button with an icon (remove a folder). */
@Composable
fun IconDot(icon: io.github.arkmind.seedy.ui.icons.IconDef, onClick: () -> Unit, enabled: Boolean = true, size: Dp = 32.dp, color: Color = SeedyColors.Text2) {
    Box(Modifier.size(size).clip(CircleShape).background(SeedyColors.Surface).tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 14.dp, color = color)
    }
}

/** A row in a card that adds something: a plus and its label in the accent. */
@Composable
fun AddRow(label: String, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp).heightIn(min = 38.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(AdminIcons.Plus, size = 16.dp, color = accent)
        SText(label, size = 14.sp, weight = 700, color = accent)
    }
}

/** A sheet's two buttons at the bottom right. */
@Composable
fun SheetButtons(action: String, onAction: () -> Unit, onCancel: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        PillButton(t("Cancel"), onCancel)
        PillButton(action, onAction, style = if (danger) PillStyle.Danger else PillStyle.Light, enabled = enabled)
    }
}

/** A sheet whose content scrolls when it's taller than the screen allows. */
@Composable
fun ScrollSheet(title: String, text: String?, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BottomSheet(onDismiss = onDismiss) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
            content()
        }
    }
}

/** Types a number between the setting's bounds. */
@Composable
fun NumberSheet(n: NumberSetting, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember(n.key) { mutableStateOf(numberText(n.number).replace(' ', ' ')) }
    val value = text.trim().replace(',', '.').replace(" ", "").toDoubleOrNull()
    val ok = value != null && value >= n.min && value <= n.max && (n.decimals || value % 1.0 == 0.0)
    val submit = { if (ok) { onSave(value!!); onDismiss() } }
    ScrollSheet(n.name, n.text ?: n.help, onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SeedyField(text, { text = it }, Modifier.weight(1f), keyboard = if (n.decimals) KeyboardType.Decimal else KeyboardType.Number, onDone = submit)
            if (!n.unit.isNullOrEmpty()) SText(n.unit!!, size = 15.sp, weight = 700, color = SeedyColors.Text2)
        }
        SText(
            t("Between {min} and {max}.", "min" to numberText(n.min), "max" to numberText(n.max)),
            size = 12.sp,
            color = if (text.isNotEmpty() && !ok) SeedyColors.Danger else SeedyColors.Text3,
        )
        SheetButtons(t("Save"), submit, onDismiss, enabled = ok)
    }
}

/** The server's folders (web FolderPicker): Up, the path, what's in it, and the button that picks it. */
@Composable
fun FolderPickerSheet(p: FolderPicker, actions: FolderPickerActions) {
    var typed by remember(p.path) { mutableStateOf(p.path) }
    ScrollSheet(p.title, t("Folders on the server, not on this device."), actions::closePicker) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(t("Up"), { actions.browse(p.parent.orEmpty()) }, enabled = p.parent != null, leading = { SIcon(WebIcons.ChevronLeft, size = 16.dp, color = SeedyColors.White) })
            BoardField(typed, { typed = it }, Modifier.weight(1f), placeholder = "/", mono = true, onDone = { actions.browse(typed.trim()) })
            PillButton(t("Go"), { actions.browse(typed.trim()) })
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row)) {
            val entries = p.entries
            when {
                entries == null -> SText("…", Modifier.padding(16.dp), size = 13.sp, color = SeedyColors.Text3)
                entries.isEmpty() -> SText(p.error ?: t("No folders in here."), Modifier.padding(16.dp), size = 13.sp, color = if (p.error != null) SeedyColors.Danger else SeedyColors.Text3)
                else -> entries.forEachIndexed { i, e ->
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
                    val file = e.type == "File"
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).tap { if (file) actions.pickFolder(e.path) else actions.browse(e.path) }.padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SIcon(if (file) WebIcons.List else WebIcons.Folder, size = 18.dp, color = SeedyColors.Text2)
                        SText(e.name.ifEmpty { e.path }, Modifier.weight(1f), size = 14.sp, weight = 600, color = SeedyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!file) SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
                    }
                }
            }
        }
        if (!p.files) SheetButtons(p.action, { actions.pickFolder(p.path) }, actions::closePicker, enabled = p.path.isNotEmpty())
        else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PillButton(t("Cancel"), actions::closePicker) }
    }
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
    ScrollSheet(title, text, onDismiss) {
        if (list.isEmpty()) SText(t("Nothing here yet."), size = 13.sp, color = SeedyColors.Text3)
        list.forEachIndexed { i, r ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Row).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SText("${labels.first} · ${labels.second}", Modifier.weight(1f), size = 12.sp, weight = 700, color = SeedyColors.Text3)
                    IconDot(WebIcons.Close, { list = list.filterIndexed { j, _ -> j != i } })
                }
                BoardField(r.a, { v -> list = list.mapIndexed { j, x -> if (j == i) x.copy(a = v) else x } }, placeholder = placeholders.first, mono = true)
                BoardField(r.b, { v -> list = list.mapIndexed { j, x -> if (j == i) x.copy(b = v) else x } }, placeholder = placeholders.second, mono = true)
            }
        }
        Row { PillButton(addLabel, { list = list + Pair2("", "") }, leading = { SIcon(AdminIcons.Plus, size = 14.dp, color = SeedyColors.White) }) }
        SheetButtons(t("Done"), { onDone(list.filter { it.a.isNotBlank() || it.b.isNotBlank() }); onDismiss() }, onDismiss)
    }
}

/** A long list to pick from, with a search field over it (languages). */
@Composable
fun SearchPickSheet(title: String, placeholder: String, options: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit, empty: String) {
    var q by remember { mutableStateOf("") }
    val shown = options.filter { q.isBlank() || it.second.contains(q.trim(), ignoreCase = true) }.take(120)
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            SeedyField(q, { q = it }, placeholder = placeholder)
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp).imePadding()) {
            if (shown.isEmpty()) SText(empty, Modifier.padding(12.dp), size = 14.sp, color = SeedyColors.Text3)
            for ((value, label) in shown) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp)).tap { onPick(value); onDismiss() }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { SText(label, size = 15.sp, weight = 600, color = SeedyColors.Text) }
            }
        }
    }
}
