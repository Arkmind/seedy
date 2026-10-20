package io.github.arkmind.seedy.tv.signin

import androidx.compose.foundation.background
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
private fun TvBrand(name: String) {
    BrandMark(name, markSize = 50.bp, radius = 14.bp, letterSize = 26.bsp, wordSize = 36.bsp, gap = 16.bp)
}

/**
 * The sign-in pages' frame: brand on top, the page's heading 120 px under it, actions at the bottom. While the
 * system keyboard is up (it covers the lower half of the screen) the brand and the spacing go, so the field being
 * typed in stays above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Frame(brand: String, title: String, bottom: @Composable () -> Unit = {}, content: @Composable () -> Unit) {
    val typing = WindowInsets.isImeVisible
    Column(
        Modifier.fillMaxSize().background(SeedyColors.Bg).padding(start = 96.bp, end = 96.bp, top = if (typing) 48.bp else 96.bp, bottom = 72.bp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!typing) TvBrand(brand)
        SText(
            title, Modifier.padding(top = if (typing) 0.bp else 120.bp),
            size = if (typing) 36.bsp else 56.bsp, weight = 900, tracking = -0.02f, lineHeight = 1f, color = SeedyColors.White,
        )
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
        brand, t("Who's watching?"),
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
    LaunchedEffect(state.profiles.isNotEmpty()) { if (state.profiles.isNotEmpty()) first.focusSoon() }
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

/** "Add your server" on the TV: servers found on the network, or an address typed with the remote. */
@Composable
fun TvAddServerScreen(state: AddServerViewModel.State, onAdd: (String) -> Unit, onSearch: () -> Unit) {
    var address by rememberSaveable { mutableStateOf("") }
    val first = remember { FocusRequester() }
    Frame("Seedy", t("Add your server"), bottom = {
        SText(t("Seedy plays from your Jellyfin server."), size = 19.bsp, color = SeedyColors.Text3)
    }) {
        Column(Modifier.padding(top = if (LocalTyping.current) 28.bp else 72.bp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.bp)) {
            if (state.found.isNotEmpty() && !LocalTyping.current) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                    state.found.forEachIndexed { i, s ->
                        TvButton(s.name, { onAdd(s.address) }, if (i == 0) Modifier.focusRequester(first) else Modifier, icon = AppIcons.Server)
                    }
                }
                SText(t("or type its address"), size = 20.bsp, color = SeedyColors.Text3)
            } else {
                SText(if (state.searching) t("Looking for servers on this network…") else t("No server answered on this network."), size = 22.bsp, color = SeedyColors.Text2)
            }
            TvField(
                address, { address = it }, "192.168.1.20:8096",
                if (state.found.isEmpty()) Modifier.focusRequester(first) else Modifier,
                keyboard = KeyboardType.Uri, onDone = { onAdd(address) },
            )
            state.error?.let { SText(it, size = 20.bsp, weight = 600, color = SeedyColors.Danger) }
            Row(horizontalArrangement = Arrangement.spacedBy(24.bp)) {
                TvButton(if (state.busy) t("Connecting…") else t("Connect"), { onAdd(address) }, primary = true)
                if (!state.searching) TvButton(t("Look again"), onSearch)
            }
        }
    }
    LaunchedEffect(state.found.isNotEmpty()) { first.focusSoon() }
}
