package io.github.arkmind.seedy.tv.wrapped

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.wrapped.StoryKey
import io.github.arkmind.seedy.core.wrapped.Wrapped
import io.github.arkmind.seedy.core.wrapped.bingeEpisode
import io.github.arkmind.seedy.core.wrapped.bingeLine
import io.github.arkmind.seedy.core.wrapped.bingeText
import io.github.arkmind.seedy.core.wrapped.clockDay
import io.github.arkmind.seedy.core.wrapped.clockShare
import io.github.arkmind.seedy.core.wrapped.clockTitle
import io.github.arkmind.seedy.core.wrapped.genreLead
import io.github.arkmind.seedy.core.wrapped.genreName
import io.github.arkmind.seedy.core.wrapped.genreOther
import io.github.arkmind.seedy.core.wrapped.hourChips
import io.github.arkmind.seedy.core.wrapped.hoursOf
import io.github.arkmind.seedy.core.wrapped.movieMeta
import io.github.arkmind.seedy.core.wrapped.movieTickets
import io.github.arkmind.seedy.core.wrapped.movieTitle
import io.github.arkmind.seedy.core.wrapped.personalityName
import io.github.arkmind.seedy.core.wrapped.personalityText
import io.github.arkmind.seedy.core.wrapped.showMeta
import io.github.arkmind.seedy.core.wrapped.showsFootnote
import io.github.arkmind.seedy.core.wrapped.statLine
import io.github.arkmind.seedy.core.wrapped.storiesOf
import io.github.arkmind.seedy.core.wrapped.timeOf
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.rememberLoopClock
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.wrapped.storySlide
import io.github.arkmind.seedy.ui.wrapped.BoardCanvas
import io.github.arkmind.seedy.ui.wrapped.ClockDial
import io.github.arkmind.seedy.ui.wrapped.Heading
import io.github.arkmind.seedy.ui.wrapped.LocalStoryLoop
import io.github.arkmind.seedy.ui.wrapped.LocalStoryTime
import io.github.arkmind.seedy.ui.wrapped.MovieArt
import io.github.arkmind.seedy.ui.wrapped.Odometer
import io.github.arkmind.seedy.ui.wrapped.OutlineText
import io.github.arkmind.seedy.ui.wrapped.Segments
import io.github.arkmind.seedy.ui.wrapped.ShowArt
import io.github.arkmind.seedy.ui.wrapped.StoryLook
import io.github.arkmind.seedy.ui.wrapped.StoryPlayer
import io.github.arkmind.seedy.ui.wrapped.SummaryCard
import io.github.arkmind.seedy.ui.wrapped.TightText
import io.github.arkmind.seedy.ui.wrapped.WrappedIcons
import io.github.arkmind.seedy.ui.wrapped.WrappedInk
import io.github.arkmind.seedy.ui.wrapped.WrappedPeach
import io.github.arkmind.seedy.ui.wrapped.WrappedBlue
import io.github.arkmind.seedy.ui.wrapped.WrappedGreen
import io.github.arkmind.seedy.ui.wrapped.WrappedLilac
import io.github.arkmind.seedy.ui.wrapped.loopSeconds
import io.github.arkmind.seedy.ui.wrapped.margin
import io.github.arkmind.seedy.ui.wrapped.rememberStoryPlayer
import io.github.arkmind.seedy.ui.wrapped.storyBackdrop
import io.github.arkmind.seedy.ui.wrapped.storyFan
import io.github.arkmind.seedy.ui.wrapped.storyGrow
import io.github.arkmind.seedy.ui.wrapped.storyIn
import io.github.arkmind.seedy.ui.wrapped.storyLook
import io.github.arkmind.seedy.ui.wrapped.storyPop
import io.github.arkmind.seedy.ui.wrapped.storyTime
import io.github.arkmind.seedy.core.wrapped.loverChips
import io.github.arkmind.seedy.core.wrapped.loverOther
import io.github.arkmind.seedy.core.wrapped.loverPct
import io.github.arkmind.seedy.core.wrapped.loverShows
import io.github.arkmind.seedy.core.wrapped.loverSplitLabel
import io.github.arkmind.seedy.core.wrapped.loverTitle
import io.github.arkmind.seedy.core.wrapped.loverUnit
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.IntrinsicSize
import io.github.arkmind.seedy.core.i18n.tc
import io.github.arkmind.seedy.core.wrapped.MeduLine
import io.github.arkmind.seedy.core.wrapped.arrivalName
import io.github.arkmind.seedy.core.wrapped.arrivalTime
import io.github.arkmind.seedy.core.wrapped.meduLine
import io.github.arkmind.seedy.core.wrapped.oddFacts
import io.github.arkmind.seedy.ui.wrapped.MeduSpot
import io.github.arkmind.seedy.ui.wrapped.OddestArt
import io.github.arkmind.seedy.ui.wrapped.WrappedRust

/*
 * Board "Seedy Wrapped · TV" (1920 × 1080, top shows): the stories sideways, the title and its words on the left with
 * Medu under them (her line beside her), the story's content on the right, the keys' hints top right. Only the top shows
 * have a board; the others, requests and the oddest request included, are laid out the same way. ◀ ▶ move, OK holds the story (on the cover it goes on, on the summary it starts
 * over), Back closes. Nothing to share on a TV.
 */

private val Ink = WrappedInk

/** The phone boards' backdrops, 2.46 × bigger, as the TV board draws the top shows' waves. */
private const val BackdropScale = 2.46f

