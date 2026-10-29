package io.github.arkmind.seedy.mobile.books.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.books.Bookmark
import io.github.arkmind.seedy.core.books.ComicSettings
import io.github.arkmind.seedy.core.books.Direction
import io.github.arkmind.seedy.core.books.EpubSettings
import io.github.arkmind.seedy.core.books.Fit
import io.github.arkmind.seedy.core.books.FontChoice
import io.github.arkmind.seedy.core.books.Margins
import io.github.arkmind.seedy.core.books.PageTheme
import io.github.arkmind.seedy.core.books.ReaderLayout
import io.github.arkmind.seedy.core.books.Spacing
import io.github.arkmind.seedy.core.books.TocEntry
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.NotoSans
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/* The reader's sheets (web reader chrome.tsx, as phone bottom sheets): Reading settings, and Contents with the
   bookmarks. */

private val SegBg = Color(0xFF262626)

/** The web's contextual "Normal" (spacing, margins). */
private fun tc(text: String, context: String): String = t("$text|$context").let { if (it == "$text|$context") text else it }

@Composable
fun EpubSettingsSheet(value: EpubSettings, onChange: ((EpubSettings) -> EpubSettings) -> Unit, onClose: () -> Unit) = BottomSheet(onClose) {
    SheetBody {
        SText(t("Reading settings"), size = 16.sp, weight = 800, color = SeedyColors.White)
        ThemePicker(value.theme) { th -> onChange { it.copy(theme = th) } }
        Group(t("Font")) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SegBg).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple(FontChoice.Serif, "Noto Serif", FontFamily.Serif),
                    Triple(FontChoice.Sans, "Noto Sans", NotoSans),
                    Triple(FontChoice.Book, t("Book’s own"), NotoSans),
                ).forEach { (f, label, family) ->
                    val on = value.font == f
                    Box(
                        Modifier.weight(1f).height(38.dp).clip(RoundedCornerShape(10.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                            .tap { onChange { it.copy(font = f) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(
                            label, maxLines = 1,
                            style = BaseText.copy(fontFamily = family, fontSize = 14.sp, fontWeight = FontWeight(700), color = if (on) SeedyColors.InkInverse else SeedyColors.TextHero),
                        )
                    }
                }
            }
        }
        SettingRow(t("Text size")) {
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(SegBg).padding(4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SizeButton("A−", 14, value.size > EpubSettings.SIZE_MIN) { onChange { it.copy(size = (it.size - 1).coerceAtLeast(EpubSettings.SIZE_MIN)) } }
                SText(t("{n} px", "n" to value.size), Modifier.widthIn(min = 44.dp), size = 14.sp, weight = 800, color = LocalAccent.current, align = androidx.compose.ui.text.style.TextAlign.Center)
                SizeButton("A+", 18, value.size < EpubSettings.SIZE_MAX) { onChange { it.copy(size = (it.size + 1).coerceAtMost(EpubSettings.SIZE_MAX)) } }
            }
        }
        Seg(t("Line spacing"), value.spacing, listOf(Spacing.Tight to t("Tight"), Spacing.Normal to tc("Normal", "spacing"), Spacing.Airy to t("Airy"))) { v -> onChange { it.copy(spacing = v) } }
        Seg(t("Margins"), value.margins, listOf(Margins.Narrow to t("Narrow"), Margins.Normal to tc("Normal", "margins"), Margins.Wide to t("Wide"))) { v -> onChange { it.copy(margins = v) } }
        Seg(t("Layout"), if (value.scroll) ReaderLayout.Scroll else ReaderLayout.One, listOf(ReaderLayout.One to t("One page"), ReaderLayout.Scroll to t("Scroll"))) { v ->
            onChange { if (v == ReaderLayout.One && !it.scroll) it else it.copy(layout = v) }
        }
        Note()
    }
}

