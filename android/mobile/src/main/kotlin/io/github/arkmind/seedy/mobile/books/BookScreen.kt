package io.github.arkmind.seedy.mobile.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.audio.authorLine
import io.github.arkmind.seedy.core.audio.authorsOf
import io.github.arkmind.seedy.core.audio.narratorOf
import io.github.arkmind.seedy.core.books.BookFormat
import io.github.arkmind.seedy.core.books.BookViewModel
import io.github.arkmind.seedy.core.books.audioLength
import io.github.arkmind.seedy.core.books.formatOf
import io.github.arkmind.seedy.core.books.isAudioBook
import io.github.arkmind.seedy.core.books.isComic
import io.github.arkmind.seedy.core.books.languageName
import io.github.arkmind.seedy.core.books.roughTime
import io.github.arkmind.seedy.core.books.volumeLabel
import io.github.arkmind.seedy.core.books.yearOfBook
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.format.timeLeft
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import org.jellyfin.sdk.model.api.BaseItemDto
import kotlin.math.roundToInt

class BookActions(
    val onBack: () -> Unit = {},
    /** Null when this profile may not download the file. */
    val onDownload: (() -> Unit)? = null,
    val onMore: () -> Unit = {},
    val onRead: (BaseItemDto) -> Unit = {},
    val onListen: (BaseItemDto) -> Unit = {},
    /** A format chip: the reader, the player or a download. */
    val onFormat: (BaseItemDto) -> Unit = {},
    val onAuthor: (String) -> Unit = {},
    val onSeries: (String) -> Unit = {},
    val onOpen: (BaseItemDto) -> Unit = {},
)

/**
 * Board "Android · Book details" (web BookDetails.tsx): the cover over the cover's colour, the title, author and
 * "The Halden Cycle, book 3 · 2025 · 412 pages", Read and Listen (a book and its audiobook edition), how far in, the
 * formats of the work, the synopsis (More opens the rest of it and the details), the series' volumes and more by the
 * author. An audiobook uses the same page with Listen first. [now] dates "new" volumes.
 */
@Composable
fun BookScreen(state: BookViewModel.State, actions: BookActions, bottomPadding: Dp, now: Long, modifier: Modifier = Modifier) {
    val item = state.item
    Box(modifier.fillMaxSize().background(SeedyColors.Bg)) {
        if (item == null) {
            Column {
                Row(Modifier.padding(start = 8.dp, top = topInset())) { HeaderButton(BookIcons.Back, actions.onBack) }
                if (state.error) BooksEmpty(t("Couldn’t load this title"), action = t("Try again"), onAction = actions.onBack)
            }
            return@Box
        }
        val audio = state.audio
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomPadding + 28.dp)) {
            Box {
                HeroGlow(item, audio)
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        HeaderButton(BookIcons.Back, actions.onBack)
                        Spacer(Modifier.weight(1f))
                        actions.onDownload?.let { dl ->
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).tap(onClick = dl), contentAlignment = Alignment.Center) {
                                SIcon(BookIcons.Download, size = 21.dp, color = SeedyColors.White)
                            }
                        }
                        HeaderButton(BookIcons.More, actions.onMore)
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
                        if (audio) ItemAudioCover(item, Modifier.size(200.dp).clip(RoundedCornerShape(16.dp)), titleSize = 18.sp, width = 600)
                        else ItemBookCover(item, Modifier.width(148.dp), titleSize = 19.sp, authorSize = 9.sp, small = 4.dp, big = 10.dp, width = 600)
                    }
                    Titles(state, item, actions)
                }
            }
            Buttons(state, item, actions)
            progressOf(state, item)?.let { (fraction, text) ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ProgressLine(fraction, Modifier.weight(1f))
                    if (text.isNotEmpty()) SText(text, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
                }
            }
            val work = state.work
            if (work.size > 1 || formatOf(state.self ?: item) != BookFormat.Other) {
                LazyRow(Modifier.padding(top = 16.dp), contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(work, key = { it.id }) { w -> FormatChip(w, state) { actions.onFormat(w) } }
                }
            }
            About(state, item)
            val series = state.series
            if (series != null && series.volumes.size > 1) {
                SectionHead(series.name, link = tn(series.volumes.size, "{n} book", "{n} books"), top = 20.dp) { actions.onSeries(series.name) }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(series.volumes, key = { it.id }) { v ->
                        val label = volumeLabel(v, state.data?.reading?.get(v.id), now)
                        SmallBook(v, label.text, if (label.read) Dim else if (label.inProgress) LocalAccent.current else SeedyColors.Text2) { actions.onOpen(v) }
                    }
                }
            }
            val author = authorsOf(item).firstOrNull()?.first
            val more = state.more
            if (author != null && more.isNotEmpty()) {
                SectionHead(t("More by {name}", "name" to author), link = t("See all"), top = 20.dp) { actions.onAuthor(author) }
                LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(more.take(16), key = { it.id }) { o -> SmallBook(o, o.name.orEmpty(), SeedyColors.White) { actions.onOpen(o) } }
                }
            }
        }
    }
}

