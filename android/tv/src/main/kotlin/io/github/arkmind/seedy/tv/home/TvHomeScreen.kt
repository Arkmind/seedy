package io.github.arkmind.seedy.tv.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.feature.HomeViewModel
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.tv.requests.titleAction
import io.github.arkmind.seedy.tv.requests.TitleCard
import io.github.arkmind.seedy.core.format.cardTitle
import io.github.arkmind.seedy.core.format.episodeShort
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.progress
import io.github.arkmind.seedy.core.format.rating
import io.github.arkmind.seedy.core.format.remainingSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.format.year
import io.github.arkmind.seedy.core.format.yearRange
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import kotlin.math.min

/** Space kept around a card row so the focused card's ring isn't clipped (ring 9 px, plus the focused card's growth). */
private val RingRoom = 12.bp

/** Lets the home rows' list ignore focus; [TvHomeScreen] scrolls it to the row in use itself. */
@OptIn(ExperimentalFoundationApi::class)
private object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** What the hero shows: a title and why it's there (the focused card's row, or Seedy's featured reason). */
private data class HeroPick(val item: BaseItemDto, val reason: String)

/**
 * Board "Android TV · Home": the hero describes whatever the remote is on, over that title's backdrop; the rows sit
 * under it and scroll so the row in use is always the first one.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvHomeScreen(
    state: HomeViewModel.State,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit = {},
    firstCard: FocusRequester = remember { FocusRequester() },
    onSeerr: (SeerrTitle) -> Unit = {},
) {
    val rows = state.visibleRows
    var pick by remember { mutableStateOf<HeroPick?>(null) }
    var focusedRow by remember { mutableIntStateOf(0) }
    val featured = state.featured?.firstOrNull()?.let { HeroPick(it.item, it.reason) }
    val firstLoaded = rows.firstOrNull()?.items?.firstOrNull()
    val hero = pick ?: featured ?: firstLoaded?.let { HeroPick(it, rows.first().title) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        Backdrop(hero?.item)
        Column(Modifier.fillMaxSize().padding(start = RailInset, top = 104.bp), verticalArrangement = Arrangement.spacedBy(44.bp - RingRoom)) {
            Hero(hero)
            // Rows are 44 px apart on the board; each card row keeps 12 px above and below for the focus ring, taken from the gaps.
            // The list doesn't follow focus by itself (it would scroll just enough to show the card, leaving the row
            // above half in view): the row in use is scrolled to the top instead, title and all.
            val cardRows = LocalBringIntoViewSpec.current
            CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScroll) {
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(44.bp - RingRoom * 2), contentPadding = PaddingValues(bottom = 200.bp)) {
                itemsIndexed(rows, key = { _, r -> r.row.id }) { index, row ->
                    TvRow(
                        row = row,
                        active = index == focusedRow,
                        firstCard = if (index == 0) firstCard else null,
                        onFocus = { item ->
                            // Seerr's titles aren't in the library, so the hero stays on the last one that is.
                            if (item != null) pick = HeroPick(item, row.title)
                            if (focusedRow != index) {
                                focusedRow = index
                                scope.launch { list.animateScrollToItem(index) }
                            }
                        },
                        onOpen = onOpen,
                        onPlay = onPlay,
                        onSeerr = onSeerr,
                        cardScroll = cardRows,
                    )
                }
            }
            }
        }
    }
    LaunchedEffect(firstLoaded != null) { if (firstLoaded != null) firstCard.focusSoon() }
}

/** The focused title's backdrop at the top right, faded into the page on its left and bottom. */
@Composable
private fun Backdrop(item: BaseItemDto?) {
    val viewer = LocalViewer.current
    Crossfade(item?.takeIf { viewer.backdrops }?.let { viewer.images?.backdrop(it, 1920) }, label = "backdrop") { url ->
        Box(Modifier.fillMaxSize()) {
            if (url != null) {
                AsyncImage(url, null, Modifier.align(Alignment.TopEnd).size(1480.bp, 760.bp), contentScale = ContentScale.Crop)
            }
        }
    }
    Box(
        Modifier.size(1300.bp, 760.bp).background(
            Brush.horizontalGradient(
                0f to SeedyColors.Bg, 0.34f to SeedyColors.Bg, 0.6f to SeedyColors.Bg.copy(alpha = 0.7f), 1f to Color.Transparent,
            ),
        ),
    )
    Box(
        Modifier.offset(y = 400.bp).fillMaxWidth().height(360.bp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, SeedyColors.Bg))),
    )
}

