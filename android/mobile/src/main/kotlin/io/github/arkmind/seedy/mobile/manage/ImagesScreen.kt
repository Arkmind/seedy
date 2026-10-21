package io.github.arkmind.seedy.mobile.manage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.manage.ImageSlot
import io.github.arkmind.seedy.core.manage.ImagesActions
import io.github.arkmind.seedy.core.manage.ImagesState
import io.github.arkmind.seedy.core.manage.ImagesViewModel
import io.github.arkmind.seedy.core.manage.OfferedImage
import io.github.arkmind.seedy.core.settings.Choice
import io.github.arkmind.seedy.mobile.admin.ErrorCard
import io.github.arkmind.seedy.mobile.admin.LoadingCards
import io.github.arkmind.seedy.mobile.admin.MenuPill
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.SaveBarRoom
import io.github.arkmind.seedy.mobile.admin.SearchPickSheet
import io.github.arkmind.seedy.mobile.parts.Toast
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.mobile.settings.Card
import io.github.arkmind.seedy.mobile.settings.RowLine
import io.github.arkmind.seedy.mobile.settings.SettingsPage
import io.github.arkmind.seedy.ui.components.Artwork
import io.github.arkmind.seedy.ui.components.boxShadow
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import io.github.arkmind.seedy.ui.theme.currentSession

/** Edit images (board ItemImages) as a phone page, for admins. */
@Composable
fun ImagesRoute(id: String, onBack: () -> Unit) {
    val session = currentSession()
    val vm = viewModel(key = "images:$id") { ImagesViewModel(session, id) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.let { vm.upload(it, mime) }
        }
    }
    ImagesScreen(state, vm, onBack, onUpload = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
}

@Composable
fun ImagesScreen(state: ImagesState, actions: ImagesActions, onBack: () -> Unit, onUpload: () -> Unit = {}) {
    var sheet by remember { mutableStateOf<String?>(null) }
    SettingsPage(
        t("Edit images"),
        lede = null,
        onBack = onBack,
        overlay = {
            if (!state.loading && !state.failed) ImagesFooter(onBack)
            Toast(state.message?.takeIf { !state.failed }, actions::messageShown, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            when (sheet) {
                "provider" -> SearchPickSheet(
                    t("Image source"), null, listOf(Choice<String?>(null, t("All providers"))) + state.providers.map { Choice<String?>(it, ImagesViewModel.providerName(it)) },
                    state.provider, actions::provider, { sheet = null },
                )
                "language" -> SearchPickSheet(
                    t("Image language"), null, listOf(Choice(true, t("Any language")), Choice(false, t("This title’s language"))), state.allLanguages, actions::languages, { sheet = null },
                )
            }
        },
    ) {
        ItemHead(state.slots.firstOrNull()?.images?.firstOrNull()?.url, state.name.ifEmpty { "…" }, t("Pick a slot, then choose from providers or upload your own"))
        when {
            state.failed -> ErrorCard(t("Couldn’t load the images"), actions::retry, Modifier.padding(top = 16.dp), state.message.orEmpty())
            state.loading -> LoadingCards(2)
            else -> {
                Card(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    state.slots.forEachIndexed { i, slot ->
                        if (i > 0) RowLine()
                        SlotRow(slot, slot.type == state.picked, state.busy, actions)
                    }
                }
                Offers(state, actions, onSheet = { sheet = it }, onUpload = onUpload)
                SaveBarRoom()
            }
        }
    }
}

@Composable
private fun SlotRow(slot: ImageSlot, picked: Boolean, busy: String?, actions: ImagesActions) {
    val accent = LocalAccent.current
    Row(
        Modifier.fillMaxWidth().tap { actions.pick(slot.type) }
            .then(if (picked) Modifier.background(accent.copy(alpha = 0.08f)) else Modifier)
            .drawBehind { if (picked) drawRect(accent, size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp).heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SlotPreview(slot)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            SText(slot.name, size = 14.sp, weight = 700, color = SeedyColors.White, maxLines = 1)
            SText(slot.sub, size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val label = when {
            slot.many -> t("Add")
            slot.images.isEmpty() -> t("Find")
            else -> t("Change")
        }
        PillButton(label, { actions.pick(slot.type) }, height = 34.dp)
        if (!slot.many && slot.images.isNotEmpty()) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(SeedyColors.Surface).tap(enabled = busy == null) { actions.remove(slot.type, slot.images.first().index) }, contentAlignment = Alignment.Center) {
                SIcon(ManageIcons.Trash, size = 15.dp, color = SeedyColors.Text)
            }
        }
    }
}

/** What's in a slot: the poster, three backdrops, the logo, the thumbnail, or a dashed banner when empty. */
@Composable
private fun SlotPreview(slot: ImageSlot) {
    val shape = RoundedCornerShape(6.dp)
    when (slot.type) {
        "Primary" -> Artwork(slot.images.firstOrNull()?.url, Modifier.size(34.dp, 50.dp).clip(shape), placeholder = SeedyColors.Surface)
        "Backdrop" -> Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            val shown = slot.images.take(2)
            for (img in shown) Artwork(img.url, Modifier.size(30.dp, 30.dp).clip(shape), placeholder = SeedyColors.Surface)
            repeat(2 - shown.size) { Empty(Modifier.size(30.dp, 30.dp)) }
        }
        else -> {
            val img = slot.images.firstOrNull()
            if (img == null) Empty(Modifier.size(63.dp, 30.dp))
            else Box(Modifier.size(63.dp, 30.dp).clip(shape).background(SeedyColors.Surface)) {
                Artwork(img.url, Modifier.matchParentSize(), placeholder = SeedyColors.Surface, scale = if (slot.type == "Logo") ContentScale.Fit else ContentScale.Crop)
            }
        }
    }
}