@Composable
fun TvWrappedScreen(wrapped: Wrapped, onClose: () -> Unit, start: Int = 0, fixedProgress: Float? = null) {
    val stories = remember(wrapped) { storiesOf(wrapped) }
    if (stories.isEmpty()) return
    val player = rememberStoryPlayer(stories.size, start, fixedProgress)
    val story = stories[player.index.coerceIn(0, stories.lastIndex)]
    val accent = LocalAccent.current
    val look = storyLook(story, accent, wrapped.clock?.kind, loverShows(wrapped))
    val loop = rememberLoopClock()
    val focus = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { focus.focusSoon() }

    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF0E0E0E)).focusRequester(focus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { player.previous(); true }
                    Key.DirectionRight -> { player.next(); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when (story) {
                            StoryKey.Cover -> player.next()
                            StoryKey.Summary -> { player.paused = false; player.go(0) }
                            else -> player.paused = !player.paused
                        }
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> true
                    else -> false
                }
            }
            .focusable(),
    ) {
        // The story slides in when it changes; the bar and the key hints stay put.
        Box(Modifier.fillMaxSize().storySlide(player.index).background(look.bg)) {
            look.brush?.let { b -> Box(Modifier.fillMaxSize().background(b)) }
            BoardCanvas(Modifier.fillMaxSize(), unit = BackdropScale / 2f) { w, h ->
                val turn = when (story) {
                    StoryKey.Cover -> Offset(w / 2f, h * .52f)
                    StoryKey.Movie -> Offset(w * .71f, h * .5f)
                    StoryKey.Oddest -> Offset(w * .54f, h * .41f)
                    else -> null
                }
                storyBackdrop(story, accent, w, h, loop.loopSeconds(), wrapped.clock?.kind, bubbles = false, bottomShift = 70.7f, focus = turn, showsWon = loverShows(wrapped), band = false)
            }
            CompositionLocalProvider(LocalStoryTime provides player.storyTime(), LocalStoryLoop provides loop) {
                key(player.index) { TvStory(story, wrapped, look) }
            }
        }
        Header(wrapped.year, player, story, look)
    }
}

/** The bar, little Medu with "Seedy Wrapped 2026", and what the keys do. */
@Composable
private fun Header(year: Int, player: StoryPlayer, story: StoryKey, look: StoryLook) {
    Column(Modifier.fillMaxWidth().padding(start = 96.bp, end = 96.bp, top = 48.bp), verticalArrangement = Arrangement.spacedBy(22.bp)) {
        Segments(player, look.ink, Modifier.fillMaxWidth(), height = 5.bp, gap = 8.bp, radius = 3.bp, track = .22f)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
            Medu(MeduMood.Idle, 40.bp, height = 53.bp, glow = true, bubbles = true)
            SText(t("Seedy Wrapped"), size = 24.bsp, weight = 900, color = look.ink, maxLines = 1)
            SText(year.toString(), size = 24.bsp, weight = 700, color = look.ink.copy(alpha = .6f), maxLines = 1)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(34.bp), verticalAlignment = Alignment.CenterVertically) {
                KeyHint(null, t("Move"), look.ink)
                val ok = when {
                    story == StoryKey.Cover -> t("Let’s go")
                    story == StoryKey.Summary -> t("Watch Wrapped again")
                    player.paused -> t("Play")
                    else -> t("Pause")
                }
                KeyHint("OK", ok, look.ink)
                KeyHint(t("Back"), t("Close"), look.ink)
            }
        }
    }
}

private val ArrowLeft = io.github.arkmind.seedy.ui.icons.IconDef(0f, listOf(io.github.arkmind.seedy.ui.icons.IconPart("M19 4v16L5 12z", filled = true)))
private val ArrowRight = io.github.arkmind.seedy.ui.icons.IconDef(0f, listOf(io.github.arkmind.seedy.ui.icons.IconPart("M5 4v16l14-8z", filled = true)))

/** A key as the TV board draws it (a keycap ringed in the story's ink) and what it does; no [key]: ◀ ▶. */
@Composable
private fun KeyHint(key: String?, label: String, ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp)) {
        Box(
            Modifier.drawBehind {
                val w = 2.bp.toPx()
                drawRoundRect(ink.copy(alpha = .45f), Offset(w / 2f, w / 2f), Size(size.width - w, size.height - w), CornerRadius(9.bp.toPx() - w / 2f), style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            }.padding(horizontal = 12.bp, vertical = 4.bp),
        ) {
            if (key != null) SText(key, size = 20.bsp, weight = 900, color = ink, maxLines = 1)
            else Row(Modifier.height(27.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.bp)) {
                SIcon(ArrowLeft, size = 18.bp, color = ink)
                SIcon(ArrowRight, size = 18.bp, color = ink)
            }
        }
        SText(label, size = 20.bsp, weight = 700, color = ink, maxLines = 1)
    }
}

// ── Layout ──

/** The left column (from 96 px, 230 px down, 700 px wide): title, words, Medu. */
@Composable
private fun Left(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(start = 96.bp, top = 230.bp).width(700.bp), content = content)
}

/** The right column (from 900 px to 96 px off the edge, 230 px down). */
@Composable
private fun Right(gap: Dp = 30.bp, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().padding(start = 900.bp, end = 96.bp, top = 230.bp), verticalArrangement = Arrangement.spacedBy(gap), content = content)
}

@Composable
private fun Title(text: String, ink: Color, size: Int = 128) {
    Heading(text, size.bsp, ink, Modifier.storyIn(.1f, 40.bp), lineHeight = .92f, tracking = -.035f)
}

@Composable
private fun Lede(text: String, ink: Color) {
    if (text.isEmpty()) return
    SText(text, Modifier.storyIn(.25f, 40.bp).padding(top = 30.bp).widthIn(max = 600.bp), size = 32.bsp, weight = 700, lineHeight = 1.4f, color = ink.copy(alpha = .8f))
}

