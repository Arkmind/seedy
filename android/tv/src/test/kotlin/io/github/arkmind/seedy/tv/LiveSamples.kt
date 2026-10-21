package io.github.arkmind.seedy.tv

import io.github.arkmind.seedy.core.feature.LiveTvViewModel
import io.github.arkmind.seedy.core.livetv.LiveTv
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.RecordingStatus
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** The Live TV boards' channels and programmes, on Mon 19 Oct at 21:50. */
object LiveSamples {
    private val day = LocalDate.of(2026, 10, 19)
    val now: LocalDateTime = day.atTime(21, 50)

    private fun at(hhmm: String): LocalDateTime {
        val t = LocalTime.parse(hhmm)
        return if (t.hour < 6) day.plusDays(1).atTime(t) else day.atTime(t)
    }

    private val data = listOf(
        Triple("1", "Northline One", listOf("21:00" to "22:10" to "The Glass House", "22:10" to "23:00" to "Late Desk", "23:00" to "00:45" to "Copper Line")),
        Triple("2", "Harbor News 24", listOf("21:30" to "22:00" to "World Tonight", "22:00" to "22:30" to "Markets Close", "22:30" to "23:00" to "World Tonight", "23:00" to "00:00" to "Overnight")),
        Triple("3", "Kestrel Sports", listOf("20:45" to "22:45" to "Euro Cup: Lyon v Porto", "22:45" to "23:30" to "Match Night")),
        Triple("4", "Cinema Nova", listOf("20:50" to "22:40" to "Halcyon", "22:40" to "00:35" to "Night Ferry")),
        Triple("7", "Meridian", listOf("21:10" to "22:05" to "The Undercroft", "22:05" to "23:00" to "Saltmarsh", "23:00" to "23:55" to "The Undercroft")),
        Triple("10", "Cobalt Crime", listOf("20:45" to "21:40" to "Cold Files", "21:40" to "22:35" to "Cold Files", "22:35" to "23:30" to "Cold Files")),
    )

    private fun id(s: String) = UUID.nameUUIDFromBytes(s.toByteArray())

    val programs: Map<UUID, List<BaseItemDto>> = data.associate { (_, name, progs) ->
        id(name) to progs.map { (range, title) ->
            BaseItemDto(
                id = id("$name ${range.first}"), type = BaseItemKind.PROGRAM, name = title, channelId = id(name), channelName = name,
                startDate = at(range.first), endDate = at(range.second),
                genres = if (name == "Kestrel Sports") listOf("Football") else null,
                overview = if (name == "Kestrel Sports") "Second half of the group-stage decider. Lyon need a draw to go through; Porto need to win by two." else null,
            )
        }
    }

    val channels = LiveTv.decorate(
        data.map { (num, name, _) ->
            val c = id(name)
            BaseItemDto(
                id = c, type = BaseItemKind.TV_CHANNEL, name = name, channelNumber = num,
                currentProgram = programs[c]?.firstOrNull { LiveTv.isAiring(it, now) },
                userData = UserItemDataDto(playbackPositionTicks = 0, playCount = 0, isFavorite = true, played = false, key = c.toString(), itemId = c),
            )
        },
    )

    val kestrel get() = channels.first { it.label == "Kestrel Sports" }
    val euroCup get() = programs[kestrel.id]!!.first()

    val state = LiveTvViewModel.State(
        loading = false, channels = channels, favoritesOnly = true, day = day, windowStart = day.atTime(21, 30), programs = programs,
        timers = listOf(TimerInfoDto(id = "t1", programId = programs[id("Cobalt Crime")]!![1].id.toString(), status = RecordingStatus.IN_PROGRESS)),
        lastChannelId = id("Kestrel Sports"), now = now,
    )
}
