package io.github.arkmind.seedy.mobile.requests

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import kotlin.math.roundToInt
import io.github.arkmind.seedy.core.mediarequests.MediaFormat
import io.github.arkmind.seedy.ui.components.CoverPalettes
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.parseHexColor

/*
 * The request pages' covers (web: media-parts.tsx AlbumArt, BookArt): the image when there is one, laid over the
 * boards' drawn art, which shows while it loads and stays when there's none. The art is picked by the web's string hash
 * of the album's, artist's or book's id, so a title gets the same cover as on the web.
 */

private val AlbumPaths = listOf(
    "M0 140 Q50 110 100 140 T200 140 V200 H0Z", "M0 200 L200 70 V200Z", "M0 150 L50 110 L90 140 L140 90 L200 130 V200 H0Z", "M0 0 H200 V40 Q100 70 0 40Z",
).map { PathParser().parsePathString(it).toPath() }

/** The book boards' eight palettes: background, sun, hill and ink. */
private val BookPalettes: List<List<Color>> = listOf(
    listOf("#e8dcc8", "#c9583e", "#2c3a49", "#1e1e1e"), listOf("#1f3a3c", "#a8e6df", "#0f1c1d", "#def4f1"), listOf("#2f2840", "#e3c4ff", "#16121f", "#efe6fb"),
    listOf("#f2cf86", "#1d170f", "#5c4a2c", "#1d170f"), listOf("#3d2424", "#f0b9a0", "#1c1111", "#f6e3da"), listOf("#dfe7ef", "#2f4a7a", "#9fb2c6", "#141b22"),
    listOf("#26362b", "#d9e3b5", "#121a14", "#eef2e0"), listOf("#141414", "#ff9fff", "#2a2a2a", "#f0f0f0"),
).map { p -> p.map { parseHexColor(it)!! } }

private val BookPaths = listOf(
    "M0 300 L0 210 Q100 170 200 210 V300Z", "M0 300 L200 160 V300Z", "M0 230 L50 190 L100 220 L150 170 L200 200 V300 H0Z", "M0 300 V240 H200 V300Z",
).map { PathParser().parsePathString(it).toPath() }

/**
 * Text set in CSS line boxes ([lineHeight] × size each, the glyphs centred in them): Compose keeps the font's whole
 * ascent above the first line and descent under the last, so the block loses the difference to the CSS box, half at
 * the top and half at the foot. The covers' tight titles then stack and sit as on the boards, also over several lines.
 */
@Composable
private fun CssText(text: String, modifier: Modifier, size: TextUnit, weight: Int, color: Color, lineHeight: Float, tracking: Float = 0f, maxLines: Int = 3) {
    BasicText(
        text.uppercase(),
        modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val cut = ((NotoLineRatio - lineHeight) * size.toPx()).coerceAtLeast(0f)
            layout(placeable.width, (placeable.height - cut).roundToInt().coerceAtLeast(0)) { placeable.place(0, -(cut / 2).roundToInt()) }
        },
        style = BaseText.copy(
            fontSize = size, fontWeight = FontWeight(weight), color = color, lineHeight = lineHeight.em,
            letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.em,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** The web's hash of [seed], the index the art is drawn from. */
fun artIndex(seed: String): Long = MediaFormat.hash(seed)

/** An image over the art; the art stays when there is none or it doesn't load. */
@Composable
private fun CoverImage(url: String?, modifier: Modifier = Modifier) {
    var failed by remember(url) { mutableStateOf(false) }
    if (url != null && !failed) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier, onError = { failed = true })
    }
}

/**
 * Square album art: the Cover Art Archive's image, or the boards' drawn cover with [text] on it (the title, uppercase,
 * 8% in from the sides and the bottom). [round] draws an artist: the plain background with [text] as initials.
 */
@Composable
fun RequestAlbumArt(
    index: Long,
    url: String?,
    modifier: Modifier = Modifier,
    text: String? = null,
    textSize: TextUnit = 12.sp,
    round: Boolean = false,
    initialsSize: TextUnit = 22.sp,
) {
    val c = CoverPalettes[(index % CoverPalettes.size).toInt()]
    val s = (index % 4).toInt()
    BoxWithConstraints(modifier.background(c[0])) {
        Canvas(Modifier.fillMaxSize()) {
            // viewBox 0 0 200 200, preserveAspectRatio xMidYMid slice.
            val k = maxOf(size.width, size.height) / 200f
            translate((size.width - 200f * k) / 2f, (size.height - 200f * k) / 2f) {
                scale(k, k, pivot = Offset.Zero) {
                    val by = when (s) { 1 -> 120f; 3 -> 0f; else -> 200f }
                    val bh = when (s) { 1 -> 80f; 3 -> 30f; else -> 0f }
                    if (bh > 0f) drawRect(c[1], topLeft = Offset(0f, by), size = Size(200f, bh))
                    if (!round) {
                        val center = Offset(listOf(70f, 130f, 100f, 60f)[s], listOf(80f, 70f, 95f, 120f)[s])
                        drawCircle(c[2], radius = listOf(46f, 30f, 58f, 22f)[s], center = center)
                        drawCircle(c[3].copy(alpha = listOf(0.5f, 0.35f, 0.6f, 0.4f)[s]), radius = listOf(70f, 52f, 82f, 40f)[s], center = center, style = Stroke(width = listOf(3f, 8f, 2f, 6f)[s]))
                        drawPath(AlbumPaths[((index + 1) % 4).toInt()], c[1].copy(alpha = listOf(0.9f, 0.7f, 0.85f, 0.6f)[s]))
                    }
                }
            }
        }
        if (!text.isNullOrEmpty()) {
            if (round) {
                SText(text, Modifier.align(Alignment.Center), size = initialsSize, weight = 900, color = c[3], align = TextAlign.Center, maxLines = 1)
            } else {
                CssText(
                    text, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = maxWidth * 0.08f, end = maxWidth * 0.08f, bottom = maxHeight * 0.08f),
                    size = textSize, weight = 900, color = c[3], lineHeight = 0.95f,
                )
            }
        }
        CoverImage(url, Modifier.matchParentSize())
    }
}

