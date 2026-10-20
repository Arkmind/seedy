package io.github.arkmind.seedy.mobile.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.browse.marks
import io.github.arkmind.seedy.core.format.inProgress
import io.github.arkmind.seedy.core.format.libraryMeta
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.core.seedy.SeerrItem
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.ArtTitle
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.CardArtwork
import io.github.arkmind.seedy.ui.components.CardProgress
import io.github.arkmind.seedy.ui.components.WatchedMark
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import io.github.arkmind.seedy.ui.theme.SText
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

/* Pieces the phone's browse pages share (Libraries, a library, Favorites, Search, Details). */

/** The top of the phone boards: 36 px down, or 12 px under a taller status bar. */
@Composable
fun topInset(): Dp = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)

/** Board "Android · Libraries" header: a 24 px title, then Search and Cast. */
@Composable
fun TabHeader(title: String, onSearch: () -> Unit, modifier: Modifier = Modifier, count: String? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = topInset()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(title, size = 24.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
            if (!count.isNullOrEmpty()) SText(count, size = 13.sp, weight = 600, color = SeedyColors.Text3, maxLines = 1)
        }
        HeaderButton(WebIcons.Search, onSearch, stroke = 2.2f)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).tap {}, contentAlignment = Alignment.Center) {
            SIcon(WebIcons.Cast, size = 21.dp, color = SeedyColors.White)
        }
    }
}

/** A 36 px pill: the library's views, Search's kinds, Favorites' groups. [count] follows the label. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, count: String? = null, weight: Int = if (on) 800 else 600) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(999.dp)).background(if (on) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SText(label, size = 13.sp, weight = weight, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1)
        if (count != null) {
            SText(count, Modifier.padding(start = 6.dp), size = 13.sp, weight = 600, color = if (on) Color(0xFF5A5A5A) else SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** A row of chips that scrolls sideways, 20 px in from the edges. */
@Composable
fun ChipRow(modifier: Modifier = Modifier, side: Dp = 20.dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** The library's 40 px tool buttons (sort, filters) on the row surface. */
@Composable
fun ToolButton(icon: IconDef, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, badge: Int = 0) {
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).tap(onClick = onClick)
            .padding(start = 12.dp, end = if (badge > 0) 8.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SIcon(icon, size = 16.dp, color = SeedyColors.Text)
        SText(label, size = 13.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
        if (badge > 0) {
            Box(Modifier.height(20.dp).background(LocalAccent.current, CircleShape).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                SText("$badge", size = 11.sp, weight = 800, color = SeedyColors.InkInverse)
            }
        }
    }
}

/** Board "Android · Movies library" card: 16:9 art with the title on it, a watched mark or progress, the title and year. */
@Composable
fun GridCard(item: BaseItemDto, onClick: () -> Unit, modifier: Modifier = Modifier, meta: String = item.libraryMeta) {
    val viewer = LocalViewer.current
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            CardArtwork(
                viewer.images?.cardArt(item, 480, viewer.episodeImages), item.name.orEmpty(),
                ArtTitle(13.sp, start = 9.dp, end = 30.dp, bottom = 9.dp), Modifier.matchParentSize(),
            )
            if (item.userData?.played == true) WatchedMark(size = 22.dp, inset = 6.dp, check = 12.dp, stroke = 3.2f)
            if (item.inProgress) CardProgress(item.progress, side = 6.dp, bottom = 5.dp, height = 3.dp)
        }
        Column(Modifier.padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(item.name.orEmpty(), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            if (meta.isNotEmpty()) SText(meta, size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** A grid card's stand-in while the page loads. */
@Composable
fun GridCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface))
        Box(Modifier.width(100.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Surface))
    }
}

