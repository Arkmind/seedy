package io.github.arkmind.seedy.core.admin

import io.github.arkmind.seedy.core.i18n.t

/* Installed plugins against the repository catalog (web/src/screens/server/plugins.ts). */

const val SeedyPluginId = "b547483f57e9439e8a2a561828a524a6"

/** Plugins built for an older major version (10.11) don't load on 12. */
fun isCompatible(v: PackageVersion, server: String): Boolean {
    val abi = v.targetAbi ?: return true
    val major = abi.substringBefore('.').toIntOrNull() ?: return true
    val serverMajor = server.substringBefore('.').toIntOrNull() ?: return true
    if (serverMajor == 0) return true
    return major >= serverMajor && cmpVersion(abi, server) <= 0
}

fun latestCompatible(pkg: PackageInfo, server: String): PackageVersion? =
    pkg.versions.filter { isCompatible(it, server) }.sortedWith { a, b -> cmpVersion(b.version, a.version) }.firstOrNull()

/** GET /Plugins can list an old and a new version of the same plugin after an update: the newest, needing a restart. */
fun dedupePlugins(list: List<PluginInfo>): List<PluginInfo> =
    list.groupBy { normId(it.id) }.values.map { group ->
        val top = group.sortedWith { a, b -> cmpVersion(b.version, a.version) }.first()
        if (group.size > 1 && top.status != "Disabled") top.copy(status = "Restart") else top
    }.sortedWith(compareBy<PluginInfo> { normId(it.id) != SeedyPluginId }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })

/** Updates for the installed plugins: the newest compatible version above the installed one. */
fun pluginUpdates(plugins: List<PluginInfo>, packages: List<PackageInfo>, server: String): List<Pair<PluginInfo, String>> {
    val installed = LinkedHashMap<String, PluginInfo>()
    for (p in plugins) {
        if (p.status == "Deleted") continue
        val prev = installed[normId(p.id)]
        if (prev == null || cmpVersion(p.version, prev.version) > 0) installed[normId(p.id)] = p
    }
    return installed.mapNotNull { (id, plugin) ->
        val best = packages.filter { normId(it.guid) == id }.mapNotNull { latestCompatible(it, server)?.version }
            .fold<String, String?>(null) { a, v -> if (a == null || cmpVersion(v, a) > 0) v else a }
        if (best != null && cmpVersion(best, plugin.version) > 0) plugin to best else null
    }
}

/** The Jellyfin version a plugin build targets, from the catalog entry for the installed version. */
fun pluginTargetAbi(plugin: PluginInfo, packages: List<PackageInfo>): String? =
    packages.filter { normId(it.guid) == normId(plugin.id) }.flatMap { it.versions }.firstOrNull { cmpVersion(it.version, plugin.version) == 0 }?.targetAbi

fun pluginWontLoad(p: PluginInfo): Boolean = p.status == "NotSupported" || p.status == "Malfunctioned"

/** Why a plugin won't load, as one sentence (the Overview's notice). */
fun wontLoadReason(p: PluginInfo, packages: List<PackageInfo>?, serverVersion: String): String {
    val abi = packages?.let { pluginTargetAbi(p, it) }
    val server = majorMinor(serverVersion)
    if (p.status == "NotSupported") {
        return if (abi != null) t("It needs Jellyfin {version} or newer.", "version" to majorMinor(abi)) else t("It isn’t compatible with Jellyfin {version}.", "version" to server)
    }
    val built = abi?.let(::majorMinor)
    if (built != null && server.isNotEmpty() && built != server && (built.substringBefore('.').toIntOrNull() ?: 0) < (server.substringBefore('.').toIntOrNull() ?: 0)) {
        return t("Built for Jellyfin {built}; {server} needs a rebuilt version.", "built" to built, "server" to server)
    }
    return t("It stopped with an error while starting. The server log has the details.")
}
