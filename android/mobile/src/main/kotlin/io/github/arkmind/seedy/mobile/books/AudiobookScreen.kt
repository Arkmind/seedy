package io.github.arkmind.seedy.mobile.books

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.books.uuidOf
import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.audio.AudioIcons
import io.github.arkmind.seedy.mobile.audio.OutputChip
import io.github.arkmind.seedy.mobile.browse.topInset
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.player.audio.AudioState
import io.github.arkmind.seedy.player.audio.AudioTime
import io.github.arkmind.seedy.player.audio.BookChapter
import io.github.arkmind.seedy.player.audio.BookInfo
import io.github.arkmind.seedy.player.audio.Sleep
import io.github.arkmind.seedy.ui.components.AudioCover
import io.github.arkmind.seedy.ui.components.SeekGlyph
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.coverPalette
import io.github.arkmind.seedy.ui.components.webId
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import java.text.NumberFormat
import kotlin.math.ceil

class AudiobookActions(
    val onClose: () -> Unit = {},
    val onMore: () -> Unit = {},
    val onToggle: () -> Unit = {},
    /** Seconds into the whole book. */
    val onSeek: (Double) -> Unit = {},
    val onSkip: (Double) -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onSpeed: () -> Unit = {},
    val onSleep: () -> Unit = {},
    val onChapters: () -> Unit = {},
    val onBookmarks: () -> Unit = {},
    val onOutput: () -> Unit = {},
    val onVolume: (Float) -> Unit = {},
)

/** The audiobook speeds (web book-controls.tsx). */
val Speeds = listOf(0.75f, 0.9f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f)

/** "1.2×", "1,25×". */
fun speedLabel(v: Float): String = NumberFormat.getInstance(I18n.locale).apply { maximumFractionDigits = 2 }.format(v) + "×"

/** "18:02", "1:02:03" (web audio/parts.tsx). */
fun audioClock(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = (s % 60).toString().padStart(2, '0')
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:$sec" else "$m:$sec"
}

/** The chapter of [book] at [pos] seconds. */
fun chapterOf(book: BookInfo?, pos: Double): BookChapter? {
    val chapters = book?.chapters?.takeIf { it.isNotEmpty() } ?: return null
    return chapters.lastOrNull { it.start <= pos + 0.01 } ?: chapters.first()
}

/** Minutes left on a sleep timer set to a time. */
fun sleepMinutes(sleep: Sleep?, now: Long): Int? = (sleep as? Sleep.Time)?.let { maxOf(1, ceil((it.at - now) / 60_000.0).toInt()) }

/**
 * Board "Android · Audiobook" (web AudiobookPlayer.tsx): over the cover's colour, the header "AUDIOBOOK · title", the
 * cover, "CHAPTER 7 OF 18", the chapter, "author · read by narrator", the seek bar over the chapter (the book when it
 * has one chapter), the time left in the book, the transport, the Speed, Sleep, Chapters and Bookmark tiles, then the
 * output and the volume. [art] is the cover's picture; [bookmarked] fills the bookmark when one is near.
 */
