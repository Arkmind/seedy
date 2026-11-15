package io.github.arkmind.seedy.tv.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.StillWatchingRules
import io.github.arkmind.seedy.tv.browse.TvSidePanel
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.Overlay
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.SettingsIcons
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * "Ask “Are you still watching?”": the ticked rules as the value ("3 episodes or 90 min"). OK opens a panel with the
 * three rules: OK ticks or unticks one, left and right change its count, Back closes it and the remote comes back here.
 */
@Composable
fun TvStillWatchingRow(rules: StillWatchingRules, onChange: (StillWatchingRules) -> Unit, focus: FocusRequester? = null) {
    var open by remember { mutableStateOf(false) }
    var reopened by remember { mutableStateOf(false) }
    val row = focus ?: remember { FocusRequester() }
    TvInfoRow(t("Ask “Are you still watching?”"), t("Asks when any rule you pick is met"), rules.summary(), onClick = { open = true }, focus = row)
    if (open) Overlay {
        TvSidePanel(t("Ask “Are you still watching?”"), onDismiss = { open = false; reopened = true }, width = 760.bp) { first ->
            TvRule(
                t("After episodes in a row"), t("Shows only"), rules.episodesOn, StillWatchingRules.Episodes, rules.episodes, StillWatchingRules::episodesLabel,
                onTick = { onChange(rules.copy(episodesOn = it)) }, onCount = { onChange(rules.copy(episodes = it, episodesOn = true)) },
                modifier = Modifier.focusRequester(first),
            )
            TvRule(
                t("After watching for a while"), t("Shows and movies, without a break"), rules.minutesOn, StillWatchingRules.Minutes, rules.minutes, StillWatchingRules::minutesLabel,
                onTick = { onChange(rules.copy(minutesOn = it)) }, onCount = { onChange(rules.copy(minutes = it, minutesOn = true)) },
            )
            TvRule(
                t("After movies in a row"), t("Before end-of-movie picks or the next film start"), rules.moviesOn, StillWatchingRules.Movies, rules.movies, StillWatchingRules::moviesLabel,
                onTick = { onChange(rules.copy(moviesOn = it)) }, onCount = { onChange(rules.copy(movies = it, moviesOn = true)) },
            )
        }
    }
    LaunchedEffect(reopened) {
        if (reopened) {
            row.focusSoon()
            reopened = false
        }
    }
}

/** One rule in the panel: its box, words and count; the count dims while the rule is unticked. */
@Composable
private fun TvRule(
    name: String,
    help: String,
    on: Boolean,
    counts: List<Int>,
    value: Int,
    label: (Int) -> String,
    onTick: (Boolean) -> Unit,
    onCount: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choices = if (value in counts) counts else (counts + value).sorted()
    val i = choices.indexOf(value)
    TvPressable(
        { onTick(!on) },
        modifier.onKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
            val by = when (e.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> return@onKeyEvent false
            }
            choices.getOrNull(i + by)?.let(onCount)
            true
        },
    ) { focused ->
        val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
        val sub = if (focused) Color(0xFF5A5A5A) else SeedyColors.Text3
        Row(
            Modifier.fillMaxWidth().heightIn(min = 84.bp).focusRing(focused, radius = 18.bp, gap = 3.bp, ring = 4.bp, gapColor = Color(0xFF141414))
                .clip(RoundedCornerShape(18.bp)).background(if (focused) SeedyColors.Inverse else Color.Transparent)
                .padding(horizontal = 22.bp, vertical = 12.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.bp),
        ) {
            val box = RoundedCornerShape(9.bp)
            Box(
                Modifier.size(32.bp).clip(box)
                    .then(if (on) Modifier.background(LocalAccent.current) else Modifier.border(3.bp, if (focused) Color(0xFF8C8C8C) else Color(0xFF5A5A5A), box)),
                contentAlignment = Alignment.Center,
            ) {
                if (on) SIcon(WebIcons.Check, size = 20.bp, color = SeedyColors.InkInverse, stroke = 3.2f)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(name, size = 21.bsp, weight = if (on || focused) 800 else 600, color = ink, maxLines = 1)
                SText(help, size = 16.bsp, color = sub)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                val valueInk = when {
                    focused -> SeedyColors.InkInverse
                    on -> SeedyColors.TextHero
                    else -> SeedyColors.Text3
                }
                if (focused) SIcon(SettingsIcons.ArrowLeft, size = 22.bp, color = if (i > 0) valueInk else valueInk.copy(alpha = .3f))
                SText(label(value), size = 20.bsp, weight = 700, color = valueInk, maxLines = 1)
                if (focused) SIcon(SettingsIcons.ArrowRight, size = 22.bp, color = if (i < choices.lastIndex) valueInk else valueInk.copy(alpha = .3f))
            }
        }
    }
}
