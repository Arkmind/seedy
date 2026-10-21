package io.github.arkmind.seedy.core.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.arkmind.seedy.core.data.AppStore
import io.github.arkmind.seedy.core.livetv.LiveChannel
import io.github.arkmind.seedy.core.livetv.LiveTv
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.TimerInfoDto
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID

/** Live TV for both screen sets: channels with what's on, the guide's window of programmes, timers and recordings. */
class LiveTvViewModel(private val session: UserSession, private val store: AppStore) : ViewModel() {
    private val live = LiveTv(session)

    data class State(
        val loading: Boolean = true,
        val channels: List<LiveChannel> = emptyList(),
        val favoritesOnly: Boolean = false,
        /** The guide's day and the time its grid starts at (the half hour before now, today). */
        val day: LocalDate = LocalDate.now(),
        val windowStart: LocalDateTime = halfHourBefore(LocalDateTime.now()),
        val programs: Map<UUID, List<BaseItemDto>> = emptyMap(),
        val timers: List<TimerInfoDto> = emptyList(),
        val recordings: List<BaseItemDto> = emptyList(),
        val lastChannelId: UUID? = null,
        val now: LocalDateTime = LocalDateTime.now(),
    ) {
        /** The channels the lists show: favourites only when that filter is on (and there are some). */
        val shown: List<LiveChannel>
            get() = if (favoritesOnly && channels.any { it.favorite }) channels.filter { it.favorite } else channels
        val lastWatched: LiveChannel? get() = channels.firstOrNull { it.id == lastChannelId }
        val recordingNow: Int get() = timers.count { it.status == org.jellyfin.sdk.model.api.RecordingStatus.IN_PROGRESS }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val key = "${session.server.id}:${session.userId}"

    init {
        viewModelScope.launch {
            val last = store.current().lastChannel[key]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            _state.update { it.copy(lastChannelId = last) }
            refresh()
        }
        // What's on moves on: the clock every 15 s, the channels' current programmes every minute.
        viewModelScope.launch {
            var ticks = 0
            while (isActive) {
                delay(15_000)
                _state.update { it.copy(now = LocalDateTime.now()) }
                if (++ticks % 4 == 0) refresh(programs = false)
            }
        }
    }

    private suspend fun refresh(programs: Boolean = true) {
        val channels = runCatching { live.channels() }.getOrNull()
        val timers = live.timers()
        _state.update { it.copy(loading = false, channels = channels ?: it.channels, timers = timers) }
        if (programs) loadPrograms()
    }

    /** The guide's programmes: from the window's start to the end of its day (and a little into the next morning). */
    private suspend fun loadPrograms() {
        val s = _state.value
        val to = s.day.plusDays(1).atTime(LocalTime.of(5, 0))
        val programs = runCatching { live.programs(s.channels.map { it.id }, s.windowStart, to) }.getOrDefault(emptyList())
        _state.update { it.copy(programs = programs.groupBy { p -> p.channelId ?: UUID(0, 0) }) }
    }

    fun loadRecordings() {
        viewModelScope.launch { _state.update { it.copy(recordings = live.recordings()) } }
    }

    fun setFavoritesOnly(on: Boolean) = _state.update { it.copy(favoritesOnly = on) }

    /** Moves the guide to [day]: today starts at the last half hour, other days at 6 in the morning. */
    fun setDay(day: LocalDate) {
        val start = if (day == LocalDate.now()) halfHourBefore(LocalDateTime.now()) else day.atTime(6, 0)
        _state.update { it.copy(day = day, windowStart = start) }
        viewModelScope.launch { loadPrograms() }
    }

    fun toggleRecord(program: BaseItemDto) {
        viewModelScope.launch {
            runCatching { live.toggleRecord(program, _state.value.timers) }
            _state.update { it.copy(timers = live.timers()) }
        }
    }

    fun toggleFavorite(channel: LiveChannel) {
        viewModelScope.launch {
            runCatching { live.setFavorite(channel, !channel.favorite) }
            refresh(programs = false)
        }
    }

    /** Remembers [channel] as this profile's last watched (Live TV's first card). */
    fun watched(channel: UUID) {
        _state.update { it.copy(lastChannelId = channel) }
        viewModelScope.launch { store.update { d -> d.copy(lastChannel = d.lastChannel + (key to channel.toString())) } }
    }

    /** What [channel] shows at [time] (from the guide when loaded, else its current programme). */
    fun programAt(channel: LiveChannel, time: LocalDateTime): BaseItemDto? =
        _state.value.programs[channel.id]?.firstOrNull { p -> LiveTv.isAiring(p, time) } ?: channel.current?.takeIf { LiveTv.isAiring(it, time) }

    companion object {
        fun halfHourBefore(t: LocalDateTime): LocalDateTime = t.withMinute(if (t.minute >= 30) 30 else 0).withSecond(0).withNano(0)
    }
}
