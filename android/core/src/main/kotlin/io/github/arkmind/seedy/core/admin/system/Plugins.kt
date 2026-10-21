package io.github.arkmind.seedy.core.admin.system

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.admin.PackageInfo
import io.github.arkmind.seedy.core.admin.PackageVersion
import io.github.arkmind.seedy.core.admin.PluginInfo
import io.github.arkmind.seedy.core.admin.SeedyJson
import io.github.arkmind.seedy.core.admin.SeedyPluginId
import io.github.arkmind.seedy.core.admin.SystemInfo
import io.github.arkmind.seedy.core.admin.cmpVersion
import io.github.arkmind.seedy.core.admin.dedupePlugins
import io.github.arkmind.seedy.core.admin.errorText
import io.github.arkmind.seedy.core.admin.latestCompatible
import io.github.arkmind.seedy.core.admin.majorMinor
import io.github.arkmind.seedy.core.admin.normId
import io.github.arkmind.seedy.core.admin.shortVersion
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URI
import java.net.URLEncoder

/*
 * Plugins (web/src/screens/server/Plugins.tsx, PluginViews.tsx, plugins.ts; web board "Server · Plugins"): what's
 * installed with update, enable, disable and uninstall; the repositories' catalog to install from; and the
 * repositories themselves.
 */

enum class PluginsView { Installed, Catalog, Repositories }

/** A status dot: green, pending (restart, won't load), grey (disabled, going away) or red. */
enum class Dot { Ok, Pending, Muted, Danger }

/** What a plugin's ⋯ sheet offers; [key] is "update", "seedy", "enable", "disable", "settings" or "uninstall". */
data class PluginMenuItem(val key: String, val label: String, val sub: String? = null, val danger: Boolean = false, val enabled: Boolean = true)

data class PluginRow(
    val id: String,
    val name: String,
    /** The tile's letters ("OS", "S" for Seedy). */
    val letters: String,
    val self: Boolean,
    /** "Update available", "Installed with 12.2", "Up to date", "Built for 10.11", "0.1.0". */
    val version: String,
    /** "This interface · Seedy repository", "Jellyfin", "Third party". */
    val source: String,
    val status: String,
    val dot: Dot,
    /** Why it won't load and what to do. */
    val note: String? = null,
    /** The version an update installs. */
    val update: String? = null,
    /** "Updating…", "Disabling…" while that happens. */
    val busy: String? = null,
    /** Its settings page in Jellyfin's web app. */
    val settingsUrl: String? = null,
    val menu: List<PluginMenuItem> = emptyList(),
)

data class CatalogRow(
    val guid: String,
    val name: String,
    val letters: String,
    /** "12.0.0 · Intro Skipper", "Built for 10.11". */
    val meta: String,
    val desc: String,
    val note: String? = null,
    /** "Installed", "Restart required"… when it's installed. */
    val status: String? = null,
    val dot: Dot = Dot.Ok,
    val canInstall: Boolean = false,
    val installing: Boolean = false,
)

data class CatalogGroup(val label: String, val rows: List<CatalogRow>)

data class RepoRow(val index: Int, val name: String, val letters: String, val source: String, val url: String, val enabled: Boolean)

data class PluginsState(
    val view: PluginsView = PluginsView.Installed,
    val lede: String = "",
    /** Null while loading. */
    val rows: List<PluginRow>? = null,
    val installedError: String? = null,
    val updates: Int = 0,
    val updatingAll: Boolean = false,
    /** Plugin changes waiting for a restart. */
    val restart: Int = 0,
    val restarting: Boolean = false,
    val catalog: List<CatalogGroup>? = null,
    val catalogError: String? = null,
    /** "36 plugins from 1 repository", "3 matches". */
    val catalogCount: String? = null,
    val search: String = "",
    val repos: List<RepoRow>? = null,
    val reposError: String? = null,
    val savingRepos: Boolean = false,
    val message: String? = null,
)

interface PluginsActions {
    fun setView(view: PluginsView) {}
    fun retry() {}
    fun update(id: String) {}
    fun updateAll() {}
    fun setEnabled(id: String, on: Boolean) {}
    fun uninstall(id: String) {}
    fun install(guid: String) {}
    fun setSearch(text: String) {}
    fun setRepoEnabled(index: Int, on: Boolean) {}
    fun removeRepo(index: Int) {}
    /** Adds a repository; [done] gets the problem to show under the form, or null once it's saved. */
    fun addRepo(name: String, url: String, done: (String?) -> Unit) {}
    fun addJellyfinRepo() {}
    fun restart() {}
    fun messageShown() {}
}

