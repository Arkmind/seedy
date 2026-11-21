package io.github.arkmind.seedy.mobile.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.unit.Constraints
import io.github.arkmind.seedy.ui.theme.NotoLineRatio
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.wrapped.Wrapped
import io.github.arkmind.seedy.core.wrapped.bingeEpisode
import io.github.arkmind.seedy.core.wrapped.bingeLine
import io.github.arkmind.seedy.core.wrapped.bingeText
import io.github.arkmind.seedy.core.wrapped.clockDay
import io.github.arkmind.seedy.core.wrapped.clockMedu
import io.github.arkmind.seedy.core.wrapped.clockMood
import io.github.arkmind.seedy.core.wrapped.clockShare
import io.github.arkmind.seedy.core.wrapped.clockTitle
import io.github.arkmind.seedy.core.wrapped.coverHint
import io.github.arkmind.seedy.core.wrapped.genreLead
import io.github.arkmind.seedy.core.wrapped.genreMedu
import io.github.arkmind.seedy.core.wrapped.genreName
import io.github.arkmind.seedy.core.wrapped.genreOther
import io.github.arkmind.seedy.core.wrapped.hourChips
import io.github.arkmind.seedy.core.wrapped.hoursOf
import io.github.arkmind.seedy.core.wrapped.movieMedu
import io.github.arkmind.seedy.core.wrapped.movieMeta
import io.github.arkmind.seedy.core.wrapped.movieTickets
import io.github.arkmind.seedy.core.wrapped.movieTitle
import io.github.arkmind.seedy.core.wrapped.personalityMedu
import io.github.arkmind.seedy.core.wrapped.personalityName
import io.github.arkmind.seedy.core.wrapped.personalityText
import io.github.arkmind.seedy.core.wrapped.showMeta
import io.github.arkmind.seedy.core.wrapped.showsFootnote
import io.github.arkmind.seedy.core.wrapped.showsMedu
import io.github.arkmind.seedy.core.wrapped.statLine
import io.github.arkmind.seedy.core.wrapped.summaryAlso
import io.github.arkmind.seedy.core.wrapped.timeOf
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.cssLineBox
import io.github.arkmind.seedy.ui.wrapped.BoardCanvas
import io.github.arkmind.seedy.ui.wrapped.ClockDial
import io.github.arkmind.seedy.ui.wrapped.LocalStoryTime
import io.github.arkmind.seedy.ui.wrapped.MovieArt
import io.github.arkmind.seedy.ui.wrapped.Odometer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.arkmind.seedy.ui.wrapped.Say
import io.github.arkmind.seedy.ui.wrapped.ShowArt
import io.github.arkmind.seedy.ui.wrapped.StoryLook
import io.github.arkmind.seedy.ui.wrapped.WrappedBlue
import io.github.arkmind.seedy.ui.wrapped.WrappedGreen
import io.github.arkmind.seedy.ui.wrapped.WrappedInk
import io.github.arkmind.seedy.ui.wrapped.WrappedLilac
import io.github.arkmind.seedy.ui.wrapped.WrappedPeach
import io.github.arkmind.seedy.ui.wrapped.enter
import io.github.arkmind.seedy.ui.wrapped.meduMood
import io.github.arkmind.seedy.ui.wrapped.storyGrow
import io.github.arkmind.seedy.ui.wrapped.storyFan
import io.github.arkmind.seedy.ui.wrapped.storyIn
import io.github.arkmind.seedy.ui.wrapped.storyPop
import io.github.arkmind.seedy.ui.wrapped.storySay
import io.github.arkmind.seedy.ui.wrapped.waves

/* The phone boards' stories (Wrapped{Intro,Hours,Genres,Shows,Movie,Binge,Night,Personality,Summary}), each in the
   space between the header and the share button, 24 px from the sides. */

private val Ink = WrappedInk
private val Hero = Color(0xFFC8C8C8)

