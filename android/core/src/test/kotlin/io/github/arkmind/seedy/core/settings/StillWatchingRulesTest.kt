package io.github.arkmind.seedy.core.settings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class StillWatchingRulesTest {
    private fun of(json: String?): StillWatchingRules =
        StillWatchingRules.of(if (json == null) emptyMap() else mapOf("playback.stillWatching" to Json.parseToJsonElement(json)))

    @Test
    fun nothingStoredIsTheDefault() {
        assertEquals(StillWatchingRules(3, true, 90, false, 2, true), of(null))
    }

    @Test
    fun readsTheNewForm() {
        assertEquals(StillWatchingRules(4, true, 120, true, 1, false), of("""{"episodes":4,"minutes":120,"movies":1,"off":["movies"]}"""))
    }

    @Test
    fun readsTheOldForm() {
        assertEquals(StillWatchingRules(2, true, 60, true, 2, true), of("""{"episodes":2,"minutes":60}"""))
        assertEquals(StillWatchingRules(3, true, 90, false, 2, true), of("""{"episodes":3,"minutes":0}"""))
        val off = StillWatchingRules(episodesOn = false, minutesOn = false, moviesOn = false)
        assertEquals(off, of("false"))
        assertEquals(off, StillWatchingRules.of(mapOf("playback.stillWatching" to JsonPrimitive("off"))))
    }

    @Test
    fun writesWhatItReads() {
        val r = StillWatchingRules(2, false, 180, true, 3, true)
        assertEquals(r, StillWatchingRules.of(mapOf<String, JsonElement>("playback.stillWatching" to r.toJson())))
        assertEquals("""{"episodes":2,"minutes":180,"movies":3,"off":["episodes"]}""", r.toJson().toString())
    }
}
