package io.github.arkmind.seedy.core.update

import io.github.arkmind.seedy.core.data.bool
import io.github.arkmind.seedy.core.data.long
import io.github.arkmind.seedy.core.data.objects
import io.github.arkmind.seedy.core.data.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.net.HttpURLConnection
import java.net.URL

/** Where the app's releases live: their descriptions are the update screen's "What's new". */
const val ReleasesRepo = "Arkmind/seedy"
const val ReleasesPage = "https://github.com/$ReleasesRepo/releases"

/**
 * The app's version code for a version name, as android/app/build.gradle.kts computes it from the release tag:
 * 0.4.7 → 40799, pre-releases before their release (0.5.0-alpha.N → 50001+N, -beta.N → 50031+N, -rc.N → 50061+N).
 */
fun versionCodeOf(version: String): Long? {
    val m = Regex("""^v?(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-([A-Za-z]+)\.?(\d+)?)?""").find(version.trim()) ?: return null
    val (major, minor, patch) = (1..3).map { m.groupValues[it].toLongOrNull() ?: 0L }
    val n = (m.groupValues[5].toLongOrNull() ?: 0L).coerceIn(0L, 28L)
    val stage = when (m.groupValues[4].lowercase()) {
        "" -> 99L
        "beta" -> 31L + n
        "rc" -> 61L + n
        else -> 1L + n
    }
    return ((major * 100 + minor) * 100 + patch) * 100 + stage
}

/** The version name back from a code ([versionCodeOf]'s inverse). */
fun versionNameOf(code: Long): String {
    val stage = code % 100
    val base = "${code / 1_000_000}.${code / 10_000 % 100}.${code / 100 % 100}"
    return when {
        stage == 99L -> base
        stage >= 61 -> "$base-rc.${stage - 61}"
        stage >= 31 -> "$base-beta.${stage - 31}"
        else -> "$base-alpha.${stage - 1}"
    }
}

/** A newer Seedy than the one installed, and what the update screen says about it. */
@Serializable
data class UpdateOffer(
    /** "0.4.7". */
    val version: String,
    val code: Long,
    val items: List<ReleaseItem> = emptyList(),
    /** The release's page ("See everything that changed"). */
    val url: String = ReleasesPage,
    /** The download's size, when known. */
    val bytes: Long? = null,
    val fromPlay: Boolean = false,
    /** The APK to download, outside Google Play, and its SHA-256 (hex) when GitHub gives it. */
    val apkUrl: String? = null,
    val apkSha256: String? = null,
)

/** A published release of the app on GitHub. */
data class Release(
    val version: String,
    val code: Long,
    val prerelease: Boolean,
    val body: String,
    val url: String,
    val apkUrl: String?,
    val apkBytes: Long?,
    val apkSha256: String? = null,
) {
    fun offer() = UpdateOffer(version, code, ReleaseNotes.parse(body), url, apkBytes, false, apkUrl, apkSha256)
}

object Releases {
    /** The latest releases, newest first; drafts and tags that aren't versions are left out. Null when GitHub can't be reached. */
    fun fetch(count: Int = 10): List<Release>? = runCatching {
        val c = URL("https://api.github.com/repos/$ReleasesRepo/releases?per_page=$count").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "Seedy-Android")
        c.connectTimeout = 15_000
        c.readTimeout = 15_000
        try {
            if (c.responseCode == 200) parse(c.inputStream.bufferedReader().use { it.readText() }) else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun parse(json: String): List<Release> =
        (Json.parseToJsonElement(json) as? JsonArray).orEmpty().filterIsInstance<JsonObject>().mapNotNull { r ->
            if (r.bool("draft")) return@mapNotNull null
            val tag = r.str("tag_name") ?: return@mapNotNull null
            val code = versionCodeOf(tag) ?: return@mapNotNull null
            val apk = r.objects("assets").firstOrNull { it.str("name")?.let { n -> n.startsWith("seedy-android-") && n.endsWith(".apk") } == true }
            Release(
                version = tag.removePrefix("v"),
                code = code,
                prerelease = r.bool("prerelease"),
                body = r.str("body").orEmpty(),
                url = r.str("html_url") ?: ReleasesPage,
                apkUrl = apk?.str("browser_download_url"),
                apkBytes = apk?.long("size")?.takeIf { it > 0 },
                apkSha256 = apk?.str("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        }.sortedByDescending { it.code }

    /**
     * Outside Google Play: the newest release with its APK attached that's newer than [installed]. Pre-releases only
     * for someone already on one.
     */
    fun newest(releases: List<Release>, installed: Long): Release? {
        val onPre = installed % 100 != 99L
        return releases.firstOrNull { it.code > installed && it.apkUrl != null && (!it.prerelease || onPre) }
    }
}
