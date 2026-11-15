package io.github.arkmind.seedy.tv.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.player.StillWatchingAsk
import io.github.arkmind.seedy.player.askReason
import io.github.arkmind.seedy.player.countdown
import io.github.arkmind.seedy.player.finishedLine
import io.github.arkmind.seedy.player.nextLine
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.PauseBackdrop
import io.github.arkmind.seedy.ui.components.boxShadow
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
private val StillClock = IconDef(2.2f, "M12 3a9 9 0 1 0 0 18a9 9 0 0 0 0-18z", "M12 7.5V12l3 2")

/**
 * Board "Android TV · Still watching?": [item] ended after a run of episodes (or a long stretch) without a key
 * pressed, so the player stopped before [next]. Its backdrop behind, sleepy Medu, the next episode, Keep watching
 * (where the remote lands) or I'm done for now, the stream's last [secondsLeft] counting down and the keys' hints;
 * [fraction] is how far the edge bar goes. Back (the route's) leaves the player.
 */
@Composable
fun TvStillWatching(
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
    val keep = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        PauseBackdrop(backdrop)
        Column(Modifier.align(Alignment.CenterStart).padding(start = 160.bp).width(853.bp), verticalArrangement = Arrangement.spacedBy(21.bp)) {
            SText(finishedLine(item), size = 17.bsp, weight = 800, tracking = .14f, uppercase = true, color = SeedyColors.Text2, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.bp)) {
                SText(t("Still watching?"), size = 83.bsp, weight = 900, lineHeight = 1f, tracking = -.02f, color = SeedyColors.White, maxLines = 1)
                // margin: -35px 0 -24px: Medu reaches above and below the title's row.
                Box(Modifier.margins(top = 35.bp, bottom = 24.bp)) { Medu(MeduMood.Sleepy, 128.bp, height = 169.bp, glow = false) }
            }
            SText(askReason(ask, short = false), Modifier.widthIn(max = 746.bp), size = 24.bsp, lineHeight = 1.5f, color = SeedyColors.TextHero)
            Row(
                Modifier.padding(top = 8.bp).clip(RoundedCornerShape(21.bp)).background(Color(0xD1181A1E))
                    .border(1.bp, Color.White.copy(alpha = .07f), RoundedCornerShape(21.bp))
                    .padding(start = 16.bp, top = 16.bp, bottom = 16.bp, end = 32.bp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(21.bp),
            ) {
                Box(Modifier.width(224.bp).aspectRatio(16f / 9f).clip(RoundedCornerShape(13.bp)).background(Color(0xFF1B2A3A)), content = thumb)
                Column(verticalArrangement = Arrangement.spacedBy(5.bp)) {
                    SText(t("Next"), size = 16.bsp, weight = 800, tracking = .1f, uppercase = true, color = SeedyColors.Text2, maxLines = 1)
                    SText(next.name.orEmpty(), size = 27.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(nextLine(next, now), size = 17.bsp, color = SeedyColors.Text2, maxLines = 1)
                }
            }
            // The remote stays on the two buttons.
            Row(
                Modifier.padding(top = 13.bp).focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.bp),
            ) {
                AskButton(t("Keep watching"), onKeep, Modifier.focusRequester(keep), icon = WebIcons.Play)
                AskButton(t("I’m done for now"), onDone)
            }
        }
        Row(
            Modifier.align(Alignment.BottomStart).padding(start = 160.bp, bottom = 53.bp).widthIn(max = 1200.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(13.bp),
        ) {
            SIcon(StillClock, size = 24.bp, color = SeedyColors.Text2)
            val mark = ""
            val (before, after) = t("Nobody here? The stream stops in {time} to free up the server. Your spot is saved.", "time" to mark).split(mark)
                .let { it[0] to it.getOrElse(1) { "" } }
            BasicText(
                buildAnnotatedString {
                    append(before)
                    withStyle(SpanStyle(fontWeight = FontWeight(700), color = SeedyColors.Text, fontFeatureSettings = "tnum")) { append(countdown(secondsLeft)) }
                    append(after)
                },
                style = BaseText.copy(fontSize = 19.bsp, lineHeight = 1.4.em, color = SeedyColors.Text2),
            )
        }
        Row(
            Modifier.align(Alignment.BottomEnd).padding(end = 107.bp, bottom = 48.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.bp),
        ) {
            KeyHint(t("OK"), t("Keep watching"))
            KeyHint(t("Back"), t("Stop for now"))
        }
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(5.bp).background(Color.White.copy(alpha = .12f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(Color.White.copy(alpha = .7f)))
        }
    }
    LaunchedEffect(Unit) { keep.focusSoon() }
}

/**
 * The board's 69 px buttons: translucent at rest; focused, light with dark text, 6 % larger from their left edge, with
 * a deep shadow and the browser's focus ring (2 px near-black, then 1 px white).
 */
@Composable
private fun AskButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: IconDef? = null) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, label = "ask-scale")
    val ink = if (focused) SeedyColors.InkInverse else SeedyColors.Text
    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, .5f) }
            .then(if (focused) Modifier.boxShadow(Color.Black.copy(alpha = .55f), blur = 44.bp, y = 20.bp, radius = 22.bp) else Modifier)
            .then(if (focused) Modifier.focusOutline(22.bp, px = 1.bp) else Modifier)
            .height(69.bp).clip(RoundedCornerShape(22.bp))
            .background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = .12f))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 32.bp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(13.bp),
    ) {
        if (icon != null) SIcon(icon, size = 23.bp, color = ink)
        SText(label, size = 21.bsp, weight = 800, color = ink, maxLines = 1)
    }
}

/** "[OK] Keep watching": a key cap and what it does. */
@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
        Box(
            Modifier.defaultMinSize(minWidth = 44.bp).height(36.bp).clip(RoundedCornerShape(10.bp)).background(Color.White.copy(alpha = .12f))
                .padding(horizontal = 10.bp),
            contentAlignment = Alignment.Center,
        ) { SText(key, size = 18.bsp, weight = 800, color = SeedyColors.White, maxLines = 1) }
        SText(label, size = 22.bsp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** CSS negative vertical margins: the box takes [top] + [bottom] less room than it draws in. */
private fun Modifier.margins(top: Dp, bottom: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val t = top.roundToPx()
    layout(p.width, (p.height - t - bottom.roundToPx()).coerceAtLeast(0)) { p.place(0, -t) }
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
    Artwork(viewer.images?.landscape(next, 640, viewer.episodeImages), Modifier.matchParentSize(), placeholder = Color.Transparent)
}
