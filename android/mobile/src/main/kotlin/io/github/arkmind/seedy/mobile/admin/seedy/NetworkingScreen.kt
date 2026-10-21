package io.github.arkmind.seedy.mobile.admin.seedy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.arkmind.seedy.core.admin.seedy.NetworkConfig
import io.github.arkmind.seedy.core.admin.seedy.NetworkingActions
import io.github.arkmind.seedy.core.admin.seedy.NetworkingState
import io.github.arkmind.seedy.core.admin.seedy.Port
import io.github.arkmind.seedy.core.admin.seedy.showBase
import io.github.arkmind.seedy.core.admin.seedy.validAddress
import io.github.arkmind.seedy.core.admin.seedy.validPublished
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ConfirmSheet
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.settings.PickRow
import io.github.arkmind.seedy.mobile.settings.Section
import io.github.arkmind.seedy.mobile.settings.Segmented
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.mobile.settings.SwitchRow
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** A list the page edits in a sheet. */
private enum class NetList { Filter, Published, Home, Proxies, Listen }

/** "192.168.1.0/24", "192.168.1.0/24 +2", or what an empty list means. */
private fun listValue(items: List<String>, empty: String): String = when (items.size) {
    0 -> empty
    1 -> items[0]
    else -> "${items[0]} +${items.size - 1}"
}

/** Server › Networking (no phone board: the server boards' kit with the web board ServerNetworking's content). */
@Composable
fun NetworkingScreen(state: NetworkingState, actions: NetworkingActions, onBack: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var list by remember { mutableStateOf<NetList?>(null) }
    var certSheet by remember { mutableStateOf(false) }
    var passwordSheet by remember { mutableStateOf(false) }
    val changes = state.changes
    val bar = changes > 0 || state.restarting
    LeaveGuard(changes > 0, t("Your changes to the network settings haven’t been saved yet. If you leave now, they’re lost."), onBack)
    SettingsPage(
        t("Networking"),
        t("Ports, HTTPS, remote access and your home network. Every change here needs a restart."),
        onBack,
        overlay = {
            val c = state.value
            if (confirm) {
                ConfirmSheet(
                    t("Save and restart the server?"),
                    (if (state.watching > 0) tn(state.watching, "{n} person is watching right now; their playback stops while the server restarts.", "{n} people are watching right now; their playback stops while the server restarts.") + " " else "") +
                        t("Network changes take effect after the restart, which usually takes less than a minute."),
                    t("Save and restart"), actions::save, { confirm = false },
                )
            }
            if (c != null) {
                list?.let { which -> NetListSheet(which, c, actions) { list = null } }
                if (certSheet) {
                    FieldSheet(t("Certificate file"), t("A .pfx file with its private key"), c.certificatePath, { v -> actions.edit { it.copy(certificatePath = v.trim()) } }, { certSheet = false }, placeholder = "/config/ssl/server.pfx", keyboard = KeyboardType.Uri)
                }
                if (passwordSheet) {
                    FieldSheet(t("Certificate password"), t("Leave empty if the file has none"), c.certificatePassword, { v -> actions.edit { it.copy(certificatePassword = v) } }, { passwordSheet = false }, placeholder = t("Password"), password = true)
                }
            }
            if (bar) {
                val note = if (state.watching > 0) " · " + tn(state.watching, "{n} person is watching", "{n} people are watching") else ""
                UnsavedBar(
                    tn(changes, "{n} unsaved change", "{n} unsaved changes") + note, t("Save and restart"),
                    onDiscard = actions::discard,
                    onSave = { if (state.portText.isNotEmpty()) actions.say(t("Ports are numbers from 1 to 65535.")) else confirm = true },
                    saving = state.saving, busy = if (state.restarting) t("Restarting the server…") else null,
                )
            }
            Toast(state.message, actions::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = if (bar) 90.dp else 0.dp))
        },
    ) {
        val c = state.value
        when {
            c == null && state.loadError != null -> LoadFailed(t("Couldn’t load the network settings"), state.loadError.orEmpty(), actions::retry)
            c == null -> LoadingCards(4)
            else -> NetworkBody(c, state, actions, open = { list = it }, cert = { certSheet = true }, password = { passwordSheet = true })
        }
        if (bar) SaveBarRoom()
    }
}

