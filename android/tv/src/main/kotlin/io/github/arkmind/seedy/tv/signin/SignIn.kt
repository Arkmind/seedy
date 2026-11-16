package io.github.arkmind.seedy.tv.signin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import io.github.arkmind.seedy.core.data.host
import io.github.arkmind.seedy.tv.parts.FocusWhen
import io.github.arkmind.seedy.tv.parts.TvBigButton
import io.github.arkmind.seedy.ui.components.DeepScenes
import io.github.arkmind.seedy.ui.components.DeepSea
import io.github.arkmind.seedy.ui.components.Medu
import io.github.arkmind.seedy.ui.components.MeduMood
import io.github.arkmind.seedy.ui.components.Spinner
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import io.github.arkmind.seedy.core.data.SavedServer
import io.github.arkmind.seedy.core.feature.AddServerViewModel
import io.github.arkmind.seedy.core.feature.PasswordViewModel
import io.github.arkmind.seedy.core.feature.ProfilesViewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.tv.parts.TvButton
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusRing
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.ui.components.AddProfileSquare
import io.github.arkmind.seedy.ui.components.BrandMark
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.icons.AppIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/** The TV boards' header mark: 50 px square, 36 px name. */
@Composable
internal fun TvBrand(name: String, modifier: Modifier = Modifier) {
    BrandMark(name, modifier, markSize = 50.bp, radius = 14.bp, letterSize = 26.bsp, wordSize = 36.bsp, gap = 16.bp)
}

/**
 * The sign-in pages' frame: brand on top, the page's heading 120 px under it, actions at the bottom. While the
 * system keyboard is up (it covers the lower half of the screen) the brand and the spacing go, so the field being
 * typed in stays above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Frame(brand: String, title: String, mascot: MeduMood? = null, bottom: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val typing = WindowInsets.isImeVisible
    Column(
        Modifier.fillMaxSize().background(SeedyColors.Bg).padding(start = 96.bp, end = 96.bp, top = if (typing) 48.bp else 96.bp, bottom = 72.bp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!typing) TvBrand(brand)
        if (mascot != null && !typing) {
            // Board "Android TV · Who is watching": Medu by the heading, which tucks 4 px into its glow.
            Row(Modifier.padding(top = 56.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-4).bp)) {
                Medu(mascot, 130.bp, height = 172.bp)
                SText(title, size = 56.bsp, weight = 900, tracking = -0.02f, lineHeight = 1f, color = SeedyColors.White)
            }
        } else {
            SText(
                title, Modifier.padding(top = if (typing) 0.bp else 120.bp),
                size = if (typing) 36.bsp else 56.bsp, weight = 900, tracking = -0.02f, lineHeight = 1f, color = SeedyColors.White,
            )
        }
        CompositionLocalProvider(LocalTyping provides typing) { content() }
        Spacer(Modifier.weight(1f))
        if (!typing) bottom()
    }
}

/** True while the system keyboard is up: pages leave out what would push the field under it. */
private val LocalTyping = staticCompositionLocalOf { false }

