package io.github.arkmind.seedy.mobile.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.format.monthYear
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.photos.FolderInfo
import io.github.arkmind.seedy.core.photos.Memory
import io.github.arkmind.seedy.core.photos.PhotoMonth
import io.github.arkmind.seedy.core.photos.PhotosApi
import io.github.arkmind.seedy.core.photos.countsLabel
import io.github.arkmind.seedy.core.photos.dayLabel
import io.github.arkmind.seedy.core.photos.daySub
import io.github.arkmind.seedy.core.photos.isVideo
import io.github.arkmind.seedy.core.photos.monthTitle
import io.github.arkmind.seedy.core.photos.ratioOf
import io.github.arkmind.seedy.core.photos.yearsAgo
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import kotlin.math.roundToInt

/*
 * Pieces the phone's photos pages share (built from the web's Photos boards in the phone kit): the boards' icons,
 * photo tiles in justified rows (every tile in a row as tall as the others, as on the Timeline board), day and month
 * heads, "On this day" cards and folder cards.
 */

/** Image addresses for the photos pages (the route provides them; screenshot tests draw the boards' art instead). */
val LocalPhotosApi = staticCompositionLocalOf<PhotosApi?> { null }

/** Draws a photo in place of its image (screenshot tests: the boards' drawn landscapes). */
val LocalPhotoArt = staticCompositionLocalOf<(@Composable (BaseItemDto, Modifier) -> Unit)?> { null }

object PhotoIcons {
    val Back = IconDef(2.2f, "M15 5l-7 7 7 7")
    val Slideshow = IconDef(
        2.2f,
        listOf(IconPart("M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z"), IconPart("M10 9.5v5l4-2.5z", filled = true)),
    )
    val Pause = IconDef(
        0f,
        listOf(
            IconPart("M7.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2V5.7a1.2 1.2 0 0 1 1.2-1.2z", filled = true),
            IconPart("M15.2 4.5h1.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1-1.2 1.2h-1.6a1.2 1.2 0 0 1-1.2-1.2V5.7a1.2 1.2 0 0 1 1.2-1.2z", filled = true),
        ),
    )
    val More = IconDef(
        0f,
        listOf(
            IconPart("M3.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M16.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
        ),
    )
    val Heart = IconDef(2.2f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val HeartFilled = IconDef(2.2f, listOf(IconPart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", filled = true)))
    val Folder = IconDef(2.2f, "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z")
    val Shuffle = IconDef(2.2f, "M16 3h5v5", "M4 20L21 3", "M21 16v5h-5", "M15 15l6 6", "M4 4l5 5")
    val Download = IconDef(2.2f, "M12 3v12", "M7 10l5 5 5-5", "M5 20h14")
    val ChevronDown = IconDef(2.2f, "M6 9l6 6 6-6")
    val ChevronRight = IconDef(2.4f, "M9 6l6 6-6 6")
    val Play = IconDef(0f, listOf(IconPart("M8 5v14l11-7z", filled = true)))
    val Close = IconDef(2.2f, "M6 6l12 12", "M18 6L6 18")
    val Rotate = IconDef(2.2f, "M20 11a8 8 0 1 0-2.3 5.7", "M20 4v7h-7")
    val Info = IconDef(2.2f, "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z", "M12 11v5", "M12 8h.01")
    val Calendar = IconDef(2f, "M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z", "M4 10h16M9 3v4M15 3v4")
    val Camera = IconDef(2f, "M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M12 10a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z")
    val File = IconDef(2f, "M6 3h8l4 4v14H6z", "M14 3v4h4")
    val Video = IconDef(2f, "M5 6h9a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M16 10.5l5-3v9l-5-3z")
    val Grid = IconDef(
        2.2f,
        "M5.5 4h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M15 4h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M5.5 13.5h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M15 13.5h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
    )
    val Refresh = IconDef(2f, "M19.5 12a7.5 7.5 0 1 1-2.3-5.4", "M19.5 4v3.5H16")
    val Sort = IconDef(2f, "M7 4v16", "M3 16l4 4 4-4", "M12 6h9", "M12 11h6", "M12 16h3")
}

/* ───────── Images ───────── */

/** A photo filling [modifier]'s box (cropped), at about [heightDp] tall. */
@Composable
fun PhotoImage(item: BaseItemDto, heightDp: Dp, modifier: Modifier = Modifier) {
    val art = LocalPhotoArt.current
    if (art != null) {
        art(item, modifier)
        return
    }
    val px = with(LocalDensity.current) { heightDp.roundToPx() }
    Artwork(LocalPhotosApi.current?.thumbUrl(item, (px * 1.25f).roundToInt()), modifier, placeholder = SeedyColors.Row)
}

/* ───────── Justified rows ───────── */

/** One line of tiles and its height. */
data class TileRow(val items: List<BaseItemDto>, val height: Float, val first: Int)

/**
 * Packs photos into rows like the boards' flex rows: a tile's basis is its ratio × [row]; a row takes tiles while they
 * fit, then grows so it fills the [width] (every tile the same height); the last row grows only a little (the boards'
 * spacer takes most of what's left).
 */
fun justify(items: List<BaseItemDto>, width: Float, row: Float, gap: Float, offset: Int = 0): List<TileRow> {
    val out = mutableListOf<TileRow>()
    var start = 0
    var sum = 0f
    fun close(end: Int, last: Boolean) {
        val n = end - start
        if (n <= 0) return
        val free = width - sum * row - gap * (n - 1) - (if (last) gap else 0f)
        val h = if (last) row + free.coerceAtLeast(0f) / (sum + 60f) else row + free / sum
        out += TileRow(items.subList(start, end), h, offset + start)
    }
    items.forEachIndexed { i, it ->
        val r = ratioOf(it)
        val n = i - start
        if (n > 0 && sum * row + gap * n + r * row > width) {
            close(i, last = false)
            start = i
            sum = 0f
        }
        sum += r
    }
    close(items.size, last = true)
    return out
}

@Composable
fun TileLine(row: TileRow, gap: Dp, onOpen: (BaseItemDto) -> Unit, modifier: Modifier = Modifier) {
    val h = with(LocalDensity.current) { row.height.toDp() }
    Row(modifier.fillMaxWidth().height(h), horizontalArrangement = Arrangement.spacedBy(gap)) {
        for (it in row.items) Tile(it, Modifier.width(h * ratioOf(it)).height(h), h) { onOpen(it) }
    }
}

@Composable
fun Tile(item: BaseItemDto, modifier: Modifier, height: Dp, onClick: () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(SeedyColors.Row).tap(onClick = onClick)) {
        PhotoImage(item, height, Modifier.fillMaxSize())
        if (item.isVideo) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xC70E0E0E)).padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SIcon(PhotoIcons.Play, size = 10.dp, color = SeedyColors.White)
                SText(clockOf(ticksToSeconds(item.runTimeTicks)), size = 11.sp, weight = 700, color = SeedyColors.White)
            }
        }
        if (item.userData?.isFavorite == true) {
            SIcon(PhotoIcons.HeartFilled, Modifier.align(Alignment.BottomStart).padding(6.dp).shadow(0.dp), size = 14.dp, color = SeedyColors.White)
        }
    }
}

