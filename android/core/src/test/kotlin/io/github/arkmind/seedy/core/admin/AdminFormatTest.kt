package io.github.arkmind.seedy.core.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** The server pages' wording, at a fixed time: Monday 19 Oct 2026, 20:45 in Paris. */
class AdminFormatTest {
    private val zone = ZoneId.of("Europe/Paris")
    private val clock = Clock.fixed(Instant.parse("2026-10-19T18:45:00Z"), zone)
    private fun at(local: String): Instant = java.time.LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test
    fun jellyfinDates() {
        assertEquals(Instant.parse("2026-10-19T15:28:09.4225079Z"), realDate("2026-10-19T15:28:09.4225079+00:00"))
        assertEquals(Instant.parse("2026-10-21T14:59:23.0327018Z"), realDate("2026-10-21T14:59:23.0327018Z"))
        assertEquals(Instant.parse("2026-10-21T14:59:23Z"), realDate("2026-10-21T14:59:23"))
        assertNull(realDate("0001-01-01T00:00:00"))
        assertNull(realDate(null))
    }

    @Test
    fun days() {
        assertEquals("today at 03:00", dayAt(at("2026-10-19T03:00"), clock))
        assertEquals("yesterday at 23:10", dayAt(at("2026-10-18T23:10"), clock))
        assertEquals("Friday at 04:00", dayAt(at("2026-10-16T04:00"), clock))
        assertEquals("2 Oct at 04:00", dayAt(at("2026-10-02T04:00"), clock))
        assertEquals("yesterday", onDay(at("2026-10-18T04:00"), clock))
        assertEquals("on Saturday", onDay(at("2026-10-17T04:00"), clock))
        assertEquals("20:02", activityWhen(at("2026-10-19T20:02"), clock))
        assertEquals("Yesterday", activityWhen(at("2026-10-18T22:40"), clock))
        assertEquals("17 Oct", activityWhen(at("2026-10-17T22:40"), clock))
    }

    @Test
    fun people() {
        assertEquals("Last seen yesterday on Living room TV", lastSeen(at("2026-10-18T21:00"), clock, "Living room TV"))
        assertEquals("Last seen 3 months ago", lastSeen(at("2026-07-15T12:00"), clock))
        assertEquals("Never signed in", lastSeen(null, clock))
        assertEquals("up 6 days", uptime(at("2026-10-13T12:00"), clock))
        assertEquals("up 3 hours", uptime(at("2026-10-19T17:45"), clock))
    }

    @Test
    fun sizesAndVersions() {
        assertEquals("412 MB", fmtSize(412L * 1024 * 1024))
        assertEquals("2.4 GB", fmtSize((2.4 * 1024 * 1024 * 1024).toLong()))
        assertEquals("1 s", runLength(400))
        assertEquals("4 min", runLength(252_000))
        assertEquals("1 h 5 min", runLength(3_900_000))
        assertEquals("12.2", majorMinor("12.2.0"))
        assertEquals("0.1.0", shortVersion("0.1.0.0"))
        assertTrue(cmpVersion("10.11.0.0", "12.2.0") < 0)
        assertEquals("21 Mbps", mbps(21_400_000))
        assertEquals("2.5 Mbps", mbps(2_500_000))
        assertEquals("Linux x64", platformLabel(SystemInfo(systemArchitecture = "X64", programDataPath = "/config")))
    }

    @Test
    fun tasks() {
        val daily = TaskTrigger("DailyTrigger", timeOfDayTicks = 3L * 60 * 600_000_000)
        val weekly = TaskTrigger("WeeklyTrigger", timeOfDayTicks = 4L * 60 * 600_000_000, dayOfWeek = "Sunday")
        val every12 = TaskTrigger("IntervalTrigger", intervalTicks = 12L * 60 * 600_000_000)
        assertEquals("Daily at 03:00", scheduleText(TaskInfo(triggers = listOf(daily))))
        assertEquals("Sundays at 04:00", scheduleText(TaskInfo(triggers = listOf(weekly))))
        assertEquals("Every 12 hours", scheduleText(TaskInfo(triggers = listOf(every12))))
        assertEquals("Every 24 hours and on startup", scheduleText(TaskInfo(triggers = listOf(TaskTrigger("StartupTrigger"), every12.copy(intervalTicks = 24L * 60 * 600_000_000)))))
        assertEquals("After each scan", scheduleText(TaskInfo(key = "TaskExtractMediaSegments")))
        val done = TaskInfo(lastExecutionResult = TaskResult(startTimeUtc = "2026-10-19T01:00:00Z", endTimeUtc = "2026-10-19T01:22:00Z"))
        assertEquals("Today, 22 min", lastRun(done, clock).text)
        val failed = TaskInfo(lastExecutionResult = TaskResult(startTimeUtc = "2026-10-18T02:00:00Z", endTimeUtc = "2026-10-18T02:01:00Z", status = "Failed"))
        assertEquals("Failed yesterday", lastRun(failed, clock).text)
        assertEquals(at("2026-10-20T03:00"), nextRun(TaskInfo(triggers = listOf(daily)), clock))
        assertEquals("Scan media library", sentenceCase("Scan Media Library"))
        assertEquals("Refresh TheMovieDb", sentenceCase("Refresh TheMovieDb"))
    }

    @Test
    fun pluginUpdatesAndAbi() {
        val installed = listOf(PluginInfo("Intro Skipper", "1.0.0.0", id = "aa"), PluginInfo("Old", "1.0.0.0", id = "bb", status = "Malfunctioned"))
        val packages = listOf(
            PackageInfo("Intro Skipper", guid = "aa", versions = listOf(PackageVersion("1.1.0.0", targetAbi = "12.0.0.0"), PackageVersion("2.0.0.0", targetAbi = "13.0.0.0"))),
            PackageInfo("Old", guid = "bb", versions = listOf(PackageVersion("1.0.0.0", targetAbi = "10.11.0.0"))),
        )
        assertEquals(listOf("Intro Skipper" to "1.1.0.0"), pluginUpdates(installed, packages, "12.2.0").map { it.first.name to it.second })
        assertEquals("Built for Jellyfin 10.11; 12.2 needs a rebuilt version.", wontLoadReason(installed[1], packages, "12.2.0"))
        assertEquals("Open Subtitles and Intro Skipper", listing(listOf("Open Subtitles", "Intro Skipper")))
    }
}
