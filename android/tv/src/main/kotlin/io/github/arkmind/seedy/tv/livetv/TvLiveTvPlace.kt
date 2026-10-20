package io.github.arkmind.seedy.tv.livetv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.compose.ContentFrame
import io.github.arkmind.seedy.core.feature.LiveTvViewModel
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.weekdayDayMonth
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.livetv.LiveChannel
import io.github.arkmind.seedy.core.livetv.LiveTv
import io.github.arkmind.seedy.player.LivePreview
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.nav.TvRoute
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.shell.RailInset
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.ChannelLogo
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalCore
import io.github.arkmind.seedy.ui.theme.LocalViewer
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

private val LiveRed = Color(0xFFD63C3C)
private val RecRed = Color(0xFFFF5C5C)
private val FilledHeart = IconDef(0f, listOf(IconPart("M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z", filled = true)))

/** The guide's span across the screen: two hours, four half-hour columns. */
private const val SpanMinutes = 120

/** Lets the guide's rows ignore focus; the screen scrolls the row in use to the top itself. */
@OptIn(ExperimentalFoundationApi::class)
private object NoFocusScroll : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/** The rail's Live TV: board "Android TV · Live TV guide". */
@Composable
fun TvLiveTvPlace() {
    val session = currentSession()
    val core = checkNotNull(LocalCore.current)
    val nav = LocalTvNav.current
    val context = LocalContext.current
    val vm = viewModel { LiveTvViewModel(session, core.store) }
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val preview = remember { LivePreview(context, session, scope) }
    DisposableEffect(preview) { onDispose { preview.release() } }

    var focused by remember { mutableStateOf<Pair<LiveChannel, BaseItemDto?>?>(null) }
    LaunchedEffect(focused?.first?.id) { preview.show(focused?.first?.item) }

    TvGuide(
        state = state,
        focused = focused,
        previewPlayer = { ContentFrame(player = preview.exo, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) },
        previewPlaying = preview.state.collectAsStateWithLifecycle().value == LivePreview.State.Playing,
        onFocus = { c, p -> focused = c to p },
        onWatch = { c -> vm.watched(c.id); nav.go(TvRoute.Player(c.id)) },
        onRecord = vm::toggleRecord,
        onFavoritesOnly = vm::setFavoritesOnly,
        onDay = vm::setDay,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvGuide(
    state: LiveTvViewModel.State,
    focused: Pair<LiveChannel, BaseItemDto?>?,
    previewPlayer: @Composable () -> Unit,
    previewPlaying: Boolean,
    onFocus: (LiveChannel, BaseItemDto?) -> Unit,
    onWatch: (LiveChannel) -> Unit,
    onRecord: (BaseItemDto) -> Unit,
    onFavoritesOnly: (Boolean) -> Unit,
    onDay: (LocalDate) -> Unit,
) {
    val accent = LocalAccent.current
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    // Minutes past the window's start shown at the grid's left edge (moves when the remote goes past either side).
    var offset by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.windowStart) { offset = 0 }
    val shown = state.shown

    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        // The focused programme: muted preview and what's on.
        Row(Modifier.padding(start = RailInset, end = 96.bp, top = 54.bp), horizontalArrangement = Arrangement.spacedBy(48.bp)) {
            Box(Modifier.width(600.bp).aspectRatio(16f / 9f).clip(RoundedCornerShape(22.bp)).background(Color(0xFF0B120E))) {
                val program = focused?.second
                val images = LocalViewer.current.images
                Artwork((program?.let { images?.landscape(it, 720) } ?: focused?.first?.item?.let { images?.backdrop(it, 720) }), Modifier.matchParentSize(), placeholder = Color(0xFF0B120E))
                if (focused != null) Box(Modifier.matchParentSize().then(if (previewPlaying) Modifier else Modifier.size(0.bp))) { previewPlayer() }
                Row(
                    Modifier.padding(18.bp).clip(RoundedCornerShape(10.bp)).background(LiveRed).padding(horizontal = 14.bp, vertical = 6.bp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.bp),
                ) {
                    Box(Modifier.size(8.bp).background(Color.White, CircleShape))
                    SText(t("Live"), size = 18.bsp, weight = 800, tracking = 0.06f, color = Color.White, uppercase = true)
                }
                if (previewPlaying) {
                    SText(
                        t("Muted preview"), Modifier.align(Alignment.BottomStart).padding(18.bp).clip(RoundedCornerShape(10.bp)).background(SeedyColors.ScrimSoft).padding(horizontal = 14.bp, vertical = 6.bp),
                        size = 18.bsp, weight = 700, color = SeedyColors.Text,
                    )
                }
            }
            focused?.let { (c, p) -> ProgramDetails(c, p, state.now) }
        }
        // Filters.
        Row(
            Modifier.padding(start = RailInset, end = 96.bp, top = 440.bp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.bp),
        ) {
            Pill({ onFavoritesOnly(!state.favoritesOnly) }) { ink ->
                SIcon(if (state.favoritesOnly) FilledHeart else WebIcons.Heart, size = 22.bp, color = if (state.favoritesOnly) accent else ink)
                SText(if (state.favoritesOnly) t("Favorites") else t("All channels"), size = 19.bsp, weight = 700, color = ink)
            }
            Pill({ onDay(if (state.day == LocalDate.now()) LocalDate.now().plusDays(1) else LocalDate.now()) }) { ink ->
                SText(dayLabel(state.day), size = 19.bsp, weight = 700, color = ink)
                SIcon(WebIcons.ChevronDown, size = 20.bp, color = ink.copy(alpha = 0.7f), stroke = 2.4f)
            }
            Spacer(Modifier.weight(1f))
            SText(t("Channel up and down move a page at a time"), size = 18.bsp, color = SeedyColors.Text3)
        }
        // The grid.
        BoxWithConstraints(
            Modifier.padding(start = RailInset, end = 96.bp, top = 512.bp).fillMaxSize().clip(RoundedCornerShape(topStart = 22.bp, topEnd = 22.bp)).background(SeedyColors.Panel),
        ) {
            val area = maxWidth - 300.bp
            val perMinute = area / SpanMinutes.toFloat()
            val nowMin = Duration.between(state.windowStart, state.now).toMinutes()
            Column {
                // The board's rows are 52 and 72 px plus a 2 px border under each.
                Row(Modifier.fillMaxWidth().height(54.bp).drawBehind { drawLine(Color(0xFF1F1F1F), Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2.bp.toPx()) }) {
                    Box(
                        Modifier.width(300.bp).fillMaxHeight().drawBehind { drawLine(Color(0xFF1F1F1F), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 2.bp.toPx()) }.padding(horizontal = 22.bp),
                        contentAlignment = Alignment.CenterStart,
                    ) { SText(weekdayDayMonth(state.day), size = 18.bsp, weight = 800, color = SeedyColors.White) }
                    Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
                        var m = (offset / 30) * 30
                        while (m < offset + SpanMinutes) {
                            val x = perMinute * (m - offset).toFloat()
                            Box(
                                Modifier.offset(x = x).fillMaxHeight().drawBehind { drawLine(Color(0xFF1F1F1F), Offset(0f, 0f), Offset(0f, size.height), 2.bp.toPx()) }.padding(start = 14.bp),
                                contentAlignment = Alignment.CenterStart,
                            ) { SText(LiveTv.clockOf(state.windowStart.plusMinutes(m)), size = 17.bsp, weight = 700, color = SeedyColors.Text2) }
                            m += 30
                        }
                        if (nowMin in offset until offset + SpanMinutes) {
                            SText(
                                LiveTv.clockOf(state.now),
                                Modifier.offset(x = perMinute * (nowMin - offset).toFloat() - 32.bp, y = 10.bp).clip(RoundedCornerShape(8.bp)).background(accent).padding(horizontal = 10.bp, vertical = 4.bp),
                                size = 18.bsp, weight = 800, color = SeedyColors.InkInverse,
                            )
                        }
                    }
                }
                CompositionLocalProvider(LocalBringIntoViewSpec provides NoFocusScroll) {
                    LazyColumn(
                        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val page = 5
                            when (e.key) {
                                Key.ChannelUp, Key.PageUp -> { scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex - page).coerceAtLeast(0)) }; true }
                                Key.ChannelDown, Key.PageDown -> { scope.launch { list.animateScrollToItem((list.firstVisibleItemIndex + page).coerceAtMost((shown.size - 1).coerceAtLeast(0))) }; true }
                                else -> false
                            }
                        },
                        state = list,
                    ) {
                        itemsIndexed(shown, key = { _, c -> c.id }) { index, c ->
                            GuideRow(
                                c = c, state = state, offset = offset, perMinute = perMinute, nowMin = nowMin,
                                focusedId = focused?.second?.id,
                                firstModifier = if (index == 0) Modifier.focusRequester(first) else Modifier,
                                onFocus = { p, start, end ->
                                    onFocus(c, p)
                                    // Keep the focused programme in view: past the right edge, start it at the left.
                                    if (end <= offset || start >= offset + SpanMinutes) offset = start.coerceAtLeast(0)
                                    else if (start < offset) offset = start.coerceAtLeast(0)
                                    scope.launch { list.animateScrollToItem(index) }
                                },
                                onWatch = { onWatch(c) },
                                onRecord = onRecord,
                            )
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(shown.isNotEmpty()) { if (shown.isNotEmpty()) first.focusSoon() }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    val short = weekdayDayMonth(day).substringBeforeLast(' ')
    return when (day) {
        today -> t("Today, {day}", "day" to short)
        today.plusDays(1) -> t("Tomorrow, {day}", "day" to short)
        else -> weekdayDayMonth(day)
    }
}

@Composable
private fun Pill(onClick: () -> Unit, content: @Composable (Color) -> Unit) {
    TvPressable(onClick) { f ->
        val ink = if (f) SeedyColors.InkInverse else SeedyColors.White
        Row(
            Modifier.height(52.bp).focusRing(f, radius = 15.bp, gap = 4.bp, ring = 4.bp).clip(RoundedCornerShape(15.bp))
                .background(if (f) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f)).padding(horizontal = 20.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp),
        ) { content(ink) }
    }
}

