package io.github.arkmind.seedy.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import android.provider.Settings
import androidx.annotation.VisibleForTesting
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Ease
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativePaint
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import kotlinx.coroutines.flow.first
import kotlin.math.floor
import kotlin.math.min

/** Medu's moods (board "Brand · Mascot"): each has its face and its own small loop on top of the idle float. */
enum class MeduMood {
    /** The idle float, pulse and blink: the screensaver. */
    Idle,

    /** Hello: a waving arm (sign-in, a new profile, the server answering again). */
    Wave,

    /** Lost: a tilting question mark, a drop of sweat, eyes looking around, and a sweeping torch when asked for. */
    Search,

    /** Sleepy: closed eyes and rising z's, at half speed (empty lists, a sleep timer, Still watching?). */
    Sleepy,

    /** Yay: a grin and sparkles, at double speed (something is ready). */
    Yay,

    /** Thinking: bouncing dots over the bell (a long wait). */
    Think,

    /** Oops: a dropped plug swinging from a tentacle (the server can't be reached). */
    Oops,

    /** Love: heart eyes and hearts floating up (added to Favorites, a "Love it" rating). */
    Love,

    /** Popcorn: a bucket held in two tentacles, a chewing mouth and a kernel hopping out (something starts playing). */
    Popcorn,

    /** Sad: worried brows and a falling tear, at half speed (a request declined, a title leaving soon). */
    Sad,

    /** Party: sunglasses, a party hat and a big open grin, at double speed (Seedy Wrapped's cover and summary). */
    Party,
}

/**
 * Whether looping decorations hold still: the system's animations are off (Settings › Accessibility › Remove
 * animations, or a duration scale of 0), a preview, or a screenshot test. They then show their rest pose.
 */
@Composable
fun rememberStillMotion(): Boolean {
    if (LocalInspectionMode.current || Build.FINGERPRINT == "robolectric") return true
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * Nanoseconds since this first showed, ticking every frame, or null while motion is off ([rememberStillMotion]).
 * Read it while drawing (or in a graphicsLayer block) so only the drawing follows the clock.
 */
@Composable
fun rememberLoopClock(): MutableLongState? {
    if (rememberStillMotion()) return null
    val nanos = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withInfiniteAnimationFrameNanos { nanos.longValue = it - start }
    }
    return nanos
}

/** A loop clock's time in seconds; null (rest pose) without one. */
fun MutableLongState?.seconds(): Double? = this?.longValue?.let { it / 1e9 }

/**
 * Medu, the jellyfish (board "Brand · Mascot"), drawn from the boards' SVG (`viewBox="0 -24 200 264"`) at [width],
 * its height 1.32× that unless given (the boards round it). The full detail has six tentacles, the bell's pulse, its
 * spots, highlight and blink, and a soft glow of the accent behind it; [small] is the under-80 px Medu: three thick
 * tentacles, bigger eyes, no face details, no pulse. The moods' loops run as the boards' CSS keyframes; with motion
 * off Medu holds still in its pose. Hidden from screen readers: the text beside it says what happened.
 *
 * Medu never cuts from one mood to another (board "Moving between moods"): when [mood] changes while shown, it acts
 * its way across as the web's medu.tsx does, moving its parts (eyes, mouths, props…) one by one: the board's own
 * choreography for the pairs it draws ([MeduBridges]), a blink, a hop and props popping in and out for every other
 * pair. One bridge at a time; a mood asked for meanwhile follows. [onSettled] hears when Medu has reached a new
 * [mood]: after its bridge, or at once with motion off or [small], which swap in place.
 */
@Composable
fun Medu(
    mood: MeduMood,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = width * 1.32f,
    small: Boolean = false,
    glow: Boolean = !small,
    torch: Boolean = true,
    bubbles: Boolean = false,
    accent: Color = LocalAccent.current,
    onSettled: ((MeduMood) -> Unit)? = null,
) {
    val clock = rememberLoopClock()
    val texts = rememberMeduTexts()
    val stage = remember { Stage(mood) }
    val asked by rememberUpdatedState(mood)
    val swaps by rememberUpdatedState(small)
    val holdsTorch by rememberUpdatedState(torch)
    val settled by rememberUpdatedState(onSettled)
    LaunchedEffect(stage) {
        while (true) {
            val next = snapshotFlow { asked }.first { it != stage.shown }
            if (clock == null || swaps) stage.swap(next) else stage.play(plan(stage.shown, next, holdsTorch, stage.restY), clock)
            if (asked == stage.shown) settled?.invoke(stage.shown)
        }
    }
    Canvas(modifier.size(width, height)) {
        val pose = stage.pose(clock, torch && !small)
        fit { drawMedu(pose, clock.seconds(), stage.loops, accent, small, glow, bubbles, texts) }
    }
}

/**
 * Medu on its way from [from] to [to], held at [progress] (0..1) of the bridge with every loop at rest: a bridge's
 * frames for the screenshot tests (MeduShots), the same on every run. The app uses [Medu], which plays them.
 */
@VisibleForTesting
@Composable
fun MeduBridgeFrame(
    from: MeduMood,
    to: MeduMood,
    progress: Float,
    width: Dp,
    modifier: Modifier = Modifier,
    height: Dp = width * 1.32f,
    accent: Color = LocalAccent.current,
) {
    val texts = rememberMeduTexts()
    val run = remember(from, to) { plan(from, to, torch = true, restY = 0f) }
    Canvas(modifier.size(width, height)) {
        val pose = Pose(from, run, progress.coerceIn(0f, 1f), 0f, run.parts)
        fit { drawMedu(pose, null, null, accent, small = false, glow = true, bubbles = false, texts = texts) }
    }
}

private class MeduTexts(val question: TextLayoutResult, val z: List<TextLayoutResult>)

/** The "?" and z's, laid out in viewBox units (a unit is a pixel before Medu's scale). */
@Composable
private fun rememberMeduTexts(): MeduTexts {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        fun glyph(c: String, units: Float) = measurer.measure(
            c,
            BaseText.copy(fontSize = with(density) { units.toSp() }, fontWeight = FontWeight(900)),
        )
        MeduTexts(question = glyph("?", 46f), z = listOf(glyph("z", 20f), glyph("z", 28f), glyph("z", 22f)))
    }
}

/** Draws in the boards' viewBox (0 -24 200 264), centred and scaled to fit. */
private inline fun DrawScope.fit(block: DrawScope.() -> Unit) {
    val s = min(size.width / 200f, size.height / 264f)
    translate((size.width - 200f * s) / 2f, (size.height - 264f * s) / 2f + 24f * s) {
        scale(s, s, Offset.Zero, block)
    }
}

private val HopEase = CubicBezierEasing(.3f, 0f, .3f, 1f)
private val HopAt = floatArrayOf(0f, .18f, .45f, .7f, .82f, 1f)
private val HopY = floatArrayOf(0f, 0f, -22f, 0f, -6f, 0f)
private val HopX = floatArrayOf(1f, 1.06f, .97f, 1.04f, 1f, 1f)
private val HopH = floatArrayOf(1f, .92f, 1.04f, .95f, 1f, 1f)

/**
 * The update screen's hop around [content] (CSS `up-hop`, 1.4 s): a squash, a 22 px jump, a landing and a small
 * bounce, from the bottom centre. Still with motion off.
 */
