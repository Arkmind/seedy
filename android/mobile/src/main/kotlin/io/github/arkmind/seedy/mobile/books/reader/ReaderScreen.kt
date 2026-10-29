package io.github.arkmind.seedy.mobile.books.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.books.PageTheme
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.IconPart
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import kotlin.math.roundToInt

/* The reader's frame (board "Android · Reader"): the top bar, the page, the scrubber with its two texts. Stateless, so
   the screenshot test draws it around the board's sample text; the page itself (epub.js, comic pages, a PDF) comes in
   as a slot. */

/** The reader's icons, from the board (24 grid, 2.2 px lines). */
object ReaderIcons {
    val Back = IconDef(2.2f, "M15 5l-7 7 7 7")
    val Type = IconDef(2.2f, "M4 18L9 6l5 12", "M6 14h6", "M15 18l3-7 3 7", "M16 16h4")
    val Bookmark = IconDef(2.2f, "M6 3h12v18l-6-4-6 4z")
    val BookmarkOn = IconDef(2.2f, listOf(IconPart("M6 3h12v18l-6-4-6 4z", filled = true)))
    val Contents = IconDef(2.2f, "M9 6h11", "M9 12h11", "M9 18h11", "M4 6h.01", "M4 12h.01", "M4 18h.01")
    val Close = IconDef(2.2f, "M6 6l12 12", "M18 6L6 18")
    val Download = IconDef(2.2f, "M12 3v12", "M7 10l5 5 5-5", "M5 20h14")
    val File = IconDef(2.2f, "M6 3h8l4 4v14H6z", "M14 3v4h4")
}

/** The frame's colours for each page colour (web reader.css .rd-theme-*; the dark one from the board). */
data class ReaderPalette(
    val bg: Color,
    val title: Color,
    val sub: Color,
    val icon: Color,
    val line: Color,
    val track: Color,
    val fill: Color,
    val knob: Color,
    val status: Color,
    val hover: Color,
    /** The page's own text (the settings' theme tiles, the board's sample). */
    val ink: Color,
    val heading: Color,
    val label: Color,
)

fun paletteOf(theme: PageTheme): ReaderPalette = when (theme) {
    PageTheme.Dark -> Dark
    PageTheme.Black -> Dark.copy(bg = Color(0xFF000000), line = Color(0xFF161616), track = Color(0xFF262626), ink = Color(0xFFC8C8C8), heading = Color(0xFFE6E6E6), label = Color(0xFF7A7A7A))
    PageTheme.Sepia -> ReaderPalette(
        bg = Color(0xFFF1E6D0), title = Color(0xFF2E2416), sub = Color(0xFF8A7454), icon = Color(0xFF4A3B28), line = Color(0xFFE2D4B8),
        track = Color(0xFFDDCDAF), fill = Color(0xFF6B5A40), knob = Color(0xFF4A3B28), status = Color(0xFF8A7454), hover = Color(0x144A3B28),
        ink = Color(0xFF4A3B28), heading = Color(0xFF2E2416), label = Color(0xFF8A7454),
    )
    PageTheme.Light -> ReaderPalette(
        bg = Color(0xFFFAFAFA), title = Color(0xFF111111), sub = Color(0xFF7A7A7A), icon = Color(0xFF2A2A2A), line = Color(0xFFECECEC),
        track = Color(0xFFE2E2E2), fill = Color(0xFF4A4A4A), knob = Color(0xFF1E1E1E), status = Color(0xFF7A7A7A), hover = Color(0x0F000000),
        ink = Color(0xFF1E1E1E), heading = Color(0xFF000000), label = Color(0xFF7A7A7A),
    )
}

private val Dark = ReaderPalette(
    bg = Color(0xFF111111), title = Color(0xFFFFFFFF), sub = Color(0xFF8C8C8C), icon = Color(0xFFE0E0E0), line = Color(0xFF1C1C1C),
    track = Color(0xFF2A2A2A), fill = Color(0xFFF0F0F0), knob = Color(0xFFF0F0F0), status = Color(0xFF8C8C8C), hover = Color(0x14FFFFFF),
    ink = Color(0xFFD8D4CC), heading = Color(0xFFF0ECE4), label = Color(0xFF8C8C8C),
)

