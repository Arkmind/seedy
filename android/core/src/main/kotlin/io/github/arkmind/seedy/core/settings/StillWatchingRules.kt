package io.github.arkmind.seedy.core.settings

import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * "Are you still watching?" (prefs.custom `playback.stillWatching`, as the web stores it): three rules, each keeping
 * its count when unticked. Seedy asks before auto-playing when any ticked one is met — [episodes] in a row, [minutes]
 * without a touch, [movies] in a row.
 */
data class StillWatchingRules(
    val episodes: Int = 3,
    val episodesOn: Boolean = true,
    val minutes: Int = 90,
    val minutesOn: Boolean = false,
    val movies: Int = 2,
    val moviesOn: Boolean = true,
) {
    /** `{"episodes": 3, "minutes": 90, "movies": 2, "off": ["minutes"]}`. */
    fun toJson(): JsonElement = buildJsonObject {
        put("episodes", episodes)
        put("minutes", minutes)
        put("movies", movies)
        putJsonArray("off") {
            if (!episodesOn) add("episodes")
            if (!minutesOn) add("minutes")
            if (!moviesOn) add("movies")
        }
    }

    /** The ticked rules, "3 episodes or 90 min" ("Never" with none). */
    fun summary(): String {
        val parts = listOfNotNull(
            episodesLabel(episodes).takeIf { episodesOn },
            minutesLabel(minutes).takeIf { minutesOn },
            moviesLabel(movies).takeIf { moviesOn },
        )
        return if (parts.isEmpty()) t("Never") else parts.reduce { a, b -> t("{a} or {b}", "a" to a, "b" to b) }
    }

    companion object {
        val Episodes = listOf(2, 3, 4)
        val Minutes = listOf(90, 120, 180)
        val Movies = listOf(1, 2, 3)

        fun episodesLabel(n: Int): String = tn(n, "{n} episode", "{n} episodes")
        fun moviesLabel(n: Int): String = tn(n, "{n} movie", "{n} movies")

        /** "90 min", "2 h". */
        fun minutesLabel(m: Int): String = if (m >= 120 && m % 60 == 0) t("{n} h", "n" to m / 60) else t("{n} min", "n" to m)

        /**
         * Reads the stored value as the web does (web/src/player/settings.ts): nothing stored is the default; false or
         * "off" turns all three off; a count missing or not above 0 is the default one; without `off` (the old
         * `{episodes, minutes}`), a rule with no count is off and movies are on.
         */
        fun of(custom: Map<String, JsonElement>): StillWatchingRules {
            val d = StillWatchingRules()
            val v = custom[SettingsState.Companion.Keys.StillWatching] ?: return d
            if (v !is JsonObject) {
                val p = runCatching { v.jsonPrimitive }.getOrNull()
                val off = p?.booleanOrNull == false || (p?.isString == true && p.content == "off")
                return if (off) d.copy(episodesOn = false, minutesOn = false, moviesOn = false) else d
            }
            fun count(key: String) = runCatching { v[key]?.jsonPrimitive?.contentOrNull?.trim()?.toDoubleOrNull() }.getOrNull()
                ?.takeIf { it > 0 }?.toInt() ?: 0
            val e = count("episodes")
            val m = count("minutes")
            val mv = count("movies")
            val off = (v["off"] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.toSet()
                ?: setOfNotNull("episodes".takeIf { e == 0 }, "minutes".takeIf { m == 0 })
            return StillWatchingRules(
                episodes = e.takeIf { it > 0 } ?: d.episodes, episodesOn = "episodes" !in off,
                minutes = m.takeIf { it > 0 } ?: d.minutes, minutesOn = "minutes" !in off,
                movies = mv.takeIf { it > 0 } ?: d.movies, moviesOn = "movies" !in off,
            )
        }
    }
}