/** "0:42", "2:10", "1:04:09". */
fun clockOf(secs: Double): String {
    val s = secs.roundToInt().coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

/* ───────── The timeline as list entries ───────── */

sealed interface Line {
    val key: String
    data class Month(val title: String, val first: Boolean, override val key: String) : Line
    data class Day(val title: String, val sub: String, override val key: String) : Line
    data class Tiles(val row: TileRow, override val key: String) : Line
}

/** Months, days and their rows, for a lazy list ([headings] false: days only, a folder of one month). */
fun timelineLines(months: List<PhotoMonth>, width: Float, row: Float, gap: Float, own: String? = null, headings: Boolean = true): List<Line> = buildList {
    months.forEachIndexed { mi, m ->
        if (headings) add(Line.Month(monthTitle(m.year, m.month), mi == 0, "m${m.year}-${m.month}"))
        for (d in m.days) {
            add(Line.Day(dayLabel(d.date), daySub(d, own), "d${d.date}"))
            justify(d.items, width, row, gap, d.offset).forEachIndexed { i, r -> add(Line.Tiles(r, "r${d.date}-$i-${r.first}")) }
        }
    }
}

@Composable
fun MonthHead(title: String, first: Boolean) {
    SText(title, Modifier.padding(start = 20.dp, end = 20.dp, top = if (first) 22.dp else 34.dp), size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
}

@Composable
fun DayHead(title: String, sub: String) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(title, Modifier.alignByBaseline(), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        SText(sub, Modifier.alignByBaseline().weight(1f), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
    }
}

/* ───────── Chips, buttons ───────── */

/** A 36 px chip with 12 px corners (the phone's views). */
@Composable
fun PhotoChip(label: String, on: Boolean, icon: IconDef? = null, count: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) SIcon(icon, size = 15.dp, color = if (on) SeedyColors.InkInverse else SeedyColors.Text)
        SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
        if (count != null) SText(count, size = 13.sp, weight = 700, color = if (on) Color(0xFF6A6A6A) else SeedyColors.Text3, maxLines = 1)
    }
}