/** The page's top takes the cover's colour: the drawn cover's hill, or the picture's own colour, fading out by 420 px. */
@Composable
private fun HeroGlow(item: BaseItemDto, audio: Boolean) {
    val url = bookArt(item, 64)
    val rgb = rememberDominant(url)
    val color = when {
        url != null && rgb != null -> scaledColor(rgb, 40f)
        url != null -> SeedyColors.Bg
        audio -> io.github.arkmind.seedy.ui.components.coverPalette("${item.name.orEmpty()}|${authorLine(item)}")[0]
        else -> bookPalette(item.name.orEmpty(), authorLine(item))[2]
    }
    Box(Modifier.fillMaxWidth().height(420.dp).background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0f)))))
}

@Composable
private fun Titles(state: BookViewModel.State, item: BaseItemDto, actions: BookActions) {
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SText(item.name.orEmpty(), size = 24.sp, weight = 900, lineHeight = 1.1f, color = SeedyColors.White, align = TextAlign.Center)
        val author = authorLine(item)
        if (author.isNotEmpty()) {
            SText(author, Modifier.tap { authorsOf(item).firstOrNull()?.first?.let(actions.onAuthor) }, size = 14.sp, weight = 700, color = SeedyColors.White, align = TextAlign.Center)
        }
        val meta = metaLine(state, item)
        val score = item.communityRating?.takeIf { it > 0f }?.let { String.format(I18n.locale, "%.1f", it / 2f) }
        if (meta.isNotEmpty() || score != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (meta.isNotEmpty()) SText(meta, Modifier.weight(1f, fill = false), size = 12.sp, color = SeedyColors.Text2, align = TextAlign.Center)
                if (score != null) {
                    if (meta.isNotEmpty()) SText("·", size = 12.sp, color = SeedyColors.Text2)
                    SIcon(BookIcons.Star, size = 12.dp, color = LocalAccent.current)
                    SText(score, size = 12.sp, weight = 700, color = SeedyColors.Text2)
                }
            }
        }
    }
}

/** "The Halden Cycle, book 3 · 2025 · 412 pages · English" (an audiobook: its length). */
fun metaLine(state: BookViewModel.State, item: BaseItemDto): String {
    val series = state.seriesName?.let { name ->
        val n = state.source?.indexNumber
        if (state.self?.let(::isComic) == true) t("{series} · No. {n}", "series" to name, "n" to n) else t("{series}, book {n}", "series" to name, "n" to n)
    }
    val length = if (state.audio) audioLength(item).ifEmpty { null } else state.info?.pages?.takeIf { it > 0 }?.let { tn(it, "{n} page", "{n} pages") }
    return listOfNotNull(series, yearOfBook(item)?.toString(), length, languageName(state.language).ifEmpty { null }).joinToString(" · ")
}

private fun percentOf(r: io.github.arkmind.seedy.core.audio.Reading?): Int? = r?.takeIf { !it.finished && it.percent > 0 }?.percent?.roundToInt()

private fun audioPercent(item: BaseItemDto?): Int? {
    val ud = item?.userData ?: return null
    val total = item.runTimeTicks ?: 0L
    val pos = ud.playbackPositionTicks
    if (ud.played || pos <= 0 || total <= 0) return null
    return (pos * 100.0 / total).roundToInt()
}

@Composable
private fun Buttons(state: BookViewModel.State, item: BaseItemDto, actions: BookActions) {
    val listen = state.audioTwin
    val read = state.readable
    val readPct = percentOf(if (state.audio) state.twinReading else state.reading)
    val listenPct = audioPercent(listen)
    val readLabel = readPct?.let { t("Read · {n}%", "n" to it) } ?: t("Read")
    val listenLabel = listenPct?.let { t("Listen · {n}%", "n" to it) } ?: t("Listen")
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.audio) {
            BigButton(BookIcons.Headphones, listenLabel, primary = true, Modifier.weight(1f)) { actions.onListen(item) }
            if (read != null) BigButton(BookIcons.Book, readLabel, primary = false, Modifier.weight(1f)) { actions.onRead(read) }
        } else {
            BigButton(BookIcons.Book, readLabel, primary = true, Modifier.weight(1f)) { actions.onRead(item) }
            if (listen != null) BigButton(BookIcons.Headphones, listenLabel, primary = false, Modifier.weight(1f)) { actions.onListen(listen) }
        }
    }
}

