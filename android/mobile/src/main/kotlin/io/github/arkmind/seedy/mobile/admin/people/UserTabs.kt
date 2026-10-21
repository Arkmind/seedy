package io.github.arkmind.seedy.mobile.admin.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.people.Days
import io.github.arkmind.seedy.core.admin.people.UnratedKinds
import io.github.arkmind.seedy.core.admin.people.UserEditActions
import io.github.arkmind.seedy.core.admin.people.UserEditState
import io.github.arkmind.seedy.core.admin.people.allows
import io.github.arkmind.seedy.core.admin.people.array
import io.github.arkmind.seedy.core.admin.people.dayLabel
import io.github.arkmind.seedy.core.admin.people.flag
import io.github.arkmind.seedy.core.admin.people.hourLabel
import io.github.arkmind.seedy.core.admin.people.labelOf
import io.github.arkmind.seedy.core.admin.people.ratingOptions
import io.github.arkmind.seedy.core.admin.people.schedules
import io.github.arkmind.seedy.core.admin.people.strings
import io.github.arkmind.seedy.core.admin.sameId
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.TextSheet
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.CardRows
import io.github.arkmind.seedy.mobile.settings.FormSheet

import io.github.arkmind.seedy.mobile.settings.SheetState
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.github.arkmind.seedy.mobile.admin.AccentRow
import io.github.arkmind.seedy.mobile.admin.ChipFlow
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.admin.FormError
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.StackRow
import io.github.arkmind.seedy.mobile.settings.RowLabel
import io.github.arkmind.seedy.ui.icons.IconDef
import androidx.compose.ui.text.style.TextAlign

/* A user's other tabs (web/src/screens/server/UserEdit.tsx, built from the phone kit): libraries and devices,
 * parental controls, password and Seedy. */

private const val DevicePage = 40

private fun toggleIn(list: List<String>, id: String, on: Boolean): List<String> {
    val rest = list.filter { !sameId(it, id) && it != id }
    return if (on) rest + id else rest
}

/** A grey line inside a card ("There are no libraries yet."). */
@Composable
private fun NoteRow(text: String, color: androidx.compose.ui.graphics.Color = SeedyColors.Text2) {
    SText(text, Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), size = 13.sp, lineHeight = 1.45f, color = color)
}

/* ── Libraries and devices ── */

@Composable
internal fun AccessTab(state: UserEditState, actions: UserEditActions) {
    var allDevices by remember { mutableStateOf(false) }
    var shown by remember { mutableIntStateOf(DevicePage) }
    val p = state.policy
    val name = state.name
    Section(t("Libraries"), foot = t("What {name} can browse and play.", "name" to name)) {
        switch(state, actions, "EnableAllFolders", t("All libraries"), t("Including libraries you add later"), default = true)
        if (!p.flag("EnableAllFolders", true)) {
            val enabled = p.strings("EnabledFolders")
            if (state.folders.isEmpty()) row { NoteRow(t("There are no libraries yet.")) }
            for (f in state.folders) {
                row {
                    SwitchRow(f.name, f.sub, enabled.any { sameId(it, f.id) }) { on -> actions.set("EnabledFolders", strings(toggleIn(enabled, f.id, on))) }
                }
            }
        }
    }
    Section(t("Devices"), foot = t("Where {name} can sign in.", "name" to name)) {
        switch(state, actions, "EnableAllDevices", t("Any device"), t("New phones, TVs and browsers included"), default = true)
        if (!p.flag("EnableAllDevices", true)) {
            val enabled = p.strings("EnabledDevices")
            val relevant = state.devices.filter { it.theirs || p.allows(it.id) }
            val list = if (allDevices) state.devices else relevant
            if (list.isEmpty()) row { NoteRow(t("{name} hasn’t signed in on any device yet.", "name" to name)) }
            for (d in list.take(shown)) {
                row {
                    SwitchRow(d.name, d.sub.ifEmpty { null }, p.allows(d.id), device = d.mine) { on -> actions.set("EnabledDevices", strings(toggleIn(enabled, d.id, on))) }
                }
            }
            if (list.size > shown) row { AccentRow(t("Show more") + " · " + t("{n} more", "n" to formatNumber(list.size - shown)), { shown += DevicePage }, icon = null) }
            if (state.devices.size > relevant.size || allDevices) {
                row {
                    AccentRow(
                        if (allDevices) t("Only {name}’s devices", "name" to name) else t("Show every device on the server ({n})", "n" to formatNumber(state.devices.size)),
                        { allDevices = !allDevices; shown = DevicePage },
                        icon = null,
                    )
                }
            }
        }
    }
}

/* ── Parental controls ── */

