package io.github.arkmind.seedy.tv.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.SettingsIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlin.math.roundToInt

/*
 * The TV settings boards' parts ("Android TV · Settings · …"): rows that light up under the remote, change their value
 * with left and right (switches with OK), the "This TV" tag and the 64 × 36 switch.
 */

private val TrackOff = Color(0xFF3A3A3A)
private val TagRing = Color(0xFF3A3A3A)
private val TagRingFocused = Color(0xFF9A9A9A)
private val SubFocused = Color(0xFF4A4A4A)

/** A section's title in the right pane (15 px capitals). */
@Composable
fun TvSectionTitle(text: String) {
    SText(
        text, Modifier.padding(start = 24.bp, end = 24.bp, top = 18.bp, bottom = 8.bp).cssBox(15f, io.github.arkmind.seedy.ui.theme.NotoLineRatio, up = false),
        size = 15.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true,
    )
}

/** The "This TV" tag: a thin ring, darker on a focused row. */
@Composable
fun TvTag(focused: Boolean, label: String = t("This TV")) {
    Box(Modifier.clip(CircleShape).border(1.5.bp, if (focused) TagRingFocused else TagRing, CircleShape).padding(horizontal = 10.bp, vertical = 2.bp)) {
        SText(label, size = 13.bsp, weight = 700, color = if (focused) SubFocused else SeedyColors.Text3, maxLines = 1)
    }
}

/** The boards' TV switch: 64 × 36, the accent with a dark knob when on. */
@Composable
fun TvSwitch(on: Boolean) {
    val accent = LocalAccent.current
    Box(Modifier.size(64.bp, 36.bp).clip(CircleShape).background(if (on) accent else TrackOff)) {
        Box(Modifier.padding(start = if (on) 33.bp else 5.bp, top = 5.bp).size(26.bp).background(if (on) SeedyColors.InkInverse else SeedyColors.Text2, CircleShape))
    }
}

/**
 * A settings row: name (with the tag), a line under it, and on the right whatever [end] draws. Focused, it turns light
 * with dark text, grows a little and casts a shadow. [onKey] sees the remote's keys first (left and right change values).
 */
@Composable
fun TvRowFrame(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onKey: (Key) -> Boolean = { false },
    content: @Composable (focused: Boolean) -> Unit,
) {
    TvPressable(
        onClick,
        modifier.fillMaxWidth().padding(bottom = 4.bp)
            .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
            .onKeyEvent { e -> e.type == KeyEventType.KeyDown && onKey(e.key) },
    ) { f ->
        Box(
            Modifier.focusScale(f, 1.02f)
                .then(if (f) Modifier.boxShadow(Color.Black.copy(alpha = 0.5f), 30.bp, 14.bp, 18.bp) else Modifier)
                .clip(RoundedCornerShape(18.bp)).background(if (f) SeedyColors.Inverse else Color.Transparent),
        ) { content(f) }
    }
}

/** The name, its tag and the line under it. */
@Composable
fun TvRowText(name: String, help: String?, device: Boolean, focused: Boolean, modifier: Modifier = Modifier, nameColor: Color? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.bp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
            SText(name, Modifier.weight(1f, fill = false), size = 21.bsp, weight = 700, color = if (focused) SeedyColors.InkInverse else nameColor ?: SeedyColors.White, maxLines = 1)
            if (device) TvTag(focused)
        }
        if (!help.isNullOrEmpty()) SText(help, size = 16.bsp, lineHeight = 1.35f, color = if (focused) SubFocused else SeedyColors.Text3)
    }
}

private val RowPad = Modifier.padding(horizontal = 24.bp, vertical = 12.bp).heightIn(min = 56.bp)

/** A row with a value: left and right step through [choices] (stopping at the ends), OK moves to the next one. */
@Composable
fun <T> TvChoiceRow(
    name: String,
    help: String?,
    choices: List<Choice<T>>,
    value: T,
    onChange: (T) -> Unit,
    device: Boolean = false,
    dot: Color? = null,
    focus: FocusRequester? = null,
    label: String? = null,
) {
    val i = choices.indexOfFirst { it.value == value }
    fun step(by: Int) {
        if (choices.isEmpty()) return
        val n = if (i < 0) 0 else (i + by).coerceIn(0, choices.lastIndex)
        if (n != i) onChange(choices[n].value)
    }
    TvRowFrame(
        onClick = { if (choices.isNotEmpty()) onChange(choices[if (i < 0) 0 else (i + 1) % choices.size].value) },
        focus = focus,
        onKey = { k ->
            when (k) {
                Key.DirectionLeft -> { step(-1); true }
                Key.DirectionRight -> { step(1); true }
                else -> false
            }
        },
    ) { f ->
        Row(RowPad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.bp)) {
            TvRowText(name, help, device, f, Modifier.weight(1f))
            ValueText(label ?: choices.getOrNull(i)?.label ?: value.toString(), f, arrows = f, dot = dot)
        }
    }
}

@Composable
private fun ValueText(text: String, focused: Boolean, arrows: Boolean, dot: Color? = null, chevron: Boolean = false) {
    val ink = if (focused) SeedyColors.InkInverse else SeedyColors.TextHero
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
        if (dot != null) Box(Modifier.size(22.bp).clip(CircleShape).background(dot).border(1.5.bp, Color.Black.copy(alpha = 0.3f), CircleShape))
        if (arrows) SIcon(SettingsIcons.ArrowLeft, size = 22.bp, color = ink)
        SText(text, size = 20.bsp, weight = 700, color = ink, maxLines = 1)
        if (arrows || chevron) SIcon(SettingsIcons.ArrowRight, size = 22.bp, color = ink)
    }
}

/** A switch row: OK turns it on or off. */
@Composable
fun TvSwitchRow(name: String, help: String?, on: Boolean, onChange: (Boolean) -> Unit, device: Boolean = false, focus: FocusRequester? = null) {
    TvRowFrame(onClick = { onChange(!on) }, focus = focus) { f ->
        Row(RowPad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.bp)) {
            TvRowText(name, help, device, f, Modifier.weight(1f))
            TvSwitch(on)
        }
    }
}

/** A row that shows a fact, or opens something with OK ([chevron]). */
@Composable
fun TvInfoRow(name: String, help: String?, value: String, onClick: () -> Unit = {}, chevron: Boolean = false, focus: FocusRequester? = null) {
    TvRowFrame(onClick = onClick, focus = focus) { f ->
        Row(RowPad.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.bp)) {
            TvRowText(name, help, false, f, Modifier.weight(1f))
            ValueText(value, f, arrows = false, chevron = chevron)
        }
    }
}

/**
 * Keeps the row in use clear of the pane's faded bottom (150 px) and its top: the pane scrolls only when focus gets
 * that close to an edge.
 */
@OptIn(ExperimentalFoundationApi::class)
class EdgeScroll(private val top: Float, private val bottom: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
        offset < top -> offset - top
        offset + size > containerSize - bottom -> offset + size - (containerSize - bottom)
        else -> 0f
    }
}

/** A text box as tall as CSS makes it (lines × [ratio] × [size] board px), rounded down, up or to the nearest. */
internal fun Modifier.cssBox(size: Float, ratio: Float, up: Boolean? = null): Modifier = this.layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val line = ratio * size.bp.toPx()
    val lines = (p.height / line).roundToInt().coerceAtLeast(1)
    val exact = lines * line
    val h = when (up) {
        true -> kotlin.math.ceil(exact).toInt()
        false -> kotlin.math.floor(exact).toInt()
        null -> exact.roundToInt()
    }
    layout(p.width, h) { p.place(0, (h - p.height) / 2) }
}