/** CSS margins, negative ones included: the box grows or shrinks by them and the content sits [start], [top] in. */
internal fun Modifier.margin(start: Dp = 0.dp, top: Dp = 0.dp, end: Dp = 0.dp, bottom: Dp = 0.dp): Modifier = layout { m, c ->
    val s = start.roundToPx()
    val e = end.roundToPx()
    val tp = top.roundToPx()
    val b = bottom.roundToPx()
    val h = s + e
    val inner = c.copy(
        minWidth = (c.minWidth - h).coerceAtLeast(0),
        maxWidth = if (c.hasBoundedWidth) (c.maxWidth - h).coerceAtLeast(0) else c.maxWidth,
        minHeight = (c.minHeight - tp - b).coerceAtLeast(0),
        maxHeight = if (c.hasBoundedHeight) (c.maxHeight - tp - b).coerceAtLeast(0) else c.maxHeight,
    )
    val p = m.measure(inner)
    layout((p.width + h).coerceAtLeast(0), (p.height + tp + b).coerceAtLeast(0)) { p.place(s, tp) }
}

/**
 * A story heading: heavy capitals, tight lines, allowed 6 px into the right margin (a line that just fits on the
 * boards then fits here too).
 */
@Composable
internal fun Heading(text: String, size: TextUnit, color: Color, modifier: Modifier = Modifier, lineHeight: Float = .98f, tracking: Float = -.03f) {
    TightText(AnnotatedString(text.uppercase()), size, 900, color, lineHeight, modifier.margin(end = (-6).dp), tracking = tracking)
}

/**
 * Text whose lines are closer than the font's own height, laid out as CSS does: Compose keeps the font's full height
 * above the first line and below the last, so the box loses the difference on both ends ([NotoLineRatio]).
 */
@Composable
internal fun TightText(
    text: AnnotatedString,
    size: TextUnit,
    weight: Int,
    color: Color,
    lineHeight: Float,
    modifier: Modifier = Modifier,
    tracking: Float = 0f,
    align: TextAlign = TextAlign.Unspecified,
) {
    BasicText(
        text,
        modifier.layout { m, c ->
            val d = ((NotoLineRatio - lineHeight) / 2f * size.toPx()).roundToInt().coerceAtLeast(0)
            val p = m.measure(c.copy(minHeight = 0, maxHeight = Constraints.Infinity))
            layout(p.width, (p.height - 2 * d).coerceAtLeast(0)) { p.place(0, -d) }
        },
        style = BaseText.copy(
            fontSize = size, fontWeight = FontWeight(weight), color = color, lineHeight = lineHeight.em,
            letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.em, textAlign = align,
        ),
    )
}

/** Text drawn as an outline only (CSS -webkit-text-stroke on transparent text). */
@Composable
internal fun OutlineText(text: String, size: TextUnit, color: Color, stroke: Dp, modifier: Modifier = Modifier, lineHeight: Float = 1f, tracking: Float = 0f) {
    val px = with(LocalDensity.current) { stroke.toPx() }
    BasicText(
        text,
        modifier.cssLineBox(size, lineHeight),
        style = BaseText.copy(fontSize = size, fontWeight = FontWeight(900), color = color, letterSpacing = tracking.em, drawStyle = Stroke(px)),
        maxLines = 1,
        softWrap = false,
    )
}

/** Medu with its line beside it: the bubble's tail points at Medu. */
@Composable
private fun MeduSays(
    mood: MeduMood,
    width: Dp,
    height: Dp,
    line: String,
    bubbleBg: Color,
    bubbleInk: Color,
    meduFirst: Boolean,
    gap: Dp,
    lift: Dp,
    meduDelay: Float,
    sayDelay: Float,
    modifier: Modifier = Modifier,
    glow: Boolean = true,
    torch: Boolean = true,
) {
    // When the story runs out of room Medu stays whole and runs over the bottom, as on the boards.
    Row(
        modifier.fillMaxWidth().wrapContentHeight(Alignment.Top, unbounded = true),
        horizontalArrangement = if (meduFirst) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.Bottom,
    ) {
        val medu: @Composable () -> Unit = { Medu(mood, width, Modifier.storyPop(meduDelay), height = height, glow = glow, torch = torch) }
        val say: @Composable (Modifier) -> Unit = { m ->
            if (line.isNotEmpty()) Say(line, bubbleBg, bubbleInk, tailEnd = !meduFirst, modifier = m.storySay(sayDelay))
        }
        if (meduFirst) {
            medu()
            say(Modifier.margin(start = -gap, bottom = lift))
        } else {
            say(Modifier.margin(end = -gap, bottom = lift))
            medu()
        }
    }
}

// ── 1 · Cover ──

