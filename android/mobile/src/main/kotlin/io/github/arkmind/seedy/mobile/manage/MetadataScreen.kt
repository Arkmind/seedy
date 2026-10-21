package io.github.arkmind.seedy.mobile.manage

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.format.fullDate
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.MetaPerson
import io.github.arkmind.seedy.core.manage.MetaTab
import io.github.arkmind.seedy.core.manage.MetadataActions
import io.github.arkmind.seedy.core.manage.MetadataState
import io.github.arkmind.seedy.core.manage.MetadataViewModel
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.AdminAvatar
import io.github.arkmind.seedy.mobile.admin.AccentRow
import io.github.arkmind.seedy.mobile.admin.BoardField
import io.github.arkmind.seedy.mobile.admin.ChipFlow
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.FieldSection
import io.github.arkmind.seedy.mobile.admin.LeaveGuard
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.admin.SearchPickSheet
import io.github.arkmind.seedy.mobile.admin.TabPills
import io.github.arkmind.seedy.mobile.admin.TextSheet
import io.github.arkmind.seedy.mobile.admin.avatarColour
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SectionFoot
import io.github.arkmind.seedy.mobile.settings.Segmented
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Edit metadata (board ItemEditMetadata) as a phone page, for admins. */
@Composable
fun MetadataRoute(id: String, onBack: () -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "metadata:$id") { MetadataViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val poster = session.api.baseUrl.orEmpty().trimEnd('/') + "/Items/$id/Images/Primary?maxWidth=160&quality=90"
    MetadataScreen(state, vm, poster, onBack)
}

