package io.github.arkmind.seedy.tv.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.em
import androidx.compose.ui.zIndex
import io.github.arkmind.seedy.core.feature.SettingsViewModel
import io.github.arkmind.seedy.core.i18n.I18n
import io.github.arkmind.seedy.core.i18n.Lang
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.i18n.tn
import io.github.arkmind.seedy.core.seedy.HomeRowPref
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.core.settings.Choices
import io.github.arkmind.seedy.core.settings.RowView
import io.github.arkmind.seedy.core.settings.SettingsActions
import io.github.arkmind.seedy.core.settings.SettingsState
import io.github.arkmind.seedy.core.settings.SettingsState.Companion.Keys
import io.github.arkmind.seedy.tv.parts.TvPressable
import io.github.arkmind.seedy.tv.parts.bp
import io.github.arkmind.seedy.tv.parts.bsp
import io.github.arkmind.seedy.tv.parts.focusScale
import io.github.arkmind.seedy.tv.parts.focusSoon
import io.github.arkmind.seedy.ui.components.ProfileSquare
import io.github.arkmind.seedy.ui.components.PassScene
import io.github.arkmind.seedy.ui.components.SubtitleLine
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.SettingsIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.LocalLinearText
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.parseHexColor
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/** The TV settings' sections, in the left list's order. */
enum class TvSection(val label: String, val icon: IconDef) {
    Profile("Profile and sign-in", SettingsIcons.User),
    Home("Home screen", SettingsIcons.Home),
    Playback("Playback", SettingsIcons.Play),
    Subtitles("Subtitles", SettingsIcons.Subtitles),
    Display("Display", SettingsIcons.Display),
    Remote("Remote", SettingsIcons.Remote),
    About("About Seedy", SettingsIcons.Info),
}

private val CardBg = Color(0xFF161616)