@Composable
internal fun CoverStory(w: Wrapped, stories: Int, onGo: () -> Unit) {
    val accent = LocalAccent.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Medu(MeduMood.Party, 156.dp, Modifier.storyPop(.15f).margin(bottom = 30.dp), height = 206.dp, glow = false)
        // The year, with two outlines climbing out of it.
        Box(Modifier.storyIn(.45f)) {
            val year = w.year.toString()
            OutlineText(year, 118.sp, accent.copy(alpha = .22f), 2.dp, Modifier.offset(y = (-30).dp), lineHeight = .9f, tracking = -.05f)
            OutlineText(year, 118.sp, accent.copy(alpha = .45f), 2.dp, Modifier.offset(y = (-15).dp), lineHeight = .9f, tracking = -.05f)
            SText(year, size = 118.sp, weight = 900, color = accent, lineHeight = .9f, tracking = -.05f, maxLines = 1)
        }
        Heading(t("Wrapped"), 50.sp, Color.White, Modifier.storyIn(.6f).padding(top = 8.dp), lineHeight = 1f, tracking = -.02f)
        SText(
            t("Medu took notes all year. Here’s what you watched, binged and couldn’t stop rewatching."),
            Modifier.storyIn(.75f).padding(top = 16.dp).widthIn(max = 300.dp),
            size = 16.sp, lineHeight = 1.45f, color = Hero, align = TextAlign.Center,
        )
        Row(
            Modifier.storyIn(.9f).padding(top = 26.dp).height(54.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFF0F0F0)).tap(onClick = onGo).padding(horizontal = 30.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SText(t("Let’s go"), size = 17.sp, weight = 900, color = Ink, maxLines = 1)
            SIcon(WrappedIcons.Go, size = 20.dp, color = Ink)
        }
        SText(coverHint(stories), Modifier.storyIn(1f).padding(top = 12.dp), size = 12.sp, color = Color(0xFF8C8C8C), align = TextAlign.Center)
    }
}

// ── 2 · Hours ──

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HoursStory(w: Wrapped) {
    val hours = hoursOf(w.watchMs)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        SText(t("This year you watched"), Modifier.storyIn(.1f).padding(top = 18.dp), size = 22.sp, weight = 800, color = Ink)
        // The number rolls up digit by digit (an odometer), in a box as tall as the line around it.
        Box(Modifier.margin(start = (-6).dp, top = 6.dp).height((128 * 1.362).dp)) {
            Odometer(formatNumber(hours), 128.sp, Ink, tracking = -.06f)
        }
        Heading(tn(hours.toInt(), "hour", "hours"), 62.sp, Ink, Modifier.storyIn(.9f).margin(top = (-22).dp), lineHeight = 1f)
        FlowRow(Modifier.storyIn(1.3f).padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (chip in hourChips(w)) {
                Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Ink).padding(horizontal = 13.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SText(chip.big, size = 19.sp, weight = 900, color = Color.White, maxLines = 1)
                    SText(chip.small, size = 12.sp, color = Hero, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        MeduSays(
            MeduMood.Popcorn, 150.dp, 198.dp, t("I made popcorn for every single one of them."), Color.White, Ink,
            meduFirst = false, gap = 26.dp, lift = 120.dp, meduDelay = 1.5f, sayDelay = 1.8f, modifier = Modifier.margin(end = (-14).dp),
        )
    }
}

// ── 3 · Genres ──

private val GenreColours = listOf(Color.Unspecified, WrappedPeach, WrappedBlue, WrappedGreen, WrappedLilac)

@Composable
internal fun GenresStory(w: Wrapped) {
    val accent = LocalAccent.current
    val top = w.genres.maxOfOrNull { it.pct }?.takeIf { it > 0 } ?: 1
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Heading(t("Your year") + "\n" + t("in genres"), 44.sp, Color.White, Modifier.storyIn(.1f).padding(top = 18.dp))
        SText(genreLead(w), Modifier.storyIn(.25f).padding(top = 12.dp), size = 16.sp, lineHeight = 1.45f, color = Hero)
        Column(Modifier.padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            w.genres.take(5).forEachIndexed { i, g ->
                val c = GenreColours[i].takeIf { it != Color.Unspecified } ?: accent
                Row(Modifier.storyIn(.5f + .12f * i), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SText("${formatNumber(g.pct)}%", Modifier.width(82.dp), size = 38.sp, weight = 900, tracking = -.04f, color = c, maxLines = 1)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        SText(genreName(g), size = 16.sp, weight = 800, color = Color.White, maxLines = 1)
                        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color(0xFF262626))) {
                            Box(Modifier.fillMaxWidth(g.pct.toFloat() / top).height(14.dp).storyGrow(.6f + .12f * i).clip(RoundedCornerShape(7.dp)).background(c))
                        }
                    }
                }
            }
        }
        val other = genreOther(w)
        if (other.isNotEmpty()) SText(other, Modifier.storyIn(1.2f).padding(top = 14.dp), size = 12.sp, color = Color(0xFF8C8C8C))
        Spacer(Modifier.weight(1f))
        MeduSays(
            MeduMood.Think, 104.dp, 137.dp, genreMedu(w), Color.White, Ink,
            meduFirst = true, gap = 14.dp, lift = 92.dp, meduDelay = 1.4f, sayDelay = 1.7f, modifier = Modifier.margin(start = (-10).dp),
        )
    }
}