@Composable
fun MetadataScreen(state: MetadataState, actions: MetadataActions, poster: String?, onBack: () -> Unit) {
    var message by remember { mutableStateOf<String?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }
    val back = LeaveGuard(state.dirty, t("Your changes to {name} aren’t saved yet.", "name" to state.name), onBack)
    SettingsPage(
        t("Edit metadata"),
        lede = null,
        onBack = back,
        overlay = {
            if (!state.loading && !state.failed) MetaFooter(state, actions) { actions.save { message = it } }
            Toast(message ?: state.error?.takeIf { !state.failed }, { message = null }, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            MetaSheets(sheet, state, actions) { sheet = null }
        },
    ) {
        ItemHead(poster, state.name.ifEmpty { "…" }, state.facts)
        when {
            state.failed -> ErrorCard(t("Couldn’t load this title"), actions::retry, Modifier.padding(top = 16.dp), state.error.orEmpty())
            state.loading -> LoadingCards(3)
            else -> {
                TabPills(
                    listOf(
                        MetaTab.Details to t("Details"),
                        MetaTab.People to t("Cast and crew · {n}", "n" to state.people.size),
                        MetaTab.Providers to t("Provider IDs"),
                        MetaTab.Display to t("Display and language"),
                    ),
                    state.tab, actions::tab,
                )
                when (state.tab) {
                    MetaTab.Details -> Details(state, actions) { sheet = it }
                    MetaTab.People -> People(state, actions) { sheet = it }
                    MetaTab.Providers -> FieldSection(null, foot = t("The ids the providers know this title by. Identify finds them for you.")) {
                        for (p in state.providers) {
                            MetaField(p.label) {
                                BoardField(p.value, { v -> actions.edit { s -> s.copy(providers = s.providers.map { if (it.key == p.key) it.copy(value = v) else it }) } }, placeholder = t("None"))
                            }
                        }
                    }
                    MetaTab.Display -> FieldSection(null, foot = t("What the providers are asked for when this title refreshes.")) {
                        MetaField(t("Metadata language")) { SelectField(state.languages.firstOrNull { it.value == state.language }?.label ?: state.language, t("Library default")) { sheet = "language" } }
                        MetaField(t("Country")) { SelectField(state.countries.firstOrNull { it.value == state.country }?.label ?: state.country, t("Library default")) { sheet = "country" } }
                        if (state.isSeries) {
                            MetaField(t("Episode order")) {
                                SelectField(t(MetadataViewModel.displayOrders.firstOrNull { it.first == state.displayOrder }?.second ?: "Aired"), "") { sheet = "order" }
                            }
                        }
                    }
                }
                SaveBarRoom()
            }
        }
    }
}

/** The poster, name and "Series · path · last refreshed" over the page. */
@Composable
fun ItemHead(poster: String?, name: String, facts: String) {
    Row(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(poster, Modifier.size(44.dp, 66.dp).clip(RoundedCornerShape(8.dp)), placeholder = SeedyColors.Surface)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SText(name, size = 20.sp, weight = 900, lineHeight = 1.2f, color = SeedyColors.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (facts.isNotEmpty()) SText(facts, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3, maxLines = 3)
        }
    }
}

@Composable
private fun Details(state: MetadataState, actions: MetadataActions, open: (String) -> Unit) {
    FieldSection(null) {
        MetaField(t("Title"), "Name", state, actions) { BoardField(state.title, { v -> actions.edit { it.copy(title = v) } }, autoCorrect = true) }
        MetaField(t("Original title")) { BoardField(state.originalTitle, { v -> actions.edit { it.copy(originalTitle = v) } }, autoCorrect = true) }
        MetaField(t("Sort title")) { BoardField(state.sortTitle, { v -> actions.edit { it.copy(sortTitle = v) } }, placeholder = state.title) }
    }
    FieldSection(null) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetaField(state.dateLabel, modifier = Modifier.weight(1f)) { DateField(state.premiere, t("Unknown")) { open("premiere") } }
            if (state.isSeries) MetaField(t("Ended"), modifier = Modifier.weight(1f)) { DateField(state.ended, t("Still airing")) { open("ended") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.isSeries) MetaField(t("Status"), modifier = Modifier.weight(1f)) { SelectField(statusLabel(state.status), t("Unknown")) { open("status") } }
            MetaField(t("Age rating"), "OfficialRating", state, actions, Modifier.weight(1f)) { SelectField(state.rating, t("None")) { open("rating") } }
        }
    }
    FieldSection(null) {
        MetaField(t("Overview"), "Overview", state, actions) { BoardField(state.overview, { v -> actions.edit { it.copy(overview = v) } }, lines = 6, autoCorrect = true) }
    }
    FieldSection(null) {
        MetaField(t("Genres"), "Genres", state, actions) {
            ChipFlow(state.genres, { i -> actions.edit { s -> s.copy(genres = s.genres.filterIndexed { j, _ -> j != i }) } }, t("Add genre"), { open("genre") })
        }
        MetaField(t("Tags"), "Tags", state, actions) {
            ChipFlow(state.tags, { i -> actions.edit { s -> s.copy(tags = s.tags.filterIndexed { j, _ -> j != i }) } }, t("Add tag"), { open("tag") })
        }
        MetaField(t("Studios and networks"), "Studios", state, actions) {
            ChipFlow(state.studios, { i -> actions.edit { s -> s.copy(studios = s.studios.filterIndexed { j, _ -> j != i }) } }, t("Add"), { open("studio") })
        }
    }
    FieldSection(null, foot = t("Fields you edit lock on their own, so a refresh won’t undo them.")) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetaField(t("Community rating"), modifier = Modifier.weight(1f)) {
                BoardField(state.community, { v -> actions.edit { it.copy(community = v) } }, placeholder = t("None"), keyboard = KeyboardType.Decimal)
            }
            MetaField(t("Critic rating"), modifier = Modifier.weight(1f)) {
                BoardField(state.critic, { v -> actions.edit { it.copy(critic = v) } }, placeholder = t("None"), keyboard = KeyboardType.Decimal)
            }
        }
    }
}

