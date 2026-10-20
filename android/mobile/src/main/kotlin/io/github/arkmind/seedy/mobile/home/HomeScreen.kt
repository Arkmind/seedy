package io.github.arkmind.seedy.mobile.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
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
import io.github.arkmind.seedy.core.seedy.FeaturedEntry
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
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
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.math.min

/** What the header's account menu offers. */
data class AccountActions(val onSwitchProfile: () -> Unit, val onSignOut: () -> Unit, val onSettings: () -> Unit = {})

/** Board "Android · Home": the featured hero (swipe for the next title), then the home rows. */
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
) {
    LazyColumn(
        Modifier.fillMaxSize().background(SeedyColors.Bg),
        state = listState,
        contentPadding = PaddingValues(bottom = bottomPadding + 24.dp),
    ) {
        item(key = "hero") {
            val featured = state.featured
            if (state.hasHero && !featured.isNullOrEmpty()) {
                Hero(featured, account, onOpen, onPlay, onSearch)
            } else {
                Box(Modifier.fillMaxWidth().height(if (state.hasHero && featured == null) 540.dp else 96.dp)) {
                    Header(account, onSearch)
                }
            }
        }
        for (row in state.visibleRows) {
            item(key = row.row.id) { HomeRowView(row, onOpen, onSeeAll) }
        }
    }
}

@Composable
private fun Header(account: AccountActions, onSearch: () -> Unit) {
    val viewer = LocalViewer.current
    val top = max(36.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 12.dp)
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = top),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BrandMark(viewer.brand, Modifier.weight(1f))
        HeaderButton(WebIcons.Search, onSearch, stroke = 2.2f)
        HeaderButton(WebIcons.Cast, {})
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
    account: AccountActions,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    onSearch: () -> Unit,
) {
    val pager = rememberPagerState { entries.size }
    Box(Modifier.fillMaxWidth().height(540.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page -> HeroPage(entries[page], onOpen, onPlay) }
        Box(
            Modifier.fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))),
        )
        Header(account, onSearch)
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
private fun HeroPage(entry: FeaturedEntry, onOpen: (BaseItemDto) -> Unit, onPlay: (BaseItemDto) -> Unit) {
    val viewer = LocalViewer.current
    val item = entry.item
    Box(Modifier.fillMaxSize()) {
        // The board crops the 16:9 backdrop to the right of centre (its viewBox starts 86% of the way across).
        viewer.images?.backdrop(item, 1280)?.let { url ->
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
                size = heroTitleSize(item.name.orEmpty(), maxWidth = LocalConfiguration.current.screenWidthDp.dp - 40.dp),
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

/** 40 px, or smaller so the title's longest word fits on one line (Noto Sans Black capitals run about 0.72 em). */
private fun heroTitleSize(title: String, maxWidth: Dp): TextUnit {
    val longest = title.split(Regex("\\s+")).maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
    return min(40f, maxWidth.value / (longest * 0.72f)).sp
}

@Composable
private fun HomeRowView(row: HomeViewModel.Row, onOpen: (BaseItemDto) -> Unit, onSeeAll: (HomeViewModel.Row) -> Unit) {
    Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            SText(row.title, Modifier.weight(1f), size = 16.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            if (row.row.kind != "mymedia") {
                SText(t("See all"), Modifier.tap { onSeeAll(row) }, size = 13.sp, weight = 600, color = SeedyColors.Text2)
            }
        }
        val items = row.items
        LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (items == null) {
                items(3) { Box(Modifier.width(232.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface)) }
            } else {
                items(items, key = { it.id }) { item -> LandscapeCard(item, row.row.kind, onOpen) }
            }
        }
    }
}

/** The board's 232 px landscape card: art with a tag and a progress bar, the title and a line under it. */
@Composable
private fun LandscapeCard(item: BaseItemDto, kind: String, onOpen: (BaseItemDto) -> Unit) {
    val viewer = LocalViewer.current
    val progress = item.progress
    Column(Modifier.width(232.dp).tap { onOpen(item) }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (progress > 0f && item.userData?.played != true) {
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