/**
 * Medu with her line beside her, under the words (the TV board's: 180 px, a bubble 340 px wide at most centred on her
 * bell, 20 px away). A dark bubble on light stories ([dark]: a dark story, a white one).
 */
@Composable
private fun TvMedu(line: MeduLine?, dark: Boolean, top: Int = 64) {
    if (line == null) return
    MeduSpot(
        line, meduFirst = true, bubbleBg = if (dark) Color.White else Ink, bubbleInk = if (dark) Ink else Color.White, at = 1.4f,
        modifier = Modifier.padding(top = top.bp).wrapContentHeight(Alignment.Top, unbounded = true),
        meduWidth = 180.bp, bubbleBox = 169.bp, gap = 20.bp, sayDelay = 1.7f,
        textSize = 26.bsp, maxWidth = 340.bp, padX = 24.bp, padY = 18.bp, radius = 26.bp, tail = 22.bp, tailOut = 8.bp, tailRadius = 4.bp,
    )
}

@Composable
private fun TvStory(story: StoryKey, w: Wrapped, look: StoryLook) {
    when (story) {
        StoryKey.Cover -> Cover(w)
        StoryKey.Hours -> Hours(w)
        StoryKey.Lover -> Lover(w)
        StoryKey.Genres -> Genres(w)
        StoryKey.Shows -> Shows(w)
        StoryKey.Movie -> Movie(w)
        StoryKey.Binge -> Binge(w, look)
        StoryKey.Clock -> Clock(w, look)
        StoryKey.Requests -> Requests(w)
        StoryKey.Oddest -> Oddest(w)
        StoryKey.Personality -> Personality(w)
        StoryKey.Summary -> Summary(w)
    }
}

// ── 1 · Cover ──

@Composable
private fun Cover(w: Wrapped) {
    val accent = LocalAccent.current
    Column(Modifier.fillMaxSize().padding(top = 150.bp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Medu(MeduMood.Party, 232.bp, Modifier.storyPop(.15f).margin(bottom = 14.bp), height = 306.bp, glow = false)
        SText(w.year.toString(), Modifier.storyIn(.45f, 40.bp), size = 200.bsp, weight = 900, color = accent, lineHeight = .9f, tracking = -.05f, maxLines = 1)
        Heading(t("Wrapped"), 84.bsp, Color.White, Modifier.storyIn(.6f, 40.bp).padding(top = 12.bp), lineHeight = 1f, tracking = -.02f)
        SText(
            t("Medu took notes all year. Here’s what you watched, binged and couldn’t stop rewatching."),
            Modifier.storyIn(.75f, 40.bp).padding(top = 24.bp).widthIn(max = 900.bp),
            size = 30.bsp, lineHeight = 1.45f, color = Color(0xFFC8C8C8), align = TextAlign.Center,
        )
        Row(
            Modifier.storyIn(.9f, 40.bp).padding(top = 36.bp).height(84.bp).clip(RoundedCornerShape(999.bp)).background(Color(0xFFF0F0F0)).padding(horizontal = 46.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            SText(t("Let’s go"), size = 28.bsp, weight = 900, color = Ink, maxLines = 1)
            SIcon(WrappedIcons.Go, size = 30.bp, color = Ink)
        }
    }
}

// ── 2 · Hours ──

@Composable
private fun Hours(w: Wrapped) {
    val hours = hoursOf(w.watchMs)
    Left {
        SText(t("This year you watched"), Modifier.storyIn(.1f, 40.bp), size = 44.bsp, weight = 800, color = Ink)
        Box(Modifier.margin(start = (-12).bp, top = 8.bp).height((240 * 1.362).bp)) { Odometer(formatNumber(hours), 240.bsp, Ink, tracking = -.06f) }
        Heading(tn(hours.toInt(), "hour", "hours"), 116.bsp, Ink, Modifier.storyIn(.9f, 40.bp).margin(top = (-44).bp), lineHeight = 1f)
        TvMedu(meduLine(StoryKey.Hours, w), dark = false, top = 50)
    }
    Right(gap = 22.bp) {
        hourChips(w).forEachIndexed { i, chip ->
            Column(
                Modifier.storyIn(1.3f + .1f * i, 40.bp).clip(RoundedCornerShape(36.bp)).background(Ink).padding(horizontal = 34.bp, vertical = 26.bp),
                verticalArrangement = Arrangement.spacedBy(2.bp),
            ) {
                SText(chip.big, size = 56.bsp, weight = 900, color = Color.White, maxLines = 1)
                SText(chip.small, size = 26.bsp, color = Color(0xFFC8C8C8), maxLines = 1)
            }
        }
    }
}

// ── 3 · Shows or movies? ──

/** No TV board: the phone board laid out like the others. The winner on the left, the split and the other side (on its
 * own colour, the phone's slanted band) on the right. */
@Composable
private fun Lover(w: Wrapped) {
    val shows = loverShows(w)
    val count = if (shows) w.showsWatched else w.moviesPlayed
    val pct = loverPct(w)
    val (otherCount, otherLine) = loverOther(w)
    val (lead, kind) = loverTitle(w)
    val (win, lose) = if (shows) t("Shows") to t("Movies") else t("Movies") to t("Shows")
    Left {
        SText(t("Shows or movies?"), Modifier.storyIn(.1f, 40.bp), size = 36.bsp, weight = 800, color = Ink)
        Box(Modifier.padding(top = 10.bp)) { Title("$lead\n$kind", Ink, size = 80) }
        Row(
            Modifier.padding(top = 26.bp).semantics(mergeDescendants = true) { contentDescription = "${formatNumber(count)} ${loverUnit(count, shows)}" },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(20.bp),
        ) {
            Box(Modifier.height(184.bp).margin(end = (-22).bp), contentAlignment = Alignment.BottomStart) { Odometer(formatNumber(count), 184.bsp, Ink, tracking = -.06f, delay = .45f) }
            SText(loverUnit(count, shows), Modifier.storyIn(.9f, 40.bp).padding(bottom = 12.bp), size = 56.bsp, weight = 900, color = Ink, lineHeight = 1f, maxLines = 1)
        }
        TvMedu(meduLine(StoryKey.Lover, w), dark = false, top = 40)
    }
    Right(gap = 34.bp) {
        FlowRow(Modifier.storyIn(1f, 40.bp), horizontalArrangement = Arrangement.spacedBy(12.bp), verticalArrangement = Arrangement.spacedBy(12.bp)) {
            for (chip in loverChips(w)) {
                SText(chip, Modifier.clip(RoundedCornerShape(999.bp)).background(Ink).padding(horizontal = 22.bp, vertical = 12.bp), size = 26.bsp, weight = 800, color = Color.White, maxLines = 1)
            }
        }
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = loverSplitLabel(w) }) {
            Box(Modifier.fillMaxWidth().height(64.bp).clip(RoundedCornerShape(20.bp)).background(Color.White.copy(alpha = .55f))) {
                Box(Modifier.fillMaxWidth(pct / 100f).fillMaxHeight().storyGrow(1.1f).background(Ink), contentAlignment = Alignment.CenterStart) {
                    SText("${formatNumber(pct)}%", Modifier.padding(start = 24.bp), size = 30.bsp, weight = 900, color = Color.White, maxLines = 1)
                }
                SText("${formatNumber(100 - pct)}%", Modifier.align(Alignment.CenterEnd).padding(end = 20.bp), size = 30.bsp, weight = 900, color = Ink, maxLines = 1)
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.bp), horizontalArrangement = Arrangement.SpaceBetween) {
                SText(win, size = 26.bsp, weight = 800, color = Ink, maxLines = 1)
                SText(lose, size = 26.bsp, weight = 800, color = Ink, maxLines = 1)
            }
        }
        Column(
            Modifier.storyIn(1.5f, 40.bp).fillMaxWidth().clip(RoundedCornerShape(36.bp)).background(if (shows) WrappedPeach else WrappedBlue)
                .padding(horizontal = 40.bp, vertical = 32.bp),
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.bp)) {
                SText(formatNumber(otherCount), size = 104.bsp, weight = 900, tracking = -.05f, lineHeight = .9f, color = Ink, maxLines = 1)
                SText(loverUnit(otherCount, !shows), Modifier.padding(bottom = 4.bp), size = 44.bsp, weight = 900, color = Ink, maxLines = 1)
            }
            SText(otherLine, Modifier.padding(top = 8.bp), size = 28.bsp, weight = 700, color = Ink.copy(alpha = .75f))
        }
    }
}

