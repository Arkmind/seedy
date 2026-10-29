package io.github.arkmind.seedy.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.parseHexColor
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import java.util.UUID

/*
 * Album art (web/src/audio/cover.tsx). Without a picture on the server a cover is drawn the way the music boards draw
 * theirs: a sun or moon over a hill, a wave or a field in one of nine palettes, picked from the item's id, with the
 * title set on it.
 */

/** The boards' nine cover palettes: background, band and hill, sun, ring and ink. */
val CoverPalettes: List<List<Color>> = listOf(
    listOf("#1c2a3a", "#2f4a66", "#f2c47e", "#e8eef4"), listOf("#3a1d2e", "#6b2f4f", "#ff9fbf", "#fbe4f3"), listOf("#13302b", "#1f5a4d", "#9fe0c8", "#def4f1"),
    listOf("#2b2118", "#5c3b22", "#f0a35e", "#f7ecd5"), listOf("#1d1d2e", "#3b3b6b", "#b9b3ff", "#efe6fb"), listOf("#262626", "#444444", "#f0f0f0", "#ffffff"),
    listOf("#36200f", "#a3471f", "#ffd27a", "#fff2d8"), listOf("#0f2233", "#1b6f8f", "#9fe9ff", "#e6fbff"), listOf("#2a1035", "#7a3fd1", "#ffb3e6", "#fbe4f3"),
).map { p -> p.map { parseHexColor(it)!! } }

private val CoverPaths = listOf(
    "M0 140 Q50 110 100 140 T200 140 V200 H0Z", "M0 200 L200 70 V200Z", "M0 150 L50 110 L90 140 L140 90 L200 130 V200 H0Z", "M0 0 H200 V40 Q100 70 0 40Z",
).map { PathParser().parsePathString(it).toPath() }

/** FNV-1a over the id's characters, as the web does: the same item gets the same cover everywhere. */
fun coverSeed(id: String?): Int {
    var h = 2166136261L.toInt()
    for (ch in id.orEmpty()) {
        h = h xor ch.code
        h *= 16777619
    }
    return if (h == Int.MIN_VALUE) 0 else kotlin.math.abs(h)
}

fun coverPalette(id: String?): List<Color> = CoverPalettes[coverSeed(id) % CoverPalettes.size]

/** Jellyfin's 32-character ids, the way the web sees them (the seed hashes that form). */
fun webId(id: UUID?): String? = id?.toString()?.replace("-", "")

/** The item's own picture, else its album's, else (a book's file) its parent's. */
fun coverUrl(item: BaseItemDto?, images: io.github.arkmind.seedy.core.format.Images?, width: Int = 300): String? {
    if (item == null || images == null) return null
    item.imageTags?.get(ImageType.PRIMARY)?.let { return images.item(item.id, ImageType.PRIMARY, it, width) }
    val albumId = item.albumId
    val albumTag = item.albumPrimaryImageTag
    if (albumId != null && albumTag != null) return images.item(albumId, ImageType.PRIMARY, albumTag, width)
    val parentId = item.parentPrimaryImageItemId
    val parentTag = item.parentPrimaryImageTag
    if (parentId != null && parentTag != null) return images.item(parentId, ImageType.PRIMARY, parentTag, width)
    return null
}

/** The first letters of a name's first two words ("Ada Vale" → "AV"). */
fun initialsOf(name: String?): String =
    name.orEmpty().split(Regex("\\s+")).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.joinToString("").take(2).uppercase()

/** The drawn cover for [seed]; [plain] leaves out the sun and the hill (artists, whose initials go on top). */
@Composable
fun DrawnCover(seed: Int, modifier: Modifier = Modifier, plain: Boolean = false) {
    val i = seed % CoverPalettes.size
    val (c0, c1, c2, c3) = CoverPalettes[i]
    val s = i % 4
    val by = when (s) { 1 -> 120f; 3 -> 0f; else -> 200f }
    val bh = when (s) { 1 -> 80f; 3 -> 30f; else -> 0f }
    val cx = listOf(70f, 130f, 100f, 60f)[s]
    val cy = listOf(80f, 70f, 95f, 120f)[s]
    Canvas(modifier) {
        // viewBox 0 0 200 200, preserveAspectRatio xMidYMid slice.
        val k = maxOf(size.width, size.height) / 200f
        translate((size.width - 200f * k) / 2f, (size.height - 200f * k) / 2f) {
            scale(k, k, pivot = Offset.Zero) {
                drawRect(c0, size = androidx.compose.ui.geometry.Size(200f, 200f))
                if (bh > 0f) drawRect(c1, topLeft = Offset(0f, by), size = androidx.compose.ui.geometry.Size(200f, bh))
                if (!plain) {
                    drawCircle(c2, radius = listOf(46f, 30f, 58f, 22f)[s], center = Offset(cx, cy))
                    drawCircle(
                        c3.copy(alpha = listOf(0.5f, 0.35f, 0.6f, 0.4f)[s]), radius = listOf(70f, 52f, 82f, 40f)[s], center = Offset(cx, cy),
                        style = Stroke(width = listOf(3f, 8f, 2f, 6f)[s]),
                    )
                    drawPath(CoverPaths[(i + 1) % 4], c1.copy(alpha = listOf(0.9f, 0.7f, 0.85f, 0.6f)[s]))
                }
            }
        }
    }
}

/**
 * A cover: the server's picture, or the drawn one with [title] set on it (uppercase, at [titleSize]) or an artist's
 * [initials] in the middle. [seedId] draws from another id (a song draws its album's cover).
 */
@Composable
fun AudioCover(
    url: String?,
    seedId: String?,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleSize: TextUnit = 17.sp,
    initials: String? = null,
    initialsSize: TextUnit = 44.sp,
) {
    val seed = coverSeed(seedId)
    val palette = CoverPalettes[seed % CoverPalettes.size]
    var failed by remember(url) { mutableStateOf(false) }
    val drawn = url == null || failed
    Box(modifier.background(palette[0])) {
        if (drawn) {
            DrawnCover(seed, Modifier.fillMaxSize(), plain = initials != null)
            if (title != null) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    SText(
                        title, Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = maxWidth * 0.08f, end = maxWidth * 0.08f, bottom = maxHeight * 0.07f),
                        size = titleSize, weight = 900, color = palette[3], tracking = -0.01f, lineHeight = 0.95f, uppercase = true,
                    )
                }
            }
            if (initials != null) {
                SText(initials, Modifier.align(Alignment.Center), size = initialsSize, weight = 900, color = palette[3], align = TextAlign.Center, maxLines = 1)
            }
        }
        if (url != null && !failed) {
            AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize(), onError = { failed = true })
        }
    }
}
