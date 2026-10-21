package io.github.arkmind.seedy.core.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Settings the boards mark "This phone" / "This TV": they stay on this device, whoever is signed in (the web keeps the
 * same ones in the browser as `seedy.device.*`, web/src/player/settings.ts).
 */
@Serializable
data class DeviceSettings(
    /** Bits per second; 0 = Auto. On a TV, Quality sets home and away together. */
    val qualityHome: Int = 0,
    val qualityAway: Int = 0,
    val qualityMobile: Int = 4_000_000,
    val qualityCast: Int = 20_000_000,
    val askMobileData: Boolean = true,
    /** Largest picture when the server converts: 0 = this screen's size, -1 = no limit, else a width in pixels. */
    val maxResolution: Int = 0,
    /** 0 = Auto (whatever the output takes, passthrough included), else 8, 6, 2 or 1. */
    val audioChannels: Int = 0,
    val matchFrameRate: Boolean = true,
    val matchDynamicRange: Boolean = true,
    /** Seconds; null = the profile's (prefs.playback). */
    val seekBack: Int? = null,
    val seekForward: Int? = null,
    val pictureInPicture: Boolean = true,
    val backgroundAudio: Boolean = false,
    /** Settings · Profile › "Tell me when a request is ready". */
    val notifyRequests: Boolean = true,
    /** TV: the in-progress titles in Google TV's Continue watching row. */
    val googleTvContinue: Boolean = true,
    /** TV · Remote: Back leaves the player instead of only hiding the controls. */
    val backClosesPlayer: Boolean = false,
    /** Phone · Gestures. */
    val doubleTapSeek: Boolean = true,
    val swipeControls: Boolean = true,
    /** How subtitles look here; unset fields follow the profile (prefs.subtitles) or the defaults. */
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    val systemCaptionStyle: Boolean = false,
    /** "auto", "picture", "styled" or "all". */
    val burnIn: String = "auto",
    /** Draw PGS and VobSub here instead of having the server burn them in. */
    val pictureSubtitles: Boolean = true,
    /* Settings · Playback › Advanced: they shape the device profile sent to the server. */
    val preferFmp4: Boolean = false,
    val hevc: Boolean = true,
    val av1: Boolean = true,
    val hdr: Boolean = true,
    val dts: Boolean = true,
    val truehd: Boolean = true,
    val mkv: Boolean = true,
)

/**
 * Subtitle look: size "small" | "normal" | "large" | "xlarge", weight "normal" | "bold", font "seedy" | "system" |
 * "print" | "typewriter" | "console" | "smallcaps", color "white" | "grey" | "yellow" | "cyan" | "green" | "pink",
 * background "none" | "dim" | "black", outline "none" | "shadow" | "outline" | "raised", position "bottom" |
 * "lifted" | "top" (the web's values).
 */
@Serializable
data class SubtitleStyle(
    val size: String? = null,
    val weight: String? = null,
    val font: String? = null,
    val color: String? = null,
    val background: String? = null,
    val outline: String? = null,
    val position: String? = null,
)

/** This device's settings, read once and kept in memory; the player reads them when it starts. */
class DevicePrefs private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("seedy.device", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val _flow = MutableStateFlow(read())

    val flow: StateFlow<DeviceSettings> = _flow.asStateFlow()
    val current: DeviceSettings get() = _flow.value

    fun update(transform: (DeviceSettings) -> DeviceSettings) {
        synchronized(this) {
            val next = transform(_flow.value)
            _flow.value = next
            prefs.edit().putString(KEY, json.encodeToString(DeviceSettings.serializer(), next)).apply()
        }
    }

    private fun read(): DeviceSettings =
        prefs.getString(KEY, null)?.let { runCatching { json.decodeFromString(DeviceSettings.serializer(), it) }.getOrNull() } ?: DeviceSettings()

    companion object {
        private const val KEY = "settings"

        @Volatile private var instance: DevicePrefs? = null

        fun get(context: Context): DevicePrefs = instance ?: synchronized(this) {
            instance ?: DevicePrefs(context.applicationContext).also { instance = it }
        }
    }
}
