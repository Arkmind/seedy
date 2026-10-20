package io.github.arkmind.seedy.core.browse

import org.jellyfin.sdk.model.api.AudioSpatialFormat
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.VideoRange
import org.jellyfin.sdk.model.api.VideoRangeType

/* The details hero's format badges, as the web's media-info.ts: "4K", "HDR10", "Atmos", "CC", in that order. */

private fun streamsOf(item: BaseItemDto?): List<MediaStream> = item?.mediaSources?.firstOrNull()?.mediaStreams ?: item?.mediaStreams.orEmpty()

private fun resolution(v: MediaStream?): String? {
    if (v == null) return null
    val w = v.width ?: 0
    val h = v.height ?: 0
    if (w == 0 && h == 0) return null
    return when {
        w >= 3800 || h >= 2000 -> "4K"
        w >= 1900 || h >= 1000 -> "1080p"
        w >= 1260 || h >= 700 -> "720p"
        else -> "SD"
    }
}

private fun range(v: MediaStream?): String? = when (v?.videoRangeType) {
    null -> null
    VideoRangeType.HDR10 -> "HDR10"
    VideoRangeType.HDR10_PLUS -> "HDR10+"
    VideoRangeType.HLG -> "HLG"
    VideoRangeType.UNKNOWN, VideoRangeType.SDR -> if (v.videoRange == VideoRange.HDR) "HDR" else null
    else -> if (v.videoRangeType.serialName.startsWith("DOVI")) "Dolby Vision" else null
}

private fun isAtmos(a: MediaStream): Boolean =
    a.audioSpatialFormat == AudioSpatialFormat.DOLBY_ATMOS || listOfNotNull(a.profile, a.title, a.displayTitle).joinToString(" ").contains("atmos", ignoreCase = true)

fun techBadges(item: BaseItemDto?): List<String> {
    val ms = streamsOf(item)
    if (ms.isEmpty()) return emptyList()
    val v = ms.firstOrNull { it.type == MediaStreamType.VIDEO }
    return listOfNotNull(
        resolution(v),
        range(v),
        "Atmos".takeIf { ms.any { it.type == MediaStreamType.AUDIO && isAtmos(it) } },
        "CC".takeIf { ms.any { it.type == MediaStreamType.SUBTITLE } },
    )
}
