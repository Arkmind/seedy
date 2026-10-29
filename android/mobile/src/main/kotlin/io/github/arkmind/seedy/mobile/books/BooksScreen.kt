package io.github.arkmind.seedy.mobile.books

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.audio.authorLine
import io.github.arkmind.seedy.core.books.AuthorGroup
import io.github.arkmind.seedy.core.books.BookSort
import io.github.arkmind.seedy.core.books.BooksData
import io.github.arkmind.seedy.core.books.BooksView
import io.github.arkmind.seedy.core.books.BooksViewModel
import io.github.arkmind.seedy.core.books.ContinueEntry
import io.github.arkmind.seedy.core.books.SeriesGroup
import io.github.arkmind.seedy.core.books.authorCounts
import io.github.arkmind.seedy.core.books.authorGroups
import io.github.arkmind.seedy.core.books.bookSub
import io.github.arkmind.seedy.core.books.comicSub
import io.github.arkmind.seedy.core.books.isAudioBook
import io.github.arkmind.seedy.core.books.isComic
import io.github.arkmind.seedy.core.books.normalize
import io.github.arkmind.seedy.core.books.seriesInProgress
import io.github.arkmind.seedy.core.books.seriesOf
import io.github.arkmind.seedy.core.books.sortItems
import io.github.arkmind.seedy.core.books.sortSeries
import io.github.arkmind.seedy.core.books.volumeLabel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto

class BooksActions(
    val onBack: () -> Unit = {},
    /** Opens the search field (an empty query), types in it, or closes it (null). */
    val onSearch: (String?) -> Unit = {},
    val onMore: () -> Unit = {},
    val onView: (BooksView) -> Unit = {},
    /** Opens a view on a series or author (null: the whole view). */
    val onFocus: (BooksView, String?) -> Unit = { _, _ -> },
    val onSeeAll: (BooksView, BookSort?) -> Unit = { _, _ -> },
    val onOpen: (BaseItemDto) -> Unit = {},
    val onContinue: (ContinueEntry) -> Unit = {},
    val onRetry: () -> Unit = {},
)

/**
 * Board "Android · Books": a books library. The header (back, the library's name, search, ⋯), the views as chips, then
 * the view: All (Continue, the series being read, Recently added, Audiobooks, Comics), the Ebooks, Audiobooks and Comics
 * grids, and Series and Authors as lists that open one series or author. [now] dates "new" volumes.
 */