// ── 3 · Genres ──

@Composable
private fun Genres(w: Wrapped) {
    val accent = LocalAccent.current
    val colours = listOf(accent, WrappedPeach, WrappedBlue, WrappedGreen, WrappedLilac)
    val top = w.genres.maxOfOrNull { it.pct }?.takeIf { it > 0 } ?: 1
    Left {
        Title(t("Your year") + "\n" + t("in genres"), Color.White)
        SText(genreLead(w), Modifier.storyIn(.25f, 40.bp).padding(top = 30.bp).widthIn(max = 600.bp), size = 32.bsp, lineHeight = 1.4f, color = Color(0xFFC8C8C8))
        TvMedu(meduLine(StoryKey.Genres, w), dark = true)
    }
    Right(gap = 34.bp) {
        w.genres.take(5).forEachIndexed { i, g ->
            Row(Modifier.storyIn(.5f + .12f * i, 40.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.bp)) {
                SText("${formatNumber(g.pct)}%", Modifier.width(170.bp), size = 76.bsp, weight = 900, tracking = -.04f, color = colours[i], maxLines = 1)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.bp)) {
                    SText(genreName(g), size = 32.bsp, weight = 800, color = Color.White, maxLines = 1)
                    Box(Modifier.fillMaxWidth().height(26.bp).clip(RoundedCornerShape(13.bp)).background(Color(0xFF262626))) {
                        Box(Modifier.fillMaxWidth(g.pct.toFloat() / top).height(26.bp).storyGrow(.6f + .12f * i).clip(RoundedCornerShape(13.bp)).background(colours[i]))
                    }
                }
            }
        }
        val other = genreOther(w)
        if (other.isNotEmpty()) SText(other, Modifier.storyIn(1.2f, 40.bp), size = 24.bsp, color = Color(0xFF8C8C8C))
    }
}

// ── 4 · Top shows (board "Seedy Wrapped · TV") ──

