package io.github.arkmind.seedy.core.settings

import android.content.Context
import android.view.accessibility.CaptioningManager
import android.view.accessibility.CaptioningManager.CaptionStyle

/** "Use your phone's caption style": Android's caption preferences in place of Seedy's look (the position stays Seedy's). */
object SystemCaptions {
    fun look(context: Context, base: ResolvedStyle): ResolvedStyle {
        val cm = context.getSystemService(CaptioningManager::class.java) ?: return base
        val s = cm.userStyle
        val outline = if (!s.hasEdgeType()) "shadow" else when (s.edgeType) {
            CaptionStyle.EDGE_TYPE_NONE -> "none"
            CaptionStyle.EDGE_TYPE_OUTLINE -> "outline"
            CaptionStyle.EDGE_TYPE_DROP_SHADOW -> "shadow"
            CaptionStyle.EDGE_TYPE_RAISED, CaptionStyle.EDGE_TYPE_DEPRESSED -> "raised"
            else -> "shadow"
        }
        fun argb(c: Int): Long = c.toLong() and 0xFFFFFFFFL
        return base.copy(
            size = "normal", weight = "normal", font = "system", outline = outline,
            system = SystemLook(
                color = if (s.hasForegroundColor()) argb(s.foregroundColor) else 0xFFFFFFFF,
                background = if (s.hasBackgroundColor()) argb(s.backgroundColor) else 0,
                edge = if (s.hasEdgeColor()) argb(s.edgeColor) else 0xFF000000,
                scale = cm.fontScale.takeIf { it > 0f } ?: 1f,
                typeface = s.typeface,
            ),
        )
    }
}
