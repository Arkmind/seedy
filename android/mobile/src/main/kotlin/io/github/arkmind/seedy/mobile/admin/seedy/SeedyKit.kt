package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.Tone
import io.github.arkmind.seedy.core.admin.seedy.FolderListing
import io.github.arkmind.seedy.core.admin.seedy.SaveState
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.NoticeCard
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.SaveLine
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.cssBox
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.launch

/*
 * Parts the Seedy and network pages share, drawn in the phone server boards' style (AndroidAdminSettings: fields in a
 * card, AndroidAdminPlayback: chips; the settings kit's rows and sheets do the rest).
 */

/** A dot's colour for a status tone (Normal is the grey "not yet"). */
@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Ok -> SeedyColors.Ok
    Tone.Pending -> SeedyColors.Pending
    Tone.Danger -> SeedyColors.Danger
    Tone.Accent -> LocalAccent.current
    Tone.Normal -> Color(0xFF6A6A6A)
}

@Composable
fun Dot(color: Color, size: Dp = 7.dp) = Box(Modifier.size(size).background(color, CircleShape))

/** The board's 46 px text field: #1f1f1f, 13 px corners, 15 px semibold text; the accent ring while focused. */
@Composable
fun BoardInput(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    error: Boolean = false,
    enabled: Boolean = true,
    size: Int = 15,
    onDone: () -> Unit = {},
    onBlur: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var wasFocused by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val ring = when {
        error -> SeedyColors.Danger
        focused -> accent
        else -> null
    }
    Box(
        modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface)
            .then(if (ring != null) Modifier.border(2.dp, ring, RoundedCornerShape(13.dp)) else Modifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty() && placeholder.isNotEmpty()) SText(placeholder, size = size.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            textStyle = BaseText.copy(fontSize = size.sp, fontWeight = FontWeight(600), color = SeedyColors.White),
            cursorBrush = SolidColor(accent),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { f ->
                if (wasFocused && !f.isFocused) onBlur()
                wasFocused = f.isFocused
            },
        )
    }
}

/** A labelled field: the name (14 px bold), the field, a line under it (or the error in red). */
@Composable
fun FieldBlock(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    error: String? = null,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onDone: () -> Unit = {},
    onBlur: () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SText(label, size = 14.sp, weight = 700, color = SeedyColors.White)
        BoardInput(value, onChange, placeholder = placeholder, keyboard = keyboard, password = password, error = error != null, onDone = onDone, onBlur = onBlur)
        when {
            error != null -> if (error.isNotEmpty()) SText(error, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Danger)
            !help.isNullOrEmpty() -> SText(help, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
    }
}

/** A section whose card holds fields: 14 × 16 × 16 px inside, 14 px between them. */
@Composable
fun FieldSection(title: String?, foot: String? = null, footColor: Color = SeedyColors.Text3, content: @Composable ColumnScope.() -> Unit) {
    if (title != null) SectionTitle(title) else Spacer(Modifier.height(16.dp))
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel).padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
    if (foot != null) {
        if (footColor == SeedyColors.Text3) SectionFoot(foot)
        else SText(foot, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(12, 1.45f, up = true), size = 12.sp, lineHeight = 1.45f, color = footColor)
    }
}

/** A row's padding for things the settings kit has no row for (12 × 14 × 12 × 16, at least 44 px inside). */
fun Modifier.seedyRow(): Modifier = this.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 44.dp)

/** A row's name and line (as the settings rows draw them). */
@Composable
fun RowLabel(name: String, help: String?, modifier: Modifier = Modifier, nameColor: Color = SeedyColors.White, helpColor: Color = SeedyColors.Text3) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SText(name, size = 14.sp, weight = 700, color = nameColor)
        if (!help.isNullOrEmpty()) SText(help, size = 12.sp, lineHeight = 1.4f, color = helpColor)
    }
}

