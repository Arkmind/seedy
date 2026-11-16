package io.github.arkmind.seedy.mobile.update

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.photos.bytesLabel
import io.github.arkmind.seedy.core.update.ReleaseItem
import io.github.arkmind.seedy.core.update.UpdateOffer
import io.github.arkmind.seedy.mobile.parts.blockTaps
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.signin.DeepText
import io.github.arkmind.seedy.ui.components.DeepScenes
import io.github.arkmind.seedy.ui.components.DeepSea
import io.github.arkmind.seedy.ui.components.Hop
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.ReleaseIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import kotlin.math.roundToInt

/** The board's Update button icon (a taller arrow than the web's Download). */
private val UpdateIcon = IconDef(2.4f, "M12 3v12", "M7 10l5 5 5-5", "M5 20h14")
private val ListBg = Color(0xE6141414)

/**
 * Board "Android · Update ready": Medu hops over what the new version brings (the release's "What's Changed"), with
 * the download's size and where it comes from. Back is "Later".
 */
@Composable
fun UpdateScreen(offer: UpdateOffer, installed: String, onUpdate: () -> Unit, onLater: () -> Unit, onChanges: () -> Unit) {
    BackHandler(onBack = onLater)
    val accent = LocalAccent.current
    val top = max(40.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 16.dp)
    val bottom = max(28.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
    BoxWithConstraints(Modifier.fillMaxSize().background(SeedyColors.Bg).blockTaps()) {
        DeepSea(DeepScenes.PhoneUpdate, glow = accent)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(start = 20.dp, end = 20.dp, top = top, bottom = bottom),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Hop { Medu(MeduMood.Yay, 140.dp, height = 185.dp) }
            Row(
                Modifier.padding(top = 6.dp).clip(RoundedCornerShape(999.dp)).background(accent.copy(alpha = .14f)).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SText(t("Update ready"), size = 12.sp, weight = 800, color = accent, tracking = .06f, uppercase = true, maxLines = 1)
                SText("$installed → ${offer.version}", size = 12.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
            }
            SText(
                t("A fresh Seedy is here"), Modifier.padding(top = 12.dp),
                size = 28.sp, weight = 900, lineHeight = 1.1f, tracking = -.015f, color = SeedyColors.White, align = TextAlign.Center,
            )
            SText(
                t("Version {version} brings a few things Medu is very excited about.", "version" to offer.version),
                Modifier.padding(top = 8.dp).widthIn(max = 300.dp),
                size = 14.sp, lineHeight = 1.5f, color = DeepText, align = TextAlign.Center,
            )
            if (offer.items.isNotEmpty()) {
                Column(Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(ListBg)) {
                    offer.items.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(SeedyColors.Line))
                        ItemRow(item, accent)
                    }
                }
            }
            SText(
                t("See everything that changed"), Modifier.padding(top = 10.dp).tap(onClick = onChanges),
                size = 13.sp, weight = 700, color = SeedyColors.Text2,
            )
            Spacer(Modifier.weight(1f))
            SText(sourceLine(offer), Modifier.padding(top = 18.dp), size = 12.sp, color = SeedyColors.Text3, align = TextAlign.Center)
            Column(Modifier.padding(top = 12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Inverse).tap(onClick = onUpdate),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    SIcon(UpdateIcon, size = 18.dp, color = SeedyColors.InkInverse)
                    SText(t("Update"), size = 16.sp, weight = 800, color = SeedyColors.InkInverse)
                }
                Box(
                    Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(16.dp)).background(SeedyColors.Surface).tap(onClick = onLater),
                    contentAlignment = Alignment.Center,
                ) {
                    SText(t("Later"), size = 16.sp, weight = 800, color = SeedyColors.White)
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: ReleaseItem, accent: Color) {
    val french = I18n.lang == Lang.Fr && item.frTitle != null
    val title = if (french) item.frTitle.orEmpty() else item.title
    val text = if (french) item.frText else item.text
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(SeedyColors.Surface), contentAlignment = Alignment.Center) {
            SIcon(ReleaseIcons[item.icon], size = 18.dp, color = accent, stroke = 2.2f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(title, size = 14.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        }
    }
}

/** "18.4 MB · about 20 seconds on Wi‑Fi · from Google Play". */
private fun sourceLine(offer: UpdateOffer): String = listOfNotNull(
    offer.bytes?.let(::bytesLabel),
    offer.bytes?.let(::wifiTime),
    if (offer.fromPlay) t("from Google Play") else t("from GitHub"),
).joinToString(" · ")

/** How long the download takes at about 1 MB/s, in tens of seconds or whole minutes. */
private fun wifiTime(bytes: Long): String {
    val s = bytes / 1_000_000.0
    return when {
        s < 55 -> t("about {n} seconds on Wi‑Fi", "n" to ((s / 10).roundToInt() * 10).coerceAtLeast(10))
        s < 90 -> t("about a minute on Wi‑Fi")
        else -> t("about {n} minutes on Wi‑Fi", "n" to (s / 60).roundToInt())
    }
}
