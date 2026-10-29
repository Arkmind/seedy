package io.github.arkmind.seedy.mobile.books.reader

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.viewinterop.AndroidView
import io.github.arkmind.seedy.core.books.BookChapter
import io.github.arkmind.seedy.core.books.EpubPos
import io.github.arkmind.seedy.core.books.EpubSettings
import io.github.arkmind.seedy.core.books.TocEntry
import io.github.arkmind.seedy.core.books.toJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/* The EPUB's page: epub.js 0.3.93 (the web reader's build) in a WebView, from the app's assets (assets/reader). The
   book file comes from this phone's cache; the page tells the app where the reader is (EpubBridge), the app tells the
   page what to do (EpubController). */

/** What epub.js says. */
sealed interface EpubEvent {
    data object Ready : EpubEvent
    data class Counted(val total: Int) : EpubEvent
    data class Contents(val toc: List<TocEntry>) : EpubEvent
    data class Relocated(val pos: EpubPos) : EpubEvent
    /** The middle of the page: show or hide the bars. */
    data object Tap : EpubEvent
    /** A page turned by a tap or a swipe. */
    data object Turn : EpubEvent
    data class Failed(val message: String) : EpubEvent
}

/** The page's JavaScript calls this (kept through R8 by android/app/proguard-rules.pro). */
class EpubBridge internal constructor(private val onMessage: (String) -> Unit) {
    @JavascriptInterface
    fun post(json: String) = onMessage(json)
}

/** Sends the page what to do; what's asked before it has loaded waits for it. */
class EpubController {
    internal var web: WebView? = null
    private var loaded = false
    private val queue = mutableListOf<String>()

    private fun run(js: String) {
        val w = web
        if (w != null && loaded) w.evaluateJavascript(js, null) else queue += js
    }

    internal fun detach() {
        web = null
        loaded = false
    }

    internal fun pageLoaded() {
        loaded = true
        val pending = queue.toList()
        queue.clear()
        pending.forEach { web?.evaluateJavascript(it, null) }
    }

    fun open(settings: EpubSettings, cfi: String?, percent: Double?, chapters: List<BookChapter>) {
        val o = buildJsonObject {
            put("file", BOOK_URL)
            put("settings", settings.toJson())
            cfi?.let { put("cfi", it) }
            percent?.let { put("percent", it) }
            put("chapters", buildJsonArray {
                chapters.forEach { c -> add(buildJsonObject { put("Title", c.title); put("Href", c.href); put("Page", c.page) }) }
            })
        }
        run("seedy.open($o)")
    }

    fun next() = run("seedy.next()")
    fun prev() = run("seedy.prev()")
    fun goTo(target: String) = run("seedy.goTo(${JsonPrimitive(target)})")
    fun goToPage(page: Int) = run("seedy.goToPage($page)")
    fun settings(s: EpubSettings) = run("seedy.settings(${s.toJson()})")
}

private const val HOST = "appassets.androidplatform.net"
private const val PAGE_URL = "https://$HOST/reader/epub.html"
private const val BOOK_URL = "https://$HOST/book.epub"

/** The book's pages in the text column ([padding] from the frame). */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EpubPage(file: File, controller: EpubController, background: Int, padding: PaddingValues, onEvent: (EpubEvent) -> Unit) {
    val events = rememberUpdatedState(onEvent)
    val main = remember { Handler(Looper.getMainLooper()) }
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(padding),
        factory = { context ->
            WebView(context).apply {
                // Without these a WebView in Compose sizes its page to its content, and the book's 100% height is 0.
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(background)
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                overScrollMode = WebView.OVER_SCROLL_NEVER
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.textZoom = 100
                settings.setSupportZoom(false)
                addJavascriptInterface(EpubBridge { json -> parse(json)?.let { e -> main.post { events.value(e) } } }, "SeedyReader")
                webViewClient = ReaderClient(context, file) { controller.pageLoaded() }
                controller.web = this
                loadUrl(PAGE_URL)
            }
        },
        update = { it.setBackgroundColor(background) },
    )
    DisposableEffect(controller) {
        onDispose {
            controller.web?.apply {
                stopLoading()
                removeJavascriptInterface("SeedyReader")
                destroy()
            }
            controller.detach()
        }
    }
}

/** Serves the reader page from the assets and the book from the cache, under one https origin; nothing else loads. */
private class ReaderClient(private val context: Context, private val file: File, private val onLoaded: () -> Unit) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if (url.scheme != "https" && url.scheme != "http") return null
        if (url.host != HOST) return blocked()
        val path = url.path.orEmpty()
        return runCatching {
            when {
                path == "/book.epub" -> response("application/epub+zip", file.inputStream())
                path.startsWith("/reader/") && !path.contains("..") -> {
                    val name = path.removePrefix("/")
                    response(mimeOf(name), context.assets.open(name))
                }
                else -> blocked()
            }
        }.getOrElse { blocked() }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = request.url.host != HOST

    override fun onPageFinished(view: WebView, url: String?) {
        if (url?.startsWith(PAGE_URL) == true) onLoaded()
    }

    private fun response(mime: String, data: java.io.InputStream) = WebResourceResponse(
        mime, if (mime.startsWith("text/") || mime.endsWith("javascript")) "utf-8" else null, 200, "OK",
        mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"), data,
    )

    private fun blocked() = WebResourceResponse("text/plain", "utf-8", 404, "Not here", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))

    private fun mimeOf(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "html" -> "text/html"
        "js" -> "application/javascript"
        "css" -> "text/css"
        "woff2" -> "font/woff2"
        else -> "application/octet-stream"
    }
}

private fun parse(json: String): EpubEvent? = runCatching {
    val o = Json.parseToJsonElement(json).jsonObject
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    fun int(k: String) = (o[k] as? JsonPrimitive)?.intOrNull ?: 0
    fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    when (str("type")) {
        "ready" -> EpubEvent.Ready
        "model" -> EpubEvent.Counted(int("total"))
        "toc" -> EpubEvent.Contents(
            o["items"]?.jsonArray.orEmpty().mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                TocEntry(
                    title = (c["title"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    href = (c["href"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    page = (c["page"] as? JsonPrimitive)?.intOrNull ?: 0,
                )
            },
        )
        "relocated" -> str("cfi")?.let { EpubEvent.Relocated(EpubPos(it, int("index"), int("page"), bool("atStart"), bool("atEnd"))) }
        "tap" -> EpubEvent.Tap
        "turn" -> EpubEvent.Turn
        "error" -> EpubEvent.Failed(str("message").orEmpty())
        else -> null
    }
}.getOrNull()
