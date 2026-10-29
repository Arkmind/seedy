package io.github.arkmind.seedy.mobile.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.mediarequests.BookCard
import io.github.arkmind.seedy.core.mediarequests.MediaFormat
import io.github.arkmind.seedy.core.mediarequests.MediaRequest
import io.github.arkmind.seedy.core.mediarequests.MusicArtistRef
import io.github.arkmind.seedy.core.mediarequests.MusicRelease
import io.github.arkmind.seedy.core.mediarequests.RequestService
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.initialsOf
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * The Requests tab's shared parts (boards "Android · Requests", "… Music", "… Books"): the title with Search and Open
 * in…, the Movies & shows · Music · Books tabs, the Discover · My requests switch, the summary card, and the album and
 * book cards.
 */

/** The services the server offers, and the one shown; the tabs only show when there's more than one. */
class ServiceTabs(val services: List<RequestService>, val current: RequestService, val onService: (RequestService) -> Unit)

/** "Requests", Search, and Open in Seerr/Lidarr/Shelfarr when the server says where it is. */
@Composable
fun RequestsTitleRow(onSearch: () -> Unit, onOpen: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SText(t("Requests"), Modifier.weight(1f), size = 24.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
        HeaderButton(WebIcons.Search, onSearch, color = SeedyColors.TextHero, stroke = 2.2f)
        onOpen?.let { open ->
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).tap(onClick = open), contentAlignment = Alignment.Center) {
                SIcon(WebIcons.External, size = 20.dp, color = SeedyColors.TextHero, stroke = 2.2f)
            }
        }
    }
}

private fun serviceLabel(s: RequestService): String = when (s) {
    RequestService.Movies -> t("Movies & shows")
    RequestService.Music -> t("Music")
    RequestService.Books -> t("Books")
}

private fun serviceIcon(s: RequestService): IconDef = when (s) {
    RequestService.Movies -> WebIcons.Film
    RequestService.Music -> WebIcons.Notes
    RequestService.Books -> WebIcons.Book
}

/** The boards' "What to request" tabs: 38 px, 6 px apart, each as wide as its words plus an even share of the rest. */
@Composable
fun ServiceTabsRow(tabs: ServiceTabs, modifier: Modifier = Modifier) {
    FlexRow(gap = 6, modifier = modifier) {
        for (s in tabs.services) {
            val on = s == tabs.current
            val ink = if (on) SeedyColors.InkInverse else SeedyColors.Text
            Row(
                Modifier.height(38.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Row)
                    .tap { if (!on) tabs.onService(s) }.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SIcon(serviceIcon(s), size = 15.dp, color = ink, stroke = 2.2f)
                SText(serviceLabel(s), size = 13.sp, weight = if (on) 800 else 600, color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * CSS `flex: 1 1 auto` in a row: each child gets its natural width plus an even share of what's left (or gives up its
 * share when there isn't enough room).
 */
@Composable
fun FlexRow(gap: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.fillMaxWidth()) { measurables, constraints ->
        val space = gap.dp.roundToPx()
        val n = measurables.size
        if (n == 0) return@Layout layout(constraints.minWidth, 0) {}
        val total = constraints.maxWidth - space * (n - 1)
        val natural = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val sum = natural.sum()
        val widths = if (sum <= total) {
            val extra = total - sum
            natural.mapIndexed { i, w -> w + extra / n + if (i < extra % n) 1 else 0 }
        } else {
            natural.map { (it.toLong() * total / sum).toInt() }
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixedWidth(widths[i]).copy(minHeight = 0, maxHeight = constraints.maxHeight)) }
        val height = placeables.maxOf { it.height }
        layout(constraints.maxWidth, height) {
            var x = 0
            for (p in placeables) {
                p.place(x, (height - p.height) / 2)
                x += p.width + space
            }
        }
    }
}

/** One of the Discover · My requests · All requests tabs; All's count is what waits for approval. */
data class SwitchItem<T>(val id: T, val label: String, val count: Int = 0, val pending: Boolean = false)

/** The board's switch: 40 px tabs on a 4 px #1a1a1a track. */
@Composable
fun <T> RequestsSwitch(items: List<SwitchItem<T>>, current: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp)).background(SeedyColors.Row).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (item in items) {
            val on = item.id == current
            Row(
                Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(11.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap { onSelect(item.id) },
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SText(item.label, size = 14.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
                if (item.count > 0) {
                    Box(
                        Modifier.height(20.dp).widthIn(min = 20.dp)
                            .background(if (item.pending) SeedyColors.Pending else if (on) Color(0xFFDCDCDC) else SeedyColors.Surface2, CircleShape)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { SText("${item.count}", size = 11.sp, weight = 800, color = if (item.pending || on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1) }
                }
            }
        }
    }
}

/** A count on the summary card: how many, and the dot's colour. */
data class SummaryCount(val text: String, val dot: Color)

/**
 * The boards' "2 music requests in progress" card: two of the covers stacked (album squares or books), and how many
 * wait, download, are looked for and just arrived.
 */
@Composable
fun MediaSummaryCard(title: String, requests: List<MediaRequest>, counts: List<SummaryCount>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel).border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp))
            .tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val shown = requests.take(2)
        val music = shown.firstOrNull()?.music ?: true
        // The 2 px ring (a box-shadow on the board) sits outside each cover, over the one before.
        if (music) {
            Box(Modifier.width((36 + 24 * (shown.size - 1).coerceAtLeast(0)).dp).height(36.dp)) {
                shown.forEachIndexed { i, r ->
                    Box(Modifier.offset(x = (24 * i - 2).dp, y = (-2).dp).size(40.dp).background(SeedyColors.Panel, RoundedCornerShape(10.dp)).padding(2.dp)) {
                        RequestThumbArt(r, Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                    }
                }
            }
        } else {
            Box(Modifier.width((28 + 18 * (shown.size - 1).coerceAtLeast(0)).dp).height(42.dp)) {
                shown.forEachIndexed { i, r ->
                    Box(
                        Modifier.offset(x = (18 * i - 2).dp, y = (-2).dp).size(32.dp, 46.dp)
                            .background(SeedyColors.Panel, RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp, topEnd = 8.dp, bottomEnd = 8.dp)).padding(2.dp),
                    ) {
                        RequestThumbArt(r, Modifier.size(28.dp, 42.dp).clip(BookShapeSmall))
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(title, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (c in counts) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(Modifier.size(6.dp).background(c.dot, CircleShape))
                        SText(c.text, size = 11.sp, color = SeedyColors.Text2, maxLines = 1)
                    }
                }
            }
        }
        SIcon(WebIcons.ChevronRight, size = 16.dp, color = SeedyColors.Text2, stroke = 2.2f)
    }
}

private val BookShapeSmall = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 6.dp, bottomEnd = 6.dp)