@Composable
private fun Empty(modifier: Modifier) {
    Box(
        modifier.drawBehind {
            val w = 1.5.dp.toPx()
            drawRoundRect(
                Color(0xFF4A4A4A), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), cornerRadius = CornerRadius(6.dp.toPx()),
                style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        },
    )
}

private fun ratio(type: String): Float = when (type) {
    "Primary" -> 2f / 3f
    "Banner" -> 1000f / 185f
    else -> 16f / 9f
}

@Composable
private fun Offers(state: ImagesState, actions: ImagesActions, onSheet: (String) -> Unit, onUpload: () -> Unit) {
    val slot = state.slot ?: return
    val offered = state.offered
    SText(
        if (offered == null) t("{slot} from providers", "slot" to slotName(slot.type)) else t("{slot} from providers · {n}", "slot" to slotName(slot.type), "n" to state.offeredTotal),
        Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 10.dp), size = 15.sp, weight = 800, color = SeedyColors.White,
    )
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MenuPill(state.provider?.let(ImagesViewModel::providerName) ?: t("All providers"), { onSheet("provider") })
        MenuPill(if (state.allLanguages) t("Any language") else t("This title’s language"), { onSheet("language") })
        PillButton(if (state.busy == "upload") t("Uploading…") else t("Upload"), onUpload, enabled = state.busy == null, leading = { SIcon(ManageIcons.Upload, size = 15.dp, color = SeedyColors.White) })
    }
    if (slot.many && slot.images.isNotEmpty()) {
        SText(t("On the title"), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp), size = 12.sp, weight = 800, tracking = 0.1f, color = SeedyColors.Text3, uppercase = true)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (img in slot.images) {
                Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(SeedyColors.Surface)) {
                    Artwork(img.url, Modifier.matchParentSize(), placeholder = SeedyColors.Surface)
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp).clip(CircleShape).background(SeedyColors.Bg.copy(alpha = 0.85f))
                            .tap(enabled = state.busy == null) { actions.remove(slot.type, img.index) },
                        contentAlignment = Alignment.Center,
                    ) { SIcon(ManageIcons.Trash, size = 14.dp, color = SeedyColors.White) }
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    when {
        offered == null -> Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(2) { Box(Modifier.weight(1f).aspectRatio(ratio(slot.type)).clip(RoundedCornerShape(12.dp)).background(SeedyColors.Panel)) }
        }
        offered.isEmpty() -> SText(t("The providers have nothing for this slot. Upload your own instead."), Modifier.padding(horizontal = 20.dp), size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        else -> {
            var focused by remember(slot.type) { mutableStateOf<String?>(null) }
            val columns = if (slot.type == "Banner") 1 else 2
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (row in offered.chunked(columns)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (img in row) OfferCard(img, slot, focused == img.url, state.busy == img.url, Modifier.weight(1f), { focused = if (focused == img.url) null else img.url }) { actions.use(img) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferCard(img: OfferedImage, slot: ImageSlot, focused: Boolean, busy: Boolean, modifier: Modifier, onClick: () -> Unit, onUse: () -> Unit) {
    val accent = LocalAccent.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(ratio(slot.type))
                .then(if (focused) Modifier.drawBehind {
                    val gap = 4.dp.toPx()
                    drawRoundRect(accent, topLeft = Offset(-gap, -gap), size = Size(size.width + gap * 2, size.height + gap * 2), cornerRadius = CornerRadius(15.dp.toPx()), style = Stroke(2.dp.toPx()))
                } else Modifier)
                .clip(RoundedCornerShape(12.dp)).background(SeedyColors.Surface).tap(onClick = onClick),
        ) {
            Artwork(img.thumb, Modifier.matchParentSize(), placeholder = SeedyColors.Surface, scale = if (slot.type == "Logo") ContentScale.Fit else ContentScale.Crop)
            if (img.inUse) {
                Row(
                    Modifier.padding(6.dp).clip(RoundedCornerShape(7.dp)).background(SeedyColors.Bg.copy(alpha = 0.85f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SIcon(WebIcons.Check, size = 11.dp, color = SeedyColors.Ok, stroke = 3f)
                    SText(t("In use"), size = 11.sp, weight = 800, color = SeedyColors.Ok, maxLines = 1)
                }
            }
            if (focused || busy) {
                Box(Modifier.matchParentSize().background(SeedyColors.Bg.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    PillButton(
                        if (busy) t("Adding…") else useLabel(slot.type), onUse, style = PillStyle.Light, enabled = !busy && !img.inUse, height = 36.dp,
                    )
                }
            }
        }
        SText(img.meta, Modifier.padding(horizontal = 2.dp), size = 12.sp, color = SeedyColors.Text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun slotName(type: String): String = when (type) {
    "Primary" -> t("Posters")
    "Backdrop" -> t("Backdrops")
    "Logo" -> t("Logos")
    "Thumb" -> t("Thumbnails")
    "Banner" -> t("Banners")
    else -> type
}

private fun useLabel(type: String): String = when (type) {
    "Backdrop" -> t("Add backdrop")
    "Primary" -> t("Use as poster")
    "Logo" -> t("Use as logo")
    "Thumb" -> t("Use as thumbnail")
    "Banner" -> t("Use as banner")
    else -> t("Use")
}

/** The pinned foot: changes save as they're made, and Done. */
@Composable
private fun BoxScope.ImagesFooter(onDone: () -> Unit) {
    val nav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Row(
        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .boxShadow(Color.Black.copy(alpha = 0.5f), blur = 30.dp, y = (-10).dp)
            .background(SeedyColors.Row).tap {}
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = max(26.dp, nav + 12.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SIcon(WebIcons.Check, size = 15.dp, color = SeedyColors.Ok, stroke = 2.8f)
        SText(t("Changes save right away"), Modifier.weight(1f), size = 13.sp, color = SeedyColors.Text2, maxLines = 1)
        PillButton(t("Done"), onDone, style = PillStyle.Light, height = 44.dp)
    }
}

