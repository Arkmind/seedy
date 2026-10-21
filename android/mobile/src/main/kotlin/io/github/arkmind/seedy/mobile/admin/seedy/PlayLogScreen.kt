package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.seedy.PlayLogAdminActions
import io.github.arkmind.seedy.core.admin.seedy.PlayLogAdminState
import io.github.arkmind.seedy.core.admin.seedy.PlayLogProfile
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.mobile.admin.ActionSheet
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.RowLine
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SheetHost
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.rememberSheet
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

private val Retention = listOf(90, 365, 730, 0)

private fun retentionLabel(n: Int): String = when (n) {
    90 -> t("90 days")
    365 -> t("1 year")
    730 -> t("2 years")
    0 -> t("Forever")
    else -> tn(n, "{n} day", "{n} days")
}

/** The two switch columns of the profiles table. */
private val SwitchColumn = 64.dp

/** Seedy › Play log (no phone board: the server boards' kit with the web board AdminPlayLog's content). */
@Composable
fun PlayLogScreen(state: PlayLogAdminState, actions: PlayLogAdminActions, onBack: () -> Unit, onExport: (PlayLogProfile) -> Unit) {
    val sheet = rememberSheet()
    var menu by remember { mutableStateOf<PlayLogProfile?>(null) }
    var clearing by remember { mutableStateOf<PlayLogProfile?>(null) }
    var clearAll by remember { mutableStateOf(false) }
    SettingsPage(
        t("Play log"),
        t("Seedy records every play to build the Top 10, viewing history and row order. Jellyfin itself only keeps the last play of each title."),
        onBack,
        overlay = {
            SheetHost(sheet)
            menu?.let { p ->
                val options = buildList<Pair<String, () -> Unit>> {
                    add(t("Export plays") to { onExport(p) })
                    if (p.plays > 0) add(t("Clear plays") to { clearing = p })
                }
                ActionSheet(p.name, options, { menu = null }, danger = if (p.plays > 0) setOf(1) else emptySet())
            }
            clearing?.let { p ->
                ConfirmSheet(
                    t("Clear {name}’s plays?", "name" to p.name),
                    t("Removes {name}’s viewing history and their part in the Top 10 and row order. Watched status in Jellyfin is not touched. This can’t be undone.", "name" to p.name),
                    t("Clear plays"), { actions.clear(p.userId) }, { clearing = null }, danger = true,
                )
            }
            if (clearAll) {
                ConfirmSheet(
                    t("Clear the whole play log?"),
                    t("Every profile’s history, the Top 10 and what Seedy learned about row order will be removed. Watched status in Jellyfin is not touched. This can’t be undone."),
                    t("Clear play log"), actions::clearAll, { clearAll = false }, danger = true,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        val c = state.config
        when {
            c == null && state.loadError != null -> LoadFailed(t("Couldn’t load Seedy’s settings"), state.loadError.orEmpty(), actions::retry)
            c == null -> LoadingCards(2)
            else -> {
                val log = c.playLog
                Section(t("Logging")) {
                    row { SwitchRow(t("Record plays"), t("Start, stop, position, device and the row it started from"), log.enabled) { v -> actions.set { it.copy(enabled = v) } } }
                    row {
                        val value = if (log.retentionDays in Retention) log.retentionDays else 365
                        PickRow(t("Keep plays for"), t("Older plays are deleted every night"), retentionLabel(value)) {
                            sheet.open(t("Keep plays for"), choices(Retention, ::retentionLabel), value) { v -> actions.set { it.copy(retentionDays = v) } }
                        }
                    }
                }
                Section(t("What the log is used for")) {
                    row { SwitchRow(t("Top 10 this week"), t("Counts plays across all profiles"), log.useTop10) { v -> actions.set { it.copy(useTop10 = v) } } }
                    row { SwitchRow(t("Viewing history page"), t("Each profile sees only its own plays"), log.useHistory) { v -> actions.set { it.copy(useHistory = v) } } }
                    row { SwitchRow(t("Row order"), t("Learns which home rows each person plays from"), log.useRowOrder) { v -> actions.set { it.copy(useRowOrder = v) } } }
                    row { SwitchRow(t("Genre rows"), t("Picks the genres each person watches most"), log.useGenres) { v -> actions.set { it.copy(useGenres = v) } } }
                }
            }
        }

        SectionTitle(t("Profiles"))
        Profiles(state, actions) { menu = it }
        SectionFoot(t("Leave a profile out of the Top 10, hide its history page, or export and clear its plays."))

        DangerZone(state.clearing) { clearAll = true }
        if (c != null) AutoSaveLine(state.save)
    }
}

@Composable
private fun Profiles(state: PlayLogAdminState, actions: PlayLogAdminActions, onMenu: (PlayLogProfile) -> Unit) {
    val list = state.profiles
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(SeedyColors.Panel)) {
        when {
            list == null && state.profilesError != null -> Row(Modifier.seedyRow(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(SeedyColors.Danger)
                SText(t("Couldn’t load the profiles.") + " " + state.profilesError, Modifier.weight(1f), size = 13.sp, color = SeedyColors.Text2)
                PillButton(t("Try again"), actions::retryProfiles, height = 36.dp)
            }
            list == null -> repeat(3) { i ->
                if (i > 0) RowLine()
                Box(Modifier.seedyRow())
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Head(t("Profile"), Modifier.weight(1f))
                    Head("Top 10", Modifier.width(SwitchColumn), center = true)
                    Head(t("History"), Modifier.width(SwitchColumn), center = true)
                }
                list.forEachIndexed { i, p ->
                    RowLine()
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).tap(enabled = state.busy != p.userId) { onMenu(p) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AdminAvatar(p.name, avatarColour(i), 40.dp, 16.sp, imageUrl = state.avatars[p.userId])
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                SText(p.name, size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SText(
                                    if (state.busy == p.userId) t("Working…") else tn(p.plays, "{n} play", "{n} plays"),
                                    size = 12.sp, color = SeedyColors.Text3, maxLines = 1,
                                )
                            }
                        }
                        Box(Modifier.width(SwitchColumn), contentAlignment = Alignment.Center) { BoardSwitch(p.top10, { actions.setTop10(p.userId, it) }) }
                        Box(Modifier.width(SwitchColumn), contentAlignment = Alignment.Center) { BoardSwitch(p.history, { actions.setHistory(p.userId, it) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Head(text: String, modifier: Modifier, center: Boolean = false) {
    SText(
        text, modifier, size = 11.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true, maxLines = 1,
        align = if (center) androidx.compose.ui.text.style.TextAlign.Center else null,
    )
}

/** "Clear the whole play log": a card ringed in red with its button. */
@Composable
private fun DangerZone(busy: Boolean, onClear: () -> Unit) {
    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .border(1.dp, SeedyColors.Danger.copy(alpha = 0.35f), RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SText(t("Clear the whole play log"), size = 15.sp, weight = 800, color = SeedyColors.White)
        SText(
            t("Removes every profile's history, the Top 10 and what Seedy learned about row order. Watched status in Jellyfin is not touched. This can't be undone."),
            size = 12.sp, lineHeight = 1.45f, color = SeedyColors.Text2,
        )
        PillButton(if (busy) t("Clearing…") else t("Clear play log"), onClear, Modifier.padding(top = 8.dp), style = PillStyle.Danger, enabled = !busy)
    }
}
