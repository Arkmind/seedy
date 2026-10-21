package io.github.arkmind.seedy.core.admin.seedy

import io.github.arkmind.seedy.core.admin.AdminHttp
import io.github.arkmind.seedy.core.i18n.t
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import org.jellyfin.sdk.api.client.HttpMethod
import java.util.UUID

/* Pieces several of these pages share: restarting the server, browsing its folders, checking addresses. */

/**
 * Restarts the server and waits until it answers again (web/src/screens/server/api.ts restartServer): it has to go
 * away first, or 12 s pass; gives up after 3 minutes.
 */
suspend fun restartAndWait(http: AdminHttp) {
    http.send("/System/Restart")
    val started = System.currentTimeMillis()
    var wentDown = false
    delay(2_500)
    while (System.currentTimeMillis() - started < 180_000) {
        val up = try {
            http.raw(HttpMethod.GET, "/System/Info/Public")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        if (!up) wentDown = true
        if (up && (wentDown || System.currentTimeMillis() - started > 12_000)) return
        delay(2_000)
    }
}

/** A folder or file on the server ([type] "Directory", "File", "NetworkShare"…). */
@Serializable
data class FsEntry(val name: String = "", val path: String = "", val type: String = "Directory")

/** What a folder holds and the folder above it ("" for the list of drives, null at the drives). */
data class FolderListing(val path: String, val entries: List<FsEntry>, val parent: String?, val error: String? = null)

/** Browses the server's file system, as the web's folder picker does (/Environment). */
suspend fun listFolder(http: AdminHttp, path: String, files: Boolean): FolderListing {
    val p = path.trim()
    return try {
        val entries = if (p.isEmpty()) http.get<List<FsEntry>>("/Environment/Drives")
        else http.get<List<FsEntry>>("/Environment/DirectoryContents", mapOf("path" to p, "includeDirectories" to true, "includeFiles" to files))
        val parent = if (p.isEmpty()) null else runCatching {
            val e = http.element(HttpMethod.GET, "/Environment/ParentPath", mapOf("path" to p))
            (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it != p } ?: ""
        }.getOrDefault("")
        FolderListing(p, entries.filter { it.type != "NetworkComputer" }, parent)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        FolderListing(p, emptyList(), if (p.isEmpty()) null else "", if (p.isEmpty()) t("The server didn’t list its drives.") else t("The server can’t open this folder."))
    }
}

private val Ipv4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
private val Ipv6 = Regex("^[0-9a-f:]+(%\\w+)?$", RegexOption.IGNORE_CASE)
private val Host = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$", RegexOption.IGNORE_CASE)

/** Loose checks: Jellyfin takes addresses, ranges and (for proxies) host names; this only catches typos. Null when fine. */
fun validAddress(v: String, cidr: Boolean = false, host: Boolean = false): String? {
    val bits = v.split('/')
    if (bits.size > 2) return t("Use one address, or one address and a /prefix.")
    val ip = bits[0]
    val isIp = Ipv4.matches(ip) || (ip.contains(':') && Ipv6.matches(ip))
    if (bits.size == 2) {
        if (!cidr || !isIp) return t("Ranges are written like 192.168.1.0/24.")
        val n = bits[1].toIntOrNull()
        if (n == null || n < 0 || n > (if (ip.contains(':')) 128 else 32)) return t("The /prefix must be between 0 and 32 (128 for IPv6).")
        return null
    }
    if (isIp) return null
    if (host && Host.matches(v)) return null
    return if (cidr) t("Write an address like 192.168.1.20 or a range like 192.168.1.0/24.") else t("Write an address like 192.168.1.20.")
}

/** Jellyfin's user ids come without dashes from Seedy. */
fun uuidOf(id: String?): UUID? {
    val s = id?.replace("-", "")?.takeIf { it.length == 32 } ?: return null
    return runCatching { UUID.fromString("${s.substring(0, 8)}-${s.substring(8, 12)}-${s.substring(12, 16)}-${s.substring(16, 20)}-${s.substring(20)}") }.getOrNull()
}
