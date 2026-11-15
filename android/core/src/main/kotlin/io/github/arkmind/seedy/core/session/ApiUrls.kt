package io.github.arkmind.seedy.core.session

import org.jellyfin.sdk.api.client.ApiClient
import java.net.URLEncoder

/** The server's address without a trailing slash ("https://jf.example.com"). */
val ApiClient.serverBase: String get() = baseUrl.orEmpty().trimEnd('/')

/** [path] (or a full address) on this server with the session's token in it, for players and downloads that can't send headers. */
fun ApiClient.withApiKey(path: String): String {
    val full = if (path.startsWith("http")) path else serverBase + path
    if (Regex("[?&](ApiKey|api_key)=", RegexOption.IGNORE_CASE).containsMatchIn(full)) return full
    return full + (if ('?' in full) "&" else "?") + "ApiKey=" + urlEncode(accessToken.orEmpty())
}

/** "a=1&b=2", every key and value URL-encoded. */
fun queryString(q: Map<String, String>): String = q.entries.joinToString("&") { "${urlEncode(it.key)}=${urlEncode(it.value)}" }

private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8")