/** The boards' 44 px buttons: light ([primary]) or dark, an icon and a label. */
@Composable
fun PhotoButton(icon: IconDef, label: String?, primary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp).clip(RoundedCornerShape(13.dp)).background(if (primary) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(onClick = onClick).padding(horizontal = if (label == null) 12.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        SIcon(icon, size = 18.dp, color = if (primary) SeedyColors.InkInverse else SeedyColors.White)
        if (label != null) SText(label, size = 14.sp, weight = if (primary) 800 else 700, color = if (primary) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
    }
}

/* ───────── On this day ───────── */

@Composable
fun MemoryCard(memory: Memory, onClick: () -> Unit) {
    val cover = memory.items.firstOrNull { (it.height ?: 0) >= (it.width ?: 0) } ?: memory.items.first()
    val photos = memory.items.count { !it.isVideo }
    Box(Modifier.width(150.dp).aspectRatio(0.8f).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Row).tap(onClick = onClick)) {
        PhotoImage(cover, 190.dp, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color(0x000A0A0A), 1f to Color(0xD90A0A0A))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(yearsAgo(memory.years), size = 10.sp, weight = 800, tracking = 0.1f, uppercase = true, color = LocalAccent.current, maxLines = 1)
            SText(memory.title, size = 15.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
            SText("${countsLabel(photos, memory.items.size - photos)} · ${monthYear(memory.date)}", size = 11.sp, color = SeedyColors.TextHero, maxLines = 1)
        }
    }
}

/* ───────── Folders ───────── */

/** Three photos in the folder page's mosaic (one when there are fewer), with [radius] corners. */
@Composable
fun Mosaic(covers: List<BaseItemDto>, modifier: Modifier, radius: Dp, big: Dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(SeedyColors.Row)) {
        when {
            covers.size >= 3 -> Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                PhotoImage(covers[0], big, Modifier.weight(2f).fillMaxSize())
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PhotoImage(covers[1], big / 2, Modifier.weight(1f).fillMaxWidth())
                    PhotoImage(covers[2], big / 2, Modifier.weight(1f).fillMaxWidth())
                }
            }
            covers.isNotEmpty() -> PhotoImage(covers[0], big, Modifier.fillMaxSize())
            else -> SIcon(PhotoIcons.Folder, Modifier.align(Alignment.Center), size = 30.dp, color = SeedyColors.Text3)
        }
    }
}

@Composable
fun FolderCard(item: BaseItemDto, info: FolderInfo?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Mosaic(info?.covers.orEmpty(), Modifier.fillMaxWidth().aspectRatio(4f / 3f), 14.dp, 130.dp)
        Spacer(Modifier.height(5.dp))
        SText(item.name.orEmpty(), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        SText(
            info?.let { listOfNotNull(countsLabel(it.photos, it.videos), it.to?.let(::monthYear)).joinToString(" · ") } ?: " ",
            size = 12.sp, color = SeedyColors.Text3, maxLines = 1,
        )
    }
}

/** A thin accent ring (the viewer's current thumbnail). */
fun Modifier.ring(on: Boolean): Modifier = if (on) this.border(2.dp, SeedyColors.White, RoundedCornerShape(8.dp)) else this

@Composable
fun EmptyNote(title: String, text: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SText(title, size = 17.sp, weight = 800, color = SeedyColors.White, align = androidx.compose.ui.text.style.TextAlign.Center)
        if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2, align = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) {
            Box(Modifier.padding(top = 8.dp).height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(onClick = onAction).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                SText(action, size = 13.sp, weight = 700, color = SeedyColors.White)
            }
        }
    }
}

/** A placeholder block while a page loads. */
@Composable
fun Skeleton(modifier: Modifier) = Box(modifier.clip(RoundedCornerShape(8.dp)).background(SeedyColors.Surface))

internal fun noCamera() = t("No camera details")