/** A request's cover: its album's square or its book (web: RequestThumb). */
@Composable
fun RequestThumbArt(r: MediaRequest, modifier: Modifier) {
    if (r.music) RequestAlbumArt(artIndex(r.albums.firstOrNull()?.mbid ?: r.foreignId), r.coverUrl, modifier)
    else RequestBookArt(artIndex(r.foreignId), r.coverUrl, r.title, r.subtitle, modifier, titleSize = 0.sp)
}

/** Board "Android · Requests · Music" card: a 128 px album with its type, where it is, the title and a line. */
@Composable
fun AlbumCard(r: MusicRelease, meta: Pair<String, Boolean>, onClick: () -> Unit, modifier: Modifier = Modifier.width(128.dp)) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            RequestAlbumArt(artIndex(r.mbid), r.coverUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(11.dp)), text = r.title)
            val (type, key) = MediaFormat.releaseType(r)
            TypeBadge(type, key, Modifier.padding(6.dp))
            MediaFormat.cardStatus(r.status, r.requestedBy)?.let { StateDot(stateColour(it.dot), Modifier.align(Alignment.TopEnd).padding(6.dp)) }
        }
        CardText(r.title, meta)
    }
}

/** Board "Android · Requests · Books" card: a 112 px book with its formats, where it is, the title and a line. */
@Composable
fun BookCardView(b: BookCard, cover: String?, meta: Pair<String, Boolean>, onClick: () -> Unit, modifier: Modifier = Modifier.width(112.dp)) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            BookCover(b, cover, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
            val (type, key) = MediaFormat.bookType(b)
            TypeBadge(type, key, Modifier.align(Alignment.BottomStart).padding(6.dp))
            MediaFormat.cardStatus(b.status, b.requestedBy, short = true)?.let { StateDot(stateColour(it.dot), Modifier.align(Alignment.BottomEnd).padding(6.dp)) }
        }
        CardText(b.title, meta)
    }
}

private val BookShape = RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp, topEnd = 9.dp, bottomEnd = 9.dp)

/** A book's cover as the boards set it: the spine's shade down the left, and its shadow under it. */
@Composable
fun BookCover(b: BookCard, cover: String?, modifier: Modifier) {
    Box(modifier.boxShadow(Color.Black.copy(alpha = 0.45f), 26.dp, 12.dp, 9.dp)) {
        RequestBookArt(
            artIndex(b.workId), cover, b.title, b.author,
            Modifier.matchParentSize().clip(BookShape).drawWithContent {
                drawContent()
                // inset 5px 0 0 rgba(0,0,0,.22), inset 6px 0 0 rgba(255,255,255,.06)
                drawRect(Color.White.copy(alpha = 0.06f), size = Size(6.dp.toPx(), size.height))
                drawRect(Color.Black.copy(alpha = 0.22f), topLeft = Offset.Zero, size = Size(5.dp.toPx(), size.height))
            },
        )
    }
}

@Composable
private fun CardText(title: String, meta: Pair<String, Boolean>) {
    Column(Modifier.padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
        SText(title, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
        if (meta.first.isNotEmpty()) SText(meta.first, size = 11.sp, weight = 600, color = if (meta.second) LocalAccent.current else SeedyColors.Text3, maxLines = 1)
    }
}

/** A search's artist: the round picture (or initials), the name, and "Band · Lisbon". */
@Composable
fun ArtistCard(a: MusicArtistRef, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RequestAlbumArt(artIndex(a.mbid), a.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape), text = initialsOf(a.name), round = true, initialsSize = 26.sp)
        Column(Modifier.fillMaxWidth().padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(a.name, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val kind = if (a.type == "Group") t("Band") else t("Artist")
            SText(listOf(kind, a.disambiguation?.takeIf { it.isNotEmpty() } ?: a.area.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · "), size = 11.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** A card while its row loads. */
@Composable
fun CardSkeleton(width: Int, ratio: Float, modifier: Modifier = Modifier.width(width.dp)) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface))
        Box(Modifier.width((width * 0.7f).dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
    }
}