// ── 4 · Top shows ──

@Composable
internal fun ShowsStory(w: Wrapped) {
    val accent = LocalAccent.current
    val first = w.shows.firstOrNull()
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Heading(t("Your top") + "\n" + t("shows"), 44.sp, Ink, Modifier.storyIn(.1f).padding(top = 18.dp))
        if (first != null) {
            val shape = RoundedCornerShape(22.dp)
            Box(
                Modifier.storyPop(.35f).padding(top = 18.dp).fillMaxWidth().height(158.dp)
                    .boxShadow(Color(0x401E1E1E), blur = 30.dp, y = 14.dp, radius = 22.dp).clip(shape),
            ) {
                ShowArt(first, 0, wide = true, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .75f))))
                OutlineText("1", 92.sp, Color.White, 2.5.dp, Modifier.align(Alignment.TopEnd).padding(end = 16.dp), tracking = -.05f)
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        SText(first.name, size = 26.sp, weight = 900, tracking = -.01f, color = Color.White, maxLines = 1)
                        SText(showMeta(first), size = 13.sp, color = Color(0xFFD0D0D0), maxLines = 1)
                    }
                    Box(Modifier.clip(RoundedCornerShape(999.dp)).background(accent).padding(horizontal = 10.dp, vertical = 4.dp)) {
                        SText(t("Your #1"), size = 12.sp, weight = 900, color = Ink, maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            w.shows.drop(1).take(4).forEachIndexed { i, s ->
                Row(Modifier.storyIn(.6f + .12f * i), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    SText("${i + 2}", Modifier.width(30.dp), size = 30.sp, weight = 900, tracking = -.04f, color = Ink, maxLines = 1)
                    ShowArt(s, i + 1, wide = false, modifier = Modifier.size(70.dp, 40.dp).clip(RoundedCornerShape(9.dp)))
                    Column(Modifier.weight(1f)) {
                        SText(s.name, size = 17.sp, weight = 900, color = Ink, maxLines = 1)
                        SText(tn(s.episodes, "{n} episode", "{n} episodes"), size = 13.sp, color = Ink.copy(alpha = .7f), maxLines = 1)
                    }
                    SText(t("{n} h", "n" to formatNumber(hoursOf(s.watchMs))), size = 15.sp, weight = 900, color = Ink, maxLines = 1)
                }
            }
        }
        val foot = showsFootnote(w)
        if (foot.isNotEmpty()) SText(foot, Modifier.storyIn(1.3f).padding(top = 14.dp), size = 14.sp, weight = 600, lineHeight = 1.45f, color = Ink.copy(alpha = .75f))
        Spacer(Modifier.weight(1f))
        MeduSays(
            MeduMood.Wave, 78.dp, 103.dp, showsMedu(w), Ink, Color.White,
            meduFirst = false, gap = 14.dp, lift = 52.dp, meduDelay = 1.5f, sayDelay = 1.8f, modifier = Modifier.margin(end = (-10).dp, bottom = (-6).dp),
        )
    }
}

// ── 5 · The movie ──

