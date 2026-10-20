package io.github.arkmind.seedy.tv.browse

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import io.github.arkmind.seedy.core.format.inProgress
import io.github.arkmind.seedy.core.format.libraryMetaLong
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.ArtTitle
import io.github.arkmind.seedy.ui.components.CardArtwork
import io.github.arkmind.seedy.ui.components.CardProgress
import io.github.arkmind.seedy.ui.components.WatchedMark
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

/* Pieces the TV's browse pages share (a library, Favorites, Search, Details). Sizes are the boards' px (.bp). */

/** Board "Android TV · Movies library" card: 16:9 art with the title on it, the white ring when focused, title and line. */
@Composable
fun TvGridCard(
    item: BaseItemDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String = item.libraryMetaLong,
    onFocus: () -> Unit = {},
) {
    val viewer = LocalViewer.current
    TvPressable(onClick, modifier.onFocusChanged { if (it.isFocused) onFocus() }) { focused ->
        Column(verticalArrangement = Arrangement.spacedBy(12.bp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(focused, radius = 16.bp, gap = 4.bp, ring = 5.bp).clip(RoundedCornerShape(16.bp))) {
                val progress = item.inProgress
                CardArtwork(
                    viewer.images?.cardArt(item, 640, viewer.episodeImages), item.name.orEmpty(),
                    ArtTitle(22.bsp, start = 16.bp, end = 50.bp, bottom = if (progress) 26.bp else 16.bp), Modifier.matchParentSize(),
                )
                if (item.userData?.played == true) WatchedMark(size = 34.bp, inset = 10.bp, check = 18.bp, stroke = 3f)
                if (progress) CardProgress(item.progress, side = 10.bp, bottom = 8.bp, height = 5.bp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(item.name.orEmpty(), size = 19.bsp, weight = if (focused) 800 else 700, color = SeedyColors.White, maxLines = 1)
                if (meta.isNotEmpty()) SText(meta, size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** A grid card's stand-in while the page loads. */
@Composable
fun TvCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.bp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.bp)).background(SeedyColors.Surface))
        Box(Modifier.width(160.bp).height(16.bp).clip(RoundedCornerShape(8.bp)).background(SeedyColors.Surface))
    }
}

/** The library's views and Favorites' groups: 52 px tabs, the current one brighter; light with dark text when focused. */
@Composable
fun TvTab(label: String, current: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: String? = null) {
    TvPressable(onClick, modifier) { focused ->
        Row(
            Modifier.focusScale(focused, 1.04f).height(52.bp).clip(RoundedCornerShape(15.bp))
                .background(if (focused) SeedyColors.Inverse else if (current) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.07f))
                .padding(horizontal = 22.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp),
        ) {
            val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
            SText(label, size = 19.bsp, weight = if (current || focused) 800 else 600, color = ink, maxLines = 1)
            if (count != null) SText(count, size = 16.bsp, weight = 600, color = if (focused) Color(0xFF5A5A5A) else SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** The library's 56 px tools (sort, filters, shuffle); [badge] counts the filters that are on. */
@Composable
fun TvToolButton(icon: IconDef, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    TvPressable(onClick, modifier) { focused ->
        Row(
            Modifier.focusScale(focused, 1.04f).height(56.bp).clip(RoundedCornerShape(16.bp))
                .background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f))
                .padding(start = 22.bp, end = if (badge > 0) 16.bp else 22.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp),
        ) {
            val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
            SIcon(icon, size = 22.bp, color = ink)
            SText(label, size = 19.bsp, weight = 700, color = ink, maxLines = 1)
            if (badge > 0) {
                Box(Modifier.height(28.bp).background(LocalAccent.current, CircleShape).padding(horizontal = 8.bp), contentAlignment = Alignment.Center) {
                    SText("$badge", size = 15.bsp, weight = 800, color = SeedyColors.InkInverse)
                }
            }
        }
    }
}

/**
 * A panel at the right of the screen over a dimmed page (sort and filter choices): the remote lands on its first
 * choice, Back closes it.
 */
@Composable
fun TvSidePanel(title: String, onDismiss: () -> Unit, width: Dp = 620.bp, content: @Composable ColumnScope.(first: FocusRequester) -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(Color(0xC7050505))) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(width).shadow(30.bp).background(Color(0xFF141414))
                .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
                .verticalScroll(rememberScrollState()).padding(horizontal = 48.bp, vertical = 64.bp),
            verticalArrangement = Arrangement.spacedBy(10.bp),
        ) {
            SText(title, Modifier.padding(bottom = 18.bp), size = 34.bsp, weight = 900, color = SeedyColors.White)
            content(first)
        }
    }
    LaunchedEffect(Unit) { first.focusSoon() }
}

/** One choice in a [TvSidePanel]: a check when it's on. */
@Composable
fun TvChoice(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null) {
    TvPressable(onClick, modifier) { focused ->
        Row(
            Modifier.fillMaxWidth().height(if (sub != null) 76.bp else 64.bp).focusRing(focused, radius = 18.bp, gap = 3.bp, ring = 4.bp, gapColor = Color(0xFF141414))
                .clip(RoundedCornerShape(18.bp)).background(if (focused) SeedyColors.Inverse else Color.Transparent).padding(horizontal = 22.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(label, size = 21.bsp, weight = if (on || focused) 800 else 600, color = ink, maxLines = 1)
                if (sub != null) SText(sub, size = 16.bsp, color = if (focused) Color(0xFF5A5A5A) else SeedyColors.Text3, maxLines = 1)
            }
            if (on) SIcon(WebIcons.Check, size = 24.bp, color = if (focused) SeedyColors.InkInverse else LocalAccent.current, stroke = 2.8f)
        }
    }
}

/** A panel's group label (12 px-style capitals, as the phone's sheets). */
@Composable
fun TvPanelLabel(text: String) {
    SText(text, Modifier.padding(top = 22.bp, bottom = 4.bp), size = 16.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
}

/** Message for an empty or failed page. */
@Composable
fun TvMessage(title: String, text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 80.bp), verticalArrangement = Arrangement.spacedBy(12.bp)) {
        SText(title, size = 30.bsp, weight = 900, color = SeedyColors.White)
        SText(text, size = 21.bsp, color = SeedyColors.Text2)
    }
}

/** Focuses [requester] once [ready]. */
@Composable
fun FocusWhen(ready: Boolean, requester: FocusRequester) {
    LaunchedEffect(ready) { if (ready) requester.focusSoon() }
}

/**
 * Room for the focus ring around a row that clips (a lazy row): the row is [room] bigger on every side than the place
 * it takes, so give it contentPadding of [room] and its cards sit exactly where the board has them.
 */
fun Modifier.ringRoom(room: Dp): Modifier = this.layout { measurable, c ->
    val r = room.roundToPx()
    val p = measurable.measure(
        c.copy(
            minWidth = c.minWidth + 2 * r, maxWidth = if (c.hasBoundedWidth) c.maxWidth + 2 * r else c.maxWidth,
            minHeight = c.minHeight + 2 * r, maxHeight = if (c.hasBoundedHeight) c.maxHeight + 2 * r else c.maxHeight,
        ),
    )
    layout(p.width - 2 * r, p.height - 2 * r) { p.place(-r, -r) }
}

/** Attaches [requester] when there is one. */
fun Modifier.maybeFocus(requester: FocusRequester?): Modifier = if (requester != null) focusRequester(requester) else this