@Composable
private fun Shows(w: Wrapped) {
    val accent = LocalAccent.current
    Left {
        Title(t("Your top") + "\n" + t("shows"), Ink)
        Lede(showsFootnote(w), Ink)
        TvMedu(meduLine(StoryKey.Shows, w), dark = false)
    }
    Right {
        w.shows.firstOrNull()?.let { first ->
            val shape = RoundedCornerShape(34.bp)
            Box(Modifier.storyPop(.35f).fillMaxWidth().height(420.bp).boxShadow(Color(0x401E1E1E), blur = 60.bp, y = 30.bp, radius = 34.bp).clip(shape)) {
                ShowArt(first, 0, wide = true, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.4f to Color.Transparent, 1f to Color.Black.copy(alpha = .78f))))
                OutlineText("1", 230.bsp, Color.White, 2.5.bp * 2, Modifier.align(Alignment.TopEnd).padding(end = 34.bp, top = 4.bp), tracking = -.05f)
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 40.bp, end = 40.bp, bottom = 34.bp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.bp)) {
                        SText(first.name, size = 64.bsp, weight = 900, lineHeight = 1f, tracking = -.02f, color = Color.White, maxLines = 1)
                        SText(showMeta(first), size = 26.bsp, color = Color(0xFFD0D0D0), maxLines = 1)
                    }
                    Box(Modifier.clip(RoundedCornerShape(999.bp)).background(accent).padding(horizontal = 18.bp, vertical = 8.bp)) {
                        SText(t("Your #1"), size = 22.bsp, weight = 900, color = Ink, maxLines = 1)
                    }
                }
            }
        }
        val rest = w.shows.drop(1).take(4)
        Column(verticalArrangement = Arrangement.spacedBy(26.bp)) {
            rest.chunked(2).forEachIndexed { r, pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(40.bp)) {
                    pair.forEachIndexed { c, s ->
                        val i = r * 2 + c
                        Row(Modifier.weight(1f).storyIn(.6f + .12f * i, 40.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.bp)) {
                            SText("${i + 2}", Modifier.width(40.bp), size = 50.bsp, weight = 900, tracking = -.04f, color = Ink, maxLines = 1)
                            ShowArt(s, i + 1, wide = false, modifier = Modifier.size(120.bp, 68.bp).clip(RoundedCornerShape(14.bp)))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                                SText(s.name, size = 28.bsp, weight = 900, color = Ink, maxLines = 1)
                                SText(showMeta(s), size = 21.bsp, color = Ink.copy(alpha = .7f), maxLines = 1)
                            }
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

// ── 5 · The movie ──

/** A cinema ticket: a stub with a half-circle notch out of each side. */
private val Ticket = GenericShape { size, _ ->
    val r = size.height * 5f / 34f
    val body = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(size.height * 8f / 34f))) }
    val notches = Path().apply {
        addOval(Rect(Offset(0f, size.height / 2f), r))
        addOval(Rect(Offset(size.width, size.height / 2f), r))
    }
    addPath(Path.combine(PathOperation.Difference, body, notches))
}

@Composable
private fun Movie(w: Wrapped) {
    val accent = LocalAccent.current
    val m = w.movie ?: return
    Left {
        Title(movieTitle(w), Ink, size = 80)
        SText(m.name, Modifier.storyIn(1f, 40.bp).padding(top = 34.bp), size = 56.bsp, weight = 900, tracking = -.02f, color = Ink, maxLines = 2)
        SText(movieMeta(w), Modifier.storyIn(1f, 40.bp).padding(top = 6.bp), size = 28.bsp, weight = 600, color = Ink.copy(alpha = .75f))
        TvMedu(meduLine(StoryKey.Movie, w), dark = false, top = 50)
    }
    Column(Modifier.fillMaxSize().padding(start = 900.bp, end = 96.bp, top = 220.bp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.TopCenter) {
            val shape = RoundedCornerShape(30.bp)
            MovieArt(m, Modifier.padding(horizontal = 90.bp).storyPop(.35f, rotation = -4f).size(360.bp, 540.bp).boxShadow(Color(0x593C1E00), blur = 80.bp, y = 40.bp, radius = 30.bp).clip(shape))
            if (m.views >= 2) {
                Column(
                    Modifier.align(Alignment.TopEnd).offset(y = (-20).bp).storyPop(.8f, rotation = 8f).size(196.bp).clip(CircleShape).background(Ink),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SText("${formatNumber(m.views)}×", size = 90.bsp, weight = 900, lineHeight = .9f, tracking = -.04f, color = Color.White, maxLines = 1)
                    SText(t("watched"), size = 20.bsp, weight = 800, color = accent, maxLines = 1)
                }
            }
        }
        Row(Modifier.padding(top = 44.bp), horizontalArrangement = Arrangement.spacedBy(12.bp)) {
            movieTickets(w).forEachIndexed { i, month ->
                Box(Modifier.storyPop(1.2f + .12f * i).size(104.bp, 60.bp).clip(Ticket).background(Ink), contentAlignment = Alignment.Center) {
                    SText(month, size = 20.bsp, weight = 800, color = Color.White, maxLines = 1)
                }
            }
        }
    }
}

// ── 6 · The binge ──

