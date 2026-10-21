package io.github.arkmind.seedy.mobile.admin.system

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.system.CatalogRow
import io.github.arkmind.seedy.core.admin.system.Dot
import io.github.arkmind.seedy.core.admin.system.PluginRow
import io.github.arkmind.seedy.core.admin.system.PluginsActions
import io.github.arkmind.seedy.core.admin.system.PluginsState
import io.github.arkmind.seedy.core.admin.system.PluginsView
import io.github.arkmind.seedy.core.admin.system.RepoRow
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.EmptyCard
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.MenuItem
import io.github.arkmind.seedy.mobile.admin.MenuSheet
import io.github.arkmind.seedy.mobile.admin.NoticeCard
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillRow
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.RoundButton
import io.github.arkmind.seedy.mobile.admin.SheetButtons
import io.github.arkmind.seedy.mobile.admin.SkeletonCard
import io.github.arkmind.seedy.mobile.admin.StatusLine
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.BoardSwitch
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.Faint
import io.github.arkmind.seedy.mobile.settings.FootNote
import io.github.arkmind.seedy.mobile.settings.FormSheet
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SectionTitle
import io.github.arkmind.seedy.mobile.settings.Segmented
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * Plugins, built from the phone boards' kit with the web board "Server · Plugins": what's installed (update, enable,
 * disable, uninstall, its settings page), the catalog to install from, and the repositories it comes from.
 */
@Composable
fun PluginsScreen(state: PluginsState, actions: PluginsActions, onBack: () -> Unit, openUrl: (String) -> Unit, openSeedy: () -> Unit) {
    var menuFor by remember { mutableStateOf<PluginRow?>(null) }
    var uninstalling by remember { mutableStateOf<PluginRow?>(null) }
    var removing by remember { mutableStateOf<RepoRow?>(null) }
    var adding by remember { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    SettingsPage(
        t("Plugins"),
        lede = state.lede,
        onBack = onBack,
        overlay = {
            menuFor?.let { p ->
                MenuSheet(p.name, p.menu.map { m ->
                    MenuItem(m.label, m.sub, m.danger, m.enabled) {
                        when (m.key) {
                            "update" -> actions.update(p.id)
                            "seedy" -> openSeedy()
                            "enable" -> actions.setEnabled(p.id, true)
                            "disable" -> actions.setEnabled(p.id, false)
                            "settings" -> p.settingsUrl?.let(openUrl)
                            "uninstall" -> uninstalling = p
                        }
                    }
                }, { menuFor = null }, text = listOf(p.version, p.source).filter { it.isNotEmpty() }.joinToString(" · "))
            }
            uninstalling?.let { p ->
                ConfirmSheet(
                    t("Uninstall {plugin}?", "plugin" to p.name),
                    t("Jellyfin removes {plugin} the next time the server restarts. Its saved settings stay on the server in case you install it again.", "plugin" to p.name),
                    t("Uninstall"), { actions.uninstall(p.id) }, { uninstalling = null }, danger = true,
                )
            }
            removing?.let { r ->
                ConfirmSheet(
                    t("Remove {repository}?", "repository" to r.name),
                    t("Its plugins leave the catalog and stop getting updates. Plugins you already installed from it stay installed."),
                    t("Remove"), { actions.removeRepo(r.index) }, { removing = null }, danger = true,
                )
            }
            if (restarting) {
                ConfirmSheet(
                    t("Restart the server?"),
                    t("Everyone watching is interrupted for a minute while Jellyfin restarts. Seedy reconnects on its own when the server is back."),
                    t("Restart"), actions::restart, { restarting = false },
                )
            }
            if (adding) AddRepositorySheet(state.savingRepos, actions, onClose = { adding = false })
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter))
        },
    ) {
        when {
            state.view == PluginsView.Installed && state.updates > 0 -> PillRow {
                PillButton(if (state.updatingAll) t("Updating…") else t("Update all"), actions::updateAll, style = PillStyle.Light, enabled = !state.updatingAll)
            }
            state.view == PluginsView.Repositories && state.repos != null -> PillRow {
                PillButton(t("Add repository"), { adding = true }, style = PillStyle.Light, leading = { SIcon(WebIcons.Plus, size = 15.dp, color = SeedyColors.InkInverse) })
            }
        }
        Segmented(
            listOf(Choice(PluginsView.Installed, t("Installed")), Choice(PluginsView.Catalog, t("Catalog")), Choice(PluginsView.Repositories, t("Repositories"))),
            state.view, actions::setView, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), size = 13,
            count = {
                when (it) {
                    PluginsView.Installed -> state.rows?.size?.toString()
                    PluginsView.Catalog -> null
                    PluginsView.Repositories -> state.repos?.size?.toString()
                }
            },
        )
        if (state.restart > 0) {
            NoticeCard(
                t("Restart the server to finish."),
                tn(state.restart, "One plugin change takes effect after a restart.", "{n} plugin changes take effect after a restart."),
                if (state.restarting) t("Restarting…") else t("Restart"),
                SeedyColors.Pending, SeedyColors.Panel, { if (!state.restarting) restarting = true },
                Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp),
            )
        }
        when (state.view) {
            PluginsView.Installed -> Installed(state, actions, onMenu = { menuFor = it })
            PluginsView.Catalog -> Catalog(state, actions)
            PluginsView.Repositories -> Repositories(state, actions, onRemove = { removing = it })
        }
    }
}