/* ── What each row says (plugins.ts) ── */

fun isJellyfinRepo(url: String?): Boolean {
    if (url.isNullOrEmpty()) return false
    val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: return false
    return host == "jellyfin.org" || host.endsWith(".jellyfin.org")
}

internal class PluginView(
    val plugin: PluginInfo,
    val self: Boolean,
    val pkg: PackageInfo?,
    val update: PackageVersion?,
    val wontLoad: Boolean,
    val builtIn: Boolean,
    val page: ConfigPage?,
)

internal fun describe(p: PluginInfo, packages: List<PackageInfo>?, pages: List<ConfigPage>?, server: String): PluginView {
    val id = normId(p.id)
    val pkg = packages?.firstOrNull { normId(it.guid) == id }
    val latest = pkg?.let { latestCompatible(it, server) }
    val update = latest?.takeIf { cmpVersion(it.version, p.version) > 0 && p.status != "Deleted" }
    val own = pages.orEmpty().filter { normId(it.pluginId) == id }
    val page = own.firstOrNull { it.name == p.name } ?: own.firstOrNull { it.enableInMainMenu } ?: own.firstOrNull()
    return PluginView(p, id == SeedyPluginId, pkg, update, p.status == "NotSupported" || p.status == "Malfunctioned", !p.canUninstall && id != SeedyPluginId, page)
}

internal fun versionLabel(v: PluginView, server: String, abi: String?): String {
    val ver = shortVersion(v.plugin.version)
    return when {
        v.self -> ver
        v.wontLoad -> if (abi != null) t("Built for {version}", "version" to majorMinor(abi)) else ver
        v.update != null -> t("Update available")
        v.builtIn -> t("Installed with {version}", "version" to majorMinor(server).ifEmpty { ver })
        v.pkg != null -> t("Up to date")
        else -> ver
    }
}

internal fun sourceLabel(v: PluginView): String {
    val repo = v.pkg?.versions?.firstOrNull { cmpVersion(it.version, v.plugin.version) == 0 } ?: v.pkg?.versions?.firstOrNull()
    return when {
        v.self -> if (!repo?.repositoryName.isNullOrEmpty()) t("This interface · {repo}", "repo" to repo!!.repositoryName) else t("This interface")
        v.builtIn || isJellyfinRepo(repo?.repositoryUrl) -> "Jellyfin"
        else -> t("Third party")
    }
}

fun pluginStatus(status: String): Pair<String, Dot> = when (status) {
    "Active" -> t("Active") to Dot.Ok
    "Restart" -> t("Restart required") to Dot.Pending
    "Disabled" -> t("Disabled") to Dot.Muted
    "NotSupported", "Malfunctioned" -> t("Won’t load") to Dot.Pending
    "Deleted" -> t("Removed on restart") to Dot.Muted
    else -> t("Replaced on restart") to Dot.Muted
}

fun needsRestart(p: PluginInfo): Boolean = p.status == "Restart" || p.status == "Deleted" || p.status == "Superceded" || p.status == "Superseded"

/** "Open Subtitles" → "OS", "TMDb" → "TM", "Fanart" → "FA". */
fun initials(name: String): String {
    val words = name.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return "?"
    if (words.size > 1) return (words[0].take(1) + words[1].take(1)).uppercase()
    val w = words[0]
    val cap = w.drop(1).firstOrNull { it.isUpperCase() }
    if (cap != null && w[0].isUpperCase()) return "${w[0]}$cap".uppercase()
    return w.take(2).uppercase()
}

private val CategoryLabels = mapOf(
    "MoviesAndShows" to "Movies and shows", "LiveTV" to "Live TV", "Anime" to "Anime", "Books" to "Books", "General" to "General",
    "Administration" to "Administration", "Music" to "Music", "Subtitles" to "Subtitles", "Metadata" to "Metadata",
    "Authentication" to "Authentication", "Channels" to "Channels", "Notifications" to "Notifications",
)

fun categoryLabel(c: String?): String = when {
    c.isNullOrEmpty() -> t("Other")
    CategoryLabels[c] != null -> t(CategoryLabels.getValue(c))
    else -> c.replace(Regex("([a-z])([A-Z])"), "$1 $2")
}