/**
 * Boards "Android TV · Settings · …": the profile and the sections on the left, the section on the right. Rows change
 * with left and right, switches with OK; Back returns to the list, then leaves. [focusRow] and [moving] put the remote
 * on a row (and pick a Home row up) for screenshot tests.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvSettingsScreen(
    state: SettingsState,
    actions: SettingsActions,
    appVersion: String,
    onLeave: () -> Unit,
    start: TvSection = TvSection.Profile,
    focusRow: String? = null,
    moving: String? = null,
) {
    var section by remember { mutableStateOf(start) }
    var inPane by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val categories = remember { TvSection.entries.associateWith { FocusRequester() } }
    val pane = remember { FocusRequester() }
    val rowFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    val scrollSpec = remember(density) { EdgeScroll(top = 60.bp.value * density.density, bottom = 170.bp.value * density.density) }

    BackHandler { if (inPane) categories.getValue(section).requestFocus() else onLeave() }

    CompositionLocalProvider(LocalLinearText provides true) {
        Box(Modifier.fillMaxSize().background(SeedyColors.Bg)) {
            // Left: Settings, the profile, the sections, Switch profile and Sign out.
            Column(Modifier.padding(start = 176.bp, top = 64.bp).width(480.bp).focusGroup(), verticalArrangement = Arrangement.spacedBy(8.bp)) {
                SText(t("Settings"), Modifier.padding(bottom = 14.bp).cssBox(44f, io.github.arkmind.seedy.ui.theme.NotoLineRatio), size = 44.bsp, weight = 900, color = SeedyColors.White)
                Row(
                    Modifier.padding(bottom = 14.bp).fillMaxWidth().clip(RoundedCornerShape(20.bp)).background(CardBg).padding(horizontal = 18.bp, vertical = 16.bp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp),
                ) {
                    ProfileSquare(state.name, state.imageUrl, 0, size = 60.bp, radius = 30.bp, letterSize = 26.bsp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
                        SText(state.name, size = 22.bsp, weight = 800, color = SeedyColors.White, maxLines = 1)
                        SText(listOfNotNull(t("Admin").takeIf { state.isAdmin }, state.serverName).joinToString(" · "), size = 16.bsp, color = SeedyColors.Text3, maxLines = 1)
                    }
                }
                for (s in TvSection.entries) {
                    Category(s, s == section, categories.getValue(s), pane) { section = s }
                }
                Row(Modifier.padding(top = 14.bp), horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                    LeftButton(t("Switch profile"), SeedyColors.Text, actions::switchProfile)
                    LeftButton(t("Sign out"), SeedyColors.Danger, actions::signOut)
                }
            }

            // Right: the section, scrolling under a fade.
            CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
                Column(
                    Modifier.padding(start = 704.bp, end = 80.bp, top = 64.bp).fillMaxSize()
                        .focusRequester(pane)
                        .focusProperties { left = categories.getValue(section) }
                        .focusRestorer()
                        .onFocusChanged { inPane = it.hasFocus }
                        .focusGroup()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.bp, end = 16.bp, bottom = 160.bp),
                ) {
                    key(section) {
                        SText(t(section.label), Modifier.padding(horizontal = 24.bp).cssBox(40f, io.github.arkmind.seedy.ui.theme.NotoLineRatio), size = 40.bsp, weight = 900, color = SeedyColors.White)
                        SText(
                            lede(section, state), Modifier.padding(start = 24.bp, end = 24.bp, top = 8.bp, bottom = 6.bp).widthIn(max = 948.bp).cssBox(19f, 1.45f),
                            size = 19.bsp, lineHeight = 1.45f, color = SeedyColors.Text2,
                        )
                        val focusKey = focusRow
                        val f: (String) -> FocusRequester? = { k -> if (k == focusKey) rowFocus else null }
                        when (section) {
                            TvSection.Profile -> ProfileSection(state, actions, f)
                            TvSection.Home -> HomeSection(state, actions, f, moving) { hint = it }
                            TvSection.Playback -> PlaybackSection(state, actions, f)
                            TvSection.Subtitles -> SubtitlesSection(state, actions, f)
                            TvSection.Display -> DisplaySection(state, actions, f)
                            TvSection.Remote -> RemoteSection(state, actions, f)
                            TvSection.About -> AboutSection(state, appVersion, f)
                        }
                    }
                }
            }
            Box(
                Modifier.align(Alignment.BottomStart).padding(start = 720.bp).fillMaxWidth().height(150.bp)
                    .background(Brush.verticalGradient(0f to Color.Transparent, 0.65f to SeedyColors.Bg, 1f to SeedyColors.Bg)),
            )
            SText(
                if (section == TvSection.Home && hint != null) hint!! else t("Left and right change the value · Back returns to the list"),
                Modifier.align(Alignment.BottomEnd).padding(end = 96.bp, bottom = 44.bp), size = 17.bsp, color = SeedyColors.Text3,
            )
        }
    }
    LaunchedEffect(Unit) { if (focusRow != null) rowFocus.focusSoon() else categories.getValue(start).focusSoon() }
}

private fun lede(s: TvSection, state: SettingsState): String = when (s) {
    TvSection.Profile -> t("Your picture, password, requests and privacy. These follow your profile to every device.")
    TvSection.Home -> t("Which rows you see and in what order. Follows your profile to every device.")
    TvSection.Playback -> t("Quality, sound, what happens between episodes, and skipping. Quality is set per device.")
    TvSection.Subtitles -> t("When subtitles turn on by themselves, and how they look on this TV.")
    TvSection.Display -> t("Language, colour and what shows while you browse.")
    TvSection.Remote -> t("What the remote’s buttons do while a video plays. These stay on this TV.")
    TvSection.About -> listOfNotNull("Seedy", state.serverName.takeIf { it.isNotEmpty() }).joinToString(" · ")
}

/** A section in the left list: lit while it's on screen, light while the remote is on it. Moving onto it shows it. */
@Composable
private fun Category(s: TvSection, current: Boolean, requester: FocusRequester, pane: FocusRequester, onShow: () -> Unit) {
    TvPressable(
        onClick = { onShow(); pane.requestFocus() },
        modifier = Modifier.focusRequester(requester).focusProperties { right = pane }.onFocusChanged { if (it.isFocused) onShow() },
    ) { f ->
        val ink = when {
            f -> SeedyColors.InkInverse
            current -> SeedyColors.White
            else -> SeedyColors.Text2
        }
        Row(
            Modifier.fillMaxWidth().height(62.bp).focusScale(f, 1.02f).clip(RoundedCornerShape(18.bp))
                .background(if (f) SeedyColors.Inverse else if (current) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 20.bp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.bp),
        ) {
            SIcon(s.icon, size = 26.bp, color = ink, stroke = 2f)
            SText(t(s.label), size = 21.bsp, weight = if (current || f) 800 else 600, color = ink, maxLines = 1)
        }
    }
}