/** Board "Android TV · Who is watching". */
@Composable
fun TvProfilesScreen(
    brand: String,
    state: ProfilesViewModel.State,
    onProfile: (ProfilesViewModel.Profile) -> Unit,
    onAddAccount: () -> Unit,
    onQuickConnect: () -> Unit,
    onChangeServer: () -> Unit,
) {
    var focusedName by remember { mutableStateOf<ProfilesViewModel.Profile?>(null) }
    val first = remember { FocusRequester() }
    Frame(
        brand, t("Who's watching?"), mascot = MeduMood.Wave,
        bottom = {
            Row(horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                if (state.quickConnect) TvButton(t("Sign in with Quick Connect"), onQuickConnect, icon = AppIcons.Phone)
                TvButton(t("Change server"), onChangeServer, icon = AppIcons.Server)
            }
            val locked = focusedName?.takeIf { !it.hasSession }
            SText(
                if (locked != null) t("{name} has a password. Type it with the remote, or approve this TV from your phone with Quick Connect.", "name" to locked.name) else " ",
                Modifier.padding(top = 24.bp), size = 19.bsp, color = SeedyColors.Text3, align = TextAlign.Center,
            )
        },
    ) {
        // 24 px around the tiles so the focused one's zoom and ring aren't clipped, taken from the 88 px above.
        LazyRow(
            Modifier.padding(top = 88.bp - 24.bp),
            horizontalArrangement = Arrangement.spacedBy(56.bp),
            contentPadding = PaddingValues(24.bp),
        ) {
            items(state.profiles, key = { it.userId }) { p ->
                ProfileTile(
                    name = p.name,
                    modifier = if (p == state.profiles.first()) Modifier.focusRequester(first) else Modifier,
                    onFocused = { focusedName = p },
                    onClick = { onProfile(p) },
                ) { ProfileSquare(p.name, p.imageUrl, p.colour, size = 200.bp, radius = 42.bp, letterSize = 84.bsp, locked = !p.hasSession, badgeSize = 48.bp, badgeInset = 14.bp) }
            }
            item {
                ProfileTile(t("Add account"), onFocused = { focusedName = null }, onClick = onAddAccount) {
                    AddProfileSquare(size = 200.bp, radius = 42.bp, plusSize = 84.bsp, dash = 4.bp)
                }
            }
        }
    }
    FocusWhen(state.profiles.isNotEmpty(), first)
}

@Composable
private fun ProfileTile(name: String, onClick: () -> Unit, onFocused: () -> Unit, modifier: Modifier = Modifier, square: @Composable () -> Unit) {
    TvPressable(onClick, modifier.width(200.bp).onFocusChanged { if (it.isFocused) onFocused() }) { focused ->
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.bp)) {
            Box(Modifier.focusScale(focused, 1.08f).focusRing(focused, radius = 42.bp, gap = 8.bp, ring = 6.bp)) { square() }
            SText(
                name,
                size = if (focused) 26.bsp else 24.bsp, weight = if (focused) 800 else 600,
                color = if (focused) SeedyColors.White else SeedyColors.Text2, maxLines = 1,
            )
        }
    }
}

/**
 * A 76 px text well for the remote. Landing on it only shows the white ring; OK opens the system keyboard, and its
 * Done (or Back) closes it and leaves the remote on the field.
 */
@Composable
private fun TvField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onDone: () -> Unit = {},
) {
    var editing by remember { mutableStateOf(false) }
    val outer = remember { FocusRequester() }
    val inner = remember { FocusRequester() }
    val outerInteraction = remember { MutableInteractionSource() }
    val innerInteraction = remember { MutableInteractionSource() }
    val outerFocused by outerInteraction.collectIsFocusedAsState()
    val innerFocused by innerInteraction.collectIsFocusedAsState()
    val focused = outerFocused || innerFocused
    val keyboardController = LocalSoftwareKeyboardController.current
    val stop = {
        editing = false
        keyboardController?.hide()
        outer.requestFocus()
    }
    Box(
        modifier.width(720.bp).height(76.bp).focusRing(focused, radius = 20.bp, gap = 4.bp, ring = 5.bp)
            .clip(RoundedCornerShape(20.bp)).background(SeedyColors.Row)
            .then(if (innerFocused) Modifier.border(2.bp, LocalAccent.current, RoundedCornerShape(20.bp)) else Modifier)
            .focusRequester(outer)
            .clickable(interactionSource = outerInteraction, indication = null) { editing = true }
            .padding(horizontal = 28.bp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) SText(placeholder, size = 24.bsp, color = SeedyColors.Text3, maxLines = 1)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            interactionSource = innerInteraction,
            textStyle = BaseText.copy(
                fontSize = 24.bsp, fontWeight = FontWeight(700), color = SeedyColors.White,
                letterSpacing = if (password) 0.1.em else 0.em,
            ),
            cursorBrush = SolidColor(LocalAccent.current),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ImeAction.Done, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onDone = { stop(); onDone() }),
            modifier = Modifier.fillMaxWidth().focusRequester(inner).focusProperties { canFocus = editing }
                .onFocusChanged { if (!it.isFocused && editing) editing = false },
        )
    }
    LaunchedEffect(editing) {
        if (editing) {
            inner.focusSoon()
            keyboardController?.show()
        }
    }
    BackHandler(enabled = editing) { stop() }
}