/** A 34 px chip (the Playback board's codec chips, dark): its text and a remove cross. */
@Composable
fun RemovableChip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(SeedyColors.Surface).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SText(label, Modifier.weight(1f, fill = false), size = 12.sp, weight = 700, color = SeedyColors.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Box(Modifier.size(28.dp).clip(CircleShape).tap(onClick = onRemove), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.Close, size = 12.dp, color = SeedyColors.Text2)
        }
    }
}

/** The dashed "+ Add" chip. */
@Composable
fun AddChip(label: String, onClick: () -> Unit, icon: IconDef = WebIcons.Plus) {
    val ring = Color(0xFF4A4A4A)
    Row(
        Modifier.height(34.dp).clip(CircleShape)
            .drawBehind {
                val w = 1.dp.toPx()
                drawRoundRect(ring, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w), cornerRadius = CornerRadius(size.height / 2), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            .tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SIcon(icon, size = 13.dp, color = SeedyColors.Text)
        SText(label, size = 12.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
    }
}

/** Chips with an add chip after them, wrapping (8 px apart). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChipFlow(items: List<String>, onRemove: (Int) -> Unit, addLabel: String, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, v -> RemovableChip(v) { onRemove(i) } }
        AddChip(addLabel, onAdd)
    }
}

/** "− Up to 6 +": a value with a step either way. */
@Composable
fun StepperPill(label: String, onDec: () -> Unit, onInc: () -> Unit, decEnabled: Boolean = true, incEnabled: Boolean = true) {
    Row(Modifier.height(32.dp).clip(CircleShape).background(SeedyColors.Surface).padding(2.dp), verticalAlignment = Alignment.CenterVertically) {
        StepButton(WebIcons.Minus, decEnabled, onDec)
        SText(label, Modifier.padding(horizontal = 6.dp), size = 12.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        StepButton(WebIcons.Plus, incEnabled, onInc)
    }
}

@Composable
private fun StepButton(icon: IconDef, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(SeedyColors.Surface2).tap(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 13.dp, color = if (enabled) SeedyColors.White else SeedyColors.Text3)
    }
}

/** What went wrong loading a page, with Try again. */
@Composable
fun LoadFailed(title: String, error: String, onRetry: () -> Unit) {
    NoticeCard(title, error, t("Try again"), SeedyColors.Danger, SeedyColors.Danger.copy(alpha = 0.08f), onRetry, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
}

/** Grey cards where a page's sections will be. */
@Composable
fun LoadingCards(count: Int = 3, height: Dp = 132.dp) {
    repeat(count) {
        Spacer(Modifier.height(22.dp))
        Box(Modifier.padding(start = 20.dp).size(96.dp, 12.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Panel))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(height).clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel))
    }
}

/** The foot line of a page that saves as you go: saving, saved, or the error. */
@Composable
fun AutoSaveLine(save: SaveState) {
    SaveLine(saving = save == SaveState.Saving, error = if (save == SaveState.Error) t("Couldn't save. Check the server log.") else null)
}

/**
 * The unsaved-changes bar with its own button ([saveLabel], "Save and restart"); [busy] replaces it with a line
 * ("Restarting the server…").
 */
@Composable
fun BoxScope.UnsavedBar(
    text: String,
    saveLabel: String,
    onDiscard: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean = false,
    busy: String? = null,
    discardLabel: String = t("Discard"),
    savingLabel: String = t("Saving…"),
    note: String? = null,
    /** Tighter buttons, for a long [saveLabel] ("Save and restart"). */
    compact: Boolean = false,
) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .boxShadow(Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = (-10).dp)
            .background(SeedyColors.Row).tap {}
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = max(26.dp, nav + 12.dp)).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (busy != null) {
            Dot(SeedyColors.Pending)
            SText(busy, Modifier.weight(1f), size = 13.sp, weight = 700, color = SeedyColors.Pending)
            return@Row
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (text.isNotEmpty()) SText(text, size = 13.sp, weight = 700, color = SeedyColors.Pending)
            if (!note.isNullOrEmpty()) SText(note, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text2)
        }
        Row(Modifier.height(40.dp).clip(CircleShape).tap(enabled = !saving, onClick = onDiscard).padding(horizontal = if (compact) 8.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(discardLabel, size = 13.sp, weight = 700, color = SeedyColors.Text, maxLines = 1)
        }
        Box(Modifier.height(44.dp).clip(CircleShape).background(SeedyColors.Inverse).tap(enabled = !saving, onClick = onSave).padding(horizontal = if (compact) 16.dp else 20.dp), contentAlignment = Alignment.Center) {
            SText(if (saving) savingLabel else saveLabel, size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
        }
    }
}

