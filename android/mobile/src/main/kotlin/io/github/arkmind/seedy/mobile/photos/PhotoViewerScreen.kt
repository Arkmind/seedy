package io.github.arkmind.seedy.mobile.photos

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.photos.bytesLabel
import io.github.arkmind.seedy.core.photos.cameraName
import io.github.arkmind.seedy.core.photos.coordsLabel
import io.github.arkmind.seedy.core.photos.exposureLine
import io.github.arkmind.seedy.core.photos.fileName
import io.github.arkmind.seedy.core.photos.formatName
import io.github.arkmind.seedy.core.photos.hasPlace
import io.github.arkmind.seedy.core.photos.isVideo
import io.github.arkmind.seedy.core.photos.longDay
import io.github.arkmind.seedy.core.photos.ratioOf
import io.github.arkmind.seedy.core.photos.takenAt
import io.github.arkmind.seedy.core.photos.timeOfDay
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemDto
import kotlin.math.max
import kotlin.math.roundToInt

class ViewerActions(
    val onClose: () -> Unit = {},
    val onFavorite: (BaseItemDto) -> Unit = {},
    /** Null: this profile can't download. */
    val onDownload: ((BaseItemDto) -> Unit)? = null,
    val onPlayVideo: (BaseItemDto) -> Unit = {},
    val onSlideshow: (Boolean) -> Unit = {},
    val onInfo: (BaseItemDto) -> Unit = {},
    /** The viewer is near the end of what's loaded. */
    val onNearEnd: () -> Unit = {},
)

private val Glass = Color(0x14FFFFFF)

/**
 * The photo viewer on the phone (the web's Photos · Viewer board in the phone kit): the photo over black, swiped
 * left and right, pinched or double-tapped to zoom and dragged when zoomed; Close, its name, date and place in the
 * list on top; the neighbours in a strip and Favorite, Rotate, Slideshow, Download and Details below. A tap hides the
 * controls; the slideshow moves on every [every] ms (videos skipped) and hides them too.
 */
@Composable
fun PhotoViewerScreen(
    items: List<BaseItemDto>,
    start: Int,
    total: Int,
    playing: Boolean,
    actions: ViewerActions,
    modifier: Modifier = Modifier,
    every: Long = 5_000,
) {
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (items.size - 1).coerceAtLeast(0))) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val rotation = remember { mutableStateOf(mapOf<java.util.UUID, Int>()) }
    val page = pager.currentPage.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val item = items.getOrNull(page)

    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { p -> zoomed = false; if (p >= items.size - 6) actions.onNearEnd() } }
    LaunchedEffect(playing) { if (playing) chrome = false }
    // Slideshow: on to the next photo, round again at the end.
    LaunchedEffect(playing, page, items.size) {
        if (!playing || items.isEmpty()) return@LaunchedEffect
        delay(every)
        var next = page + 1
        while (next < items.size && items[next].isVideo) next++
        if (next >= items.size) next = items.indexOfFirst { !it.isVideo }.takeIf { it >= 0 } ?: return@LaunchedEffect
        pager.animateScrollToPage(next)
    }

    Box(modifier.fillMaxSize().background(Color(0xFF060606))) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, beyondViewportPageCount = 1, key = { items[it].id }) { p ->
            val it = items[p]
            ZoomPage(it, rotation.value[it.id] ?: 0, active = p == page, onZoom = { z -> if (p == page) zoomed = z }, onTap = { chrome = !chrome }, onPlay = { actions.onPlayVideo(it) })
        }
        if (chrome && item != null) {
            Box(Modifier.fillMaxWidth().height(140.dp).background(Brush.verticalGradient(listOf(Color(0xB3060606), Color(0x00060606)))))
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = topInset() - 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(PhotoIcons.Close, t("Close"), size = 18, onClick = actions.onClose)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(fileName(item), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
                    val taken = takenAt(item)
                    val sub = listOfNotNull(taken?.let { longDay(it.toLocalDate()) }, taken?.let(::timeOfDay), t("{n} of {total}", "n" to page + 1, "total" to max(total, items.size))).joinToString(" · ")
                    SText(sub, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
                }
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0x00060606), Color(0xCC060606))))
                    .padding(top = 28.dp, bottom = 14.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Strip(items, page) { pager.requestScrollToPage(it) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    val fav = item.userData?.isFavorite == true
                    GlassButton(if (fav) PhotoIcons.HeartFilled else PhotoIcons.Heart, if (fav) t("Remove from favorites") else t("Add to favorites"), tint = if (fav) LocalAccent.current else SeedyColors.White) { actions.onFavorite(item) }
                    GlassButton(PhotoIcons.Rotate, t("Rotate"), enabled = !item.isVideo) {
                        rotation.value = rotation.value + (item.id to (((rotation.value[item.id] ?: 0) + 270) % 360))
                    }
                    GlassButton(if (playing) PhotoIcons.Pause else PhotoIcons.Slideshow, if (playing) t("Pause slideshow") else t("Start slideshow")) { actions.onSlideshow(!playing) }
                    actions.onDownload?.let { dl -> GlassButton(PhotoIcons.Download, t("Download original")) { dl(item) } }
                    GlassButton(PhotoIcons.Info, t("Show details"), size = 20) { actions.onInfo(item) }
                }
            }
        }
    }
}

