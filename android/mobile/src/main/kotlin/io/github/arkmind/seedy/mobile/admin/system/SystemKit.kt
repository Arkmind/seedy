package io.github.arkmind.seedy.mobile.admin.system

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.admin.system.Dot
import io.github.arkmind.seedy.core.i18n.t
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.mobile.admin.AdminIcons
import io.github.arkmind.seedy.mobile.admin.Card
import io.github.arkmind.seedy.mobile.admin.NoticeCard
import io.github.arkmind.seedy.mobile.admin.PillButton
import io.github.arkmind.seedy.mobile.admin.PillStyle
import io.github.arkmind.seedy.mobile.admin.RowLine
import io.github.arkmind.seedy.mobile.browse.BottomSheet
import io.github.arkmind.seedy.mobile.parts.tap
import io.github.arkmind.seedy.ui.icons.IconDef
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors

/*
 * Parts the System pages share beyond the admin kit: menu and Live pills, the round run/stop and ⋯ buttons, the
 * Users board's tab bar, plugin tiles, status dots, a menu sheet with lines under its items, loading and error cards,
 * and downloads from the server.
 */

/** A pill that opens a list ("Everyone ▾", "All levels ▾"). */
@Composable
fun MenuPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PillButton(label, onClick, modifier, trailing = { SIcon(AdminIcons.Caret, size = 13.dp, color = SeedyColors.White) })
}

/** Live: a green dot while new activity shows up as it happens, grey while paused. */
@Composable
fun LivePill(on: Boolean, onClick: () -> Unit, onLabel: String = t("Live"), offLabel: String = t("Paused"), enabled: Boolean = true) {
    PillButton(
        if (on) onLabel else offLabel, onClick, enabled = enabled,
        leading = { Box(Modifier.size(7.dp).background(if (on) SeedyColors.Ok else Color(0xFF5A5A5A), CircleShape)) },
    )
}

/** The boards' 40 px round button: dark with a white icon, or light with a dark one (a running task's stop). */
@Composable
fun RoundButton(icon: IconDef, onClick: () -> Unit, light: Boolean = false, iconSize: Dp = 16.dp, enabled: Boolean = true, bare: Boolean = false, tint: Color? = null) {
    Box(
        Modifier.size(40.dp).clip(CircleShape)
            .background(if (bare) Color.Transparent else if (light) SeedyColors.Inverse else SeedyColors.Surface)
            .tap(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.5f),
        contentAlignment = Alignment.Center,
    ) { SIcon(icon, size = iconSize, color = tint ?: if (light) SeedyColors.InkInverse else SeedyColors.White) }
}

class TabItem(val label: String, val count: String?, val on: Boolean, val onClick: () -> Unit)

/** The Users board's tabs: a #1f1f1f well with 36 px choices, the picked one light, each with its count. */
@Composable
fun TabBar(tabs: List<TabItem>, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(13.dp)).background(SeedyColors.Surface).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        for (tab in tabs) {
            Row(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(10.dp)).background(if (tab.on) SeedyColors.Inverse else Color.Transparent).tap(onClick = tab.onClick),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SText(tab.label, size = 13.sp, weight = if (tab.on) 800 else 600, color = if (tab.on) SeedyColors.InkInverse else SeedyColors.TextHero, maxLines = 1)
                if (!tab.count.isNullOrEmpty()) SText(" " + tab.count, size = 13.sp, weight = 600, color = if (tab.on) Color(0xFF5A5A5A) else SeedyColors.Text3, maxLines = 1)
            }
        }
    }
}

/** A plugin's or repository's 44 px tile with its letters; Seedy's is light. */
@Composable
fun LetterTile(letters: String, light: Boolean = false) {
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(if (light) SeedyColors.Inverse else SeedyColors.Surface), contentAlignment = Alignment.Center) {
        SText(letters, size = 15.sp, weight = 900, color = if (light) SeedyColors.InkInverse else SeedyColors.White, maxLines = 1)
    }
}

fun dotColor(dot: Dot): Color = when (dot) {
    Dot.Ok -> SeedyColors.Ok
    Dot.Pending -> SeedyColors.Pending
    Dot.Muted -> Color(0xFF6A6A6A)
    Dot.Danger -> SeedyColors.Danger
}

/** "● Active": a 6 px dot and a short bold label. */
@Composable
fun StatusLine(label: String, dot: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        SText(label, size = 12.sp, weight = 700, color = SeedyColors.TextHero, maxLines = 1)
    }
}

class MenuItem(val label: String, val sub: String? = null, val danger: Boolean = false, val enabled: Boolean = true, val run: () -> Unit = {})