@Composable
private fun LeftButton(label: String, ink: Color, onClick: () -> Unit) {
    TvPressable(onClick) { f ->
        Box(
            Modifier.height(52.bp).focusScale(f, 1.04f).clip(RoundedCornerShape(15.bp))
                .background(if (f) SeedyColors.Inverse else Color.White.copy(alpha = 0.07f)).padding(horizontal = 20.bp),
            contentAlignment = Alignment.Center,
        ) { SText(label, size = 18.bsp, weight = 700, color = if (f) SeedyColors.InkInverse else ink, maxLines = 1) }
    }
}

/* ── Sections ── */

@Composable
private fun ProfileSection(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?) {
    TvSectionTitle(t("Sign in a phone with a code"))
    CodeRow(state, actions, f("code"))
    TvSectionTitle(t("Password"))
    TvInfoRow(t("Password"), state.passwordChanged ?: t("The same password for every Jellyfin app you use"), t("Change it on your phone or the web"), focus = f("password"))
    if (state.requests.connected) {
        TvSectionTitle(t("Requests"))
        val account = state.requests.account
        TvInfoRow(t("Seerr account"), if (account?.linkedBy == "manual") t("Linked by your admin") else t("Linked through your Jellyfin sign-in"), (account?.name ?: state.name).lowercase())
    }
    if (state.seedy) {
        TvSectionTitle(t("Privacy"))
        val p = state.prefs.privacy
        TvSwitchRow(t("Personal recommendations"), t("Because you watched, genre rows and row order learn from your plays"), p.personalRecs, { actions.setPrivacy(it, p.countInTop10) })
        TvSwitchRow(t("Count my plays in the Top 10"), t("Others see totals only, never who watched"), p.countInTop10, { actions.setPrivacy(p.personalRecs, it) })
    }
    TvSectionTitle(t("Devices"))
    TvInfoRow(t("Signed in on"), t("Sign out the ones you don’t use any more"), state.devices?.let { tn(it, "{n} device", "{n} devices") } ?: "")
}

/** Quick Connect on the TV: OK opens the keyboard on the code; Done approves it. */
@Composable
private fun CodeRow(state: SettingsState, actions: SettingsActions, focus: FocusRequester?) {
    val accent = LocalAccent.current
    val c = state.code
    var typing by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    TvRowFrame(onClick = { typing = true }, focus = focus) { f ->
        Row(Modifier.padding(horizontal = 24.bp, vertical = 12.bp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.bp)) {
            TvRowText(t("Code shown on the other device"), c.message ?: t("Choose Quick Connect there, then type the code it shows. It signs in as you."), false, f, Modifier.weight(1f))
            Box(Modifier.width(260.bp).height(60.bp).clip(RoundedCornerShape(14.bp)).background(if (f) Color(0xFFDADADA) else SeedyColors.Surface).padding(horizontal = 18.bp), contentAlignment = Alignment.CenterStart) {
                if (typing) {
                    BasicTextField(
                        c.code, actions::setCode, singleLine = true,
                        textStyle = BaseText.copy(fontSize = 26.bsp, fontWeight = FontWeight(800), color = if (f) SeedyColors.InkInverse else SeedyColors.White, letterSpacing = 0.3.em),
                        cursorBrush = SolidColor(accent),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { typing = false; actions.approveCode() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field),
                    )
                    LaunchedEffect(Unit) { field.focusSoon() }
                } else {
                    SText(c.code.ifEmpty { "······" }, size = 26.bsp, weight = 800, tracking = 0.3f, color = if (f) SeedyColors.InkInverse else if (c.code.isEmpty()) SeedyColors.Text3 else SeedyColors.White)
                }
            }
        }
    }
}

