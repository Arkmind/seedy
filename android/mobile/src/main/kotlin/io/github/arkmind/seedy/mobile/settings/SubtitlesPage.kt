package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.Choices
import io.github.arkmind.seedy.core.settings.ResolvedStyle
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.PassScene
import io.github.arkmind.seedy.ui.components.SubtitleLine
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.Card

/** Board "Android · Settings · Subtitles": when they turn on (Jellyfin's mode and language) and how they look here. */
@Composable
fun SubtitlesPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav) {
    val sheet = rememberSheet()
    val s = state.style
    val d = state.device
    SettingsPage(t("Subtitles"), t("When subtitles turn on by themselves, and how they look on this phone."), nav.back, overlay = { SheetHost(sheet) }) {
        Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
            SubtitlePreview(s, Modifier.fillMaxWidth(), radius = 16.dp, textSizes = PhonePreviewSizes, side = 10.dp, barInset = 12.dp, barHeight = 4.dp) {
                SText(
                    t("Preview"),
                    Modifier.padding(start = 10.dp, top = 10.dp).clip(CircleShape).background(Color(0xBF0E0E0E)).padding(horizontal = 9.dp, vertical = 3.dp),
                    size = 10.sp, weight = 800, tracking = 0.1f, color = SeedyColors.TextHero, uppercase = true,
                )
            }
        }

        if (state.config != null) {
            SectionTitle(t("When to show them"))
            val accent = LocalAccent.current
            Card(Modifier.padding(horizontal = 16.dp)) {
                Choices.modes().forEachIndexed { i, m ->
                    if (i > 0) RowLine()
                    val on = m.value == state.subtitleMode
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 58.dp).tap(role = Role.RadioButton) { actions.setSubtitleMode(m.value) }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(22.dp).border(2.dp, if (on) accent else Color(0xFF5A5A5A), CircleShape), contentAlignment = Alignment.Center) {
                            if (on) Box(Modifier.size(10.dp).background(accent, CircleShape))
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            SText(m.name, size = 14.sp, weight = 700, color = SeedyColors.White)
                            SText(m.help, size = 12.sp, color = SeedyColors.Text3)
                        }
                    }
                }
            }
            Section(null) {
                row {
                    PickRow(t("Language"), t("Used by Smart and Always"), state.subtitleLanguage) {
                        actions.loadLanguages()
                        val options = listOf(Choice("", t("Any"))) + state.languages
                        sheet.open(t("Language"), options, state.config?.subtitleLanguagePreference.orEmpty()) { v ->
                            actions.setConfig { it.copy(subtitleLanguagePreference = v.ifEmpty { null }) }
                        }
                    }
                }
            }
        }

        Section(t("How they look"), t("Styled subtitles (ASS and SSA) and picture subtitles (PGS, VobSub) keep their own look.")) {
            row {
                SwitchRow(t("Use your phone’s caption style"), t("Follow Settings › Accessibility › Caption preferences"), d.systemCaptionStyle, device = true) { v ->
                    actions.setDevice { it.copy(systemCaptionStyle = v) }
                }
            }
            row { SegRow(t("Size"), null, Choices.sizes(), s.size, device = true) { v -> actions.setSubtitleStyle { it.copy(size = v) } } }
            row { SegRow(t("Weight"), null, Choices.weights(), s.weight, device = true) { v -> actions.setSubtitleStyle { it.copy(weight = v) } } }
            row {
                val fonts = Choices.fonts()
                PickRow(t("Font"), null, Choices.label(fonts, s.font), device = true) {
                    sheet.open(t("Font"), fonts, s.font) { v -> actions.setSubtitleStyle { it.copy(font = v) } }
                }
            }
            row {
                val colors = Choices.colors()
                SwatchRow(t("Text colour"), colors.firstOrNull { it.first.value == s.color }?.first?.label, colors.map { Swatch(it.first.value, it.first.label, it.second) }, s.color, device = true) { v ->
                    actions.setSubtitleStyle { it.copy(color = v) }
                }
            }
            row {
                val bgs = Choices.backgrounds()
                SwatchRow(t("Background"), bgs.firstOrNull { it.first.value == s.background }?.first?.label, bgs.map { Swatch(it.first.value, it.first.label, it.second.takeIf { v -> v != 0L }) }, s.background, device = true) { v ->
                    actions.setSubtitleStyle { it.copy(background = v) }
                }
            }
            row { SegRow(t("Outline"), null, Choices.outlines(), s.outline, device = true) { v -> actions.setSubtitleStyle { it.copy(outline = v) } } }
            row { SegRow(t("Position"), null, Choices.positions(), s.position, device = true) { v -> actions.setSubtitleStyle { it.copy(position = v) } } }
        }

        Section(t("Rendering"), t("Burning subtitles into the picture costs the server a lot. Leave these alone unless subtitles go out of sync.")) {
            row {
                val options = Choices.burnIns()
                PickRow(t("Burn subtitles in"), t("Auto burns in picture subtitles only when needed"), Choices.label(options, d.burnIn), device = true) {
                    sheet.open(t("Burn subtitles in"), options, d.burnIn) { v -> actions.setDevice { it.copy(burnIn = v) } }
                }
            }
            row {
                SwitchRow(t("Show picture subtitles on this phone"), t("Draw PGS and VobSub here instead of on the server"), d.pictureSubtitles, device = true) { v ->
                    actions.setDevice { it.copy(pictureSubtitles = v) }
                }
            }
        }
        SaveLine(state.saving, state.error)
    }
}

/** The phone preview's text sizes (the board's Large is 17 px). */
private val PhonePreviewSizes = mapOf("small" to 12.sp, "normal" to 14.sp, "large" to 17.sp, "xlarge" to 20.sp)

/**
 * The boards' subtitle preview: a moonlit pass, a line in [style] where the player would draw it, and a progress bar
 * standing in for the controls. [overlay] adds the "Preview" tag on the phone.
 */
@Composable
fun SubtitlePreview(
    style: ResolvedStyle,
    modifier: Modifier,
    radius: Dp,
    textSizes: Map<String, TextUnit>,
    side: Dp,
    barInset: Dp,
    barHeight: Dp,
    lifted: Dp = barInset + 22.dp,
    overlay: @Composable () -> Unit = {},
) {
    PassScene(modifier.aspectRatio(16f / 9f).clip(RoundedCornerShape(radius))) {
        val pos = when (style.position) {
            "top" -> Modifier.align(Alignment.TopCenter).padding(top = lifted)
            "bottom" -> Modifier.align(Alignment.BottomCenter).padding(bottom = barInset + barHeight + 6.dp)
            else -> Modifier.align(Alignment.BottomCenter).padding(bottom = lifted)
        }
        SubtitleLine(t("We won't make the pass before dark."), style, textSizes[style.size] ?: 14.sp, pos.padding(horizontal = side))
        Box(Modifier.align(Alignment.BottomStart).padding(start = barInset, end = barInset, bottom = barInset).fillMaxWidth().height(barHeight).clip(RoundedCornerShape(barHeight / 2)).background(Color.White.copy(alpha = 0.25f))) {
            Box(Modifier.fillMaxWidth(0.3f).fillMaxHeight().clip(RoundedCornerShape(barHeight / 2)).background(Color.White))
        }
        overlay()
    }
}
