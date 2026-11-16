package io.github.arkmind.seedy.tv.update

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.update.InstallState
import io.github.arkmind.seedy.core.update.ReleaseItem
import io.github.arkmind.seedy.core.update.UpdateOffer
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.signin.TvBrand
import io.github.arkmind.seedy.ui.components.DeepScenes
import io.github.arkmind.seedy.ui.components.DeepSea
import io.github.arkmind.seedy.ui.components.Hop
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.Spinner
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.components.rememberStillMotion
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.ReleaseIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.update.AppUpdates
import io.github.arkmind.seedy.ui.update.rememberUpdateActions
import io.github.arkmind.seedy.ui.update.sourceLine

private val UpdateIcon = IconDef(2.4f, "M12 3v12", "M7 10l5 5 5-5", "M5 20h14")
private val DeepText = Color(0xFFB4B4B4)
private val ListBg = Color(0xDB141414)
private val Faint = Color.White.copy(alpha = .08f)

/**
 * Board "Android TV · Update ready": Medu on the left, what the new version brings on the right, Update (where the
 * remote starts) and Later; Back is Later too. Outside Google Play the APK downloads here ([install]: its progress
 * along the button), then Android's own dialog takes over. Focus stays on the screen while it's up.
 */
@Composable
fun TvUpdateScreen(offer: UpdateOffer, installed: String, brand: String, onUpdate: () -> Unit, onLater: () -> Unit, install: InstallState = InstallState.Idle) {
    BackHandler(onBack = onLater)
    val accent = LocalAccent.current
    val update = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.TvUpdate)
        TvBrand(brand, Modifier.padding(start = 96.bp, top = 64.bp))
        Box(Modifier.offset(x = 180.bp).width(680.bp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Hop { Medu(MeduMood.Yay, 380.bp, height = 502.bp) }
        }
        Column(
            Modifier.fillMaxSize().padding(start = 940.bp, end = 130.bp).focusProperties { onExit = { cancelFocusChange() } }.focusGroup(),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(
                Modifier.clip(CircleShape).background(accent.copy(alpha = .14f)).padding(horizontal = 18.bp, vertical = 8.bp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.bp),
            ) {
                SText(t("Update ready"), size = 19.bsp, weight = 800, color = accent, tracking = .08f, uppercase = true, maxLines = 1)
                SText("$installed → ${offer.version}", size = 19.bsp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
            }
            SText(
                t("A fresh Seedy is here"), Modifier.padding(top = 22.bp),
                size = 72.bsp, weight = 900, lineHeight = 1.04f, tracking = -.02f, color = SeedyColors.White,
            )
            val intro = t("Version {version} brings a few things Medu is very excited about.", "version" to offer.version)
            // Google Play restarts the app after its update; the APK's install leaves it closed.
            SText(
                if (offer.fromPlay) intro + " " + t("Seedy reopens right where you were.") else intro,
                Modifier.padding(top = 16.bp).widthIn(max = 860.bp),
                size = 28.bsp, lineHeight = 1.45f, color = DeepText,
            )
            if (offer.items.isNotEmpty()) {
                Column(Modifier.padding(top = 36.bp).fillMaxWidth().clip(RoundedCornerShape(28.bp)).background(ListBg)) {
                    offer.items.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.bp).background(Color.White.copy(alpha = .07f)))
                        ItemRow(item, accent)
                    }
                }
            }
            Row(Modifier.padding(top = 44.bp), horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                UpdateButton(install, accent, onUpdate, Modifier.focusRequester(update))
                BigButton(onLater, primary = false) { ink -> SText(t("Later"), size = 28.bsp, weight = 800, color = ink, maxLines = 1) }
            }
            val failed = install as? InstallState.Failed
            Row(Modifier.padding(top = 30.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.bp)) {
                SText(
                    when {
                        failed?.download == true -> t("Couldn’t download the update. Check your connection, then try again.")
                        failed != null -> t("Android couldn’t install the update.")
                        else -> sourceLine(offer, wifi = false)
                    },
                    size = 21.bsp, color = if (failed != null) SeedyColors.Danger else SeedyColors.Text3, maxLines = 1,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                    KeyCap(t("Back"))
                    SText(t("Later"), size = 21.bsp, color = SeedyColors.Text3, maxLines = 1)
                }
            }
        }
    }
    LaunchedEffect(Unit) { update.focusSoon() }
}

@Composable
private fun ItemRow(item: ReleaseItem, accent: Color) {
    val french = I18n.lang == Lang.Fr && item.frTitle != null
    val title = if (french) item.frTitle.orEmpty() else item.title
    val text = if (french) item.frText else item.text
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.bp, vertical = 22.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.bp)) {
        Box(Modifier.size(60.bp).clip(RoundedCornerShape(17.bp)).background(Faint), contentAlignment = Alignment.Center) {
            SIcon(ReleaseIcons[item.icon], size = 30.bp, color = accent, stroke = 2.2f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.bp)) {
            SText(title, size = 27.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
            if (text != null) SText(text, size = 21.bsp, lineHeight = 1.35f, color = SeedyColors.Text2)
        }
    }
}

