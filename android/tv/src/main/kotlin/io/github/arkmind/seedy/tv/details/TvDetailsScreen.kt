package io.github.arkmind.seedy.tv.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.format.episodeNumber
import io.github.arkmind.seedy.core.format.inProgress
import io.github.arkmind.seedy.core.format.isNewEpisode
import io.github.arkmind.seedy.core.format.isVirtual
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.playLabel
import io.github.arkmind.seedy.core.format.premiereDay
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.core.format.rating
import io.github.arkmind.seedy.core.format.remainingSeconds
import io.github.arkmind.seedy.core.format.runtime
import io.github.arkmind.seedy.core.format.score
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.format.year
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.tv.browse.TvGridCard
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.CardProgress
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.time.LocalDate
import java.util.UUID

/** What the TV details page does; the route wires these to the view model and navigation. */
class TvDetailsActions(
    val onPlay: () -> Unit = {},
    val onEpisode: (BaseItemDto) -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onTrailer: () -> Unit = {},
    val onPlayed: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onSeason: (UUID) -> Unit = {},
    val onRequest: (List<Int>) -> Unit = {},
)

/**
 * Board "Android TV · Series details": the backdrop across the screen, the title and its facts, Resume, Episodes,
 * Trailer, Request and the heart, as on the board; the progress; the seasons as tabs (missing ones dashed)
 * and the open season's episodes. Movies show "More like this" there; collections and people their titles.
 */