/** A cinema ticket: a dark stub with a half-circle notch out of each side. */
private val Ticket = GenericShape { size, _ ->
    val r = 5f * (size.height / 34f)
    val body = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset.Zero, size), androidx.compose.ui.geometry.CornerRadius(8f * size.height / 34f))) }
    val notches = androidx.compose.ui.graphics.Path().apply {
        addOval(Rect(Offset(0f, size.height / 2f), r))
        addOval(Rect(Offset(size.width, size.height / 2f), r))
    }
    addPath(androidx.compose.ui.graphics.Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, body, notches))
}

@Composable
internal fun MovieStory(w: Wrapped) {
    val accent = LocalAccent.current
    val m = w.movie ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Heading(movieTitle(w), 40.sp, Ink, Modifier.storyIn(.1f).padding(top = 18.dp))
        Box(Modifier.fillMaxWidth().padding(top = 26.dp), contentAlignment = Alignment.TopCenter) {
            val shape = RoundedCornerShape(16.dp)
            MovieArt(
                m,
                Modifier.storyPop(.35f, rotation = -4f).size(196.dp, 294.dp)
                    .boxShadow(Color(0x593C1E00), blur = 44.dp, y = 22.dp, radius = 16.dp).clip(shape),
            )
            if (m.views >= 2) {
                Column(
                    Modifier.align(Alignment.TopEnd).offset(x = (-6).dp, y = (-10).dp).storyPop(.8f, rotation = 8f).size(108.dp).clip(CircleShape).background(Ink),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SText("${formatNumber(m.views)}×", size = 50.sp, weight = 900, lineHeight = .9f, tracking = -.04f, color = Color.White, maxLines = 1)
                    SText(t("watched"), size = 11.sp, weight = 800, color = accent, maxLines = 1)
                }
            }
        }
        Column(Modifier.storyIn(1f).fillMaxWidth().padding(top = 22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SText(m.name, size = 30.sp, weight = 900, tracking = -.02f, color = Ink, align = TextAlign.Center, maxLines = 2)
            SText(movieMeta(w), size = 14.sp, weight = 600, color = Ink.copy(alpha = .75f), align = TextAlign.Center)
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            movieTickets(w).forEachIndexed { i, month ->
                Box(Modifier.storyPop(1.2f + .12f * i).size(58.dp, 34.dp).clip(Ticket).background(Ink), contentAlignment = Alignment.Center) {
                    SText(month, size = 11.sp, weight = 800, lineHeight = 1.15f, color = Color.White, maxLines = 1)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        MeduSays(
            MeduMood.Love, 96.dp, 127.dp, movieMedu(w), Color.White, Ink,
            meduFirst = true, gap = 10.dp, lift = 80.dp, meduDelay = 1.7f, sayDelay = 2f, modifier = Modifier.margin(start = (-12).dp),
        )
    }
}

// ── 6 · The binge ──

@Composable
internal fun BingeStory(w: Wrapped, look: StoryLook) {
    val accent = LocalAccent.current
    val b = w.binge ?: return
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Heading(t("Your biggest") + "\n" + t("binge"), 40.sp, Ink, Modifier.storyIn(.1f).padding(top = 18.dp))
        Row(Modifier.storyIn(.3f).padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SText(formatNumber(b.episodes), Modifier.alignByBaseline(), size = 104.sp, weight = 900, lineHeight = .9f, tracking = -.06f, color = Ink, maxLines = 1)
            TightText(AnnotatedString(t("episodes") + "\n" + t("in one go")), 26.sp, 900, Ink, 1f, Modifier.alignByBaseline())
        }
        // One box per episode (at most 12), as wide as the minutes watched of it.
        val minutes = b.minutes?.takeIf { it.size == b.episodes || it.isNotEmpty() }
        val boxes = (minutes?.size ?: b.episodes).coerceIn(0, 12)
        Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in 0 until boxes) {
                val weight = minutes?.getOrNull(i)?.coerceAtLeast(1)?.toFloat() ?: 1f
                Box(Modifier.weight(weight).storyPop(.7f + .08f * i).height(30.dp).clip(RoundedCornerShape(8.dp)).background(Ink), contentAlignment = Alignment.Center) {
                    SText("${i + 1}", size = 11.sp, weight = 900, color = look.bg, maxLines = 1)
                }
            }
        }
        Row(Modifier.storyIn(1.2f).padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SText(timeOf(b.start), size = 12.sp, weight = 800, color = Ink, maxLines = 1)
            SText(bingeLine(w), Modifier.weight(1f, fill = false).padding(horizontal = 6.dp), size = 12.sp, weight = 800, color = Ink.copy(alpha = .7f), maxLines = 1)
            SText(timeOf(b.end), size = 12.sp, weight = 800, color = Ink, maxLines = 1)
        }
        // "Still watching?" cards fanned out, with how many times it was asked.
        Box(Modifier.padding(top = 22.dp).fillMaxWidth().height(158.dp)) {
            val card = RoundedCornerShape(18.dp)
            val shadow = Color(0x4D001E3C)
            for ((top, turn, alpha, delay) in listOf(Fan(22, -8f, .35f, 1.3f), Fan(14, 6f, .6f, 1.38f))) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = top.dp).storyFan(delay, turn, alpha).size(246.dp, 108.dp)
                        .boxShadow(shadow, blur = 26.dp, y = 10.dp, radius = 18.dp).clip(card).background(Ink),
                )
            }
            // 246 px wide inside its padding, its left edge where the others' is (CSS content-box).
            Column(
                Modifier.align(Alignment.TopCenter).offset(x = 14.dp).padding(top = 26.dp).storyFan(1.45f, 0f, 1f).width(274.dp)
                    .boxShadow(shadow, blur = 26.dp, y = 10.dp, radius = 18.dp).clip(card).background(Ink).padding(14.dp),
            ) {
                SText(t("Still watching?"), size = 16.sp, weight = 900, color = Color.White, maxLines = 1)
                SText(bingeEpisode(w), Modifier.padding(top = 2.dp), size = 12.sp, color = Color(0xFFA1A1A1), maxLines = 1)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.weight(1f).height(30.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFFF0F0F0)), contentAlignment = Alignment.Center) {
                        SText(t("Yes, keep going"), size = 12.sp, weight = 800, color = Ink, maxLines = 1)
                    }
                    Box(Modifier.height(30.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xFF2A2A2A)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                        SText(t("Stop"), size = 12.sp, weight = 700, color = Color.White, maxLines = 1)
                    }
                }
            }
            if (w.still.asked > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(end = 4.dp).storyPop(1.8f, rotation = 10f).clip(RoundedCornerShape(999.dp)).background(accent).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    SText("×${formatNumber(w.still.asked)}", size = 15.sp, weight = 900, color = Ink, maxLines = 1)
                }
            }
        }
        SText(bingeText(w), Modifier.storyIn(1.9f).padding(top = 14.dp), size = 15.sp, weight = 700, lineHeight = 1.45f, color = Ink)
        Spacer(Modifier.weight(1f))
        MeduSays(
            MeduMood.Search, 80.dp, 106.dp, t("Did you… blink at all?"), Ink, Color.White,
            meduFirst = true, gap = 8.dp, lift = 56.dp, meduDelay = 2.1f, sayDelay = 2.4f, torch = false,
            modifier = Modifier.margin(start = (-10).dp, bottom = (-6).dp),
        )
    }
}

