package io.github.arkmind.seedy.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.blockTaps
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.cssText
import io.github.arkmind.seedy.player.StillWatchingAsk
import io.github.arkmind.seedy.player.askReason
import io.github.arkmind.seedy.player.countdown
import io.github.arkmind.seedy.player.finishedLine
import io.github.arkmind.seedy.player.nextLine
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.PauseBackdrop
import io.github.arkmind.seedy.ui.components.focusOutline
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.LocalTime

/** The boards' stopwatch: a 9 px circle and its hands. */
internal val StillClock = IconDef(2.2f, "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z", "M12 7.5V12l3 2")

/**
 * Board "Android · Still watching?": [item] ended after a run of episodes (or a long stretch) without a tap, so the
 * player stopped before [next]. Its backdrop behind, sleepy Medu, the next episode, Keep watching or I'm done for now,
 * and the stream's last [secondsLeft] counting down; [fraction] is how far the edge bar goes.
 */
@Composable
fun StillWatchingScreen(
    item: BaseItemDto,
    next: BaseItemDto,
    ask: StillWatchingAsk,
    secondsLeft: Int,
    fraction: Float,
    onKeep: () -> Unit,
    onDone: () -> Unit,
    /** The time "Ends at" counts from (fixed in screenshot tests). */
    now: LocalTime = LocalTime.now(),
    backdrop: @Composable () -> Unit = { NextBackdrop(next) },
    thumb: @Composable BoxScope.() -> Unit = { NextThumb(next) },
) {
    Box(Modifier.fillMaxSize().blockTaps()) {
        PauseBackdrop(backdrop)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 32.dp).width(420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(finishedLine(item), size = 11.sp, weight = 800, tracking = .14f, uppercase = true, color = SeedyColors.Text2, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(t("Still watching?"), size = 28.sp, weight = 900, lineHeight = 1f, tracking = -.02f, color = SeedyColors.White, maxLines = 1)
                // margin: -14px 0 -10px: Medu reaches above and below the title's row.
                Box(Modifier.margins(top = 14, bottom = 10)) { Medu(MeduMood.Sleepy, 58.dp, height = 77.dp, glow = false) }
            }
            SText(askReason(ask, short = true), Modifier.widthIn(max = 400.dp).cssText(13, 1.5f), size = 13.sp, lineHeight = 1.5f, color = SeedyColors.TextHero)
            Row(
                Modifier.padding(top = 2.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xD1181A1E))
                    .border(1.dp, Color.White.copy(alpha = .07f), RoundedCornerShape(12.dp))
                    .padding(start = 8.dp, top = 8.dp, bottom = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1B2A3A)), content = thumb)
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(t("Next"), size = 10.sp, weight = 800, tracking = .1f, uppercase = true, color = SeedyColors.Text2, maxLines = 1)
                    SText(next.name.orEmpty(), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(nextLine(next, now), size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
                }
            }
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // The board's Keep watching carries the browser's focus ring (it has the focus when the screen opens).
                AskButton(t("Keep watching"), onKeep, primary = true)
                AskButton(t("I’m done for now"), onDone, primary = false)
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 32.dp, bottom = 18.dp).widthIn(max = 520.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SIcon(StillClock, size = 14.dp, color = SeedyColors.Text2)
            StopsIn(secondsLeft, 11)
        }
        EdgeBar(fraction, 3)
    }
}

/** "Nobody here? The stream stops in **14:32** to free up the server. Your spot is saved." */
@Composable
private fun StopsIn(secondsLeft: Int, size: Int) {
    val mark = ""
    val (before, after) = t("Nobody here? The stream stops in {time} to free up the server. Your spot is saved.", "time" to mark).split(mark).let { it[0] to it.getOrElse(1) { "" } }
    BasicText(
        buildAnnotatedString {
            append(before)
            withStyle(SpanStyle(fontWeight = FontWeight(700), color = SeedyColors.Text, fontFeatureSettings = "tnum")) { append(countdown(secondsLeft)) }
            append(after)
        },
        Modifier.cssText(size, 1.4f),
        style = BaseText.copy(fontSize = size.sp, lineHeight = 1.4.em, color = SeedyColors.Text2),
    )
}

@Composable
private fun AskButton(label: String, onClick: () -> Unit, primary: Boolean) {
    val ink = if (primary) SeedyColors.InkInverse else SeedyColors.White
    Row(
        Modifier
            .then(if (primary) Modifier.focusOutline(13.dp) else Modifier)
            .height(44.dp).clip(RoundedCornerShape(13.dp))
            .background(if (primary) SeedyColors.Inverse else Color.White.copy(alpha = .12f))
            .tap(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (primary) SIcon(WebIcons.Play, size = 16.dp, color = ink)
        SText(label, size = 14.sp, weight = 800, color = ink, maxLines = 1)
    }
}

/** The bar along the bottom edge: how far the finished episode went (all the way). */
@Composable
private fun BoxScope.EdgeBar(fraction: Float, height: Int) {
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(height.dp).background(Color.White.copy(alpha = .12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Color.White.copy(alpha = .7f)))
    }
}

/** CSS negative vertical margins: the box takes [top] + [bottom] less room than it draws in. */
private fun Modifier.margins(top: Int, bottom: Int): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val t = top.dp.roundToPx()
    layout(p.width, (p.height - t - bottom.dp.roundToPx()).coerceAtLeast(0)) { p.place(0, -t) }
}

/** The next episode's backdrop, else its show's. */
@Composable
private fun NextBackdrop(next: BaseItemDto) {
    val viewer = LocalViewer.current
    Artwork(viewer.images?.backdrop(next, 1920), Modifier.fillMaxSize(), placeholder = Color.Transparent)
}

@Composable
private fun BoxScope.NextThumb(next: BaseItemDto) {
    val viewer = LocalViewer.current
    Artwork(viewer.images?.landscape(next, 320, viewer.episodeImages), Modifier.matchParentSize(), placeholder = Color.Transparent)
}
