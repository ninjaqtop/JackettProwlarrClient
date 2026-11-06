package com.aggregatorx.app.engine.network

import android.util.Log
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Which engine actually carried a request. */
enum class TransportEngine { NATIVE_TLS, OKHTTP_FALLBACK }

data class TransportRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    /** Request body for POST; null for GET. */
    val body: String? = null,
    /** Hard bound for this request (the provider's saved timeout on search paths). */
    val timeoutMs: Int = HttpTransport.DEFAULT_TIMEOUT_MS,
    val clientProfile: String = TlsClient.DEFAULT_PROFILE,
    val contentType: String = "application/x-www-form-urlencoded"
)

data class TransportResponse(
    val url: String,
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val finalUrl: String,
    /** Transport-level failure (DNS, TLS, timeout, ...). Null when an HTTP response was received. */
    val error: String?,
    val engine: TransportEngine,
    val elapsedMs: Long,
    /** Set when this request was carried by OkHttp instead of the native TLS client, and why. */
    val degradedReason: String? = null
) {
    val isHttpOk: Boolean get() = error == null && statusCode in 200..399
    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    fun contentType(): String? = header("Content-Type")
    fun cookies(): Map<String, String> = headers.entries
        .filter { it.key.equals("Set-Cookie", ignoreCase = true) }
        .flatMap { it.value }
        .mapNotNull { raw ->
            val pair = raw.substringBefore(';')
            val name = pair.substringBefore('=').trim()
            if (name.isBlank() || !pair.contains('=')) null else name to pair.substringAfter('=').trim()
        }.toMap()
    fun parse(): Document = Jsoup.parse(body, finalUrl.ifBlank { url })
}

/** User-visible state of the transport layer (shown on the Search and Settings screens). */
data class TransportHealth(
    val nativeLoaded: Boolean,
    val nativeUnavailableReason: String?,
    val nativeRequests: Long = 0,
    val fallbackRequests: Long = 0,
    val lastDegradation: String? = null,
    val lastDegradationAt: Long = 0
) {
    val degraded: Boolean get() = !nativeLoaded || fallbackRequests > 0
    fun summary(): String = when {
        !nativeLoaded -> "Native TLS client unavailable (${nativeUnavailableReason ?: "not loaded"}) — " +
            "all requests are using plain OkHttp (degraded fingerprint)"
        fallbackRequests > 0 -> "Native TLS client active; $fallbackRequests request(s) fell back to OkHttp " +
            "(last: ${lastDegradation ?: "unknown"})"
        else -> "Native TLS client active ($nativeRequests request(s))"
    }
}

/**
 * THE transport for the app's search, Analyzer and recon fetches (spec §6).
 *
 *  - Primary engine: the native TLS client ([TlsClient], Go tls-client via JNI) so
 *    requests carry a browser-grade TLS fingerprint.
 *  - Bounded + async: every native call runs on a dedicated, capped pool
 *    ([MAX_NATIVE_IN_FLIGHT] threads) and is awaited with a hard deadline
 *    (request timeout + grace). A native call that overruns is abandoned by the
 *    caller (the Go client's own timeout ends it) — it can never stall a search.
 *  - Degradation: when the native pair failed to load, or a native call fails at
 *    the transport level, the request is carried by OkHttp. That is never silent:
 *    it is logged (tag [TAG]), counted, recorded on the response
 *    ([TransportResponse.degradedReason]) and published in [health] for the UI.
 *  - HTTP statuses are NOT a reason to fall back: a 403 from the native client is
 *    the site's real answer and is returned as-is.
 */
object HttpTransport {
    private const val TAG = "HttpTransport"
    const val DEFAULT_TIMEOUT_MS = 15_000
    const val MIN_TIMEOUT_MS = 1_000
    const val MAX_TIMEOUT_MS = 60_000
    private const val NATIVE_GRACE_MS = 2_000L
    private const val MAX_NATIVE_IN_FLIGHT = 8
    private const val MAX_BODY_BYTES = 8L * 1024 * 1024

    private val nativeExecutor = Executors.newFixedThreadPool(MAX_NATIVE_IN_FLIGHT) { r ->
        Thread(r, "native-tls").apply { isDaemon = true }
    }

    /** Native calls from suspend and blocking callers share the same capped pool. */
    private val nativeScope = CoroutineScope(SupervisorJob() + nativeExecutor.asCoroutineDispatcher())

    private val nativeCount = AtomicLong(0)
    private val fallbackCount = AtomicLong(0)

    private val _health = MutableStateFlow(
        TransportHealth(
            nativeLoaded = TlsClient.isAvailable,
            nativeUnavailableReason = TlsClient.unavailableReason
        )
    )
    val health: StateFlow<TransportHealth> = _health.asStateFlow()