@Composable
fun Hop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val clock = rememberLoopClock()
    Box(
        modifier.graphicsLayer {
            val t = clock.seconds() ?: return@graphicsLayer
            val p = loop(t, 1.4)
            transformOrigin = TransformOrigin(.5f, 1f)
            translationY = track(p, HopAt, HopY, HopEase).dp.toPx()
            scaleX = track(p, HopAt, HopX, HopEase)
            scaleY = track(p, HopAt, HopH, HopEase)
        },
    ) { content() }
}

/**
 * Medu in its rest pose as a bitmap of [width] × [height] pixels, for where Compose doesn't draw (a notification's
 * large icon). The question mark and z's need text, so Search and Sleepy come without them.
 */
fun meduBitmap(
    mood: MeduMood,
    width: Int,
    height: Int,
    accent: Color,
    small: Boolean = false,
    glow: Boolean = false,
    torch: Boolean = false,
): ImageBitmap {
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
        val pose = Pose(mood, null, 0f, 0f, partsOf(mood, torch && !small))
        fit { drawMedu(pose, null, null, accent, small, glow, false, null) }
    }
    return bitmap
}

// ---- Parts ----------------------------------------------------------------------------------------------------

/**
 * Medu's parts that come and go with the moods (the web's `<g data-p>`), by the web's names, and where each one turns,
 * shrinks or pops from in a bridge the board doesn't draw (drawing units; the sparks from their own centre).
 */
private enum class Part(val key: String, val at: Offset?) {
    Eyes("eyes", Offset(100f, 83f)),
    EyesLook("eyesLook", Offset(100f, 83f)),
    EyesUp("eyesUp", Offset(100f, 80f)),
    EyesClosed("eyesClosed", Offset(100f, 86f)),
    EyesHappy("eyesHappy", Offset(100f, 86f)),
    EyesHeart("eyesHeart", Offset(100f, 85f)),
    BrowsLost("browsLost", Offset(100f, 66f)),
    BrowsOops("browsOops", Offset(100f, 70f)),
    BrowsSad("browsSad", Offset(100f, 68f)),
    MSmile("mSmile", Offset(100f, 98f)),
    MWobble("mWobble", Offset(100f, 99f)),
    MO("mO", Offset(100f, 99f)),
    MFlat("mFlat", Offset(100f, 98f)),
    MGrin("mGrin", Offset(100f, 100f)),
    MFrown("mFrown", Offset(100f, 98f)),
    MSad("mSad", Offset(100f, 100f)),
    MLove("mLove", Offset(100f, 103f)),
    MChew("mChew", Offset(100f, 99f)),
    PartyFace("party", Offset(100f, 70f)),
    Tear("tear", Offset(73f, 100f)),
    Popcorn("popcorn", Offset(100f, 176f)),
    D4("d4", Offset(152f, 112f)),
    PlugT("plugT", Offset(152f, 112f)),
    Plug("plug", Offset(170f, 200f)),
    Arm("arm", Offset(163f, 96f)),
    Torch("torch", Offset(152f, 112f)),
    Q("q", Offset(164f, 22f)),
    Sweat("sweat", Offset(150f, 72f)),
    Zs("zs", Offset(170f, 30f)),
    Dots("dots", Offset(100f, 0f)),
    Hearts("hearts", Offset(130f, 60f)),
    Spark0("spark0", null),
    Spark1("spark1", null),
    Spark2("spark2", null),
    Spark3("spark3", null),
}

private val SparkParts = listOf(Part.Spark0, Part.Spark1, Part.Spark2, Part.Spark3)

/** The face of each mood (inside the bell). */
private fun faceOf(mood: MeduMood): List<Part> = when (mood) {
    MeduMood.Idle, MeduMood.Wave -> listOf(Part.Eyes, Part.MSmile)
    MeduMood.Search -> listOf(Part.EyesLook, Part.BrowsLost, Part.MWobble)
    MeduMood.Sleepy -> listOf(Part.EyesClosed, Part.MO)
    MeduMood.Yay -> listOf(Part.EyesHappy, Part.MGrin)
    MeduMood.Think -> listOf(Part.EyesUp, Part.MFlat)
    MeduMood.Oops -> listOf(Part.Eyes, Part.BrowsOops, Part.MFrown)
    MeduMood.Love -> listOf(Part.EyesHeart, Part.MLove)
    MeduMood.Popcorn -> listOf(Part.EyesUp, Part.MChew)
    MeduMood.Sad -> listOf(Part.Eyes, Part.BrowsSad, Part.MSad)
    MeduMood.Party -> listOf(Part.PartyFace)
}

/** What comes with each mood's face. */
private fun propsOf(mood: MeduMood): List<Part> = when (mood) {
    MeduMood.Idle -> emptyList()
    MeduMood.Wave -> listOf(Part.Arm)
    MeduMood.Search -> listOf(Part.Q, Part.Sweat)
    MeduMood.Sleepy -> listOf(Part.Zs)
    MeduMood.Yay -> SparkParts
    MeduMood.Think -> listOf(Part.Dots)
    MeduMood.Oops -> listOf(Part.PlugT, Part.Plug)
    MeduMood.Love -> listOf(Part.Hearts)
    MeduMood.Popcorn -> listOf(Part.Popcorn)
    MeduMood.Sad -> listOf(Part.Tear)
    MeduMood.Party -> emptyList()
}

private val FaceParts: Set<Part> = MeduMood.entries.flatMapTo(mutableSetOf(), ::faceOf)

/** Parts that stand in for the fourth tentacle: they grow and shrink rather than pop. */
private val Limbs = setOf(Part.D4, Part.PlugT, Part.Torch)

private fun partsOf(mood: MeduMood, torch: Boolean): Set<Part> {
    val parts = LinkedHashSet(faceOf(mood) + propsOf(mood))
    if (mood == MeduMood.Search && torch) parts += Part.Torch
    if (Part.Torch !in parts && Part.PlugT !in parts) parts += Part.D4
    return parts
}

private fun blushOf(mood: MeduMood): Float = when (mood) {
    MeduMood.Idle, MeduMood.Wave -> .55f
    MeduMood.Search, MeduMood.Oops -> .4f
    MeduMood.Sleepy, MeduMood.Popcorn -> .5f
    MeduMood.Yay -> .7f
    MeduMood.Think -> .45f
    MeduMood.Love, MeduMood.Party -> .75f
    MeduMood.Sad -> .35f
}

/** The idle loops' durations in seconds (float and pulse, tentacles): m-slow and m-fast. */
private enum class Pace(val float: Double, val wiggle: Double) { Normal(3.2, 2.4), Slow(5.5, 4.0), Fast(1.6, 1.2) }

/** Sleepy and Sad float at half speed, Yay and Party at double. */
private fun paceOf(mood: MeduMood): Pace = when (mood) {
    MeduMood.Sleepy, MeduMood.Sad -> Pace.Slow
    MeduMood.Yay, MeduMood.Party -> Pace.Fast
    else -> Pace.Normal
}

/** The web's name for a mood, as the bridges are keyed ("sleepy>popcorn"). */
private val MeduMood.key: String get() = name.lowercase()

// ---- Bridges --------------------------------------------------------------------------------------------------

private class Span(val a: Float, val b: Float)

/** When, within a bridge, the old props leave, the new ones arrive, the face swaps and the blush changes. */
private data class Windows(val exit: Span, val enter: Span, val swap: Float, val blush: Span)

private val Generic = Windows(Span(0f, .35f), Span(.45f, .8f), .42f, Span(.35f, .6f))