@Composable
private fun Binge(w: Wrapped, look: StoryLook) {
    val accent = LocalAccent.current
    val b = w.binge ?: return
    Left {
        Title(t("Your biggest") + "\n" + t("binge"), Ink, size = 96)
        SText(bingeText(w), Modifier.storyIn(1.9f, 40.bp).padding(top = 30.bp).widthIn(max = 600.bp), size = 30.bsp, weight = 700, lineHeight = 1.45f, color = Ink)
        TvMedu(meduLine(StoryKey.Binge, w), dark = false, top = 50)
    }
    Right(gap = 0.bp) {
        Row(Modifier.storyIn(.3f, 40.bp), horizontalArrangement = Arrangement.spacedBy(20.bp)) {
            SText(formatNumber(b.episodes), Modifier.alignByBaseline(), size = 200.bsp, weight = 900, lineHeight = .9f, tracking = -.06f, color = Ink, maxLines = 1)
            TightText(AnnotatedString(t("episodes") + "\n" + t("in one go")), 52.bsp, 900, Ink, 1f, Modifier.alignByBaseline())
        }
        val minutes = b.minutes?.takeIf { it.isNotEmpty() }
        val boxes = (minutes?.size ?: b.episodes).coerceIn(0, 12)
        Row(Modifier.padding(top = 30.bp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.bp)) {
            for (i in 0 until boxes) {
                Box(
                    Modifier.weight(minutes?.getOrNull(i)?.coerceAtLeast(1)?.toFloat() ?: 1f).storyPop(.7f + .08f * i).height(56.bp).clip(RoundedCornerShape(14.bp)).background(Ink),
                    contentAlignment = Alignment.Center,
                ) { SText("${i + 1}", size = 20.bsp, weight = 900, color = look.bg, maxLines = 1) }
            }
        }
        Row(Modifier.storyIn(1.2f, 40.bp).padding(top = 12.bp).fillMaxWidth()) {
            SText(timeOf(b.start), size = 22.bsp, weight = 800, color = Ink, maxLines = 1)
            SText(bingeLine(w), Modifier.weight(1f).padding(horizontal = 12.bp), size = 22.bsp, weight = 800, color = Ink.copy(alpha = .7f), align = TextAlign.Center, maxLines = 1)
            SText(timeOf(b.end), size = 22.bsp, weight = 800, color = Ink, maxLines = 1)
        }
        // "Still watching?" cards fanned out, 1.6 × the phone's.
        Box(Modifier.padding(top = 40.bp).fillMaxWidth().height(260.bp)) {
            val card = RoundedCornerShape(29.bp)
            val shadow = Color(0x4D001E3C)
            for ((top, turn, alpha, delay) in listOf(Fan(35, -8f, .35f, 1.3f), Fan(22, 6f, .6f, 1.38f))) {
                Box(
                    Modifier.align(Alignment.TopCenter).padding(top = top.bp).storyFan(delay, turn, alpha, 64.bp).size(394.bp, 173.bp)
                        .boxShadow(shadow, blur = 42.bp, y = 16.bp, radius = 29.bp).clip(card).background(Ink),
                )
            }
            Column(
                Modifier.align(Alignment.TopCenter).offset(x = 22.bp).padding(top = 42.bp).storyFan(1.45f, 0f, 1f, 64.bp).width(438.bp)
                    .boxShadow(shadow, blur = 42.bp, y = 16.bp, radius = 29.bp).clip(card).background(Ink).padding(22.bp),
            ) {
                SText(t("Still watching?"), size = 26.bsp, weight = 900, color = Color.White, maxLines = 1)
                SText(bingeEpisode(w), Modifier.padding(top = 4.bp), size = 20.bsp, color = Color(0xFFA1A1A1), maxLines = 1)
                Row(Modifier.padding(top = 16.bp), horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                    Box(Modifier.weight(1f).height(48.bp).clip(RoundedCornerShape(14.bp)).background(Color(0xFFF0F0F0)), contentAlignment = Alignment.Center) {
                        SText(t("Yes, keep going"), size = 20.bsp, weight = 800, color = Ink, maxLines = 1)
                    }
                    Box(Modifier.height(48.bp).clip(RoundedCornerShape(14.bp)).background(Color(0xFF2A2A2A)).padding(horizontal = 16.bp), contentAlignment = Alignment.Center) {
                        SText(t("Stop"), size = 20.bsp, weight = 700, color = Color.White, maxLines = 1)
                    }
                }
            }
            if (w.still.asked > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(end = 100.bp).storyPop(1.8f, rotation = 10f).clip(RoundedCornerShape(999.bp)).background(accent).padding(horizontal = 16.bp, vertical = 10.bp)) {
                    SText("×${formatNumber(w.still.asked)}", size = 24.bsp, weight = 900, color = Ink, maxLines = 1)
                }
            }
        }
    }
}

private data class Fan(val top: Int, val turn: Float, val alpha: Float, val delay: Float)

// ── 7 · The clock ──

@Composable
private fun Clock(w: Wrapped, look: StoryLook) {
    val accent = LocalAccent.current
    val clock = w.clock ?: return
    val light = !look.dark
    val (lead, kind) = clockTitle(w)
    Left {
        TightText(
            buildAnnotatedString {
                append(lead.uppercase())
                append("\n")
                withStyle(SpanStyle(color = if (light) Color.White else accent)) { append(kind.uppercase()) }
            },
            112.bsp, 900, look.ink, .92f, Modifier.storyIn(.1f, 40.bp), tracking = -.035f,
        )
        val share = clockShare(w)
        val at = share.indexOf("{pct}")
        val body = if (light) Ink.copy(alpha = .8f) else Color(0xFFC8C8C8)
        TightText(
            buildAnnotatedString {
                if (at >= 0) {
                    append(share.substring(0, at))
                    withStyle(SpanStyle(fontWeight = FontWeight(900), color = look.ink)) { append("${formatNumber(clock.pct)}%") }
                    append(share.substring(at + 5))
                } else append(share)
                append(" ")
                append(clockDay(w))
            },
            32.bsp, 600, body, 1.4f, Modifier.storyIn(1.4f, 40.bp).padding(top = 30.bp).widthIn(max = 640.bp),
        )
        TvMedu(meduLine(StoryKey.Clock, w), dark = look.dark)
    }
    Box(Modifier.fillMaxSize().padding(start = 900.bp, end = 96.bp, top = 200.bp), contentAlignment = Alignment.TopCenter) {
        ClockDial(
            clock.hours, clock.peak, t("your peak"),
            hot = if (light) Ink else accent, cold = look.ink.copy(alpha = if (light) .25f else .32f), ink = look.ink,
            labels = if (light) Ink.copy(alpha = .55f) else Color(0xFF8C8C8C), sub = if (light) Ink.copy(alpha = .65f) else Color(0xFFA1A1A1),
            modifier = Modifier.storyIn(.3f, 40.bp).size(620.bp),
        )
    }
}

