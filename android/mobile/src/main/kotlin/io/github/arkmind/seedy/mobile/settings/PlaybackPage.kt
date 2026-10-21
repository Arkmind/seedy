package io.github.arkmind.seedy.mobile.settings

import androidx.compose.runtime.Composable
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.Choices
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.core.data.DeviceSettings

/** Board "Android · Settings · Playback". Quality is per device; Jellyfin's own options go to its UserConfiguration. */
@Composable
fun PlaybackPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav) {
    val sheet = rememberSheet()
    val d = state.device
    val pb = state.playback
    fun device(transform: (DeviceSettings) -> DeviceSettings) = actions.setDevice(transform)
    SettingsPage(t("Playback"), t("Quality, audio, what happens between episodes, and skipping. Quality is set per device."), nav.back, overlay = { SheetHost(sheet) }) {
        Section(t("Quality"), t("Auto picks the best your connection and screen allow.")) {
            row {
                val auto = Choices.autoUpTo()
                PickRow(t("On Wi-Fi at home"), t("Same network as the server"), Choices.quality(d.qualityHome, auto), device = true) {
                    sheet.open(t("On Wi-Fi at home"), Choices.qualities(auto), d.qualityHome) { v -> device { it.copy(qualityHome = v) } }
                }
            }
            row {
                val auto = if (state.remoteLimit > 0) Choices.autoUpTo(state.remoteLimit) else t("Auto")
                PickRow(t("Away from home"), t("Your admin may set a lower limit"), Choices.quality(d.qualityAway, auto), device = true) {
                    sheet.open(t("Away from home"), Choices.qualities(auto), d.qualityAway) { v -> device { it.copy(qualityAway = v) } }
                }
            }
            row {
                val auto = t("Auto")
                PickRow(t("On mobile data"), t("Used when you are not on Wi-Fi"), Choices.quality(d.qualityMobile, auto), device = true) {
                    sheet.open(t("On mobile data"), Choices.qualities(auto), d.qualityMobile) { v -> device { it.copy(qualityMobile = v) } }
                }
            }
            row {
                val auto = t("Auto")
                PickRow(t("When casting"), t("Chromecast and Google TV"), Choices.quality(d.qualityCast, auto, picture = false), device = true) {
                    sheet.open(t("When casting"), Choices.qualities(auto, picture = false), d.qualityCast) { v -> device { it.copy(qualityCast = v) } }
                }
            }
            row {
                SwitchRow(t("Ask before using mobile data"), t("Before a video starts away from Wi-Fi"), d.askMobileData, device = true) { v -> device { it.copy(askMobileData = v) } }
            }
        }

        Section(t("Audio")) {
            row {
                PickRow(t("Preferred audio language"), t("Picked when a title has several"), state.audioLanguage) {
                    actions.loadLanguages()
                    val options = listOf(Choice("", t("Original language"))) + state.languages
                    sheet.open(t("Preferred audio language"), options, state.config?.audioLanguagePreference.orEmpty()) { v ->
                        actions.setConfig { it.copy(audioLanguagePreference = v.ifEmpty { null }) }
                    }
                }
            }
            row {
                SwitchRow(t("Always use the default track"), t("Even when it isn’t in your language"), state.config?.playDefaultAudioTrack ?: true) { v ->
                    actions.setConfig { it.copy(playDefaultAudioTrack = v) }
                }
            }
            row {
                val options = Choices.channels(tv = false)
                PickRow(t("Most audio channels"), t("Headphones get stereo either way"), Choices.label(options, d.audioChannels), device = true) {
                    sheet.open(t("Most audio channels"), options, d.audioChannels) { v -> device { it.copy(audioChannels = v) } }
                }
            }
        }

        Section(t("Between episodes")) {
            row { SwitchRow(t("Play the next episode automatically"), t("From the Up next card at the credits"), pb.autoPlayNext, onChange = actions::setAutoPlayNext) }
            if (state.seedy) {
                row {
                    val options = (Choices.UpNextSeconds + pb.upNextSeconds).distinct().sorted().map { Choice(it, Choices.seconds(it)) }
                    PickRow(t("Up next countdown"), t("Your admin’s default is {n} seconds", "n" to state.defaults.upNextSeconds), Choices.seconds(pb.upNextSeconds)) {
                        sheet.open(t("Up next countdown"), options, pb.upNextSeconds) { v -> actions.setPlayback { it.copy(upNextSeconds = v) } }
                    }
                }
                row {
                    val value = state.stillWatching
                    val options = (Choices.StillWatching + value).distinct().map { Choice(it, Choices.stillWatching(it, short = false)) }
                    PickRow(t("Ask “Are you still watching?”"), t("Pauses auto-play when nobody’s there"), Choices.stillWatching(value, short = true)) {
                        sheet.open(t("Ask “Are you still watching?”"), options, value, actions::setStillWatching)
                    }
                }
            }
            row {
                val c = state.config
                val on = c == null || (c.rememberAudioSelections && c.rememberSubtitleSelections)
                SwitchRow(t("Keep my audio and subtitle choice"), t("Pick the same kind of track as the last episode"), on) { v ->
                    actions.setConfig { it.copy(rememberAudioSelections = v, rememberSubtitleSelections = v) }
                }
            }
        }

        Section(t("Skipping"), t("For titles where the server knows these parts.")) {
            if (state.seedy) {
                val skips = Choices.skips()
                row { SegRow(t("Intros"), null, skips, pb.skipIntro) { v -> actions.setPlayback { it.copy(skipIntro = v) } } }
                row { SegRow(t("Recaps"), null, skips, pb.skipRecap) { v -> actions.setPlayback { it.copy(skipRecap = v) } } }
                row { SegRow(t("Credits"), t("Goes to Up next or end-of-movie picks"), skips, pb.skipCredits) { v -> actions.setPlayback { it.copy(skipCredits = v) } } }
            }
            row {
                val options = (Choices.SeekBack + state.seekBack).distinct().sorted().map { Choice(it, Choices.seconds(it)) }
                PickRow(t("Skip back"), t("Double-tap the left side of the video"), Choices.seconds(state.seekBack), device = true) {
                    sheet.open(t("Skip back"), options, state.seekBack) { v -> device { it.copy(seekBack = v) } }
                }
            }
            row {
                val options = (Choices.SeekForward + state.seekForward).distinct().sorted().map { Choice(it, Choices.seconds(it)) }
                PickRow(t("Skip forward"), t("Double-tap the right side of the video"), Choices.seconds(state.seekForward), device = true) {
                    sheet.open(t("Skip forward"), options, state.seekForward) { v -> device { it.copy(seekForward = v) } }
                }
            }
        }

        Section(t("Leaving the app")) {
            row {
                SwitchRow(t("Picture in picture"), t("When you go to the home screen while a video plays"), d.pictureInPicture, device = true) { v -> device { it.copy(pictureInPicture = v) } }
            }
            row {
                SwitchRow(t("Keep the sound going with the screen off"), t("For concerts and talk shows"), d.backgroundAudio, device = true) { v -> device { it.copy(backgroundAudio = v) } }
            }
        }

        Section(null) {
            row {
                PickRow(t("Advanced"), t("Codec and streaming tweaks for when something won’t play"), t("{n} settings", "n" to Advanced.size), device = true) { nav.open("advanced") }
            }
        }
        SaveLine(state.saving, state.error)
    }
}

