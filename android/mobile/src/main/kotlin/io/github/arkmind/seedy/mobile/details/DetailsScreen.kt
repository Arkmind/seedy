package io.github.arkmind.seedy.mobile.details

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.feature.DetailsViewModel
import io.github.arkmind.seedy.core.format.dayMonth
import io.github.arkmind.seedy.core.format.episodeNumber
import io.github.arkmind.seedy.core.format.inProgress
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
import io.github.arkmind.seedy.core.format.weekdayDate
import io.github.arkmind.seedy.core.format.year
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.browse.GridCard
import io.github.arkmind.seedy.mobile.browse.NoticeCard
import io.github.arkmind.seedy.mobile.browse.Portrait
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.bleed
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.CardProgress
import io.github.arkmind.seedy.ui.components.WatchedMark
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
import java.util.UUID
import kotlin.math.min

/** What the details page does; the route wires these to the view model and navigation. */
class DetailsActions(
    val onBack: () -> Unit = {},
    val onPlay: () -> Unit = {},
    val onEpisode: (BaseItemDto) -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
    val onTrailer: () -> Unit = {},
    val onPlayed: () -> Unit = {},
    val onFavorite: () -> Unit = {},
    val onShuffle: () -> Unit = {},
    val onSeason: (UUID) -> Unit = {},
    /** Opens the request sheet: the seasons to tick first (empty: every open one). */
    val onRequest: (List<Int>) -> Unit = {},
    val onMarkSeason: () -> Unit = {},
)

/**
 * Board "Android · Series details": the backdrop with the back, cast and menu buttons on it; the title, its facts and
 * formats; Play and the progress under it; Trailer, Watched, Favorite and Shuffle; the overview; the missing seasons
 * card; the season picker and its episodes. A movie is the same page without seasons; collections and people list
 * their titles.
 */
