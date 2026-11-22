package io.github.arkmind.seedy.ui.wrapped

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.EaseIn
import androidx.compose.animation.core.EaseInOut
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.ui.components.seconds
import kotlin.math.floor

/*
 * The Wrapped boards' CSS animations (.wr .in, .pop, .say, .fan, .grow, .draw, .odo and the looping .drift, .spin,
 * .twinkle, .bub, .glow), run from two clocks the viewer provides: the story's own time (entrances start when a story
 * comes up) and the loop clock (backgrounds). With motion off both are null: entrances are done and loops rest as the
 * boards' `prefers-reduced-motion` styles leave them (`animation: none`).
 */

/** Seconds since the story on screen came up; null with motion off. */
val LocalStoryTime = compositionLocalOf<FloatState?> { null }

/** The background loops' clock (Medu's rememberLoopClock); null with motion off. */
val LocalStoryLoop = compositionLocalOf<MutableLongState?> { null }

internal val InEase = CubicBezierEasing(.2f, .8f, .2f, 1f)
internal val PopEase = CubicBezierEasing(.2f, .9f, .25f, 1.3f)
internal val FanEase = CubicBezierEasing(.2f, .9f, .25f, 1.2f)
val OdoEase = CubicBezierEasing(.15f, .85f, .2f, 1f)

/** How far (0..1, eased) an entrance of [dur] s starting at [delay] s is at story time [t]; 1 without a clock. */
fun enter(t: Float?, delay: Float, dur: Float, ease: Easing = InEase): Float =
    if (t == null) 1f else ease.transform(((t - delay) / dur).coerceIn(0f, 1f))

/** .in: fades in, rising [rise] (26 px on the phone boards), .7 s. */
fun Modifier.storyIn(delay: Float, rise: Dp = 26.dp): Modifier = composed {
    val time = LocalStoryTime.current
    graphicsLayer {
        val p = enter(time?.floatValue, delay, .7f)
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * rise.toPx()
    }
}

/** .pop: from half size, turned −6°, to the element's own [rotation], .8 s with an overshoot. */
fun Modifier.storyPop(delay: Float, rotation: Float = 0f): Modifier = composed {
    val time = LocalStoryTime.current
    graphicsLayer {
        val p = enter(time?.floatValue, delay, .8f, PopEase)
        alpha = p.coerceIn(0f, 1f)
        val k = .5f + .5f * p
        scaleX = k
        scaleY = k
        rotationZ = -6f + (rotation + 6f) * p
    }
}

/** .say: a speech bubble growing from the middle of the side Medu is on ([fromEnd]: its end), .6 s. */
fun Modifier.storySay(delay: Float, fromEnd: Boolean): Modifier = composed {
    val time = LocalStoryTime.current
    graphicsLayer {
        val p = enter(time?.floatValue, delay, .6f, PopEase)
        alpha = p.coerceIn(0f, 1f)
        val k = .4f + .6f * p
        scaleX = k
        scaleY = k
        transformOrigin = TransformOrigin(if (fromEnd) 1f else 0f, .5f)
    }
}

/** .fan: a card rising [rise] into its place, turning to [rotation] and fading to [opacity], .8 s. */
fun Modifier.storyFan(delay: Float, rotation: Float, opacity: Float, rise: Dp = 40.dp): Modifier = composed {
    val time = LocalStoryTime.current
    graphicsLayer {
        val p = enter(time?.floatValue, delay, .8f, FanEase)
        alpha = (opacity * p).coerceIn(0f, 1f)
        translationY = (1f - p) * rise.toPx()
        rotationZ = rotation * p
    }
}

/** .grow: a bar growing from its left end, 1.1 s. */
fun Modifier.storyGrow(delay: Float): Modifier = composed {
    val time = LocalStoryTime.current
    graphicsLayer {
        scaleX = enter(time?.floatValue, delay, 1.1f)
        transformOrigin = TransformOrigin(0f, .5f)
    }
}

/** Where a loop of [dur] s (CSS `animation-delay: [delay]`) is, 0..1, at loop time [t]. */
fun loopAt(t: Double, dur: Double, delay: Double = 0.0): Float {
    val x = (t - delay) / dur
    return (x - floor(x)).toFloat()
}

/** An `alternate` loop of [dur] s: 0 → 1 → 0 over twice its length, eased in and out. */
fun alternate(t: Double, dur: Double): Float {
    val p = loopAt(t, dur * 2)
    val half = if (p < .5f) p * 2f else 2f - p * 2f
    return EaseInOut.transform(half)
}

/** The story's loop time in seconds, or null at rest; read it while drawing. */
fun MutableLongState?.loopSeconds(): Double? = this.seconds()

/** .twinkle: .25 → 1 → .25 over 2.6 s; 1 at rest. */
fun twinkle(t: Double?, delay: Double): Float {
    if (t == null) return 1f
    val p = loopAt(t, 2.6, delay)
    val half = if (p < .5f) p * 2f else 2f - p * 2f
    return .25f + .75f * EaseInOut.transform(half)
}

/** .bub: rising 260 px and fading (0 → .6 at 15 % → 0) over 7 s; at rest, in place and fully shown. */
fun bubble(t: Double?, delay: Double): Pair<Float, Float> {
    if (t == null) return 0f to 1f
    val p = loopAt(t, 7.0, delay)
    val e = EaseIn.transform(p)
    val alpha = if (p < .15f) .6f * EaseIn.transform(p / .15f) else .6f * (1f - EaseIn.transform((p - .15f) / .85f))
    return -260f * e to alpha
}

/** .glow: opacity .55 → .9 and a 6 % swell over 4 s; at rest fully shown, at its size. */
fun glow(t: Double?): Pair<Float, Float> {
    if (t == null) return 1f to 1f
    val p = loopAt(t, 4.0)
    val half = if (p < .5f) p * 2f else 2f - p * 2f
    val e = EaseInOut.transform(half)
    return (.55f + .35f * e) to (1f + .06f * e)
}