/** The board's bridges move everything they draw; these place what they don't (the torch, the plugged tentacle). */
private val Drawn = mapOf(
    "search>sleepy" to Generic.copy(exit = Span(0f, .3f), enter = Span(.2f, .5f), blush = Span(.3f, .62f)),
    "sleepy>search" to Generic.copy(exit = Span(.1f, .3f), enter = Span(.35f, .65f), blush = Span(.1f, .4f)),
    "oops>wave" to Generic.copy(exit = Span(.2f, .5f), enter = Span(.35f, .75f)),
    "wave>oops" to Generic.copy(exit = Span(.5f, .75f), enter = Span(.6f, .95f)),
)

private fun fr(offset: Float, transform: String? = null, opacity: Float? = null, easing: String? = null) = MeduFrame(offset, transform, opacity, easing)

private fun hold(s: Span, from: MeduFrame, to: MeduFrame) = listOf(
    fr(0f, from.transform, from.opacity), fr(s.a, from.transform, from.opacity, from.easing), fr(s.b, to.transform, to.opacity), fr(1f, to.transform, to.opacity),
)

private fun propOut(s: Span) = hold(s, fr(0f, "translateY(0px) scale(1)", 1f, "ease-in"), fr(0f, "translateY(10px) scale(.4)", 0f))

private fun propIn(s: Span) = listOf(
    fr(0f, "translateY(0px) scale(0)", 0f), fr(s.a, "translateY(0px) scale(0)", 0f, "cubic-bezier(.2,.8,.3,1.4)"),
    fr(s.a + (s.b - s.a) * .65f, "translateY(0px) scale(1.15)", 1f, "ease-in-out"), fr(s.b, "translateY(0px) scale(1)", 1f), fr(1f, "translateY(0px) scale(1)", 1f),
)

private fun limbOut(s: Span) = hold(s, fr(0f, "scaleY(1)", 1f, "ease-in"), fr(0f, "scaleY(.2)", 0f))

private fun limbIn(s: Span) = hold(s, fr(0f, "scaleY(.2)", 0f, "cubic-bezier(.2,.8,.3,1.3)"), fr(0f, "scaleY(1)", 1f))

/** Face parts swap while squashed shut, like a blink (rule 3). */
private fun faceOut(s: Float) = listOf(
    fr(0f, "scaleY(1)", 1f), fr(s - .1f, "scaleY(1)", 1f, "ease-in"), fr(s, "scaleY(.1)", 1f), fr(s + .005f, "scaleY(.1)", 0f), fr(1f, "scaleY(.1)", 0f),
)

private fun faceIn(s: Float) = listOf(
    fr(0f, "scaleY(.1)", 0f), fr(s, "scaleY(.1)", 0f), fr(s + .005f, "scaleY(.1)", 1f, "cubic-bezier(.2,.8,.3,1)"),
    fr(s + .12f, "scaleY(1.12)", 1f, "ease-in-out"), fr(s + .2f, "scaleY(1)", 1f), fr(1f, "scaleY(1)", 1f),
)

private fun blink(s: Float) = listOf(
    fr(0f, "scaleY(1)"), fr(s - .1f, "scaleY(1)", easing = "ease-in"), fr(s, "scaleY(.1)", easing = "ease-out"), fr(s + .12f, "scaleY(1)"), fr(1f, "scaleY(1)"),
)

private fun ty(y: Float) = "translateY(${y}px)"

private val TyPattern = Regex("""translateY\((-?[\d.]+)px\)""")

private fun offsetY(transform: String?): Float = transform?.let { TyPattern.find(it) }?.groupValues?.get(1)?.toFloat() ?: 0f

private fun trackOf(part: Part, frames: List<MeduFrame>) = MeduTrack(part.at?.x ?: 0f, part.at?.y ?: 0f, frames, centre = part.at == null)

/**
 * A bridge the board doesn't draw: wind up, hop, swap the face at the top of the hop, land with a squash (rule 4).
 * Towards Sad or Sleepy Medu sighs instead: a breath in, then it sinks and settles.
 */
private fun genericBody(fromY: Float, to: MeduMood): MutableMap<String, MeduTrack> {
    if (to == MeduMood.Sad || to == MeduMood.Sleepy) return mutableMapOf(
        "b" to MeduTrack(100f, 130f, listOf(fr(0f, ty(fromY), easing = "ease-in-out"), fr(.22f, ty(fromY - 3f), easing = "ease-in-out"), fr(.55f, ty(8f), easing = "ease-in-out"), fr(1f, ty(0f)))),
        "bell" to MeduTrack(100f, 124f, listOf(fr(0f, "scale(1,1)", easing = "ease-in-out"), fr(.22f, "scale(.97,1.04)", easing = "ease-in-out"), fr(.55f, "scale(1.05,.94)", easing = "ease-in-out"), fr(1f, "scale(1,1)"))),
        "tg" to MeduTrack(100f, 116f, listOf(fr(0f, "scaleY(1)", easing = "ease-in-out"), fr(.22f, "scaleY(1.04)", easing = "ease-in-out"), fr(.55f, "scaleY(.94)", easing = "ease-in-out"), fr(1f, "scaleY(1)"))),
    )
    val top = if (to == MeduMood.Yay || to == MeduMood.Love || to == MeduMood.Party || to == MeduMood.Popcorn || to == MeduMood.Wave) -12f else -6f
    return mutableMapOf(
        "b" to MeduTrack(100f, 130f, listOf(fr(0f, ty(fromY), easing = "ease-in-out"), fr(.16f, ty(fromY + 4f), easing = "cubic-bezier(.2,.7,.3,1)"), fr(.42f, ty(top), easing = "cubic-bezier(.5,0,.5,1)"), fr(.7f, ty(3f), easing = "ease-out"), fr(1f, ty(0f)))),
        "bell" to MeduTrack(100f, 124f, listOf(fr(0f, "scale(1,1)", easing = "ease-out"), fr(.16f, "scale(1.07,.93)", easing = "cubic-bezier(.2,.7,.3,1)"), fr(.42f, "scale(.94,1.08)", easing = "ease-in-out"), fr(.7f, "scale(1.05,.95)", easing = "ease-out"), fr(1f, "scale(1,1)"))),
        "tg" to MeduTrack(100f, 116f, listOf(fr(0f, "scaleY(1)", easing = "ease-out"), fr(.16f, "scaleY(.92)", easing = "ease-out"), fr(.42f, "scaleY(1.1)", easing = "ease-in-out"), fr(.7f, "scaleY(.93)", easing = "ease-in-out"), fr(1f, "scaleY(1)"))),
    )
}

/** A bridge under way: to [to] in [ms], its tracks by part, where the body rests after it, and both moods' parts. */
private class Run(val to: MeduMood, val ms: Int, val tracks: Map<String, Track>, val restY: Float, val parts: Set<Part>)