@Composable
private fun HomeSection(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?, preview: String?, onHint: (String?) -> Unit) {
    if (state.seedy) {
        val mine = state.prefs.rowOrder == "mine"
        TvSectionTitle(t("Order and Google TV"))
        TvChoiceRow(t("Order"), t("Rows you play from most move up once a day"), listOf(Choice(false, t("Automatic")), Choice(true, t("My order"))), mine, actions::setRowOrder, focus = f("order"))
        TvSwitchRow(
            t("Continue watching on the Google TV home"), t("Your in-progress titles in the home screen’s Continue watching row"), state.device.googleTvContinue,
            { v -> actions.setDevice { it.copy(googleTvContinue = v) } }, device = true, focus = f("googletv"),
        )
        Box(Modifier.padding(start = 24.bp, end = 24.bp, top = 18.bp, bottom = 10.bp)) {
            SText(if (mine) t("Rows · in my order") else t("Rows · ordered automatically"), Modifier.cssBox(15f, io.github.arkmind.seedy.ui.theme.NotoLineRatio, up = false), size = 15.bsp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        }
        TvRows(state, actions, f, preview, onHint)
    }
    val libraries = state.libraries.orEmpty()
    if (libraries.isNotEmpty()) {
        TvSectionTitle(t("Libraries on Home"))
        for (l in libraries) TvSwitchRow(l.name, state.libraryLine(l), state.onHome(l), { actions.setLibraryOnHome(l.id, it) })
    }
    TvSectionTitle(t("Next up and Continue watching"))
    if (state.seedy) {
        val days = state.customInt(Keys.NextUpDays, 365)
        val options = (listOf(7, 14, 30, 60, 90, 180, 365, 730, 0) + days).distinct().sortedWith(compareBy { if (it == 0) Int.MAX_VALUE else it })
            .map { Choice(it, if (it == 0) t("Never") else t("{n} days", "n" to it)) }
        TvChoiceRow(t("Drop a show from Next up after"), t("Days since you last watched it"), options, days, { actions.setCustom(Keys.NextUpDays, JsonPrimitive(it)) })
        TvSwitchRow(t("Include shows I’m rewatching"), t("Show the next episode even if you’ve seen it"), state.customBool(Keys.Rewatching, false), { actions.setCustom(Keys.Rewatching, JsonPrimitive(it)) })
        TvSwitchRow(t("Use episode pictures"), t("Instead of the show’s artwork"), state.customBool(Keys.EpisodeImages, true), { actions.setCustom(Keys.EpisodeImages, if (it) JsonNull else JsonPrimitive(false)) })
    }
    TvSwitchRow(t("Hide what I’ve watched from Latest"), t("Only new-to-you titles in Latest rows"), state.config?.hidePlayedInLatest != false, { on -> actions.setConfig { it.copy(hidePlayedInLatest = on) } })
}

/**
 * Home's rows on the TV: OK picks a row up, up and down move it, OK drops it (Back puts it back); left and right show
 * or hide a row.
 */
@Composable
private fun TvRows(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?, preview: String?, onHint: (String?) -> Unit) {
    val rows = state.rows()
    var moving by remember { mutableStateOf(preview) }
    var order by remember { mutableStateOf<List<String>?>(null) }
    val shown = order?.mapNotNull { k -> rows.firstOrNull { it.kind == k } }?.takeIf { it.size == rows.size } ?: rows
    fun save(list: List<RowView>) = actions.setRows(list.map { HomeRowPref(it.kind, it.enabled, it.count, it.pinned) })

    BackHandler(enabled = moving != null && order != null) { moving = null; order = null; onHint(null) }
    LaunchedEffect(preview) { if (preview != null) onHint(t("Up and down move the row · OK drops it · Back cancels")) }

    Column(verticalArrangement = Arrangement.spacedBy(6.bp)) {
        shown.forEachIndexed { i, r ->
            key(r.kind) {
                val isMoving = r.kind == moving
                TvPressable(
                    onClick = {
                        if (moving == null) {
                            moving = r.kind; order = shown.map { it.kind }
                            onHint(t("Up and down move the row · OK drops it · Back cancels"))
                        } else {
                            val list = order?.mapNotNull { k -> rows.firstOrNull { it.kind == k } }
                            if (list != null && list.map { it.kind } != rows.map { it.kind }) save(list)
                            moving = null; order = null; onHint(null)
                        }
                    },
                    modifier = Modifier.zIndex(if (isMoving) 1f else 0f)
                        .then(f(r.kind)?.let { Modifier.focusRequester(it) } ?: Modifier)
                        .onFocusChanged { if (it.isFocused && moving == null) onHint(t("OK picks the row up to move it · Left and right show or hide it")) }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (isMoving && order != null) {
                                val list = order!!.toMutableList()
                                val at = list.indexOf(r.kind)
                                val to = when (e.key) {
                                    Key.DirectionUp -> at - 1
                                    Key.DirectionDown -> at + 1
                                    else -> return@onPreviewKeyEvent e.key == Key.DirectionLeft || e.key == Key.DirectionRight
                                }
                                if (to in list.indices) { list.add(to, list.removeAt(at)); order = list }
                                true
                            } else when (e.key) {
                                Key.DirectionLeft, Key.DirectionRight -> {
                                    val on = e.key == Key.DirectionRight
                                    if (on != r.enabled) actions.setRows(rows.map { HomeRowPref(it.kind, if (it.kind == r.kind) on else it.enabled, it.count, it.pinned) })
                                    true
                                }
                                else -> false
                            }
                        },
                ) { focused -> TvRowItem(r, i + 1, isMoving, focused) }
            }
        }
    }
}

