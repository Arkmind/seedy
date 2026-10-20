package io.github.arkmind.seedy.core.i18n

import android.content.Context
import android.icu.text.PluralRules
import kotlinx.serialization.json.Json
import java.text.NumberFormat
import java.util.Locale

/**
 * Seedy's languages, as on the web: English text is the key, wrapped in [t]. French comes from
 * assets/i18n/fr.web.json (the web app's dictionaries, copied by `make android-shared`) and fr.<area>.json (text
 * only the apps have, one file per area). The language is the profile's display.language ("Auto" follows the device); this device remembers the
 * last one so the first screen is already right. Changing it recreates the activity, so [t] may be called anywhere
 * at composition time.
 */
enum class Lang(val code: String) { En("en"), Fr("fr") }

object I18n {
    @Volatile
    var lang: Lang = Lang.En
        private set

    @Volatile
    private var dict: Map<String, String> = emptyMap()

    /** The locale for number and date formatting. English keeps the boards' day-first style. */
    val locale: Locale get() = if (lang == Lang.Fr) Locale.FRANCE else Locale.UK

    fun deviceLang(): Lang = if (Locale.getDefault().language == "fr") Lang.Fr else Lang.En

    /** A stored preference ("auto", "fr", "fr-FR", "en-US"…) → the language Seedy shows. */
    fun resolve(pref: String?): Lang {
        val v = pref?.lowercase().orEmpty()
        return when {
            v.startsWith("fr") -> Lang.Fr
            v.startsWith("en") -> Lang.En
            else -> deviceLang()
        }
    }

    fun load(context: Context, lang: Lang) {
        this.lang = lang
        dict = if (lang == Lang.En) emptyMap() else buildMap {
            // The web app's dictionary first, then the apps' own (one file per area: fr.home.json, fr.player.json…).
            val own = context.assets.list("i18n").orEmpty().filter { it.startsWith("${lang.code}.") && it.endsWith(".json") && it != "${lang.code}.web.json" }.sorted()
            for (name in listOf("${lang.code}.web.json") + own) {
                val text = runCatching { context.assets.open("i18n/$name").bufferedReader().use { it.readText() } }.getOrNull()
                if (text != null) putAll(Json.decodeFromString<Map<String, String>>(text))
            }
        }
    }

    internal fun lookup(text: String): String = dict[text] ?: text
}

private val placeholder = Regex("""\{(\w+)\}""")

private fun fill(text: String, vars: Array<out Pair<String, Any?>>): String =
    if (vars.isEmpty()) text
    else placeholder.replace(text) { m -> vars.firstOrNull { it.first == m.groupValues[1] }?.second?.toString() ?: m.value }

/** Translate English text. Placeholders like {n} or {name} are filled from [vars] (in both languages). */
fun t(text: String, vararg vars: Pair<String, Any?>): String = fill(I18n.lookup(text), vars)

/** Plural forms: tn(3, "{n} movie", "{n} movies"). The language's own rule picks one (French treats 0 and 1 as singular). */
fun tn(n: Int, one: String, other: String, vararg vars: Pair<String, Any?>): String {
    val form = runCatching { PluralRules.forLocale(I18n.locale).select(n.toDouble()) }.getOrElse { if (n == 1) "one" else "other" }
    return t(if (form == "one") one else other, "n" to formatNumber(n), *vars)
}

fun formatNumber(n: Number): String = NumberFormat.getInstance(I18n.locale).format(n)