@Composable
fun BooksScreen(state: BooksViewModel.State, actions: BooksActions, bottomPadding: Dp, now: Long, modifier: Modifier = Modifier) {
    val data = state.data
    val query = state.query
    LazyColumn(modifier.fillMaxSize().background(SeedyColors.Bg), contentPadding = PaddingValues(bottom = bottomPadding + 24.dp)) {
        item(key = "head") {
            if (query != null) SearchHeader(query, actions) else BooksHeader(state.name, actions)
        }
        if (state.query == null) {
            item(key = "chips") {
                LazyRow(
                    Modifier.fillMaxWidth().padding(top = 12.dp), contentPadding = PaddingValues(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(BooksView.entries, key = { it.name }) { v ->
                        BooksChip(if (v == BooksView.Series) tc("Series", "tab") else t(v.label), v == state.view) { actions.onView(v) }
                    }
                }
            }
        }
        when {
            data == null && state.error -> item(key = "error") { BooksEmpty(t("Couldn't open this library"), action = t("Try again"), onAction = actions.onRetry) }
            data == null -> item(key = "loading") { BooksSkeleton() }
            data.all.isEmpty() -> item(key = "empty") {
                BooksEmpty(t("{name} is empty", "name" to state.name), t("Once the server finds books in this library they will show up here."))
            }
            query != null -> searchResults(data, query, actions)
            else -> when (state.view) {
                BooksView.All -> overview(state, data, actions)
                BooksView.Ebooks -> grid(sortItems(data.books, state.sortOrDefault, data.reading), t("No books yet"), actions) { bookSub(it) }
                BooksView.Comics -> grid(sortItems(data.comics, state.sortOrDefault, data.reading), t("No comics yet"), actions) { comicSub(it, data.reading[it.id], data.running(it)) }
                BooksView.Audiobooks -> audioGrid(sortItems(data.audio, state.sortOrDefault, data.reading), actions)
                BooksView.Series -> seriesView(data, state.sortOrDefault, state.focus, actions, now)
                BooksView.Authors -> authorsView(data, state.sortOrDefault, state.focus, actions)
            }
        }
    }
}

/** A word whose translation depends on where it stands ("Series|tab" is plural in French). */
internal fun tc(text: String, context: String): String {
    val key = "$text|$context"
    val out = t(key)
    return if (out == key) text else out
}

@Composable
private fun BooksHeader(name: String, actions: BooksActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderButton(BookIcons.Back, actions.onBack)
        SText(name, Modifier.weight(1f).padding(start = 6.dp), size = 22.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
        HeaderButton(BookIcons.Search, { actions.onSearch("") })
        HeaderButton(BookIcons.More, actions.onMore)
    }
}

@Composable
private fun SearchHeader(query: String, actions: BooksActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BackHandler { actions.onSearch(null) }
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = topInset()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HeaderButton(BookIcons.Back, { actions.onSearch(null) })
        Row(
            Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SIcon(BookIcons.Search, size = 18.dp, color = SeedyColors.Text2)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) SText(t("Search books"), size = 15.sp, color = SeedyColors.Text3, maxLines = 1)
                BasicTextField(
                    query, { actions.onSearch(it) }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                    textStyle = BaseText.copy(fontSize = 15.sp, fontWeight = FontWeight(600), color = SeedyColors.White),
                    cursorBrush = SolidColor(LocalAccent.current), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
        }
    }
}

/** The page while the library loads: a Continue card and a row of covers in grey. */
@Composable
private fun BooksSkeleton() {
    Column {
        Box(Modifier.padding(start = 20.dp, top = 26.dp, bottom = 14.dp).size(110.dp, 18.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Surface))
        Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(120.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Panel))
        Box(Modifier.padding(start = 20.dp, top = 30.dp, bottom = 14.dp).size(140.dp, 18.dp).clip(RoundedCornerShape(6.dp)).background(SeedyColors.Surface))
        GridRows(List(3) { it }, 3) { Box(Modifier.fillMaxWidth().height(163.dp).clip(RoundedCornerShape(3.dp, 8.dp, 8.dp, 3.dp)).background(SeedyColors.Surface)) }
    }
}

/* ───────── All ───────── */

private fun LazyListScope.overview(state: BooksViewModel.State, data: BooksData, actions: BooksActions) {
    if (state.cont.isNotEmpty()) {
        item(key = "cont-head") { SectionHead(t("Continue")) }
        item(key = "cont") {
            LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.cont, key = { it.item.id }) { e -> ContinueCard(e, live = e.item.id == state.playing) { actions.onContinue(e) } }
            }
        }
    }
    // The series this profile is in (read or reading one of them); every series when none is started.
    val started = seriesInProgress(data).filter { g -> g.read > 0 || g.readAt > 0 || g.volumes.any { data.reading[it.id] != null } }
    val series = started.ifEmpty { seriesInProgress(data) }.take(3)
    if (series.isNotEmpty()) {
        item(key = "series-head") {
            SectionHead(if (started.isNotEmpty()) t("Series you’re in") else tc("Series", "tab"), link = t("See all")) { actions.onView(BooksView.Series) }
        }
        item(key = "series") {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (g in series) SeriesRow(g) { actions.onFocus(BooksView.Series, g.name) }
            }
        }
    }
    val recent = sortItems(data.books, BookSort.Added, data.reading).take(6)
    if (recent.isNotEmpty()) {
        item(key = "recent-head") { SectionHead(t("Recently added"), link = t("See all")) { actions.onSeeAll(BooksView.Ebooks, BookSort.Added) } }
        item(key = "recent") { GridRows(recent, 3) { BookGridCard(it, { actions.onOpen(it) }) } }
    }
    val audio = sortItems(data.audio, BookSort.Added, data.reading).take(3)
    if (audio.isNotEmpty()) {
        item(key = "audio-head") { SectionHead(t("Audiobooks"), link = t("See all")) { actions.onView(BooksView.Audiobooks) } }
        item(key = "audio") { GridRows(audio, 3) { AudioGridCard(it, { actions.onOpen(it) }) } }
    }
    val comics = sortItems(data.comics, BookSort.Added, data.reading).take(3)
    if (comics.isNotEmpty()) {
        item(key = "comics-head") { SectionHead(t("Comics"), link = t("See all")) { actions.onView(BooksView.Comics) } }
        item(key = "comics") { GridRows(comics, 3) { BookGridCard(it, { actions.onOpen(it) }, sub = comicSub(it, data.reading[it.id], data.running(it))) } }
    }
}