/** Back with unsaved changes asks first ("Leave without saving?"). */
@Composable
fun LeaveGuard(dirty: Boolean, text: String, onLeave: () -> Unit) {
    var asking by remember { mutableStateOf(false) }
    BackHandler(enabled = dirty && !asking) { asking = true }
    if (asking) {
        ConfirmSheet(t("Leave without saving?"), text, t("Leave without saving"), onLeave, { asking = false }, danger = true)
    }
}

/** A choice list with [value] in it (in order) when the server has one the list doesn't. */
fun <T : Comparable<T>> withValue(list: List<T>, value: T): List<T> = if (value in list) list else (list + value).sorted()

fun <T> choices(values: List<T>, label: (T) -> String): List<Choice<T>> = values.map { Choice(it, label(it)) }

/* ── Sheets ── */

/** A sheet's title and its line. */
@Composable
fun SheetHead(title: String, text: String? = null) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
        if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
    }
}

/** The light and dark buttons at a sheet's foot, right-aligned. */
@Composable
fun SheetActions(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
        content()
    }
}

/**
 * Edits a list of addresses (the web's chip list and its add popover): the entries with a remove cross, a field and
 * Add; [top] goes above the list (the allow/block switch). [validate] answers null when an entry is fine.
 */
@Composable
fun ListSheet(
    title: String,
    help: String?,
    items: List<String>,
    placeholder: String,
    emptyText: String?,
    validate: (String) -> String?,
    onChange: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    top: (@Composable () -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    fun add() {
        val v = text.trim()
        if (v.isEmpty()) return
        val err = validate(v) ?: if (items.any { it.equals(v, ignoreCase = true) }) t("That’s already in the list.") else null
        if (err != null) {
            error = err
            return
        }
        onChange(items + v)
        text = ""
        error = null
    }
    BottomSheet(onDismiss) {
        SheetHead(title, help)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            top?.invoke()
            if (items.isEmpty()) {
                if (emptyText != null) SText(emptyText, size = 13.sp, color = SeedyColors.Text3)
            } else {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items.forEachIndexed { i, v -> RemovableChip(v) { onChange(items.filterIndexed { j, _ -> j != i }) } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardInput(text, { text = it; error = null }, Modifier.weight(1f), placeholder = placeholder, keyboard = KeyboardType.Uri, error = error != null, onDone = ::add)
                PillButton(t("Add"), ::add, style = PillStyle.Light, enabled = text.isNotBlank(), height = 46.dp)
            }
            error?.let { SText(it, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Danger) }
        }
        SheetActions { PillButton(t("Done"), onDismiss, style = PillStyle.Light) }
    }
}

/** A sheet with one field (a path, a program, a password) and Save. */
@Composable
fun FieldSheet(
    title: String,
    text: String?,
    initial: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    placeholder: String = "",
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    action: String = t("Save"),
    extra: (@Composable () -> Unit)? = null,
) {
    var value by remember { mutableStateOf(initial) }
    BottomSheet(onDismiss) {
        SheetHead(title, text)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            BoardInput(value, { value = it }, placeholder = placeholder, password = password, keyboard = keyboard, onDone = { onSave(value); onDismiss() })
            extra?.invoke()
        }
        SheetActions {
            PillButton(t("Cancel"), onDismiss)
            PillButton(action, { onSave(value); onDismiss() }, style = PillStyle.Light)
        }
    }
}