@Composable
private fun ProgramDetails(c: LiveChannel, p: BaseItemDto?, now: LocalDateTime) {
    Column(Modifier.padding(top = 8.bp), verticalArrangement = Arrangement.spacedBy(16.bp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp)) {
            ChannelLogo(c.item, 48.bp, 13.bp, 17.bsp)
            SText("${c.number} · ${c.label}", size = 21.bsp, weight = 700, color = SeedyColors.Text, maxLines = 1)
        }
        SText(p?.let(LiveTv::programTitle) ?: c.label, size = 44.bsp, weight = 900, lineHeight = 1.05f, color = SeedyColors.White, maxLines = 2)
        if (p != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.bp)) {
                SText(join(LiveTv.categoryOf(p), LiveTv.timeRange(p)), size = 19.bsp, color = SeedyColors.Text2)
                if (LiveTv.isAiring(p, now)) {
                    Box(Modifier.size(260.bp, 8.bp).clip(RoundedCornerShape(4.bp)).background(SeedyColors.Surface2)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(LiveTv.fraction(p, now)).background(Color.White))
                    }
                    SText(LiveTv.leftLabel(p, now), size = 19.bsp, weight = 700, color = SeedyColors.Text)
                }
            }
            p.overview?.let { SText(it, Modifier.width(860.bp), size = 21.bsp, lineHeight = 1.5f, color = SeedyColors.TextHero, maxLines = 2) }
        }
        SText(t("OK to watch · hold OK to record or open the channel"), size = 18.bsp, color = SeedyColors.Text3)
    }
}