/** Update; while the APK downloads, its progress along the bottom; then "Installing…"; after a failure, "Try again". */
@Composable
private fun UpdateButton(install: InstallState, accent: Color, onUpdate: () -> Unit, modifier: Modifier) {
    val busy = install is InstallState.Downloading || install is InstallState.Installing
    BigButton({ if (!busy) onUpdate() }, primary = true, modifier = modifier, progress = (install as? InstallState.Downloading)?.progress, accent = accent) { ink ->
        when (install) {
            is InstallState.Downloading -> {
                Spinner(30.bp, ink, stroke = 2.4f)
                val percent = install.progress?.let { (it * 100).toInt() }
                SText(if (percent != null) t("Downloading… {n}%", "n" to percent) else t("Downloading…"), size = 28.bsp, weight = 800, color = ink, maxLines = 1)
            }
            InstallState.Installing -> {
                Spinner(30.bp, ink, stroke = 2.4f)
                SText(t("Installing…"), size = 28.bsp, weight = 800, color = ink, maxLines = 1)
            }
            is InstallState.Failed -> {
                SIcon(WebIcons.Refetch, size = 30.bp, color = ink, stroke = 2.4f)
                SText(t("Try again"), size = 28.bsp, weight = 800, color = ink, maxLines = 1)
            }
            InstallState.Idle -> {
                SIcon(UpdateIcon, size = 30.bp, color = ink)
                SText(t("Update"), size = 28.bsp, weight = 800, color = ink, maxLines = 1)
            }
        }
    }
}

/**
 * The board's 84 px button (22 px corners, at least 260 px wide): translucent at rest, light when [primary]; focused
 * it's light, lifts 6 %, casts the boards' shadow and pulses an 8 px ring of the accent. [progress] fills a bar along
 * its bottom.
 */
@Composable
private fun BigButton(
    onClick: () -> Unit,
    primary: Boolean,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    accent: Color = LocalAccent.current,
    content: @Composable (ink: Color) -> Unit,
) {
    val still = rememberStillMotion()
    TvPressable(onClick, modifier) { focused ->
        val light = focused || primary
        val ink = if (light) SeedyColors.InkInverse else SeedyColors.Text
        val pulse = if (focused && !still) rememberPulse() else 0f
        Box(
            Modifier.focusScale(focused, 1.06f)
                .then(if (focused) Modifier.boxShadow(Color.Black.copy(alpha = .55f), 44.bp, 20.bp, 22.bp).pulseRing(pulse, accent, 22.bp) else Modifier)
                .height(84.bp).defaultMinSize(minWidth = 260.bp).clip(RoundedCornerShape(22.bp))
                .background(if (light) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f)),
        ) {
            Row(
                Modifier.align(Alignment.Center).padding(horizontal = 46.bp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.bp),
            ) { content(ink) }
            if (progress != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(8.bp).background(accent))
        }
    }
}

/** 0 → 1 → 0 every 2.4 s (CSS `tv-focus`, ease-in-out). */
@Composable
private fun rememberPulse(): Float {
    val p by rememberInfiniteTransition(label = "focus-pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = EaseInOut), RepeatMode.Reverse), label = "pulse",
    )
    return p
}

/** CSS `box-shadow: 0 0 0 8px` of the accent at .16, grown by [p]. */
private fun Modifier.pulseRing(p: Float, accent: Color, radius: Dp): Modifier = drawBehind {
    if (p <= 0f) return@drawBehind
    val spread = 8.bp.toPx() * p
    drawRoundRect(
        accent.copy(alpha = .16f * p), Offset(-spread, -spread), Size(size.width + 2 * spread, size.height + 2 * spread),
        CornerRadius(radius.toPx() + spread),
    )
}

/** "[Back]": a key on the remote, outlined. */
@Composable
private fun KeyCap(key: String) {
    Box(
        Modifier.height(34.bp).clip(RoundedCornerShape(9.bp)).drawBehind {
            val w = 2.bp.toPx()
            drawRoundRect(Color(0xFF5A5A5A), Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(9.bp.toPx() - w / 2), style = Stroke(w))
        }.padding(horizontal = 12.bp),
        contentAlignment = Alignment.Center,
    ) { SText(key, size = 18.bsp, weight = 800, color = SeedyColors.TextHero, maxLines = 1) }
}

/** The update screen over the TV's places while there's an update to offer ([visible]: not over a page or the player). */
@Composable
fun TvUpdatePrompt(visible: Boolean, brand: String) {
    val offer = AppUpdates.offer ?: return
    if (!visible) return
    val actions = rememberUpdateActions(offer)
    TvUpdateScreen(offer, AppUpdates.installed, brand, actions.onUpdate, actions.onLater, actions.install)
}
