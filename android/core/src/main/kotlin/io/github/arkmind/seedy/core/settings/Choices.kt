package io.github.arkmind.seedy.core.settings

import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.data.SubtitleStyle
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.SubtitlePrefs
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/** One choice of a settings row: the value saved and its label (translated). */
data class Choice<T>(val value: T, val label: String)

/*
 * The choices behind Settings · Playback and Subtitles, with the web's values (web/src/player/settings.ts) so a profile
 * reads the same on every device. Labels are built when asked for, in the language shown.
 */
object Choices {
    /** The bitrate "Auto" stands for. */
    const val AutoBitrate = 120_000_000

    /** Quality pickers (bits per second), as on the web. */
    val Bitrates = listOf(80.0, 60.0, 40.0, 20.0, 15.0, 10.0, 8.0, 6.0, 4.0, 3.0, 2.0, 1.5, 1.0, 0.72, 0.42).map { (it * 1_000_000).roundToInt() }

    /** "120 Mbps", "1.5 Mbps", "720 kbps". */
    fun bitrate(bps: Int): String {
        if (bps >= 1_000_000) {
            val m = BigDecimal(bps / 1_000_000.0).setScale(1, RoundingMode.HALF_UP).stripTrailingZeros()
            return "${formatNumber(m)} Mbps"
        }
        return "${formatNumber((bps / 1000.0).roundToInt())} kbps"
    }

    /** The picture a bitrate gets when the server converts. */
    fun pictureFor(bps: Int): String = when {
        bps >= 40_000_000 -> "4K"
        bps >= 10_000_000 -> "1080p"
        bps >= 4_000_000 -> "720p"
        bps >= 1_500_000 -> "480p"
        else -> "360p"
    }

    /** A quality value: Auto's label, "20 Mbps", or "4 Mbps · 720p" with [picture]. */
    fun quality(bps: Int, auto: String, picture: Boolean = true): String = when {
        bps <= 0 -> auto
        picture -> "${bitrate(bps)} · ${pictureFor(bps)}"
        else -> bitrate(bps)
    }

    fun autoUpTo(bps: Int = AutoBitrate): String = t("Auto · up to {rate}", "rate" to bitrate(bps))

    fun qualities(auto: String, picture: Boolean = true): List<Choice<Int>> =
        listOf(Choice(0, auto)) + Bitrates.map { Choice(it, quality(it, auto, picture)) }

    /** Largest picture when converting: 0 = this screen's size, -1 = no limit. */
    fun resolutions(): List<Choice<Int>> = listOf(
        Choice(0, t("Screen size")), Choice(3840, "4K"), Choice(2560, "1440p"), Choice(1920, "1080p"),
        Choice(1280, "720p"), Choice(854, "480p"), Choice(-1, t("No limit")),
    )

    /** Most audio channels (phone) or Surround sound (TV, where Auto passes Dolby and DTS through). */
    fun channels(tv: Boolean): List<Choice<Int>> = listOf(
        Choice(0, if (tv) t("Passthrough") else t("Auto")), Choice(8, t("7.1 surround")), Choice(6, t("5.1 surround")),
        Choice(2, t("Stereo")), Choice(1, t("Mono")),
    )

    fun skips(): List<Choice<String>> = listOf(Choice("auto", t("Skip")), Choice("button", t("Show button")), Choice("off", t("Nothing")))

    fun seconds(n: Int): String = "${formatNumber(n)} s"

    val UpNextSeconds = listOf(5, 10, 15, 20, 30, 45, 60)
    val SeekBack = listOf(5, 10, 15, 20, 30)
    val SeekForward = listOf(10, 15, 30, 45, 60, 90)

    /** TV: one row for both directions. */
    val SeekPairs = listOf(5 to 10, 10 to 10, 10 to 30, 15 to 30, 15 to 60, 30 to 30, 30 to 60)

    /** "Are you still watching?": episodes and minutes (the web's prefs.custom playback.stillWatching), or "off". */
    val StillWatching = listOf("off", "2:60", "3:90", "4:120", "6:180")

    fun stillWatching(value: String, short: Boolean): String {
        if (value == "off") return t("Never")
        val (e, m) = value.split(':').map { it.toIntOrNull() ?: 0 }
        if (short) return t("After {n} episodes", "n" to e)
        return when (m) {
            60 -> t("After 2 episodes or 60 min")
            90 -> if (e == 3) t("After 3 episodes or 90 min") else t("After {episodes} episodes or {minutes} min", "episodes" to e, "minutes" to m)
            120 -> t("After 4 episodes or 2 hours")
            180 -> t("After 6 episodes or 3 hours")
            else -> t("After {episodes} episodes or {minutes} min", "episodes" to e, "minutes" to m)
        }
    }