@Composable
fun TvDetailsScreen(state: DetailsViewModel.State, actions: TvDetailsActions, canRequest: Boolean, playFocus: FocusRequester = remember { FocusRequester() }) {
    val item = state.item
    val viewer = LocalViewer.current
    val episodesFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        val art = item?.let { viewer.images?.backdrop(it, 1920) } ?: state.children?.firstNotNullOfOrNull { viewer.images?.backdrop(it, 1920) }
        if (art != null && item?.type != BaseItemKind.PERSON && viewer.backdrops) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to SeedyColors.Bg, 0.32f to SeedyColors.Bg.copy(alpha = 0.92f), 0.62f to SeedyColors.Bg.copy(alpha = 0.4f), 1f to SeedyColors.Bg.copy(alpha = 0.1f)),
            ),
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(520.bp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.6f to SeedyColors.Bg, 1f to SeedyColors.Bg)),
        )
        if (item == null) {
            if (!state.loading) {
                SText(
                    if (state.missing) t("This title isn't here") else t("Couldn't load this title"),
                    Modifier.padding(start = 96.bp, top = 96.bp), size = 48.bsp, weight = 900, color = SeedyColors.White,
                )
            }
            return@Box
        }
        val episodesList = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val upNext = state.shownEpisodes.orEmpty().indexOfFirst { it.id == state.target?.item?.id }.coerceAtLeast(0)
        Hero(state, item, actions, canRequest, playFocus, onEpisodes = {
            // Episodes: the remote goes to the episode to watch next.
            scope.launch {
                episodesList.scrollToItem((upNext - 1).coerceAtLeast(0))
                episodesFocus.focusSoon()
            }
        })
        Column(Modifier.padding(start = 96.bp, top = 628.bp), verticalArrangement = Arrangement.spacedBy(26.bp)) {
            when (item.type) {
                BaseItemKind.SERIES -> Episodes(state, actions, episodesFocus, episodesList, upNext)
                BaseItemKind.BOX_SET, BaseItemKind.PLAYLIST, BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.PERSON ->
                    CardRow(if (item.type == BaseItemKind.PERSON) t("Filmography") else t("In this collection"), state.children.orEmpty(), actions.onOpen, episodesFocus)
                else -> CardRow(t("More like this"), state.similar.orEmpty(), actions.onOpen, episodesFocus)
            }
        }
    }
    LaunchedEffect(item != null) { if (item != null) playFocus.focusSoon() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    state: DetailsViewModel.State,
    item: BaseItemDto,
    actions: TvDetailsActions,
    canRequest: Boolean,
    playFocus: FocusRequester,
    onEpisodes: () -> Unit,
) {
    val accent = LocalAccent.current
    val isSeries = item.type == BaseItemKind.SERIES
    val isList = item.type == BaseItemKind.BOX_SET || item.type == BaseItemKind.PLAYLIST || item.type == BaseItemKind.FOLDER
    val gaps = state.gaps
    Column(Modifier.padding(start = 96.bp, top = 96.bp).width(1240.bp), verticalArrangement = Arrangement.spacedBy(20.bp)) {
        val name = item.name.orEmpty()
        SText(name, size = titleSize(name), weight = 900, lineHeight = 0.9f, tracking = -0.02f, color = SeedyColors.White, uppercase = true, maxLines = 2)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(18.bp), verticalArrangement = Arrangement.spacedBy(8.bp), itemVerticalAlignment = Alignment.CenterVertically) {
            val first = if (isSeries) item.yearRange else item.year?.toString()
            if (!first.isNullOrEmpty()) SText(first, size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
            if (item.rating.isNotEmpty()) {
                SText(item.rating, Modifier.border(2.bp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(7.bp)).padding(horizontal = 12.bp, vertical = 2.bp), size = 16.bsp, weight = 700, color = SeedyColors.TextHero)
            }
            val count = when {
                isSeries -> state.seasonsLabel
                isList || item.type == BaseItemKind.PERSON -> state.children?.size?.let { tn(it, "{n} title", "{n} titles") }.orEmpty()
                else -> item.runtime
            }
            if (count.isNotEmpty()) SText(count, size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
            item.score?.let { s ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.bp)) {
                    SIcon(WebIcons.Star, size = 20.bp, color = accent)
                    SText(s, size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
                }
            }
            val genres = item.genres.orEmpty().take(3)
            if (genres.isNotEmpty()) SText(genres.joinToString(" · "), size = 21.bsp, weight = 500, color = SeedyColors.TextHero)
        }
        if (state.badges.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                for (b in state.badges) {
                    SText(b, Modifier.border(2.bp, Color.White.copy(alpha = 0.24f), RoundedCornerShape(9.bp)).padding(horizontal = 14.bp, vertical = 5.bp), size = 16.bsp, weight = 700, color = SeedyColors.Text)
                }
            }
        }
        item.overview?.takeIf { it.isNotBlank() }?.let { SText(it, Modifier.width(940.bp), size = 22.bsp, lineHeight = 1.5f, color = SeedyColors.TextHero, maxLines = 3) }
        if (item.type != BaseItemKind.PERSON) {
            Row(Modifier.padding(top = 10.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp)) {
                val target = state.target?.item
                val label = when {
                    target != null -> playLabel(target)
                    !isSeries && item.inProgress -> t("Resume")
                    else -> t("Play")
                }
                HeroButton(label, WebIcons.Play, actions.onPlay, Modifier.focusRequester(playFocus), primary = true)
                if (isSeries) HeroButton(t("Episodes"), AppIcons.Episodes, onEpisodes)
                if (state.hasTrailer) HeroButton(t("Trailer"), WebIcons.Trailer, actions.onTrailer)
                if (isSeries && canRequest && gaps.open.isNotEmpty()) {
                    HeroButton(tn(gaps.open.size, "Request {n} season", "Request {n} seasons"), WebIcons.Request, { actions.onRequest(emptyList()) })
                }
                val fav = item.userData?.isFavorite == true
                HeroButton(null, if (fav) AppIcons.HeartFilled else WebIcons.Heart, actions.onFavorite)
            }
            Progress(state, item, canRequest)
        }
    }
}

/** 92 px, smaller (down to 60) so the title fits its 1240 px on one line (Noto Sans Black capitals run about 0.72 em). */
private fun titleSize(title: String): TextUnit {
    val fit = 1240f / (title.length.coerceAtLeast(4) * 0.72f)
    return (fit.coerceIn(60f, 92f) / 2f).sp
}

