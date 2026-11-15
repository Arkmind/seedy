package io.github.arkmind.seedy.mobile.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.Choices
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.core.settings.StillWatchingRules
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
            // The board's "When casting" waits for video casting.
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
                    val options = choices(withValue(Choices.UpNextSeconds, pb.upNextSeconds)) { Choices.seconds(it) }
                    PickRow(t("Up next countdown"), t("Your admin’s default is {n} seconds", "n" to state.defaults.upNextSeconds), Choices.seconds(pb.upNextSeconds)) {
                        sheet.open(t("Up next countdown"), options, pb.upNextSeconds) { v -> actions.setPlayback { it.copy(upNextSeconds = v) } }
                    }
                }
                row { StillWatchingRow(state.stillWatching, actions::setStillWatching) }
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
                val options = choices(withValue(Choices.SeekBack, state.seekBack)) { Choices.seconds(it) }
                PickRow(t("Skip back"), t("Double-tap the left side of the video"), Choices.seconds(state.seekBack), device = true) {
                    sheet.open(t("Skip back"), options, state.seekBack) { v -> device { it.copy(seekBack = v) } }
                }
            }
            row {
                val options = choices(withValue(Choices.SeekForward, state.seekForward)) { Choices.seconds(it) }
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

/**
 * "Ask “Are you still watching?”": three rules to tick, each a card with its checkbox and a segmented count (dimmed
 * while unticked; picking a count ticks the rule, as on the web).
 */
@Composable
private fun StillWatchingRow(rules: StillWatchingRules, onChange: (StillWatchingRules) -> Unit) {
    StackRow(t("Ask “Are you still watching?”"), t("Seedy asks when any rule you tick is met. Touching the screen or the volume starts the count again.")) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleCard(
                t("After episodes in a row"), t("Shows only"), rules.episodesOn,
                choices(withValue(StillWatchingRules.Episodes, rules.episodes), StillWatchingRules::episodesLabel), rules.episodes,
                onTick = { onChange(rules.copy(episodesOn = it)) }, onCount = { onChange(rules.copy(episodes = it, episodesOn = true)) },
            )
            RuleCard(
                t("After watching for a while"), t("Shows and movies, without a break"), rules.minutesOn,
                choices(withValue(StillWatchingRules.Minutes, rules.minutes), StillWatchingRules::minutesLabel), rules.minutes,
                onTick = { onChange(rules.copy(minutesOn = it)) }, onCount = { onChange(rules.copy(minutes = it, minutesOn = true)) },
            )
            RuleCard(
                t("After movies in a row"), t("Before end-of-movie picks or the next film start"), rules.moviesOn,
                choices(withValue(StillWatchingRules.Movies, rules.movies), StillWatchingRules::moviesLabel), rules.movies,
                onTick = { onChange(rules.copy(moviesOn = it)) }, onCount = { onChange(rules.copy(movies = it, moviesOn = true)) },
            )
        }
    }
}

/** One rule: a 12 px card on #1a1a1a, the checkbox and its words over the counts (34 px options in a #242424 well). */
@Composable
private fun RuleCard(name: String, help: String, on: Boolean, options: List<Choice<Int>>, value: Int, onTick: (Boolean) -> Unit, onCount: (Int) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Row).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().tap(role = Role.Checkbox) { onTick(!on) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RuleCheck(on)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SText(name, size = 13.sp, weight = 700, color = if (on) SeedyColors.White else SeedyColors.TextHero)
                SText(help, Modifier.cssText(11, 1.35f), size = 11.sp, lineHeight = 1.35f, color = SeedyColors.Text3)
            }
        }
        Segmented(
            options, value, onCount, Modifier.alpha(if (on) 1f else .4f),
            height = 34.dp, well = Color(0xFF242424), wellRadius = 12.dp, radius = 9.dp,
        )
    }
}

/** The 22 px checkbox: the accent with a dark tick when on, a 2 px #5a5a5a ring when off. */
@Composable
private fun RuleCheck(on: Boolean) {
    val shape = RoundedCornerShape(7.dp)
    Box(
        Modifier.size(22.dp).clip(shape)
            .then(if (on) Modifier.background(LocalAccent.current) else Modifier.border(2.dp, Color(0xFF5A5A5A), shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (on) SIcon(WebIcons.Check, size = 14.dp, color = SeedyColors.InkInverse, stroke = 3.2f)
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