/** What the frame shows around the page. */
data class ReaderChrome(
    val title: String,
    val sub: String = "",
    val theme: PageTheme = PageTheme.Dark,
    /** The scrubber, from 1; none when [total] is 0. */
    val page: Int = 0,
    val total: Int = 0,
    val left: String = "",
    val right: String = "",
    val rtl: Boolean = false,
    /** The Aa button (reading settings). */
    val settings: Boolean = false,
    /** The bookmark button: null for none, else whether this page is marked. */
    val bookmark: Boolean? = null,
    val contents: Boolean = false,
)

class ReaderActions(
    val onBack: () -> Unit,
    val onSettings: () -> Unit = {},
    val onBookmark: () -> Unit = {},
    val onContents: () -> Unit = {},
    /** The scrubber let go on a page (from 1). */
    val onSeek: (Int) -> Unit = {},
    /** What the scrubber's tip says while dragging. */
    val tip: (Int) -> String = { "" },
)

/** The frame's room above and below the text: the bars' height, with the system bars (steady while they hide). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun readerTop(): Dp = max(36.dp, WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding() + 12.dp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun readerBottom(): Dp = max(30.dp, WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues().calculateBottomPadding() + 12.dp)

/**
 * The reader: [page] fills the screen and gets the text column's padding (under the top bar, over the footer, as on the
 * board); the bars show while [shown].
 */
@Composable
fun ReaderScreen(chrome: ReaderChrome, shown: Boolean, actions: ReaderActions, page: @Composable (PaddingValues) -> Unit) {
    val p = paletteOf(chrome.theme)
    val top = readerTop()
    val bottom = readerBottom()
    Box(Modifier.fillMaxSize().background(p.bg)) {
        // Board: the text starts 112 px down and ends 120 px up (36 + 76 / 30 + 90).
        page(PaddingValues(top = top + 76.dp, bottom = bottom + 90.dp))
        AnimatedVisibility(
            shown, Modifier.align(Alignment.TopCenter),
            enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        ) { TopBar(chrome, p, top, actions) }
        if (chrome.total > 0) {
            AnimatedVisibility(
                shown, Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
            ) { Footer(chrome, p, bottom, actions) }
        }
    }
}

@Composable
private fun TopBar(c: ReaderChrome, p: ReaderPalette, top: Dp, a: ReaderActions) {
    Column(Modifier.fillMaxWidth().background(p.bg.copy(alpha = 0.96f)).tap {}) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = top, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            BarButton(ReaderIcons.Back, 22.dp, p.title, a.onBack)
            Column(Modifier.weight(1f).padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SText(c.title, size = 14.sp, weight = 800, color = p.title, maxLines = 1)
                if (c.sub.isNotEmpty()) SText(c.sub, size = 12.sp, color = p.sub, maxLines = 1)
            }
            if (c.settings) BarButton(ReaderIcons.Type, 21.dp, p.icon, a.onSettings)
            c.bookmark?.let { on ->
                BarButton(if (on) ReaderIcons.BookmarkOn else ReaderIcons.Bookmark, 20.dp, if (on) LocalAccent.current else p.icon, a.onBookmark)
            }
            if (c.contents) BarButton(ReaderIcons.Contents, 21.dp, p.icon, a.onContents)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
    }
}

@Composable
private fun BarButton(icon: IconDef, size: Dp, color: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        SIcon(icon, size = size, color = color)
    }
}

@Composable
private fun Footer(c: ReaderChrome, p: ReaderPalette, bottom: Dp, a: ReaderActions) {
    Column(Modifier.fillMaxWidth().background(p.bg.copy(alpha = 0.96f)).tap {}) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        Column(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = bottom),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Scrubber(c.page, c.total, c.rtl, p, a)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SText(c.left, Modifier.weight(1f, fill = false), size = 12.sp, weight = 600, color = p.status, maxLines = 1)
                SText(c.right, Modifier.weight(1f, fill = false).padding(start = 16.dp), size = 12.sp, weight = 600, color = p.status, maxLines = 1, align = TextAlign.End)
            }
        }
    }
}