// ── 9 · Your requests ──

@Composable
private fun Requests(w: Wrapped) {
    val r = w.requests ?: return
    val parts = listOf(
        Triple(r.here, t("{n} here", "n" to formatNumber(r.here)), t("arrived and ready to watch")),
        Triple(r.coming, formatNumber(r.coming), t("on the way")),
        Triple(r.declined, formatNumber(r.declined), t("declined, not available yet")),
    ).mapIndexed { i, (n, label, line) -> RequestPart(n, label, line, i) }.filter { it.n > 0 }
    val one = r.count == 1
    Left {
        SText(tc("Your requests", "wrapped"), Modifier.storyIn(.1f, 40.bp), size = 44.bsp, weight = 800, color = Ink)
        Row(
            Modifier.padding(top = 12.bp).semantics(mergeDescendants = true) { contentDescription = tn(r.count, "{n} title requested", "{n} titles requested") },
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(24.bp),
        ) {
            Box(Modifier.height(240.bp).margin(end = (-29).bp), contentAlignment = Alignment.BottomStart) { Odometer(formatNumber(r.count), 240.bsp, Ink, tracking = -.06f, delay = .3f) }
            TightText(
                AnnotatedString(tc(if (one) "title" else "titles", "count") + "\n" + tc("requested", if (one) "one" else "other")),
                60.bsp, 900, Ink, .95f, Modifier.storyIn(.8f, 40.bp).padding(bottom = 18.bp),
            )
        }
        TvMedu(meduLine(StoryKey.Requests, w), dark = false, top = 50)
    }
    Right(gap = 0.bp) {
        // Each part 40 px of padding plus its share of the rest, as on the phone.
        androidx.compose.foundation.layout.BoxWithConstraints(
            Modifier.fillMaxWidth().height(72.bp)
                .semantics { contentDescription = t("{a} arrived, {b} on the way, {c} declined", "a" to r.here, "b" to r.coming, "c" to r.declined) },
        ) {
            val free = maxWidth - 6.bp * (parts.size - 1) - 40.bp * parts.size
            val all = parts.sumOf { it.n }.coerceAtLeast(1)
            Row(horizontalArrangement = Arrangement.spacedBy(6.bp)) {
                parts.forEachIndexed { i, x ->
                    Box(
                        Modifier.width(40.bp + free * (x.n.toFloat() / all)).fillMaxHeight().storyGrow(.7f + .15f * i).then(x.look(18.bp)).padding(horizontal = 20.bp),
                        contentAlignment = if (x.kind == 0) Alignment.CenterStart else Alignment.Center,
                    ) { SText(x.label, size = 28.bsp, weight = 900, color = if (x.kind == 2) Ink else Color.White, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Clip) }
                }
            }
        }
        Column(Modifier.storyIn(1.2f, 40.bp).padding(top = 34.bp), verticalArrangement = Arrangement.spacedBy(18.bp)) {
            for (x in parts) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp)) {
                    Box(Modifier.size(26.bp).then(x.look(8.bp)))
                    SText(formatNumber(x.n), Modifier.width(56.bp), size = 34.bsp, weight = 900, color = Ink, maxLines = 1)
                    SText(x.line, size = 34.bsp, weight = 700, color = Ink, maxLines = 1)
                }
            }
        }
        val cards = listOfNotNull(
            r.fastest?.let { arrivalTime(it.ms) to t("fastest arrival: {title}", "title" to arrivalName(it)) },
            r.genre?.let { genreName(it) to t("what you asked for most") },
        )
        if (cards.isNotEmpty()) {
            Row(Modifier.storyIn(1.4f, 40.bp).padding(top = 44.bp).fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.bp)) {
                for ((big, small) in cards) {
                    Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(36.bp)).background(Ink).padding(30.bp), verticalArrangement = Arrangement.spacedBy(6.bp)) {
                        SText(big, size = 56.bsp, weight = 900, lineHeight = 1f, tracking = -.02f, color = Color.White, maxLines = 1)
                        SText(small, size = 26.bsp, lineHeight = 1.35f, color = Color(0xFFC8C8C8))
                    }
                }
            }
        }
    }
}

/** A part of the requests' bar: here (0, solid), on the way (1, faded) or declined (2, a ring). */
private class RequestPart(val n: Int, val label: String, val line: String, val kind: Int) {
    fun look(radius: Dp): Modifier {
        val shape = RoundedCornerShape(radius)
        return when (kind) {
            0 -> Modifier.clip(shape).background(Ink)
            1 -> Modifier.clip(shape).background(Ink.copy(alpha = .45f))
            else -> Modifier.clip(shape).border(4.bp, Ink, shape)
        }
    }
}

// ── 10 · The oddest request ──

