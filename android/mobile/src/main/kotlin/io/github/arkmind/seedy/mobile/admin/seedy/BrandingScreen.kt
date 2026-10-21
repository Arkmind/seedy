package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.seedy.AccentSwatches
import io.github.arkmind.seedy.core.admin.seedy.AdminConfig
import io.github.arkmind.seedy.core.admin.seedy.BrandingActions
import io.github.arkmind.seedy.core.admin.seedy.BrandingState
import io.github.arkmind.seedy.core.admin.seedy.validHex
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.seedy.PlaybackPrefs
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.Choices
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.RadioRow
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SegRow
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.FieldBlock
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.settings.StackRow

private val PauseSeconds = listOf(5, 10, 30, 0)

private fun pauseLabel(n: Int): String = when (n) {
    5 -> t("After 5 seconds")
    10 -> t("After 10 seconds")
    30 -> t("After 30 seconds")
    0 -> t("Never")
    else -> t("After {n} seconds", "n" to n)
}

private val Qualities = listOf("auto", "4k", "1080p", "720p")

private fun qualityLabel(v: String): String = when (v) {
    "4k" -> "4K · 80 Mbps"
    "1080p" -> "1080p · 20 Mbps"
    "720p" -> "720p · 8 Mbps"
    else -> t("Auto")
}

/** The web's skip choices, in its order; "Skip" as the phone settings say it. */
private fun skipChoices() = listOf(Choice("button", t("Show button")), Choice("auto", t("Skip")), Choice("off", t("Off")))

/** Seedy › Branding & playback (no phone board: the server boards' kit with the web board AdminGeneral's content). */
@Composable
fun BrandingScreen(state: BrandingState, actions: BrandingActions, onBack: () -> Unit, onPickLogo: () -> Unit) {
    val sheet = rememberSheet()
    SettingsPage(
        t("Branding & playback"),
        t("Where Seedy opens, how it looks, and the playback defaults new profiles start with. Server status lives on Overview."),
        onBack,
        overlay = {
            SheetHost(sheet)
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        val c = state.config
        when {
            c == null && state.loadError != null -> ErrorCard(t("Couldn’t load Seedy’s settings"), actions::retry, Modifier.padding(top = 16.dp), detail = state.loadError.orEmpty())
            c == null -> LoadingCards(3)
            else -> {
                Section(t("Where Seedy opens"), t("Jellyfin's own web app always stays reachable for admins.")) {
                    row {
                        RadioRow(t("Replace Jellyfin's web app"), t("The server address opens Seedy for everyone. Jellyfin's app moves to /old."), c.opensAt == "replace", onClick = { actions.setOpensAt("replace") })
                    }
                    row {
                        RadioRow(t("Alongside Jellyfin"), t("Seedy opens at {path}. The server address still opens Jellyfin's app.", "path" to state.alongsidePath), c.opensAt != "replace", onClick = { actions.setOpensAt("alongside") })
                    }
                }
                BrandingSection(c, state, actions, onPickLogo)
                PlaybackSection(c.playbackDefaults, actions, sheet)
                AutoSaveLine(state.save)
            }
        }
    }
}

@Composable
private fun BrandingSection(c: AdminConfig, state: BrandingState, actions: BrandingActions, onPickLogo: () -> Unit) {
    val b = c.branding
    Section(t("Branding"), t("Shown in the header, on the sign-in page and in browser tabs.")) {
        row {
            var name by remember(b.name) { mutableStateOf(b.name) }
            val commit = {
                if (name.isBlank()) name = b.name else actions.setName(name)
            }
            FieldBlock(
                t("Name"), name, { name = it.take(40) },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                help = t("Next to the logo"), onDone = commit, onBlur = commit,
            )
        }
        row {
            StackRow(t("Logo"), t("SVG or PNG, square or wide")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LogoBox(b.name, state.logoUrl, state.logoBusy)
                    SText(
                        if (b.hasLogo) b.logoFileName?.takeIf { it.isNotEmpty() } ?: t("Custom logo") else t("No logo yet"),
                        Modifier.weight(1f), size = 13.sp, color = SeedyColors.Text2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    PillButton(if (b.hasLogo) t("Replace") else t("Upload"), onPickLogo, enabled = !state.logoBusy, height = 36.dp)
                    if (b.hasLogo) {
                        Box(Modifier.size(height = 36.dp, width = 72.dp).clip(RoundedCornerShape(18.dp)).tap(enabled = !state.logoBusy, onClick = actions::removeLogo), contentAlignment = Alignment.Center) {
                            SText(t("Remove"), size = 13.sp, weight = 700, color = SeedyColors.Text2, maxLines = 1)
                        }
                    }
                }
            }
        }
        row { AccentColourRow(b.accent, actions) }
        row {
            SegRow(
                t("Sign-in background"), t("Behind the profile picker"),
                listOf(Choice("backdrops", t("Library backdrops")), Choice("plain", t("Plain"))), b.signInBackground, onChange = actions::setSignInBackground,
            )
        }
    }
}

/** The logo as the header shows it, or the lettered mark, in a 52 px dark tile. */
@Composable
private fun LogoBox(name: String, url: String?, busy: Boolean) {
    Box(
        Modifier.size(52.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Bg).border(1.dp, SeedyColors.Surface2, RoundedCornerShape(13.dp)).alpha(if (busy) 0.5f else 1f),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).background(SeedyColors.Inverse, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
            SText(name.trim().take(1).uppercase().ifEmpty { "S" }, size = 16.sp, weight = 900, color = SeedyColors.InkInverse)
        }
        if (url != null) Artwork(url, Modifier.fillMaxSize().padding(8.dp), placeholder = SeedyColors.Bg, scale = ContentScale.Fit)
    }
}

