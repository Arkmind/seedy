package io.github.arkmind.seedy.mobile.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * The phone boards' page padding: [top] and [bottom] include the system bars (the boards leave 36 px above and about
 * 28 px below), so a taller status bar pushes the page down instead of covering it.
 */
@Composable
fun pagePadding(top: Dp = 36.dp, side: Dp = 24.dp, bottom: Dp = 28.dp): PaddingValues {
    val status = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return PaddingValues(start = side, end = side, top = max(top, status + 12.dp), bottom = max(bottom, nav + 12.dp))
}

/** Click without the default grey press overlay; the boards' controls change colour themselves. */
fun Modifier.tap(role: Role = Role.Button, enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this.clickable(interactionSource = null, indication = null, enabled = enabled, role = role, onClick = onClick)

/** Widens the element by [h] on both sides, into the page margin (CSS `margin: 0 -8px`). */
fun Modifier.bleed(h: Dp): Modifier = this.layout { measurable, constraints ->
    val extra = (h * 2).roundToPx()
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
    layout(placeable.width - extra, placeable.height) { placeable.place(-h.roundToPx(), 0) }
}

/** A 44 px header button with a 22 px icon (back arrow, search, cast…). */
@Composable
fun HeaderButton(icon: IconDef, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = SeedyColors.White, stroke: Float = icon.stroke) {
    Box(modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = 22.dp, color = color, stroke = stroke)
    }
}

/** The 52 px light button that ends a form ("Sign in", "Connect"). */
@Composable
fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp))
            .background(if (enabled) SeedyColors.Inverse else SeedyColors.Surface2)
            .tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        SText(label, size = 15.sp, weight = 800, color = if (enabled) SeedyColors.InkInverse else SeedyColors.Text3)
    }
}

/**
 * The sign-in pages' bottom card (Quick Connect): an icon tile in the accent, a title and a line under it, a chevron.
 */
@Composable
fun ActionCard(icon: IconDef, title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, iconColor: Color = LocalAccent.current) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel)
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp))
            .tap(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(SeedyColors.Surface, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            SIcon(icon, size = 20.dp, color = iconColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 2)
        }
        SIcon(WebIcons.ChevronRight, size = 16.dp, color = SeedyColors.Text2, stroke = 2.4f)
    }
}

/**
 * The boards' 52 px text field: a dark well that rings in the accent while focused. [password] hides the text and adds
 * the show/hide eye.
 */
@Composable
fun SeedyField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    onDone: () -> Unit = {},
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var shown by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row)
            .then(if (focused) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier)
            .padding(start = 16.dp, end = if (password) 4.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty() && placeholder.isNotEmpty()) SText(placeholder, size = 16.sp, color = SeedyColors.Text3, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                interactionSource = interaction,
                textStyle = BaseText.copy(
                    fontSize = 16.sp,
                    fontWeight = FontWeight(700),
                    color = SeedyColors.White,
                    letterSpacing = if (password && !shown) 0.1.em else 0.em,
                ),
                cursorBrush = SolidColor(accent),
                visualTransformation = if (password && !shown) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboard,
                    imeAction = imeAction,
                    autoCorrectEnabled = false,
                ),
                keyboardActions = KeyboardActions(onDone = { onDone() }, onGo = { onDone() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (password) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap { shown = !shown }, contentAlignment = Alignment.Center) {
                SIcon(if (shown) WebIcons.EyeOff else AppIcons.ShowPassword, size = 20.dp, color = SeedyColors.Text2)
            }
        }
    }
}

/** A form label above a field (13 px bold). */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier, size = 13.sp, weight = 700, color = SeedyColors.Text)
}
