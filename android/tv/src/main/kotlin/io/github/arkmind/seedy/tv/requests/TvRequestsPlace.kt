package io.github.arkmind.seedy.tv.requests

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.feature.RequestsViewModel
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.requests.RequestFormat
import io.github.arkmind.seedy.core.requests.RequestState
import io.github.arkmind.seedy.core.requests.SeerrRequest
import io.github.arkmind.seedy.core.requests.SeerrTitle
import io.github.arkmind.seedy.core.requests.TitlesKind
import io.github.arkmind.seedy.core.seedy.libraryUuid
import io.github.arkmind.seedy.core.seedy.tmdbImage
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.nav.TvNav
import io.github.arkmind.seedy.tv.nav.TvRoute
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale

/** What OK does on a Discover card. */
enum class TitleAction { Open, Details }

/** What the Requests place does; the route wires these to the view model and navigation. */
class TvRequestsActions(
    val onSearch: () -> Unit = {},
    val onTitle: (SeerrTitle) -> Unit = {},
    val onRequest: (SeerrRequest) -> Unit = {},
    val onMessageShown: () -> Unit = {},
)

/** Board "Android TV · Requests" (the rail's Requests place). */
@Composable
fun TvRequestsPlace(onSearch: () -> Unit) {
    val session = currentSession()
    val nav = LocalTvNav.current
    val vm = viewModel { RequestsViewModel(session) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }
    TvRequestsScreen(
        state,
        TvRequestsActions(
            onSearch = onSearch,
            onTitle = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) },
            onRequest = { nav.openSeerr(it.mediaType, it.tmdbId, it.jellyfinItemId) },
            onMessageShown = vm::clearMessage,
        ),
    )
}

/** A Seerr title opens its library page when it's on the server, else its Seerr page. */
fun TvNav.openSeerr(mediaType: String, tmdbId: Int, jellyfinItemId: String?) {
    val local = libraryUuid(jellyfinItemId)
    go(if (local != null) TvRoute.Details(local) else TvRoute.SeerrTitle(mediaType, tmdbId))
}

/** OK never requests: a title opens on the server, or on its Seerr page, where it can be requested once seen. */
fun titleAction(title: SeerrTitle): TitleAction =
    if (title.status == "available" && title.jellyfinItemId != null) TitleAction.Open else TitleAction.Details

/** Space kept around a card row so the focused card's ring (9 px) isn't clipped. */
private val RingRoom = 12.bp

