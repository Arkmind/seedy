package io.github.arkmind.seedy.core.update

import kotlinx.serialization.Serializable

/**
 * One line of a release's "What's Changed" list, as the update screen shows it: an icon from the release icons
 * ([icon] is its key, such as `play_button`), a short title and an optional line under it. [frTitle] and [frText] are
 * the French ones when the release gives them.
 */
@Serializable
data class ReleaseItem(
    val icon: String,
    val title: String,
    val text: String? = null,
    val frTitle: String? = null,
    val frText: String? = null,
)

/**
 * Reads a GitHub release's description in Seedy's format (see CONTRIBUTING.md, "Release notes"):
 *
 * ```
 * # What's Changed
 * - <!-- icon:play_button --> Your own “Still watching?” rules
 *   2–4 episodes, 90 min–3 h, or 1–3 movies
 *   <!-- fr: Vos propres règles « Toujours là ? » | 2 à 4 épisodes, 90 min à 3 h, ou 1 à 3 films -->
 *
 * **Full changelog**: https://github.com/Arkmind/seedy/compare/v0.4.6...v0.4.7
 * ```
 *
 * Only the list under the first heading counts (up to the next heading or the "Full changelog" line); a bullet without
 * an icon gets [DefaultIcon], and GitHub's generated lines ("… by @someone in https://…") are left out.
 */
object ReleaseNotes {
    const val DefaultIcon = "sparkles"

    private val bullet = Regex("""^[-*]\s+(.*)$""")
    private val icon = Regex("""<!--\s*icon:\s*([a-z0-9_]+)\s*-->""")
    private val french = Regex("""<!--\s*fr:\s*(.*?)\s*-->""")
    private val comment = Regex("""<!--.*?-->""")
    private val generated = Regex("""\sby @\S+ in https?://\S+\s*$""")
    private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val marks = Regex("""\*\*|__|`""")

    fun parse(body: String?): List<ReleaseItem> {
        if (body.isNullOrBlank()) return emptyList()
        val items = mutableListOf<ReleaseItem>()
        var item: Draft? = null
        fun close() {
            item?.let { d -> if (d.title.isNotEmpty() && !generated.containsMatchIn(d.raw)) items += d.done() }
            item = null
        }
        for (raw in body.replace("\r", "").lines()) {
            val line = raw.trimEnd()
            val trimmed = line.trim()
            if (trimmed.startsWith("#")) {
                if (items.isNotEmpty() || item != null) break
                continue
            }
            if (trimmed.startsWith("**Full changelog", ignoreCase = true)) break
            val b = bullet.find(line)
            when {
                b != null -> {
                    close()
                    val rest = b.groupValues[1]
                    val fr = french.find(rest)?.groupValues?.get(1)
                    item = Draft(
                        raw = rest,
                        icon = icon.find(rest)?.groupValues?.get(1) ?: DefaultIcon,
                        title = plain(rest),
                    ).also { d -> fr?.let(d::french) }
                }
                item != null && trimmed.isNotEmpty() && raw.startsWith(" ") -> {
                    val fr = french.find(trimmed)?.groupValues?.get(1)
                    if (fr != null) item!!.french(fr)
                    val text = plain(trimmed)
                    if (text.isNotEmpty()) item!!.lines += text
                }
                trimmed.isEmpty() -> Unit
                item != null -> close()
            }
        }
        close()
        return items
    }

    /** Text without comments and the usual Markdown marks (bold, code, links). */
    private fun plain(s: String): String =
        s.replace(comment, "").replace(link, "$1").replace(marks, "").trim()

    private class Draft(val raw: String, val icon: String, val title: String) {
        val lines = mutableListOf<String>()
        var frTitle: String? = null
        var frText: String? = null

        fun french(s: String) {
            val parts = s.split('|', limit = 2).map { it.trim() }
            frTitle = parts[0].takeIf { it.isNotEmpty() }
            frText = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
        }

        fun done() = ReleaseItem(icon, title, lines.joinToString(" ").takeIf { it.isNotEmpty() }, frTitle, frText)
    }
}
