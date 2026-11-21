package io.github.arkmind.seedy.mobile.wrapped

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.wrapped.WrappedTeaser
import io.github.arkmind.seedy.core.wrapped.bannerText
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.wrapped.WrappedInk
import io.github.arkmind.seedy.ui.wrapped.WrappedLilac
import io.github.arkmind.seedy.ui.wrapped.cssGradient

/**
 * Board "Android · Home · Wrapped": December's banner under the hero (Medu in party mode on the accent, a ring off its
 * top right corner), opening this year's stories.
 */
@Composable
fun WrappedBanner(teaser: WrappedTeaser, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    Box(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cssGradient(115f, 0f to accent, 1f to WrappedLilac))
            .drawBehind {
                // A 16 px band inside a 150 px circle, 30 px past the right edge and 40 px above the top.
                val r = 75.dp.toPx()
                drawCircle(Color.White.copy(alpha = .25f), r - 8.dp.toPx(), Offset(size.width + 30.dp.toPx() - r, r - 40.dp.toPx()), style = Stroke(16.dp.toPx()))
            }
            .tap(onClick = onOpen).semantics { contentDescription = t("Open Seedy Wrapped {year}", "year" to teaser.year) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Medu(MeduMood.Party, 62.dp, height = 82.dp, glow = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                SText(t("New · Seedy Wrapped"), size = 11.sp, weight = 900, tracking = .1f, color = WrappedInk.copy(alpha = .65f), uppercase = true, maxLines = 1)
                SText(t("Your {year} is ready", "year" to teaser.year), size = 18.sp, weight = 900, lineHeight = 1.15f, color = WrappedInk, maxLines = 1)
                SText(bannerText(teaser.watchMs, teaser.stories), size = 12.sp, weight = 600, color = WrappedInk.copy(alpha = .75f), maxLines = 2)
            }
            Box(Modifier.size(36.dp).clip(CircleShape).background(WrappedInk), contentAlignment = Alignment.Center) {
                SIcon(WrappedIcons.Next, size = 18.dp, color = Color.White)
            }
        }
    }
}

/** Settings › Seedy Wrapped with more than one year: which one to open. */
@Composable
fun WrappedYearsSheet(years: List<Int>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    BottomSheet(onDismiss) {
        SText(t("Seedy Wrapped"), Modifier.padding(start = 20.dp, top = 6.dp, bottom = 10.dp), size = 18.sp, weight = 900, color = Color.White)
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (y in years) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp)).tap { onPick(y) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Medu(MeduMood.Party, 30.dp, height = 40.dp, glow = false)
                    SText(t("Wrapped {year}", "year" to y), Modifier.weight(1f), size = 16.sp, weight = 800, color = Color.White, maxLines = 1)
                    SIcon(AppIcons.Chevron, size = 16.dp, color = SeedyColors.Text3)
                }
            }
        }
    }
}