@Composable
private fun People(state: MetadataState, actions: MetadataActions, open: (String) -> Unit) {
    Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 8.dp).fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        SText(t("Cast and crew"), Modifier.weight(1f), size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        LockChip("Cast" in state.locked, "Cast" in state.locked || "Cast" in state.toggled) { actions.toggleLock("Cast") }
    }
    Card(Modifier.padding(horizontal = 16.dp)) {
        state.people.forEachIndexed { i, p ->
            if (i > 0) RowLine()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AdminAvatar(p.name, avatarColour(i), 36.dp, 14.sp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    SText(p.name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SText(listOf(p.role, t(personType(p.type))).filter { it.isNotBlank() }.joinToString(" · "), size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(40.dp).clip(CircleShape).tap { actions.edit { s -> s.copy(people = s.people.filterIndexed { j, _ -> j != i }) } }, contentAlignment = Alignment.Center) {
                    SIcon(WebIcons.Close, size = 14.dp, color = SeedyColors.Text2, stroke = 2.4f)
                }
            }
        }
        if (state.people.isNotEmpty()) RowLine()
        AccentRow(t("Add someone"), { open("person") })
    }
}

/** A field's name (with its lock when Jellyfin can lock it) over the field. */
@Composable
private fun MetaField(
    label: String,
    lock: String? = null,
    state: MetadataState? = null,
    actions: MetadataActions? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldLabel(label, Modifier.weight(1f))
            if (lock != null && state != null && actions != null) {
                val locked = lock in state.locked
                LockChip(locked, locked || lock in state.toggled) { actions.toggleLock(lock) }
            }
        }
        content()
    }
}

/** "Locked" (in the accent) on a field a refresh won't change; tapping it unlocks it. */
@Composable
fun LockChip(locked: Boolean, shown: Boolean, onClick: () -> Unit) {
    if (!shown) return
    val accent = LocalAccent.current
    val ink = if (locked) accent else SeedyColors.Text3
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(7.dp)).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)).tap(onClick = onClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SIcon(if (locked) ManageIcons.Lock else ManageIcons.Unlock, size = 12.dp, color = ink)
        SText(if (locked) t("Locked") else t("Unlocked"), size = 11.sp, weight = 800, color = ink, maxLines = 1)
    }
}

/** A field that opens a list: the value (or [placeholder] in grey) and a chevron. */
@Composable
fun SelectField(value: String, placeholder: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).tap(onClick = onClick).padding(start = 14.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SText(value.ifEmpty { placeholder }, Modifier.weight(1f), size = 15.sp, weight = 600, color = if (value.isEmpty()) SeedyColors.Text3 else SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        SIcon(WebIcons.ChevronDown, size = 16.dp, color = SeedyColors.Text2, stroke = 2.2f)
    }
}

/** A date field ("1 Mar 2024"), or [placeholder] when there's none. */
@Composable
fun DateField(date: LocalDate?, placeholder: String, onClick: () -> Unit) = SelectField(date?.let(::fullDate).orEmpty(), placeholder, onClick)

private fun statusLabel(status: String): String = when (status) {
    "Continuing" -> t("Continuing")
    "Ended" -> t("Ended")
    else -> status
}

private fun personType(type: String): String = when (type) {
    "Actor" -> "Actor"
    "Director" -> "Director"
    "Writer" -> "Writer"
    "Producer" -> "Producer"
    "GuestStar" -> "Guest star"
    "Composer" -> "Composer"
    "Creator" -> "Creator"
    else -> type
}