    /** Jellyfin's subtitle modes, in the boards' order and words. */
    data class Mode(val value: String, val name: String, val help: String)

    fun modes(): List<Mode> = listOf(
        Mode("Smart", t("Smart"), t("In your language when the audio is in another one")),
        Mode("Always", t("Always"), t("In your language, whatever the audio")),
        Mode("Default", t("As the file says"), t("Follow the default and forced flags in the file")),
        Mode("OnlyForced", t("Forced only"), t("Just signs and foreign-language lines")),
        Mode("None", t("Never"), t("Turn them on yourself during playback")),
    )

    fun sizes(): List<Choice<String>> = listOf(Choice("small", t("Small")), Choice("normal", t("Normal")), Choice("large", t("Large")), Choice("xlarge", t("Extra large")))
    fun weights(): List<Choice<String>> = listOf(Choice("normal", t("Normal")), Choice("bold", t("Bold")))
    fun fonts(): List<Choice<String>> = listOf(
        Choice("seedy", t("Seedy (Noto Sans)")), Choice("system", t("System")), Choice("print", t("Print (serif)")),
        Choice("typewriter", t("Typewriter")), Choice("console", t("Console")), Choice("smallcaps", t("Small capitals")),
    )

    /** Text colours with their RGB. */
    fun colors(): List<Pair<Choice<String>, Long>> = listOf(
        Choice("white", t("White")) to 0xFFFFFFFF, Choice("grey", t("Light grey")) to 0xFFD0D0D0, Choice("yellow", t("Yellow")) to 0xFFFFE14D,
        Choice("cyan", t("Cyan")) to 0xFF7FE7FF, Choice("green", t("Green")) to 0xFF8FF59A, Choice("pink", t("Pink")) to 0xFFFF9FFF,
    )

    /** Backgrounds with their ARGB (none is transparent). */
    fun backgrounds(): List<Pair<Choice<String>, Long>> = listOf(
        Choice("none", t("None")) to 0x00000000, Choice("dim", t("Dark, see-through")) to 0x80000000, Choice("black", t("Black")) to 0xFF000000,
    )

    fun outlines(): List<Choice<String>> = listOf(Choice("none", t("None")), Choice("shadow", t("Shadow")), Choice("outline", t("Outline")), Choice("raised", t("Raised")))
    fun positions(): List<Choice<String>> = listOf(Choice("bottom", t("Bottom")), Choice("lifted", t("Above controls")), Choice("top", t("Top")))

    fun burnIns(): List<Choice<String>> = listOf(
        Choice("auto", t("Auto")), Choice("picture", t("Picture subtitles")), Choice("styled", t("Styled and picture")), Choice("all", t("All subtitles")),
    )

    fun <T> label(list: List<Choice<T>>, value: T): String = list.firstOrNull { it.value == value }?.label ?: value.toString()

    /** The next or previous choice (TV rows change with left and right; they stop at the ends). */
    fun <T> step(list: List<T>, current: T, by: Int): T {
        val i = list.indexOf(current)
        if (i < 0) return list.first()
        return list[(i + by).coerceIn(0, list.lastIndex)]
    }
}

/** A subtitle look with every field set: this device's choices, else the profile's size and lift, else the defaults. */
data class ResolvedStyle(
    val size: String,
    val weight: String,
    val font: String,
    val color: String,
    val background: String,
    val outline: String,
    val position: String,
) {
    companion object {
        private val SizeFromPrefs = mapOf("S" to "small", "M" to "normal", "L" to "large", "XL" to "xlarge")

        /** The web keeps S, M and L in the profile; Extra large is saved as L there. */
        val SizeToPrefs = mapOf("small" to "S", "normal" to "M", "large" to "L", "xlarge" to "L")

        fun of(device: SubtitleStyle, prefs: SubtitlePrefs?): ResolvedStyle {
            val p = prefs ?: SubtitlePrefs()
            return ResolvedStyle(
                size = device.size ?: SizeFromPrefs[p.size] ?: "normal",
                weight = device.weight ?: "normal",
                font = device.font ?: "seedy",
                color = device.color ?: "white",
                background = device.background ?: "none",
                outline = device.outline ?: "shadow",
                position = device.position ?: if (p.lift) "lifted" else "bottom",
            )
        }

        fun of(device: DeviceSettings, prefs: SubtitlePrefs?): ResolvedStyle = of(device.subtitleStyle, prefs)
    }

    fun colorArgb(): Long = Choices.colors().firstOrNull { it.first.value == color }?.second ?: 0xFFFFFFFF
    fun backgroundArgb(): Long = Choices.backgrounds().firstOrNull { it.first.value == background }?.second ?: 0
}
