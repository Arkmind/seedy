package io.github.arkmind.seedy.mobile.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.graphicsLayer
import io.github.arkmind.seedy.core.format.inProgress
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.fitTitleSize
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.format.cardTitle
import io.github.arkmind.seedy.core.format.episodeShort
import io.github.arkmind.seedy.core.format.heroMeta
import io.github.arkmind.seedy.core.format.playLabel
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.core.format.remainingSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.requests.SeerrTitleCard
import io.github.arkmind.seedy.mobile.wrapped.WrappedBanner
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.BrandMark
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.SeedyMotion
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/** Board "Android · Control a screen": the header's Cast button. */
private val CastIcon = io.github.arkmind.seedy.ui.icons.IconDef(2f, "M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6", "M3 12a8 8 0 0 1 8 8", "M3 16a4 4 0 0 1 4 4", "M3 20h.01")

/** What the header's account menu offers. */
data class AccountActions(val onSwitchProfile: () -> Unit, val onSignOut: () -> Unit, val onSettings: () -> Unit = {})

/** Board "Android · Home": the featured hero (swipe for the next title), then the home rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeViewModel.State,
    account: AccountActions,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    onSearch: () -> Unit,
    onSeeAll: (HomeViewModel.Row) -> Unit,
    bottomPadding: Dp,
    listState: LazyListState = rememberLazyListState(),
    onSeerr: (SeerrTitle) -> Unit = {},
    /** A Continue watching card's menu (long press): mark watched (true) or take out of the row (false); says whether it worked. */
    onFinish: ((BaseItemDto, Boolean, (Boolean) -> Unit) -> Unit)? = null,
    /** Pull to refresh (board "Android · Pull to refresh"); null turns it off. */
    onRefresh: (() -> Unit)? = null,
    pull: PullToRefreshState = rememberPullToRefreshState(),
    /** The header's Cast: "Control a screen" (null hides it); [controlling] lights it while the phone is a remote. */
    onCast: (() -> Unit)? = null,
    controlling: Boolean = false,
    /** December's Seedy Wrapped banner (board "Android · Home · Wrapped"): opens that year. */
    onWrapped: (Int) -> Unit = {},
) {
    var menuFor by remember { mutableStateOf<BaseItemDto?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val threshold = PullThreshold
    Box(
        Modifier.fillMaxSize().background(SeedyColors.Bg)
            .pullToRefresh(state.refreshing, pull, enabled = onRefresh != null, threshold = threshold) { onRefresh?.invoke() },
    ) {
        PullMedu(pull, state.refreshing, threshold)
        // The page (header included) comes down with the pull; the tab bar stays.
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = pull.distanceFraction.coerceAtLeast(0f) * threshold.toPx() }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
        ) {
            item(key = "hero") {
                val featured = state.featured
                if (state.hasHero && !featured.isNullOrEmpty()) {
                    Hero(featured, onOpen, onPlay)
                } else {
                    Box(Modifier.fillMaxWidth().height(if (state.hasHero && featured == null) 540.dp else 96.dp))
                }
            }
            state.wrapped?.let { teaser ->
                item(key = "wrapped") { WrappedBanner(teaser, { onWrapped(teaser.year) }, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) }
            }
            for (row in state.visibleRows) {
                val onMenu: ((BaseItemDto) -> Unit)? = if (row.row.kind == "continue" && onFinish != null) { item -> menuFor = item } else null
                item(key = row.row.id) { HomeRowView(row, onOpen, onSeeAll, onSeerr, onMenu) }
            }
        }
        // The header stays put (as the web's): clear over the banner, on the page's colour once the page moves.
        val stuck by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 } }
        Header(account, onSearch, stuck, onCast, controlling)
        }
        menuFor?.let { item ->
            ItemMenuSheet(item.cardTitle, continueEntries { watched ->
                onFinish?.invoke(item, watched) { ok ->
                    message = when {
                        !ok && watched -> t("Couldn’t mark it as watched")
                        !ok -> t("Couldn’t remove it")
                        watched -> t("Marked as watched")
                        else -> t("Removed from Continue watching")
                    }
                }
            }) { menuFor = null }
        }
        Toast(message, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPadding))
    }
}

/** How far the page comes down before letting go refreshes it: room for Medu and its line. */
private val PullThreshold = 112.dp

