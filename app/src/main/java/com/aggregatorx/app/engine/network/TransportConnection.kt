package com.aggregatorx.app.engine.network

import org.jsoup.Connection
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URL

/**
 * Jsoup-`Connection`-shaped builder over [HttpTransport].
 *
 * Search, Analyzer and recon code historically fetched with `Jsoup.connect(url)`,
 * which is a separate HTTP stack (HttpURLConnection) with a JVM TLS fingerprint.
 * `HttpTransport.connect(url)` keeps the same call shape so every such site rides
 * the one transport (native TLS primary, logged OkHttp degradation) instead.
 *
 * Blocking: call from IO threads only (all existing call sites already are).
 */
class TransportConnection internal constructor(private val url: String) {
    private val headers = linkedMapOf<String, String>()
    private val cookies = linkedMapOf<String, String>()
    private var timeoutMs: Int = HttpTransport.DEFAULT_TIMEOUT_MS
    private var ignoreHttpErrors = false
    private var method: Connection.Method = Connection.Method.GET
    private var requestBody: String? = null

    fun userAgent(ua: String) = apply { headers["User-Agent"] = ua }
    fun header(name: String, value: String) = apply { headers[name] = value }
    fun headers(values: Map<String, String>) = apply { headers.putAll(values) }
    fun cookie(name: String, value: String) = apply { cookies[name] = value }
    fun cookies(values: Map<String, String>) = apply { cookies.putAll(values) }
    fun referrer(value: String) = apply { headers["Referer"] = value }
    fun timeout(ms: Int) = apply { timeoutMs = if (ms <= 0) HttpTransport.MAX_TIMEOUT_MS else ms }
    fun ignoreHttpErrors(value: Boolean) = apply { ignoreHttpErrors = value }
    /** Content type is not filtered by the transport; kept for call-shape compatibility. */
    @Suppress("UNUSED_PARAMETER")
    fun ignoreContentType(value: Boolean) = this
    /** Redirects are always followed by both engines; kept for call-shape compatibility. */
    @Suppress("UNUSED_PARAMETER")
    fun followRedirects(value: Boolean) = this
    /** The transport caps bodies at 8 MB; kept for call-shape compatibility. */
    @Suppress("UNUSED_PARAMETER")
    fun maxBodySize(bytes: Int) = this
    fun method(value: Connection.Method) = apply { method = value }
    fun requestBody(body: String) = apply { requestBody = body }

    @Throws(IOException::class)
    fun execute(): TransportJsoupResponse {
        val allHeaders = LinkedHashMap(headers)
        if (cookies.isNotEmpty() && allHeaders.keys.none { it.equals("Cookie", true) }) {
            allHeaders["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        if (allHeaders.keys.none { it.equals("User-Agent", true) }) {
            allHeaders.putAll(HttpTransport.defaultHeaders().filterKeys { k -> allHeaders.keys.none { it.equals(k, true) } })
        }
        val response = HttpTransport.fetchBlocking(
            TransportRequest(
                url = url,
                method = method.name,
                headers = allHeaders,
                body = requestBody ?: if (method == Connection.Method.POST) "" else null,
                timeoutMs = timeoutMs
            )
        )
        if (response.error != null) throw IOException(response.error)
        if (!ignoreHttpErrors && response.statusCode >= 400) {
            throw HttpStatusException("HTTP error fetching URL", response.statusCode, url)
        }
        return TransportJsoupResponse(response)
    }

    @Throws(IOException::class)
    fun get(): Document = execute().parse()

    @Throws(IOException::class)
    fun post(): Document = method(Connection.Method.POST).execute().parse()
}

/** Jsoup-`Connection.Response`-shaped view of a [TransportResponse]. */
class TransportJsoupResponse(val raw: TransportResponse) {
    fun statusCode(): Int = raw.statusCode
    fun body(): String = raw.body
    fun url(): URL = URL(raw.finalUrl.ifBlank { raw.url })
    fun parse(): Document = Jsoup.parse(raw.body, raw.finalUrl.ifBlank { raw.url })
    fun headers(): Map<String, String> = raw.headers.mapValues { it.value.joinToString(", ") }
    fun header(name: String): String? = raw.header(name)
    fun cookies(): Map<String, String> = raw.cookies()
    fun contentType(): String? = raw.contentType()
    val engine: TransportEngine get() = raw.engine
}