@Composable
internal fun ParentalTab(state: UserEditState, actions: UserEditActions, sheet: SheetState) {
    val p = state.policy
    val name = state.name
    var tagging by remember { mutableStateOf<String?>(null) }
    var scheduling by remember { mutableStateOf(false) }
    val (options, value) = ratingOptions(state.ratings, p)
    val (savedOptions, savedValue) = ratingOptions(state.ratings, state.saved)
    Section(t("Ratings"), foot = t("Titles above the limit are hidden from {name} everywhere, including search.", "name" to name)) {
        row {
            val help = if (state.ratings.isNotEmpty()) t("Ratings from the server’s country, lowest first") else t("Ratings from the metadata, lowest first")
            PickRow(t("Highest rating allowed"), helpFor(state, "MaxParentalRating", help) { labelOf(savedOptions, savedValue) }, labelOf(options, value)) {
                sheet.open(t("Highest rating allowed"), options, value, actions::setRating)
            }
        }
    }
    val blocked = p.strings("BlockUnratedItems")
    Section(t("Hide titles without a rating"), foot = t("Turn on for each kind of title that should stay hidden when it has no rating")) {
        for ((id, label) in UnratedKinds) {
            row {
                SwitchRow(t(label), null, id in blocked) { on -> actions.set("BlockUnratedItems", strings(if (on) blocked.filter { it != id } + id else blocked.filter { it != id })) }
            }
        }
    }
    Section(t("Tags"), foot = t("Tags come from the metadata, or from what you add on a title’s page.")) {
        tags(t("Only show titles tagged"), t("Leave empty to allow everything"), p.strings("AllowedTags"), { tagging = "AllowedTags" }) { actions.set("AllowedTags", strings(it)) }
        tags(t("Hide titles tagged"), t("Wins over the list above"), p.strings("BlockedTags"), { tagging = "BlockedTags" }) { actions.set("BlockedTags", strings(it)) }
    }
    val schedules = p.schedules()
    val raw = p.array("AccessSchedules")
    Section(t("When {name} can watch", "name" to name), foot = t("Outside these hours, sign-in and playback are blocked. No times means any time.")) {
        schedules.forEachIndexed { i, s ->
            row {
                ScheduleLine("${dayLabel(s.dayOfWeek)} · ${hourLabel(s.startHour)}–${hourLabel(s.endHour)}") {
                    actions.set("AccessSchedules", JsonArray(raw.filterIndexed { j, _ -> j != i }))
                }
            }
        }
        row { AccentRow(t("Add a time"), { scheduling = true }) }
    }
    tagging?.let { key ->
        val list = p.strings(key)
        TextSheet(t("Add tag"), null, t("Add"), { v ->
            val tag = v.trim()
            if (tag.isNotEmpty() && list.none { it.equals(tag, ignoreCase = true) }) actions.set(key, strings(list + tag))
        }, { tagging = null }, placeholder = t("Tag"))
    }
    if (scheduling) {
        ScheduleSheet(onClose = { scheduling = false }) { day, start, end ->
            actions.set("AccessSchedules", JsonArray(raw + buildJsonObject { put("DayOfWeek", day); put("StartHour", start); put("EndHour", end) }))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun CardRows.tags(name: String, help: String, tags: List<String>, onAdd: () -> Unit, onChange: (List<String>) -> Unit) {
    row {
        StackRow(name, help) {
            ChipFlow(tags, { i -> onChange(tags.filter { it != tags[i] }) }, t("Add tag"), onAdd, small = true)
        }
    }
}

@Composable
private fun ScheduleLine(text: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SIcon(WebIcons.Clock, size = 18.dp, color = SeedyColors.Text3)
        SText(text, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
        RoundButton(WebIcons.Close, onRemove, iconSize = 14.dp, bare = true, tint = SeedyColors.Text2)
    }
}

/** Days, from and until, in half hours. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScheduleSheet(onClose: () -> Unit, onAdd: (String, Double, Double) -> Unit) {
    var day by remember { mutableStateOf("Everyday") }
    var start by remember { mutableDoubleStateOf(9.0) }
    var end by remember { mutableDoubleStateOf(20.0) }
    FormSheet(t("Add a time"), null, onClose) {
        SText(t("Days"), size = 13.sp, weight = 700, color = SeedyColors.Text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((v, label) in Days) {
                val on = v == day
                Box(
                    Modifier.height(36.dp).clip(CircleShape).background(if (on) SeedyColors.Inverse else SeedyColors.Surface).tap(role = Role.RadioButton) { day = v }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { SText(t(label), size = 13.sp, weight = if (on) 800 else 600, color = if (on) SeedyColors.InkInverse else SeedyColors.Text, maxLines = 1) }
            }
        }
        Stepper(t("From"), hourLabel(start), if (start > 0) ({ start -= 0.5 }) else null, if (start < 23.5) ({ start += 0.5 }) else null)
        Stepper(t("Until"), hourLabel(end), if (end > 0.5) ({ end -= 0.5 }) else null, if (end < 24) ({ end += 0.5 }) else null)
        if (end <= start) FormError(t("The end has to be after the start."))
        SheetButtons(t("Add"), { onAdd(day, start, end); onClose() }, onClose, enabled = end > start)
    }
}

/* ── Password ── */

@Composable
internal fun PasswordTab(state: UserEditState, actions: UserEditActions, onRemove: () -> Unit) {
    val name = state.savedName
    // A password that went through clears the fields.
    var current by remember(state.password.done) { mutableStateOf("") }
    var next by remember(state.password.done) { mutableStateOf("") }
    var again by remember(state.password.done) { mutableStateOf("") }
    val mismatch = again.isNotEmpty() && next != again
    val foot = if (state.isMe) t("Changing your password signs you out on your other devices.")
    else t("A new password signs {name} out on their other devices. Remove it to let {name} sign in without one.", "name" to name)
    Section(t("Password"), foot = foot) {
        row {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (state.isMe) PasswordField(t("Current password"), t("Needed to change your own password"), current) { current = it; actions.clearPasswordError() }
                PasswordField(t("New password"), t("Any length; longer is safer"), next) { next = it; actions.clearPasswordError() }
                PasswordField(t("Type it again"), t("To be sure there’s no typo"), again) { again = it; actions.clearPasswordError() }
                if (mismatch) FormError(t("The two passwords don’t match."))
                state.password.error?.let { FormError(it) }
                SheetButtons {
                    if (!state.isMe && !state.saved.flag("IsAdministrator")) PillButton(t("Remove password"), onRemove)
                    PillButton(
                        if (state.password.busy) t("Saving…") else t("Set password"),
                        { actions.setPassword(current, next) },
                        style = PillStyle.Light,
                        enabled = next.isNotEmpty() && next == again && !state.password.busy && (!state.isMe || current.isNotEmpty()),
                    )
                }
            }
        }
    }
}

@Composable
private fun PasswordField(name: String, help: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RowLabel(name, help)
        SeedyField(value, onChange, password = true)
    }
}

/* ── Seedy ── */

@Composable
internal fun SeedyTab(state: UserEditState, actions: UserEditActions, open: (String) -> Unit) {
    val name = state.name
    val f = state.seedy
    val was = state.savedSeedy
    fun help(changed: Boolean, old: Boolean, help: String) = if (changed) t("Changed · was {value}", "value" to if (old) t("on") else t("off")) else help
    Section(t("Seedy"), foot = t("What {name} gets in Seedy, on top of Jellyfin’s access.", "name" to name)) {
        if (!state.seedyLoaded) {
            row { NoteRow("…") }
            return@Section
        }
        if (state.seedyError) row { NoteRow(t("Seedy’s settings didn’t load, so these show their defaults."), SeedyColors.Pending) }
        row {
            val linkedHelp = if (state.linked) t("Through Seerr, as {name}", "name" to (state.seerrName ?: t("their Seerr account")))
            else t("Link {name} to a Seerr account under {requests} first", "name" to name, "requests" to t("Requests"))
            SwitchRow(t("Request titles"), help(f.canRequest != was.canRequest, was.canRequest, linkedHelp), f.canRequest && state.linked, enabled = state.linked) {
                actions.setSeedy(f.copy(canRequest = it))
            }
        }
        if (!state.linked) row { AccentRow(t("Requests"), { open("requests") }, icon = null) }
        row {
            SwitchRow(t("Counts toward Top 10"), help(f.top10 != was.top10, was.top10, t("Their plays shape the Top 10 row everyone sees")), f.top10) { actions.setSeedy(f.copy(top10 = it)) }
        }
        row {
            SwitchRow(
                t("Keep viewing history"),
                help(f.history != was.history, was.history, t("Their plays show up in Viewing history. Details under {playlog}", "playlog" to t("Play log"))),
                f.history,
            ) { actions.setSeedy(f.copy(history = it)) }
        }
    }
}

private val Minus = IconDef(2.6f, "M5 12h14")

/** A stepper for a value in steps ("9:00" with − and +). */
@Composable
private fun Stepper(label: String, value: String, onMinus: (() -> Unit)?, onPlus: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SText(label, Modifier.weight(1f), size = 14.sp, weight = 700, color = SeedyColors.White)
        StepButton(Minus, onMinus)
        SText(value, Modifier.padding(horizontal = 4.dp), size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1, align = TextAlign.Center)
        StepButton(WebIcons.Plus, onPlus)
    }
}

@Composable
private fun StepButton(icon: IconDef, onClick: (() -> Unit)?) =
    RoundButton(icon, { onClick?.invoke() }, enabled = onClick != null, tint = if (onClick != null) SeedyColors.White else SeedyColors.Stroke, dim = 1f)