/**
 * A 2:3 book cover: the image, or the boards' drawn cover with the title at the top and the author at the foot. [spine]
 * shades the left edge the boards' way (inset shadows: under the drawing, over the background).
 */
@Composable
fun RequestBookArt(
    index: Long,
    url: String?,
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
    titleSize: TextUnit = 11.sp,
    authorSize: TextUnit = 6.sp,
    spine: Boolean = false,
) {
    val c = BookPalettes[(index % BookPalettes.size).toInt()]
    val s = (index % 4).toInt()
    BoxWithConstraints(modifier.background(c[0])) {
        Canvas(Modifier.fillMaxSize()) {
            if (spine) {
                // inset 5px 0 0 rgba(0,0,0,.22), inset 6px 0 0 rgba(255,255,255,.06)
                drawRect(Color.White.copy(alpha = 0.06f), size = Size(6.dp.toPx(), size.height))
                drawRect(Color.Black.copy(alpha = 0.22f), size = Size(5.dp.toPx(), size.height))
            }
            // viewBox 0 0 200 300, preserveAspectRatio xMidYMid slice.
            val k = maxOf(size.width / 200f, size.height / 300f)
            translate((size.width - 200f * k) / 2f, (size.height - 300f * k) / 2f) {
                scale(k, k, pivot = Offset.Zero) {
                    drawCircle(c[1], radius = listOf(44f, 60f, 36f, 50f)[s], center = Offset(listOf(140f, 60f, 100f, 150f)[s], listOf(150f, 170f, 130f, 120f)[s]))
                    drawPath(BookPaths[s], c[2])
                }
            }
        }
        if (titleSize.value > 0f) {
            CssText(
                title, Modifier.align(Alignment.TopStart).fillMaxWidth().padding(start = maxWidth * 0.11f, end = maxWidth * 0.11f, top = maxHeight * 0.09f),
                size = titleSize, weight = 900, color = c[3], lineHeight = 1f, tracking = -0.01f, maxLines = 4,
            )
            if (!author.isNullOrEmpty()) {
                SText(
                    author, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = maxWidth * 0.11f, end = maxWidth * 0.11f, bottom = maxHeight * 0.07f).alpha(0.85f),
                    size = authorSize, weight = 800, tracking = 0.12f, uppercase = true, color = c[3], maxLines = 1,
                )
            }
        }
        CoverImage(url, Modifier.matchParentSize())
    }
}

/**
 * The summary card's small covers: the image over the boards' plain gradient (160°: an album's band to its background,
 * a book's background to its hill).
 */
@Composable
fun RequestThumbGradient(index: Long, url: String?, book: Boolean, modifier: Modifier) {
    val (from, to) = if (book) BookPalettes[(index % BookPalettes.size).toInt()].let { it[0] to it[2] }
    else CoverPalettes[(index % CoverPalettes.size).toInt()].let { it[1] to it[0] }
    Box(
        modifier.drawBehind {
            // CSS linear-gradient(160deg): the line runs from the top-left-ish corner down, 20° off vertical.
            val a = Math.toRadians(160.0)
            val dx = kotlin.math.sin(a).toFloat()
            val dy = -kotlin.math.cos(a).toFloat()
            val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawRect(Brush.linearGradient(listOf(from, to), start = c - Offset(dx, dy) * half, end = c + Offset(dx, dy) * half))
        },
    ) { CoverImage(url, Modifier.matchParentSize()) }
}

/** The badges' colours (web media.css .reqs-type). */
fun typeColour(key: String): Color = when (key) {
    "album", "ebook" -> SeedyColors.TypeMovie
    "ep", "audio" -> SeedyColors.TypeSeries
    "single", "comic" -> Color(0xFFA3471F)
    "both" -> Color(0xFF1F5A4D)
    else -> SeedyColors.TypeUpcoming
}

/** A card state's dot: on the server, asked for (or waiting), downloading. */
@Composable
fun stateColour(key: String): Color = when (key) {
    "ok" -> SeedyColors.Ok
    "accent" -> LocalAccent.current
    else -> SeedyColors.Pending
}

/** The 9 px uppercase badge on covers ("Album", "Ebook + audio"). */
@Composable
fun TypeBadge(text: String, key: String, modifier: Modifier = Modifier) {
    SText(
        text, modifier.background(typeColour(key), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
        size = 9.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.White, maxLines = 1,
    )
}

/** The 20 px dot on covers for where a title is (requested, downloading, on the server). */
@Composable
fun StateDot(colour: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(20.dp).background(SeedyColors.Scrim, CircleShape), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).background(colour, CircleShape))
    }
}
