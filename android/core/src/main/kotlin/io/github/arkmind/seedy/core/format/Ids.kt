package io.github.arkmind.seedy.core.format

import java.util.UUID

/** Jellyfin's ids often come without dashes ("7e64e319657a9516ec78490da03edccb"); a UUID string wants them. */
fun dashed(id: String): String =
    if (id.length == 32 && '-' !in id) "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}" else id

/** A Jellyfin id, with or without dashes, as the SDK's UUID; null when it is blank or not one. */
fun uuidOf(id: String?): UUID? {
    val s = id?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { UUID.fromString(dashed(s)) }.getOrNull()
}