/** The pinned foot: Lock all fields, and Save changes. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MetaFooter(state: MetadataState, actions: MetadataActions, onSave: () -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .boxShadow(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = (-10).dp)
            .background(SeedyColors.Row).tap {}
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = max(26.dp, nav + 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BoardSwitch(state.lockAll, actions::setLockAll)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(t("Lock all fields"), size = 13.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(if (state.dirty) t("Not saved yet") else t("Nothing changed"), size = 12.sp, color = if (state.dirty) SeedyColors.Pending else SeedyColors.Text3, maxLines = 1)
        }
        PillButton(if (state.saving) t("Saving…") else t("Save changes"), onSave, style = PillStyle.Light, enabled = state.dirty && !state.saving, height = 44.dp,
            leading = { SIcon(WebIcons.Check, size = 15.dp, color = SeedyColors.InkInverse, stroke = 2.8f) })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaSheets(sheet: String?, state: MetadataState, actions: MetadataActions, close: () -> Unit) {
    when (sheet) {
        "genre" -> TextSheet(t("Add genre"), null, t("Add"), { v -> actions.edit { s -> s.copy(genres = (s.genres + v.trim()).distinct()) } }, close)
        "tag" -> TextSheet(t("Add tag"), null, t("Add"), { v -> actions.edit { s -> s.copy(tags = (s.tags + v.trim()).distinct()) } }, close)
        "studio" -> TextSheet(t("Add a studio or network"), null, t("Add"), { v -> actions.edit { s -> s.copy(studios = (s.studios + v.trim()).distinct()) } }, close)
        "status" -> SearchPickSheet(t("Status"), null, listOf(Choice("Continuing", t("Continuing")), Choice("Ended", t("Ended")), Choice("", t("Unknown"))), state.status, { v -> actions.edit { it.copy(status = v) } }, close)
        "rating" -> SearchPickSheet(t("Age rating"), null, listOf(Choice("", t("None"))) + state.ratings.map { Choice(it, it) }, state.rating, { v -> actions.edit { it.copy(rating = v) } }, close)
        "language" -> SearchPickSheet(t("Metadata language"), null, listOf(Choice("", t("Library default"))) + state.languages, state.language, { v -> actions.edit { it.copy(language = v) } }, close, searchLabel = t("Find a language"))
        "country" -> SearchPickSheet(t("Country"), null, listOf(Choice("", t("Library default"))) + state.countries, state.country, { v -> actions.edit { it.copy(country = v) } }, close, searchLabel = t("Find a country"))
        "order" -> SearchPickSheet(t("Episode order"), null, MetadataViewModel.displayOrders.map { Choice(it.first, t(it.second)) }, state.displayOrder, { v -> actions.edit { it.copy(displayOrder = v) } }, close)
        "person" -> PersonSheet({ p -> actions.edit { s -> s.copy(people = s.people + p) } }, close)
        "premiere", "ended" -> {
            val current = if (sheet == "premiere") state.premiere else state.ended
            val picker = rememberDatePickerState(initialSelectedDateMillis = current?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
            val set = { d: LocalDate? -> actions.edit { if (sheet == "premiere") it.copy(premiere = d) else it.copy(ended = d) } }
            val colors = DatePickerDefaults.colors(containerColor = SeedyColors.Panel)
            DatePickerDialog(
                onDismissRequest = close,
                confirmButton = { PillButton(t("Done"), { set(picker.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }); close() }, style = PillStyle.Light) },
                dismissButton = { PillButton(t("Clear"), { set(null); close() }) },
                colors = colors,
            ) { DatePicker(picker, colors = colors) }
        }
    }
}

/** Someone to add to the cast and crew: name, role, and what they did. */
@Composable
private fun PersonSheet(onAdd: (MetaPerson) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("Actor") }
    FormSheet(t("Add someone"), t("To the cast and crew of this title."), onDismiss) {
        BoardField(name, { name = it }, placeholder = t("Name"), autoCorrect = false)
        BoardField(role, { role = it }, placeholder = t("Role (optional)"))
        Segmented(listOf(Choice("Actor", t("Actor")), Choice("Director", t("Director")), Choice("Writer", t("Writer")), Choice("Producer", t("Producer"))), type, { type = it })
        io.github.arkmind.seedy.mobile.admin.SheetButtons(t("Add"), { onAdd(MetaPerson(name.trim(), role.trim(), type)); onDismiss() }, onDismiss, enabled = name.isNotBlank())
    }
}