@Composable
private fun Installed(state: PluginsState, actions: PluginsActions, onMenu: (PluginRow) -> Unit) {
    val rows = state.rows
    Box(Modifier.padding(top = 14.dp)) {
        when {
            rows == null && state.installedError != null -> ErrorCard(state.installedError!!, actions::retry)
            rows == null -> SkeletonCard(5, tile = true)
            rows.isEmpty() -> EmptyCard(t("No plugins installed"), t("Add metadata providers, subtitle sources and more from the Catalog."))
            else -> Card(Modifier.padding(horizontal = 16.dp)) {
                rows.forEachIndexed { i, p ->
                    if (i > 0) RowLine()
                    InstalledRow(p, onUpdate = { actions.update(p.id) }, onMenu = { onMenu(p) })
                }
            }
        }
    }
    FootNote(t("A plugin's own settings page comes from the plugin itself and opens in Jellyfin's web app."))
}

@Composable
private fun InstalledRow(p: PluginRow, onUpdate: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().tap(enabled = p.busy == null, onClick = onMenu).padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp).heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LetterTile(p.letters, light = p.self)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(p.name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Quiet(listOf(p.version, p.source).filter { it.isNotEmpty() }.joinToString(" · "))
            StatusLine(p.busy ?: p.status, dotColor(p.dot), Modifier.padding(top = 2.dp))
            p.note?.let { note -> SText(note, Modifier.padding(top = 2.dp), size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Pending) }
        }
        if (p.update != null) PillButton(t("Update"), onUpdate, style = PillStyle.Light, enabled = p.busy == null)
        else RoundButton(AdminIcons.Dots, onMenu, iconSize = 18.dp, enabled = p.busy == null)
    }
}

@Composable
private fun Catalog(state: PluginsState, actions: PluginsActions) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SearchBox(state.search, actions::setSearch, t("Search plugins"), trailing = if (state.search.isEmpty()) null else ({
            Box(Modifier.padding(end = 4.dp).tap { actions.setSearch("") }.padding(8.dp)) { SIcon(WebIcons.Close, size = 16.dp, color = SeedyColors.Text2) }
        }))
        state.catalogCount?.let { SText(it, Modifier.padding(horizontal = 4.dp), size = 12.sp, color = SeedyColors.Text3) }
    }
    val groups = state.catalog
    when {
        groups == null && state.catalogError != null -> Box(Modifier.padding(top = 14.dp)) { ErrorCard(state.catalogError!!, actions::retry) }
        groups == null -> {
            SectionTitle(" ")
            SkeletonCard(5, tile = true)
        }
        groups.isEmpty() -> Box(Modifier.padding(top = 14.dp)) {
            val term = state.search.trim()
            EmptyCard(
                if (term.isNotEmpty()) t("Nothing matches “{term}”", "term" to term) else t("The catalog is empty"),
                if (term.isNotEmpty()) t("Try a shorter search, or add the repository that offers the plugin.") else t("Turn on a repository under Repositories to see its plugins here."),
            )
        }
        else -> for (g in groups) {
            SectionTitle(g.label)
            Card(Modifier.padding(horizontal = 16.dp)) {
                g.rows.forEachIndexed { i, p ->
                    if (i > 0) RowLine()
                    CatalogLine(p) { actions.install(p.guid) }
                }
            }
        }
    }
    FootNote(t("Plugins run inside the server with full access to it. Install from repositories you trust; a restart loads new plugins."))
}