/** A long list to pick from, with a search field when it's long ([options] are value and label; [sub] a second line). */
@Composable
fun <T> SearchPickSheet(
    title: String,
    text: String?,
    options: List<Choice<T>>,
    value: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    searchLabel: String = t("Search"),
    sub: (T) -> String? = { null },
) {
    var q by remember { mutableStateOf("") }
    val accent = LocalAccent.current
    val shown = if (q.isBlank()) options else options.filter { it.label.contains(q.trim(), ignoreCase = true) }
    BottomSheet(onDismiss) {
        SheetHead(title, text)
        if (options.size > 8) BoardInput(q, { q = it }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), placeholder = searchLabel)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (o in shown) {
                val on = o.value == value
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Surface else Color.Transparent)
                        .tap { onPick(o.value); onDismiss() }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        SText(o.label, size = 15.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else SeedyColors.Text)
                        sub(o.value)?.let { SText(it, size = 12.sp, color = SeedyColors.Text3) }
                    }
                    if (on) SIcon(WebIcons.Check, size = 18.dp, color = accent, stroke = 2.6f)
                }
            }
            if (shown.isEmpty()) SText(t("Nothing matches"), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
        }
    }
}

/**
 * Browses the server's folders (not this phone's): Up, the folder's path, its folders (and files with [files]), and
 * "Use this folder". A file is picked by tapping it.
 */
@Composable
fun FolderSheet(
    title: String,
    start: String,
    files: Boolean,
    list: suspend (String, Boolean) -> FolderListing,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var listing by remember { mutableStateOf<FolderListing?>(null) }
    var typed by remember { mutableStateOf(start.trim()) }
    var seq by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf(if (files) start.trim().replace(Regex("[\\\\/][^\\\\/]*$"), "") else start.trim()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(target) {
        val n = ++seq
        listing = null
        typed = target
        val l = list(target, files)
        if (n == seq) listing = l
    }
    BottomSheet(onDismiss) {
        SheetHead(title, t("Folders on the server, not on this device."))
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(t("Up"), { listing?.parent?.let { target = it } }, enabled = listing?.parent != null, height = 46.dp)
            BoardInput(typed, { typed = it }, Modifier.weight(1f), placeholder = "/", keyboard = KeyboardType.Uri, onDone = { scope.launch { target = typed.trim() } })
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp)) {
            val l = listing
            when {
                l == null -> repeat(4) { Box(Modifier.padding(vertical = 5.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) }
                l.entries.isEmpty() -> SText(l.error ?: t("No folders in here."), Modifier.padding(12.dp), size = 13.sp, color = SeedyColors.Text3)
                else -> for (e in l.entries) {
                    val file = e.type == "File"
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).tap { if (file) { onPick(e.path); onDismiss() } else target = e.path }.padding(horizontal = 12.dp),
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
        SheetActions {
            PillButton(t("Cancel"), onDismiss)
            if (!files) PillButton(t("Use this folder"), { onPick(listing?.path ?: target); onDismiss() }, style = PillStyle.Light, enabled = (listing?.path ?: target).isNotEmpty())
        }
    }
}

/** A 40 px icon tile (tuners, guide sources). */
@Composable
fun IconTile(icon: IconDef, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 20.dp, color = SeedyColors.Text)
    }
}

/** A small tag after a name ("Seerr", "XMLTV"), grey on a light tint. */
@Composable
fun SmallTag(label: String, color: Color = SeedyColors.TextHero, dot: Color? = null) {
    Row(
        Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 7.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (dot != null) Dot(dot, 6.dp)
        SText(label, size = 10.sp, weight = 800, color = color, maxLines = 1)
    }
}

/** The space a row's trailing control takes, so a column of rows lines up. */
@Composable
fun Gap(w: Dp) = Spacer(Modifier.width(w))