/** Signing in on the TV (no board: built from "Who is watching"): the password, or Quick Connect's code. */
@Composable
fun TvPasswordScreen(
    brand: String,
    server: SavedServer,
    profile: ProfilesViewModel.Profile?,
    state: PasswordViewModel.State,
    onUserName: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSignIn: () -> Unit,
    onQuickConnect: () -> Unit,
) {
    val firstField = remember { FocusRequester() }
    Frame(brand, profile?.name ?: t("Sign in"), bottom = {
        SText(
            t("Signing in to {server}", "server" to server.name),
            size = 19.bsp, color = SeedyColors.Text3,
        )
    }) {
        if (profile != null && !LocalTyping.current) {
            Box(Modifier.padding(top = 56.bp)) {
                ProfileSquare(profile.name, profile.imageUrl, profile.colour, size = 160.bp, radius = 36.bp, letterSize = 68.bsp)
            }
        }
        Column(Modifier.padding(top = if (LocalTyping.current) 28.bp else 56.bp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.bp)) {
            if (state.code != null) {
                QuickConnectCode(state.code!!)
            } else {
                if (profile == null) TvField(state.userName, onUserName, t("User name"), Modifier.focusRequester(firstField))
                TvField(
                    state.password, onPassword, t("Password"),
                    if (profile != null) Modifier.focusRequester(firstField) else Modifier,
                    password = true, onDone = onSignIn,
                )
                state.error?.let { SText(it, size = 20.bsp, weight = 600, color = SeedyColors.Danger) }
                state.quickConnectError?.let { SText(it, size = 20.bsp, weight = 600, color = SeedyColors.Danger) }
                Row(Modifier.padding(top = 8.bp), horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                    TvButton(if (state.busy) t("Signing in…") else t("Sign in"), onSignIn, primary = true)
                    TvButton(t("Use Quick Connect instead"), onQuickConnect, icon = AppIcons.Phone)
                }
            }
        }
    }
    LaunchedEffect(Unit) { firstField.focusSoon() }
}

/** Quick Connect's code in big tiles, and where to type it. */
@Composable
private fun QuickConnectCode(code: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(28.bp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.bp)) {
            for (c in code) {
                Box(Modifier.size(96.bp, 120.bp).clip(RoundedCornerShape(22.bp)).background(SeedyColors.Row), contentAlignment = Alignment.Center) {
                    SText(c.toString(), size = 60.bsp, weight = 900, color = LocalAccent.current)
                }
            }
        }
        SText(
            t("On your phone, open Seedy › Settings › Profile and sign-in › Quick Connect and enter this code."),
            Modifier.width(900.bp), size = 22.bsp, color = SeedyColors.TextHero, align = TextAlign.Center, lineHeight = 1.5f,
        )
        SText(t("This TV signs in as soon as it’s approved. Press Back to type a password instead."), size = 19.bsp, color = SeedyColors.Text3)
    }
}