@Composable
private fun CatalogLine(p: CatalogRow, onInstall: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LetterTile(p.letters)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(p.name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (p.meta.isNotEmpty()) Quiet(p.meta, color = SeedyColors.Text3)
            if (p.desc.isNotEmpty()) SText(p.desc, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            p.note?.let { note -> SText(note, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Pending) }
        }
        when {
            p.status != null -> StatusLine(p.status!!, dotColor(p.dot))
            p.canInstall -> PillButton(if (p.installing) t("Installing…") else t("Install"), onInstall, style = PillStyle.Light, enabled = !p.installing)
        }
    }
}

@Composable
private fun Repositories(state: PluginsState, actions: PluginsActions, onRemove: (RepoRow) -> Unit) {
    val repos = state.repos
    Box(Modifier.padding(top = 14.dp)) {
        when {
            repos == null && state.reposError != null -> ErrorCard(state.reposError!!, actions::retry)
            repos == null -> SkeletonCard(2, tile = true)
            repos.isEmpty() -> EmptyCard(t("No repositories"), t("The catalog stays empty and plugins don’t get updates until you add one.")) {
                PillButton(t("Add Jellyfin’s repository"), actions::addJellyfinRepo, enabled = !state.savingRepos)
            }
            else -> Card(Modifier.padding(horizontal = 16.dp)) {
                repos.forEachIndexed { i, r ->
                    if (i > 0) RowLine()
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        LetterTile(r.letters)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SText(r.name, Modifier.weight(1f, fill = false), size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                SText(r.source, size = 11.sp, color = SeedyColors.Text3, maxLines = 1)
                            }
                            Quiet(r.url)
                        }
                        BoardSwitch(r.enabled, { on -> actions.setRepoEnabled(r.index, on) }, enabled = !state.savingRepos)
                        RoundButton(WebIcons.Trash, { onRemove(r) }, iconSize = 18.dp, bare = true, enabled = !state.savingRepos, tint = SeedyColors.Text2)
                    }
                }
            }
        }
    }
    FootNote(t("Jellyfin checks each repository for new versions once a day. Plugins from other repositories aren’t reviewed by the Jellyfin team."))
}

@Composable
private fun AddRepositorySheet(saving: Boolean, actions: PluginsActions, onClose: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun submit() {
        busy = true
        actions.addRepo(name, link) { problem ->
            busy = false
            if (problem == null) onClose() else error = problem
        }
    }
    FormSheet(t("Add repository"), t("Paste the address of the repository’s manifest.json. Its author lists it with their install instructions."), onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(t("Name"))
            SeedyField(name, { name = it.take(80); error = "" }, placeholder = "Intro Skipper")
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel(t("Manifest address"))
            SeedyField(link, { link = it; error = "" }, placeholder = "https://example.com/manifest.json", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri, onDone = ::submit)
        }
        if (error.isNotEmpty()) SText(error, size = 13.sp, color = SeedyColors.Danger)
        SheetButtons(if (busy || saving) t("Adding…") else t("Add repository"), ::submit, onClose, Modifier.padding(top = 4.dp), enabled = !busy && !saving)
    }
}

/** A plugin's or repository's 44 px tile with its letters; Seedy's is light. */
@Composable
private fun LetterTile(letters: String, light: Boolean = false) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(if (light) SeedyColors.Inverse else SeedyColors.Surface), contentAlignment = Alignment.Center) {
        SText(letters, size = 15.sp, weight = 900, color = if (light) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
    }
}

private fun dotColor(dot: Dot): Color = when (dot) {
    Dot.Ok -> SeedyColors.Ok
    Dot.Pending -> SeedyColors.Pending
    Dot.Muted -> Faint
    Dot.Danger -> SeedyColors.Danger
}