@Composable
private fun GlassButton(icon: IconDef, label: String, size: Int = 18, tint: Color = SeedyColors.White, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Glass).tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        SIcon(icon, size = size.dp, color = tint)
    }
}

/** The neighbours: 56 px thumbnails, the current one ringed, scrolled to stay in view. */
@Composable
private fun Strip(items: List<BaseItemDto>, page: Int, onPick: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = page)
    val density = LocalDensity.current
    // The current one in the middle of the strip.
    LaunchedEffect(page, state.layoutInfo.viewportSize.width) {
        val w = state.layoutInfo.viewportSize.width
        if (w == 0) return@LaunchedEffect
        val item = with(density) { (56 * ratioOf(items[page])).dp.roundToPx() }
        state.scrollToItem(page, -(w - item) / 2)
    }
    LazyRow(state = state, contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(items, key = { _, it -> it.id }) { i, it ->
            val cur = i == page
            Box(
                Modifier.padding(vertical = 3.dp).width((56 * ratioOf(it)).dp).height(56.dp).ring(cur).padding(if (cur) 3.dp else 0.dp)
                    .clip(RoundedCornerShape(if (cur) 5.dp else 8.dp)).alpha(if (cur) 1f else 0.55f).tap { onPick(i) },
            ) {
                PhotoImage(it, 56.dp, Modifier.fillMaxSize())
            }
        }
    }
}

/** One photo, fitted to the screen, pinch-zoomed and panned; a video shows its picture and a play button. */
@Composable
private fun ZoomPage(item: BaseItemDto, rotation: Int, active: Boolean, onZoom: (Boolean) -> Unit, onTap: () -> Unit, onPlay: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(active) { if (!active) { scale = 1f; offset = Offset.Zero } }
    val video = item.isVideo
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val turned = rotation % 180 != 0
        val ar = ratioOf(item).let { if (turned) 1f / it else it }
        val bw = constraints.maxWidth.toFloat()
        val bh = constraints.maxHeight.toFloat()
        val fw = minOf(bw, bh * ar)
        val fh = fw / ar
        fun clamp(o: Offset, s: Float): Offset {
            val mx = max(0f, (fw * s - bw) / 2f)
            val my = max(0f, (fh * s - bh) / 2f)
            return Offset(o.x.coerceIn(-mx, mx), o.y.coerceIn(-my, my))
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(video) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { p ->
                            if (!video) {
                                if (scale > 1.01f) { scale = 1f; offset = Offset.Zero } else {
                                    val c = p - Offset(bw / 2f, bh / 2f)
                                    scale = 2.5f
                                    offset = clamp(c * -1.5f, 2.5f)
                                }
                                onZoom(scale > 1.01f)
                            }
                        },
                    )
                }
                .pointerInput(video) {
                    if (video) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        do {
                            val event = awaitPointerEvent()
                            if (event.changes.size > 1 || scale > 1.01f) {
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid() - Offset(bw / 2f, bh / 2f)
                                val next = (scale * zoom).coerceIn(1f, 6f)
                                val k = next / scale
                                offset = clamp((offset - c) * k + c + pan, next)
                                scale = next
                                onZoom(scale > 1.01f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (scale < 1.01f) { scale = 1f; offset = Offset.Zero; onZoom(false) }
                    }
                },
        ) {
            val w = with(density) { (if (turned) fh else fw).toDp() }
            val h = with(density) { (if (turned) fw else fh).toDp() }
            Box(
                Modifier.align(Alignment.Center).size(w, h).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y; rotationZ = rotation.toFloat()
                }.clip(RoundedCornerShape(4.dp)),
            ) {
                ViewerImage(item, (bw * 1.0f).roundToInt(), (bh * 1.0f).roundToInt(), full = scale > 1.3f)
            }
            if (video) {
                Column(Modifier.align(Alignment.Center).tap(onClick = onPlay), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xB30E0E0E)), contentAlignment = Alignment.Center) {
                        SIcon(PhotoIcons.Play, Modifier.padding(start = 4.dp), size = 30.dp, color = SeedyColors.White)
                    }
                    SText(clockOf(ticksToSeconds(item.runTimeTicks)), size = 13.sp, weight = 700, color = SeedyColors.White)
                }
            }
        }
    }
}

