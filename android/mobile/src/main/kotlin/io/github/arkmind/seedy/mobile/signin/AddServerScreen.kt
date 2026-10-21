package io.github.arkmind.seedy.mobile.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.FieldLabel
import io.github.arkmind.seedy.mobile.parts.HeaderButton
import io.github.arkmind.seedy.mobile.parts.PrimaryButton
import io.github.arkmind.seedy.mobile.parts.SeedyField
import io.github.arkmind.seedy.mobile.parts.bleed
import io.github.arkmind.seedy.mobile.parts.pagePadding
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.BrandMark
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/**
 * "Add your server" (no board: built from the sign-in boards' parts). Servers that answer Jellyfin's discovery on this
 * network are listed; any other address can be typed.
 */
@Composable
fun AddServerScreen(state: AddServerViewModel.State, onAdd: (String) -> Unit, onSearch: () -> Unit, onBack: (() -> Unit)?) {
    var address by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(SeedyColors.Bg).imePadding().padding(pagePadding())) {
        if (onBack != null) {
            Row(Modifier.fillMaxWidth().bleed(8.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(WebIcons.ArrowLeft, onBack, stroke = 2.2f)
            }
        } else {
            BrandMark("Seedy")
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            SText(
                t("Add your server"),
                Modifier.fillMaxWidth().padding(top = if (onBack != null) 40.dp else 64.dp),
                size = 26.sp, weight = 900, color = SeedyColors.White, lineHeight = 1.2f, align = TextAlign.Center,
            )
            SText(
                t("Seedy plays from your Jellyfin server. Servers on this network show up below."),
                Modifier.fillMaxWidth().padding(top = 10.dp, start = 8.dp, end = 8.dp),
                size = 14.sp, color = SeedyColors.Text2, align = TextAlign.Center, lineHeight = 1.45f,
            )
            Row(Modifier.padding(top = 32.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(t("On this network"), Modifier.weight(1f))
                if (state.searching) {
                    SText(t("Looking…"), size = 12.sp, color = SeedyColors.Text3)
                } else {
                    SText(t("Look again"), Modifier.tap(onClick = onSearch), size = 12.sp, weight = 700, color = SeedyColors.Text2)
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (s in state.found) ServerRow(s.name, s.address.removePrefix("http://").removePrefix("https://")) { onAdd(s.address) }
                if (!state.searching && state.found.isEmpty()) {
                    SText(t("No server answered. Type its address below."), size = 13.sp, color = SeedyColors.Text3)
                }
            }
            Column(Modifier.padding(top = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldLabel(t("Server address"))
                SeedyField(address, { address = it }, placeholder = "192.168.1.20:8096", keyboard = KeyboardType.Uri, onDone = { onAdd(address) })
                state.error?.let { SText(it, Modifier.padding(top = 4.dp), size = 13.sp, weight = 600, color = SeedyColors.Danger) }
            }
        }
        PrimaryButton(
            if (state.busy) t("Connecting…") else t("Connect"),
            { onAdd(address) },
            Modifier.padding(top = 16.dp),
            enabled = !state.busy && address.isNotBlank(),
        )
    }
}

@Composable
private fun ServerRow(name: String, address: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SeedyColors.Panel)
            .border(1.dp, Color(0xFF222222), RoundedCornerShape(14.dp)).tap(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(SeedyColors.Surface, RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
            SIcon(AppIcons.Server, size = 20.dp, color = LocalAccent.current)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SText(name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(address, size = 12.sp, color = SeedyColors.Text3, maxLines = 1)
        }
        SIcon(WebIcons.ChevronRight, size = 16.dp, color = SeedyColors.Text2, stroke = 2.4f)
    }
}