/** How Medu gets from one mood to the next. [restY] is where the body sits now (Sleepy sinks 10 units when it dozes off). */
private fun plan(from: MeduMood, to: MeduMood, torch: Boolean, restY: Float): Run {
    val key = "${from.key}>${to.key}"
    val drawn = MeduBridges[key]
    val win = Drawn[key] ?: Generic
    val tracks = drawn?.tracks?.toMutableMap() ?: genericBody(restY, to)
    val was = partsOf(from, torch)
    val will = partsOf(to, torch)
    for (part in was + will) {
        if (part.key in tracks) continue
        val face = part in FaceParts
        if (part in was && part in will) {
            if (face && drawn == null) tracks[part.key] = trackOf(part, blink(win.swap))
            continue
        }
        val leaving = part in was
        val frames = when {
            face -> if (leaving) faceOut(win.swap) else faceIn(win.swap)
            part in Limbs -> if (leaving) limbOut(win.exit) else limbIn(win.enter)
            else -> if (leaving) propOut(win.exit) else propIn(win.enter)
        }
        tracks[part.key] = trackOf(part, frames)
    }
    if ("blush" !in tracks && blushOf(from) != blushOf(to)) {
        tracks["blush"] = MeduTrack(100f, 97f, hold(win.blush, fr(0f, null, blushOf(from), "ease-in-out"), fr(0f, null, blushOf(to))))
    }
    // The body starts from where it rests now: swap in that height while the board's track still holds still.
    tracks["b"]?.let { body ->
        val start = body.frames[0].transform
        if (offsetY(start) == restY) return@let
        var holding = true
        tracks["b"] = MeduTrack(body.x, body.y, body.frames.map { f ->
            holding = holding && f.transform == start
            if (holding) fr(f.offset, (f.transform ?: ty(0f)).replace(TyPattern, ty(restY)), f.opacity, f.easing) else f
        }, body.centre)
    }
    val end = tracks["b"]?.frames?.last()?.transform
    return Run(to, drawn?.ms ?: 1000, tracks.mapValues { Track(it.value) }, if ("b" in tracks) offsetY(end) else restY, was + will)
}

/** One CSS transform function as numbers: a translation (x, y), a rotation (x degrees) or a scale (x, y). */
private class Fn(val kind: Int, val x: Float, val y: Float)

private const val Move = 0
private const val Turn = 1
private const val Grow = 2

private val FnPattern = Regex("""(\w+)\(([^)]*)\)""")

/** "translateY(10px) scale(.4)" → its functions, left to right; every frame of a track lists the same ones. */
private fun functions(css: String): List<Fn> = FnPattern.findAll(css).mapNotNull { m ->
    val n = m.groupValues[2].split(',', ' ').filter { it.isNotBlank() }.map { it.trim().removeSuffix("px").removeSuffix("deg").toFloat() }
    when (m.groupValues[1]) {
        "translate" -> Fn(Move, n[0], n.getOrElse(1) { 0f })
        "translateX" -> Fn(Move, n[0], 0f)
        "translateY" -> Fn(Move, 0f, n[0])
        "rotate" -> Fn(Turn, n[0], 0f)
        "scale" -> Fn(Grow, n[0], n.getOrElse(1) { n[0] })
        "scaleX" -> Fn(Grow, n[0], 1f)
        "scaleY" -> Fn(Grow, 1f, n[0])
        else -> null
    }
}.toList()

/** A CSS timing function; Compose's cubic Béziers overshoot as the browser's do. */
private fun easing(css: String?): Easing = when (css) {
    null, "linear" -> LinearEasing
    "ease" -> Ease
    "ease-in" -> EaseIn
    "ease-out" -> EaseOut
    "ease-in-out" -> EaseInOut
    else -> css.substringAfter('(').substringBefore(')').split(',').map { it.trim().toFloat() }.let { CubicBezierEasing(it[0], it[1], it[2], it[3]) }
}

/** The frames of a track that set one property: their offsets, values, and the easing from each to the next. */
private class Keys<T>(frames: List<MeduFrame>, value: (MeduFrame) -> T) {
    val offsets = FloatArray(frames.size) { frames[it].offset }
    val values = frames.map(value)
    val ease = frames.map { easing(it.easing) }

    /** Reads the interval [p] falls in (from the last frame at or before it, as Web Animations do) and how far along, eased. */
    inline fun <R> at(p: Float, read: (a: T, b: T, e: Float) -> R): R {
        if (offsets.size == 1) return read(values[0], values[0], 0f)
        var i = 0
        while (i < offsets.size - 2 && offsets[i + 1] <= p) i++
        val span = offsets[i + 1] - offsets[i]
        val f = if (span <= 0f) 1f else ((p - offsets[i]) / span).coerceIn(0f, 1f)
        return read(values[i], values[i + 1], ease[i].transform(f))
    }
}

/** A bridge's track, ready to read while drawing. */
private class Track(src: MeduTrack) {
    /** Where it turns and scales; null: around the part's own centre. */
    private val pivot: Offset? = if (src.centre) null else Offset(src.x, src.y)
    private val moves = Keys(src.frames.filter { it.transform != null }) { functions(it.transform!!) }
    private val fades = Keys(src.frames.filter { it.opacity != null }) { it.opacity!! }

    fun opacity(p: Float): Float? = if (fades.offsets.isEmpty()) null else fades.at(p) { a, b, e -> (a + (b - a) * e).coerceIn(0f, 1f) }

    /** Applies the transform at [p], its functions left to right around the pivot (or [centre]), as CSS does. */
    fun transform(canvas: androidx.compose.ui.graphics.Canvas, p: Float, centre: Offset) {
        if (moves.offsets.isEmpty()) return
        val o = pivot ?: centre
        canvas.translate(o.x, o.y)
        moves.at(p) { a, b, e ->
            for (k in a.indices) {
                val fa = a[k]
                val fb = b.getOrElse(k) { fa }
                val x = fa.x + (fb.x - fa.x) * e
                val y = fa.y + (fb.y - fa.y) * e
                when (fa.kind) {
                    Move -> canvas.translate(x, y)
                    Turn -> canvas.rotate(x)
                    else -> canvas.scale(x, y)
                }
            }
        }
        canvas.translate(-o.x, -o.y)
    }
}

/**
 * The idle float, pulse and tentacles' place in their loops. When the pace changes (Sleepy wakes up, Yay calms
 * down), each loop goes on from where it was at its new speed instead of jumping (the web's keepPhase); the
 * tentacles' delays stay the share of a cycle they were when Medu first showed, as a CSS animation's do.
 */
private class Loops {
    private var pace: Pace? = null
    private var first = Pace.Normal
    private var since = 0.0
    private var floats = 0.0
    private var wiggles = 0.0

    fun pace(t: Double, now: Pace) {
        val was = pace
        if (was == null) {
            pace = now
            first = now
        } else if (was != now) {
            floats += (t - since) / was.float
            wiggles += (t - since) / was.wiggle
            since = t
            pace = now
        }
    }

    fun float(t: Double): Float = frac(floats + (t - since) / (pace ?: first).float)

    fun wiggle(t: Double, delay: Double): Float = frac(wiggles + (t - since) / (pace ?: first).wiggle - delay / first.wiggle)

    private fun frac(x: Double) = (x - floor(x)).toFloat()
}

/**
 * Which mood Medu shows, and the bridge under way to the next one. [restY] is where the body rests: Sleepy sinks 10
 * units as it dozes off in the board's bridges, and the next bridge starts from there.
 */
private class Stage(mood: MeduMood) {
    var shown by mutableStateOf(mood)
        private set
    private var run by mutableStateOf<Run?>(null)
    var restY by mutableFloatStateOf(0f)
        private set

    /** The loop clock's time when the bridge started. */
    private var since = 0L
    val loops = Loops()

    /** Motion off, or the small Medu: the new mood swaps in place. */
    fun swap(mood: MeduMood) {
        restY = 0f
        shown = mood
    }

    /** Plays [run] to its end, then hands over to its mood's plain drawing. */
    suspend fun play(run: Run, clock: MutableLongState) {
        since = clock.longValue
        this.run = run
        val ns = run.ms * 1_000_000L
        while (clock.longValue - since < ns) withFrameNanos { }
        restY = run.restY
        shown = run.to
        this.run = null
    }

    fun pose(clock: MutableLongState?, torch: Boolean): Pose {
        val run = run
        if (run == null || clock == null) return Pose(shown, null, 0f, restY, partsOf(shown, torch))
        val p = ((clock.longValue - since) / (run.ms * 1e6)).toFloat().coerceIn(0f, 1f)
        return Pose(shown, run, p, restY, run.parts)
    }
}