/**
 * Small Medu over the gap the pull opens, sliding down with it: "Pull to refresh", "Release to refresh" once far
 * enough, then "Refreshing…" while the rows come in again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PullMedu(pull: PullToRefreshState, refreshing: Boolean, threshold: Dp) {
    val fraction = pull.distanceFraction
    if (fraction <= 0f && !refreshing) return
    Column(
        Modifier.fillMaxWidth().graphicsLayer {
            translationY = (threshold * fraction.coerceAtLeast(0f) - 98.dp).toPx()
            alpha = fraction.coerceIn(0f, 1f)
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Medu(MeduMood.Idle, 60.dp, height = 79.dp, small = true)
        SText(
            when {
                refreshing -> t("Refreshing…")
                fraction >= 1f -> t("Release to refresh")
                else -> t("Pull to refresh")
            },
            size = 12.sp, weight = 700, color = SeedyColors.Text2,
        )
    }
}

/** A Continue watching card's menu: mark the title watched, or take it out of the row without marking it. */
private fun continueEntries(finish: (Boolean) -> Unit): List<MenuEntry> = listOf(
    MenuEntry(WebIcons.Check, t("Mark as watched"), run = { finish(true) }),
    MenuEntry(WebIcons.Close, t("Remove from Continue watching"), sub = t("Starts from the beginning next time"), run = { finish(false) }),
)

@Composable
private fun Header(account: AccountActions, onSearch: () -> Unit, stuck: Boolean, onCast: (() -> Unit)?, controlling: Boolean) {
    val viewer = LocalViewer.current
    val top = topInset()
    // The web blurs what's under its 85 % scrim; without a blur, anything see-through shows the artwork, so it's solid.
    val scrim by animateColorAsState(if (stuck) SeedyColors.Bg else Color.Transparent, tween(SeedyMotion.Fast), label = "header")
    val line by animateColorAsState(if (stuck) SeedyColors.Hairline else Color.Transparent, tween(SeedyMotion.Fast), label = "header-line")
    Row(
        Modifier.fillMaxWidth().background(scrim)
            .drawBehind { drawRect(line, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
            .padding(start = 20.dp, end = 16.dp, top = top, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrandMark(viewer.brand, Modifier.weight(1f))
        HeaderButton(WebIcons.Search, onSearch, stroke = 2.2f)
        if (onCast != null) HeaderButton(CastIcon, onCast, stroke = 2f, color = if (controlling) LocalAccent.current else SeedyColors.White)
        AccountButton(account)
    }
}

@Composable
private fun AccountButton(account: AccountActions) {
    val viewer = LocalViewer.current
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(44.dp).clip(CircleShape).tap { open = true }, contentAlignment = Alignment.Center) {
            ProfileSquare(viewer.name, viewer.imageUrl, 0, size = 34.dp, radius = 17.dp, letterSize = 14.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = SeedyColors.Row, shape = RoundedCornerShape(12.dp)) {
            DropdownMenuItem(
                text = { SText(t("Switch profile"), size = 14.sp, weight = 700) },
                leadingIcon = { SIcon(WebIcons.Users, size = 18.dp, color = SeedyColors.Text2) },
                onClick = { open = false; account.onSwitchProfile() },
            )
            DropdownMenuItem(
                text = { SText(t("Settings"), size = 14.sp, weight = 700) },
                leadingIcon = { SIcon(WebIcons.Sliders, size = 18.dp, color = SeedyColors.Text2) },
                onClick = { open = false; account.onSettings() },
            )
            DropdownMenuItem(
                text = { SText(t("Sign out"), size = 14.sp, weight = 700) },
                leadingIcon = { SIcon(WebIcons.SignOut, size = 18.dp, color = SeedyColors.Text2) },
                onClick = { open = false; account.onSignOut() },
            )
        }
    }
}

@Composable
private fun Hero(
    entries: List<FeaturedEntry>,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
) {
    val pager = rememberPagerState { entries.size }
    Box(Modifier.fillMaxWidth().height(540.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page -> HeroPage(entries[page], onOpen, onPlay) }
        Box(
            Modifier.fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(entries.size) { i ->
                Box(
                    Modifier.size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (i == pager.currentPage) SeedyColors.White else Color(0xFF5A5A5A)),
                )
            }
        }
    }
}

@Composable
@Suppress("ConfigurationScreenWidthHeight") // the title scales with the screen width as on the board; the window size would round differently
private fun HeroPage(entry: FeaturedEntry, onOpen: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit) {
    val viewer = LocalViewer.current
    val item = entry.item
    Box(Modifier.fillMaxSize()) {
        // The board crops the 16:9 backdrop to the right of centre (its viewBox starts 86% of the way across).
        viewer.images?.backdrop(item, 1280)?.takeIf { viewer.backdrops }?.let { url ->
            AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = BiasAlignment(0.72f, 0f))
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, SeedyColors.Bg))),
        )
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 22.dp + 6.dp + 2.dp + 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (entry.reason.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(6.dp).background(LocalAccent.current, CircleShape))
                    SText(t(entry.reason), size = 12.sp, weight = 800, tracking = 0.1f, color = LocalAccent.current, uppercase = true, maxLines = 1)
                }
            }
            SText(
                item.name.orEmpty(),
                size = fitTitleSize(item.name.orEmpty(), maxWidth = LocalConfiguration.current.screenWidthDp.dp - 40.dp, max = 40f),
                weight = 900, tracking = -0.02f, lineHeight = 0.92f, color = SeedyColors.White, uppercase = true, maxLines = 3,
            )
            SText(item.heroMeta, size = 13.sp, color = SeedyColors.TextHero, maxLines = 1)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val play = entry.play ?: item
                Row(
                    Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Inverse).tap { onPlay(play) },
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SIcon(WebIcons.Play, size = 20.dp, color = SeedyColors.InkInverse)
                    SText(playLabel(play), size = 14.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
                }
                Box(
                    Modifier.size(50.dp).clip(RoundedCornerShape(13.dp)).background(Color.White.copy(alpha = 0.14f)).tap { onOpen(item) },
                    contentAlignment = Alignment.Center,
                ) {
                    SIcon(HeroInfo, size = 22.dp, color = SeedyColors.White)
                }
            }
        }
    }
}

