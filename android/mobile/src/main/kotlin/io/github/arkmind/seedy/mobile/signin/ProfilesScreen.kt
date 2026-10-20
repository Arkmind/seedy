package io.github.arkmind.seedy.mobile.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.ProfilesViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.mobile.parts.ActionCard
import io.github.arkmind.seedy.mobile.parts.pagePadding
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.components.AddProfileSquare
import io.github.arkmind.seedy.ui.components.BrandMark
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** Board "Android · Who is watching": the server's profiles, the server switcher, Quick Connect. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    brand: String,
    server: SavedServer,
    state: ProfilesViewModel.State,
    onProfile: (ProfilesViewModel.Profile) -> Unit,
    onAddAccount: () -> Unit,
    onQuickConnect: () -> Unit,
    onServer: (SavedServer) -> Unit,
    onAddServer: () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(SeedyColors.Bg).padding(pagePadding())) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BrandMark(brand)
            ServerChip(server, state.servers, onServer, onAddServer)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SText(
                t("Who's watching?"),
                Modifier.padding(top = 64.dp),
                size = 26.sp, weight = 900, color = SeedyColors.White, lineHeight = 1.2f, align = TextAlign.Center,
            )
            FlowRow(
                Modifier.padding(top = 36.dp, bottom = 24.dp).width(128.dp * 2 + 28.dp),
                horizontalArrangement = Arrangement.spacedBy(28.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
                maxItemsInEachRow = 2,
            ) {
                for (p in state.profiles) {
                    ProfileItem(
                        name = p.name,
                        modifier = Modifier.alpha(if (state.opening != null && state.opening != p.userId) 0.5f else 1f),
                        onClick = { onProfile(p) },
                    ) {
                        ProfileSquare(p.name, p.imageUrl, p.colour, size = 112.dp, radius = 28.dp, letterSize = 46.sp, locked = !p.hasSession)
                    }
                }
                ProfileItem(name = t("Add account"), nameColor = SeedyColors.Text2, onClick = onAddAccount) {
                    AddProfileSquare(size = 112.dp, radius = 28.dp)
                }
            }
        }
        if (state.quickConnect) {
            ActionCard(AppIcons.Phone, t("Sign in with Quick Connect"), t("Approve this phone from a signed-in device"), onQuickConnect)
        }
    }
}

@Composable
private fun ProfileItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    nameColor: androidx.compose.ui.graphics.Color = SeedyColors.Text,
    square: @Composable () -> Unit,
) {
    Column(
        modifier.width(128.dp).tap(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        square()
        SText(name, size = 14.sp, weight = 700, color = nameColor, maxLines = 1, align = TextAlign.Center)
    }
}

/** The header's server button: a green dot, the server's name, and a menu of the other servers. */
@Composable
private fun ServerChip(server: SavedServer, servers: List<SavedServer>, onServer: (SavedServer) -> Unit, onAddServer: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.height(40.dp).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Row).tap { open = true }
                .padding(start = 12.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(7.dp).background(SeedyColors.Ok, CircleShape))
            SText(server.name, size = 12.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
            SIcon(WebIcons.ChevronDown, size = 14.dp, color = SeedyColors.TextHero, stroke = 2.4f)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = SeedyColors.Row,
            shape = RoundedCornerShape(12.dp),
        ) {
            for (s in servers) {
                DropdownMenuItem(
                    text = {
                        Column {
                            SText(s.name, size = 14.sp, weight = 700, color = if (s.id == server.id) SeedyColors.White else SeedyColors.Text)
                            SText(s.address.removePrefix("http://").removePrefix("https://"), size = 12.sp, color = SeedyColors.Text3)
                        }
                    },
                    trailingIcon = { if (s.id == server.id) SIcon(WebIcons.Check, size = 18.dp, color = SeedyColors.White) },
                    onClick = { open = false; if (s.id != server.id) onServer(s) },
                )
            }
            DropdownMenuItem(
                text = { SText(t("Add a server"), size = 14.sp, weight = 700, color = SeedyColors.Text) },
                leadingIcon = { SIcon(WebIcons.Plus, size = 18.dp, color = SeedyColors.Text2) },
                onClick = { open = false; onAddServer() },
            )
        }
    }
}
