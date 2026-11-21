package io.github.arkmind.seedy.tv.wrapped

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.wrapped.WrappedTeaser
import io.github.arkmind.seedy.core.wrapped.WrappedViewModel
import io.github.arkmind.seedy.core.wrapped.bannerText
import io.github.arkmind.seedy.tv.nav.LocalTvNav
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import io.github.arkmind.seedy.ui.wrapped.WrappedIcons
import io.github.arkmind.seedy.ui.wrapped.WrappedInk
import io.github.arkmind.seedy.ui.wrapped.WrappedLilac
import io.github.arkmind.seedy.ui.wrapped.cssGradient

/** Seedy Wrapped for [year] on the TV (from the December banner or Settings). */
@Composable
fun TvWrappedRoute(year: Int) {
    val session = currentSession()
    val nav = LocalTvNav.current
    val vm = viewModel(key = "wrapped-$year") { WrappedViewModel(session, year) }
    val state by vm.state.collectAsStateWithLifecycle()
    val w = state.wrapped
    when {
        w != null -> TvWrappedScreen(w, onClose = nav::back)
        state.failed -> {
            BackHandler(onBack = nav::back)
            Box(Modifier.fillMaxSize().background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 900.bp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.bp)) {
                    Medu(MeduMood.Sleepy, 200.bp, height = 264.bp)
                    SText(t("Your Wrapped isn’t ready"), size = 44.bsp, weight = 900, color = Color.White, align = TextAlign.Center)
                    SText(t("Seedy Wrapped arrives every December, once you’ve watched a few things."), size = 26.bsp, lineHeight = 1.45f, color = SeedyColors.Text2, align = TextAlign.Center)
                }
            }
        }
        else -> Box(Modifier.fillMaxSize().background(SeedyColors.Bg), contentAlignment = Alignment.Center) {
            BackHandler(onBack = nav::back)
            Medu(MeduMood.Think, 200.bp, height = 264.bp)
        }
    }
}

/**
 * December's Wrapped card on the TV home, over the rows (no TV board: the phone's banner, "Android · Home · Wrapped",
 * at TV size): Medu in party mode on the accent, the year's line and an arrow; OK opens the stories.
 */
@Composable
fun TvWrappedBanner(teaser: WrappedTeaser, onOpen: () -> Unit, modifier: Modifier = Modifier, onFocus: () -> Unit = {}) {
    val accent = LocalAccent.current
    TvPressable(onOpen, modifier.onFocusChanged { if (it.isFocused) onFocus() }) { focused ->
        Row(
            Modifier.width(880.bp).focusScale(focused, 1.03f).focusRing(focused, 32.bp).clip(RoundedCornerShape(32.bp))
                .background(cssGradient(115f, 0f to accent, 1f to WrappedLilac))
                .drawBehind {
                    val r = 120.bp.toPx()
                    drawCircle(Color.White.copy(alpha = .25f), r - 13.bp.toPx(), Offset(size.width + 48.bp.toPx() - r, r - 64.bp.toPx()), style = Stroke(26.bp.toPx()))
                }
                .padding(start = 10.bp, end = 26.bp, top = 16.bp, bottom = 16.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(18.bp),
        ) {
            Medu(MeduMood.Party, 96.bp, height = 127.bp, glow = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                SText(t("New · Seedy Wrapped"), size = 18.bsp, weight = 900, tracking = .1f, color = WrappedInk.copy(alpha = .65f), uppercase = true, maxLines = 1)
                SText(t("Your {year} is ready", "year" to teaser.year), size = 32.bsp, weight = 900, color = WrappedInk, maxLines = 1)
                SText(bannerText(teaser.watchMs, teaser.stories), size = 20.bsp, weight = 600, color = WrappedInk.copy(alpha = .75f), maxLines = 2)
            }
            Box(Modifier.size(58.bp).clip(CircleShape).background(WrappedInk), contentAlignment = Alignment.Center) {
                SIcon(WrappedIcons.Next, size = 28.bp, color = Color.White)
            }
        }
    }
}

/** Settings › Seedy Wrapped, under the sections: a gift, as the sections' rows look; OK opens the newest year. */
@Composable
fun TvWrappedSettingsRow(onOpen: () -> Unit) {
    TvPressable(onOpen) { f ->
        val ink = if (f) SeedyColors.InkInverse else SeedyColors.Text2
        Row(
            Modifier.fillMaxWidth().height(62.bp).focusScale(f, 1.02f).clip(RoundedCornerShape(18.bp))
                .background(if (f) SeedyColors.Inverse else Color.Transparent).padding(horizontal = 20.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            SIcon(Gift, size = 26.bp, color = ink, stroke = 2f)
            SText(t("Seedy Wrapped"), size = 21.bsp, weight = if (f) 800 else 600, color = ink, maxLines = 1)
        }
    }
}

private val Gift = IconDef(
    2.1f,
    "M4 11.5h16v8a1.5 1.5 0 0 1-1.5 1.5h-13a1.5 1.5 0 0 1-1.5-1.5z", "M3 7.5h18v4H3z", "M12 7.5V21",
    "M12 7.5H8.2a2.5 2.5 0 0 1 0-5C10.8 2.5 12 7.5 12 7.5z", "M12 7.5h3.8a2.5 2.5 0 0 0 0-5C13.2 2.5 12 7.5 12 7.5z",
)