@Composable
private fun TvRowItem(r: RowView, number: Int, moving: Boolean, focused: Boolean) {
    val accent = LocalAccent.current
    val light = moving || focused
    val ink = if (light) SeedyColors.InkInverse else if (r.enabled) SeedyColors.White else SeedyColors.Text3
    val sub = if (light) Color(0xFF4A4A4A) else SeedyColors.Text3
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer {
                val s = if (moving) 1.03f else if (focused) 1.02f else 1f
                scaleX = s; scaleY = s
                if (moving) translationY = -6.bp.toPx()
            }
            .then(
                if (moving) Modifier.boxShadow(Color.Black.copy(alpha = 0.6f), 44.bp, 22.bp, 18.bp)
                else if (focused) Modifier.boxShadow(Color.Black.copy(alpha = 0.5f), 30.bp, 14.bp, 18.bp) else Modifier,
            )
            .clip(RoundedCornerShape(18.bp)).background(if (light) SeedyColors.Inverse else Color.Transparent)
            .padding(start = 18.bp, end = 24.bp, top = 8.bp, bottom = 8.bp).heightIn(min = 56.bp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.bp),
    ) {
        Box(Modifier.width(36.bp), contentAlignment = Alignment.Center) {
            if (moving) SIcon(SettingsIcons.Move, size = 26.bp, color = SeedyColors.InkInverse)
            else SText(number.toString(), size = 19.bsp, weight = 700, color = if (light) SeedyColors.InkInverse else Quiet)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.bp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.bp)) {
                SText(r.name, size = 21.bsp, weight = 700, color = ink, maxLines = 1)
                val tag = when {
                    moving -> t("Moving")
                    r.pinned -> t("Pinned")
                    r.count != null -> tn(r.count ?: 0, "{n} row", "{n} rows")
                    else -> null
                }
                if (tag != null) {
                    Box(Modifier.clip(RoundedCornerShape(999.bp)).background(if (moving) accent else Color.White.copy(alpha = 0.1f)).padding(horizontal = 10.bp, vertical = 2.bp)) {
                        SText(tag, size = 13.bsp, weight = 800, color = if (moving) SeedyColors.InkInverse else SeedyColors.TextHero, maxLines = 1)
                    }
                }
            }
            SText(r.help, size = 16.bsp, color = sub, maxLines = 1)
        }
        TvSwitch(r.enabled)
    }
}