/** One moment of Medu: [mood]'s [parts] at rest, or during a bridge ([run], at [p]) both moods' parts as it moves them. */
private class Pose(val mood: MeduMood, val run: Run?, val p: Float, val restY: Float, val parts: Set<Part>) {
    fun track(key: String): Track? = run?.tracks?.get(key)
}

/** Room for a part fading in a bridge (drawing units around Medu); the torch's beam needs more. */
private val LayerBounds = Rect(-120f, -160f, 320f, 360f)
private val BeamBounds = Rect(-120f, -500f, 1000f, 800f)

/**
 * Draws [block] moved and faded by the bridge's track [key], if any, turning around [centre] when the track has no
 * pivot of its own. A part fades as a whole (the web's group opacity), so its shapes don't show through each other.
 */
private inline fun DrawScope.moved(pose: Pose, key: String, centre: Offset = Offset.Zero, bounds: Rect = LayerBounds, crossinline block: DrawScope.() -> Unit) {
    val track = pose.track(key)
    if (track == null) {
        block()
        return
    }
    val alpha = track.opacity(pose.p) ?: 1f
    if (alpha <= 0f) return
    val canvas = drawContext.canvas
    canvas.save()
    track.transform(canvas, pose.p, centre)
    if (alpha < 1f) canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha })
    block()
    if (alpha < 1f) canvas.restore()
    canvas.restore()
}

/** Draws [part] when the pose has it, as its bridge's track moves it. */
private inline fun DrawScope.part(pose: Pose, part: Part, centre: Offset = Offset.Zero, bounds: Rect = LayerBounds, crossinline block: DrawScope.() -> Unit) {
    if (part in pose.parts) moved(pose, part.key, centre, bounds, block)
}

// ---- Drawing, in the SVG's own units --------------------------------------------------------------------------

private val Body = Color(0xFFEFDFF3)
private val Ink = Color(0xFF1E1E1E)
private val Frill = Color(0xFFD6B3DE)
private val Tear = Color(0xFF9FD3FF)
private val Light = Color(0xFFFFF4D6)
private val Mist = Color(0xFFE0E0E0)
private val Corn = Color(0xFFFFF3CF)
private val CornEdge = Color(0xFFECD59C)

private fun p(d: String): Path = PathParser().parsePathString(d).toPath()

/** A tentacle: its path, the top centre of its box (it swings from there), its delay, ink and width. */
private class Tentacle(val path: Path, val pivot: Offset, val delay: Double, val accent: Boolean, val width: Float)

private object Art {
    val bell = p("M30 108 C30 52 66 22 100 22 C134 22 170 52 170 108 Q156 124 142 110 Q128 124 114 110 Q100 124 86 110 Q72 124 58 110 Q44 124 30 108 Z")
    val frill = p("M30 108 Q44 124 58 110 Q72 124 86 110 Q100 124 114 110 Q128 124 142 110 Q156 124 170 108")
    val bodyFill = Brush.verticalGradient(listOf(Color(0xFFFDF6FE), Color(0xFFEBD3F0)), startY = 22f, endY = 117f)

    private val a = p("M48 112 C40 136 56 152 46 174 C38 192 50 206 44 220")
    private val d = p("M152 112 C160 136 144 152 154 174 C162 192 150 206 156 220")

    /** The outer tentacles before the fourth, the fourth (it holds the torch or drops the plug), then the inner two. */
    val outer = listOf(
        Tentacle(a, Offset(46.277f, 112f), -.4, false, 6f),
        Tentacle(p("M70 117 C64 142 78 160 68 184 C62 200 72 214 66 228"), Offset(68.861f, 117f), -1.2, false, 6f),
        Tentacle(p("M130 117 C136 142 122 160 132 184 C138 200 128 214 134 228"), Offset(131.139f, 117f), -.8, false, 6f),
    )
    val fourth = Tentacle(d, Offset(153.723f, 112f), -1.6, false, 6f)
    val inner = listOf(
        Tentacle(p("M90 116 C82 132 98 144 88 160 C80 174 94 182 88 196"), Offset(88.433f, 116f), -2.0, true, 8f),
        Tentacle(p("M110 116 C118 132 102 144 112 160 C120 174 106 182 112 196"), Offset(111.567f, 116f), -.8, true, 8f),
    )
    val small = listOf(
        Tentacle(a, Offset(46.277f, 112f), -.4, false, 12f),
        Tentacle(p("M100 118 C94 144 108 162 98 190 C92 206 102 218 98 230"), Offset(98.875f, 118f), -.8, false, 12f),
        Tentacle(d, Offset(153.723f, 112f), -1.2, false, 12f),
    )

    val waveArm = p("M163 96 C180 92 188 76 180 60")
    val beam = p("M30 -6 L640 -160 L640 160 L30 6 Z")
    val torchArm = p("M152 112 C156 126 166 133 192 131")
    val plugTentacle = p("M152 112 C160 136 146 150 156 170 C162 182 168 190 170 200")
    val cable = p("M170 200 C172 212 160 218 166 230")
    val prongs = p("M161 239v6M171 239v6")
    val sweat = p("M150 62 C150 62 143 72 143 77 a7 7 0 0 0 14 0 C157 72 150 62 150 62 Z")
    val tear = p("M73 95 C73 95 68 102 68 105 a5 5 0 0 0 10 0 C78 102 73 95 73 95 Z")
    val spark = p("M0 -10 C1.5 -2 2 -1.5 10 0 C2 1.5 1.5 2 0 10 C-1.5 2 -2 1.5 -10 0 C-2 -1.5 -1.5 -2 0 -10 Z")
    val heart = p("M0 8 C-12 0 -8 -10 0 -4 C8 -10 12 0 0 8 Z")

    val smile = p("M93 95 Q100 102 107 95")
    val searchBrowL = p("M71 69 Q78 63 86 66")
    val searchBrowR = p("M114 64 Q121 61 129 66")
    val wobble = p("M93 99 q3.5 -4 7 0 t7 0")
    val sleepL = p("M73 86 Q80 92 87 86")
    val sleepR = p("M113 86 Q120 92 127 86")
    val happyL = p("M73 88 Q80 79 87 88")
    val happyR = p("M113 88 Q120 79 127 88")
    val grin = p("M89 94 Q100 112 111 94 Z")
    val tongue = p("M95 101 Q100 106 105 101 Q100 98 95 101 Z")
    val flat = p("M94 98 L106 97")
    val oopsBrowL = p("M70 72 L86 67")
    val oopsBrowR = p("M130 72 L114 67")
    val frown = p("M92 101 Q100 94 108 101")
    val heartEyes = listOf(p("M80 96 C63 86 69 70 80 77 C91 70 97 86 80 96 Z"), p("M120 96 C103 86 109 70 120 77 C131 70 137 86 120 96 Z"))
    val loveMouth = p("M89 98 Q100 113 111 98 Z")
    val sadBrowL = p("M70 71 Q78 64 86 68")
    val sadBrowR = p("M114 68 Q122 64 130 71")
    val sadMouth = p("M92 103 Q100 96 108 103")
    val partyHat = p("M78 31 Q100 40 122 31 L106 -18 Z")
    val partyStripes = p("M86 14 Q100 20 114 14 M93 -2 Q101 2 109 -2")
    val shadesFrame = p("M95 79 Q100 75 105 79 M64 78 L48 72 M136 78 L152 72")
    val shadesGlint = p("M71 77 L79 86 M112 77 L120 86")
    val partyMouth = p("M88 98 Q100 116 112 98 Z")
    val partyTongue = p("M94 105 Q100 111 106 105 Q100 101 94 105 Z")