private fun plural(n: Int, one: String, many: String = one + "s") = tn(n, "{n} $one", "{n} $many")

private val JellyfinStable = RepositoryInfo("Jellyfin Stable", "https://repo.jellyfin.org/files/plugin/manifest.json", true)

class PluginsViewModel(private val session: UserSession) : ViewModel(), PluginsActions {
    private val http = AdminHttp(session.api)
    private val _state = MutableStateFlow(PluginsState(lede = t("Reading the installed plugins…")))
    val state: StateFlow<PluginsState> = _state.asStateFlow()

    private var plugins: List<PluginInfo>? = null
    private var pages: List<ConfigPage>? = null
    private var repos: List<RepositoryInfo>? = null
    private var packages: List<PackageInfo>? = null
    private var server = ""
    private var installedFailed = false
    private val busy = mutableMapOf<String, String>()
    private val installing = mutableSetOf<String>()
    /** Target versions read from plugin manifests ("Built for 10.11"). */
    private val abis = mutableMapOf<String, String>()

    fun load() {
        loadInstalled()
        loadCatalog()
    }

    private fun loadInstalled() {
        viewModelScope.launch {
            try {
                coroutineScope {
                    val list = async { http.get<List<PluginInfo>>("/Plugins") }
                    val pg = async { runCatching { http.get<List<ConfigPage>>("/web/ConfigurationPages") }.getOrDefault(emptyList()) }
                    val rp = async { runCatching { http.get<List<RepositoryInfo>>("/Repositories") }.getOrNull() }
                    val info = async { if (server.isEmpty()) runCatching { http.get<SystemInfo>("/System/Info").version }.getOrDefault("") else server }
                    plugins = dedupePlugins(list.await())
                    pages = pg.await()
                    repos = rp.await() ?: repos
                    server = info.await()
                }
                installedFailed = false
                if (repos == null) _state.update { it.copy(reposError = t("Couldn’t load the repositories.")) }
                readAbis()
            } catch (e: Throwable) {
                rethrowCancel(e)
                installedFailed = true
                _state.update { it.copy(installedError = t("Couldn’t load the plugins.") + " " + errorText(e)) }
            }
            publish()
        }
    }

    private fun loadCatalog() {
        _state.update { it.copy(catalogError = null) }
        viewModelScope.launch {
            runCatching { http.get<List<PackageInfo>>("/Packages", json = SeedyJson) }
                .onSuccess { packages = it; readAbis() }
                .onFailure { rethrowCancel(it); _state.update { s -> s.copy(catalogError = t("Couldn’t load the catalog.") + " " + errorText(it)) } }
            publish()
        }
    }

    /** "Built for 10.11": a plugin that won't load says which Jellyfin it targets in its catalog entry or manifest. */
    private fun readAbis() {
        for (p in plugins.orEmpty().filter { it.status == "NotSupported" || it.status == "Malfunctioned" }) {
            if (abis[p.id] != null) continue
            val fromCatalog = packages?.firstOrNull { normId(it.guid) == normId(p.id) }?.versions?.firstOrNull { it.version == p.version }?.targetAbi
            if (fromCatalog != null) {
                abis[p.id] = fromCatalog
                continue
            }
            viewModelScope.launch {
                runCatching { http.post<PluginManifest>("/Plugins/${p.id}/Manifest") }.onSuccess { m -> m.targetAbi?.let { abis[p.id] = it; publish() } }.onFailure(::rethrowCancel)
            }
        }
    }

    private fun views(): List<PluginView> {
        val rank = { v: PluginView -> if (v.self) 0 else if (v.update != null) 1 else if (v.wontLoad) 3 else 2 }
        return plugins.orEmpty().map { describe(it, packages, pages, server) }.sortedBy(rank)
    }

