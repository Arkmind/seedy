package io.github.arkmind.seedy.core.livetv

import io.github.arkmind.seedy.core.format.duration
import io.github.arkmind.seedy.core.format.episodeTag
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.GetProgramsDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.TimerInfoDto
import org.jellyfin.sdk.model.api.RecordingStatus
import java.text.Collator
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A channel as the guide lists it: Seedy numbers channels that have no number, and cleans their names. */
data class LiveChannel(
    val item: BaseItemDto,
    val number: String,
    /** The name without "[FR]" tags or "(1080p)" suffixes. */
    val label: String,
    /** "HD" or "4K", read from the name. */
    val hd: String?,
) {
    val id: UUID get() = item.id
    val favorite: Boolean get() = item.userData?.isFavorite == true
    val current: BaseItemDto? get() = item.currentProgram
}

/** Live TV for the signed-in person: channels, the guide's programmes, recordings and their timers (web: screens/livetv/data.ts). */
class LiveTv(private val session: UserSession) {
    private val api get() = session.api

    suspend fun channels(): List<LiveChannel> = io {
        val items = api.liveTvApi.getLiveTvChannels(
            userId = session.userId, addCurrentProgram = true, enableUserData = true, enableImages = true, imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
            fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.CHANNEL_INFO),
            sortBy = listOf(ItemSortBy.SORT_NAME),
        ).content.items
        decorate(items)
    }

    /** The programmes on [channelIds] between [from] and [to]. */
    suspend fun programs(channelIds: List<UUID>, from: LocalDateTime, to: LocalDateTime): List<BaseItemDto> = io {
        if (channelIds.isEmpty()) return@io emptyList()
        api.liveTvApi.getPrograms(
            GetProgramsDto(
                channelIds = channelIds, userId = session.userId, minEndDate = from, maxStartDate = to,
                enableImages = true, imageTypeLimit = 1, enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB, ImageType.BACKDROP),
                enableUserData = false, fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.CHANNEL_INFO),
                enableTotalRecordCount = false, sortBy = listOf(ItemSortBy.START_DATE),
            ),
        ).content.items
    }

    suspend fun timers(): List<TimerInfoDto> = io { runCatching { api.liveTvApi.getTimers().content.items }.getOrDefault(emptyList()) }

    suspend fun recordings(): List<BaseItemDto> = io {
        runCatching {
            api.liveTvApi.getRecordings(
                userId = session.userId, enableImages = true, imageTypeLimit = 1, enableUserData = true,
                fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.CHANNEL_INFO),
            ).content.items
        }.getOrDefault(emptyList())
    }

    /** Records [program] with the server's defaults (padding), or cancels its recording. True when it's now set. */
    suspend fun toggleRecord(program: BaseItemDto, timers: List<TimerInfoDto>): Boolean = io {
        val existing = timers.firstOrNull { (program.timerId != null && it.id == program.timerId) || it.programId == program.id.toString() }
        if (existing?.id != null) {
            api.liveTvApi.cancelTimer(existing.id!!)
            false
        } else {
            val defaults = api.liveTvApi.getDefaultTimer(program.id.toString()).content
            api.liveTvApi.createTimer(
                TimerInfoDto(
                    channelId = defaults.channelId, programId = defaults.programId, name = defaults.name,
                    startDate = defaults.startDate, endDate = defaults.endDate,
                    prePaddingSeconds = defaults.prePaddingSeconds, postPaddingSeconds = defaults.postPaddingSeconds,
                    isPrePaddingRequired = defaults.isPrePaddingRequired, isPostPaddingRequired = defaults.isPostPaddingRequired,
                    priority = defaults.priority, keepUntil = defaults.keepUntil,
                ),
            )
            true
        }
    }

    suspend fun setFavorite(channel: LiveChannel, favorite: Boolean) = io {
        if (favorite) api.userDataApi.markFavoriteItem(channel.id, session.userId) else api.userDataApi.unmarkFavoriteItem(channel.id, session.userId)
    }

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        private val tag = Regex("\\s*\\[[^\\]]*]\\s*")
        private val resolution = Regex("\\s*\\((?:\\d{3,4}[pi]|SD|HD|FHD|UHD|4K)\\)\\s*", RegexOption.IGNORE_CASE)

        fun cleanName(name: String): String = name.replace(tag, " ").replace(resolution, " ").replace(Regex("\\s+"), " ").trim().ifEmpty { name }

        private fun hdOf(name: String): String? {
            Regex("\\((\\d{3,4})[pi]\\)", RegexOption.IGNORE_CASE).find(name)?.let {
                val p = it.groupValues[1].toInt()
                return if (p >= 2000) "4K" else if (p >= 720) "HD" else null
            }
            return when {
                Regex("\\b(UHD|4K)\\b").containsMatchIn(name) -> "4K"
                Regex("\\b(HD|FHD)\\b").containsMatchIn(name) -> "HD"
                else -> null
            }
        }

        /** Numbers the channels the tuner left without one (by name, after the numbers in use), then sorts by number. */
        fun decorate(items: List<BaseItemDto>): List<LiveChannel> {
            val collator = Collator.getInstance(I18n.locale).apply { strength = Collator.PRIMARY }
            val used = items.mapNotNull { it.channelNumber?.takeIf(String::isNotBlank) ?: it.number?.takeIf(String::isNotBlank) }.toMutableSet()
            val numbers = mutableMapOf<UUID, String>()
            var next = 1
            for (c in items.sortedWith { a, b -> collator.compare(cleanName(a.name.orEmpty()), cleanName(b.name.orEmpty())) }) {
                val own = c.channelNumber?.takeIf(String::isNotBlank) ?: c.number?.takeIf(String::isNotBlank)
                if (own != null) {
                    numbers[c.id] = own
                    continue
                }
                while (next.toString() in used) next++
                numbers[c.id] = next.toString()
                used += next.toString()
            }
            return items.map { LiveChannel(it, numbers[it.id].orEmpty(), cleanName(it.name.orEmpty()), hdOf(it.name.orEmpty())) }
                .sortedWith(compareBy<LiveChannel> { it.number.toDoubleOrNull() ?: Double.MAX_VALUE }.thenComparator { a, b -> collator.compare(a.label, b.label) })
        }

        /** Two letters for a channel without a logo ("Kestrel Sports" → KS, "Meridian" → Me). */
        fun initials(name: String): String {
            val words = cleanName(name).replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size >= 2) return "${words[0][0]}${words[1][0]}".uppercase()
            val w = words.firstOrNull() ?: "?"
            return if (w.length > 1) "${w[0].uppercaseChar()}${w[1].lowercaseChar()}" else w.uppercase()
        }

        private val logoColours = listOf(0xFF2F4A7A, 0xFFB33A3A, 0xFF1F7A3A, 0xFF6B3FA0, 0xFFC9852B, 0xFF2A6F73, 0xFF2B3F8F, 0xFF3F8FB3, 0xFF8A6D3B, 0xFFF2C94C, 0xFFD96A9B, 0xFF3A3A3A)
        private val lightColours = setOf(0xFFC9852B, 0xFFF2C94C, 0xFFD96A9B)

        /** The boards' channel colours, picked by name: (background, ink) as ARGB. */
        fun channelColour(name: String): Pair<Long, Long> {
            var h = 0L
            for (ch in cleanName(name)) h = (h * 31 + ch.code) and 0xFFFFFFFFL
            val bg = logoColours[(h % logoColours.size).toInt()]
            return bg to (if (bg in lightColours) 0xFF1E1E1E else 0xFFFFFFFF)
        }

        private val hhmm = DateTimeFormatter.ofPattern("HH:mm")

        fun clockOf(t: LocalDateTime?): String = t?.format(hhmm).orEmpty()

        /** "20:45 – 22:45". */
        fun timeRange(p: BaseItemDto): String = "${clockOf(p.startDate)} – ${clockOf(p.endDate)}"

        fun isAiring(p: BaseItemDto?, now: LocalDateTime): Boolean = p?.startDate != null && p.endDate != null && !p.startDate!!.isAfter(now) && now.isBefore(p.endDate)

        /** How far into the programme it is, 0–1. */
        fun fraction(p: BaseItemDto?, now: LocalDateTime): Float {
            val s = p?.startDate ?: return 0f
            val e = p.endDate ?: return 0f
            val total = Duration.between(s, e).toMillis().toFloat()
            return if (total > 0) (Duration.between(s, now).toMillis() / total).coerceIn(0f, 1f) else 0f
        }

        /** "55 min left". */
        fun leftLabel(p: BaseItemDto, now: LocalDateTime): String {
            val left = p.endDate?.let { Duration.between(now, it).seconds.toDouble() } ?: 0.0
            return t("{time} left", "time" to duration(left.coerceAtLeast(60.0)))
        }

        /** "The Undercroft · S1 E3". */
        fun programTitle(p: BaseItemDto): String {
            val ep = if (p.isSeries == true && p.indexNumber != null) p.episodeTag else ""
            return if (ep.isNotEmpty()) "${p.name.orEmpty()} · $ep" else p.name.orEmpty()
        }

        private val generic = setOf("movie", "movies", "film", "sports", "sport", "news", "kids", "children", "series", "show", "tv show", "documentary")

        /** The programme's kind as the boards write it: its own genre ("Football"), else Film, Sports, News… */
        fun categoryOf(p: BaseItemDto): String {
            p.genres.orEmpty().firstOrNull { it.lowercase() !in generic }?.let { return it }
            return when {
                p.isMovie == true -> t("Film")
                p.isSports == true -> t("Sports")
                p.isNews == true -> t("News")
                p.isKids == true -> t("Kids")
                p.genres.orEmpty().any { it.equals("documentary", true) } -> t("Documentary")
                p.isSeries == true -> t("Series")
                else -> ""
            }
        }

        /** A timer recording right now, or set for [p]. */
        fun recState(p: BaseItemDto?, timers: List<TimerInfoDto>): String? {
            if (p == null) return null
            val timer = timers.firstOrNull { (p.timerId != null && it.id == p.timerId) || it.programId == p.id.toString() } ?: return null
            return if (timer.status == RecordingStatus.IN_PROGRESS) "now" else "set"
        }
    }
}
