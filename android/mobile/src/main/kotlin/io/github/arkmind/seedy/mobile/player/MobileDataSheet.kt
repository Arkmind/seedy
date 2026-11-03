package io.github.arkmind.seedy.mobile.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.data.DeviceSettings
import io.github.arkmind.seedy.core.format.join
import io.github.arkmind.seedy.core.format.runtime
import io.github.arkmind.seedy.core.format.ticksToSeconds
import io.github.arkmind.seedy.core.i18n.formatNumber
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.settings.Choices
import io.github.arkmind.seedy.mobile.parts.blockTaps
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.math.roundToInt

/** A quality the prompt offers: its name, its line, the bitrate it plays at; [upTo] for the one that can go higher. */
data class DataChoice(val name: String, val sub: String, val bitrate: Int, val upTo: Boolean = false)

/** Data saver (720p · 2 Mbps), High (1080p · 8 Mbps), and the quality this phone uses at home (Auto: up to 4K · 40 Mbps). */
fun dataChoices(device: DeviceSettings): List<DataChoice> {
    val home = device.qualityHome
    return listOf(
        DataChoice(t("Data saver"), "720p · ${Choices.bitrate(2_000_000)}", 2_000_000),
        DataChoice(t("High"), "1080p · ${Choices.bitrate(8_000_000)}", 8_000_000),
        if (home <= 0) DataChoice(t("Same as Wi-Fi"), t("Up to 4K · {rate}", "rate" to Choices.bitrate(40_000_000)), 40_000_000, upTo = true)
        else DataChoice(t("Same as Wi-Fi"), Choices.quality(home, ""), home, upTo = true),
    )
}

private val Bars = IconDef(2.6f, "M5 19v-3", "M10 19v-6", "M15 19v-9", "M20 19V6")
private val Amber = Color(0xFFFFC98A)

/** "about 0.8 GB" for the whole video, or "about 2.3 GB an hour" when its length isn't known (live TV). */
private fun estimate(c: DataChoice, seconds: Double?): String {
    val g = c.bitrate * (seconds ?: 3600.0) / 8 / 1e9
    // "Up to" never rounds up.
    val size = when {
        g < 10 -> formatNumber(BigDecimal(g).setScale(1, if (c.upTo) RoundingMode.FLOOR else RoundingMode.HALF_UP))
        c.upTo -> formatNumber(g.toInt())
        else -> formatNumber(g.roundToInt())
    }
    return when {
        seconds == null && c.upTo -> t("up to {size} GB an hour", "size" to size)
        seconds == null -> t("about {size} GB an hour", "size" to size)
        c.upTo -> t("up to {size} GB", "size" to size)
        else -> t("about {size} GB", "size" to size)
    }
}

/**
 * Board "Android · Mobile data prompt": before a video plays away from Wi-Fi (Settings · Playback › Ask before using
 * mobile data), the quality to use this time with what it costs, and "Don't ask again" (which keeps that quality for
 * mobile data). [id] is the video; [item] skips loading it (screenshot tests).
 */
@Composable
fun BoxScope.MobileDataSheet(id: UUID, device: DeviceSettings, onPlay: (DataChoice, Boolean) -> Unit, onWait: () -> Unit, item: BaseItemDto? = null) {
    val session = if (item == null) currentSession() else null
    var loaded by remember(id) { mutableStateOf(item) }
    LaunchedEffect(id) {
        if (session != null && loaded == null) {
            loaded = withContext(Dispatchers.IO) { runCatching { session.api.libraryApi.getItems(userId = session.userId, ids = listOf(id)).content.items.firstOrNull() }.getOrNull() }
        }
    }
    val choices = remember(device) { dataChoices(device) }
    var picked by remember { mutableStateOf(choices.indexOfFirst { it.bitrate == device.qualityMobile }.coerceAtLeast(0)) }
    var never by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val seconds = loaded?.runTimeTicks?.let(::ticksToSeconds)?.takeIf { it > 0 }
    BackHandler(onBack = onWait)

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)).tap(onClick = onWait))
    Column(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(Color(0xFF161616))
            .blockTaps().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A3A3A)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(Amber.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                SIcon(Bars, size = 22.dp, color = Amber)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(t("You’re on mobile data"), size = 20.sp, weight = 800, color = SeedyColors.White)
                loaded?.let { SText(join(it.name, it.runtime), size = 13.sp, color = SeedyColors.Text2, maxLines = 1) }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            choices.forEachIndexed { i, c ->
                val on = i == picked
                val shape = RoundedCornerShape(14.dp)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(shape).background(if (on) SeedyColors.Surface else Color(0xFF1A1A1A))
                        .then(if (on) Modifier.border(2.dp, accent, shape) else Modifier)
                        .tap(role = Role.RadioButton) { picked = i }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(20.dp).clip(CircleShape)
                            .then(if (on) Modifier.background(accent).padding(6.dp).background(Color(0xFF1E1E1E), CircleShape) else Modifier.border(2.dp, Color(0xFF5A5A5A), CircleShape)),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        SText(c.name, size = 15.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
                        SText(c.sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
                    }
                    SText(estimate(c, seconds), size = 13.sp, weight = 700, color = if (on) SeedyColors.White else SeedyColors.Text2, maxLines = 1)
                }
            }
        }
        val choice = choices[picked]
        Row(
            Modifier.fillMaxWidth().tap(role = Role.Switch) { never = !never }.padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SText(t("Don’t ask again"), size = 14.sp, weight = 700, color = SeedyColors.White)
                SText(t("Plays at {quality} on mobile data from now on", "quality" to choice.name), size = 12.sp, color = SeedyColors.Text3)
            }
            Box(Modifier.size(44.dp, 26.dp).clip(CircleShape).background(if (never) accent else Color(0xFF2A2A2A))) {
                Box(Modifier.padding(start = if (never) 21.dp else 3.dp, top = 3.dp).size(20.dp).background(if (never) Color(0xFF1E1E1E) else SeedyColors.Text3, CircleShape))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f).height(52.dp).clip(RoundedCornerShape(15.dp)).background(Color(0xFF262626)).tap(onClick = onWait), contentAlignment = Alignment.Center) {
                SText(t("Wait for Wi-Fi"), size = 15.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            }
            Row(
                Modifier.weight(1.4f).height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Inverse).tap { onPlay(choice, never) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                SIcon(WebIcons.Play, size = 20.dp, color = SeedyColors.InkInverse)
                SText(t("Play at {quality}", "quality" to choice.name), size = 15.sp, weight = 800, color = SeedyColors.InkInverse, maxLines = 1)
            }
        }
    }
}