/** The list scrolls itself to the row in use (as on Home), so it ignores focus. */
@OptIn(ExperimentalFoundationApi::class)
private object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * The title and Search Seerr; the person's requests as wide cards with where each one is; then Discover's rows of
 * titles, 2:3 cards with their kind and status, the focused one wider with what OK does.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRequestsScreen(
    state: RequestsViewModel.State,
    actions: TvRequestsActions,
    today: LocalDate = LocalDate.now(),
    firstCard: FocusRequester = remember { FocusRequester() },
) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var activeRow by remember { mutableIntStateOf(0) }
    val mine = state.latest.orEmpty()
    val rows = state.rows.filter { it.items?.isEmpty() != true }
    // List items: 0 the header, 1 the person's requests (when there are any), then the rows.
    val rowBase = if (mine.isEmpty()) 1 else 2
    fun focusAt(index: Int) {
        scope.launch { list.animateScrollToItem(if (index <= rowBase) 0 else index) }
    }
    val cardRows = LocalBringIntoViewSpec.current
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScroll) {
            LazyColumn(
                Modifier.fillMaxSize(),
                state = list,
                contentPadding = PaddingValues(start = RailInset, top = 72.bp, bottom = 300.bp),
                verticalArrangement = Arrangement.spacedBy(40.bp - RingRoom * 2),
            ) {
                item(key = "head") {
                    Row(Modifier.fillMaxWidth().padding(end = 96.bp, bottom = RingRoom), verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.bp)) {
                            SText(t("Requests"), size = 44.bsp, weight = 900, lineHeight = 1f, color = SeedyColors.White, maxLines = 1)
                            SText(t("From Seerr · ask for anything the server doesn’t have"), size = 19.bsp, color = SeedyColors.Text3, maxLines = 1)
                        }
                        SearchButton(actions.onSearch, Modifier.onFocusChanged { if (it.isFocused) focusAt(0) })
                    }
                }
                if (mine.isNotEmpty()) {
                    item(key = "mine") {
                        Column(Modifier.padding(top = RingRoom), verticalArrangement = Arrangement.spacedBy(16.bp - RingRoom)) {
                            SText(t("Your requests"), size = 24.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                            CompositionLocalProvider(LocalBringIntoViewSpec provides cardRows) {
                                LazyRow(
                                    Modifier.offset(x = -RingRoom),
                                    horizontalArrangement = Arrangement.spacedBy(24.bp),
                                    contentPadding = PaddingValues(start = RingRoom, end = 96.bp, top = RingRoom, bottom = RingRoom),
                                ) {
                                    items(mine, key = { "req:${it.requestId}" }) { r ->
                                        RequestCard(r, today, { actions.onRequest(r) }, Modifier.onFocusChanged { if (it.isFocused) { activeRow = -1; focusAt(1) } })
                                    }
                                }
                            }
                        }
                    }
                }
                itemsIndexed(rows, key = { _, r -> "row:${r.kind.key}" }) { i, row ->
                    val index = rowBase + i
                    Column(Modifier.padding(top = RingRoom), verticalArrangement = Arrangement.spacedBy(16.bp - RingRoom)) {
                        SText(if (row.kind == TitlesKind.Trending) t("Trending on Seerr") else t(row.kind.title), size = 24.bsp, weight = 800, color = if (activeRow == i) SeedyColors.White else SeedyColors.TextHero, maxLines = 1)
                        val items = row.items
                        CompositionLocalProvider(LocalBringIntoViewSpec provides cardRows) {
                            LazyRow(
                                Modifier.offset(x = -RingRoom),
                                horizontalArrangement = Arrangement.spacedBy(24.bp),
                                verticalAlignment = Alignment.Top,
                                contentPadding = PaddingValues(start = RingRoom, end = 96.bp, top = RingRoom, bottom = RingRoom),
                            ) {
                                if (items == null) {
                                    items(7) { Box(Modifier.width(196.bp).aspectRatio(2f / 3f).clip(RoundedCornerShape(16.bp)).background(SeedyColors.Surface)) }
                                } else {
                                    itemsIndexed(items, key = { _, it -> "${it.mediaType}:${it.tmdbId}" }) { j, title ->
                                        TitleCard(
                                            title, row.kind.upcoming, titleAction(title), today = today,
                                            onClick = { actions.onTitle(title) },
                                            modifier = (if (i == 0 && j == 0) Modifier.focusRequester(firstCard) else Modifier)
                                                .onFocusChanged { if (it.isFocused) { activeRow = i; focusAt(index) } },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        state.message?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(3200)
                actions.onMessageShown()
            }
            SText(
                message,
                Modifier.align(Alignment.BottomStart).padding(start = RailInset, bottom = 48.bp).clip(RoundedCornerShape(16.bp)).background(SeedyColors.Surface)
                    .padding(horizontal = 24.bp, vertical = 16.bp),
                size = 20.bsp, weight = 600, color = SeedyColors.Text,
            )
        }
    }
    val firstLoaded = rows.firstOrNull()?.items?.firstOrNull()
    LaunchedEffect(firstLoaded != null) { if (firstLoaded != null) firstCard.focusSoon() }
}

/** The board's 60 px "Search Seerr" button, translucent at rest, light when focused. */
@Composable
private fun SearchButton(onClick: () -> Unit, modifier: Modifier) {
    TvPressable(onClick, modifier) { focused ->
        val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
        Row(
            Modifier.focusScale(focused, 1.04f).height(60.bp).clip(RoundedCornerShape(17.bp))
                .background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f)).padding(horizontal = 24.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp),
        ) {
            SIcon(WebIcons.Search, size = 24.bp, color = ink, stroke = 2.2f)
            SText(t("Search Seerr"), size = 20.bsp, weight = 700, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun toneColour(tone: RequestState.Tone): Color = when (tone) {
    RequestState.Tone.Ok -> SeedyColors.Ok
    RequestState.Tone.Pending -> SeedyColors.Pending
    RequestState.Tone.Accent -> LocalAccent.current
    RequestState.Tone.Danger -> SeedyColors.Danger
    RequestState.Tone.Muted -> SeedyColors.Text3
}

/** The board's 500 px request card: poster, title, what was asked and when, where it is (and its download bar). */
@Composable
private fun RequestCard(r: SeerrRequest, today: LocalDate, onClick: () -> Unit, modifier: Modifier) {
    val state = RequestFormat.state(r)
    TvPressable(onClick, modifier) { focused ->
        Row(
            Modifier.width(500.bp).focusRing(focused, radius = 18.bp).clip(RoundedCornerShape(18.bp)).background(Color(0xFF161616)).padding(14.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.bp),
        ) {
            Box(Modifier.size(72.bp, 108.bp).clip(RoundedCornerShape(10.bp)).background(SeedyColors.Surface2)) {
                Artwork(tmdbImage(r.posterPath, "w185"), Modifier.matchParentSize(), placeholder = SeedyColors.Surface2)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.bp)) {
                SText(r.title, size = 21.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                SText(RequestFormat.line(r, everyone = false, today), size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
                val ink = toneColour(state.tone)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.bp)) {
                    Box(Modifier.size(9.bp).background(ink, CircleShape))
                    SText(state.text, size = 16.bsp, weight = 700, color = ink, maxLines = 1)
                }
                state.fraction?.let { f ->
                    Box(Modifier.fillMaxWidth().height(5.bp).clip(RoundedCornerShape(3.bp)).background(SeedyColors.Surface2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(LocalAccent.current))
                    }
                }
            }
        }
    }
}

/**
 * The board's 2:3 Discover card (196 px, 224 px focused inside the ring): its kind, where it is with Seerr, the title
 * and its year and score (an upcoming one's date), and what OK does when it's focused.
 */
@Composable
fun TitleCard(
    title: SeerrTitle,
    upcoming: Boolean,
    action: TitleAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
) {
    TvPressable(onClick, modifier) { focused ->
        val width by animateDpAsState(if (focused) 224.bp else 196.bp, label = "card")
        Column(Modifier.width(width), verticalArrangement = Arrangement.spacedBy(10.bp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).focusRing(focused, radius = 16.bp).clip(RoundedCornerShape(16.bp)).background(SeedyColors.Surface)) {
                val poster = tmdbImage(title.posterPath, "w342")
                if (poster == null) {
                    SText(
                        title.title, Modifier.align(Alignment.BottomStart).padding(start = 14.bp, end = 14.bp, bottom = 16.bp),
                        size = 20.bsp, weight = 900, lineHeight = 0.95f, uppercase = true, color = SeedyColors.White, maxLines = 3,
                    )
                } else {
                    Artwork(poster, Modifier.matchParentSize())
                }
                val (label, bg) = when {
                    upcoming -> t("Upcoming") to SeedyColors.TypeUpcoming
                    title.mediaType == "tv" -> t("Series") to SeedyColors.TypeSeries
                    else -> t("Movie") to SeedyColors.TypeMovie
                }
                SText(
                    label, Modifier.padding(10.bp).clip(CircleShape).background(bg).padding(horizontal = 11.bp, vertical = 4.bp),
                    size = 13.bsp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.White, maxLines = 1,
                )
                val status = when (title.status) {
                    "pending", "processing" -> t("Requested") to SeedyColors.Pending
                    "partial" -> t("Partly on the server") to SeedyColors.Ok
                    "available" -> t("On the server") to SeedyColors.Ok
                    else -> null
                }
                if (status != null) {
                    Row(
                        Modifier.padding(start = 10.bp, top = 46.bp).clip(CircleShape).background(SeedyColors.Scrim).padding(horizontal = 10.bp, vertical = 4.bp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.bp),
                    ) {
                        Box(Modifier.size(7.bp).background(status.second, CircleShape))
                        SText(status.first, size = 13.bsp, weight = 700, color = SeedyColors.Text, maxLines = 1)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.bp)) {
                SText(title.title, size = if (focused) 20.bsp else 18.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                val (meta, soon) = if (upcoming) RequestFormat.upcoming(title, today) else join(title.year, title.rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.US, "%.1f", it) }) to false
                SText(meta, size = 16.bsp, color = if (soon) LocalAccent.current else SeedyColors.Text3, maxLines = 1)
                if (focused) {
                    val hint = when (action) {
                        TitleAction.Open -> t("OK to open")
                        TitleAction.Details -> t("OK for details")
                    }
                    SText(hint, size = 16.bsp, weight = 700, color = LocalAccent.current, maxLines = 1)
                }
            }
        }
    }
}