@Composable
private fun PlaybackSection(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?) {
    val d = state.device
    val pb = state.playback
    TvSectionTitle(t("Picture"))
    TvChoiceRow(
        t("Quality"), t("Auto picks the best your connection allows"), Choices.qualities(Choices.autoUpTo(), picture = false), d.qualityHome,
        { v -> actions.setDevice { it.copy(qualityHome = v, qualityAway = v) } }, device = true, focus = f("quality"),
    )
    TvChoiceRow(t("Largest picture when converting"), t("Used when the server has to transcode"), Choices.resolutions(), d.maxResolution, { v -> actions.setDevice { it.copy(maxResolution = v) } }, device = true, focus = f("resolution"))
    TvSwitchRow(t("Match frame rate"), t("Switch the TV to the video’s frame rate"), d.matchFrameRate, { v -> actions.setDevice { it.copy(matchFrameRate = v) } }, device = true)
    TvSwitchRow(t("Match dynamic range"), t("Switch to HDR or Dolby Vision when the video has it"), d.matchDynamicRange, { v -> actions.setDevice { it.copy(matchDynamicRange = v) } }, device = true)

    TvSectionTitle(t("Sound"))
    LaunchedEffect(Unit) { actions.loadLanguages() }
    TvChoiceRow(
        t("Preferred audio language"), t("Picked when a title has several"), listOf(Choice("", t("Original language"))) + state.languages,
        state.config?.audioLanguagePreference.orEmpty(), { v -> actions.setConfig { it.copy(audioLanguagePreference = v.ifEmpty { null }) } },
        label = state.audioLanguage,
    )
    TvChoiceRow(t("Surround sound"), t("Send Dolby and DTS straight to your soundbar or receiver"), Choices.channels(tv = true), d.audioChannels, { v -> actions.setDevice { it.copy(audioChannels = v) } }, device = true)

    TvSectionTitle(t("Between episodes"))
    TvSwitchRow(t("Play the next episode automatically"), t("From the Up next card at the credits"), pb.autoPlayNext, actions::setAutoPlayNext)
    if (state.seedy) {
        TvChoiceRow(
            t("Up next countdown"), t("Your admin’s default is {n} seconds", "n" to state.defaults.upNextSeconds),
            (Choices.UpNextSeconds + pb.upNextSeconds).distinct().sorted().map { Choice(it, Choices.seconds(it)) }, pb.upNextSeconds,
            { v -> actions.setPlayback { it.copy(upNextSeconds = v) } },
        )
        val still = state.stillWatching
        TvChoiceRow(
            t("Ask “Are you still watching?”"), t("Pauses auto-play when nobody’s there"),
            (Choices.StillWatching + still).distinct().map { Choice(it, Choices.stillWatching(it, short = true)) }, still, actions::setStillWatching,
        )
    }
    val c = state.config
    TvSwitchRow(
        t("Keep my audio and subtitle choice"), t("Pick the same kind of track as the last episode"), c == null || (c.rememberAudioSelections && c.rememberSubtitleSelections),
        { v -> actions.setConfig { it.copy(rememberAudioSelections = v, rememberSubtitleSelections = v) } },
    )

    TvSectionTitle(t("Skipping"))
    if (state.seedy) {
        val skips = Choices.skips()
        TvChoiceRow(t("Intros"), null, skips, pb.skipIntro, { v -> actions.setPlayback { it.copy(skipIntro = v) } })
        TvChoiceRow(t("Recaps"), null, skips, pb.skipRecap, { v -> actions.setPlayback { it.copy(skipRecap = v) } })
        TvChoiceRow(t("Credits"), t("Goes to Up next or end-of-movie picks"), skips, pb.skipCredits, { v -> actions.setPlayback { it.copy(skipCredits = v) } })
    }
    val pairs = (Choices.SeekPairs + (state.seekBack to state.seekForward)).distinct()
    TvChoiceRow(
        t("Skip back and forward"), t("Left and right on the remote while playing"),
        pairs.map { Choice(it, "${Choices.seconds(it.first)} · ${Choices.seconds(it.second)}") }, state.seekBack to state.seekForward,
        { (b, fw) -> actions.setDevice { it.copy(seekBack = b, seekForward = fw) } }, device = true,
    )
}

