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
        requests = WrappedRequests(27, 21, 4, 2, WrappedArrival("Glass Harbor", season = 2, ms = 14 * 60_000L), WrappedGenre("Sci-Fi & Fantasy", "scifi", 40)),
        oddest = WrappedOddest(18_123, "movie", "Mole Men of Venus", 1958, 3.9, 27, 1, 92),
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
        assertEquals(MeduLine("I made popcorn for every single one of them.", "popcorn"), hoursMedu(year))
        assertEquals(MeduLine("That’s a lot of popcorn. Not complaining.", "popcorn"), hoursMedu(year.copy(watchMs = 200 * h)))
        assertEquals(MeduLine("Quality over quantity. I respect that.", "idle"), hoursMedu(year.copy(watchMs = 199 * h)))
    }

    @Test
    fun lover() {
        assertEquals(MeduLine("Me too. One more episode?", "love"), loverMedu(year.copy(showMs = 900 * h, movieMs = 100 * h)))
        assertEquals(MeduLine("Me too. Another movie tonight?", "love"), loverMedu(year.copy(showMs = 100 * h, movieMs = 900 * h)))
        assertEquals(MeduLine("Shows or movies? Why choose.", "idle"), loverMedu(year.copy(showMs = 55 * h, movieMs = 45 * h)))
    }

    @Test
    fun startsWithAWord() {
        assertEquals("Nine", startNumber(9))
        assertEquals("Zero", startNumber(0))
        assertEquals("10", startNumber(10))
        assertEquals("1,284", startNumber(1284))
    }

    @Test
    fun genres() {
        assertEquals("You went dark this year: a third of your nights were thrillers.", genreLead(year))
        assertEquals("Sci-fi", genreName(year.genres[1]))
        assertEquals("Telenovela", genreName(year.genres[2]))
        assertEquals("The other 9% was a bit of everything.", genreOther(year))
        assertEquals("half", shareWords(50))
        assertEquals("12%", shareWords(12))
        assertEquals(MeduLine("That many thrillers… should I be worried?", "search"), genreMedu(year))
        val top = { key: String -> year.copy(genres = listOf(WrappedGenre(key, key, 40))) }
        assertEquals(MeduLine("That many thrillers… should I be worried?", "search"), genreMedu(top("crime")))
        assertEquals(MeduLine("You laughed a lot this year. I could hear you.", "yay"), genreMedu(top("comedy")))
        assertEquals(MeduLine("I watched those through my tentacles.", "oops"), genreMedu(top("horror")))
        assertEquals(MeduLine("Same. Cartoons are the best.", "yay"), genreMedu(top("family")))
        assertEquals(MeduLine("I cried too. Twice.", "sad"), genreMedu(top("drama")))
        assertEquals(MeduLine("Good taste, if you ask me.", "idle"), genreMedu(top("western")))
    }

    @Test
    fun showsAndMovie() {
        assertEquals("62 episodes · 51 h", showMeta(year.shows[0]))
        assertEquals("You finished 11 seasons and started 7 new shows.", showsFootnote(year))
        assertEquals(MeduLine("62 episodes of Northbound. I counted.", "think"), showsMedu(year))
        assertEquals("Nine episodes of Northbound. I counted.", showsMedu(year.copy(shows = listOf(WrappedTitle("1", "Northbound", 9)))).text)
        assertEquals("One episode of Northbound. I counted.", showsMedu(year.copy(shows = listOf(WrappedTitle("1", "Northbound", 1)))).text)
        assertEquals(MeduLine("Four times? You must know it by heart.", "love"), movieMedu(year))
        assertEquals(MeduLine("Four times, and I still cry at the end.", "sad"), movieMedu(year.copy(movie = year.movie!!.copy(genres = listOf("romance")))))
        assertEquals(MeduLine("Good pick. I’d watch it again.", "idle"), movieMedu(year.copy(movie = year.movie!!.copy(views = 1))))
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
        assertEquals(MeduLine("Nine in a row? I had to unplug.", "oops"), bingeMedu(year))
        assertEquals(MeduLine("Only 4 in a row. Very civilised.", "idle"), bingeMedu(year.copy(binge = year.binge!!.copy(episodes = 4))))
    }

    @Test
    fun requests() {
        assertEquals("14 min", arrivalTime(14 * 60_000L))
        assertEquals("2 h 5 min", arrivalTime(125 * 60_000L))
        assertEquals("3 days", arrivalTime(3 * 86_400_000L + 3 * h))
        assertEquals("Glass Harbor, season 2", arrivalName(year.requests!!.fastest!!))
        assertEquals("Glass Harbor, seasons 1–3", arrivalName(WrappedArrival("Glass Harbor", 1, 3)))
        assertEquals("Paper Lanterns", arrivalName(WrappedArrival("Paper Lanterns")))
        assertEquals(MeduLine("Fetched every single one. You’re welcome.", "wave"), requestsMedu(year))
        assertEquals(MeduLine("Short list, great taste.", "idle"), requestsMedu(year.copy(requests = WrappedRequests(9, 9))))
    }

    @Test
    fun oddest() {
        assertEquals("★ 3.9", oddRating(3.9))
        assertEquals("★ 7.0", oddRating(7.0))
        assertEquals(MeduLine("I have questions.", "think"), oddestMedu(year))
        assertEquals(
            listOf(Chip("1958", "released"), Chip("★ 3.9", "on TMDB"), Chip("27", "people rated it"), Chip("Only you", "asked for it")),
            oddFacts(year.oddest!!),
        )
        assertEquals(Chip("3 people", "asked for it"), oddFacts(year.oddest!!.copy(year = null, requesters = 3)).last())
        val odd = year.oddest!!
        assertEquals(MeduLine("Older than your parents. Respect.", "love"), oddestMedu(year.copy(oddest = odd.copy(rating = 6.4))))
        assertEquals(MeduLine("You and 27 other people. Very exclusive.", "yay"), oddestMedu(year.copy(oddest = odd.copy(rating = 6.4, year = 2004))))
        assertEquals("You and 1 other person. Very exclusive.", oddestMedu(year.copy(oddest = odd.copy(rating = 6.4, year = 2004, votes = 1))).text)
    }

    @Test
    fun clockAndPersonality() {
        assertEquals("You’re a" to "night owl", clockTitle(year))
        assertEquals("Saturdays were the big nights.", clockDay(year))
        assertEquals(MeduLine("Some of us need our sleep, you know.", "sleepy"), clockMedu(year))
        assertEquals(MeduLine("Lunch breaks well spent.", "wave"), clockMedu(year.copy(clock = year.clock!!.copy(kind = "afternoon"))))
        assertEquals("The Completionist", personalityName("completionist"))
        assertEquals("The Comfort Rewatcher", personalityName("rewatcher"))
        assertEquals("The Slow Savourer", personalityName("slowburner"))
        assertEquals(MeduLine("Not a single episode left behind.", "yay"), personalityMedu("completionist"))
        assertEquals(MeduLine("No rush. Good things take time.", "sleepy"), personalityMedu("slowburner"))
        assertEquals(Chip("16 shows", "watched to the very last episode"), statLine(WrappedStat("showsFinished", 16.0)))
        assertEquals(Chip("4.2 days", "to finish a season, on average"), statLine(WrappedStat("seasonDays", 4.2)))
        assertEquals(Chip("312 intros", "skipped. That’s 7 hours you got back"), statLine(WrappedStat("introsSkipped", 312.0, 7.0 * h)))
        assertEquals(Chip("9 seconds", "between episodes, on average"), statLine(WrappedStat("gapSeconds", 9.0)))
        assertEquals(Chip("2 minutes", "between episodes, on average"), statLine(WrappedStat("gapSeconds", 130.0)))
        assertEquals(Chip("3.5 hours", "on your biggest day"), statLine(WrappedStat("bigDay", 3.5 * h)))
    }

    @Test
    fun summaryAndBanner() {
        assertEquals("Also 312 h of music · 6 books · 27 requests", summaryAlso(year))
        assertEquals("Also 1 request", summaryAlso(year.copy(musicMs = 0, books = 0, requests = WrappedRequests(1, 1))))
        assertEquals("38 shows · 141 movies", summaryCounts(year.copy(showsWatched = 38, moviesPlayed = 141)))
        assertEquals("1 movie", summaryCounts(year.copy(moviesPlayed = 1)))
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

    @Test
    fun readsRequestsAndTheOddest() {
        val json = """{"year":2026,"stories":["requests","oddest","personality"],"movie":{"id":"m","name":"Paper Lanterns","views":2,"genres":["drama"]},
            "requests":{"count":3,"here":2,"coming":1,"declined":0,"fastest":{"name":"Glass Harbor","season":2,"ms":840000},"genre":{"name":"Drama","key":"drama","pct":67}},
            "oddest":{"tmdbId":9,"mediaType":"tv","name":"Mole Men","year":1958,"rating":3.9,"votes":27,"requesters":1,"score":92,"posterPath":"/a.jpg","itemId":null,"item":null}}"""
        val w = Json { ignoreUnknownKeys = true }.decodeFromString<Wrapped>(json)
        assertEquals(listOf(StoryKey.Requests, StoryKey.Oddest, StoryKey.Personality), storiesOf(w))
        assertEquals("Glass Harbor, season 2", arrivalName(w.requests!!.fastest!!))
        assertEquals("tv", w.oddest?.mediaType)
        assertEquals("Two times, and I still cry at the end.", movieMedu(w).text)
    }
}