/* ───────── Grids ───────── */

private fun LazyListScope.grid(items: List<BaseItemDto>, empty: String, actions: BooksActions, sub: (BaseItemDto) -> String) {
    if (items.isEmpty()) {
        item(key = "none") { BooksEmpty(empty) }
        return
    }
    item(key = "gap") { Spacer(Modifier.height(20.dp)) }
    items(items.chunked(3), key = { row -> "row-" + row.first().id }) { row ->
        GridRows(row, 3, Modifier.padding(bottom = 18.dp)) { BookGridCard(it, { actions.onOpen(it) }, sub = sub(it)) }
    }
}

private fun LazyListScope.audioGrid(items: List<BaseItemDto>, actions: BooksActions) {
    if (items.isEmpty()) {
        item(key = "none") { BooksEmpty(t("No audiobooks yet")) }
        return
    }
    item(key = "gap") { Spacer(Modifier.height(20.dp)) }
    items(items.chunked(2), key = { row -> "row-" + row.first().id }) { row ->
        GridRows(row, 2, Modifier.padding(bottom = 18.dp), gap = 14.dp) { AudioGridCard(it, { actions.onOpen(it) }) }
    }
}

/* ───────── Series ───────── */

private fun LazyListScope.seriesView(data: BooksData, sort: BookSort, focus: String?, actions: BooksActions, now: Long) {
    val groups = sortSeries(data.series, sort)
    val one = focus?.let { f -> groups.firstOrNull { it.key == normalize(f) } }
    if (one != null) {
        item(key = "one-head") {
            FocusHead(one.name, listOf(one.author, t("{read} of {total} read", "read" to one.read, "total" to one.volumes.size)).filter { it.isNotEmpty() }.joinToString(" · "), t("All series")) {
                actions.onFocus(BooksView.Series, null)
            }
        }
        items(one.volumes.chunked(3), key = { row -> "vol-" + row.first().id }) { row ->
            GridRows(row, 3, Modifier.padding(bottom = 18.dp)) { VolumeCard(it, data, now, actions) }
        }
        return
    }
    if (groups.isEmpty()) {
        item(key = "none") { BooksEmpty(t("No series yet"), t("Books with a series in their metadata are grouped here.")) }
        return
    }
    item(key = "gap") { Spacer(Modifier.height(20.dp)) }
    items(groups, key = { it.key }) { g ->
        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) { SeriesRow(g) { actions.onFocus(BooksView.Series, g.name) } }
    }
}

/** A volume of a series: "Book 2 · read" dimmed with a tick, "Book 3 · 46%" in the accent, "Book 4 · new". */
@Composable
fun VolumeCard(item: BaseItemDto, data: BooksData, now: Long, actions: BooksActions, current: Boolean = false) {
    val label = volumeLabel(item, data.reading[item.id], now)
    BookGridCard(
        item, { actions.onOpen(item) }, sub = label.text, read = label.read, current = current,
        subColor = if (label.read) Dim else if (label.inProgress) LocalAccent.current else SeedyColors.Text3,
    )
}

