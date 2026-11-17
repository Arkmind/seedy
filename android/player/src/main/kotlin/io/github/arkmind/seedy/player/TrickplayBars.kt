package io.github.arkmind.seedy.player

import android.graphics.BitmapFactory
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.trickPlayApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import java.util.UUID

/**
 * Black bars baked into [item]'s frame ([BlackBars]), read from up to two trickplay sheets in the middle of the film
 * (logos and credits sit at the ends). Null when it has no thumbnails or too few are lit to tell.
 */
internal suspend fun trickplayBars(api: ApiClient, userId: UUID, item: BaseItemDto): Bars? {
    // The items the player gets (next episode, a pick) don't all carry trickplay info: ask for it.
    val all = api.libraryApi.getItems(userId = userId, ids = listOf(item.id), fields = listOf(ItemFields.TRICKPLAY))
        .content.items.firstOrNull()?.trickplay ?: return null
    val sourceId = item.mediaSources?.firstOrNull()?.id
    val byWidth = sourceId?.let { all[it] } ?: all.values.firstOrNull() ?: return null
    val widths = byWidth.keys.mapNotNull { it.toIntOrNull() }.sorted()
    val width = widths.firstOrNull { it >= 320 } ?: widths.lastOrNull() ?: return null
    val info = byWidth[width.toString()] ?: return null
    val perSheet = info.tileWidth * info.tileHeight
    if (perSheet <= 0 || info.width <= 0 || info.height <= 0 || info.thumbnailCount <= 0) return null
    val sheets = (info.thumbnailCount + perSheet - 1) / perSheet
    val picks = if (sheets >= 3) listOf(sheets / 3, sheets * 2 / 3) else listOf(sheets / 2)
    val scans = mutableListOf<BarScan>()
    val px = IntArray(info.width * info.height)
    for (sheet in picks) {
        val bytes = runCatching {
            api.trickPlayApi.getTrickplayTileImage(item.id, width, sheet, sourceId?.toUUIDOrNull()).content
        }.getOrNull() ?: continue
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
        val count = minOf(perSheet, info.thumbnailCount - sheet * perSheet)
        for (n in 0 until count) {
            val x = (n % info.tileWidth) * info.width
            val y = (n / info.tileWidth) * info.height
            if (x + info.width > bitmap.width || y + info.height > bitmap.height) break
            bitmap.getPixels(px, 0, info.width, x, y, info.width, info.height)
            BlackBars.scan(px, info.width, info.height)?.let(scans::add)
        }
        bitmap.recycle()
    }
    return BlackBars.combine(scans, BlackBars.minLit(scans.size))
}
