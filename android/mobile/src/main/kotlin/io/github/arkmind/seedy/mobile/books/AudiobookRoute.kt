package io.github.arkmind.seedy.mobile.books

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.audio.Reading
import io.github.arkmind.seedy.core.books.AudiobookViewModel
import io.github.arkmind.seedy.core.books.Bookmark
import io.github.arkmind.seedy.core.books.uuidOf
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.audio.LocalAudio
import io.github.arkmind.seedy.mobile.audio.openOutputSwitcher
import io.github.arkmind.seedy.mobile.audio.rememberAudioOutput
import io.github.arkmind.seedy.mobile.audio.rememberMediaVolume
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.manage.ItemMenuSheet
import io.github.arkmind.seedy.mobile.manage.MenuEntry
import io.github.arkmind.seedy.mobile.nav.LocalMobileNav
import io.github.arkmind.seedy.mobile.nav.Route
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.PickOption
import io.github.arkmind.seedy.mobile.settings.SheetHead
import io.github.arkmind.seedy.player.audio.BookChapter
import io.github.arkmind.seedy.player.audio.BookInfo
import io.github.arkmind.seedy.player.audio.Sleep
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SleepMinutes = listOf(5, 10, 15, 30, 45, 60)

/** Board "Android · Audiobook": the full-screen player for the audiobook playing, with its sheets. */
@Composable
fun AudiobookRoute() {
    val audio = LocalAudio.current ?: return
    val nav = LocalMobileNav.current
    val session = currentSession()
    val context = LocalContext.current
    val state by audio.state.collectAsStateWithLifecycle()
    val time by audio.time.collectAsStateWithLifecycle()
    val book = state.book?.takeIf { state.isBook && state.current != null }
    // Nothing (or music) playing: there's no audiobook to show.
    LaunchedEffect(book == null) { if (book == null) nav.back() }
    if (book == null) return
    val vm = viewModel(key = "audiobook") { AudiobookViewModel(session) }
    val marks by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(book.id) { uuidOf(book.id)?.let { vm.open(it, book.name, book.author) } }
    val output = rememberAudioOutput()
    val (volume, setVolume) = rememberMediaVolume()
    var sheet by remember { mutableStateOf<String?>(null) }
    val pos = time.position
    val now = System.currentTimeMillis()
    Box(Modifier.fillMaxSize()) {
        AudiobookScreen(
            state, time,
            AudiobookActions(
                onClose = nav::back,
                onMore = { sheet = "more" },
                onToggle = audio::togglePlay,
                onSeek = audio::seek,
                onSkip = audio::skip,
                onPreviousChapter = audio::previousChapter,
                onNextChapter = audio::nextChapter,
                onSpeed = { sheet = "speed" },
                onSleep = { sheet = "sleep" },
                onChapters = { sheet = "chapters" },
                onBookmarks = { sheet = "bookmarks" },
                onOutput = { openOutputSwitcher(context) },
                onVolume = setVolume,
            ),
            output = output, volume = volume, now = now,
            art = state.current?.track?.let { audio.artwork(it, 720) },
            bookmarked = marks.bookmarks.any { abs(it.seconds - pos) < 30 },
        )
        val close = { sheet = null }
        when (sheet) {
            "speed" -> SpeedSheet(state.speed, onPick = audio::setSpeed, onClose = close)
            "sleep" -> SleepSheet(state.sleep, chapterOf(book, pos), onPick = audio::setSleep, onClose = close)
            "chapters" -> ChaptersSheet(book, pos, onPick = audio::seekChapter, onClose = close)
            "bookmarks" -> BookmarksSheet(
                book, pos, marks.bookmarks,
                onAdd = {
                    val ch = chapterOf(book, pos)
                    vm.add(pos, ch?.index ?: 0, ch?.name ?: book.name, placeOf(book, pos))
                },
                onRemove = { vm.remove(it, placeOf(book, pos)) },
                onPick = { audio.seek(it.seconds) },
                onClose = close,
            )
            "more" -> {
                val entries = buildList {
                    uuidOf(book.id)?.let { id ->
                        add(MenuEntry(BookIcons.Headphones, t("Go to the audiobook"), run = { nav.back(); nav.go(Route.Book(id)) }))
                    }
                    marks.ebook?.let { e ->
                        add(MenuEntry(BookIcons.Book, t("Read the EPUB from here"), run = {
                            // Reading takes over from listening: the place is kept for when they come back to it.
                            audio.pause()
                            nav.back()
                            nav.go(Route.Reader(e.id))
                        }))
                    }
                    add(MenuEntry(BookIcons.Stop, t("Stop playback"), line = true, run = { audio.stop(); nav.back() }))
                }
                ItemMenuSheet(book.name, entries, close)
            }
        }
    }
}

/** Where the player is in [book], as the engine saves it with Seedy (a bookmark saves it along). */
private fun placeOf(book: BookInfo, pos: Double): Reading {
    val ch = chapterOf(book, pos)
    return Reading(
        locator = String.format(Locale.ROOT, "%.1f", pos), percent = if (book.duration > 0) minOf(100.0, pos / book.duration * 100) else 0.0,
        chapter = ch?.name, chapterIndex = ch?.let { it.index + 1 }, chapterCount = book.chapters.size, finished = false,
    )
}