/** Search's group labels and Top hit: 12 px capitals, spaced out. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    SText(text, modifier, size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true, maxLines = 1)
}

/** Board "Android · Search": "Not here? Find … on Seerr" and the details page's missing-seasons card share this frame. */
@Composable
fun NoticeCard(
    icon: IconDef,
    title: String,
    sub: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    padding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    subColor: Color = SeedyColors.Text3,
    trailing: @Composable () -> Unit = { SIcon(AppIcons.Chevron, size = 18.dp, color = SeedyColors.Text2) },
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel)
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp))
            .then(if (onClick != null) Modifier.tap(onClick = onClick) else Modifier).padding(padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(SeedyColors.Surface, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            SIcon(icon, size = 18.dp, color = LocalAccent.current, stroke = 2.2f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(title, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2)
            if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = subColor, maxLines = 2)
        }
        trailing()
    }
}

/** [text] with the parts that match [query] in the accent (Search's results). */
@Composable
fun marked(text: String, query: String, lead: String = ""): AnnotatedString {
    val accent = LocalAccent.current
    return buildAnnotatedString {
        append(lead)
        for ((part, hit) in marks(text, query)) if (hit) withStyle(SpanStyle(color = accent)) { append(part) } else append(part)
    }
}

/**
 * Text with highlighted parts, at a size and weight (SText takes plain strings). [lineHeight] below Noto's own
 * (1.362) trims the box the CSS way, glyphs centred, as SText does.
 */
@Composable
fun MarkedText(text: AnnotatedString, size: TextUnit, weight: Int, color: Color, modifier: Modifier = Modifier, uppercase: Boolean = false, lineHeight: Float? = null) {
    val trim = lineHeight?.takeIf { it < NotoLineRatio }
    BasicText(
        if (uppercase) AnnotatedString(text.text.uppercase(), text.spanStyles) else text,
        if (trim != null) modifier.layout { measurable, constraints ->
            val p = measurable.measure(constraints)
            val cut = ((NotoLineRatio - trim) * size.toPx()).coerceAtLeast(0f)
            layout(p.width, (p.height - cut).roundToInt()) { p.place(0, -(cut / 2).roundToInt()) }
        } else modifier,
        style = BaseText.copy(fontSize = size, fontWeight = FontWeight(weight), color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A Seerr title as a 2:3 poster with its type and status (Search's "Request it"). */
@Composable
fun SeerrPoster(item: SeerrItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))) {
            Artwork(tmdbImage(item.posterPath, "w342"), Modifier.matchParentSize())
            val tv = item.mediaType == "tv"
            SText(
                if (tv) "SERIES" else "MOVIE",
                Modifier.padding(8.dp).clip(CircleShape).background(if (tv) SeedyColors.TypeSeries else SeedyColors.TypeMovie).padding(horizontal = 8.dp, vertical = 2.dp),
                size = 10.sp, weight = 800, tracking = 0.1f, color = SeedyColors.White,
            )
        }
        Column(Modifier.padding(horizontal = 1.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(item.title, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(seerrMeta(item), size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** "2021 · ★ 7.0 · Requested", what a Seerr title's card says under its name. */
fun seerrMeta(item: SeerrItem): String = io.github.arkmind.seedy.core.format.join(
    item.year,
    item.rating?.takeIf { it > 0 }?.let { "★ " + String.format(java.util.Locale.US, "%.1f", it) },
    when (item.status) {
        "available", "partial" -> io.github.arkmind.seedy.core.i18n.t("On the server")
        "pending", "processing" -> io.github.arkmind.seedy.core.i18n.t("Requested")
        else -> null
    },
)

/** A circle portrait with the person's initials under it while it loads (or when there's none). */
@Composable
fun Portrait(item: BaseItemDto, size: Dp, modifier: Modifier = Modifier, letters: TextUnit = 15.sp) {
    val images = LocalViewer.current.images
    Box(modifier.size(size).clip(CircleShape).background(Color(0xFF3D2436)), contentAlignment = Alignment.Center) {
        SText(initials(item.name.orEmpty()), size = letters, weight = 900, color = Color(0xFFFFB3E6))
        Artwork(images?.primary(item, 240), Modifier.matchParentSize(), placeholder = Color.Transparent)
    }
}

/** "IN" for Ines Northey. */
fun initials(name: String): String =
    name.split(' ').filter { w -> w.any { it.isLetter() } }.take(2).joinToString("") { it.first().uppercase() }
