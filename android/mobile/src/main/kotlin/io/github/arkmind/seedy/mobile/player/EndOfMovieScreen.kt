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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.blockTaps
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.creditsLeft
import io.github.arkmind.seedy.player.pickMeta
import io.github.arkmind.seedy.ui.components.ArtTitle
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.CardArtwork
import io.github.arkmind.seedy.ui.components.CornerMask
import io.github.arkmind.seedy.ui.components.CountdownRing
import io.github.arkmind.seedy.core.format.score
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

/**
 * The end of a movie on the phone (the web's PlayerEndMovie board, laid out for a phone on its side): the movie goes
 * on playing in a tile on the left with Watch credits and Back to browse under it; on the right, "Because you
 * watched", the first pick with Play and its countdown ([left] of [total] s, null without one), and two more to try.
 */
@Composable
fun EndOfMovieScreen(
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
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg).blockTaps()) {
        Row(
            Modifier.fillMaxSize().padding(start = 32.dp, end = 32.dp, top = 60.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp, Alignment.CenterHorizontally),
        ) {
            NowPlaying(item, positionMs, durationMs, onWatchCredits, onBack, video)
            Column(Modifier.weight(1f, fill = false).widthIn(max = 456.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SText(
                    t("Because you watched {title}", "title" to item.name.orEmpty()),
                    size = 11.sp, weight = 800, tracking = .08f, uppercase = true, color = SeedyColors.Text2, maxLines = 1,
                )
                PickCard(pick, left, total, onPlay, pickArt)
                if (rest.isNotEmpty()) {
                    SText(t("Or try"), Modifier.padding(top = 2.dp), size = 11.sp, weight = 700, color = SeedyColors.Text3)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (it in rest) OtherPick(it, Modifier.weight(1f)) { onPlay(it) }
                        if (rest.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
        // Back to browse, as the web's top bar has it.
        Row(Modifier.padding(start = 20.dp, top = 14.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).tap(onClick = onBack), contentAlignment = Alignment.Center) {
                SIcon(WebIcons.ArrowLeft, size = 22.dp, color = SeedyColors.Inverse)
            }
            SText(item.name.orEmpty(), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        }
    }
}

/** The movie, still playing in its tile, how far it is, and the two ways out of here. */
@Composable
private fun NowPlaying(item: BaseItemDto, positionMs: Long, durationMs: Long, onWatchCredits: () -> Unit, onBack: () -> Unit, video: @Composable () -> Unit) {
    Column(Modifier.width(284.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            video()
            CornerMask(12.dp, SeedyColors.Bg, Modifier.matchParentSize())
            Box(Modifier.matchParentSize().border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(12.dp)))
            val f = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 8.dp).fillMaxWidth().height(4.dp)
                    .clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = .18f)),
            ) { Box(Modifier.fillMaxHeight().fillMaxWidth(f).clip(RoundedCornerShape(2.dp)).background(SeedyColors.White)) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(item.name.orEmpty(), size = 17.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(creditsLeft(durationMs - positionMs), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndButton(t("Watch credits"), onWatchCredits, SeedyColors.Surface, SeedyColors.Text, 700)
            EndButton(t("Back to browse"), onBack, Color.Transparent, SeedyColors.Text2, 600)
        }
    }
}

@Composable
private fun EndButton(label: String, onClick: () -> Unit, bg: Color, ink: Color, weight: Int) {
    Box(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(bg).tap(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { SText(label, size = 13.sp, weight = weight, color = ink, maxLines = 1) }
}

/** The first pick: its backdrop with an accent ring, the big title, year · genre · length · score, Play and the ring. */
@Composable
private fun PickCard(pick: BaseItemDto, left: Int?, total: Int, onPlay: (BaseItemDto) -> Unit, art: @Composable BoxScope.(BaseItemDto) -> Unit) {
    val accent = LocalAccent.current
    Box(
        Modifier.fillMaxWidth().height(196.dp).accentRing(accent, 16.dp)
            .clip(RoundedCornerShape(16.dp)).background(SeedyColors.Surface),
    ) {
        art(pick)
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .2f), .3f to Color.Black.copy(alpha = .2f), 1f to Color.Black.copy(alpha = .88f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            SText(pick.name.orEmpty(), size = 26.sp, weight = 900, lineHeight = .9f, tracking = -.01f, uppercase = true, color = SeedyColors.White, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (m in pickMeta(pick)) SText(m, size = 12.sp, color = SeedyColors.TextHero, maxLines = 1)
                pick.score?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        SIcon(WebIcons.Star, size = 12.dp, color = accent)
                        SText(s, size = 12.sp, color = SeedyColors.TextHero, maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.height(36.dp).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Inverse).tap { onPlay(pick) }.padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SIcon(WebIcons.Play, size = 15.dp, color = SeedyColors.InkInverse)
                    SText(t("Play"), size = 13.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
                }
                if (left != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CountdownRing(if (total > 0) left.toFloat() / total else 0f, 18.dp)
                        SText(t("Plays in {n} s", "n" to left.coerceAtLeast(0)), size = 12.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** "Or try": a small picture, the title and year · genre · length. */
@Composable
private fun OtherPick(it: BaseItemDto, modifier: Modifier, onClick: () -> Unit) {
    val viewer = LocalViewer.current
    Row(modifier.tap(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CardArtwork(
            viewer.images?.cardArt(it, 320), it.name.orEmpty(), ArtTitle(8.sp, 5.dp, 5.dp, 5.dp),
            Modifier.size(60.dp, 34.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(it.name.orEmpty(), size = 12.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(pickMeta(it).joinToString(" · "), size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** The web's `outline: 2px solid accent; outline-offset: 3px` around a rounded box. */
private fun Modifier.accentRing(color: Color, radius: Dp): Modifier = drawBehind {
    val w = 2.dp.toPx()
    val at = 3.dp.toPx() + w / 2
    drawRoundRect(color, Offset(-at, -at), Size(size.width + 2 * at, size.height + 2 * at), CornerRadius(radius.toPx() + at), style = Stroke(w))
}

/** The pick's backdrop, else its card picture. */
@Composable
private fun BoxScope.PickBackdrop(pick: BaseItemDto) {
    val viewer = LocalViewer.current
    Artwork(viewer.images?.let { it.backdrop(pick, 1280) ?: it.cardArt(pick, 1280).src }, Modifier.matchParentSize(), placeholder = Color.Transparent)
}