@Composable
private fun Hero(pick: HeroPick?) {
    Column(Modifier.width(900.bp).height(272.bp), verticalArrangement = Arrangement.spacedBy(14.bp)) {
        if (pick == null) return@Column
        val item = pick.item
        val accent = LocalAccent.current
        if (pick.reason.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                Box(Modifier.size(8.bp).background(accent, CircleShape))
                SText(t(pick.reason), size = 18.bsp, weight = 800, tracking = 0.1f, color = accent, uppercase = true, maxLines = 1)
            }
        }
        val title = item.cardTitle
        SText(title, size = heroSize(title), weight = 900, lineHeight = 0.9f, tracking = -0.02f, color = SeedyColors.White, uppercase = true, maxLines = 2)
        val lead = if (item.type == BaseItemKind.EPISODE) join(item.episodeShort, item.name) else item.yearRange
        val rest = join(if (item.type == BaseItemKind.EPISODE) item.year else null, item.rating, item.genres?.firstOrNull())
        BasicText(
            buildAnnotatedString {
                append(lead)
                if (rest.isNotEmpty()) withStyle(SpanStyle(color = SeedyColors.Text2, fontWeight = FontWeight(500))) { append(if (lead.isEmpty()) rest else " · $rest") }
            },
            style = BaseText.copy(fontSize = 22.bsp, fontWeight = FontWeight(600), color = SeedyColors.Text),
            maxLines = 1,
        )
        item.overview?.takeIf { it.isNotBlank() }?.let {
            SText(it, Modifier.width(820.bp), size = 22.bsp, lineHeight = 1.5f, color = SeedyColors.TextHero, maxLines = 2)
        }
        val progress = item.progress
        if (progress > 0f && item.userData?.played != true) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp)) {
                Box(Modifier.size(300.bp, 6.bp).clip(RoundedCornerShape(3.bp)).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(SeedyColors.White))
                }
                SText(timeLeft(item.remainingSeconds), size = 18.bsp, color = SeedyColors.Text2)
            }
        }
    }
}

/**
 * 80 px, or smaller (down to 52 px) so the title fits the hero's 900 px on one line: two lines would push the
 * overview out of the hero. Noto Sans Black capitals run about 0.72 em.
 */