    private fun publish() {
        val list = plugins
        val all = views()
        val updates = all.count { it.update != null }
        val wontLoad = all.count { it.wontLoad }
        _state.update { st ->
            st.copy(
                lede = when {
                    list == null -> if (installedFailed) t("Couldn’t read the installed plugins.") else t("Reading the installed plugins…")
                    else -> listOfNotNull(
                        t("{n} installed", "n" to list.size),
                        packages?.let { if (updates > 0) plural(updates, "update") else t("all up to date") },
                        if (wontLoad > 0) t("{n} won't load on Jellyfin {version}", "n" to wontLoad, "version" to majorMinor(server)) else null,
                    ).joinToString(" · ")
                },
                rows = list?.let { all.map(::row) },
                installedError = if (list != null) null else st.installedError,
                updates = updates,
                restart = list.orEmpty().count(::needsRestart),
                catalog = packages?.let { catalogGroups(it, st.search) },
                catalogError = if (packages != null) null else st.catalogError,
                catalogCount = packages?.let { pk -> catalogCount(pk, st.search) },
                repos = repos?.mapIndexed { i, r -> RepoRow(i, r.name, initials(r.name.ifEmpty { "R" }), if (isJellyfinRepo(r.url)) "Jellyfin" else t("Third party"), r.url, r.enabled) },
                reposError = if (repos != null) null else st.reposError,
            )
        }
    }

    private fun configUrl(page: ConfigPage): String =
        session.api.baseUrl.orEmpty().trimEnd('/') + "/web/#/configurationpage?name=" + URLEncoder.encode(page.name, "UTF-8").replace("+", "%20")

    private fun row(v: PluginView): PluginRow {
        val p = v.plugin
        val abi = abis[p.id]
        val (status, dot) = pluginStatus(p.status)
        val mm = majorMinor(server)
        val note = if (!v.wontLoad) null
        else if (abi != null && majorMinor(abi) != mm) t("Plugins built for {abi} don’t load on {version}. Update it once its author ships a {version} build, or uninstall it.", "abi" to majorMinor(abi), "version" to mm)
        else t("This plugin failed to load on Jellyfin {version}. Check the logs, update it, or uninstall it.", "version" to mm)
        val canToggle = !v.self && p.status != "Deleted"
        val off = p.status == "Disabled"
        val canUninstall = !v.self && p.canUninstall && p.status != "Deleted"
        val settings = v.page?.takeIf { !v.self }?.let(::configUrl)
        val menu = buildList {
            if (v.update != null) add(PluginMenuItem("update", t("Update to {version}", "version" to shortVersion(v.update.version)), t("Restart the server to finish.")))
            if (v.self) {
                add(PluginMenuItem("seedy", t("Seedy settings"), t("Branding, home screen, requests")))
                add(PluginMenuItem("none", t("Can’t be disabled here"), t("Seedy is the app you’re using"), enabled = false))
            }
            if (canToggle) add(if (off) PluginMenuItem("enable", t("Enable"), t("Loads after a restart")) else PluginMenuItem("disable", t("Disable"), t("Unloads after a restart")))
            if (settings != null) add(PluginMenuItem("settings", t("Open settings"), t("Opens in Jellyfin’s web app")))
            if (canUninstall) add(PluginMenuItem("uninstall", t("Uninstall"), danger = true))
            if (!canUninstall && !v.self && v.builtIn) add(PluginMenuItem("none", t("Comes with Jellyfin"), t("Built-in plugins can’t be uninstalled"), enabled = false))
        }
        return PluginRow(
            id = p.id,
            name = p.name,
            letters = if (v.self) "S" else initials(p.name),
            self = v.self,
            version = versionLabel(v, server, abi),
            source = sourceLabel(v),
            status = status,
            dot = dot,
            note = note,
            update = v.update?.version,
            busy = busy[p.id],
            settingsUrl = settings,
            menu = menu,
        )
    }