@Composable
private fun SubtitlesSection(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?) {
    val s = state.style
    val d = state.device
    Row(horizontalArrangement = Arrangement.spacedBy(40.bp)) {
        Column(Modifier.weight(1f)) {
            if (state.config != null) {
                TvSectionTitle(t("When to show them"))
                val modes = Choices.modes()
                val mode = modes.firstOrNull { it.value == state.subtitleMode }
                TvChoiceRow(t("Show subtitles"), mode?.help, modes.map { Choice(it.value, it.name) }, state.subtitleMode, actions::setSubtitleMode, focus = f("mode"))
                LaunchedEffect(Unit) { actions.loadLanguages() }
                TvChoiceRow(
                    t("Language"), null, listOf(Choice("", t("Any"))) + state.languages, state.config?.subtitleLanguagePreference.orEmpty(),
                    { v -> actions.setConfig { it.copy(subtitleLanguagePreference = v.ifEmpty { null }) } }, label = state.subtitleLanguage, focus = f("language"),
                )
            }
            TvSectionTitle(t("How they look"))
            TvChoiceRow(
                t("Style"), t("Seedy’s look, or your TV’s caption settings"), listOf(Choice(false, t("Seedy")), Choice(true, t("This TV’s"))), d.systemCaptionStyle,
                { v -> actions.setDevice { it.copy(systemCaptionStyle = v) } }, device = true, focus = f("style"),
            )
            TvChoiceRow(t("Size"), null, Choices.sizes(), s.size, { v -> actions.setSubtitleStyle { it.copy(size = v) } }, device = true, focus = f("size"))
            TvChoiceRow(t("Weight"), null, Choices.weights(), s.weight, { v -> actions.setSubtitleStyle { it.copy(weight = v) } }, device = true)
            val colors = Choices.colors()
            TvChoiceRow(
                t("Text colour"), null, colors.map { it.first }, s.color, { v -> actions.setSubtitleStyle { it.copy(color = v) } }, device = true,
                dot = Color(colors.firstOrNull { it.first.value == s.color }?.second ?: 0xFFFFFFFF),
            )
            TvChoiceRow(t("Background"), null, Choices.backgrounds().map { it.first }, s.background, { v -> actions.setSubtitleStyle { it.copy(background = v) } }, device = true)
            TvChoiceRow(t("Outline"), null, Choices.outlines(), s.outline, { v -> actions.setSubtitleStyle { it.copy(outline = v) } }, device = true)
            TvChoiceRow(t("Position"), null, Choices.positions(), s.position, { v -> actions.setSubtitleStyle { it.copy(position = v) } }, device = true)
            TvChoiceRow(t("Font"), null, Choices.fonts(), s.font, { v -> actions.setSubtitleStyle { it.copy(font = v) } }, device = true)

            TvSectionTitle(t("Rendering"))
            TvChoiceRow(t("Burn subtitles in"), t("Auto burns in picture subtitles only when needed"), Choices.burnIns(), d.burnIn, { v -> actions.setDevice { it.copy(burnIn = v) } }, device = true)
            TvSwitchRow(t("Show picture subtitles on this TV"), t("Draw PGS and VobSub here instead of on the server"), d.pictureSubtitles, { v -> actions.setDevice { it.copy(pictureSubtitles = v) } }, device = true)
        }
        Column(Modifier.width(440.bp).padding(top = 22.bp), verticalArrangement = Arrangement.spacedBy(12.bp)) {
            TvPreview(state)
            SText(t("Preview updates as you change the look. Styled (ASS) and picture (PGS) subtitles keep their own look."), size = 17.bsp, color = SeedyColors.Text3)
        }
    }
}