/** The hero's info circle is a little smaller than the web's (r 8.5). */
private val HeroInfo = IconDef(2.2f, "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z", "M12 11v5", "M12 8h.01")

@Composable
private fun HomeRowView(
    row: HomeViewModel.Row,
    onOpen: (BaseItemDto) -> Unit,
    onSeeAll: (HomeViewModel.Row) -> Unit,
    onSeerr: (SeerrTitle) -> Unit,
    onMenu: ((BaseItemDto) -> Unit)? = null,
) {
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(row.title, Modifier.weight(1f), size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            if (row.row.kind != "mymedia") {
                SText(t("See all"), Modifier.tap { onSeeAll(row) }, size = 13.sp, weight = 600, color = SeedyColors.Text2)
            }
        }
        if (row.seerrRow) {
            // Discover and Upcoming movies: the Requests tab's posters.
            val titles = row.seerr
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (titles == null) {
                    items(4) { Box(Modifier.width(120.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface)) }
                } else {
                    items(titles, key = { "${it.mediaType}:${it.tmdbId}" }) { SeerrTitleCard(it, row.row.kind == "upcomingMovies", { onSeerr(it) }) }
                }
            }
            return@Column
        }
        val items = row.items
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (items == null) {
                items(3) { Box(Modifier.width(232.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) }
            } else {
                items(items, key = { it.id }) { item -> LandscapeCard(item, row.row.kind, onOpen, onMenu) }
            }
        }
    }
}

/** The board's 232 px landscape card: art with a tag and a progress bar, the title and a line under it. [onMenu]: a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandscapeCard(item: BaseItemDto, kind: String, onOpen: (BaseItemDto) -> Unit, onMenu: ((BaseItemDto) -> Unit)? = null) {
    val viewer = LocalViewer.current
    val progress = item.progress
    val haptics = LocalHapticFeedback.current
    val press = if (onMenu != null) {
        Modifier.combinedClickable(
            interactionSource = null, indication = null, onClick = { onOpen(item) },
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onMenu(item) },
        )
    } else {
        Modifier.tap { onOpen(item) }
    }
    Column(Modifier.width(232.dp).then(press), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp))) {
            Artwork(viewer.images?.landscape(item, 480, viewer.episodeImages), Modifier.matchParentSize())
            val tag = cardTag(item)
            if (tag.isNotEmpty()) {
                SText(
                    tag,
                    Modifier.padding(8.dp).clip(RoundedCornerShape(7.dp)).background(SeedyColors.ScrimSoft).padding(horizontal = 8.dp, vertical = 3.dp),
                    size = 11.sp, weight = 700, color = SeedyColors.Text, maxLines = 1,
                )
            }
            if (item.inProgress) {
                Box(Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.24f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(SeedyColors.White))
                }
            }
        }
        Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(item.cardTitle, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val meta = cardMeta(item, kind)
            if (meta.isNotEmpty()) SText(meta, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}

/** The corner tag: "S3 · E2" on episodes, "Movie" on films, nothing on the rest. */
private fun cardTag(item: BaseItemDto): String = when (item.type) {
    BaseItemKind.EPISODE -> item.episodeShort
    BaseItemKind.MOVIE -> t("Movie")
    BaseItemKind.SERIES -> t("Series")
    else -> ""
}

private fun cardMeta(item: BaseItemDto, kind: String): String = when {
    item.progress > 0f && item.userData?.played != true -> timeLeft(item.remainingSeconds)
    item.type == BaseItemKind.EPISODE -> item.name.orEmpty()
    kind == "mymedia" -> ""
    else -> item.yearRange
}