/** One series or author: its name, a line under it and the way back to all of them. */
@Composable
private fun FocusHead(title: String, sub: String, back: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        SectionHead(title, link = back, onLink = onBack)
        if (sub.isNotEmpty()) SText(sub, Modifier.padding(horizontal = 20.dp).padding(top = 0.dp), size = 13.sp, color = SeedyColors.Text3, maxLines = 1)
    }
}

/* ───────── Authors ───────── */

private fun LazyListScope.authorsView(data: BooksData, sort: BookSort, focus: String?, actions: BooksActions) {
    val groups = authorGroups(data, sort)
    val one = focus?.let { f -> groups.firstOrNull { it.key == normalize(f).ifEmpty { "~" } } }
    if (one != null) {
        authorPage(one, data, sort, actions)
        return
    }
    if (groups.isEmpty()) {
        item(key = "none") { BooksEmpty(t("No authors yet"), t("Authors come from each book's metadata.")) }
        return
    }
    item(key = "gap") { Spacer(Modifier.height(20.dp)) }
    items(groups, key = { it.key }) { g ->
        Box(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)) { AuthorRow(g) { actions.onFocus(BooksView.Authors, g.name) } }
    }
}

private fun LazyListScope.authorPage(one: AuthorGroup, data: BooksData, sort: BookSort, actions: BooksActions) {
    item(key = "one-head") { FocusHead(one.name.ifEmpty { t("Unknown author") }, authorCounts(one), t("All authors")) { actions.onFocus(BooksView.Authors, null) } }
    val books = sortItems(one.books, if (sort == BookSort.Added) BookSort.Released else sort, data.reading)
    items(books.chunked(3), key = { row -> "b-" + row.first().id }) { row ->
        GridRows(row, 3, Modifier.padding(bottom = 18.dp)) {
            BookGridCard(it, { actions.onOpen(it) }, sub = if (isComic(it)) comicSub(it, data.reading[it.id], data.running(it)) else bookSub(it))
        }
    }
    if (one.audio.isNotEmpty()) {
        item(key = "au-head") { SectionHead(t("Audiobooks"), top = 8.dp) }
        items(one.audio.chunked(2), key = { row -> "a-" + row.first().id }) { row ->
            GridRows(row, 2, Modifier.padding(bottom = 18.dp), gap = 14.dp) { AudioGridCard(it, { actions.onOpen(it) }) }
        }
    }
}

/* ───────── Search ───────── */

/** The library's books, comics and audiobooks whose title, author or series has the words typed. */
fun searchBooks(data: BooksData, query: String): List<BaseItemDto> {
    val q = normalize(query)
    if (q.isEmpty()) return emptyList()
    return (data.books + data.comics + data.audio).filter { normalize(it.name).contains(q) || normalize(authorLine(it)).contains(q) || normalize(seriesOf(it)).contains(q) }
}

private fun LazyListScope.searchResults(data: BooksData, query: String, actions: BooksActions) {
    val found = searchBooks(data, query)
    if (query.isBlank()) return
    if (found.isEmpty()) {
        item(key = "none") { BooksEmpty(t("No results")) }
        return
    }
    item(key = "gap") { Spacer(Modifier.height(16.dp)) }
    items(found, key = { it.id }) { item -> SearchRow(item) { actions.onOpen(item) } }
}

@Composable
private fun SearchRow(item: BaseItemDto, onClick: () -> Unit) {
    val audio = isAudioBook(item)
    Row(
        Modifier.fillMaxWidth().tap(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (audio) ItemAudioCover(item, Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)), width = 120)
        else ItemBookCover(item, Modifier.width(44.dp), titleSize = 6.sp, authorSize = 0.sp, small = 2.dp, big = 5.dp, width = 120)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(item.name.orEmpty(), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            val kind = when {
                audio -> t("Audiobook")
                isComic(item) -> t("Comic")
                else -> t("Ebook")
            }
            SText(listOf(kind, authorLine(item)).filter { it.isNotEmpty() }.joinToString(" · "), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
    }
}
