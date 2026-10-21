package io.github.arkmind.seedy.mobile.admin.people

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.DeviceKind
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.cssBox
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * Pieces the people pages share beyond the admin and settings kits: a page whose title can be tapped (renaming a
 * user), the scrolling tab pills, the counted segmented filter, chips, and the device glyphs.
 */

object PeopleIcons {
    val Tv = IconDef(1.8f, "M5 5h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M8 21h8")
    val Phone = IconDef(1.8f, "M9 3h6a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z", "M11 18h2")
    val Computer = IconDef(1.8f, "M6 5h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M2 19h20")
    val Minus = IconDef(2.6f, "M5 12h14")

    fun of(kind: DeviceKind): IconDef = when (kind) {
        DeviceKind.Tv -> Tv
        DeviceKind.Phone -> Phone
        DeviceKind.Computer -> Computer
    }
}

/** #5a5a5a: the count on the picked filter. */
private val CountOn = Color(0xFF5A5A5A)

/**
 * The settings page frame (back arrow, 22 px title, lede, scrolling content, overlay) with a title that can be
 * tapped.
 */
@Composable
fun PeoplePage(
    title: String,
    lede: String?,
    onBack: () -> Unit,
    onTitle: (() -> Unit)? = null,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())) {
            val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = top), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
                SText(
                    title,
                    Modifier.weight(1f).then(if (onTitle != null) Modifier.tap(onClick = onTitle) else Modifier).padding(start = 6.dp),
                    size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1,
                )
            }
            if (lede != null) SText(lede, Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp).cssBox(13, 1.5f), size = 13.sp, lineHeight = 1.5f, color = SeedyColors.Text2)
            content()
        }
        overlay()
    }
}

/** The boards' scrolling pills (a user's tabs, the people filter): light when picked. */
@Composable
fun <T> TabPills(options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit, modifier: Modifier = Modifier, top: Dp = 16.dp) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = top),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((v, label) in options) {
            val on = v == value
            Box(
                Modifier.height(36.dp).clip(CircleShape).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
                    .tap(role = Role.Tab) { onPick(v) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
            }
        }
    }
}

/** The users filter: a segmented control whose options carry a count ("All 5"). */
@Composable
fun <T> CountSegmented(options: List<Triple<T, String, Int>>, value: T, onPick: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for ((v, label, n) in options) {
            val on = v == value
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap(role = Role.Tab) { onPick(v) },
                contentAlignment = Alignment.Center,
            ) {
                val text = buildAnnotatedString {
                    append(label)
                    append(" ")
                    withStyle(SpanStyle(fontWeight = FontWeight(600), color = if (on) CountOn else SeedyColors.Text3)) { append(n.toString()) }
                }
                BasicText(
                    text,
                    style = BaseText.copy(fontSize = 13.sp, fontWeight = FontWeight(if (on) 800 else 600), color = if (on) SeedyColors.InkInverse else SeedyColors.TextHero, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
    }
}

/** A tag in a list that can be taken out (Parental controls' tags). */
@Composable
fun Chip(label: String, onRemove: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).background(SeedyColors.Surface).padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SText(label, size = 13.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
        Box(Modifier.size(26.dp).clip(CircleShape).tap(onClick = onRemove), contentAlignment = Alignment.Center) {
            SIcon(WebIcons.Close, size = 12.dp, color = SeedyColors.Text2)
        }
    }
}

/** The dashed "Add tag" chip. */
@Composable
fun AddChip(label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(32.dp).clip(CircleShape).border(1.dp, SeedyColors.Stroke, CircleShape).tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SIcon(WebIcons.Plus, size = 12.dp, color = SeedyColors.Text2)
        SText(label, size = 13.sp, weight = 700, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** A row's name and help over what it holds (chips, fields), inside the card's row padding. */
@Composable
fun StackRow(name: String, help: String?, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White)
            if (!help.isNullOrEmpty()) SText(help, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
        content()
    }
}

/** A row in the accent with a plus ("Add a time", "Show every device"). */
@Composable
fun AccentRow(label: String, onClick: () -> Unit, icon: IconDef? = WebIcons.Plus) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) SIcon(icon, size = 14.dp, color = accent)
        SText(label, size = 14.sp, weight = 700, color = accent)
    }
}

/** A line saying something went wrong, in red (forms). */
@Composable
fun FormError(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier, size = 13.sp, weight = 600, lineHeight = 1.4f, color = SeedyColors.Danger)
}

/** A stepper for a value in steps ("9:00" with − and +). */
@Composable
fun Stepper(label: String, value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
        StepButton(PeopleIcons.Minus, onMinus)
        SText(value, Modifier.padding(horizontal = 4.dp), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1, align = TextAlign.Center)
        StepButton(WebIcons.Plus, onPlus)
    }
}

@Composable
private fun StepButton(icon: IconDef, onClick: (() -> Unit)?) {
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(SeedyColors.Surface).tap(enabled = onClick != null) { onClick?.invoke() },
        contentAlignment = Alignment.Center,
    ) { SIcon(icon, size = 16.dp, color = if (onClick != null) SeedyColors.White else SeedyColors.Stroke) }
}