@Composable
private fun SpeedSheet(speed: Float, onPick: (Float) -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose) {
        SheetHead(t("Playback speed"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (v in Speeds) PickOption(speedLabel(v), abs(speed - v) < 0.001f, sub = if (v == 1f) t("Normal") else null) { onPick(v); onClose() }
        }
    }
}

@Composable
private fun SleepSheet(sleep: Sleep?, chapter: BookChapter?, onPick: (Sleep?) -> Unit, onClose: () -> Unit) {
    BottomSheet(onClose) {
        SheetHead(t("Sleep timer"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            PickOption(t("Off"), sleep == null) { onPick(null); onClose() }
            if (chapter != null) {
                PickOption(t("At the end of the chapter"), sleep is Sleep.Chapter, sub = chapter.name) { onPick(Sleep.Chapter(chapter.index)); onClose() }
            }
            for (m in SleepMinutes) {
                PickOption(tn(m, "In {n} minute", "In {n} minutes"), (sleep as? Sleep.Time)?.minutes == m) {
                    onPick(Sleep.Time(System.currentTimeMillis() + m * 60_000L, m))
                    onClose()
                }
            }
        }
    }
}

/** The chapters (web apb-chapters): listened ones ticked, the current one lit, each one's length; a tap goes there. */
@Composable
private fun ChaptersSheet(book: BookInfo, pos: Double, onPick: (Int) -> Unit, onClose: () -> Unit) {
    val accent = LocalAccent.current
    val current = chapterOf(book, pos)?.index ?: 0
    val listened = if (book.duration > 0) (pos / book.duration * 100).roundToInt() else 0
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (current - 2).coerceAtLeast(0))
    BottomSheet(onClose) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SText(t("Chapters"), Modifier.weight(1f).alignByBaseline(), size = 17.sp, weight = 800, color = SeedyColors.White)
            SText(t("{length} · {n}% listened", "length" to duration(book.duration), "n" to listened), Modifier.alignByBaseline(), size = 13.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        LazyColumn(Modifier.weight(1f, fill = false), state = list, contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(book.chapters, key = { it.index }) { c ->
                val on = c.index == current
                val done = c.index < current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(10.dp)).background(if (on) Color(0x1AFFFFFF) else Color.Transparent)
                        .tap { onPick(c.index); onClose() }.padding(horizontal = 6.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                        if (done) SIcon(BookIcons.Check, size = 15.dp, color = SeedyColors.Text3, stroke = 3f)
                        else SText("${c.index + 1}", size = 13.sp, weight = 700, color = if (on) accent else SeedyColors.Text3)
                    }
                    SText(c.name, Modifier.weight(1f), size = 14.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.White else if (done) SeedyColors.Text3 else SeedyColors.Text, maxLines = 1)
                    SText(audioClock(c.end - c.start), size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
        }
    }
}

/** Bookmarks: "Add bookmark" here, then the saved ones (a tap goes there, × removes one). */
@Composable
private fun BookmarksSheet(
    book: BookInfo,
    pos: Double,
    bookmarks: List<Bookmark>,
    onAdd: () -> Unit,
    onRemove: (Bookmark) -> Unit,
    onPick: (Bookmark) -> Unit,
    onClose: () -> Unit,
) {
    val accent = LocalAccent.current
    fun where(at: Double): String {
        val ch = chapterOf(book, at)
        return listOfNotNull(ch?.takeIf { book.chapters.size > 1 }?.let { t("Chapter {n}", "n" to it.index + 1) }, audioClock(at - (ch?.start ?: 0.0))).joinToString(" · ")
    }
    BottomSheet(onClose) {
        SheetHead(t("Bookmarks"))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            MarkRow(BookIcons.Bookmark, accent, t("Add bookmark"), where(pos), onClick = { onAdd() })
            if (bookmarks.isEmpty()) {
                SText(t("No bookmarks yet"), Modifier.padding(horizontal = 12.dp, vertical = 12.dp), size = 13.sp, color = SeedyColors.Text3)
            }
            for (b in bookmarks) {
                MarkRow(BookIcons.BookmarkFilled, SeedyColors.Text2, b.label, where(b.seconds), onClick = { onPick(b); onClose() }, onRemove = { onRemove(b) })
            }
        }
    }
}

@Composable
private fun MarkRow(icon: io.github.arkmind.seedy.ui.icons.IconDef, ink: Color, label: String, sub: String, onClick: () -> Unit, onRemove: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onClick).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SIcon(icon, size = 20.dp, color = ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(label, size = 15.sp, weight = 600, color = SeedyColors.Text, maxLines = 1)
            SText(sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        if (onRemove != null) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).tap(onClick = onRemove), contentAlignment = Alignment.Center) {
                SIcon(BookIcons.Close, size = 16.dp, color = SeedyColors.Text3)
            }
        }
    }
}