private data class Fan(val top: Int, val turn: Float, val alpha: Float, val delay: Float)

// ── 7 · The clock ──

@Composable
internal fun ClockStory(w: Wrapped, look: StoryLook) {
    val accent = LocalAccent.current
    val clock = w.clock ?: return
    val light = !look.dark
    val hot = if (light) Ink else accent
    val (lead, kind) = clockTitle(w)
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        ClockHeading(lead, kind, if (light) Color.White else accent, look.ink, Modifier.storyIn(.1f).padding(top = 18.dp))
        ClockDial(
            clock.hours, clock.peak, t("your peak"),
            hot = hot, cold = look.ink.copy(alpha = if (light) .25f else .32f), ink = look.ink,
            labels = if (light) Ink.copy(alpha = .55f) else Color(0xFF8C8C8C), sub = if (light) Ink.copy(alpha = .65f) else Color(0xFFA1A1A1),
            modifier = Modifier.storyIn(.3f).padding(top = 8.dp).align(Alignment.CenterHorizontally).size(270.dp),
        )
        val share = clockShare(w)
        val at = share.indexOf("{pct}")
        val pct = "${formatNumber(clock.pct)}%"
        val body = if (light) Ink.copy(alpha = .8f) else Hero
        androidx.compose.foundation.text.BasicText(
            buildAnnotatedString {
                if (at >= 0) {
                    append(share.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight(700), color = look.ink)) { append(pct) }
                    append(share.substring(at + 5))
                } else append(share)
                append(" ")
                append(clockDay(w))
            },
            Modifier.storyIn(1.4f).padding(top = 6.dp),
            style = BaseText.copy(fontSize = 16.sp, lineHeight = 1.45.em, color = body),
        )
        Spacer(Modifier.weight(1f))
        MeduSays(
            meduMood(clockMood(w)), 104.dp, 137.dp, clockMedu(w), Color.White, Ink,
            meduFirst = false, gap = 18.dp, lift = 70.dp, meduDelay = 1.6f, sayDelay = 1.9f, modifier = Modifier.margin(end = (-10).dp),
        )
    }
}

