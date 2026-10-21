package io.github.arkmind.seedy.mobile.admin.system

import android.app.DownloadManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.arkmind.seedy.core.session.UserSession
import io.github.arkmind.seedy.ui.icons.SIcon
import io.github.arkmind.seedy.ui.icons.WebIcons
import io.github.arkmind.seedy.ui.theme.BaseText
import io.github.arkmind.seedy.ui.theme.LocalAccent
import io.github.arkmind.seedy.ui.theme.SText
import io.github.arkmind.seedy.ui.theme.SeedyColors
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding

/*
 * Parts the System pages share beyond the admin kit: the search field of the plugin catalog and the log viewer, small
 * grey lines, and files from the server (downloads, the clipboard, the Logs page opened on a task's day).
 */

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
