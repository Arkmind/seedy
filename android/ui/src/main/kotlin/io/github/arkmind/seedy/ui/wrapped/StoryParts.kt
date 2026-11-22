package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.wrapped.Wrapped
import io.github.arkmind.seedy.core.wrapped.genreName
import io.github.arkmind.seedy.core.wrapped.hoursOf
import io.github.arkmind.seedy.core.wrapped.personalityName
import io.github.arkmind.seedy.core.wrapped.summaryAlso
import io.github.arkmind.seedy.core.wrapped.summaryCounts
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SText

/* Pieces the phone and the TV share: the boards' icons, the progress bar, the summary's card. */

private val Ink = WrappedInk

/** The Wrapped boards' icons. */
object WrappedIcons {
    val Pause = IconDef(2.4f, "M8 5v14", "M16 5v14")
    val Close = IconDef(2.4f, "M6 6l12 12", "M18 6L6 18")
    val Share = IconDef(2.4f, "M12 3v12", "M7 8l5-5 5 5", "M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6")
    val Go = IconDef(2.6f, "M5 12h14", "M13 6l6 6-6 6")
    val Save = IconDef(2.4f, "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
    val Again = IconDef(2.4f, "M4 12a8 8 0 1 0 2.4-5.7", "M4 4v5h5")
    val Bolt = IconDef(2.4f, "M13 2L4.5 13.5H11l-1 8.5 8.5-11.5H12z")
    val Next = IconDef(2.6f, "M9 6l6 6-6 6")

    private const val Ring = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0z"
    private val Personalities = mapOf(
        "sprinter" to Bolt,
        "rewatcher" to IconDef(2.4f, "M4 12a8 8 0 1 0 2.4-5.7", "M4 4v5h5"),
        "cinephile" to IconDef(2.4f, "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4"),
        "explorer" to IconDef(2.4f, Ring, "M15.5 8.5l-2 5-5 2 2-5z"),
        "regular" to IconDef(2.4f, "M5.5 5h13a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z", "M3.5 10h17M8 3v4M16 3v4"),
        "slowburner" to IconDef(2.4f, "M7 3h10M7 21h10", "M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9"),
        "weekender" to IconDef(2.4f, "M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3", "M3 11a2 2 0 0 1 2 2v2h14v-2a2 2 0 1 1 2 2v3H3v-3a2 2 0 0 1 0-4z", "M6 18v2M18 18v2"),
        "completionist" to IconDef(2.4f, Ring, "M8 12.5l2.7 2.7L16 9.5"),
        "easygoing" to IconDef(2.4f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z"),
    )

    /** The watch personality's icon (the web's PersonalityIcon): a bolt for the sprinter, a ticked ring for the completionist… */
    fun personality(key: String): IconDef = Personalities[key] ?: Personalities.getValue("easygoing")
}

/** The stories' bar: 3 px segments 4 px apart, past ones full, the current one filling. */
@Composable
fun Segments(player: StoryPlayer, ink: Color, modifier: Modifier = Modifier, height: Dp = 3.dp, gap: Dp = 4.dp, radius: Dp = 2.dp, track: Float = .25f) {
    Box(
        modifier.height(height).drawBehind {
            val g = gap.toPx()
            val w = (size.width - g * (player.count - 1)) / player.count
            val r = CornerRadius(radius.toPx())
            val fill = player.progress.floatValue
            for (i in 0 until player.count) {
                val x = i * (w + g)
                drawRoundRect(ink.copy(alpha = track), Offset(x, 0f), Size(w, size.height), r)
                val f = when {
                    i < player.index -> 1f
                    i == player.index -> fill
                    else -> 0f
                }
                if (f > 0f) drawRoundRect(ink, Offset(x, 0f), Size(w * f, size.height), r)
            }
        },
    )
}

/** The card the summary shares and saves: the year at a glance on the accent. */
@Composable
fun SummaryCard(w: Wrapped, accent: Color, modifier: Modifier = Modifier) {
    val first = w.shows.firstOrNull()
    val label: @Composable (String) -> Unit = { SText(it, size = 11.sp, weight = 800, tracking = .08f, color = Ink.copy(alpha = .6f), uppercase = true, maxLines = 1) }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(accent)) {
        BoardCanvas(Modifier.matchParentSize()) { _, _ -> waves(listOf(470f, 510f), 18f, 20f, Color.White.copy(alpha = .25f), to = 440f) }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Medu(MeduMood.Party, 64.dp, height = 84.dp, glow = false)
                Column {
                    SText(t("Seedy Wrapped"), size = 12.sp, weight = 800, tracking = .08f, color = Ink.copy(alpha = .6f), uppercase = true, maxLines = 1)
                    SText(t("My {year}", "year" to w.year), size = 26.sp, weight = 900, lineHeight = 1f, tracking = -.02f, color = Ink, maxLines = 1)
                }
            }
            if (first != null) {
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(128.dp).clip(RoundedCornerShape(18.dp))) {
                    ShowArt(first, 0, wide = true, modifier = Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xCC0E0E0E)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        SText("#1 · ${first.name}", size = 11.sp, weight = 800, color = Color.White, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (w.shows.isNotEmpty()) {
                    Column(Modifier.weight(1.15f)) {
                        // An inline label on the card's 16 px line (the other column's are blocks).
                        Box(Modifier.padding(top = 5.dp, bottom = 2.dp)) { label(t("Top shows")) }
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            w.shows.take(5).forEachIndexed { i, s ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SText("${i + 1}", Modifier.width(12.dp).alignByBaseline(), size = 14.sp, weight = 900, color = Ink.copy(alpha = .55f), maxLines = 1)
                                    FitText(s.name, 14.sp, 12.sp, 2, 800, Ink, Modifier.weight(1f).alignByBaseline(), lineHeight = 1.2f)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        label(t("Hours"))
                        SText(formatNumber(hoursOf(w.watchMs)), size = 30.sp, weight = 900, lineHeight = 1.05f, tracking = -.02f, color = Ink, maxLines = 1)
                        val counts = summaryCounts(w)
                        if (counts.isNotEmpty()) SText(counts, Modifier.padding(top = 2.dp), size = 11.sp, weight = 800, color = Ink.copy(alpha = .7f))
                    }
                    w.genres.firstOrNull()?.let { g ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            label(t("Top genre"))
                            FitText(genreName(g), 18.sp, 13.sp, 2, 900, Ink, Modifier.fillMaxWidth(), lineHeight = 1.05f, tracking = -.02f)
                        }
                    }
                    w.movie?.let { m ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            label(t("Top movie"))
                            FitText(m.name, 18.sp, 12.sp, 3, 900, Ink, Modifier.fillMaxWidth(), lineHeight = 1.05f, tracking = -.02f)
                        }
                    }
                }
            }
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    SText(t("Watch personality"), size = 10.sp, weight = 800, tracking = .1f, color = accent, uppercase = true, maxLines = 1)
                    FitText(personalityName(w.personality.key), 17.sp, 13.sp, 1, 900, Color.White, Modifier.fillMaxWidth())
                }
                SIcon(WrappedIcons.personality(w.personality.key), size = 24.dp, color = Color.White)
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SText(summaryAlso(w), Modifier.weight(1f), size = 12.sp, weight = 800, color = Ink)
                SText("seedy", size = 12.sp, weight = 800, color = Ink.copy(alpha = .6f), maxLines = 1)
            }
        }
    }
}