private fun heroSize(title: String): TextUnit {
    val fit = 900f / (title.length.coerceAtLeast(4) * 0.72f)
    return (fit.coerceIn(52f, 80f) / 2f).sp
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TvRow(
    row: HomeViewModel.Row,
    active: Boolean,
    firstCard: FocusRequester?,
    onFocus: (BaseItemDto?) -> Unit,
    onOpen: (BaseItemDto) -> Unit,
    onPlay: (BaseItemDto) -> Unit,
    onSeerr: (SeerrTitle) -> Unit,
    cardScroll: BringIntoViewSpec,
) {
    Column(Modifier.padding(top = RingRoom), verticalArrangement = Arrangement.spacedBy(18.bp - RingRoom)) {
        SText(row.title, size = 24.bsp, weight = 800, color = if (active) SeedyColors.White else SeedyColors.TextHero, maxLines = 1)
        val items = row.items.orEmpty()
        CompositionLocalProvider(LocalBringIntoViewSpec provides cardScroll) {
        // Room on the left too, so the first card's ring isn't cut.
        LazyRow(
            Modifier.offset(x = -RingRoom),
            horizontalArrangement = Arrangement.spacedBy(28.bp),
            // Posters grow when focused: keep their tops in line, as on the Requests place.
            verticalAlignment = if (row.seerrRow) Alignment.Top else Alignment.CenterVertically,
            contentPadding = PaddingValues(start = RingRoom, end = 96.bp, top = RingRoom, bottom = RingRoom),
        ) {
            if (row.seerrRow) {
                // Discover and Upcoming movies: the Requests place's posters; OK opens the title.
                itemsIndexed(row.seerr.orEmpty(), key = { _, it -> "${it.mediaType}:${it.tmdbId}" }) { i, title ->
                    TitleCard(
                        title, row.row.kind == "upcomingMovies",
                        titleAction(title),
                        onClick = { onSeerr(title) },
                        modifier = (if (i == 0 && firstCard != null) Modifier.focusRequester(firstCard) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocus(null) },
                    )
                }
            }
            itemsIndexed(items, key = { _, it -> it.id }) { i, item ->
                TvCard(
                    item = item,
                    kind = row.row.kind,
                    modifier = if (i == 0 && firstCard != null) Modifier.focusRequester(firstCard) else Modifier,
                    onFocus = { onFocus(item) },
                    onClick = { onOpen(item) },
                    onPlay = { onPlay(item) },
                )
            }
        }
        }
    }
}

/** The board's 384 px landscape card; the focused one widens to 420 px inside the white ring. */
@Composable
private fun TvCard(item: BaseItemDto, kind: String, onFocus: () -> Unit, onClick: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val viewer = LocalViewer.current
    // The remote's Play key starts the title straight away; OK opens its page.
    val playable = item.type in setOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE, BaseItemKind.VIDEO, BaseItemKind.TV_CHANNEL)
    val keys = Modifier.onKeyEvent { e ->
        if (playable && e.type == KeyEventType.KeyDown && (e.key == Key.MediaPlay || e.key == Key.MediaPlayPause)) { onPlay(); true } else false
    }
    TvPressable(onClick, modifier.onFocusChanged { if (it.isFocused) onFocus() }.then(keys)) { focused ->
        val width by animateDpAsState(if (focused) 420.bp else 384.bp, label = "card")
        Box(
            Modifier.width(width).height(width * 9f / 16f).focusRing(focused, radius = 18.bp, gap = 4.bp, ring = 5.bp)
                .clip(RoundedCornerShape(18.bp)),
        ) {
            Artwork(viewer.images?.landscape(item, 640, viewer.episodeImages), Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color(0xD90A0A0A)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 20.bp, end = 20.bp, bottom = 22.bp), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(item.cardTitle, size = 22.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                val meta = cardMeta(item, kind)
                if (meta.isNotEmpty()) SText(meta, size = 16.bsp, color = SeedyColors.TextHero, maxLines = 1)
            }
            val progress = item.progress
            if (progress > 0f && item.userData?.played != true) {
                Box(
                    Modifier.align(Alignment.BottomCenter).padding(start = 20.bp, end = 20.bp, bottom = 10.bp).fillMaxWidth().height(5.bp)
                        .clip(RoundedCornerShape(3.bp)).background(Color.White.copy(alpha = 0.24f)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(SeedyColors.White))
                }
            }
        }
    }
}

/** "S3 · E2 · 34 min left", "1 h 12 min left", "Series · 2023". */
private fun cardMeta(item: BaseItemDto, kind: String): String {
    val inProgress = item.progress > 0f && item.userData?.played != true
    return when {
        item.type == BaseItemKind.EPISODE -> join(item.episodeShort, if (inProgress) timeLeft(item.remainingSeconds) else item.name)
        inProgress -> timeLeft(item.remainingSeconds)
        kind == "mymedia" -> ""
        item.type == BaseItemKind.SERIES -> join(t("Series"), item.yearRange)
        item.type == BaseItemKind.MOVIE -> join(t("Movie"), item.year)
        else -> item.yearRange
    }
}
