package io.github.arkmind.seedy.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.format.score
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.player.creditsLeft
import io.github.arkmind.seedy.player.pickMeta
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.ArtTitle
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.CardArtwork
import io.github.arkmind.seedy.ui.components.CornerMask
import io.github.arkmind.seedy.ui.components.CountdownRing
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

/* The web board is 1440 px wide, the TV's 1920: one of its px is 4/3 of a TV board px, 2/3 dp. */
private val Number.w: Dp get() = (toFloat() * 2f / 3f).dp
private val Number.ws: TextUnit get() = (toFloat() * 2f / 3f).sp

/**
 * The end of a movie on the TV (the web's PlayerEndMovie board): the movie goes on playing in a tile on the left
 * with Watch credits and Back to browse; on the right, "Because you watched", the first pick with Play (where the
 * remote lands) and its countdown ([left] of [total] s, null without one), and "Or try" with two more. Back (the
 * route's) is Back to browse.
 */
@Composable
fun TvEndOfMovie(
    item: BaseItemDto,
    picks: List<BaseItemDto>,
    positionMs: Long,
    durationMs: Long,
    left: Int?,
    total: Int,
    onWatchCredits: () -> Unit,
    onBack: () -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    /** The playing video (moved here from the full screen). */
    video: @Composable () -> Unit,
    pickArt: @Composable BoxScope.(BaseItemDto) -> Unit = { PickBackdrop(it) },
) {
    val pick = picks.firstOrNull() ?: return
    val rest = picks.drop(1).take(2)
    val play = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg).focusProperties { onExit = { cancelFocusChange() } }.focusGroup()) {
        Row(
            Modifier.fillMaxSize().padding(start = 40.w, end = 40.w, top = 104.w, bottom = 32.w),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(56.w, Alignment.CenterHorizontally),
        ) {
            Column(Modifier.width(420.w), verticalArrangement = Arrangement.spacedBy(14.w)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
                    video()
                    CornerMask(13.w, SeedyColors.Bg, Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().border(1.w, Color.White.copy(alpha = .08f), RoundedCornerShape(13.w)))
                    val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(start = 8.w, end = 8.w, bottom = 8.w).fillMaxWidth().height(4.w)
                            .clip(RoundedCornerShape(2.w)).background(Color.White.copy(alpha = .18f)),
                    ) { Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(2.w)).background(SeedyColors.White)) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.w)) {
                    SText(item.name.orEmpty(), size = 20.ws, weight = 800, color = SeedyColors.White, maxLines = 1)
                    SText(creditsLeft(durationMs - positionMs), size = 13.ws, color = SeedyColors.Text3, maxLines = 1)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.w)) {
                    EndButton(t("Watch credits"), onWatchCredits, SeedyColors.Surface, SeedyColors.Text, 700)
                    EndButton(t("Back to browse"), onBack, Color.Transparent, SeedyColors.Text2, 600)
                }
            }
            Column(Modifier.width(620.w), verticalArrangement = Arrangement.spacedBy(16.w)) {
                SText(
                    t("Because you watched {title}", "title" to item.name.orEmpty()),
                    size = 13.ws, weight = 700, tracking = .08f, uppercase = true, color = SeedyColors.Text2, maxLines = 1,
                )
                PickCard(pick, left, total, onPlay, Modifier.focusRequester(play), pickArt)
                if (rest.isNotEmpty()) {
                    SText(t("Or try"), size = 13.ws, weight = 700, color = SeedyColors.Text3)
                    Row(horizontalArrangement = Arrangement.spacedBy(20.w)) {
                        for (it in rest) OtherPick(it, Modifier.weight(1f)) { onPlay(it) }
                        if (rest.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
        // The web's top bar: Back to browse and the title.
        Row(Modifier.padding(32.w), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.w)) {
            TvPressable(onBack) { f ->
                Box(
                    Modifier.focusScale(f, 1.06f).size(48.w).clip(RoundedCornerShape(13.w)).background(if (f) SeedyColors.Inverse else SeedyColors.Surface),
                    contentAlignment = Alignment.Center,
                ) { SIcon(WebIcons.ArrowLeft, size = 22.w, color = if (f) SeedyColors.InkInverse else SeedyColors.Inverse) }
            }
            SText(item.name.orEmpty(), size = 15.ws, weight = 800, color = SeedyColors.White, maxLines = 1)
        }
    }
    LaunchedEffect(Unit) { play.focusSoon() }
}

/** The web's secondary and ghost buttons; focused, light with dark text. */
@Composable
private fun EndButton(label: String, onClick: () -> Unit, bg: Color, ink: Color, weight: Int) {
    TvPressable(onClick) { f ->
        Box(
            Modifier.focusScale(f, 1.04f).height(44.w).clip(RoundedCornerShape(13.w)).background(if (f) SeedyColors.Inverse else bg).padding(horizontal = 18.w),
            contentAlignment = Alignment.Center,
        ) { SText(label, size = 14.ws, weight = if (f) 800 else weight, color = if (f) SeedyColors.InkInverse else ink, maxLines = 1) }
    }
}

/** The first pick: its backdrop in an accent ring, the big title, year genre length score, Play and the countdown. */
@Composable
private fun PickCard(pick: BaseItemDto, left: Int?, total: Int, onPlay: (BaseItemDto) -> Unit, playModifier: Modifier, art: @Composable BoxScope.(BaseItemDto) -> Unit) {
    val accent = LocalAccent.current
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).accentRing(accent, 16.w).clip(RoundedCornerShape(16.w)).background(SeedyColors.Surface)) {
        art(pick)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .2f), .3f to Color.Black.copy(alpha = .2f), 1f to Color.Black.copy(alpha = .88f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 24.w, end = 24.w, bottom = 22.w), verticalArrangement = Arrangement.spacedBy(10.w)) {
            SText(pick.name.orEmpty(), size = 44.ws, weight = 900, lineHeight = .9f, tracking = -.01f, uppercase = true, color = SeedyColors.White, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.w)) {
                for (m in pickMeta(pick)) SText(m, size = 14.ws, color = SeedyColors.TextHero, maxLines = 1)
                pick.score?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.w)) {
                        SIcon(WebIcons.Star, size = 14.w, color = accent)
                        SText(s, size = 14.ws, color = SeedyColors.TextHero, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.w)) {
                TvPressable({ onPlay(pick) }, playModifier) { f ->
                    Row(
                        Modifier.focusRing(f, 13.w, gap = 3.w, ring = 3.w, gapColor = Color(0xFF0A0A0A))
                            .height(48.w).clip(RoundedCornerShape(13.w)).background(SeedyColors.Inverse).padding(horizontal = 28.w),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.w),
                    ) {
                        SIcon(WebIcons.Play, size = 18.w, color = SeedyColors.InkInverse)
                        SText(t("Play"), size = 15.ws, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
                    }
                }
                if (left != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.w)) {
                        CountdownRing(if (total > 0) left.toFloat() / total else 0f, 22.w)
                        SText(t("Plays in {n} s", "n" to left.coerceAtLeast(0)), size = 13.ws, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** "Or try": the picture (an accent ring when the remote is on it), the title and year · genre · length. */
@Composable
private fun OtherPick(it: BaseItemDto, modifier: Modifier, onClick: () -> Unit) {
    val viewer = LocalViewer.current
    val accent = LocalAccent.current
    TvPressable(onClick, modifier) { f ->
        Column(verticalArrangement = Arrangement.spacedBy(10.w)) {
            CardArtwork(
                viewer.images?.cardArt(it, 640), it.name.orEmpty(), ArtTitle(18.ws, 14.w, 64.w, 18.w),
                Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(if (f) Modifier.accentRing(accent, 13.w) else Modifier).clip(RoundedCornerShape(13.w)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.w)) {
                SText(it.name.orEmpty(), size = 15.ws, weight = 700, color = SeedyColors.White, maxLines = 1)
                SText(pickMeta(it).joinToString(" · "), size = 13.ws, color = SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** The web's `outline: 2px solid accent; outline-offset: 3px` around a rounded box. */
private fun Modifier.accentRing(color: Color, radius: Dp): Modifier = drawBehind {
    val w = 2.w.toPx()
    val at = 3.w.toPx() + w / 2
    drawRoundRect(color, Offset(-at, -at), Size(size.width + 2 * at, size.height + 2 * at), CornerRadius(radius.toPx() + at), style = Stroke(w))
}

/** The pick's backdrop, else its card picture. */
@Composable
private fun BoxScope.PickBackdrop(pick: BaseItemDto) {
    val viewer = LocalViewer.current
    Artwork(viewer.images?.let { it.backdrop(pick, 1920) ?: it.cardArt(pick, 1920).src }, Modifier.matchParentSize(), placeholder = Color.Transparent)
}