@Composable
fun DetailsScreen(state: DetailsViewModel.State, actions: DetailsActions, canRequest: Boolean, bottomPadding: Dp) {
    val item = state.item
    LazyColumn(Modifier.fillMaxSize().background(SeedyColors.Bg), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        if (item == null) {
            item(key = "loading") {
                Box(Modifier.fillMaxWidth().height(240.dp)) { TopButtons(actions, null) }
                if (!state.loading) {
                    SText(
                        if (state.missing) t("This title isn't here") else t("Couldn't load this title"),
                        Modifier.padding(horizontal = 20.dp), size = 20.sp, weight = 900, color = SeedyColors.White,
                    )
                }
            }
            return@LazyColumn
        }
        item(key = "top") { Top(state, item, actions, canRequest) }
        when (item.type) {
            BaseItemKind.SERIES -> {
                val eps = state.shownEpisodes
                if (eps == null && state.seasons?.isNotEmpty() != false) {
                    items(3) { Box(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).fillMaxWidth().height(72.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Panel)) }
                } else if (eps != null) {
                    if (eps.isEmpty()) item { SText(t("No episodes in this season yet."), Modifier.padding(20.dp), size = 13.sp, color = SeedyColors.Text2) }
                    items(eps, key = { it.id }) { ep -> EpisodeRow(ep, upNext = ep.id == state.target?.item?.id, onPlay = { actions.onEpisode(ep) }) }
                }
                similar(state, actions)
            }
            BaseItemKind.BOX_SET, BaseItemKind.PLAYLIST, BaseItemKind.FOLDER, BaseItemKind.COLLECTION_FOLDER, BaseItemKind.PERSON -> children(state, actions)
            else -> similar(state, actions)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.similar(state: DetailsViewModel.State, actions: DetailsActions) {
    val similar = state.similar.orEmpty()
    if (similar.isEmpty()) return
    item(key = "similar") {
        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(t("More like this"), Modifier.padding(horizontal = 20.dp), size = 16.sp, weight = 800, color = SeedyColors.White)
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(similar, key = { it.id }) { GridCard(it, { actions.onOpen(it) }, Modifier.width(170.dp)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.children(state: DetailsViewModel.State, actions: DetailsActions) {
    val kids = state.children ?: return
    val person = state.item?.type == BaseItemKind.PERSON
    item(key = "kids-head") {
        SText(
            if (person) t("Filmography") else if (state.item?.type == BaseItemKind.PLAYLIST) t("In this playlist") else t("In this collection"),
            Modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 12.dp), size = 16.sp, weight = 800, color = SeedyColors.White,
        )
    }
    // Two cards a row, as in a library.
    items(kids.chunked(2), key = { it.first().id }) { pair ->
        Row(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            for (it in pair) GridCard(it, { actions.onOpen(it) }, Modifier.weight(1f))
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun TopButtons(actions: DetailsActions, menu: (@Composable (close: () -> Unit) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = topInset()),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        GlassButton(WebIcons.ArrowLeft, 22.dp, actions.onBack)
        Spacer(Modifier.weight(1f))
        GlassButton(WebIcons.Cast, 21.dp, {})
        if (menu != null) {
            var open by remember { mutableStateOf(false) }
            Box {
                GlassButton(AppIcons.MoreVertical, 22.dp, { open = true })
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                    menu { open = false }
                }
            }
        }
    }
}

@Composable
private fun GlassButton(icon: IconDef, size: Dp, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Bg.copy(alpha = 0.5f)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = size, color = SeedyColors.White)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Top(state: DetailsViewModel.State, item: BaseItemDto, actions: DetailsActions, canRequest: Boolean) {
    val viewer = LocalViewer.current
    val isSeries = item.type == BaseItemKind.SERIES
    val isPerson = item.type == BaseItemKind.PERSON
    val isList = item.type == BaseItemKind.BOX_SET || item.type == BaseItemKind.PLAYLIST || item.type == BaseItemKind.FOLDER || item.type == BaseItemKind.COLLECTION_FOLDER
    Box(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(240.dp)) {
            val art = viewer.images?.backdrop(item, 1280) ?: state.children?.firstNotNullOfOrNull { viewer.images?.backdrop(it, 1280) }
            if (art != null && !isPerson) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.4f, -0.2f))
            Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.7f).background(Brush.verticalGradient(listOf(Color.Transparent, SeedyColors.Bg))))
            TopButtons(actions, menu(state, actions))
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 196.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (isPerson) Portrait(item, 96.dp, letters = 30.sp)
            BoxWithConstraints {
                val name = item.name.orEmpty()
                SText(name, size = titleSize(name, maxWidth), weight = 900, lineHeight = 0.95f, tracking = -0.02f, color = SeedyColors.White, uppercase = true, maxLines = 3)
            }
            Meta(state, item)
            if (state.badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in state.badges) {
                        SText(b, Modifier.border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(7.dp)).padding(horizontal = 9.dp, vertical = 3.dp), size = 11.sp, weight = 700, color = SeedyColors.Text)
                    }
                }
            }
            if (!isPerson) {
                PlayButton(state, item, actions.onPlay, enabled = !isList || !state.children.isNullOrEmpty())
                Progress(state, item)
                ActionGrid(state, item, actions)
            }
            item.overview?.takeIf { it.isNotBlank() }?.let { SText(it, size = 13.sp, lineHeight = 1.55f, color = SeedyColors.TextHero, maxLines = 3) }
            val gaps = state.gaps
            if (isSeries && canRequest && gaps.open.isNotEmpty()) {
                NoticeCard(
                    AppIcons.Requests, gaps.title, gaps.sub, padding = PaddingValues(start = 12.dp, end = 10.dp, top = 10.dp, bottom = 10.dp), subColor = SeedyColors.Text2,
                ) {
                    Box(
                        Modifier.height(44.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Inverse).tap { actions.onRequest(emptyList()) }
                            .padding(horizontal = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        SText(t("Request"), size = 14.sp, weight = 800, color = SeedyColors.InkInverse)
                    }
                }
            }
            if (isSeries && !state.seasons.isNullOrEmpty()) SeasonBar(state, actions)
        }
    }
}