/** A sheet of things to do with one item, each with an optional line under it (the web's menus). */
@Composable
fun MenuSheet(title: String, items: List<MenuItem>, onDismiss: () -> Unit, text: String? = null) {
    BottomSheet(onDismiss = onDismiss) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SText(title, size = 17.sp, weight = 800, color = SeedyColors.White)
            if (text != null) SText(text, size = 13.sp, lineHeight = 1.45f, color = SeedyColors.Text2)
        }
        Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            for (item in items) {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(12.dp))
                        .tap(enabled = item.enabled) { onDismiss(); item.run() }.alpha(if (item.enabled) 1f else 0.5f)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
                ) {
                    SText(item.label, size = 15.sp, weight = 600, color = if (item.danger) SeedyColors.Danger else SeedyColors.Text)
                    if (!item.sub.isNullOrEmpty()) SText(item.sub, size = 12.sp, color = SeedyColors.Text3)
                }
            }
        }
    }
}

/** Grey bars standing in for [rows] rows while a list loads. */
@Composable
fun SkeletonCard(rows: Int, modifier: Modifier = Modifier, tile: Boolean = false) {
    Card(modifier.padding(horizontal = 16.dp)) {
        repeat(rows) { i ->
            if (i > 0) RowLine()
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (tile) Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(SeedyColors.Row))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width((150 + (i * 53) % 80).dp).height(14.dp).clip(RoundedCornerShape(7.dp)).background(SeedyColors.Surface))
                    Box(Modifier.width((190 + (i * 37) % 60).dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(SeedyColors.Row))
                }
            }
        }
    }
}

/** A list that couldn't load: the sentence and the server's reason, and Try again. */
@Composable
fun ErrorCard(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    NoticeCard(message, "", t("Try again"), SeedyColors.Danger, SeedyColors.Danger.copy(alpha = 0.08f), onRetry, modifier.padding(horizontal = 16.dp))
}

/** An empty list: a title and a line. */
@Composable
fun EmptyCard(title: String, text: String?, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Card(modifier.padding(horizontal = 16.dp), padding = 16.dp, gap = 2.dp) {
        SText(title, size = 13.sp, weight = 700, color = SeedyColors.White)
        if (!text.isNullOrEmpty()) SText(text, size = 12.sp, lineHeight = 1.4f, color = SeedyColors.Text3)
        if (action != null) Box(Modifier.padding(top = 10.dp)) { action() }
    }
}

/** A 44 px search field with the magnifier, what it found on the right, and an optional trailing part. */
@Composable
fun SearchBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    count: String = "",
    onSubmit: () -> Unit = {},
    trailing: (@Composable () -> Unit)? = null,
) {
    val accent = LocalAccent.current
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(14.dp)).background(SeedyColors.Surface).padding(start = 12.dp, end = if (trailing != null) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SIcon(WebIcons.Search, size = 16.dp, color = SeedyColors.Text3, stroke = 2f)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) SText(placeholder, size = 14.sp, color = SeedyColors.Text3, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = BaseText.copy(fontSize = 14.sp, fontWeight = FontWeight(400), color = SeedyColors.Text),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (count.isNotEmpty()) SText(count, size = 12.sp, color = SeedyColors.Text2, maxLines = 1)
        trailing?.invoke()
    }
}

/** A light pill that's busy ("Updating…") while [busy]. */
@Composable
fun LightPill(label: String, onClick: () -> Unit, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null) =
    PillButton(label, onClick, style = PillStyle.Light, enabled = enabled, leading = leading)

/** Small grey text that ellipsizes. */
@Composable
fun Quiet(text: String, modifier: Modifier = Modifier, color: Color = SeedyColors.Text3, maxLines: Int = 1) =
    SText(text, modifier, size = 12.sp, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/* ── Files from the server ── */

/**
 * Downloads a server file ([path] with [query]) into Downloads with the person's token, as the system's download
 * notification. Before Android 10 it lands in the app's own Downloads folder (no storage permission needed).
 */
fun downloadFromServer(context: Context, session: UserSession, path: String, query: Map<String, String>, fileName: String, mime: String): Boolean = runCatching {
    val base = session.api.baseUrl ?: return false
    val token = session.api.accessToken ?: return false
    val uri = Uri.parse(base.trimEnd('/') + path).buildUpon().apply { query.forEach { (k, v) -> appendQueryParameter(k, v) } }.build()
    val request = DownloadManager.Request(uri)
        .addRequestHeader("Authorization", "MediaBrowser Token=\"$token\"")
        .setTitle(fileName)
        .setMimeType(mime)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
    else request.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
    context.getSystemService(DownloadManager::class.java).enqueue(request)
    true
}.getOrDefault(false)

/** Puts [text] on the clipboard; false when it's too long or the clipboard refused it. */
fun copyText(context: Context, label: String, text: String): Boolean = runCatching {
    // Binder transactions top out near 1 MB; longer text can't go through the clipboard.
    if (text.length > 400_000) return false
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    true
}.getOrDefault(false)

/** Tasks' "see logs": the Logs page opens on that day's log, searching for the task. */
internal object LogsLink {
    var file: String? = null
    var query: String = ""
}