/** The page slider: a 4 px track, the read part filled, a 6×16 knob; let go to jump. */
@Composable
private fun Scrubber(page: Int, total: Int, rtl: Boolean, p: ReaderPalette, a: ReaderActions) {
    var drag by remember { mutableStateOf<Int?>(null) }
    val shown = drag ?: page
    val fraction = if (total > 0) (shown.toFloat() / total).coerceIn(0f, 1f) else 0f
    val actions by rememberUpdatedState(a)
    val pageNow by rememberUpdatedState(page)
    BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
        val width = constraints.maxWidth.toFloat()
        fun valueAt(x: Float): Int {
            val f = (x / width).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
            return (1 + f * (total - 1)).roundToInt().coerceIn(1, total.coerceAtLeast(1))
        }
        Box(
            Modifier.fillMaxSize()
                .pointerInput(total, rtl) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = valueAt(it.x) },
                        onDragEnd = { drag?.let { v -> if (v != pageNow) actions.onSeek(v) }; drag = null },
                        onDragCancel = { drag = null },
                    ) { change, _ -> drag = valueAt(change.position.x) }
                }
                .pointerInput(total, rtl) {
                    detectTapGestures { o -> valueAt(o.x).let { v -> if (v != pageNow) actions.onSeek(v) } }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val x = if (rtl) 1f - fraction else fraction
            Box(
                Modifier.fillMaxWidth().height(4.dp).drawBehind {
                    val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                    drawRoundRect(p.track, cornerRadius = r)
                    val w = size.width * fraction
                    drawRoundRect(p.fill, topLeft = Offset(if (rtl) size.width - w else 0f, 0f), size = size.copy(width = w), cornerRadius = r)
                },
            )
            Box(
                Modifier.layout { m, cs ->
                    val pl = m.measure(cs)
                    layout(pl.width, pl.height) { pl.place((width * x - pl.width / 2f).roundToInt(), 0) }
                }.size(6.dp, 16.dp).clip(RoundedCornerShape(3.dp)).background(p.knob),
            )
        }
        drag?.let { v ->
            val tip = a.tip(v)
            if (tip.isNotEmpty()) {
                Box(
                    Modifier.wrapContentSize(unbounded = true).layout { m, cs ->
                        val pl = m.measure(cs.copy(minWidth = 0, minHeight = 0))
                        val cx = (width * (if (rtl) 1f - fraction else fraction)).roundToInt()
                        val left = (cx - pl.width / 2).coerceIn(0, (width.roundToInt() - pl.width).coerceAtLeast(0))
                        layout(0, 0) { pl.place(left, -pl.height - 6.dp.roundToPx()) }
                    }.clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1C1C)).padding(horizontal = 10.dp, vertical = 6.dp),
                ) { SText(tip, size = 12.sp, weight = 700, color = Color.White, maxLines = 1) }
            }
        }
    }
}

/** The spinner while a book opens (web .spinner: a 40 px ring, its top white). */
@Composable
fun ReaderSpinner(modifier: Modifier = Modifier, color: Color = Color.White) {
    val turn by rememberInfiniteTransition(label = "spin").animateFloat(
        0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "turn",
    )
    Canvas(modifier.size(40.dp).rotate(turn)) {
        val w = 3.dp.toPx()
        val inset = Offset(w / 2, w / 2)
        val s = size.copy(width = size.width - w, height = size.height - w)
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, topLeft = inset, size = s, style = Stroke(w))
        drawArc(color, -135f, 90f, false, topLeft = inset, size = s, style = Stroke(w, cap = StrokeCap.Butt))
    }
}

/** "Can't open" and loading states inside the frame (web .rd-message). */
@Composable
fun ReaderMessage(theme: PageTheme, title: String, text: String? = null, action: (@Composable () -> Unit)? = null) {
    val p = paletteOf(theme)
    Column(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = readerTop() + 53.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.hover), contentAlignment = Alignment.Center) {
            SIcon(ReaderIcons.File, size = 26.dp, color = p.icon)
        }
        SText(title, size = 20.sp, weight = 800, color = p.title, align = TextAlign.Center)
        if (!text.isNullOrEmpty()) {
            SText(text, Modifier.widthIn(max = 460.dp), size = 14.sp, color = p.sub, lineHeight = 1.55f, align = TextAlign.Center)
        }
        action?.let { Box(Modifier.padding(top = 8.dp)) { it() } }
    }
}

/** The message's button ("Download CBR", "Go to the audiobook"): the web's small primary button. */
@Composable
fun ReaderButton(label: String, onClick: () -> Unit, icon: IconDef? = null) {
    Row(
        Modifier.height(44.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFF0F0F0)).tap(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icon?.let { SIcon(it, size = 18.dp, color = Color(0xFF1E1E1E)) }
        SText(label, size = 14.sp, weight = 800, color = Color(0xFF1E1E1E), maxLines = 1)
    }
}

/** Centres the spinner in the text column. */
@Composable
fun ReaderLoading(padding: PaddingValues, theme: PageTheme) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        ReaderSpinner(color = paletteOf(theme).title)
    }
}
