package io.github.arkmind.seedy.ui.components

// Medu's bridges between moods (board "Brand · Mascot", "Moving between moods"), the same data as the web's
// web/src/ui/medu-bridges.ts: the board loops each pair there and back; these are its two halves, each rescaled to
// 0..1 over its own duration, keyframe for keyframe. A track moves one part of Medu with CSS-style transforms around
// (x, y) in the drawing's units, or around the part's own centre when `centre` (the board's transform-box: fill-box).
// Every frame of a track lists the same transform functions, so they interpolate number by number.
// A frame's easing runs from that frame to the next one (CSS keyframe timing functions).

internal class MeduFrame(val offset: Float, val transform: String? = null, val opacity: Float? = null, val easing: String? = null)

internal class MeduTrack(val x: Float, val y: Float, val frames: List<MeduFrame>, val centre: Boolean = false)

internal class MeduBridge(val ms: Int, val tracks: Map<String, MeduTrack>)

private fun f(offset: Float, transform: String? = null, opacity: Float? = null, easing: String? = null) = MeduFrame(offset, transform, opacity, easing)

internal val MeduBridges: Map<String, MeduBridge> = mapOf(
    // Lost → Sleepy: the question mark droops and fades, a big yawn, heavy eyes shut, Medu sinks and settles.
    "search>sleepy" to MeduBridge(
        2250,
        mapOf(
            "eyesLook" to MeduTrack(100f, 83f, listOf(f(0f, "scaleY(1)", 1f), f(0.13332f, "scaleY(1)", 1f, easing = "ease-in-out"), f(0.23112f, "scaleY(.45)", 1f), f(0.37776f, "scaleY(.45)", 1f, easing = "ease-out"), f(0.46668f, "scaleY(.8)", 1f, easing = "ease-in"), f(0.5422f, "scaleY(.8)", 1f, easing = "ease-in"), f(0.61332f, "scaleY(.08)", 1f), f(0.6222f, "scaleY(.08)", 0f), f(1f, "scaleY(.08)", 0f))),
            "eyesClosed" to MeduTrack(100f, 86f, listOf(f(0f, "scaleY(.6)", 0f), f(0.60444f, "scaleY(.6)", 0f, easing = "ease-out"), f(0.64888f, "scaleY(1)", 1f), f(1f, "scaleY(1)", 1f))),
            "browsLost" to MeduTrack(100f, 66f, listOf(f(0f, "translateY(0px)", 1f), f(0.11112f, "translateY(0px)", 1f, easing = "ease-in"), f(0.26668f, "translateY(5px)", 0f), f(1f, "translateY(5px)", 0f))),
            "mWobble" to MeduTrack(100f, 99f, listOf(f(0f, "scale(1,1)", 1f), f(0.15556f, "scale(1,1)", 1f, easing = "ease-in"), f(0.21332f, "scale(0,1)", 0f), f(1f, "scale(0,1)", 0f))),
            "mO" to MeduTrack(100f, 99f, listOf(f(0f, "scale(0,0)", 0f), f(0.17776f, "scale(0,0)", 0f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.31112f, "scale(2.3,2.8)", 1f, easing = "ease-in-out"), f(0.49776f, "scale(2,2.4)", 1f, easing = "ease-in-out"), f(0.6222f, "scale(1,1)", 1f), f(1f, "scale(1,1)", 1f))),
            "sweat" to MeduTrack(150f, 72f, listOf(f(0f, "translateY(0px)", 1f), f(0.04444f, "translateY(0px)", 1f, easing = "ease-in"), f(0.2222f, "translateY(14px)", 0f), f(1f, "translateY(14px)", 0f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)"), f(0.17776f, "scale(1,1)", easing = "ease-out"), f(0.33332f, "scale(.95,1.08)", easing = "ease-in-out"), f(0.53332f, "scale(1,1)", easing = "ease-in"), f(0.77776f, "scale(1.07,.93)", easing = "ease-out"), f(0.93332f, "scale(1,1)"), f(1f, "scale(1,1)"))),
            "tg" to MeduTrack(100f, 116f, listOf(f(0f, "scaleY(1)"), f(0.57776f, "scaleY(1)", easing = "ease-out"), f(0.73332f, "scaleY(.9)", easing = "ease-in-out"), f(0.88888f, "scaleY(1.04)", easing = "ease-in-out"), f(1f, "scaleY(1)"))),
            "q" to MeduTrack(164f, 22f, listOf(f(0f, "rotate(0deg) translateY(0px) scale(1)", 1f, easing = "ease-in-out"), f(0.1422f, "rotate(30deg) translateY(4px) scale(1)", 1f, easing = "ease-in"), f(0.28888f, "rotate(48deg) translateY(12px) scale(0)", 0f), f(1f, "rotate(48deg) translateY(12px) scale(0)", 0f))),
            "zs" to MeduTrack(170f, 30f, listOf(f(0f, "scale(.6)", 0f), f(0.75556f, "scale(.6)", 0f, easing = "ease-out"), f(0.91112f, "scale(1)", 1f), f(1f, "scale(1)", 1f))),
            "b" to MeduTrack(100f, 130f, listOf(f(0f, "translateY(0px)"), f(0.51112f, "translateY(0px)", easing = "cubic-bezier(.4,0,.6,1)"), f(0.57776f, "translateY(-3px)", easing = "cubic-bezier(.3,0,.2,1)"), f(0.8f, "translateY(13px)", easing = "ease-in-out"), f(0.93332f, "translateY(10px)"), f(1f, "translateY(10px)"))),
        ),
    ),
    // Sleepy → Lost: startled awake.
    "sleepy>search" to MeduBridge(
        1040,
        mapOf(
            "eyesLook" to MeduTrack(100f, 83f, listOf(f(0f, "scaleY(.08)", 0f), f(0.14418f, "scaleY(.08)", 0f), f(0.16348f, "scaleY(.08)", 1f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.29805f, "scaleY(1.15)", 1f, easing = "ease-in-out"), f(0.43271f, "scaleY(1)", 1f), f(0.86543f, "scaleY(1)", 1f, easing = "ease-in"), f(0.92306f, "scaleY(.1)", 1f, easing = "ease-out"), f(1f, "scaleY(1)", 1f))),
            "eyesClosed" to MeduTrack(100f, 86f, listOf(f(0f, "scaleY(1)", 1f), f(0.12497f, "scaleY(1)", 1f), f(0.16348f, "scaleY(.6)", 0f), f(1f, "scaleY(.6)", 0f))),
            "browsLost" to MeduTrack(100f, 66f, listOf(f(0f, "translateY(-5px)", 0f), f(0.31727f, "translateY(-5px)", 0f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.52886f, "translateY(0px)", 1f), f(1f, "translateY(0px)", 1f))),
            "mWobble" to MeduTrack(100f, 99f, listOf(f(0f, "scale(0,1)", 0f), f(0.2019f, "scale(0,1)", 0f, easing = "ease-out"), f(0.35578f, "scale(1.2,1)", 1f, easing = "ease-in-out"), f(0.48074f, "scale(1,1)", 1f), f(1f, "scale(1,1)", 1f))),
            "mO" to MeduTrack(100f, 99f, listOf(f(0f, "scale(1,1)", 1f), f(0.10576f, "scale(1,1)", 1f, easing = "ease-in"), f(0.2019f, "scale(0,0)", 0f), f(1f, "scale(0,0)", 0f))),
            "sweat" to MeduTrack(150f, 72f, listOf(f(0f, "translateY(-6px)", 0f), f(0.67304f, "translateY(-6px)", 0f, easing = "ease-out"), f(0.96158f, "translateY(0px)", 1f), f(1f, "translateY(0px)", 1f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)"), f(0.09615f, "scale(1,1)", easing = "ease-out"), f(0.25963f, "scale(.92,1.09)", easing = "ease-in-out"), f(0.57689f, "scale(1.05,.96)", easing = "ease-out"), f(0.81731f, "scale(1,1)"), f(1f, "scale(1,1)"))),
            "tg" to MeduTrack(100f, 116f, listOf(f(0f, "scaleY(1)"), f(0.09615f, "scaleY(1)", easing = "ease-out"), f(0.33656f, "scaleY(1.12)", easing = "ease-in-out"), f(0.67304f, "scaleY(.94)", easing = "ease-in-out"), f(0.91346f, "scaleY(1)"), f(1f, "scaleY(1)"))),
            "q" to MeduTrack(164f, 22f, listOf(f(0f, "rotate(-30deg) translateY(0px) scale(0)", 0f), f(0.37499f, "rotate(-30deg) translateY(0px) scale(0)", 0f, easing = "cubic-bezier(.2,.8,.3,1.3)"), f(0.6058f, "rotate(8deg) translateY(0px) scale(1.25)", 1f, easing = "ease-in-out"), f(0.7981f, "rotate(0deg) translateY(0px) scale(1)", 1f), f(1f, "rotate(0deg) translateY(0px) scale(1)", 1f))),
            "zs" to MeduTrack(170f, 30f, listOf(f(0f, "scale(1)", 1f, easing = "ease-in"), f(0.14418f, "scale(1.3)", 0f), f(1f, "scale(1.3)", 0f))),
            "b" to MeduTrack(100f, 130f, listOf(f(0f, "translateY(10px)"), f(0.09615f, "translateY(10px)", easing = "cubic-bezier(.15,.8,.3,1)"), f(0.35578f, "translateY(-12px)", easing = "cubic-bezier(.5,0,.5,1)"), f(0.76928f, "translateY(0px)"), f(1f, "translateY(0px)"))),
        ),
    ),
    // Thinking → Yay: the dots pop, a grin, a crouch and a jump, sparkles at the top, two little hops of joy.
    "think>yay" to MeduBridge(
        3600,
        mapOf(
            "eyesUp" to MeduTrack(100f, 80f, listOf(f(0f, "scaleY(1)", 1f), f(0.06945f, "scaleY(1)", 1f, easing = "ease-in"), f(0.1111f, "scaleY(.1)", 1f), f(0.11667f, "scaleY(.1)", 0f), f(1f, "scaleY(.1)", 0f))),
            "eyesHappy" to MeduTrack(100f, 86f, listOf(f(0f, "scale(.4)", 0f), f(0.1111f, "scale(.4)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.18056f, "scale(1.15)", 1f, easing = "ease-in-out"), f(0.22223f, "scale(1)", 1f), f(1f, "scale(1)", 1f))),
            "mFlat" to MeduTrack(100f, 98f, listOf(f(0f, "scaleX(1)", 1f), f(0.08334f, "scaleX(1)", 1f, easing = "ease-in"), f(0.11667f, "scaleX(0)", 0f), f(1f, "scaleX(0)", 0f))),
            "mGrin" to MeduTrack(100f, 100f, listOf(f(0f, "scale(0)", 0f), f(0.11667f, "scale(0)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.18888f, "scale(1.15)", 1f, easing = "ease-in-out"), f(0.22777f, "scale(1)", 1f), f(1f, "scale(1)", 1f))),
            "blush" to MeduTrack(100f, 97f, listOf(f(0f, opacity = 0.45f), f(0.13889f, opacity = 0.45f, easing = "ease-out"), f(0.19444f, opacity = 0.7f), f(1f, opacity = 0.7f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)"), f(0.04167f, "scale(1,1)", easing = "ease-out"), f(0.09722f, "scale(1.1,.88)", easing = "cubic-bezier(.2,.7,.3,1)"), f(0.13889f, "scale(.92,1.1)", easing = "ease-out"), f(0.18056f, "scale(1,1)", easing = "ease-in"), f(0.23611f, "scale(.96,1.05)", easing = "ease-out"), f(0.2639f, "scale(1.12,.86)", easing = "ease-out"), f(0.31111f, "scale(.98,1.03)", easing = "ease-in-out"), f(0.35556f, "scale(1.03,.97)", easing = "ease-in-out"), f(0.40277f, "scale(1,1)"), f(0.58333f, "scale(1,1)", easing = "ease-out"), f(0.61111f, "scale(1.06,.93)", easing = "ease-out"), f(0.64444f, "scale(.97,1.04)", easing = "ease-in"), f(0.70556f, "scale(1.06,.93)", easing = "ease-out"), f(0.75f, "scale(1,1)"), f(0.83332f, "scale(1,1)", easing = "ease-out"), f(0.86111f, "scale(1.05,.95)", easing = "ease-out"), f(0.88888f, "scale(.98,1.03)", easing = "ease-in"), f(0.94445f, "scale(1.05,.95)", easing = "ease-out"), f(0.9861f, "scale(1,1)"), f(1f, "scale(1,1)"))),
            "tg" to MeduTrack(100f, 116f, listOf(f(0f, "scaleY(1)"), f(0.04167f, "scaleY(1)", easing = "ease-out"), f(0.10556f, "scaleY(.92)", easing = "ease-out"), f(0.17222f, "scaleY(1.14)", easing = "ease-in-out"), f(0.22777f, "scaleY(.98)", easing = "ease-in"), f(0.27778f, "scaleY(.88)", easing = "ease-out"), f(0.33333f, "scaleY(1.05)", easing = "ease-in-out"), f(0.38889f, "scaleY(1)"), f(0.61111f, "scaleY(1)", easing = "ease-out"), f(0.65554f, "scaleY(1.06)", easing = "ease-in-out"), f(0.72222f, "scaleY(.94)", easing = "ease-in-out"), f(0.76389f, "scaleY(1)"), f(0.86111f, "scaleY(1)", easing = "ease-out"), f(0.90278f, "scaleY(1.05)", easing = "ease-in-out"), f(0.95833f, "scaleY(.95)", easing = "ease-in-out"), f(1f, "scaleY(1)"))),
            "spark0" to MeduTrack(0f, 0f, listOf(f(0f, "scale(0) rotate(-40deg)", 0f), f(0.16668f, "scale(0) rotate(-40deg)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.23611f, "scale(1.3) rotate(10deg)", 1f, easing = "ease-in-out"), f(0.29166f, "scale(1) rotate(0deg)", 1f), f(1f, "scale(1) rotate(0deg)", 1f)), centre = true),
            "spark1" to MeduTrack(0f, 0f, listOf(f(0f, "scale(0) rotate(-40deg)", 0f), f(0.18056f, "scale(0) rotate(-40deg)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.25f, "scale(1.3) rotate(10deg)", 1f, easing = "ease-in-out"), f(0.30555f, "scale(1) rotate(0deg)", 1f), f(1f, "scale(1) rotate(0deg)", 1f)), centre = true),
            "spark2" to MeduTrack(0f, 0f, listOf(f(0f, "scale(0) rotate(-40deg)", 0f), f(0.19444f, "scale(0) rotate(-40deg)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.2639f, "scale(1.3) rotate(10deg)", 1f, easing = "ease-in-out"), f(0.31945f, "scale(1) rotate(0deg)", 1f), f(1f, "scale(1) rotate(0deg)", 1f)), centre = true),
            "spark3" to MeduTrack(0f, 0f, listOf(f(0f, "scale(0) rotate(-40deg)", 0f), f(0.20833f, "scale(0) rotate(-40deg)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.27778f, "scale(1.3) rotate(10deg)", 1f, easing = "ease-in-out"), f(0.33333f, "scale(1) rotate(0deg)", 1f), f(1f, "scale(1) rotate(0deg)", 1f)), centre = true),
            "dots" to MeduTrack(100f, 0f, listOf(f(0f, "translateY(0px) scale(1)", 1f, easing = "ease-in"), f(0.06945f, "translateY(6px) scale(.55)", 1f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.12501f, "translateY(-6px) scale(1.7)", 0f), f(1f, "translateY(-6px) scale(1.7)", 0f))),
            "b" to MeduTrack(100f, 130f, listOf(f(0f, "translateY(0px)"), f(0.04167f, "translateY(0px)", easing = "ease-in-out"), f(0.09722f, "translateY(7px)", easing = "cubic-bezier(.2,.7,.3,1)"), f(0.18056f, "translateY(-34px)", easing = "cubic-bezier(.6,0,.85,.45)"), f(0.2639f, "translateY(0px)", easing = "cubic-bezier(.2,.7,.4,1)"), f(0.31111f, "translateY(-7px)", easing = "cubic-bezier(.5,0,.5,1)"), f(0.35556f, "translateY(0px)"), f(0.58333f, "translateY(0px)", easing = "cubic-bezier(.2,.7,.3,1)"), f(0.64444f, "translateY(-14px)", easing = "cubic-bezier(.6,0,.8,.5)"), f(0.70556f, "translateY(0px)"), f(0.83332f, "translateY(0px)", easing = "cubic-bezier(.2,.7,.3,1)"), f(0.88888f, "translateY(-10px)", easing = "cubic-bezier(.6,0,.8,.5)"), f(0.94445f, "translateY(0px)"), f(1f, "translateY(0px)"))),
        ),
    ),
    // Yay → Thinking: settles back.
    "yay>think" to MeduBridge(
        600,
        mapOf(
            "eyesUp" to MeduTrack(100f, 80f, listOf(f(0f, "scaleY(.1)", 0f), f(0.25003f, "scaleY(.1)", 0f), f(0.28328f, "scaleY(.1)", 1f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.53331f, "scaleY(1.1)", 1f, easing = "ease-in-out"), f(0.74997f, "scaleY(1)", 1f), f(1f, "scaleY(1)", 1f))),
            "eyesHappy" to MeduTrack(100f, 86f, listOf(f(0f, "scale(1)", 1f), f(0.0833f, "scale(1)", 1f, easing = "ease-in"), f(0.28328f, "scale(.4)", 0f), f(1f, "scale(.4)", 0f))),
            "mFlat" to MeduTrack(100f, 98f, listOf(f(0f, "scaleX(0)", 0f), f(0.33333f, "scaleX(0)", 0f, easing = "ease-out"), f(0.58336f, "scaleX(1)", 1f), f(1f, "scaleX(1)", 1f))),
            "mGrin" to MeduTrack(100f, 100f, listOf(f(0f, "scale(1)", 1f), f(0.03337f, "scale(1)", 1f, easing = "ease-in"), f(0.28328f, "scale(0)", 0f), f(1f, "scale(0)", 0f))),
            "blush" to MeduTrack(100f, 97f, listOf(f(0f, opacity = 0.7f), f(0.16661f, opacity = 0.7f, easing = "ease-in"), f(0.50006f, opacity = 0.45f), f(1f, opacity = 0.45f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)"), f(0.0833f, "scale(1,1)", easing = "ease-in-out"), f(0.50006f, "scale(1.04,.96)", easing = "ease-in-out"), f(1f, "scale(1,1)"))),
            "spark0" to MeduTrack(0f, 0f, listOf(f(0f, "scale(1) rotate(0deg)", 1f, easing = "ease-in"), f(0.33333f, "scale(0) rotate(30deg)", 0f), f(1f, "scale(0) rotate(30deg)", 0f)), centre = true),
            "spark1" to MeduTrack(0f, 0f, listOf(f(0f, "scale(1) rotate(0deg)", 1f, easing = "ease-in"), f(0.33333f, "scale(0) rotate(30deg)", 0f), f(1f, "scale(0) rotate(30deg)", 0f)), centre = true),
            "spark2" to MeduTrack(0f, 0f, listOf(f(0f, "scale(1) rotate(0deg)", 1f, easing = "ease-in"), f(0.33333f, "scale(0) rotate(30deg)", 0f), f(1f, "scale(0) rotate(30deg)", 0f)), centre = true),
            "spark3" to MeduTrack(0f, 0f, listOf(f(0f, "scale(1) rotate(0deg)", 1f, easing = "ease-in"), f(0.33333f, "scale(0) rotate(30deg)", 0f), f(1f, "scale(0) rotate(30deg)", 0f)), centre = true),
            "dots" to MeduTrack(100f, 0f, listOf(f(0f, "translateY(4px) scale(.4)", 0f), f(0.41664f, "translateY(4px) scale(.4)", 0f, easing = "cubic-bezier(.2,.8,.3,1.3)"), f(0.83339f, "translateY(0px) scale(1)", 1f), f(1f, "translateY(0px) scale(1)", 1f))),
            "b" to MeduTrack(100f, 130f, listOf(f(0f, "translateY(0px)"), f(0.0833f, "translateY(0px)", easing = "ease-in-out"), f(0.50006f, "translateY(4px)", easing = "ease-in-out"), f(1f, "translateY(0px)"))),
        ),
    ),
    // Oops → Hello: a startled jump, a wobble that flings the cable away, a smile and a wave.
    "oops>wave" to MeduBridge(
        1050,
        mapOf(
            "eyes" to MeduTrack(100f, 83f, listOf(f(0f, "scaleY(1)", 1f, easing = "ease-out"), f(0.14286f, "scaleY(1.15)", 1f, easing = "ease-in-out"), f(0.42857f, "scaleY(1)", 1f), f(0.85714f, "scaleY(1)", 1f, easing = "ease-in"), f(0.92381f, "scaleY(.1)", 1f, easing = "ease-out"), f(1f, "scaleY(1)", 1f))),
            "browsOops" to MeduTrack(100f, 70f, listOf(f(0f, "translateY(0px)", 1f, easing = "ease-out"), f(0.14286f, "translateY(-4px)", 1f, easing = "ease-in"), f(0.57143f, "translateY(2px)", 0f), f(1f, "translateY(2px)", 0f))),
            "mFrown" to MeduTrack(100f, 98f, listOf(f(0f, "scaleY(1)", 1f), f(0.47619f, "scaleY(1)", 1f, easing = "ease-in"), f(0.57143f, "scaleY(0)", 0f), f(1f, "scaleY(0)", 0f))),
            "mSmile" to MeduTrack(100f, 98f, listOf(f(0f, "scale(0,0)", 0f), f(0.54286f, "scale(0,0)", 0f, easing = "cubic-bezier(.2,.8,.3,1.4)"), f(0.73333f, "scale(1.15,1.15)", 1f, easing = "ease-in-out"), f(0.85714f, "scale(1,1)", 1f), f(1f, "scale(1,1)", 1f))),
            "blush" to MeduTrack(100f, 97f, listOf(f(0f, opacity = 0.4f), f(0.61905f, opacity = 0.4f, easing = "ease-out"), f(0.90476f, opacity = 0.55f), f(1f, opacity = 0.55f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)", easing = "ease-out"), f(0.14286f, "scale(.95,1.06)", easing = "ease-in-out"), f(0.7619f, "scale(1,1)"), f(1f, "scale(1,1)"))),
            "plug" to MeduTrack(170f, 200f, listOf(f(0f, "translate(0px,0px) rotate(0deg)", 1f), f(0.28571f, "translate(0px,0px) rotate(0deg)", 1f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.42857f, "translate(26px,-14px) rotate(50deg)", 1f, easing = "cubic-bezier(.5,0,.9,.6)"), f(0.95238f, "translate(48px,70px) rotate(150deg)", 0f), f(1f, "translate(48px,70px) rotate(150deg)", 0f))),
            "tg" to MeduTrack(100f, 116f, listOf(f(0f, "rotate(0deg)"), f(0.2f, "rotate(0deg)", easing = "ease-in-out"), f(0.31429f, "rotate(5deg)", easing = "ease-in-out"), f(0.42857f, "rotate(-5deg)", easing = "ease-in-out"), f(0.54286f, "rotate(3deg)", easing = "ease-in-out"), f(0.67619f, "rotate(-2deg)", easing = "ease-in-out"), f(0.80952f, "rotate(0deg)"), f(1f, "rotate(0deg)"))),
            "arm" to MeduTrack(163f, 96f, listOf(f(0f, "rotate(70deg) scale(.6)", 0f), f(0.61905f, "rotate(70deg) scale(.6)", 0f, easing = "cubic-bezier(.2,.8,.3,1.3)"), f(0.90476f, "rotate(-6deg) scale(1)", 1f), f(1f, "rotate(-6deg) scale(1)", 1f))),
            "b" to MeduTrack(100f, 124f, listOf(f(0f, "translateY(0px) rotate(0deg)", easing = "cubic-bezier(.2,.8,.3,1)"), f(0.14286f, "translateY(-6px) rotate(0deg)", easing = "ease-in-out"), f(0.25714f, "translateY(-4px) rotate(-9deg)", easing = "ease-in-out"), f(0.37143f, "translateY(-3px) rotate(8deg)", easing = "ease-in-out"), f(0.48571f, "translateY(-2px) rotate(-5deg)", easing = "ease-in-out"), f(0.6f, "translateY(-1px) rotate(3deg)", easing = "ease-in-out"), f(0.73333f, "translateY(0px) rotate(0deg)"), f(1f, "translateY(0px) rotate(0deg)"))),
        ),
    ),
    // Hello → Oops: the arm tucks away, the plug swings back.
    "wave>oops" to MeduBridge(
        950,
        mapOf(
            "browsOops" to MeduTrack(100f, 70f, listOf(f(0f, "translateY(2px)", 0f), f(0.52632f, "translateY(2px)", 0f, easing = "ease-out"), f(0.78947f, "translateY(0px)", 1f), f(1f, "translateY(0px)", 1f))),
            "mFrown" to MeduTrack(100f, 98f, listOf(f(0f, "scaleY(0)", 0f), f(0.47368f, "scaleY(0)", 0f, easing = "ease-out"), f(0.63158f, "scaleY(1)", 1f), f(1f, "scaleY(1)", 1f))),
            "mSmile" to MeduTrack(100f, 98f, listOf(f(0f, "scale(1,1)", 1f), f(0.36842f, "scale(1,1)", 1f, easing = "ease-in"), f(0.50526f, "scale(0,0)", 0f), f(1f, "scale(0,0)", 0f))),
            "blush" to MeduTrack(100f, 97f, listOf(f(0f, opacity = 0.55f), f(0.31579f, opacity = 0.55f, easing = "ease-in"), f(0.63158f, opacity = 0.4f), f(1f, opacity = 0.4f))),
            "plug" to MeduTrack(170f, 200f, listOf(f(0f, "translate(40px,40px) rotate(-30deg)", 0f), f(0.63158f, "translate(40px,40px) rotate(-30deg)", 0f, easing = "cubic-bezier(.2,.8,.3,1.2)"), f(1f, "translate(0px,0px) rotate(0deg)", 1f))),
            "arm" to MeduTrack(163f, 96f, listOf(f(0f, "rotate(-6deg) scale(1)", 1f, easing = "ease-in"), f(0.36842f, "rotate(70deg) scale(.6)", 0f), f(1f, "rotate(70deg) scale(.6)", 0f))),
            "b" to MeduTrack(100f, 124f, listOf(f(0f, "translateY(0px) rotate(0deg)"), f(0.10526f, "translateY(0px) rotate(0deg)", easing = "ease-in-out"), f(0.52632f, "translateY(5px) rotate(-3deg)", easing = "ease-in-out"), f(0.94737f, "translateY(0px) rotate(0deg)"), f(1f, "translateY(0px) rotate(0deg)"))),
        ),
    ),
    // Sleepy → Popcorn: the z’s pop, a stretch and a yawn, the eyes snap open, the bucket bounces in as Medu lands.
    "sleepy>popcorn" to MeduBridge(
        1150,
        mapOf(
            "eyesClosed" to MeduTrack(100f, 86f, listOf(f(0f, "scaleY(1)", 1f), f(0.37387f, "scaleY(1)", 1f), f(0.4087f, "scaleY(.6)", 0f), f(1f, "scaleY(.6)", 0f))),
            "eyesUp" to MeduTrack(100f, 80f, listOf(f(0f, "scaleY(.08)", 0f), f(0.39133f, "scaleY(.08)", 0f), f(0.4087f, "scaleY(.08)", 1f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.54786f, "scaleY(1.15)", 1f, easing = "ease-in-out"), f(0.66956f, "scaleY(1)", 1f), f(1f, "scaleY(1)", 1f))),
            "mO" to MeduTrack(100f, 99f, listOf(f(0f, "scale(1,1)", 1f), f(0.08695f, "scale(1,1)", 1f, easing = "cubic-bezier(.2,.8,.3,1)"), f(0.3043f, "scale(2.3,2.8)", 1f, easing = "ease-in-out"), f(0.49566f, "scale(1.8,2)", 1f, easing = "ease-in"), f(0.58261f, "scale(0,0)", 0f), f(1f, "scale(0,0)", 0f))),
            "mChew" to MeduTrack(100f, 99f, listOf(f(0f, "scale(0)", 0f), f(0.58261f, "scale(0)", 0f, easing = "ease-out"), f(0.69562f, "scale(1)", 1f), f(1f, "scale(1)", 1f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)", easing = "ease-out"), f(0.13039f, "scale(1.06,.94)", easing = "ease-out"), f(0.32175f, "scale(.9,1.12)", easing = "ease-in-out"), f(0.65219f, "scale(1.07,.93)", easing = "ease-out"), f(0.82609f, "scale(1,1)"), f(1f, "scale(1,1)"))),
            "tg" to MeduTrack(100f, 116f, listOf(f(0f, "scaleY(1)"), f(0.02606f, "scaleY(1)", easing = "ease-out"), f(0.14777f, "scaleY(.93)", easing = "ease-out"), f(0.37387f, "scaleY(1.14)", easing = "ease-in-out"), f(0.65219f, "scaleY(.9)", easing = "ease-in-out"), f(0.86961f, "scaleY(1.03)", easing = "ease-in-out"), f(1f, "scaleY(1)"))),
            "popcorn" to MeduTrack(100f, 176f, listOf(f(0f, "translateY(46px) scale(.6)", 0f), f(0.47828f, "translateY(46px) scale(.6)", 0f), f(0.53041f, "translateY(38px) scale(.68)", 1f, easing = "cubic-bezier(.2,.8,.3,1.25)"), f(0.73914f, "translateY(0px) scale(1)", 1f), f(1f, "translateY(0px) scale(1)", 1f))),
            "zs" to MeduTrack(170f, 30f, listOf(f(0f, "scale(1)", 1f, easing = "ease-in"), f(0.14777f, "scale(1.4)", 0f), f(1f, "scale(1.4)", 0f))),
            "b" to MeduTrack(100f, 130f, listOf(f(0f, "translateY(10px)", easing = "cubic-bezier(.4,0,.6,1)"), f(0.13039f, "translateY(13px)", easing = "cubic-bezier(.2,.75,.3,1)"), f(0.39133f, "translateY(-16px)", easing = "cubic-bezier(.5,0,.5,1)"), f(0.65219f, "translateY(0px)", easing = "ease-out"), f(0.75652f, "translateY(2px)", easing = "ease-in-out"), f(0.86961f, "translateY(0px)"), f(1f, "translateY(0px)"))),
        ),
    ),
    // Popcorn → Sleepy: dozes off again.
    "popcorn>sleepy" to MeduBridge(
        1400,
        mapOf(
            "eyesClosed" to MeduTrack(100f, 86f, listOf(f(0f, "scaleY(.6)", 0f), f(0.47856f, "scaleY(.6)", 0f, easing = "ease-out"), f(0.54998f, "scaleY(1)", 1f), f(1f, "scaleY(1)", 1f))),
            "eyesUp" to MeduTrack(100f, 80f, listOf(f(0f, "scaleY(1)", 1f), f(0.17859f, "scaleY(1)", 1f, easing = "ease-in-out"), f(0.32144f, "scaleY(.5)", 1f), f(0.39286f, "scaleY(.6)", 1f, easing = "ease-in"), f(0.52144f, "scaleY(.08)", 1f), f(0.53571f, "scaleY(.08)", 0f), f(1f, "scaleY(.08)", 0f))),
            "mO" to MeduTrack(100f, 99f, listOf(f(0f, "scale(0,0)", 0f), f(0.46429f, "scale(0,0)", 0f, easing = "ease-out"), f(0.60714f, "scale(1,1)", 1f), f(1f, "scale(1,1)", 1f))),
            "mChew" to MeduTrack(100f, 99f, listOf(f(0f, "scale(1)", 1f), f(0.1071f, "scale(1)", 1f, easing = "ease-in"), f(0.21427f, "scale(0)", 0f), f(1f, "scale(0)", 0f))),
            "bell" to MeduTrack(100f, 124f, listOf(f(0f, "scale(1,1)"), f(0.39286f, "scale(1,1)", easing = "ease-out"), f(0.7143f, "scale(1.06,.94)", easing = "ease-out"), f(0.92858f, "scale(1,1)"), f(1f, "scale(1,1)"))),
            "tg" to MeduTrack(100f, 116f, listOf(f(0f, "scaleY(1)"), f(0.39286f, "scaleY(1)", easing = "ease-out"), f(0.67856f, "scaleY(.9)", easing = "ease-in-out"), f(0.8929f, "scaleY(1.03)"), f(1f, "scaleY(1)"))),
            "popcorn" to MeduTrack(100f, 176f, listOf(f(0f, "translateY(0px) scale(1)", 1f, easing = "ease-in-out"), f(0.07142f, "translateY(-5px) scale(1.03)", 1f, easing = "cubic-bezier(.5,0,.9,.5)"), f(0.20714f, "translateY(44px) scale(.78)", 1f), f(0.25002f, "translateY(52px) scale(.7)", 0f), f(1f, "translateY(52px) scale(.7)", 0f))),
            "zs" to MeduTrack(170f, 30f, listOf(f(0f, "scale(.6)", 0f), f(0.67856f, "scale(.6)", 0f, easing = "ease-out"), f(0.92858f, "scale(1)", 1f), f(1f, "scale(1)", 1f))),
            "b" to MeduTrack(100f, 130f, listOf(f(0f, "translateY(0px)"), f(0.03568f, "translateY(0px)", easing = "ease-in-out"), f(0.39286f, "translateY(-3px)", easing = "cubic-bezier(.3,0,.2,1)"), f(0.74998f, "translateY(12px)", easing = "ease-in-out"), f(0.92858f, "translateY(10px)"), f(1f, "translateY(10px)"))),
        ),
    ),
)