@Composable
private fun GuideRow(
    c: LiveChannel,
    state: LiveTvViewModel.State,
    offset: Long,
    perMinute: androidx.compose.ui.unit.Dp,
    nowMin: Long,
    focusedId: java.util.UUID?,
    firstModifier: Modifier,
    onFocus: (BaseItemDto?, Long, Long) -> Unit,
    onWatch: () -> Unit,
    onRecord: (BaseItemDto) -> Unit,
) {
    val accent = LocalAccent.current
    val rowFocused = state.programs[c.id].orEmpty().any { it.id == focusedId }
    Row(Modifier.fillMaxWidth().height(74.bp).drawBehind { drawLine(SeedyColors.Row, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), 2.bp.toPx()) }) {
        Row(
            Modifier.width(300.bp).fillMaxHeight().background(if (rowFocused) Color(0xFF1A1A1A) else Color.Transparent)
                .drawBehind { drawLine(Color(0xFF1F1F1F), Offset(size.width - 1f, 0f), Offset(size.width - 1f, size.height), 2.bp.toPx()) }.padding(horizontal = 18.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.bp),
        ) {
            SText(c.number, Modifier.width(36.bp), size = 17.bsp, weight = 700, color = SeedyColors.Text3, maxLines = 1)
            ChannelLogo(c.item, 46.bp, 12.bp, 16.bsp)
            SText(c.label, size = 19.bsp, weight = 700, color = SeedyColors.White, maxLines = 1)
        }
        Box(Modifier.weight(1f).fillMaxHeight().clipToBounds()) {
            val programs = state.programs[c.id].orEmpty().ifEmpty { listOfNotNull(c.current) }
            programs.forEachIndexed { i, p ->
                val start = p.startDate?.let { Duration.between(state.windowStart, it).toMinutes() } ?: return@forEachIndexed
                val end = p.endDate?.let { Duration.between(state.windowStart, it).toMinutes() } ?: return@forEachIndexed
                if (end <= 0) return@forEachIndexed
                val a = start.coerceAtLeast(0)
                val left = perMinute * (a - offset).toFloat()
                val width = perMinute * (end - a).toFloat()
                val airing = LiveTv.isAiring(p, state.now)
                var longPressed by remember { mutableStateOf(false) }
                TvPressable(
                    onClick = { if (airing) onWatch() },
                    modifier = Modifier.offset(x = left).width(width).fillMaxHeight().padding(start = 3.bp, end = 3.bp, top = 6.bp, bottom = 8.bp)
                        .then(if (i == 0) firstModifier else Modifier)
                        .onFocusChanged { if (it.isFocused) onFocus(p, a, end) }
                        .onPreviewKeyEvent { e ->
                            val center = e.key == Key.DirectionCenter || e.key == Key.Enter
                            when {
                                center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 1 -> { longPressed = true; onRecord(p); true }
                                center && e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount > 1 -> true
                                center && e.type == KeyEventType.KeyUp && longPressed -> { longPressed = false; true }
                                else -> false
                            }
                        },
                ) { f ->
                    Column(
                        Modifier.fillMaxSize().clip(RoundedCornerShape(12.bp))
                            .background(if (f) SeedyColors.Inverse else if (airing) Color(0xFF232323) else SeedyColors.Row)
                            .padding(horizontal = 16.bp),
                        verticalArrangement = Arrangement.spacedBy(2.bp, Alignment.CenterVertically),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.bp)) {
                            if (LiveTv.recState(p, state.timers) != null) Box(Modifier.size(10.bp).background(RecRed, CircleShape))
                            SText(LiveTv.programTitle(p), size = 19.bsp, weight = 700, color = if (f) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
                        }
                        SText(LiveTv.timeRange(p), size = 15.bsp, color = if (f) Color(0xFF4A4A4A) else SeedyColors.Text3, maxLines = 1)
                    }
                }
            }
            // What has aired is dimmed; the accent line is now.
            if (nowMin > offset) {
                val w = perMinute * (nowMin - offset).coerceAtMost(SpanMinutes.toLong()).toFloat()
                Box(Modifier.width(w).fillMaxHeight().background(Color(0x800E0E0E)))
                if (nowMin < offset + SpanMinutes) Box(Modifier.offset(x = w - 1.bp).width(3.bp).fillMaxHeight().background(accent))
            }
        }
    }
}