/** 32 px, smaller when the title's longest word wouldn't fit on a line (Noto Sans Black capitals run about 0.72 em). */
private fun titleSize(title: String, maxWidth: Dp): TextUnit {
    val longest = title.split(Regex("\\s+")).maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
    return min(32f, maxWidth.value / (longest * 0.72f)).sp
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Meta(state: DetailsViewModel.State, item: BaseItemDto) {
    val first = when (item.type) {
        BaseItemKind.SERIES -> item.yearRange
        BaseItemKind.PERSON -> null
        else -> item.year?.toString()
    }
    val rest = when (item.type) {
        BaseItemKind.SERIES -> state.seasonsLabel
        BaseItemKind.BOX_SET, BaseItemKind.PLAYLIST, BaseItemKind.FOLDER -> state.children?.size?.let { tn(it, "{n} title", "{n} titles") }.orEmpty()
        BaseItemKind.PERSON -> state.children?.size?.let { tn(it, "{n} title", "{n} titles") }.orEmpty()
        else -> item.runtime
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        if (!first.isNullOrEmpty()) SText(first, size = 13.sp, color = SeedyColors.TextHero)
        if (item.rating.isNotEmpty()) {
            SText(item.rating, Modifier.border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 1.dp), size = 11.sp, weight = 700, color = SeedyColors.TextHero)
        }
        if (rest.isNotEmpty()) SText(rest, size = 13.sp, color = SeedyColors.TextHero)
        item.score?.let { s ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SIcon(WebIcons.Star, size = 13.dp, color = LocalAccent.current)
                SText(s, size = 13.sp, color = SeedyColors.TextHero)
            }
        }
    }
}

@Composable
private fun PlayButton(state: DetailsViewModel.State, item: BaseItemDto, onPlay: () -> Unit, enabled: Boolean) {
    val target = state.target?.item
    val label = when {
        target != null -> playLabel(target)
        item.type == BaseItemKind.SERIES -> t("Play")
        else -> if (item.inProgress) t("Resume") else t("Play")
    }
    Row(
        Modifier.padding(top = 4.dp).fillMaxWidth().height(48.dp).clip(RoundedCornerShape(13.dp))
            .background(if (enabled) SeedyColors.Inverse else SeedyColors.Surface2).tap(enabled = enabled, onClick = onPlay),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (enabled) SeedyColors.InkInverse else SeedyColors.Text3
        SIcon(WebIcons.Play, size = 20.dp, color = ink)
        SText(label, size = 14.sp, weight = 800, color = ink, maxLines = 1)
    }
}

@Composable
private fun Progress(state: DetailsViewModel.State, item: BaseItemDto) {
    val target = state.target
    val (fraction, text) = when {
        target != null && target.resume -> target.item.progress to t("{time} of {name}", "time" to timeLeft(target.item.remainingSeconds), "name" to target.item.name.orEmpty())
        item.type != BaseItemKind.SERIES && item.inProgress -> item.progress to timeLeft(item.remainingSeconds)
        else -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(SeedyColors.Surface2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(SeedyColors.White))
        }
        SText(text, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
    }
}