    private fun catalogGroups(all: List<PackageInfo>, term: String): List<CatalogGroup> {
        val q = term.trim().lowercase()
        val list = all.filter { p -> q.isEmpty() || listOf(p.name, p.overview, p.description, p.owner, categoryLabel(p.category)).any { it?.lowercase()?.contains(q) == true } }
        val other = categoryLabel(null)
        return list.groupBy { categoryLabel(it.category) }.entries
            .sortedWith { a, b -> if (a.key == other) 1 else if (b.key == other) -1 else a.key.compareTo(b.key, ignoreCase = true) }
            .map { (label, items) -> CatalogGroup(label, items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }).map(::catalogRow)) }
    }

    private fun catalogRow(p: PackageInfo): CatalogRow {
        val installed = plugins?.firstOrNull { normId(it.id) == normId(p.guid) }
        val v = latestCompatible(p, server)
        val newest = p.versions.sortedWith { a, b -> cmpVersion(b.version, a.version) }.firstOrNull()
        val repo = v ?: newest
        val third = if (repo != null && !isJellyfinRepo(repo.repositoryUrl)) repo.repositoryName else ""
        val meta = listOf(
            when {
                v != null -> shortVersion(v.version)
                newest != null -> t("Built for {version}", "version" to majorMinor(newest.targetAbi))
                else -> ""
            },
            third,
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        val st = installed?.let { pluginStatus(it.status) }
        return CatalogRow(
            guid = p.guid,
            name = p.name,
            letters = initials(p.name),
            meta = meta,
            desc = (p.overview?.takeIf { it.isNotBlank() } ?: p.description.orEmpty()).trim(),
            note = if (v == null && newest != null) t("No version for Jellyfin {version} yet, so it can’t be installed.", "version" to majorMinor(server)) else null,
            status = installed?.let { if (it.status == "Active") t("Installed") else st!!.first },
            dot = st?.second ?: Dot.Ok,
            canInstall = installed == null && v != null,
            installing = p.guid in installing,
        )
    }

    private fun catalogCount(all: List<PackageInfo>, term: String): String {
        if (term.isNotBlank()) return plural(catalogGroups(all, term).sumOf { it.rows.size }, "match", "matches")
        val repoCount = all.flatMap { p -> p.versions.map { it.repositoryUrl } }.toSet().size
        return t("{plugins} from {repositories}", "plugins" to plural(all.size, "plugin"), "repositories" to plural(repoCount, "repository", "repositories"))
    }

    /* ── Actions ── */

    private fun say(text: String) = _state.update { it.copy(message = text) }

    override fun messageShown() = _state.update { it.copy(message = null) }

    override fun setView(view: PluginsView) = _state.update { it.copy(view = view) }

    override fun retry() {
        if (plugins == null) loadInstalled()
        if (packages == null) loadCatalog()
        if (repos == null && plugins != null) loadRepos()
    }

    private fun loadRepos() {
        viewModelScope.launch {
            runCatching { http.get<List<RepositoryInfo>>("/Repositories") }
                .onSuccess { repos = it; _state.update { s -> s.copy(reposError = null) } }
                .onFailure { rethrowCancel(it); _state.update { s -> s.copy(reposError = t("Couldn’t load the repositories.") + " " + errorText(it)) } }
            publish()
        }
    }

    override fun setSearch(text: String) {
        _state.update { it.copy(search = text) }
        publish()
    }

    private fun setBusy(id: String, label: String?) {
        if (label == null) busy.remove(id) else busy[id] = label
        publish()
    }

    private suspend fun updateOne(v: PluginView, quiet: Boolean): Boolean {
        val pkg = v.pkg ?: return false
        val to = v.update ?: return false
        setBusy(v.plugin.id, t("Updating…"))
        val ok = runCatching { installPackage(pkg.name, pkg.guid, to.version, to.repositoryUrl) }
            .onFailure { rethrowCancel(it); say(t("Couldn’t update {plugin}. {error}", "plugin" to v.plugin.name, "error" to errorText(it))) }
            .isSuccess
        if (ok && !quiet) say(t("{plugin} updated. Restart the server to finish.", "plugin" to v.plugin.name))
        setBusy(v.plugin.id, null)
        return ok
    }

    private suspend fun installPackage(name: String, guid: String, version: String, repositoryUrl: String) =
        http.send("/Packages/Installed/" + URLEncoder.encode(name, "UTF-8").replace("+", "%20"), query = mapOf("assemblyGuid" to guid, "version" to version, "repositoryUrl" to repositoryUrl))

    override fun update(id: String) {
        val v = views().firstOrNull { it.plugin.id == id } ?: return
        viewModelScope.launch { if (updateOne(v, quiet = false)) loadInstalled() }
    }

    override fun updateAll() {
        if (_state.value.updatingAll) return
        _state.update { it.copy(updatingAll = true) }
        viewModelScope.launch {
            var done = 0
            for (v in views().filter { it.update != null }) if (updateOne(v, quiet = true)) done++
            _state.update { it.copy(updatingAll = false) }
            if (done > 0) say(tn(done, "{n} plugin updated. Restart the server to finish.", "{n} plugins updated. Restart the server to finish."))
            loadInstalled()
        }
    }

    override fun setEnabled(id: String, on: Boolean) {
        val p = plugins?.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            setBusy(id, if (on) t("Enabling…") else t("Disabling…"))
            runCatching { http.send("/Plugins/${p.id}/${p.version}/" + if (on) "Enable" else "Disable") }
                .onSuccess { say(if (on) t("{plugin} enabled. Restart the server to load it.", "plugin" to p.name) else t("{plugin} disabled. Restart the server to unload it.", "plugin" to p.name)) }
                .onFailure { rethrowCancel(it); say((if (on) t("Couldn’t enable {plugin}. {error}", "plugin" to p.name, "error" to errorText(it)) else t("Couldn’t disable {plugin}. {error}", "plugin" to p.name, "error" to errorText(it)))) }
            setBusy(id, null)
            loadInstalled()
        }
    }

    override fun uninstall(id: String) {
        val p = plugins?.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            setBusy(id, t("Uninstalling…"))
            runCatching { http.delete("/Plugins/${p.id}/${p.version}") }
                .onSuccess { say(t("{plugin} will be removed when the server restarts.", "plugin" to p.name)) }
                .onFailure { rethrowCancel(it); say(t("Couldn’t uninstall {plugin}. {error}", "plugin" to p.name, "error" to errorText(it))) }
            setBusy(id, null)
            loadInstalled()
        }
    }

    override fun install(guid: String) {
        val p = packages?.firstOrNull { it.guid == guid } ?: return
        val v = latestCompatible(p, server) ?: return
        if (guid in installing) return
        installing += guid
        publish()
        viewModelScope.launch {
            runCatching { installPackage(p.name, p.guid, v.version, v.repositoryUrl) }
                .onSuccess { say(t("{plugin} installed. Restart the server to load it.", "plugin" to p.name)) }
                .onFailure { rethrowCancel(it); say(t("Couldn’t install {plugin}. {error}", "plugin" to p.name, "error" to errorText(it))) }
            installing -= guid
            loadInstalled()
        }
    }

    /** Saves the repository list; the catalog reloads from the new list. */
    private suspend fun saveRepos(next: List<RepositoryInfo>, done: String): Boolean {
        val prev = repos.orEmpty()
        repos = next
        _state.update { it.copy(savingRepos = true) }
        publish()
        val ok = runCatching { http.send("/Repositories", jellyfinList(next)) }
            .onFailure { rethrowCancel(it); repos = prev; say(t("Couldn’t save the repositories. {error}", "error" to errorText(it))) }
            .isSuccess
        _state.update { it.copy(savingRepos = false) }
        if (ok) {
            say(done)
            loadCatalog()
        }
        publish()
        return ok
    }

    override fun setRepoEnabled(index: Int, on: Boolean) {
        val list = repos ?: return
        val r = list.getOrNull(index) ?: return
        viewModelScope.launch {
            saveRepos(list.mapIndexed { i, x -> if (i == index) x.copy(enabled = on) else x }, if (on) t("{repository} turned on", "repository" to r.name) else t("{repository} turned off", "repository" to r.name))
        }
    }

    override fun removeRepo(index: Int) {
        val list = repos ?: return
        val r = list.getOrNull(index) ?: return
        viewModelScope.launch { saveRepos(list.filterIndexed { i, _ -> i != index }, t("{repository} removed", "repository" to r.name)) }
    }

    override fun addRepo(name: String, url: String, done: (String?) -> Unit) {
        val n = name.trim()
        val u = url.trim()
        when {
            n.isEmpty() -> return done(t("Give the repository a name."))
            !Regex("^https?://\\S+$", RegexOption.IGNORE_CASE).matches(u) -> return done(t("Enter the manifest address, starting with https://"))
            repos.orEmpty().any { it.url.trim().lowercase() == u.lowercase() } -> return done(t("That repository is already on the list."))
        }
        viewModelScope.launch {
            val ok = saveRepos(repos.orEmpty() + RepositoryInfo(n, u, true), t("{repository} added", "repository" to n))
            done(if (ok) null else "")
        }
    }

    override fun addJellyfinRepo() {
        viewModelScope.launch { saveRepos(listOf(JellyfinStable), t("Jellyfin’s repository added")) }
    }

    override fun restart() {
        if (_state.value.restarting) return
        _state.update { it.copy(restarting = true) }
        viewModelScope.launch {
            runCatching { http.send("/System/Restart") }
                .onSuccess { say(t("The server is restarting")) }
                .onFailure { rethrowCancel(it); say(t("Couldn’t restart. {error}", "error" to errorText(it))); _state.update { s -> s.copy(restarting = false) } }
        }
    }
}
