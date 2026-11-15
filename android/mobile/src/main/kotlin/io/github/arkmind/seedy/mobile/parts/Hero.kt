package io.github.arkmind.seedy.mobile.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlin.math.min

/* The title pages' hero (details, a Seerr title, Home's banner): its title size, glass buttons and scrims. */

/** [max] px, smaller when the title's longest word wouldn't fit on a line (Noto Sans Black capitals run about 0.72 em). */
fun fitTitleSize(title: String, maxWidth: Dp, max: Float = 32f): TextUnit {
    val longest = title.split(Regex("\\s+")).maxOfOrNull { it.length }?.coerceAtLeast(4) ?: 4
    return min(max, maxWidth.value / (longest * 0.72f)).sp
}

/** A 44 px glass button over the hero's art; [label] names it for accessibility. */
@Composable
fun GlassButton(icon: IconDef, size: Dp, onClick: () -> Unit, label: String? = null) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Bg.copy(alpha = 0.5f)).tap(onClick = onClick)
            .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        SIcon(icon, size = size, color = SeedyColors.White)
    }
}

/** The hero art's scrims: dark under the top buttons, fading into the page at the foot. */
@Composable
fun BoxScope.HeroScrims() {
    Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))))
    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.7f).background(Brush.verticalGradient(listOf(Color.Transparent, SeedyColors.Bg))))
}
