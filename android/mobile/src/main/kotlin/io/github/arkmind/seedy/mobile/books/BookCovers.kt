package io.github.arkmind.seedy.mobile.books

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.audio.authorLine
import io.github.arkmind.seedy.ui.components.AudioCover
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.coverSeed
import io.github.arkmind.seedy.ui.components.coverUrl
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.parseHexColor
import org.jellyfin.sdk.model.api.BaseItemDto

/*
 * Book covers (web books/covers.tsx, boards "Android · Books" and "Android · Book details"). A book is a 2:3 cover with
 * a shaded spine down its left edge and a drop shadow; an audiobook is a square. Titles without cover art get one drawn
 * in the boards' style: the title and author set on a flat colour with a disc and a hill, picked from the title and
 * author so it stays put (the same as on the web).
 */

/** The boards' eight book palettes: background, disc, hill and ink. */
private val BookPalettes: List<List<Color>> = listOf(
    listOf("#e8dcc8", "#c9583e", "#2c3a49", "#1e1e1e"), listOf("#1f3a3c", "#a8e6df", "#0f1c1d", "#def4f1"), listOf("#2f2840", "#e3c4ff", "#16121f", "#efe6fb"),
    listOf("#f2cf86", "#1d170f", "#5c4a2c", "#1d170f"), listOf("#3d2424", "#f0b9a0", "#1c1111", "#f6e3da"), listOf("#dfe7ef", "#2f4a7a", "#9fb2c6", "#141b22"),
    listOf("#26362b", "#d9e3b5", "#121a14", "#eef2e0"), listOf("#141414", "#ff9fff", "#2a2a2a", "#f0f0f0"),
).map { p -> p.map { parseHexColor(it)!! } }

private val BookPaths = listOf(
    "M0 300 L0 210 Q100 170 200 210 V300Z", "M0 300 L200 160 V300Z", "M0 230 L50 190 L100 220 L150 170 L200 200 V300 H0Z", "M0 300 V240 H200 V300Z",
).map { PathParser().parsePathString(it).toPath() }

/** The drawn cover's palette for a title (the hill colour tints the book page's top). */
fun bookPalette(title: String, author: String): List<Color> = BookPalettes[coverSeed("$title|$author") % BookPalettes.size]

/** A book's picture: its own, or (a file in a book folder) its parent's. */
@Composable
fun bookArt(item: BaseItemDto?, width: Int = 320): String? = coverUrl(item, LocalViewer.current.images, width)

/**
 * A book cover: [url]'s picture, or the drawn one with [title] at [titleSize] and [author] at [authorSize] (0: none).
 * [small] and [big] are the left (spine) and right corner radii.
 */
@Composable
fun BookCover(
    url: String?,
    title: String,
    author: String,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 12.sp,
    authorSize: TextUnit = 7.sp,
    small: Dp = 3.dp,
    big: Dp = 8.dp,
    shadow: Boolean = true,
) {
    val seed = coverSeed("$title|$author")
    val i = seed % BookPalettes.size
    val (c0, c1, c2, ink) = BookPalettes[i]
    val s = i % 4
    val shape = RoundedCornerShape(topStart = small, topEnd = big, bottomEnd = big, bottomStart = small)
    var failed by remember(url) { mutableStateOf(false) }
    Box(
        modifier.aspectRatio(2f / 3f)
            .then(if (shadow) Modifier.boxShadow(Color(0x73000000), blur = 26.dp, y = 12.dp, radius = big) else Modifier)
            .clip(shape).background(c0),
    ) {
        if (url == null || failed) {
            Canvas(Modifier.fillMaxSize()) {
                // viewBox 0 0 200 300, preserveAspectRatio xMidYMid slice.
                val k = maxOf(size.width / 200f, size.height / 300f)
                translate((size.width - 200f * k) / 2f, (size.height - 300f * k) / 2f) {
                    scale(k, k, pivot = Offset.Zero) {
                        drawCircle(c1, radius = listOf(44f, 60f, 36f, 50f)[s], center = Offset(listOf(140f, 60f, 100f, 150f)[s], listOf(150f, 170f, 130f, 120f)[s]))
                        drawPath(BookPaths[s], c2)
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val side = maxWidth * 0.11f
                if (titleSize.value > 0f && title.isNotEmpty()) {
                    SText(
                        title, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = side, end = side, top = maxHeight * 0.09f),
                        size = titleSize, weight = 900, lineHeight = 1f, tracking = -0.01f, color = ink, uppercase = true,
                    )
                }
                if (authorSize.value > 0f && author.isNotEmpty()) {
                    SText(
                        author, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = side, end = side, bottom = maxHeight * 0.07f),
                        size = authorSize, weight = 800, tracking = 0.12f, color = ink.copy(alpha = 0.85f), uppercase = true, maxLines = 1,
                    )
                }
            }
        } else {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(), onError = { failed = true })
        }
        // The spine: a dark band and a thin light edge down the left side, over any art.
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color.White.copy(alpha = 0.06f), size = Size(6.dp.toPx(), size.height))
            drawRect(Color.Black.copy(alpha = 0.22f), size = Size(5.dp.toPx(), size.height))
        }
    }
}