/** Codec and streaming tweaks: each one shapes the device profile sent to the server. */
private class AdvancedSetting(val name: String, val help: String, val get: (DeviceSettings) -> Boolean, val set: (DeviceSettings, Boolean) -> DeviceSettings)

private val Advanced = listOf(
    AdvancedSetting("Prefer fMP4 when converting", "Newer HLS segments; turn off if converted video stutters or won’t start", { it.preferFmp4 }, { d, v -> d.copy(preferFmp4 = v) }),
    AdvancedSetting("Play HEVC directly", "When this device can decode H.265", { it.hevc }, { d, v -> d.copy(hevc = v) }),
    AdvancedSetting("Play AV1 directly", "When this device can decode AV1", { it.av1 }, { d, v -> d.copy(av1 = v) }),
    AdvancedSetting("Play HDR directly", "Off asks the server to tone-map HDR to SDR", { it.hdr }, { d, v -> d.copy(hdr = v) }),
    AdvancedSetting("DTS audio", "Turn on if this device or your receiver decodes DTS", { it.dts }, { d, v -> d.copy(dts = v) }),
    AdvancedSetting("Dolby TrueHD audio", "Turn on if this device or your receiver decodes TrueHD", { it.truehd }, { d, v -> d.copy(truehd = v) }),
    AdvancedSetting("Play Matroska files directly", "Off has the server repackage MKV files first", { it.mkv }, { d, v -> d.copy(mkv = v) }),
)

/** Settings · Playback › Advanced (no board: the Playback board's rows). */
@Composable
fun AdvancedPage(state: SettingsState, actions: SettingsActions, nav: SettingsNav) {
    SettingsPage(t("Advanced"), t("Codec and streaming tweaks for when something won’t play. They stay on this phone."), nav.back) {
        Section(null, t("Each one changes what Seedy asks the server for, from the next video on.")) {
            for (a in Advanced) row { SwitchRow(t(a.name), t(a.help), a.get(state.device), device = true) { v -> actions.setDevice { a.set(it, v) } } }
        }
        SaveLine()
    }
}
