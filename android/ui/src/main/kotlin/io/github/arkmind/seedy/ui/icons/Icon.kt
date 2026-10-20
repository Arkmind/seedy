package io.github.arkmind.seedy.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** One shape of an icon: SVG path data on a 24 px grid, outlined, and filled when [filled]. */
data class IconPart(val d: String, val filled: Boolean = false)

/** An icon as the boards draw it: shapes on a 24 px grid with round caps; [stroke] is the line width (0: solid). */
data class IconDef(val stroke: Float, val parts: List<IconPart>) {
    constructor(stroke: Float, vararg paths: String) : this(stroke, paths.map { IconPart(it) })
}

/** Draws [icon] at [size] in [color]. [stroke] overrides the line width where a board draws it thicker or thinner. */
@Composable
fun SIcon(icon: IconDef, modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = SeedyColors.Text, stroke: Float = icon.stroke) {
    val vector = remember(icon, stroke) { icon.toVector(stroke) }
    Image(rememberVectorPainter(vector), contentDescription = null, modifier = modifier.size(size), colorFilter = ColorFilter.tint(color))
}

private fun IconDef.toVector(stroke: Float): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        for (part in parts) {
            addPath(
                pathData = PathParser().parsePathString(part.d).toNodes(),
                fill = if (part.filled) SolidColor(Color.Black) else null,
                stroke = if (stroke > 0f) SolidColor(Color.Black) else null,
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

/** Icons only the Android boards draw (the web set is in [WebIcons]). */
object AppIcons {
    val Home = IconDef(2f, "M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z")
    val Libraries = IconDef(
        2f,
        "M5.5 4h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M15 4h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M5.5 13.5h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
        "M15 13.5h3.5a1.5 1.5 0 0 1 1.5 1.5v3.5a1.5 1.5 0 0 1-1.5 1.5h-3.5a1.5 1.5 0 0 1-1.5-1.5v-3.5a1.5 1.5 0 0 1 1.5-1.5Z",
    )
    val Requests = IconDef(2f, "M12 3v10", "M8 9l4 4 4-4", "M4 14v4a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-4")
    val Movies = IconDef(
        2f,
        "M5 4h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M7 4v16M17 4v16", "M3 9h4M3 15h4M17 9h4M17 15h4",
    )
    val Shows = IconDef(2f, "M5 6h14a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M8 2.5l4 3.5 4-3.5")
    val LiveTv = IconDef(2f, "M12 12v9", "M8.5 8.5a5 5 0 0 1 7 0", "M5.6 5.6a9 9 0 0 1 12.8 0")
    val RailSearch = IconDef(2f, "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z", "M20 20l-4-4")
    val Phone = IconDef(2f, "M9.5 2.5h5a2.5 2.5 0 0 1 2.5 2.5v14a2.5 2.5 0 0 1-2.5 2.5h-5a2.5 2.5 0 0 1-2.5-2.5v-14a2.5 2.5 0 0 1 2.5-2.5Z", "M11 18h2")
    val ShowPassword = IconDef(2f, "M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0Z")
    val Server = IconDef(
        2f,
        "M5 4h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2Z",
        "M5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2Z", "M7 7.5h.01", "M7 16.5h.01",
    )
    val Mic = IconDef(2f, "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3Z", "M5 11a7 7 0 0 0 14 0", "M12 18v3")
    val MoreVertical = IconDef(
        0f,
        listOf(
            IconPart("M10.2 5a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0Z", filled = true),
            IconPart("M10.2 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0Z", filled = true),
            IconPart("M10.2 19a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0 -3.6 0Z", filled = true),
        ),
    )
    val History = IconDef(2f, "M3.5 12a8.5 8.5 0 1 0 17 0a8.5 8.5 0 1 0 -17 0Z", "M12 7.5V12l3 2")
    val Reorder = IconDef(2f, "M4 7h16", "M4 12h16", "M4 17h10")
    val ChannelNumber = IconDef(2f, "M5 9h14", "M5 15h14", "M10 4L8 20", "M16 4l-2 16")
}
