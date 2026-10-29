package io.github.arkmind.seedy.mobile.books.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.imageLoader
import coil3.request.ImageRequest
import io.github.arkmind.seedy.core.books.ComicSettings
import io.github.arkmind.seedy.core.books.Fit
import io.github.arkmind.seedy.core.books.PdfPages
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/* Comics (CBZ pages from the server) and PDFs (drawn on the phone): one page at a time, swiped or tapped (the left and
   right thirds turn, the middle shows or hides the bars), or one long strip (Scroll). Right to left for manga. */

/** Where a tap on the page goes. */
private fun Modifier.pageTaps(rtl: Boolean, onPrev: () -> Unit, onNext: () -> Unit, onMiddle: () -> Unit): Modifier = pointerInput(rtl) {
    detectTapGestures { o ->
        val third = size.width / 3f
        when {
            o.x < third -> if (rtl) onNext() else onPrev()
            o.x > size.width - third -> if (rtl) onPrev() else onNext()
            else -> onMiddle()
        }
    }
}

/** The pager follows [index] (the scrubber) and reports the page it settles on. */
@Composable
private fun PagerSync(pager: PagerState, index: Int, onIndex: (Int) -> Unit, onTurn: () -> Unit) {
    val report by rememberUpdatedState(onIndex)
    val turned by rememberUpdatedState(onTurn)
    val current by rememberUpdatedState(index)
    LaunchedEffect(index) { if (pager.currentPage != index && !pager.isScrollInProgress) pager.scrollToPage(index) }
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { p ->
            if (p != current) turned()
            report(p)
        }
    }
}

@Composable
fun ComicPages(count: Int, index: Int, settings: ComicSettings, pageUrl: (Int) -> String, onIndex: (Int) -> Unit, onTap: () -> Unit, onTurn: () -> Unit) {
    val context = LocalContext.current
    // Keep the next pages coming.
    LaunchedEffect(index, settings.scroll) {
        if (settings.scroll) return@LaunchedEffect
        for (n in index + 2..index + 3) if (n < count) context.imageLoader.enqueue(ImageRequest.Builder(context).data(pageUrl(n)).build())
    }
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    if (settings.scroll) {
        val list = rememberLazyListState(initialFirstVisibleItemIndex = index)
        StripSync(list, index, onIndex)
        LazyColumn(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } }, state = list, contentPadding = safe) {
            items(count) { n ->
                AsyncImage(
                    model = pageUrl(n), contentDescription = t("Page {n}", "n" to n + 1), contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(bottom = 8.dp),
                )
            }
        }
        return
    }
    val pager = rememberPagerState(initialPage = index) { count }
    val scope = rememberCoroutineScope()
    PagerSync(pager, index, onIndex, onTurn)
    HorizontalPager(pager, Modifier.fillMaxSize(), reverseLayout = settings.rtl, beyondViewportPageCount = 1, key = { it }) { n ->
        val taps = Modifier.pageTaps(
            settings.rtl,
            onPrev = { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
            onNext = { if (pager.currentPage < count - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            onMiddle = onTap,
        )
        if (settings.fit == Fit.Width) {
            Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).then(taps).padding(safe)) {
                AsyncImage(model = pageUrl(n), contentDescription = t("Page {n}", "n" to n + 1), contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            }
        } else {
            Box(Modifier.fillMaxSize().then(taps).padding(safe), contentAlignment = Alignment.Center) {
                AsyncImage(model = pageUrl(n), contentDescription = t("Page {n}", "n" to n + 1), contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/** Scroll: the page most in view is the place; the scrubber moves the strip. */
@Composable
private fun StripSync(list: LazyListState, index: Int, onIndex: (Int) -> Unit) {
    val report by rememberUpdatedState(onIndex)
    LaunchedEffect(index) { if (mostInView(list) != index) list.scrollToItem(index) }
    LaunchedEffect(list) {
        snapshotFlow { mostInView(list) }.distinctUntilChanged().collect { report(it) }
    }
}

private fun mostInView(list: LazyListState): Int {
    val info = list.layoutInfo
    val top = info.viewportStartOffset
    val bottom = info.viewportEndOffset
    return info.visibleItemsInfo.maxByOrNull { (minOf(bottom, it.offset + it.size) - maxOf(top, it.offset)).coerceAtLeast(0) }?.index ?: list.firstVisibleItemIndex
}

@Composable
fun PdfPagesView(pdf: PdfPages, index: Int, onIndex: (Int) -> Unit, onTap: () -> Unit, onTurn: () -> Unit) {
    val pager = rememberPagerState(initialPage = index) { pdf.count }
    val scope = rememberCoroutineScope()
    PagerSync(pager, index, onIndex, onTurn)
    val safe = WindowInsets.safeDrawing.asPaddingValues()
    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, key = { it }) { n ->
        BoxWithConstraints(
            Modifier.fillMaxSize().pageTaps(
                false,
                onPrev = { if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } },
                onNext = { if (pager.currentPage < pdf.count - 1) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                onMiddle = onTap,
            ).padding(safe).padding(PaddingValues(horizontal = 8.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val width = constraints.maxWidth
            val ratio by produceState(0.707f, n) { value = pdf.ratio(n) }
            val bitmap by produceState<Bitmap?>(null, n, width) { value = pdf.render(n, width) }
            Box(Modifier.fillMaxWidth().aspectRatio(ratio, matchHeightConstraintsFirst = maxHeight / maxWidth < 1f / ratio).background(Color.White)) {
                bitmap?.let { Image(it.asImageBitmap(), t("Page {n}", "n" to n + 1), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
    }
}
