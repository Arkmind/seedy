package io.github.arkmind.seedy.core.wrapped

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Wrapped sentences, in English, as the web's copy.ts writes them from the same numbers. */
class WrappedCopyTest {
    private val h = 3_600_000L
    private val year = Wrapped(
        year = 2026,
        stories = listOf("cover", "hours", "mystery", "shows", "summary"),
        watchMs = 4_620_960_000L,
        prevWatchMs = 3_916_067_797L,
        rank = 2,
        genres = listOf(WrappedGenre("Thriller", "thriller", 34), WrappedGenre("Sci-Fi & Fantasy", "scifi", 15), WrappedGenre("Telenovela", null, 4)),
        otherPct = 9,
        shows = listOf(WrappedTitle("1", "Northbound", 62, 51 * h)),
        seasonsFinished = 11,
        showsStarted = 7,
        movie = WrappedMovie("6", "Paper Lanterns", 4, 448 * 60_000L, listOf("2026-03-07", "2026-07-19", "2026-10-11", "2026-11-02")),
        binge = WrappedBinge("1", "Northbound", 9, season = 2, lastSeason = 2, lastEpisode = 7, start = "2026-03-14T14:02", end = "2026-03-14T22:47"),
        still = WrappedStill(23, 23),
        clock = WrappedClock(peak = "23:40", kind = "night", pct = 61, day = 6),
        personality = WrappedPersonality("sprinter"),
        musicMs = 312 * h,
        books = 6,
    )

    @Test
    fun skipsStoriesItDoesNotKnow() {
        assertEquals(listOf(StoryKey.Cover, StoryKey.Hours, StoryKey.Shows, StoryKey.Summary), storiesOf(year))
    }

    @Test
    fun hours() {
        assertEquals(1284L, hoursOf(year.watchMs))
        assertEquals(listOf(Chip("53 days", "back to back"), Chip("+18%", "vs 2025"), Chip("#2", "on your server")), hourChips(year))
        assertEquals("1 play", hourChips(year.copy(watchMs = 3 * h, plays = 1, prevWatchMs = null, rank = null)).single().big)
        assertEquals("−25%", hourChips(year.copy(watchMs = 300 * h, prevWatchMs = 400 * h))[1].big)
    }

    @Test
    fun genres() {
        assertEquals("You went dark this year: a third of your nights were thrillers.", genreLead(year))
        assertEquals("Sci-fi", genreName(year.genres[1]))
        assertEquals("Telenovela", genreName(year.genres[2]))
        assertEquals("The other 9% was a bit of everything.", genreOther(year))
        assertEquals("half", shareWords(50))
        assertEquals("12%", shareWords(12))
    }

    @Test
    fun showsAndMovie() {
        assertEquals("62 episodes · 51 h", showMeta(year.shows[0]))
        assertEquals("You finished 11 seasons and started 7 new shows.", showsFootnote(year))
        assertEquals("62 episodes of Northbound. I counted.", showsMedu(year))
        assertEquals("The one you couldn’t let go", movieTitle(year))
        assertEquals("7 h 28 min all in · last time on 2 November", movieMeta(year))
        assertEquals(listOf("Mar", "Jul", "Oct", "Nov"), movieTickets(year))
    }

    @Test
    fun binge() {
        assertEquals("Northbound, season 2 · Sat 14 March", bingeLine(year))
        assertEquals("Northbound · S2 E7", bingeEpisode(year))
        assertEquals("We asked “Still watching?” 23 times this year. You said yes every single time.", bingeText(year))
        assertEquals("8 h 45 min from the first episode to the last.", bingeText(year.copy(still = WrappedStill())))
        assertEquals("14:02", timeOf(year.binge!!.start))
    }

    @Test
    fun clockAndPersonality() {
        assertEquals("You’re a" to "night owl", clockTitle(year))
        assertEquals("Saturdays were the big nights.", clockDay(year))
        assertEquals("sleepy", clockMood(year))
        assertEquals(Chip("4.2 days", "to finish a season, on average"), statLine(WrappedStat("seasonDays", 4.2)))
        assertEquals(Chip("312 intros", "skipped. That’s 7 hours you got back"), statLine(WrappedStat("introsSkipped", 312.0, 7.0 * h)))
        assertEquals(Chip("9 seconds", "between episodes, on average"), statLine(WrappedStat("gapSeconds", 9.0)))
        assertEquals(Chip("2 minutes", "between episodes, on average"), statLine(WrappedStat("gapSeconds", 130.0)))
        assertEquals(Chip("3.5 hours", "on your biggest day"), statLine(WrappedStat("bigDay", 3.5 * h)))
    }

    @Test
    fun summaryAndBanner() {
        assertEquals("Also 312 h of music · 6 books", summaryAlso(year))
        assertEquals("My 2026 on Seedy: 1,284 hours, top show Northbound, top genre Thriller, The Season Sprinter", summaryLabel(year))
        assertEquals("1,284 hours in 9 stories. Medu took notes.", bannerText(year.watchMs, 9))
    }

    @Test
    fun readsTheServersJson() {
        val json = """{"year":2026,"stories":["cover","binge"],"watchMs":10,"binge":{"id":"a","name":"Pioneer One","episodes":4,"season":1,"start":"2026-02-20T19:32","end":"2026-02-20T21:12","minutes":[50,49]},
            "still":{"asked":1,"yes":1},"personality":{"key":"rewatcher","stats":[{"key":"rewatches","value":555}]},"future":true}"""
        val w = Json { ignoreUnknownKeys = true }.decodeFromString<Wrapped>(json)
        assertEquals(listOf(50, 49), w.binge?.minutes)
        assertEquals("We asked “Still watching?” 1 time this year. You said yes.", bingeText(w))
    }
}