@Composable
private fun AccentColourRow(accent: String, actions: BrandingActions) {
    var hex by remember(accent) { mutableStateOf(accent) }
    StackRow(t("Accent colour"), t("Counts, focus ring, selection")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.padding(start = 2.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for ((label, value) in AccentSwatches) {
                    AccentDot(Color(android.graphics.Color.parseColor(value)), value.equals(accent, ignoreCase = true), t(label)) {
                        hex = value
                        actions.setAccent(value)
                    }
                }
            }
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                SText("Hex", size = 12.sp, color = SeedyColors.Text3)
                BoardField(
                    hex,
                    { v ->
                        var x = v.trim().take(7)
                        if (x.isNotEmpty() && !x.startsWith("#")) x = "#$x"
                        hex = x
                        if (validHex(x)) actions.setAccent(x)
                    },
                    error = !validHex(hex), size = 14,
                    onBlur = { if (!validHex(hex)) hex = accent },
                )
            }
        }
    }
}

/** A 34 px accent swatch; the chosen one has the white ring of the settings boards. */
@Composable
private fun AccentDot(color: Color, on: Boolean, label: String, onClick: () -> Unit) {
    val stroke = SeedyColors.Stroke
    Canvas(Modifier.size(34.dp).tap(role = Role.RadioButton, onClick = onClick)) {
        val r = size.minDimension / 2
        val c = Offset(size.width / 2, size.height / 2)
        if (on) {
            drawCircle(Color.White, r + 5.dp.toPx(), c)
            drawCircle(SeedyColors.Panel, r + 3.dp.toPx(), c)
        }
        drawCircle(color, r, c)
        if (!on) drawCircle(stroke, r - 0.75.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
private fun PlaybackSection(p: PlaybackPrefs, actions: BrandingActions, sheet: SheetState) {
    val skips = skipChoices()
    fun seconds(name: String, help: String?, values: List<Int>, value: Int, set: (PlaybackPrefs, Int) -> PlaybackPrefs): @Composable () -> Unit = {
        PickRow(name, help, Choices.seconds(value)) {
            sheet.open(name, choices(withValue(values, value)) { Choices.seconds(it) }, value) { v -> actions.setPlayback { set(it, v) } }
        }
    }
    Section(t("Playback defaults"), t("New profiles start with these. Each profile can change them in its own settings.")) {
        row { SegRow(t("Intros"), t("Opening titles from media segments"), skips, p.skipIntro) { v -> actions.setPlayback { it.copy(skipIntro = v) } } }
        row { SegRow(t("Recaps"), t("\"Previously on\" segments"), skips, p.skipRecap) { v -> actions.setPlayback { it.copy(skipRecap = v) } } }
        row { SegRow(t("Credits"), t("Goes to Up next or end-of-movie picks"), skips, p.skipCredits) { v -> actions.setPlayback { it.copy(skipCredits = v) } } }
        row { SwitchRow(t("Auto-play next episode"), t("From the Up next card"), p.autoPlayNext) { v -> actions.setPlayback { it.copy(autoPlayNext = v) } } }
        row(seconds(t("Up next countdown"), t("Before Up next starts"), Choices.UpNextSeconds, p.upNextSeconds) { it, v -> it.copy(upNextSeconds = v) })
        row(seconds(t("Movie picks countdown"), t("Before end-of-movie picks start"), Choices.UpNextSeconds, p.moviePicksSeconds) { it, v -> it.copy(moviePicksSeconds = v) })
        row(seconds(t("Skip back"), null, Choices.SeekBack + listOf(45, 60), p.seekBackSeconds) { it, v -> it.copy(seekBackSeconds = v) })
        row(seconds(t("Skip forward"), null, Choices.SeekForward + 120, p.seekForwardSeconds) { it, v -> it.copy(seekForwardSeconds = v) })
        row {
            val value = if (p.pauseScreenSeconds in PauseSeconds) p.pauseScreenSeconds else 10
            PickRow(t("Pause screen"), t("Shows title and synopsis while paused"), pauseLabel(value)) {
                sheet.open(t("Pause screen"), choices(PauseSeconds, ::pauseLabel), value) { v -> actions.setPlayback { it.copy(pauseScreenSeconds = v) } }
            }
        }
        row {
            PickRow(t("Maximum streaming quality"), t("Jellyfin's per-user bitrate limits still apply"), qualityLabel(p.maxQuality)) {
                sheet.open(t("Maximum streaming quality"), choices(Qualities, ::qualityLabel), p.maxQuality) { v -> actions.setPlayback { it.copy(maxQuality = v) } }
            }
        }
    }
}