@Composable
private fun ActionGrid(state: DetailsViewModel.State, item: BaseItemDto, actions: DetailsActions) {
    val played = item.userData?.played == true
    val fav = item.userData?.isFavorite == true
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item.type == BaseItemKind.MOVIE || item.type == BaseItemKind.SERIES || item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.VIDEO) {
            Action(WebIcons.Trailer, t("Trailer"), actions.onTrailer, Modifier.weight(1f), enabled = state.hasTrailer)
        }
        Action(WebIcons.Check, t("Watched"), actions.onPlayed, Modifier.weight(1f), color = if (played) accent else SeedyColors.Text, stroke = if (played) 3f else 2.2f)
        Action(if (fav) AppIcons.HeartFilled else WebIcons.Heart, t("Favorite"), actions.onFavorite, Modifier.weight(1f), color = if (fav) accent else SeedyColors.Text)
        if (item.type == BaseItemKind.SERIES || item.type == BaseItemKind.BOX_SET || item.type == BaseItemKind.PLAYLIST || item.type == BaseItemKind.FOLDER) {
            Action(WebIcons.Shuffle, t("Shuffle"), actions.onShuffle, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Action(icon: IconDef, label: String, onClick: () -> Unit, modifier: Modifier, color: Color = SeedyColors.Text, stroke: Float = icon.stroke, enabled: Boolean = true) {
    Column(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).tap(enabled = enabled, onClick = onClick).padding(vertical = 6.dp).alpha(if (enabled) 1f else 0.4f),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SIcon(icon, size = 22.dp, color = color, stroke = stroke)
        SText(label, size = 12.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
    }
}

@Composable
private fun menu(state: DetailsViewModel.State, actions: DetailsActions): (@Composable (close: () -> Unit) -> Unit)? {
    val season = state.selectedSeason ?: return null
    val eps = state.shownEpisodes.orEmpty().filter { !it.isVirtual }
    val all = eps.isNotEmpty() && eps.all { it.userData?.played == true }
    return { close ->
        DropdownMenuItem(
            text = { SText(if (all) t("Mark {name} unwatched", "name" to season.name.orEmpty()) else t("Mark {name} watched", "name" to season.name.orEmpty()), size = 14.sp, weight = 700) },
            leadingIcon = { SIcon(WebIcons.Check, size = 18.dp, color = SeedyColors.Text2) },
            onClick = { close(); actions.onMarkSeason() },
        )
    }
}

/** "Season 3 ▾" and "1 of 10 watched". The menu lists the seasons, missing ones too (they open the request sheet). */
@Composable
private fun SeasonBar(state: DetailsViewModel.State, actions: DetailsActions) {
    var open by remember { mutableStateOf(false) }
    val eps = state.shownEpisodes.orEmpty()
    Row(Modifier.padding(top = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.height(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).tap { open = true }.padding(start = 16.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SText(state.selectedSeason?.name.orEmpty(), size = 14.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                SIcon(WebIcons.ChevronDown, size = 16.dp, color = SeedyColors.White, stroke = 2.4f)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
                for (tab in state.tabs) {
                    when (tab) {
                        is DetailsViewModel.SeasonTab.OnServer -> DropdownMenuItem(
                            text = { SText(tab.season.name.orEmpty(), size = 14.sp, weight = if (tab.season.id == state.selected) 800 else 600, color = SeedyColors.White) },
                            trailingIcon = if (tab.season.id == state.selected) ({ SIcon(WebIcons.Check, size = 16.dp, color = LocalAccent.current) }) else null,
                            onClick = { open = false; actions.onSeason(tab.season.id) },
                        )
                        is DetailsViewModel.SeasonTab.Missing -> DropdownMenuItem(
                            text = {
                                Column {
                                    SText(tab.season.name, size = 14.sp, weight = 600, color = SeedyColors.Text3)
                                    SText(
                                        if (tab.season.requested) t("Requested") else tab.season.year?.let { t("Announced for {year}", "year" to it) } ?: t("Not on the server"),
                                        size = 11.sp, color = SeedyColors.Text3,
                                    )
                                }
                            },
                            leadingIcon = { SIcon(WebIcons.Plus, size = 14.dp, color = SeedyColors.Text3, stroke = 2.4f) },
                            enabled = !tab.season.requested,
                            onClick = { open = false; actions.onRequest(listOf(tab.season.n)) },
                        )
                    }
                }
            }
        }
        if (eps.isNotEmpty()) {
            SText(t("{n} of {total} watched", "n" to eps.count { it.userData?.played == true }, "total" to eps.size), size = 12.sp, color = SeedyColors.Text3)
        }
    }
}

/** One episode: its picture (watched mark, progress), "2 · The Pass", "52 min · 34 min left", and a play button. */
@Composable
private fun EpisodeRow(ep: BaseItemDto, upNext: Boolean, onPlay: () -> Unit) {
    val viewer = LocalViewer.current
    val playable = !ep.isVirtual
    Row(
        Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp).bleed(6.dp).clip(RoundedCornerShape(12.dp))
            .background(if (upNext) SeedyColors.Row else Color.Transparent).tap(enabled = playable, onClick = onPlay).padding(6.dp)
            .alpha(if (playable) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))) {
            Artwork(viewer.images?.landscape(ep, 320, viewer.episodeImages), Modifier.matchParentSize())
            if (ep.userData?.played == true) WatchedMark(size = 22.dp, inset = 6.dp, check = 13.dp, stroke = 3f)
            if (ep.inProgress) CardProgress(ep.progress, side = 6.dp, bottom = 6.dp, height = 4.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(join(ep.episodeNumber, ep.name), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2)
            val meta = episodeMeta(ep)
            if (meta.isNotEmpty()) SText(meta, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).tap(enabled = playable, onClick = onPlay), contentAlignment = Alignment.Center) {
            SIcon(AppIcons.PlayOutline, size = 20.dp, color = SeedyColors.Text2)
        }
    }
}

/** "54 min · 25 Sep", "52 min · 34 min left", "Airs Fri 23 Oct". */
fun episodeMeta(ep: BaseItemDto): String {
    val day = ep.premiereDay
    if (ep.isVirtual) return day?.let { if (it.isAfter(java.time.LocalDate.now())) t("Airs {date}", "date" to weekdayDate(it)) else dayMonth(it) }.orEmpty()
    return join(ep.runtime, if (ep.inProgress) timeLeft(ep.remainingSeconds) else day?.let(::dayMonth))
}