/** The board's 66 px hero buttons: translucent, the main one light; light with the white ring when focused. */
@Composable
private fun HeroButton(label: String?, icon: IconDef, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, iconColor: Color? = null) {
    TvPressable(onClick, modifier) { focused ->
        val light = primary || focused
        Row(
            Modifier.focusScale(focused, 1.04f).focusRing(focused, radius = 19.bp, gap = 5.bp, ring = 5.bp).height(66.bp).clip(RoundedCornerShape(19.bp))
                .background(if (light) SeedyColors.Inverse else Color.White.copy(alpha = 0.14f))
                .padding(horizontal = if (label == null) 26.bp else if (primary) 34.bp else 26.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp),
        ) {
            val ink = if (light) SeedyColors.InkInverse else SeedyColors.White
            SIcon(icon, size = 26.bp, color = iconColor ?: ink, stroke = if (icon.stroke > 0f) 2.2f else 0f)
            if (label != null) SText(label, size = 22.bsp, weight = if (primary) 800 else 700, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun Progress(state: DetailsViewModel.State, item: BaseItemDto, canRequest: Boolean) {
    val target = state.target
    val (fraction, text) = when {
        target != null && target.resume -> target.item.progress to t("{time} of {name}", "time" to timeLeft(target.item.remainingSeconds), "name" to target.item.name.orEmpty())
        item.type != BaseItemKind.SERIES && item.inProgress -> item.progress to timeLeft(item.remainingSeconds)
        else -> null to null
    }
    val gaps = if (canRequest) state.gaps.line.takeIf { it.isNotEmpty() } else null
    if (fraction == null && gaps == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp)) {
        if (fraction != null) {
            Box(Modifier.width(280.bp).height(6.bp).clip(RoundedCornerShape(4.bp)).background(Color.White.copy(alpha = 0.2f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(SeedyColors.White))
            }
        }
        SText(join(text, gaps), size = 18.bsp, color = SeedyColors.Text2, maxLines = 1)
    }
}

@Composable
private fun Episodes(state: DetailsViewModel.State, actions: TvDetailsActions, focus: FocusRequester, list: LazyListState, start: Int) {
    // 10 px of room around tabs and cards keeps the focus ring inside their rows (they clip). Coming down from the
    // buttons lands on the open season's tab; OK on another opens it.
    val currentTab = remember { FocusRequester() }
    LazyRow(
        Modifier.offset(x = (-10).bp, y = (-10).bp).fillMaxWidth()
            .focusProperties { onEnter = { currentTab.requestFocus() } }.focusGroup(),
        contentPadding = PaddingValues(start = 10.bp, end = 96.bp, top = 10.bp, bottom = 10.bp),
        horizontalArrangement = Arrangement.spacedBy(14.bp),
    ) {
        itemsIndexed(state.tabs, key = { _, tab -> tab.key }) { _, tab ->
            when (tab) {
                is DetailsViewModel.SeasonTab.OnServer -> SeasonTab(
                    tab.season.name.orEmpty(), null, tab.season.id == state.selected, missing = false, onClick = { actions.onSeason(tab.season.id) },
                    modifier = if (tab.season.id == state.selected) Modifier.focusRequester(currentTab) else Modifier,
                )
                is DetailsViewModel.SeasonTab.Missing -> SeasonTab(
                    tab.season.name, if (tab.season.requested) t("Requested") else tab.season.year?.toString() ?: t("Not on the server"),
                    current = false, missing = true, onClick = { if (!tab.season.requested) actions.onRequest(listOf(tab.season.n)) },
                )
            }
        }
    }
    val eps = state.shownEpisodes.orEmpty()
    // As on the web, the row starts near the episode to watch next when it's further than the fifth.
    LaunchedEffect(state.episodesFor) { if (start >= 5) list.scrollToItem(start - 1) }
    LazyRow(
        Modifier.offset(x = (-10).bp, y = (-30).bp).fillMaxWidth(),
        state = list,
        contentPadding = PaddingValues(start = 10.bp, end = 96.bp, top = 10.bp, bottom = 10.bp),
        horizontalArrangement = Arrangement.spacedBy(32.bp),
    ) {
        itemsIndexed(eps, key = { _, it -> it.id }) { i, ep ->
            EpisodeCard(ep, { actions.onEpisode(ep) }, if (i == start) Modifier.focusRequester(focus) else Modifier)
        }
    }
}

private val DetailsViewModel.SeasonTab.key: String
    get() = when (this) {
        is DetailsViewModel.SeasonTab.OnServer -> season.id.toString()
        is DetailsViewModel.SeasonTab.Missing -> "missing:${season.n}"
    }

/** A season tab; a missing season's is dashed, with its year or "Not on the server". */
@Composable
private fun SeasonTab(label: String, note: String?, current: Boolean, missing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TvPressable(onClick, modifier) { focused ->
        val shape = RoundedCornerShape(16.bp)
        Row(
            Modifier.focusScale(focused, 1.04f).focusRing(focused, radius = 16.bp, gap = 4.bp, ring = 4.bp).height(52.bp).clip(shape)
                .background(
                    when {
                        focused -> SeedyColors.Inverse
                        current -> Color.White.copy(alpha = 0.2f)
                        missing -> Color.Transparent
                        else -> Color.White.copy(alpha = 0.07f)
                    },
                )
                .then(if (missing && !focused) Modifier.dashed(3.bp, 16.bp, SeedyColors.Stroke) else Modifier)
                // The board keeps the note's 10 px gap even when there's no note.
                .padding(start = if (missing) 25.bp else 22.bp, end = if (missing) 25.bp else if (note == null) 32.bp else 22.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp),
        ) {
            val ink = when {
                focused -> SeedyColors.InkInverse
                missing -> SeedyColors.Text3
                else -> SeedyColors.White
            }
            SText(label, size = 20.bsp, weight = if (current || focused) 800 else 600, color = ink, maxLines = 1)
            if (note != null) SText(note, size = 16.bsp, weight = 600, color = if (focused) Color(0xFF5A5A5A) else SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** A dashed outline drawn inside the bounds (CSS `border: 3px dashed`). */
private fun Modifier.dashed(width: Dp, radius: Dp, color: Color): Modifier = this.drawBehind {
    val w = width.toPx()
    drawRoundRect(
        color, topLeft = androidx.compose.ui.geometry.Offset(w / 2, w / 2), size = androidx.compose.ui.geometry.Size(size.width - w, size.height - w),
        cornerRadius = CornerRadius(radius.toPx() - w / 2), style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 3, w * 2))),
    )
}

/** An episode: its picture (Watched or New, progress), "2 · The Pass" and "52 min · 34 min left". */
@Composable
private fun EpisodeCard(ep: BaseItemDto, onPlay: () -> Unit, modifier: Modifier) {
    val viewer = LocalViewer.current
    val accent = LocalAccent.current
    val upcoming = ep.isVirtual
    TvPressable(onPlay, modifier.width(372.bp)) { focused ->
        Column(verticalArrangement = Arrangement.spacedBy(12.bp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 5.bp).clip(RoundedCornerShape(18.bp)).background(SeedyColors.Surface)) {
                Artwork(viewer.images?.landscape(ep, 640, viewer.episodeImages), Modifier.matchParentSize().alpha(if (upcoming) 0.35f else 1f))
                if (ep.inProgress) CardProgress(ep.progress, side = 14.bp, bottom = 14.bp, height = 6.bp)
                val badge = when {
                    ep.userData?.played == true -> t("Watched") to SeedyColors.Text
                    ep.isNewEpisode && !upcoming -> t("New") to accent
                    else -> null
                }
                if (badge != null) {
                    SText(
                        badge.first, Modifier.padding(14.bp).clip(RoundedCornerShape(10.bp)).background(SeedyColors.Bg.copy(alpha = 0.85f)).padding(horizontal = 12.bp, vertical = 4.bp),
                        size = 16.bsp, weight = 700, color = badge.second,
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(join(ep.episodeNumber, ep.name), size = 20.bsp, weight = 700, color = if (upcoming) SeedyColors.Text2 else SeedyColors.White, maxLines = 1)
                SText(tvEpisodeMeta(ep), size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** "54 min · Watched", "52 min · 34 min left", "49 min · 9 Oct", "Airs Fri 23 Oct". */
fun tvEpisodeMeta(ep: BaseItemDto): String {
    val day = ep.premiereDay
    if (ep.isVirtual) return day?.let { if (it.isAfter(LocalDate.now())) t("Airs {date}", "date" to weekdayDate(it)) else dayMonth(it) }.orEmpty()
    return join(
        ep.runtime,
        when {
            ep.userData?.played == true -> t("Watched")
            ep.inProgress -> timeLeft(ep.remainingSeconds)
            else -> day?.let(::dayMonth)
        },
    )
}

@Composable
private fun CardRow(title: String, items: List<BaseItemDto>, onOpen: (BaseItemDto) -> Unit, focus: FocusRequester) {
    if (items.isEmpty()) return
    SText(title, size = 24.bsp, weight = 800, color = SeedyColors.White)
    LazyRow(
        Modifier.offset(x = (-10).bp, y = (-10).bp).fillMaxWidth(),
        contentPadding = PaddingValues(start = 10.bp, end = 96.bp, top = 10.bp, bottom = 10.bp),
        horizontalArrangement = Arrangement.spacedBy(32.bp),
    ) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
            TvGridCard(item, { onOpen(item) }, Modifier.width(372.bp).then(if (i == 0) Modifier.focusRequester(focus) else Modifier))
        }
    }
}