    val bucket = p("M70 126 L130 126 L123 176 L77 176 Z")
    val stripes = p("M84 126 L96 126 L95 176 L86 176 Z M106 126 L118 126 L113 176 L104 176 Z")
    val holding = p("M56 112 C52 132 62 146 76 146 M144 112 C148 132 138 146 124 146")

    /** The beam's light, along its length; it's blurred (stdDeviation 5) on the boards. */
    val beamPaint: Paint by lazy {
        Paint().apply {
            shader = LinearGradientShader(
                Offset(30f, 0f), Offset(640f, 0f),
                listOf(Light.copy(alpha = .55f), Light.copy(alpha = .2f), Light.copy(alpha = 0f)),
                listOf(0f, .35f, 1f),
            )
            // Android turns a blur radius into sigma = radius × 0.57735 + 0.5; the filter follows the scale.
            nativePaint.maskFilter = BlurMaskFilter((5f - .5f) / .57735f, BlurMaskFilter.Blur.NORMAL)
        }
    }
}

// ---- The boards' keyframes ------------------------------------------------------------------------------------

/** How far into its loop (0..1) an animation of [dur] seconds with CSS `animation-delay: [delay]` is at [t]. */
internal fun loop(t: Double, dur: Double, delay: Double = 0.0): Float {
    val x = (t - delay) / dur
    return (x - floor(x)).toFloat()
}

/** A CSS keyframe track: [values] at offsets [at], [ease] applied within each step. */
internal fun track(p: Float, at: FloatArray, values: FloatArray, ease: Easing): Float {
    var i = 0
    while (i < at.size - 2 && p > at[i + 1]) i++
    val span = at[i + 1] - at[i]
    val f = if (span <= 0f) 1f else ((p - at[i]) / span).coerceIn(0f, 1f)
    return values[i] + (values[i + 1] - values[i]) * ease.transform(f)
}

private val Half = floatArrayOf(0f, .5f, 1f)

private fun swing(p: Float, from: Float, to: Float) = track(p, Half, floatArrayOf(from, to, from), EaseInOut)

private fun DrawScope.stroke(path: Path, color: Color, width: Float, cap: StrokeCap = StrokeCap.Round, join: StrokeJoin = StrokeJoin.Miter) =
    drawPath(path, color, style = Stroke(width, cap = cap, join = join))

/**
 * Medu at one [pose], in the web's layers: the halo, then the body ("b": where it rests, or as a bridge moves it)
 * holding the float (tentacles "tg", the arm or torch, the pulsing bell), then what floats beside it and the sparks.
 * [t] runs the loops (null: rest pose); [loops] keeps the idle float's place when the pace changes.
 */
private fun DrawScope.drawMedu(
    pose: Pose,
    t: Double?,
    loops: Loops?,
    accent: Color,
    small: Boolean,
    glow: Boolean,
    bubbles: Boolean,
    texts: MeduTexts?,
) {
    // m-slow / m-fast: Sleepy and Sad float at half speed, Yay at double (the small Medu keeps the normal pace).
    val idle = if (t != null && loops != null) loops.also { it.pace(t, if (small) Pace.Normal else paceOf(pose.mood)) } else null
    if (glow) {
        drawCircle(
            Brush.radialGradient(listOf(accent.copy(alpha = .32f), accent.copy(alpha = 0f)), center = Offset(100f, 96f), radius = 108f),
            radius = 108f, center = Offset(100f, 96f),
        )
    }
    if (bubbles) {
        for ((x, y, r, delay) in listOf(Spot(26f, 196f, 5f, -.6), Spot(176f, 170f, 7f, -2.1), Spot(160f, 226f, 4f, -3.3), Spot(40f, 140f, 3.5f, -1.4))) {
            var dy = 0f
            var alpha = 1f
            if (t != null) {
                val q = loop(t, 4.5, delay)
                dy = -110f * EaseIn.transform(q)
                alpha = track(q, floatArrayOf(0f, .2f, 1f), floatArrayOf(0f, .75f, 0f), EaseIn)
            }
            drawCircle(Color.White.copy(alpha = .6f * alpha), r, Offset(x, y + dy), style = Stroke(2f))
        }
    }
    val body: DrawScope.() -> Unit = {
        val bob = if (t == null || idle == null) 0f else swing(idle.float(t), 0f, -8f)
        translate(0f, bob) {
            if (small) {
                for (tn in Art.small) tentacle(tn, t, idle, accent)
                drawSmallBell()
            } else {
                moved(pose, "tg") {
                    for (tn in Art.outer) tentacle(tn, t, idle, accent)
                    part(pose, Part.D4) { tentacle(Art.fourth, t, idle, accent) }
                    for (tn in Art.inner) tentacle(tn, t, idle, accent)
                    if (Part.PlugT in pose.parts || Part.Plug in pose.parts) {
                        rotate(if (t == null) 0f else swing(loop(t, 2.6), -8f, 8f), Offset(163.5f, 112f)) {
                            part(pose, Part.PlugT) { stroke(Art.plugTentacle, Body, 6f) }
                            part(pose, Part.Plug) {
                                stroke(Art.cable, Color(0xFF8C8C8C), 3.5f)
                                drawRoundRect(Color(0xFFA1A1A1), Offset(157f, 226f), Size(18f, 13f), CornerRadius(3.5f))
                                stroke(Art.prongs, Color(0xFFA1A1A1), 3f)
                            }
                        }
                    }
                }
                part(pose, Part.Arm) {
                    rotate(if (t == null) 0f else swing(loop(t, 1.1), -14f, 16f), Offset(163f, 96f)) {
                        stroke(Art.waveArm, Body, 8f)
                        drawCircle(Body, 6.5f, Offset(180f, 58f))
                    }
                }
                part(pose, Part.Torch, bounds = BeamBounds) {
                    withTransform({ translate(186f, 128f); rotate(16f, Offset.Zero) }) {
                        rotate(if (t == null) 0f else swing(loop(t, 6.0), -16f, 14f), Offset(30f, 0f)) {
                            drawIntoCanvas { it.drawPath(Art.beam, Art.beamPaint) }
                        }
                        drawRoundRect(Color(0xFFA1A1A1), Offset(0f, -6f), Size(22f, 12f), CornerRadius(3f))
                        drawRoundRect(Color(0xFFD4D4D4), Offset(20f, -9f), Size(11f, 18f), CornerRadius(3f))
                        drawLine(Light, Offset(31f, -7f), Offset(31f, 7f), 3f, StrokeCap.Round)
                    }
                    stroke(Art.torchArm, Body, 6.5f)
                }
                var sx = 1f
                var sy = 1f
                if (t != null && idle != null) {
                    val q = idle.float(t)
                    val at = floatArrayOf(0f, .4f, .65f, 1f)
                    sx = track(q, at, floatArrayOf(1f, 1.05f, .98f, 1f), EaseInOut)
                    sy = track(q, at, floatArrayOf(1f, .94f, 1.03f, 1f), EaseInOut)
                }
                // The pulse squeezes from the bottom of the bell's box (CSS transform-box: fill-box), which reaches
                // the bucket's foot while Medu holds the popcorn.
                scale(sx, sy, Offset(100f, if (Part.Popcorn in pose.parts) 176f else 117f)) {
                    moved(pose, "bell") { drawBell(pose, t, accent) }
                }
            }
        }
        if (!small) drawExtras(pose, t, accent, texts)
        drawSparks(pose, t, accent)
    }
    if (pose.track("b") != null) moved(pose, "b") { body() } else translate(0f, pose.restY, body)
}

