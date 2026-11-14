package io.github.arkmind.seedy.mobile.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.BrandMark
import io.github.arkmind.seedy.ui.components.DeepScenes
import io.github.arkmind.seedy.ui.components.DeepSea
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.Spinner
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The sign-in pages' body text on the deep-sea boards. */
internal val DeepText = Color(0xFFB4B4B4)

/**
 * Board "Android · Server find": Medu sweeps the network with a torch for Jellyfin servers. Servers that answer are
 * listed (with their Jellyfin version and whether they run Seedy); "Enter an address instead" opens the address field;
 * Quick Connect picks the server found and opens its code.
 */
@Composable
fun AddServerScreen(
    state: AddServerViewModel.State,
    onAdd: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: (() -> Unit)?,
    onQuickConnect: ((AddServerViewModel.Found) -> Unit)? = null,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    val top = max(34.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 10.dp)
    val bottom = max(28.dp, WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp)
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.PhoneFind)
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = top, bottom = bottom)) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.CenterStart) {
                if (onBack != null) {
                    HeaderButton(WebIcons.ArrowLeft, onBack, Modifier.requiredSize(44.dp), stroke = 2.2f)
                } else {
                    BrandMark("Seedy", markSize = 28.dp, radius = 7.dp, letterSize = 15.sp, wordSize = 18.sp)
                }
            }
            Spacer(Modifier.height(96.dp - 34.dp - 28.dp))
            Medu(MeduMood.Search, 150.dp, Modifier.padding(start = 4.dp), height = 198.dp)
            SText(t("Looking for servers"), Modifier.padding(top = 4.dp), size = 26.sp, weight = 900, color = SeedyColors.White)
            SText(
                t("Medu is searching your Wi‑Fi for Jellyfin servers with Seedy."),
                Modifier.padding(top = 6.dp), size = 15.sp, lineHeight = 1.5f, color = DeepText,
            )
            if (state.found.isNotEmpty()) {
                SText(
                    t("Found on this network"), Modifier.padding(top = 22.dp, bottom = 10.dp),
                    size = 12.sp, weight = 800, tracking = .1f, uppercase = true, color = SeedyColors.Text3,
                )
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (s in state.found) FoundServer(s) { onAdd(s.address) }
                }
            } else if (!state.searching) {
                SText(t("No server answered on this network."), Modifier.padding(top = 22.dp), size = 13.sp, color = SeedyColors.Text3)
            }
            Row(
                Modifier.padding(horizontal = 4.dp, vertical = 14.dp).then(if (state.searching) Modifier else Modifier.tap(onClick = onSearch)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (state.searching) {
                    Spinner(14.dp, SeedyColors.Text3)
                    SText(t("Still looking…"), size = 13.sp, color = SeedyColors.Text3)
                } else {
                    SIcon(WebIcons.Refetch, size = 14.dp, color = SeedyColors.Text2, stroke = 2.6f)
                    SText(t("Look again"), size = 13.sp, weight = 700, color = SeedyColors.Text2)
                }
            }
            if (typing) {
                Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SeedyField(address, { address = it }, placeholder = "192.168.1.20:8096", keyboard = KeyboardType.Uri, onDone = { onAdd(address) })
                    state.error?.let { SText(it, size = 13.sp, weight = 600, color = SeedyColors.Danger) }
                    PrimaryButton(if (state.busy) t("Connecting…") else t("Connect"), { onAdd(address) }, enabled = !state.busy && address.isNotBlank())
                }
            } else {
                Box(
                    Modifier.padding(top = 6.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(15.dp)).background(SeedyColors.Surface)
                        .tap { typing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    SText(t("Enter an address instead"), size = 15.sp, weight = 800, color = SeedyColors.White)
                }
                state.error?.let { SText(it, Modifier.padding(top = 10.dp), size = 13.sp, weight = 600, color = SeedyColors.Danger) }
            }
            val first = state.found.firstOrNull()
            if (onQuickConnect != null && first != null) {
                SText(
                    t("Sign in with Quick Connect"),
                    Modifier.align(Alignment.CenterHorizontally).padding(top = 14.dp).tap { onQuickConnect(first) },
                    size = 14.sp, weight = 700, color = SeedyColors.Text2,
                )
            }
        }
    }
}

/** A server that answered: its name and what it runs, ringed in the accent. */
@Composable
private fun FoundServer(server: AddServerViewModel.Found, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(SeedyColors.Row).border(2.dp, LocalAccent.current, shape)
            .tap(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(42.dp).background(Color(0xFF262626), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            SIcon(AppIcons.ServerStack, size = 20.dp, color = SeedyColors.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(server.name, size = 15.sp, weight = 800, color = SeedyColors.White, maxLines = 1)
            SText(server.detail, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        SIcon(AppIcons.Chevron, size = 18.dp, color = SeedyColors.Text, stroke = 2.2f)
    }
}