/**
 * Board "Android TV · Server find": Medu sweeps the network with a torch; servers that answer are listed with what
 * they run. "Enter an address" opens the address field; "Use Quick Connect" picks the server found and opens its code.
 * While the system keyboard is up, only the field and its heading stay (above it).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvAddServerScreen(
    state: AddServerViewModel.State,
    onAdd: (String) -> Unit,
    onSearch: () -> Unit,
    onQuickConnect: ((AddServerViewModel.Found) -> Unit)? = null,
) {
    var address by rememberSaveable { mutableStateOf("") }
    var typing by rememberSaveable { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val field = remember { FocusRequester() }
    val ime = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.TvFind)
        if (!ime) {
            TvBrand("Seedy", Modifier.padding(start = 96.bp, top = 64.bp))
            Medu(MeduMood.Search, 300.bp, Modifier.offset(190.bp, 300.bp), height = 396.bp)
        }
        Column(Modifier.offset(980.bp, if (ime) 48.bp else 230.bp).width(780.bp)) {
            SText(t("Looking for servers"), size = 62.bsp, weight = 900, tracking = -0.02f, color = SeedyColors.White)
            if (!ime) {
                SText(
                    t("Medu is searching your network for Jellyfin servers with Seedy."),
                    Modifier.padding(top = 14.bp), size = 27.bsp, lineHeight = 1.45f, color = DeepText,
                )
                if (state.found.isNotEmpty()) {
                    SText(
                        t("Found on this network"), Modifier.padding(top = 46.bp, bottom = 18.bp),
                        size = 20.bsp, weight = 800, tracking = .1f, uppercase = true, color = SeedyColors.Text3,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(16.bp)) {
                        state.found.forEachIndexed { i, s ->
                            FoundServer(s, if (i == 0) Modifier.focusRequester(first) else Modifier) { onAdd(s.address) }
                        }
                    }
                } else if (!state.searching) {
                    SText(t("No server answered on this network."), Modifier.padding(top = 46.bp), size = 22.bsp, color = SeedyColors.Text2)
                }
                if (state.searching) {
                    Row(Modifier.padding(horizontal = 6.bp, vertical = 26.bp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.bp)) {
                        Spinner(24.bp, SeedyColors.Text3)
                        SText(t("Still looking…"), size = 22.bsp, color = SeedyColors.Text3)
                    }
                } else {
                    TvPressable(onSearch, Modifier.padding(vertical = 14.bp)) { focused ->
                        Row(
                            Modifier.focusRing(focused, radius = 14.bp, gap = 2.bp, ring = 4.bp).padding(horizontal = 6.bp, vertical = 12.bp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.bp),
                        ) {
                            SIcon(WebIcons.Refetch, size = 24.bp, color = SeedyColors.Text2, stroke = 2.6f)
                            SText(t("Look again"), size = 22.bsp, weight = 700, color = if (focused) SeedyColors.White else SeedyColors.Text2)
                        }
                    }
                }
            }
            if (typing) {
                Column(Modifier.padding(top = if (ime) 28.bp else 0.bp), verticalArrangement = Arrangement.spacedBy(24.bp)) {
                    TvField(address, { address = it }, "192.168.1.20:8096", Modifier.focusRequester(field), keyboard = KeyboardType.Uri, onDone = { onAdd(address) })
                    state.error?.let { SText(it, size = 20.bsp, weight = 600, color = SeedyColors.Danger) }
                    if (!ime) TvBigButton(if (state.busy) t("Connecting…") else t("Connect"), { onAdd(address) }, primary = true)
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(20.bp)) {
                    TvBigButton(t("Enter an address"), { typing = true }, if (state.found.isEmpty()) Modifier.focusRequester(first) else Modifier)
                    val found = state.found.firstOrNull()
                    if (onQuickConnect != null && found != null) TvBigButton(t("Use Quick Connect"), { onQuickConnect(found) })
                }
                state.error?.let { SText(it, Modifier.padding(top = 20.bp), size = 20.bsp, weight = 600, color = SeedyColors.Danger) }
            }
        }
    }
    LaunchedEffect(state.found.isNotEmpty(), typing) { if (typing) field.focusSoon() else first.focusSoon() }
}

/** The finder's body text on the deep-sea boards. */
private val DeepText = Color(0xFFB4B4B4)