private data class Spot(val x: Float, val y: Float, val size: Float, val delay: Double)

private fun DrawScope.tentacle(tn: Tentacle, t: Double?, idle: Loops?, accent: Color) {
    val angle = if (t == null || idle == null) 0f else swing(idle.wiggle(t, tn.delay), -5f, 5f)
    rotate(angle, tn.pivot) {
        stroke(tn.path, if (tn.accent) accent.copy(alpha = .75f) else Body, tn.width)
    }
}

/** The under-80 px bell: no pulse, no face details, bigger eyes. */
private fun DrawScope.drawSmallBell() {
    drawPath(Art.bell, Art.bodyFill)
    stroke(Art.frill, Frill.copy(alpha = .85f), 2.5f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    for (x in floatArrayOf(78f, 122f)) {
        drawOval(Ink, Offset(x - 10f, 84f - 12.5f), Size(20f, 25f))
        drawCircle(Color.White, 3.6f, Offset(x + 3f, 79f))
    }
}

/** The round eyes, [dy] units up or down, blinking every 5 s and, lost, looking around (m-eyes, m-look). */
private fun DrawScope.eyes(dy: Float, t: Double?, look: Boolean) {
    val cy = 83f + dy
    val blink = if (t == null) 1f else track(loop(t, 5.0), floatArrayOf(0f, .93f, .96f, 1f), floatArrayOf(1f, 1f, .1f, 1f), Ease)
    val dx = if (t != null && look) {
        track(loop(t, 3.4), floatArrayOf(0f, .18f, .42f, .64f, .88f, 1f), floatArrayOf(-4f, -4f, 4f, 4f, -4f, -4f), EaseInOut)
    } else 0f
    scale(1f, blink, Offset(100f, cy)) {
        translate(dx, 0f) {
            for (x in floatArrayOf(80f, 120f)) {
                drawOval(Ink, Offset(x - 8.5f, cy - 10.5f), Size(17f, 21f))
                drawCircle(Color.White, 3.4f, Offset(x + 2.8f, 78f + dy * 5f / 3f))
                drawCircle(Color.White, 1.6f, Offset(x - 2.4f, 88f + dy))
            }
        }
    }
}

/** The bell and what's on it: the face parts in the web's order, the tear, the blush, then the popcorn it holds. */
private fun DrawScope.drawBell(pose: Pose, t: Double?, accent: Color) {
    drawPath(Art.bell, Art.bodyFill)
    stroke(Art.frill, Frill.copy(alpha = .85f), 2.5f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    drawCircle(accent.copy(alpha = .32f), 6.5f, Offset(76f, 44f))
    drawCircle(accent.copy(alpha = .32f), 4.2f, Offset(100f, 35f))
    drawCircle(accent.copy(alpha = .32f), 5.5f, Offset(124f, 45f))
    rotate(24f, Offset(54f, 70f)) { drawOval(Color.White.copy(alpha = .9f), Offset(47f, 55f), Size(14f, 30f)) }
    part(pose, Part.Eyes) { eyes(0f, t, look = false) }
    part(pose, Part.EyesLook) { eyes(0f, t, look = true) }
    part(pose, Part.EyesUp) { eyes(-3f, t, look = false) }
    part(pose, Part.EyesClosed) {
        stroke(Art.sleepL, Ink, 3.2f)
        stroke(Art.sleepR, Ink, 3.2f)
    }
    part(pose, Part.EyesHappy) {
        stroke(Art.happyL, Ink, 3.6f)
        stroke(Art.happyR, Ink, 3.6f)
    }
    part(pose, Part.EyesHeart) {
        Art.heartEyes.forEachIndexed { i, heart ->
            drawPath(heart, accent)
            drawCircle(Color.White.copy(alpha = .9f), 2.4f, Offset(74f + 40f * i, 78f))
        }
    }
    part(pose, Part.BrowsLost) {
        stroke(Art.searchBrowL, Ink, 3f)
        stroke(Art.searchBrowR, Ink, 3f)
    }
    part(pose, Part.BrowsOops) {
        stroke(Art.oopsBrowL, Ink, 3f)
        stroke(Art.oopsBrowR, Ink, 3f)
    }
    part(pose, Part.BrowsSad) {
        stroke(Art.sadBrowL, Ink, 3f)
        stroke(Art.sadBrowR, Ink, 3f)
    }
    part(pose, Part.MSmile) { stroke(Art.smile, Ink, 3.2f) }
    part(pose, Part.MWobble) { stroke(Art.wobble, Ink, 3f, join = StrokeJoin.Round) }
    part(pose, Part.MO) { drawOval(Ink, Offset(100f - 3.4f, 99f - 2.8f), Size(6.8f, 5.6f)) }
    part(pose, Part.MFlat) { stroke(Art.flat, Ink, 3f) }
    part(pose, Part.MGrin) {
        drawPath(Art.grin, Ink)
        stroke(Art.grin, Ink, 2.4f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
        drawPath(Art.tongue, accent)
    }
    part(pose, Part.MFrown) { stroke(Art.frown, Ink, 3f) }
    part(pose, Part.MSad) { stroke(Art.sadMouth, Ink, 3f) }
    // Party: the hat, the sunglasses and a wide grin (the web's "party" part, one piece).
    part(pose, Part.PartyFace) {
        drawPath(Art.partyHat, accent)
        stroke(Art.partyStripes, Color.White.copy(alpha = .9f), 4f)
        drawCircle(Color.White, 8f, Offset(106f, -19f))
        drawRoundRect(Ink, Offset(64f, 72f), Size(31f, 22f), CornerRadius(9f))
        drawRoundRect(Ink, Offset(105f, 72f), Size(31f, 22f), CornerRadius(9f))
        stroke(Art.shadesFrame, Ink, 3.5f)
        stroke(Art.shadesGlint, Color.White.copy(alpha = .85f), 3f)
        drawPath(Art.partyMouth, Ink)
        stroke(Art.partyMouth, Ink, 2.4f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
        drawPath(Art.partyTongue, accent)
    }
    part(pose, Part.MLove) {
        drawPath(Art.loveMouth, Ink)
        stroke(Art.loveMouth, Ink, 2.4f, cap = StrokeCap.Butt, join = StrokeJoin.Round)
    }
    // m-chew: the mouth squashes shut and opens again.
    part(pose, Part.MChew) {
        scale(1f, if (t == null) 1f else swing(loop(t, .55), 1f, .35f), Offset(100f, 99f)) {
            drawOval(Ink, Offset(100f - 4.5f, 99f - 3.4f), Size(9f, 6.8f))
        }
    }
    // m-tear: it wells up under the left eye, then rolls down and fades.
    part(pose, Part.Tear) {
        var dy = 0f
        var k = 1f
        var alpha = 1f
        if (t != null) {
            val q = loop(t, 2.4)
            val at = floatArrayOf(0f, .2f, .35f, 1f)
            dy = track(q, at, floatArrayOf(0f, 0f, 0f, 34f), EaseIn)
            k = track(q, at, floatArrayOf(.4f, .4f, 1f, .9f), EaseIn)
            alpha = track(q, at, floatArrayOf(0f, 0f, 1f, 0f), EaseIn)
        }
        withTransform({ translate(0f, dy); scale(k, k, Offset(73f, 95f)) }) { drawPath(Art.tear, Tear, alpha = alpha) }
    }
    val blush = pose.track("blush")?.opacity(pose.p) ?: blushOf(pose.mood)
    drawOval(accent.copy(alpha = blush), Offset(64f - 8.5f, 97f - 4.6f), Size(17f, 9.2f))
    drawOval(accent.copy(alpha = blush), Offset(136f - 8.5f, 97f - 4.6f), Size(17f, 9.2f))
    part(pose, Part.Popcorn) { drawPopcorn(t, accent) }
}

private val Kernels = listOf(Spot(80f, 124f, 7f, 0.0), Spot(91f, 119f, 8f, 0.0), Spot(103f, 117f, 8f, 0.0), Spot(115f, 119f, 8f, 0.0), Spot(125f, 124f, 6f, 0.0), Spot(97f, 127f, 6f, 0.0), Spot(110f, 127f, 6f, 0.0))

private fun DrawScope.kernel(at: Offset, r: Float, alpha: Float = 1f) {
    drawCircle(Corn, r, at, alpha = alpha)
    drawCircle(CornEdge, r, at, alpha = alpha, style = Stroke(1.5f))
}

/** Popcorn: the striped bucket held in two tentacles, a kernel hopping out of it (m-pop). */
private fun DrawScope.drawPopcorn(t: Double?, accent: Color) {
    for (k in Kernels) kernel(Offset(k.x, k.y), k.size)
    drawPath(Art.bucket, Color(0xFFFDF6FE))
    drawPath(Art.stripes, accent)
    drawRoundRect(Color(0xFFF4EEF6), Offset(67f, 122f), Size(66f, 8f), CornerRadius(3f))
    var dx = 0f
    var dy = 0f
    var alpha = 1f
    if (t != null) {
        val q = loop(t, 1.8)
        val at = floatArrayOf(0f, .55f, .75f, .9f, 1f)
        dx = track(q, at, floatArrayOf(0f, 0f, 6f, 10f, 12f), EaseOut)
        dy = track(q, at, floatArrayOf(0f, 0f, -26f, -8f, 4f), EaseOut)
        alpha = track(q, floatArrayOf(0f, .55f, .6f, .9f, 1f), floatArrayOf(0f, 0f, 1f, 1f, 0f), EaseOut)
    }
    kernel(Offset(108f + dx, 114f + dy), 5f, alpha)
    stroke(Art.holding, Body, 6.5f)
}

private val Hearts = listOf(Spot(160f, 34f, 1.2f, 0.0), Spot(34f, 58f, .9f, -.9), Spot(172f, 92f, .8f, -1.7))

/** The heart's own centre (its box), where it grows from. */
private val HeartCentre = Offset(0f, 1.085f)

/** What floats beside Medu rather than with it: the question mark and sweat, z's, the thinking dots, hearts. */
private fun DrawScope.drawExtras(pose: Pose, t: Double?, accent: Color, texts: MeduTexts?) {
    if (texts != null) part(pose, Part.Q) {
        val q = texts.question
        val w = q.getLineRight(0)
        // Tilts from the bottom centre of its text box (font ascent and descent around the baseline at y 22).
        val origin = Offset(164f, 22f - q.firstBaseline + q.getLineBottom(0))
        val p = if (t == null) null else loop(t, 2.2)
        val angle = if (p == null) 0f else swing(p, -12f, 12f)
        val lift = if (p == null) 0f else swing(p, 0f, -4f)
        withTransform({ rotate(angle, origin); translate(0f, lift) }) {
            drawText(q, accent, Offset(164f - w / 2f, 22f - q.firstBaseline))
        }
    }
    part(pose, Part.Sweat) { drawPath(Art.sweat, Tear.copy(alpha = .9f)) }
    if (texts != null) part(pose, Part.Zs) {
        val spots = listOf(Triple(148f, 40f, 0.0), Triple(166f, 18f, -1.2), Triple(186f, -6f, -2.4))
        spots.forEachIndexed { i, (x, y, delay) ->
            val z = texts.z[i]
            val top = y - z.firstBaseline
            val origin = Offset(x + z.getLineRight(0) / 2f, top + z.getLineBottom(0) / 2f)
            var dx = 0f
            var dy = 0f
            var k = 1f
            var alpha = 1f
            if (t != null) {
                val p = loop(t, 3.6, delay)
                val e = EaseOut.transform(p)
                dx = 16f * e
                dy = -38f * e
                k = .5f + (1.15f - .5f) * e
                alpha = track(p, floatArrayOf(0f, .25f, 1f), floatArrayOf(0f, 1f, 0f), EaseOut)
            }
            withTransform({ translate(dx, dy); scale(k, k, origin) }) {
                drawText(z, Mist, Offset(x, top), alpha = alpha)
            }
        }
    }
    part(pose, Part.Dots) {
        for ((i, x) in floatArrayOf(82f, 100f, 118f).withIndex()) {
            var dy = 0f
            var alpha = 1f
            if (t != null) {
                val p = loop(t, 1.2, .18 * i)
                val at = floatArrayOf(0f, .35f, 1f)
                dy = track(p, at, floatArrayOf(0f, -7f, 0f), EaseInOut)
                alpha = track(p, at, floatArrayOf(.35f, 1f, .35f), EaseInOut)
            }
            drawCircle(if (i == 1) accent else Mist, 6f, Offset(x, dy), alpha = alpha)
        }
    }
    // m-heart: each heart rises from below, grows and fades.
    part(pose, Part.Hearts) {
        for (h in Hearts) {
            var dy = 0f
            var k = 1f
            var alpha = 1f
            if (t != null) {
                val p = loop(t, 2.6, h.delay)
                val e = EaseOut.transform(p)
                dy = 8f + (-30f - 8f) * e
                k = .5f + (1.1f - .5f) * e
                alpha = track(p, floatArrayOf(0f, .25f, 1f), floatArrayOf(0f, 1f, 0f), EaseOut)
            }
            withTransform({ translate(h.x, h.y); scale(h.size, h.size, Offset.Zero); translate(0f, dy); scale(k, k, HeartCentre) }) {
                drawPath(Art.heart, accent, alpha = alpha)
            }
        }
    }
}

private class Spark(val part: Part, val x: Float, val y: Float, val scale: Float, val accent: Boolean, val delay: Double)

private val SparkSpots = listOf(
    Spark(Part.Spark0, 28f, 30f, 1.1f, true, 0.0), Spark(Part.Spark1, 176f, 14f, 1.4f, false, -.5),
    Spark(Part.Spark2, 186f, 96f, .9f, true, -1.0), Spark(Part.Spark3, 18f, 110f, .8f, false, -.8),
)

/** Yay's sparkles, each pulsing and turning on its spot; a bridge pops each one from its own centre. */
private fun DrawScope.drawSparks(pose: Pose, t: Double?, accent: Color) {
    for (sp in SparkSpots) {
        if (sp.part !in pose.parts) continue
        withTransform({ translate(sp.x, sp.y); scale(sp.scale, sp.scale, Offset.Zero) }) {
            part(pose, sp.part) {
                var k = 1f
                var angle = 0f
                var alpha = 1f
                if (t != null) {
                    val p = loop(t, 1.5, sp.delay)
                    k = swing(p, .35f, 1f)
                    angle = swing(p, 0f, 20f)
                    alpha = swing(p, .25f, 1f)
                }
                withTransform({ scale(k, k, Offset.Zero); rotate(angle, Offset.Zero) }) {
                    drawPath(Art.spark, if (sp.accent) accent else Color.White, alpha = alpha)
                }
            }
        }
    }
}