@Composable
private fun ClockHeading(lead: String, kind: String, highlight: Color, ink: Color, modifier: Modifier) {
    val text = buildAnnotatedString {
        append(lead.uppercase())
        append("\n")
        withStyle(SpanStyle(color = highlight)) { append(kind.uppercase()) }
    }
    TightText(text, 44.sp, 900, ink, .98f, modifier.margin(end = (-6).dp), tracking = -.03f)
}

// ── 8 · The personality ──

@Composable
internal fun PersonalityStory(w: Wrapped) {
    val accent = LocalAccent.current
    val key = w.personality.key
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        SText(t("Your watch personality"), Modifier.storyIn(.1f).padding(top = 18.dp), size = 20.sp, weight = 800, color = Ink)
        Box(Modifier.storyPop(.4f).padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Ink).padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 20.dp)) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(52.dp).clip(RoundedCornerShape(16.dp)).background(accent), contentAlignment = Alignment.Center) {
                SIcon(WrappedIcons.Bolt, size = 28.dp, color = Ink)
            }
            Column {
                // An inline label: it sits on the card's 16 px line, as on the board.
                SText(t("You are"), Modifier.padding(top = 4.dp, bottom = 2.dp), size = 12.sp, weight = 800, tracking = .12f, color = accent, uppercase = true, maxLines = 1)
                Heading(personalityName(key), 42.sp, Color.White, Modifier.padding(top = 6.dp), lineHeight = .95f)
                SText(personalityText(key), Modifier.padding(top = 12.dp), size = 15.sp, lineHeight = 1.45f, color = Hero)
            }
        }
        Column(Modifier.padding(top = 18.dp)) {
            w.personality.stats.take(3).forEachIndexed { i, s ->
                val line = statLine(s)
                Row(
                    Modifier.storyIn(1.1f + .12f * i).fillMaxWidth().drawWithContent {
                        drawRect(Ink.copy(alpha = .15f), size = androidx.compose.ui.geometry.Size(size.width, 2.dp.toPx()))
                        drawContent()
                    }.padding(top = 14.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SText(line.big, Modifier.width(126.dp).alignByBaseline(), size = 24.sp, weight = 900, tracking = -.02f, color = Ink, maxLines = 1)
                    SText(line.small, Modifier.weight(1f).alignByBaseline(), size = 14.sp, weight = 600, lineHeight = 1.35f, color = Ink)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        MeduSays(
            MeduMood.Yay, 108.dp, 143.dp, personalityMedu(key), Ink, Color.White,
            meduFirst = false, gap = 16.dp, lift = 84.dp, meduDelay = 1.6f, sayDelay = 1.9f, modifier = Modifier.margin(end = (-8).dp),
        )
    }
}

// ── 9 · Summary ──

@Composable
internal fun SummaryStory(w: Wrapped, cardLayer: GraphicsLayer) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        SText(
            t("That’s your {year}. Thanks for watching with Medu.", "year" to w.year),
            Modifier.storyIn(.05f).padding(top = 8.dp), size = 18.sp, weight = 900, color = Color.White, align = TextAlign.Center,
        )
        Box(Modifier.storyPop(.3f).padding(top = 14.dp).record(cardLayer)) {
            SummaryCard(w, accent, Modifier.width(342.dp))
        }
    }
}