@Composable
private fun NetworkBody(c: NetworkConfig, state: NetworkingState, actions: NetworkingActions, open: (NetList) -> Unit, cert: () -> Unit, password: () -> Unit) {
    val badPorts = state.portText.isNotEmpty()
    FieldSection(
        t("Server address"),
        foot = if (badPorts) t("Ports are numbers from 1 to 65535.") else t("Public ports are what apps outside your network connect to, for example through router port forwarding."),
        footColor = if (badPorts) SeedyColors.Danger else SeedyColors.Text3,
    ) {
        FieldBlock(t("Base URL"), showBase(c.baseUrl), { v -> actions.edit { it.copy(baseUrl = v) } }, keyboard = KeyboardType.Uri)
        for (pair in listOf(Port.LocalHttp to Port.LocalHttps, Port.PublicHttp to Port.PublicHttps)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (p in pair.toList()) {
                    val text = state.portText[p] ?: p.of(c).toString()
                    FieldBlock(t(p.label), text, { actions.setPort(p, it) }, Modifier.weight(1f), error = if (p in state.portText) "" else null, keyboard = KeyboardType.Number)
                }
            }
        }
    }

    Section("HTTPS", t("Most setups put a reverse proxy in front instead and leave this off.")) {
        row { SwitchRow(t("Serve over HTTPS"), t("Needs a certificate on this server"), c.enableHttps) { v -> actions.edit { it.copy(enableHttps = v) } } }
        row { SwitchRow(t("Require HTTPS"), t("Refuse plain HTTP, except from the local network"), c.requireHttps) { v -> actions.edit { it.copy(requireHttps = v) } } }
        row { PickRow(t("Certificate file"), t("A .pfx file with its private key"), c.certificatePath.ifEmpty { t("None") }, onClick = cert) }
        row { PickRow(t("Certificate password"), t("Leave empty if the file has none"), if (c.certificatePassword.isEmpty()) t("None") else "••••••••", onClick = password) }
    }

    Section(t("Remote access"), t("Who can reach this server from outside your home network.")) {
        row { SwitchRow(t("Allow remote access"), t("Each user also needs remote access switched on"), c.enableRemoteAccess) { v -> actions.edit { it.copy(enableRemoteAccess = v) } } }
        row {
            val block = c.isRemoteIPFilterBlacklist
            PickRow(
                if (block) t("Block these addresses") else t("Only allow these addresses"),
                if (block) t("Switch to an allow list in the menu") else t("Switch to a block list in the menu"),
                listValue(c.remoteIPFilter, if (block) t("No one is blocked") else t("Everyone can connect")),
            ) { open(NetList.Filter) }
        }
        row { PickRow(t("Published addresses"), t("What apps are told to connect to, per network"), listValue(c.publishedServerUriBySubnet, t("Worked out automatically"))) { open(NetList.Published) } }
    }

    Section(t("Local network"), t("Devices here count as at home: no remote limits, no remote sign-in rules.")) {
        row { PickRow(t("Home network"), t("Address ranges that count as local"), listValue(c.localNetworkSubnets, t("Private address ranges"))) { open(NetList.Home) } }
        row { PickRow(t("Known proxies"), t("Reverse proxies whose forwarded addresses can be trusted"), listValue(c.knownProxies, t("None"))) { open(NetList.Proxies) } }
        row { PickRow(t("Listen on"), t("Network interfaces to use"), listValue(c.localNetworkAddresses, t("All interfaces"))) { open(NetList.Listen) } }
        row { SwitchRow(t("Find this server automatically"), t("Apps on the home network discover it without an address"), c.autoDiscovery) { v -> actions.edit { it.copy(autoDiscovery = v) } } }
        row { SwitchRow("IPv6", t("Also listen on IPv6 addresses"), c.enableIPv6) { v -> actions.edit { it.copy(enableIPv6 = v) } } }
        row { SwitchRow(t("Ignore virtual interfaces"), t("Skips Docker and VPN interfaces such as veth"), c.ignoreVirtualInterfaces) { v -> actions.edit { it.copy(ignoreVirtualInterfaces = v) } } }
    }
}

@Composable
private fun NetListSheet(which: NetList, c: NetworkConfig, actions: NetworkingActions, onDismiss: () -> Unit) {
    val cidr = { v: String -> validAddress(v, cidr = true) }
    when (which) {
        NetList.Filter -> {
            val block = c.isRemoteIPFilterBlacklist
            ListSheet(
                if (block) t("Address to block") else t("Address to allow"),
                t("An address or a range. Only applies to connections from outside your home network."),
                c.remoteIPFilter, "203.0.113.7 or 203.0.113.0/24", if (block) t("No one is blocked") else t("Everyone can connect"),
                cidr, { v -> actions.edit { it.copy(remoteIPFilter = v) } }, onDismiss,
                top = {
                    Segmented(
                        listOf(Choice(false, t("Allow only these")), Choice(true, t("Block these"))), block,
                        { v -> actions.edit { it.copy(isRemoteIPFilterBlacklist = v) } },
                    )
                },
            )
        }
        NetList.Published -> ListSheet(
            t("Published addresses"), t("network=address. The network is “all”, “internal”, “external”, an interface or a range."),
            c.publishedServerUriBySubnet, "external=https://watch.example.com", t("Worked out automatically"),
            ::validPublished, { v -> actions.edit { it.copy(publishedServerUriBySubnet = v) } }, onDismiss,
        )
        NetList.Home -> ListSheet(
            t("Home network"), t("A range like 192.168.1.0/24, or a single address."),
            c.localNetworkSubnets, "192.168.1.0/24", t("Private address ranges"),
            cidr, { v -> actions.edit { it.copy(localNetworkSubnets = v) } }, onDismiss,
        )
        NetList.Proxies -> ListSheet(
            t("Known proxies"), t("The proxy’s address or host name, or a range."),
            c.knownProxies, "172.18.0.2", t("None"),
            { v -> validAddress(v, cidr = true, host = true) }, { v -> actions.edit { it.copy(knownProxies = v) } }, onDismiss,
        )
        NetList.Listen -> ListSheet(
            t("Listen on"), t("An address this server has on one of its interfaces."),
            c.localNetworkAddresses, "192.168.1.20", t("All interfaces"),
            { v -> validAddress(v) }, { v -> actions.edit { it.copy(localNetworkAddresses = v) } }, onDismiss,
        )
    }
}