@Composable
fun AudiobookScreen(
    state: AudioState,
    time: AudioTime,
    actions: AudiobookActions,
    output: String,
    volume: Float,
    now: Long,
    art: String? = null,
    bookmarked: Boolean = false,
) {
    val book = state.book ?: return
    val accent = LocalAccent.current
    val seed = webId(uuidOf(book.id)) ?: book.id
    val rgb = rememberDominant(art)
    val tint = rgb?.let { scaledColor(it, 104f) } ?: coverPalette(seed)[1]
    val pos = time.position
    val total = book.duration.takeIf { it > 0 } ?: time.duration
    val ch = chapterOf(book, pos)
    val multi = book.chapters.size > 1
    val inChapter = if (ch != null) pos - ch.start else pos
    val length = if (ch != null) ch.end - ch.start else total
    Box(Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to tint, 0.7f to Color(0xFF121212))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 28.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = topInset()),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HeaderButton(BookIcons.ChevronDown, actions.onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SText(t("Audiobook"), size = 11.sp, weight = 800, tracking = 0.1f, uppercase = true, color = Color(0xB3FFFFFF), maxLines = 1)
                    SText(book.name, size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                }
                HeaderButton(BookIcons.More, actions.onMore)
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) {
                AudioCover(
                    art, seed, Modifier.size(290.dp).boxShadow(Color(0x80000000), blur = 60.dp, y = 24.dp, radius = 16.dp).clip(RoundedCornerShape(16.dp)),
                    title = book.name, titleSize = 26.sp,
                )
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (multi && ch != null) {
                    SText(t("Chapter {n} of {total}", "n" to ch.index + 1, "total" to book.chapters.size), size = 12.sp, weight = 800, tracking = 0.1f, uppercase = true, color = accent, maxLines = 1)
                }
                SText(if (multi && ch != null) ch.name else book.name, size = 26.sp, weight = 900, lineHeight = 1.05f, color = SeedyColors.White, maxLines = 2)
                val by = listOfNotNull(book.author, book.narrator?.let { t("read by {name}", "name" to it) }).joinToString(" · ")
                if (by.isNotEmpty()) SText(by, size = 13.sp, color = SeedyColors.TextHero, maxLines = 1)
            }
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Scrubber(
                    value = if (length > 0) (inChapter / length).toFloat() else 0f,
                    onDone = { f -> actions.onSeek((ch?.start ?: 0.0) + f * length) },
                    track = Color(0x33FFFFFF), thumb = DpSize(6.dp, 16.dp), thumbRadius = 3.dp, height = 18.dp,
                )
                Row(Modifier.fillMaxWidth()) {
                    SText(audioClock(inChapter), Modifier.weight(1f), size = 12.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
                    SText("−" + audioClock(length - inChapter), size = 12.sp, weight = 600, color = SeedyColors.Text2, maxLines = 1)
                }
            }
            SText(
                t("{time} left in the book", "time" to duration((total - pos).coerceAtLeast(0.0))),
                Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 2.dp), size = 12.sp, color = SeedyColors.Text3, maxLines = 1,
                align = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Transport(48.dp, 13.dp, actions.onPreviousChapter) { SIcon(AudioIcons.Previous, size = 24.dp, color = SeedyColors.White) }
                Transport(60.dp, 18.dp, { actions.onSkip(-30.0) }) { SeekGlyph(back = true, seconds = 30, size = 37.dp, color = SeedyColors.White) }
                Box(
                    Modifier.size(76.dp).clip(RoundedCornerShape(23.dp)).background(SeedyColors.Inverse).tap(onClick = actions.onToggle)
                        .alpha(if (state.loading && !state.playing) 0.7f else 1f),
                    contentAlignment = Alignment.Center,
                ) { SIcon(if (state.playing) AudioIcons.Pause else AudioIcons.Play, size = 30.dp, color = SeedyColors.InkInverse) }
                Transport(60.dp, 18.dp, { actions.onSkip(30.0) }) { SeekGlyph(back = false, seconds = 30, size = 37.dp, color = SeedyColors.White) }
                val last = ch == null || ch.index + 1 >= book.chapters.size
                Transport(48.dp, 13.dp, actions.onNextChapter, enabled = !last) { SIcon(AudioIcons.Next, size = 24.dp, color = SeedyColors.White) }
            }
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tile(t("Speed"), SeedyColors.White, actions.onSpeed, Modifier.weight(1f)) {
                    SText(speedLabel(state.speed), size = 18.sp, weight = 900, color = SeedyColors.White, maxLines = 1)
                }
                val sleepOn = state.sleep != null
                val sleepInk = if (sleepOn) accent else SeedyColors.White
                Tile(sleepMinutes(state.sleep, now)?.let { t("{n} min", "n" to it) } ?: t("Sleep"), sleepInk, actions.onSleep, Modifier.weight(1f)) {
                    SIcon(BookIcons.Moon, size = 20.dp, color = sleepInk)
                }
                Tile(t("Chapters"), SeedyColors.White, actions.onChapters, Modifier.weight(1f)) { SIcon(BookIcons.Chapters, size = 20.dp, color = SeedyColors.White) }
                Tile(t("Bookmark"), SeedyColors.White, actions.onBookmarks, Modifier.weight(1f)) {
                    SIcon(if (bookmarked) BookIcons.BookmarkFilled else BookIcons.Bookmark, size = 20.dp, color = SeedyColors.White)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutputChip(output, actions.onOutput)
                SIcon(BookIcons.Volume, size = 18.dp, color = SeedyColors.TextHero)
                Scrubber(
                    value = volume, onDone = actions.onVolume, onChange = actions.onVolume, modifier = Modifier.weight(1f),
                    track = Color(0x2EFFFFFF), thumb = DpSize(12.dp, 12.dp), thumbRadius = 4.dp, height = 24.dp,
                )
            }
        }
    }
}

@Composable
private fun Transport(size: Dp, radius: Dp, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(radius)).tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A 68 px tile: [top] (a value or an icon) over its label. */
@Composable
private fun Tile(label: String, ink: Color, onClick: () -> Unit, modifier: Modifier, top: @Composable () -> Unit) {
    Column(
        modifier.height(68.dp).clip(RoundedCornerShape(16.dp)).background(Color(0x12FFFFFF)).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
    ) {
        top()
        SText(label, size = 12.sp, weight = 700, color = ink, maxLines = 1)
    }
}

/**
 * A thin bar with a handle: white over [track]; tapping or dragging it moves the handle ([onChange] while dragging,
 * [onDone] where it's let go).
 */
@Composable
fun Scrubber(
    value: Float,
    onDone: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit = {},
    track: Color,
    thumb: DpSize,
    thumbRadius: Dp,
    height: Dp,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val done by rememberUpdatedState(onDone)
    val change by rememberUpdatedState(onChange)
    val v = (drag ?: value).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(height)
            .pointerInput(Unit) { detectTapGestures { o -> done((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(done); drag = null },
                    onDragCancel = { drag = null },
                ) { c, _ ->
                    val f = (c.position.x / size.width).coerceIn(0f, 1f)
                    drag = f
                    change(f)
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(v).background(SeedyColors.White))
        }
        Box(Modifier.offset(x = maxWidth * v - thumb.width / 2).size(thumb).clip(RoundedCornerShape(thumbRadius)).background(SeedyColors.White))
    }
}