/** The card the summary shares and saves: the year at a glance on the accent. */
@Composable
internal fun SummaryCard(w: Wrapped, accent: Color, modifier: Modifier = Modifier) {
    val first = w.shows.firstOrNull()
    val label: @Composable (String) -> Unit = { SText(it, size = 11.sp, weight = 800, tracking = .08f, color = Ink.copy(alpha = .6f), uppercase = true, maxLines = 1) }
    Box(modifier.clip(RoundedCornerShape(28.dp)).background(accent)) {
        BoardCanvas(Modifier.matchParentSize()) { _, _ -> waves(listOf(470f, 510f), 18f, 20f, Color.White.copy(alpha = .25f), to = 440f) }
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Medu(MeduMood.Party, 64.dp, height = 84.dp, glow = false)
                Column {
                    SText(t("Seedy Wrapped"), size = 12.sp, weight = 800, tracking = .08f, color = Ink.copy(alpha = .6f), uppercase = true, maxLines = 1)
                    SText(t("My {year}", "year" to w.year), size = 26.sp, weight = 900, lineHeight = 1f, tracking = -.02f, color = Ink, maxLines = 1)
                }
            }
            if (first != null) {
                Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(128.dp).clip(RoundedCornerShape(18.dp))) {
                    ShowArt(first, 0, wide = true, modifier = Modifier.fillMaxSize())
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xCC0E0E0E)).padding(horizontal = 9.dp, vertical = 3.dp)) {
                        SText("#1 · ${first.name}", size = 11.sp, weight = 800, color = Color.White, maxLines = 1)
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (w.shows.isNotEmpty()) {
                    Column(Modifier.weight(1.15f)) {
                        // An inline label on the card's 16 px line (the other column's are blocks).
                        Box(Modifier.padding(top = 5.dp, bottom = 2.dp)) { label(t("Top shows")) }
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            w.shows.take(5).forEachIndexed { i, s ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SText("${i + 1}", Modifier.width(12.dp).alignByBaseline(), size = 14.sp, weight = 900, color = Ink.copy(alpha = .55f), maxLines = 1)
                                    SText(s.name, Modifier.alignByBaseline(), size = 14.sp, weight = 800, color = Ink, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        label(t("Hours"))
                        SText(formatNumber(hoursOf(w.watchMs)), size = 30.sp, weight = 900, lineHeight = 1.05f, tracking = -.02f, color = Ink, maxLines = 1)
                    }
                    w.genres.firstOrNull()?.let { g ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            label(t("Top genre"))
                            TightText(AnnotatedString(genreName(g)), 18.sp, 900, Ink, 1.05f, tracking = -.02f)
                        }
                    }
                    w.movie?.let { m ->
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            label(t("Top movie"))
                            TightText(AnnotatedString(m.name), 18.sp, 900, Ink, 1.05f, tracking = -.02f)
                        }
                    }
                }
            }
            Row(
                Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Ink).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    SText(t("Watch personality"), size = 10.sp, weight = 800, tracking = .1f, color = accent, uppercase = true, maxLines = 1)
                    SText(personalityName(w.personality.key), size = 17.sp, weight = 900, color = Color.White, maxLines = 1)
                }
                SIcon(WrappedIcons.Bolt, size = 24.dp, color = Color.White)
            }
            Row(Modifier.padding(top = 12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SText(summaryAlso(w), size = 12.sp, weight = 800, color = Ink, maxLines = 1)
                SText("seedy", size = 12.sp, weight = 800, color = Ink.copy(alpha = .6f), maxLines = 1)
            }
        }
    }
}

/** Share (the card), Save image and Watch Wrapped again. */
@Composable
internal fun SummaryButtons(modifier: Modifier, onShare: () -> Unit, onSave: () -> Unit, onAgain: () -> Unit) {
    val again = t("Watch Wrapped again")
    Row(modifier.storyIn(1f).fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFFF0F0F0)).tap(onClick = onShare),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            SIcon(WrappedIcons.Share, size = 19.dp, color = Ink)
            SText(t("Share"), size = 16.sp, weight = 900, color = Ink, maxLines = 1)
        }
        Row(
            Modifier.height(52.dp).clip(RoundedCornerShape(999.dp)).background(Color(0xFF262626)).tap(onClick = onSave).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SIcon(WrappedIcons.Save, size = 19.dp, color = Color.White)
            SText(t("Save image"), size = 15.sp, weight = 800, color = Color.White, maxLines = 1)
        }
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF262626)).tap(onClick = onAgain)
                .semantics { contentDescription = again },
            contentAlignment = Alignment.Center,
        ) { SIcon(WrappedIcons.Again, size = 20.dp, color = Color.White) }
    }
}