/** A library item's book cover (its picture, else drawn from its title and author). */
@Composable
fun ItemBookCover(item: BaseItemDto, modifier: Modifier = Modifier, titleSize: TextUnit = 12.sp, authorSize: TextUnit = 7.sp, small: Dp = 3.dp, big: Dp = 8.dp, width: Int = 320) {
    BookCover(bookArt(item, width), item.name.orEmpty(), authorLine(item), modifier, titleSize, authorSize, small, big)
}

/**
 * An audiobook's square art: its picture, or the drawn cover (from its title and author, as the web's books pages
 * draw it) with the title on it unless [titleSize] is 0.
 */
@Composable
fun ItemAudioCover(item: BaseItemDto, modifier: Modifier = Modifier, titleSize: TextUnit = 0.sp, width: Int = 320) {
    val name = item.name.orEmpty()
    AudioCover(
        bookArt(item, width), "$name|${authorLine(item)}", modifier,
        title = if (titleSize.value > 0f) name.replace(Regex("^A\\s+", RegexOption.IGNORE_CASE), "") else null, titleSize = titleSize,
    )
}

/** Icons the books boards draw (24 px grid). */
object BookIcons {
    val Back = IconDef(2.2f, "M15 5l-7 7 7 7")
    val Search = IconDef(2.2f, "M4 11a7 7 0 1 0 14 0a7 7 0 1 0 -14 0Z", "M20 20l-4-4")
    val More = IconDef(
        0f,
        listOf(
            IconPart("M3.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M10.3 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
            IconPart("M16.8 12a1.7 1.7 0 1 0 3.4 0a1.7 1.7 0 1 0 -3.4 0Z", filled = true),
        ),
    )
    val Download = IconDef(2.2f, "M12 3v12", "M7 10l5 5 5-5", "M5 20h14")
    val Book = IconDef(2.2f, "M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z", "M4 21a2 2 0 0 1 2-2h13v2")
    val Headphones = IconDef(
        2.2f, "M4 15v-3a8 8 0 0 1 16 0v3",
        "M4.5 14h1a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5Z",
        "M18.5 14h1a1.5 1.5 0 0 1 1.5 1.5v3a1.5 1.5 0 0 1-1.5 1.5h-1a1.5 1.5 0 0 1-1.5-1.5v-3a1.5 1.5 0 0 1 1.5-1.5Z",
    )
    /** The format chips' headphones (square ear cups). */
    val HeadphonesSquare = IconDef(2f, "M4 15v-3a8 8 0 0 1 16 0v3", "M3 15h4v5H3zM17 15h4v5h-4z")
    val File = IconDef(2f, "M6 3h8l4 4v14H6z", "M14 3v4h4")
    val Chevron = IconDef(2.2f, "M9 6l6 6-6 6")
    val ChevronDown = IconDef(2.2f, "M6 9l6 6 6-6")
    val Moon = IconDef(2.2f, "M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z")
    val Chapters = IconDef(2.2f, "M9 6h11", "M9 12h11", "M9 18h11", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val Bookmark = IconDef(2.2f, "M6 3h12v18l-6-4-6 4z")
    val BookmarkFilled = IconDef(2.2f, listOf(IconPart("M6 3h12v18l-6-4-6 4z", filled = true)))
    val Volume = IconDef(2.2f, "M4 9v6h4l5 4V5L8 9z", "M16.5 8.5a5 5 0 0 1 0 7", "M19 6a8.5 8.5 0 0 1 0 12")
    val Check = IconDef(2.2f, "M5 12.5l4.5 4.5L19 7.5")
    val Heart = IconDef(2.2f, "M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z")
    val HeartFilled = IconDef(2.2f, listOf(IconPart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", filled = true)))
    val Close = IconDef(2.4f, "M6 6l12 12", "M18 6L6 18")
    val Refresh = IconDef(2f, "M19.5 12a7.5 7.5 0 1 1-2.3-5.4", "M19.5 4v3.5H16")
    val Sort = IconDef(2f, "M7 4v16", "M3 16l4 4 4-4", "M12 6h9", "M12 11h6", "M12 16h3")
    val Stop = IconDef(2.2f, "M7 5h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z")
    val Star = IconDef(0f, listOf(IconPart("M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z", filled = true)))
}