@Composable
private fun Oddest(w: Wrapped) {
    val o = w.oddest ?: return
    val titleSize = if (o.name.length > 26) 72 else if (o.name.length > 16) 88 else 104
    Left {
        SText(t("Your oddest request"), Modifier.storyIn(.1f, 40.bp), size = 36.bsp, weight = 800, color = Ink)
        Heading(o.name, titleSize.bsp, Ink, Modifier.storyIn(.2f, 40.bp).padding(top = 14.bp), lineHeight = .92f, tracking = -.035f, maxLines = 3)
        TvMedu(meduLine(StoryKey.Oddest, w), dark = false)
    }
    Right(gap = 0.bp) {
        Row(horizontalArrangement = Arrangement.spacedBy(44.bp)) {
            OddestArt(
                o,
                Modifier.storyPop(.4f, rotation = -3f).size(290.bp, 435.bp)
                    .boxShadow(Color(0x4D3C1E00), blur = 68.bp, y = 32.bp, radius = 24.bp).clip(RoundedCornerShape(24.bp)),
            )
            Column(Modifier.weight(1f)) {
                oddFacts(o).forEachIndexed { i, (big, small) ->
                    Column(
                        Modifier.storyIn(.7f + .1f * i, 40.bp).fillMaxWidth().drawWithContent {
                            drawRect(Ink.copy(alpha = .14f), size = Size(size.width, 3.bp.toPx()))
                            drawContent()
                        }.padding(top = 18.bp, bottom = 16.bp),
                    ) {
                        SText(big, size = 50.bsp, weight = 900, lineHeight = 1.05f, tracking = -.02f, color = Ink, maxLines = 1)
                        SText(small, size = 24.bsp, weight = 700, color = Ink.copy(alpha = .7f), maxLines = 1)
                    }
                }
            }
        }
        Column(Modifier.storyIn(1.2f, 40.bp).padding(top = 50.bp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SText(t("Odd-o-meter"), Modifier.alignByBaseline(), size = 26.bsp, weight = 900, tracking = .06f, color = Ink, uppercase = true, maxLines = 1)
                SText(t("{n} / 100", "n" to formatNumber(o.score)), Modifier.alignByBaseline(), size = 26.bsp, weight = 900, color = Ink, maxLines = 1)
            }
            Box(
                Modifier.padding(top = 12.bp).fillMaxWidth().height(32.bp).clip(RoundedCornerShape(16.bp)).background(Ink.copy(alpha = .12f))
                    .semantics { contentDescription = t("Oddness {n} out of 100", "n" to o.score) },
            ) {
                Box(
                    Modifier.fillMaxWidth(o.score.coerceIn(4, 100) / 100f).fillMaxHeight().storyGrow(1.4f).clip(RoundedCornerShape(16.bp))
                        .background(Brush.horizontalGradient(0f to Ink, .6f to Ink, 1f to WrappedRust)),
                )
            }
            Row(Modifier.padding(top = 10.bp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (label in listOf(tc("Normal", "odd"), t("Curious"), t("Medu is concerned"))) {
                    SText(label, size = 22.bsp, weight = 800, color = Ink.copy(alpha = .7f), maxLines = 1)
                }
            }
        }
    }
}

// ── 11 · The personality ──

@Composable
private fun Personality(w: Wrapped) {
    val accent = LocalAccent.current
    val key = w.personality.key
    val name = personalityName(key)
    // The longest word stays on one line ("The Completionist").
    val longest = name.split(Regex("\\s+")).maxOf { it.length }
    val nameSize = if (longest > 11) 88 else if (name.length > 19) 94 else 104
    Left {
        SText(t("Your watch personality"), Modifier.storyIn(.1f, 40.bp), size = 36.bsp, weight = 800, color = Ink)
        Heading(name, nameSize.bsp, Ink, Modifier.storyPop(.4f).padding(top = 18.bp), lineHeight = .92f, tracking = -.035f)
        SText(personalityText(key), Modifier.storyIn(.6f, 40.bp).padding(top = 26.bp).widthIn(max = 620.bp), size = 30.bsp, weight = 600, lineHeight = 1.45f, color = Ink.copy(alpha = .8f))
        TvMedu(meduLine(StoryKey.Personality, w), dark = false, top = 50)
    }
    Right(gap = 0.bp) {
        Box(Modifier.storyPop(.4f).padding(bottom = 30.bp).size(104.bp).clip(RoundedCornerShape(32.bp)).background(Ink), contentAlignment = Alignment.Center) {
            SIcon(WrappedIcons.personality(key), size = 56.bp, color = accent)
        }
        w.personality.stats.take(3).forEachIndexed { i, s ->
            val line = statLine(s)
            Row(
                Modifier.storyIn(1.1f + .12f * i, 40.bp).fillMaxWidth().drawWithContent {
                    drawRect(Ink.copy(alpha = .15f), size = Size(size.width, 3.bp.toPx()))
                    drawContent()
                }.padding(top = 29.bp, bottom = 26.bp),
                horizontalArrangement = Arrangement.spacedBy(24.bp),
            ) {
                SText(line.big, Modifier.width(320.bp).alignByBaseline(), size = 52.bsp, weight = 900, tracking = -.02f, color = Ink, maxLines = 1)
                SText(line.small, Modifier.weight(1f).alignByBaseline(), size = 28.bsp, weight = 600, lineHeight = 1.35f, color = Ink)
            }
        }
    }
}

// ── 12 · Summary ──

@Composable
private fun Summary(w: Wrapped) {
    val accent = LocalAccent.current
    Left {
        TightText(AnnotatedString(t("That’s your {year}. Thanks for watching with Medu.", "year" to w.year)), 72.bsp, 900, Color.White, 1.05f, Modifier.storyIn(.05f, 40.bp), tracking = -.02f)
        Medu(MeduMood.Party, 220.bp, Modifier.padding(top = 60.bp).margin(start = (-20).bp).storyPop(1.4f), height = (220 * 1.32f).bp, glow = false)
    }
    // The phone's card, three quarters of its size (it's drawn in the phone's units).
    Box(Modifier.fillMaxSize().padding(start = 900.bp, end = 96.bp, top = 190.bp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.wrapContentHeight(Alignment.Top, unbounded = true).storyPop(.3f).graphicsLayer { scaleX = .75f; scaleY = .75f; transformOrigin = TransformOrigin(.5f, 0f) }) {
            SummaryCard(w, accent, Modifier.width(342.dp))
        }
    }
}