/** A server that answered: light and lifted while the remote is on it. */
@Composable
private fun FoundServer(server: AddServerViewModel.Found, modifier: Modifier, onClick: () -> Unit) {
    TvPressable(onClick, modifier) { focused ->
        val ink = if (focused) SeedyColors.InkInverse else SeedyColors.White
        Row(
            Modifier.focusScale(focused, 1.04f)
                .then(if (focused) Modifier.boxShadow(Color.Black.copy(alpha = .55f), 56.bp, 24.bp, 26.bp) else Modifier)
                .fillMaxWidth().clip(RoundedCornerShape(26.bp)).background(if (focused) SeedyColors.Inverse else Color.White.copy(alpha = 0.1f))
                .padding(horizontal = 28.bp, vertical = 24.bp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.bp),
        ) {
            Box(
                Modifier.size(68.bp).clip(RoundedCornerShape(18.bp)).background(if (focused) SeedyColors.InkInverse else Color.White.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                SIcon(AppIcons.ServerStack, size = 34.bp, color = SeedyColors.White)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.bp)) {
                SText(server.name, size = 30.bsp, weight = 900, color = ink, maxLines = 1)
                SText(server.detail, size = 21.bsp, color = if (focused) Color(0xFF4A4A4A) else SeedyColors.Text2, maxLines = 1)
            }
            SIcon(AppIcons.Chevron, size = 34.bp, color = ink, stroke = 2.2f)
        }
    }
}

/** "192.168.1.20:8096": a server's address as the unreachable page prints it. */

/**
 * Board "Android TV · Server unreachable": Medu dropped the cable. Try again (the remote starts there), or pick another
 * server; Seedy tries by itself every few seconds ([retryIn] seconds left, null while a try runs).
 */
@Composable
fun TvUnreachableScreen(server: SavedServer, retryIn: Int?, onRetry: () -> Unit, onChooseServer: () -> Unit) {
    val retry = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
        DeepSea(DeepScenes.TvUnreachable)
        TvBrand(server.seedy?.name ?: "Seedy", Modifier.padding(start = 96.bp, top = 64.bp))
        Column(Modifier.fillMaxWidth().padding(top = 150.bp), horizontalAlignment = Alignment.CenterHorizontally) {
            Medu(MeduMood.Oops, 300.bp, height = 396.bp)
            SText(
                t("Can’t reach your server"), Modifier.padding(top = 16.bp),
                size = 66.bsp, weight = 900, lineHeight = 1.08f, tracking = -.015f, color = SeedyColors.White, align = TextAlign.Center,
            )
            SText(
                t("Medu dropped the cable. Check that {server} is switched on and connected, then try again.", "server" to server.name),
                Modifier.padding(top = 16.bp).widthIn(max = 900.bp),
                size = 28.bsp, lineHeight = 1.55f, color = DeepText, align = TextAlign.Center,
            )
            Row(Modifier.padding(top = 42.bp), horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                TvBigButton(t("Try again"), onRetry, Modifier.focusRequester(retry), icon = WebIcons.Refetch, primary = true)
                TvBigButton(t("Choose another server"), onChooseServer)
            }
            Row(
                Modifier.padding(top = 56.bp).clip(CircleShape).background(SeedyColors.Bg.copy(alpha = .7f)).padding(horizontal = 26.bp, vertical = 16.bp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(28.bp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                    Box(Modifier.size(12.bp).background(SeedyColors.Danger, CircleShape))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SText("${server.name} · ", size = 21.bsp, color = SeedyColors.Text2, maxLines = 1)
                        BasicText(server.host, style = BaseText.copy(fontFamily = FontFamily.Monospace, fontSize = 21.bsp, color = SeedyColors.Text2), maxLines = 1)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.bp)) {
                    Spinner(22.bp, SeedyColors.Text)
                    SText(
                        if (retryIn != null) t("Trying again in {n} s", "n" to retryIn) else t("Trying again…"),
                        size = 21.bsp, color = SeedyColors.Text2, maxLines = 1,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { retry.focusSoon() }
}