@Composable
fun ComicSettingsSheet(value: ComicSettings, onChange: ((ComicSettings) -> ComicSettings) -> Unit, onClose: () -> Unit) = BottomSheet(onClose) {
    SheetBody {
        SText(t("Reading settings"), size = 16.sp, weight = 800, color = SeedyColors.White)
        ThemePicker(value.theme) { th -> onChange { it.copy(theme = th) } }
        Seg(t("Layout"), if (value.scroll) ReaderLayout.Scroll else ReaderLayout.One, listOf(ReaderLayout.One to t("One page"), ReaderLayout.Scroll to t("Scroll"))) { v ->
            onChange { if (v == ReaderLayout.One && !it.scroll) it else it.copy(layout = v) }
        }
        Seg(t("Fit"), value.fit, listOf(Fit.Screen to t("Screen"), Fit.Width to t("Width"))) { v -> onChange { it.copy(fit = v) } }
        Seg(t("Reading"), value.direction, listOf(Direction.Ltr to t("Left to right"), Direction.Rtl to t("Right to left"))) { v -> onChange { it.copy(direction = v) } }
        Note()
    }
}

@Composable
private fun SheetBody(content: @Composable ColumnScope.() -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp + nav),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(label, size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.Text3)
        content()
    }
}

/** Page colour: four "Aa" tiles in each page's colours, the chosen one ringed in the accent. */
@Composable
private fun ThemePicker(value: PageTheme, onChange: (PageTheme) -> Unit) = Group(t("Page")) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PageTheme.entries.forEach { th ->
            val p = paletteOf(th)
            val on = th == value
            Column(
                Modifier.weight(1f).height(64.dp)
                    .drawBehind {
                        if (on) {
                            val w = 2.dp.toPx()
                            val out = 3.dp.toPx()
                            drawRoundRect(
                                accent, topLeft = Offset(-out, -out), size = Size(size.width + out * 2, size.height + out * 2),
                                cornerRadius = CornerRadius(17.dp.toPx()), style = Stroke(w),
                            )
                        }
                    }
                    .clip(RoundedCornerShape(14.dp)).background(p.bg).border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(14.dp))
                    .tap { onChange(th) },
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText("Aa", style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = p.ink))
                SText(t(th.label), size = 11.sp, weight = 700, color = p.ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2)
        control()
    }
}

@Composable
private fun <T> Seg(label: String, value: T, options: List<Pair<T, String>>, onChange: (T) -> Unit) = SettingRow(label) {
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(SegBg).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (v, text) ->
            val on = v == value
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(10.dp)).background(if (on) SeedyColors.Inverse else Color.Transparent)
                    .tap { onChange(v) }.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) { SText(text, size = 12.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.TextHero, maxLines = 1) }
        }
    }
}

@Composable
private fun SizeButton(label: String, size: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(40.dp, 36.dp).clip(RoundedCornerShape(10.dp)).tap(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        SText(label, size = size.sp, weight = 800, color = SeedyColors.White.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun Note() {
    SText(t("Saved for this book on every device. Your place syncs through the server."), size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text3)
}

/** Contents: the chapters with their first page, the current one marked, then the bookmarks. */
@Composable
fun ContentsSheet(
    toc: List<TocEntry>,
    current: Int,
    counted: Boolean,
    bookmarks: List<Bookmark>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) = BottomSheet(onClose) {
    val accent = LocalAccent.current
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
    SText(t("Contents"), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 10.dp), size = 16.sp, weight = 800, color = SeedyColors.White)
    LazyColumn(state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 20.dp + nav), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        itemsIndexed(toc) { i, c ->
            ListRow(c.title, c.page, on = i == current, accent = accent) { onPick(c.href) }
        }
        if (toc.isEmpty()) {
            item {
                SText(
                    if (counted) t("This book has no table of contents.") else t("Counting pages…"),
                    Modifier.padding(horizontal = 8.dp, vertical = 10.dp), size = 14.sp, color = SeedyColors.Text3,
                )
            }
        }
        if (bookmarks.isNotEmpty()) {
            item {
                SText(t("Bookmarks"), Modifier.padding(start = 8.dp, top = 16.dp, bottom = 6.dp), size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = SeedyColors.Text3)
            }
            itemsIndexed(bookmarks) { _, b -> ListRow(b.label, b.page, on = false, accent = accent) { onPick(b.cfi) } }
        }
    }
}

@Composable
private fun ListRow(title: String, page: Int, on: Boolean, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (on) SegBg else Color.Transparent).tap(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SText(title, Modifier.weight(1f), size = 14.sp, weight = if (on) 800 else 400, color = if (on) SeedyColors.White else SeedyColors.Text)
        if (page > 0) SText("$page", size = 12.sp, weight = 700, color = if (on) accent else SeedyColors.Text3)
    }
}