/** The TV preview: 440 px, the board's sizes (Large is 20 px). */
@Composable
private fun TvPreview(state: SettingsState) {
    val density = LocalDensity.current
    val sizes = mapOf("small" to 15.bsp, "normal" to 17.bsp, "large" to 20.bsp, "xlarge" to 24.bsp)
    Box(Modifier.fillMaxWidth().boxShadow(Color.Black.copy(alpha = 0.5f), 50.bp, 20.bp, 18.bp)) {
        PassScene(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(18.bp))) {
            val st = state.style
            val pos = when (st.position) {
                "top" -> Modifier.align(Alignment.TopCenter).padding(top = 44.bp)
                "bottom" -> Modifier.align(Alignment.BottomCenter).padding(bottom = 30.bp)
                else -> Modifier.align(Alignment.BottomCenter).padding(bottom = 44.bp)
            }
            SubtitleLine(t("We won't make the pass before dark."), st, sizes[st.size] ?: 17.bsp, pos.padding(horizontal = 16.bp), boardPx = density.density / 2)
            Box(Modifier.align(Alignment.BottomStart).padding(start = 18.bp, end = 18.bp, bottom = 18.bp).fillMaxWidth().height(5.bp).clip(RoundedCornerShape(3.bp)).background(Color.White.copy(alpha = 0.25f))) {
                Box(Modifier.fillMaxWidth(0.3f).fillMaxHeight().clip(RoundedCornerShape(3.bp)).background(Color.White))
            }
        }
    }
}

@Composable
private fun DisplaySection(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?) {
    val serverAccent = LocalAccent.current
    TvSectionTitle(t("Language and region"))
    val device = if (I18n.deviceLang() == Lang.Fr) "Français" else "English"
    val value = if (state.language.startsWith("fr")) "fr" else if (state.language.startsWith("en")) "en" else "auto"
    TvChoiceRow(
        t("Language"), t("Seedy’s menus and labels"),
        listOf(Choice("auto", t("Auto · {lang}", "lang" to device)), Choice("en", "English"), Choice("fr", "Français")), value, actions::setLanguage, focus = f("language"),
    )
    if (state.seedy) {
        TvSectionTitle(t("While browsing"))
        val accents = listOf(null to "Server colour", "#9fd3ff" to "Sky", "#b8f59f" to "Mint", "#ffc98a" to "Apricot", "#c9b8ff" to "Lilac", "#f0f0f0" to "White")
        val current = state.customString(Keys.Accent)?.lowercase()
        TvChoiceRow(
            t("Accent colour"), t("Counts, focus ring and selections. Starts as your server’s colour."), accents.map { Choice(it.first ?: "", t(it.second)) }, current ?: "",
            { v -> actions.setCustom(Keys.Accent, if (v.isEmpty()) JsonNull else JsonPrimitive(v)) },
            dot = current?.let(::parseHexColor) ?: serverAccent,
        )
        TvSwitchRow(t("Backdrops"), t("Artwork behind details pages and the home banner"), state.backdrops, { v -> actions.setCustom(Keys.Backdrops, if (v) JsonNull else JsonPrimitive(false)) })
    }
    TvSwitchRow(
        t("Show missing episodes"), t("List episodes you don’t have, greyed out, in each season"), state.config?.displayMissingEpisodes == true,
        { v -> actions.setConfig { it.copy(displayMissingEpisodes = v) } },
    )
}

@Composable
private fun RemoteSection(state: SettingsState, actions: SettingsActions, f: (String) -> FocusRequester?) {
    TvSectionTitle(t("Back button"))
    TvSwitchRow(
        t("Back button closes the player"), t("On remotes, Back stops playback instead of only hiding controls"), state.device.backClosesPlayer,
        { v -> actions.setDevice { it.copy(backClosesPlayer = v) } }, device = true, focus = f("back"),
    )
    TvSectionTitle(t("While a video plays"))
    TvInfoRow(t("Left and right"), null, t("Back {back} s · forward {forward} s", "back" to state.seekBack, "forward" to state.seekForward))
    TvInfoRow(t("OK"), null, t("Play or pause"))
    TvInfoRow(t("Up and down"), null, t("Show the controls"))
    TvInfoRow(t("Back"), null, if (state.device.backClosesPlayer) t("Leave the player") else t("Hide the controls, then leave"))
}

@Composable
private fun AboutSection(state: SettingsState, appVersion: String, f: (String) -> FocusRequester?) {
    TvSectionTitle(t("This app and your server"))
    SettingsViewModel.aboutRows(state, appVersion).forEachIndexed { i, (k, v) -> TvInfoRow(k, null, v, focus = if (i == 0) f("about") else null) }
    TvInfoRow(t("Address"), null, state.serverAddress)
}

internal val Quiet = Color(0xFF6A6A6A)