@Composable
private fun BigButton(icon: IconDef, label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val ink = if (primary) SeedyColors.InkInverse else SeedyColors.White
    Row(
        modifier.height(52.dp).clip(RoundedCornerShape(15.dp)).background(if (primary) SeedyColors.Inverse else SeedyColors.Surface).tap(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        SIcon(icon, size = 19.dp, color = ink)
        SText(label, size = 15.sp, weight = if (primary) 800 else 700, color = ink, maxLines = 1)
    }
}

/** How far in: the bar and "Chapter 12 of 28 · about 3 h left" (a comic: its pages; an audiobook: its chapters and time). */
fun progressOf(state: BookViewModel.State, item: BaseItemDto): Pair<Float, String>? {
    if (state.audio) {
        val ud = item.userData ?: return null
        val pos = ud.playbackPositionTicks
        val total = item.runTimeTicks ?: 0L
        if (pos <= 0 || total <= 0 || ud.played) return null
        val chapters = item.chapters.orEmpty().sortedBy { it.startPositionTicks }
        val ci = chapters.indexOfLast { it.startPositionTicks <= pos }
        val text = listOfNotNull(
            if (ci >= 0 && chapters.size > 1) t("Chapter {n} of {total}", "n" to ci + 1, "total" to chapters.size) else null,
            timeLeft(ticksToSeconds(total - pos)),
        ).joinToString(" · ")
        return (pos.toFloat() / total) to text
    }
    val r = state.reading ?: return null
    if (r.finished || r.percent <= 0) return null
    val fraction = (r.percent / 100).coerceIn(0.0, 1.0)
    val comic = state.self?.let(::isComic) == true || state.info?.bookFormat == BookFormat.Cbz
    val pages = state.info?.pages ?: 0
    val at = r.locator?.toIntOrNull() ?: 0
    val minutes = state.info?.minutes?.takeIf { it > 0 }?.let { it * (1 - fraction) } ?: 0.0
    val text = listOfNotNull(
        when {
            comic && pages > 0 -> t("Page {n} of {total}", "n" to minOf(pages, at + 1), "total" to pages)
            (r.chapterIndex ?: 0) > 0 && (r.chapterCount ?: 0) > 0 -> t("Chapter {n} of {total}", "n" to r.chapterIndex, "total" to r.chapterCount)
            else -> null
        },
        when {
            minutes > 0 -> t("about {time} left", "time" to roughTime(minutes))
            comic && pages > 0 -> tn(pages - at - 1, "{n} page left", "{n} pages left")
            else -> null
        },
    ).joinToString(" · ")
    return fraction.toFloat() to text
}

private fun formatName(item: BaseItemDto): String = when (formatOf(item)) {
    BookFormat.Audio -> t("Audiobook")
    BookFormat.Other -> t("File")
    else -> formatOf(item).name.uppercase()
}

/** "2.1 MB", "640 KB". */
fun sizeLabel(bytes: Long): String = if (bytes >= 1024 * 1024) String.format(I18n.locale, "%.1f MB", bytes / (1024.0 * 1024.0))
else "${maxOf(1L, (bytes / 1024.0).roundToInt().toLong())} KB"

/** A format of the work: "EPUB 2.1 MB", "Audiobook 14 h 20 min · Sam Okafor" (headphones in the accent), "PDF 8.6 MB". */
@Composable
private fun FormatChip(w: BaseItemDto, state: BookViewModel.State, onClick: () -> Unit) {
    val f = formatOf(w)
    val audio = f == BookFormat.Audio
    val parts = if (w.id == state.item?.id) state.parts else state.data?.parts?.get(w.id)
    val sub = if (audio) {
        listOfNotNull(audioLength(w).ifEmpty { null }, parts?.let { tn(it.size, "{n} file", "{n} files") }, narratorOf(w)).joinToString(" · ")
    } else {
        state.sizes[w.id]?.let(::sizeLabel).orEmpty()
    }
    val icon = when (f) {
        BookFormat.Audio -> BookIcons.HeadphonesSquare
        BookFormat.Epub, BookFormat.Cbz -> BookIcons.Book
        else -> BookIcons.File
    }
    Row(
        Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).tap(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(icon, size = 16.dp, color = if (audio) LocalAccent.current else SeedyColors.White, stroke = 2f)
        SText(formatName(w), size = 12.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
        if (sub.isNotEmpty()) SText(sub, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
    }
}

/** A cover 92 px wide with a line under it (the series row, more by the author). */
@Composable
private fun SmallBook(item: BaseItemDto, sub: String, subColor: Color, onClick: () -> Unit) {
    Column(Modifier.width(92.dp).tap(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isAudioBook(item)) ItemAudioCover(item, Modifier.size(92.dp).clip(RoundedCornerShape(10.dp)), width = 200)
        else ItemBookCover(item, Modifier.fillMaxWidth(), titleSize = 10.sp, authorSize = 6.sp, small = 3.dp, big = 7.dp, width = 200)
        SText(sub, size = 12.sp, weight = 700, color = subColor, maxLines = 1)
    }
}

/** Jellyfin hands back the description as it was in the file, sometimes as HTML. */
fun plainText(s: String): String {
    if (!Regex("<[a-z][\\s\\S]*>", RegexOption.IGNORE_CASE).containsMatchIn(s)) return s
    return androidx.core.text.HtmlCompat.fromHtml(s, androidx.core.text.HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(Regex("\\s+\n"), "\n").trim()
}

/** The details More opens: publisher, narrator, genres, ISBN and when it was added. */
private fun detailRows(state: BookViewModel.State, item: BaseItemDto): List<Pair<String, String>> {
    val studios = (if (!item.studios.isNullOrEmpty()) item else state.source ?: item).studios.orEmpty().mapNotNull { it.name }.joinToString(", ")
    val isbn = item.providerIds?.entries?.firstOrNull { it.key.equals("Isbn", ignoreCase = true) }?.value
    return listOfNotNull(
        studios.ifEmpty { null }?.let { t("Publisher") to it },
        (if (state.audio) narratorOf(item) else null)?.let { t("Narrator") to it },
        item.genres?.takeIf { it.isNotEmpty() }?.joinToString(" · ")?.let { t("Genres") to it },
        isbn?.ifEmpty { null }?.let { "ISBN" to it },
        item.dateCreated?.toLocalDate()?.let { t("Added") to fullDate(it) },
    )
}

/**
 * The synopsis, four lines at most with "More" at its end; More shows all of it and the details table, "Less" folds
 * it again. With nothing more to show it ends without a link.
 */
@Composable
private fun About(state: BookViewModel.State, item: BaseItemDto) {
    val text = item.overview?.let(::plainText)?.trim().orEmpty()
    val rows = detailRows(state, item)
    var open by rememberSaveable(item.id) { mutableStateOf(false) }
    if (text.isEmpty() && rows.isEmpty()) return
    if (text.isNotEmpty()) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            val measurer = rememberTextMeasurer()
            val style = BaseText.copy(fontSize = 14.sp, lineHeight = 1.55.em, color = SeedyColors.TextHero)
            val link = SpanStyle(color = SeedyColors.White, fontWeight = FontWeight(700))
            val width = constraints.maxWidth
            val moreLabel = t("More")
            val lessLabel = t("Less")
            val shown: AnnotatedString = remember(text, width, open, rows.isNotEmpty()) {
                fun with(body: String, label: String?) = buildAnnotatedString {
                    append(body)
                    if (label != null) {
                        append(" ")
                        withStyle(link) { append(label) }
                    }
                }
                fun lines(s: AnnotatedString) = measurer.measure(s, style, constraints = Constraints(maxWidth = width)).lineCount
                when {
                    open -> with(text, lessLabel)
                    lines(with(text, moreLabel)) <= 4 -> with(text, if (rows.isNotEmpty()) moreLabel else null)
                    else -> {
                        // The longest start of the text that leaves room for "… More" in four lines.
                        var lo = 0
                        var hi = text.length
                        while (lo < hi) {
                            val mid = (lo + hi + 1) / 2
                            if (lines(with(text.take(mid).trimEnd() + "…", moreLabel)) <= 4) lo = mid else hi = mid - 1
                        }
                        with(text.take(lo).trimEnd() + "…", moreLabel)
                    }
                }
            }
            val clickable = open || shown.text.endsWith(moreLabel)
            BasicText(shown, (if (clickable) Modifier.tap { open = !open } else Modifier), style = style)
        }
    } else if (!open) {
        SText(t("More"), Modifier.padding(start = 20.dp, top = 16.dp).tap { open = true }, size = 14.sp, weight = 700, color = SeedyColors.White)
    }
    if (open && rows.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((label, value) in rows) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SText(label, Modifier.width(96.dp), size = 13.sp, color = SeedyColors.Text3)
                    SText(value, Modifier.weight(1f), size = 13.sp, color = SeedyColors.Text)
                }
            }
        }
    }
}