@Composable
private fun ViewerImage(item: BaseItemDto, w: Int, h: Int, full: Boolean) {
    val art = LocalPhotoArt.current
    if (art != null) {
        art(item, Modifier.fillMaxSize())
        return
    }
    val api = LocalPhotosApi.current ?: return
    val screen = remember(item.id, w, h) { if (item.isVideo) api.thumbUrl(item, h) else api.screenUrl(item, w, h) }
    AsyncImage(model = screen, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
    // Zoomed in: the full-size photo over it once it's here.
    var wantFull by remember(item.id) { mutableStateOf(false) }
    if (full) wantFull = true
    if (wantFull && !item.isVideo) AsyncImage(model = api.screenUrl(item, w, h, full = true), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
}

/* ───────── Details ───────── */

@Composable
private fun Fact(icon: IconDef, title: String, sub: String?) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(icon, size = 19.dp, color = SeedyColors.TextHero)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(title, size = 14.sp, weight = 700, color = SeedyColors.White)
            if (!sub.isNullOrEmpty()) SText(sub, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        }
    }
}

/**
 * The Details panel's content (the viewer board's right column): date, camera and exposure, size and file, folder and
 * path, where it was taken (a drawn map with the pin; [onMap] opens it) and the folder it's in.
 */
@Composable
fun PhotoDetails(item: BaseItemDto, file: Pair<Long?, String?>?, cover: BaseItemDto?, onMap: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        val taken = takenAt(item)
        if (taken != null) Fact(PhotoIcons.Calendar, "${longDay(taken.toLocalDate())}, ${timeOfDay(taken)}", if (item.premiereDate != null) t("Camera’s local time") else t("Date the file was saved"))
        val camera = cameraName(item)
        if (camera.isNotEmpty()) Fact(PhotoIcons.Camera, camera, exposureLine(item).ifEmpty { null })
        val w = item.width
        val h = item.height
        val size = listOfNotNull(
            if (w != null && h != null) "$w × $h" else null,
            if (item.isVideo) clockOf(ticksToSeconds(item.runTimeTicks)) else if (w != null && h != null) "${(w.toLong() * h / 1_000_000.0).roundToInt()} MP" else null,
        ).joinToString(" · ")
        val fileLine = listOf(formatName(item, file?.second), bytesLabel(file?.first)).filter { it.isNotEmpty() }.joinToString(" · ")
        if (size.isNotEmpty() || fileLine.isNotEmpty()) Fact(if (item.isVideo) PhotoIcons.Video else PhotoIcons.File, size.ifEmpty { fileLine }, if (size.isNotEmpty()) fileLine else null)
        val folder = item.album ?: item.path?.split('/', '\\')?.dropLast(1)?.lastOrNull()
        if (!folder.isNullOrEmpty()) Fact(PhotoIcons.Folder, folder, item.path)
        if (hasPlace(item)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SText(t("Where it was taken"), size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.Text3)
                MapCard(item.latitude!!, item.longitude!!, onMap)
            }
        }
        if (item.albumId != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SeedyColors.Row).tap(onClick = onFolder).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface)) { PhotoImage(cover ?: item, 52.dp, Modifier.fillMaxSize()) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SText(t("In folder"), size = 12.sp, color = SeedyColors.Text3)
                    SText(item.album.orEmpty(), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                }
                SIcon(PhotoIcons.ChevronRight, size = 16.dp, color = SeedyColors.Text)
            }
        }
    }
}

/** The board's drawn map: water, a few streets and the pin in the accent, with the coordinates. */
@Composable
private fun MapCard(lat: Double, lon: Double, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val water = remember { PathParser().parsePathString("M0 104 Q80 92 150 110 T312 100 V150 H0Z").toPath() }
    val streets = remember { PathParser().parsePathString("M20 0 L60 150 M120 0 L95 150 M210 0 L240 150 M0 40 H312 M0 78 H312").toPath() }
    Box(Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1A2229)).tap(onClick = onClick)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = max(size.width / 312f, size.height / 150f)
            translate((size.width - 312f * k) / 2f, (size.height - 150f * k) / 2f) {
                scale(k, k, pivot = Offset.Zero) {
                    drawPath(water, Color(0xFF173447))
                    drawPath(streets, Color(0xFF26313A), style = Stroke(width = 3f))
                    drawCircle(accent.copy(alpha = 0.25f), 16f, Offset(150f, 62f))
                    drawCircle(accent, 7f, Offset(150f, 62f))
                }
            }
        }
        SText(
            coordsLabel(lat, lon),
            Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp).clip(CircleShape).background(Color(0xCC0E0E0E)).padding(horizontal = 9.dp, vertical = 3.dp),
            size = 12.sp, weight = 700, color = SeedyColors.Text,
        )
    }
}
