package io.github.arkmind.seedy.mobile.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.books.AuthorGroup
import io.github.arkmind.seedy.core.books.ContinueEntry
import io.github.arkmind.seedy.core.books.ContinueKind
import io.github.arkmind.seedy.core.books.SeriesGroup
import io.github.arkmind.seedy.core.books.audioLength
import io.github.arkmind.seedy.core.books.authorCounts
import io.github.arkmind.seedy.core.books.seriesFan
import io.github.arkmind.seedy.core.books.sortItems
import io.github.arkmind.seedy.core.books.BookSort
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

/* Pieces of the books screens (boards "Android · Books" and "Android · Book details"): section heads, cards and rows. */

internal val Dim = Color(0xFF6A6A6A)

/** "Recently added ……… See all": a 17 px title and a link on its baseline. */
@Composable
fun SectionHead(title: String, modifier: Modifier = Modifier, link: String? = null, top: Dp = 22.dp, onLink: () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = top, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SText(title, Modifier.weight(1f).alignByBaseline(), size = 17.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        if (link != null) SText(link, Modifier.alignByBaseline().tap(onClick = onLink), size = 13.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** A 36 px chip with 12 px corners (the books views). */
@Composable
fun BooksChip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(onClick = onClick).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        SText(label, size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
    }
}

/** A 4 px progress line: white on the dark track. */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier, track: Color = SeedyColors.Surface2) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(SeedyColors.White))
    }
}

/**
 * A Continue card (286 px): the cover (an audiobook's square with headphones on it), the kind in capitals — the accent
 * while it plays here — the title, where in it, the progress and the foot.
 */
@Composable
fun ContinueCard(entry: ContinueEntry, live: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(
        Modifier.width(286.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel).tap(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (entry.kind == ContinueKind.Audio) {
            Box(Modifier.size(80.dp).clip(RoundedCornerShape(10.dp))) {
                ItemAudioCover(entry.item, Modifier.matchParentSize(), width = 200)
                Box(
                    Modifier.align(Alignment.BottomEnd).padding(5.dp).size(24.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xD90E0E0E)),
                    contentAlignment = Alignment.Center,
                ) { SIcon(BookIcons.Headphones, size = 14.dp, color = SeedyColors.White) }
            }
        } else {
            ItemBookCover(entry.item, Modifier.width(64.dp), titleSize = 9.sp, authorSize = 5.sp, small = 3.dp, big = 7.dp, width = 160)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val kind = when (entry.kind) {
                ContinueKind.Book -> t("Ebook")
                ContinueKind.Comic -> t("Comic")
                ContinueKind.Audio -> if (live) t("Audiobook · listening") else t("Audiobook")
            }
            SText(kind, size = 11.sp, weight = 800, tracking = 0.06f, uppercase = true, color = if (live) accent else SeedyColors.Text2, maxLines = 1)
            SText(entry.item.name.orEmpty(), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(entry.where.orEmpty(), size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
            ProgressLine(entry.fraction, Modifier.padding(top = 4.dp).fillMaxWidth())
            SText(entry.foot, size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** Covers stacked 14 px over each other (a series' or an author's row). */
@Composable
private fun CoverStack(items: List<BaseItemDto>) {
    val n = items.size
    Box(Modifier.width(if (n == 0) 34.dp else 34.dp + 20.dp * (n - 1))) {
        items.forEachIndexed { i, it ->
            ItemBookCover(it, Modifier.offset(x = 20.dp * i).width(34.dp), titleSize = 0.sp, authorSize = 0.sp, small = 2.dp, big = 5.dp, width = 120)
        }
    }
}

/** A row on the dark panel: [art], a name, a line under it, an optional [extra] and a chevron. */
@Composable
private fun PanelRow(onClick: () -> Unit, art: @Composable () -> Unit, name: String, sub: String, extra: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel).tap(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        art()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(name, size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
            extra?.invoke()
        }
        SIcon(BookIcons.Chevron, size = 16.dp, color = Dim)
    }
}

/** A series: three covers stacked, its name, "Mara Linde · 3 of 5 read" and a dash per volume (read ones in the accent). */
@Composable
fun SeriesRow(group: SeriesGroup, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val sub = listOf(group.author, t("{read} of {total} read", "read" to group.read, "total" to group.volumes.size)).filter { it.isNotEmpty() }.joinToString(" · ")
    PanelRow(onClick, { CoverStack(seriesFan(group.volumes)) }, group.name, sub) {
        BoxWithConstraints(Modifier.padding(top = 2.dp).fillMaxWidth()) {
            val n = group.volumes.size
            val w = min(14.dp, (maxWidth - 3.dp * (n - 1)) / n.coerceAtLeast(1))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (v in group.volumes) {
                    Box(Modifier.size(w, 4.dp).clip(RoundedCornerShape(2.dp)).background(if (v.userData?.played == true) accent else SeedyColors.Surface2))
                }
            }
        }
    }
}

/** An author: up to three of their covers, their name and "3 books · 1 audiobook". */
@Composable
fun AuthorRow(group: AuthorGroup, onClick: () -> Unit) {
    val covers = sortItems(group.books, BookSort.Released, emptyMap()).take(3)
    PanelRow(
        onClick,
        {
            if (covers.isEmpty() && group.audio.isNotEmpty()) {
                ItemAudioCover(group.audio.first(), Modifier.size(51.dp).clip(RoundedCornerShape(8.dp)), width = 120)
            } else {
                CoverStack(covers)
            }
        },
        group.name.ifEmpty { t("Unknown author") }, authorCounts(group),
    )
}

/**
 * A book in a grid: the cover, its title and an optional line under it ([subColor]); [read] puts a tick on the cover and
 * dims the title.
 */
@Composable
fun BookGridCard(item: BaseItemDto, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, subColor: Color = SeedyColors.Text3, read: Boolean = false, current: Boolean = false) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            ItemBookCover(item, Modifier.fillMaxWidth())
            if (read) {
                Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Color(0xD90E0E0E)), contentAlignment = Alignment.Center) {
                    SIcon(BookIcons.Check, size = 13.dp, color = SeedyColors.White, stroke = 3f)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(item.name.orEmpty(), size = 12.sp, weight = 700, color = if (read) SeedyColors.Text2 else if (current) LocalAccent.current else SeedyColors.White, maxLines = 1)
            if (!sub.isNullOrEmpty()) SText(sub, size = 11.sp, color = subColor, maxLines = 1)
        }
    }
}

/** An audiobook in a grid: the square art, its title and its length. */
@Composable
fun AudioGridCard(item: BaseItemDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ItemAudioCover(item, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)), titleSize = 13.sp)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(item.name.orEmpty(), size = 12.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val len = audioLength(item)
            if (len.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    SIcon(BookIcons.Headphones, size = 12.dp, color = SeedyColors.Text3)
                    SText(len, size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
        }
    }
}

/** [items] in rows of [columns], [gap] apart across and [rowGap] down. */
@Composable
fun <T> GridRows(items: List<T>, columns: Int, modifier: Modifier = Modifier, gap: Dp = 12.dp, rowGap: Dp = 14.dp, cell: @Composable (T) -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(rowGap)) {
        for (row in items.chunked(columns)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (it in row) Box(Modifier.weight(1f)) { cell(it) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A message in the page: a title and a line under it, and an optional button. */
@Composable
fun BooksEmpty(title: String, text: String? = null, action: String? = null, onAction: () -> Unit = {}) {
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