    /**
     * Plain OkHttp for the degraded path. Deliberately NOT wrapped by
     * TlsFingerprintEngine's interceptor: that interceptor sets Accept-Encoding
     * itself, which disables OkHttp's transparent gzip decoding.
     */
    private val okHttpBase: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun clampTimeout(ms: Int) = ms.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)

    /** Suspend fetch: native TLS first, bounded; OkHttp only as a recorded degradation. */
    suspend fun fetch(request: TransportRequest): TransportResponse {
        val timeout = clampTimeout(request.timeoutMs)
        val bounded = request.copy(timeoutMs = timeout)
        val started = System.currentTimeMillis()
        val nativeFailure: String = if (TlsClient.isAvailable) {
            val deferred = nativeScope.async { TlsClient.execute(bounded.toTlsRequest()) }
            val native = try {
                withTimeoutOrNull(timeout + NATIVE_GRACE_MS) { deferred.await() }
            } catch (e: CancellationException) {
                deferred.cancel()
                throw e
            }
            if (native == null) {
                "native call exceeded its ${timeout} ms bound"
            } else if (native.error.isNullOrBlank() && native.statusCode > 0) {
                return native.toTransport(bounded.url, started).also { recordNative() }
            } else {
                native.error ?: "native call returned no response"
            }
        } else {
            "native TLS unavailable: ${TlsClient.unavailableReason ?: "not loaded"}"
        }
        return withContext(Dispatchers.IO) { okHttpFetch(bounded, started, nativeFailure) }
    }

    /**
     * Blocking variant for call sites that already run on an IO thread and are not
     * suspend. Same policy and bounds as [fetch]. Never call from the main thread.
     */
    fun fetchBlocking(request: TransportRequest): TransportResponse {
        val timeout = clampTimeout(request.timeoutMs)
        val bounded = request.copy(timeoutMs = timeout)
        val started = System.currentTimeMillis()
        val nativeFailure: String = if (TlsClient.isAvailable) {
            val future = nativeExecutor.submit<TlsResponse> { TlsClient.execute(bounded.toTlsRequest()) }
            val native = try {
                future.get(timeout + NATIVE_GRACE_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                null
            } catch (e: InterruptedException) {
                future.cancel(true)
                Thread.currentThread().interrupt()
                return TransportResponse(bounded.url, 0, emptyMap(), "", bounded.url, "interrupted", TransportEngine.NATIVE_TLS,
                    System.currentTimeMillis() - started)
            } catch (e: Exception) {
                TlsResponse(error = e.cause?.message ?: e.message ?: e.javaClass.simpleName)
            }
            if (native == null) {
                "native call exceeded its ${timeout} ms bound"
            } else if (native.error.isNullOrBlank() && native.statusCode > 0) {
                return native.toTransport(bounded.url, started).also { recordNative() }
            } else {
                native.error ?: "native call returned no response"
            }
        } else {
            "native TLS unavailable: ${TlsClient.unavailableReason ?: "not loaded"}"
        }
        return okHttpBlocking(bounded, started, nativeFailure)
    }

    /** Jsoup-shaped builder over this transport (see [TransportConnection]). */
    fun connect(url: String): TransportConnection = TransportConnection(url)

    /**
     * Compatibility bridge for code written against [TlsClient.execute]: same request
     * and response types, but carried by this transport (bounded, with recorded
     * degradation) instead of an unbounded direct native call.
     */
    fun executeTls(request: TlsRequest): TlsResponse {
        val response = fetchBlocking(
            TransportRequest(
                url = request.url,
                method = request.method,
                headers = request.headers,
                body = request.body.takeIf { request.method.equals("POST", true) || it.isNotEmpty() },
                timeoutMs = request.timeoutMs,
                clientProfile = request.clientProfile
            )
        )
        return TlsResponse(
            statusCode = response.statusCode,
            headers = response.headers,
            body = response.body,
            finalUrl = response.finalUrl,
            error = response.error
        )
    }

    /** Convenience: fetch and parse an HTML document, or null on transport failure / non-2xx-3xx. */
    suspend fun fetchDocument(
        url: String,
        headers: Map<String, String> = defaultHeaders(),
        timeoutMs: Int = DEFAULT_TIMEOUT_MS
    ): Document? {
        val response = fetch(TransportRequest(url = url, headers = headers, timeoutMs = timeoutMs))
        return if (response.isHttpOk && response.body.isNotBlank()) response.parse() else null
    }

    fun defaultHeaders(acceptJson: Boolean = false): Map<String, String> = buildMap {
        put("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
        put(
            "Accept",
            if (acceptJson) "application/json, text/javascript, */*;q=0.8"
            else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "en-US,en;q=0.9")
    }

    private fun TransportRequest.toTlsRequest() = TlsRequest(
        url = url,
        method = method.uppercase(),
        headers = if (body != null && headers.keys.none { it.equals("Content-Type", true) }) {
            headers + ("Content-Type" to contentType)
        } else headers,
        body = body.orEmpty(),
        clientProfile = clientProfile,
        timeoutMs = timeoutMs
    )

    private fun TlsResponse.toTransport(url: String, started: Long) = TransportResponse(
        url = url,
        statusCode = statusCode,
        headers = headers,
        body = body,
        finalUrl = finalUrl.ifBlank { url },
        error = null,
        engine = TransportEngine.NATIVE_TLS,
        elapsedMs = System.currentTimeMillis() - started
    )

    private fun recordNative() {
        val n = nativeCount.incrementAndGet()
        _health.update { it.copy(nativeLoaded = TlsClient.isAvailable, nativeUnavailableReason = TlsClient.unavailableReason, nativeRequests = n) }
    }

    private fun recordFallback(url: String, reason: String) {
        val n = fallbackCount.incrementAndGet()
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: url.take(60)
        Log.w(TAG, "DEGRADED: $host carried by OkHttp instead of native TLS — $reason")
        _health.update {
            it.copy(
                nativeLoaded = TlsClient.isAvailable,
                nativeUnavailableReason = TlsClient.unavailableReason,
                fallbackRequests = n,
                lastDegradation = "$host: $reason".take(200),
                lastDegradationAt = System.currentTimeMillis()
            )
        }
    }

    private fun buildOkRequest(request: TransportRequest): Request {
        val builder = Request.Builder().url(request.url)
        request.headers
            // OkHttp decodes gzip itself only when it negotiates the encoding.
            .filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
            .forEach { (k, v) -> runCatching { builder.header(k, v) } }
        if (request.headers.keys.none { it.equals("User-Agent", true) }) {
            builder.header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
        }
        if (request.body != null) {
            builder.method(
                request.method.uppercase().ifBlank { "POST" },
                request.body.toRequestBody(request.contentType.toMediaTypeOrNull())
            )
        }
        return builder.build()
    }

    private fun clientFor(timeoutMs: Int): OkHttpClient = okHttpBase.newBuilder()
        .callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        .build()

    private fun Response.toTransport(request: TransportRequest, started: Long, reason: String): TransportResponse {
        val source = body
        val text = if (source == null) "" else {
            val bytes = source.source().let { s ->
                s.request(MAX_BODY_BYTES)
                s.buffer.snapshot().let { snap ->
                    if (snap.size > MAX_BODY_BYTES) snap.substring(0, MAX_BODY_BYTES.toInt()) else snap
                }
            }
            bytes.string(source.contentType()?.charset() ?: Charsets.UTF_8)
        }
        return TransportResponse(
            url = request.url,
            statusCode = code,
            headers = headers.toMultimap(),
            body = text,
            finalUrl = this.request.url.toString(),
            error = null,
            engine = TransportEngine.OKHTTP_FALLBACK,
            elapsedMs = System.currentTimeMillis() - started,
            degradedReason = reason
        )
    }

    private fun failure(request: TransportRequest, started: Long, reason: String, e: Throwable) = TransportResponse(
        url = request.url,
        statusCode = 0,
        headers = emptyMap(),
        body = "",
        finalUrl = request.url,
        error = "${e.javaClass.simpleName}: ${e.message ?: "no detail"} (native: $reason)",
        engine = TransportEngine.OKHTTP_FALLBACK,
        elapsedMs = System.currentTimeMillis() - started,
        degradedReason = reason
    )

    private fun okHttpBlocking(request: TransportRequest, started: Long, reason: String): TransportResponse {
        recordFallback(request.url, reason)
        return try {
            clientFor(request.timeoutMs).newCall(buildOkRequest(request)).execute().use {
                it.toTransport(request, started, reason)
            }
        } catch (e: Exception) {
            failure(request, started, reason, e)
        }
    }

    private suspend fun okHttpFetch(request: TransportRequest, started: Long, reason: String): TransportResponse {
        recordFallback(request.url, reason)
        val okRequest = try {
            buildOkRequest(request)
        } catch (e: Exception) {
            return failure(request, started, reason, e)
        }
        val call = clientFor(request.timeoutMs).newCall(okRequest)
        return try {
            suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = runCatching { response.use { it.toTransport(request, started, reason) } }
                        result.fold(
                            onSuccess = { if (cont.isActive) cont.resume(it) },
                            onFailure = { if (cont.isActive) cont.resumeWithException(it) }
                        )
                    }
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(request, started, reason, e)
        }
    }
}
