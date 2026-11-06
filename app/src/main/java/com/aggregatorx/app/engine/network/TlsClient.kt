package com.aggregatorx.app.engine.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class TlsRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val clientProfile: String = TlsClient.DEFAULT_PROFILE,
    val timeoutMs: Int = 30000
)

@Serializable
data class TlsResponse(
    val statusCode: Int = 0,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
    val finalUrl: String = "",
    val error: String? = null
)

object TlsClient {
    const val DEFAULT_PROFILE = "chrome_120"

    val supportedProfiles: List<String> = listOf(
        "chrome_120",
        "chrome_124",
        "chrome_133",
        "chrome_144",
        "chrome_146",
        "brave_146",
        "firefox_135",
        "firefox_147",
        "firefox_148",
        "safari_ios_18_5",
        "okhttp4_android_13"
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val loadError: Throwable? = runCatching {
        System.loadLibrary("tlsclientbridge")
    }.exceptionOrNull()

    val isAvailable: Boolean
        get() = loadError == null

    val unavailableReason: String?
        get() = loadError?.message ?: loadError?.javaClass?.simpleName

    private external fun requestNative(json: String): String

    /**
     * Execute the native request away from the UI thread. The Go client has its own
     * bounded request timeout; callers should still wrap this in a coroutine timeout.
     */
    suspend fun executeAsync(request: TlsRequest): TlsResponse = withContext(Dispatchers.IO) {
        execute(request)
    }

    /**
     * Fetch an HTML/JSON page with native TLS. This is a transport primitive for the
     * site analyzer and scraper; parsing and any policy decisions remain in Kotlin.
     */
    suspend fun fetchHtml(
        url: String,
        headers: Map<String, String> = emptyMap(),
        profile: String = DEFAULT_PROFILE,
        timeoutMs: Int = 8_000
    ): TlsResponse = executeAsync(
        TlsRequest(
            url = url,
            headers = headers + ("Accept" to (headers["Accept"]
                ?: "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")),
            clientProfile = profile,
            timeoutMs = timeoutMs.coerceIn(1_000, 60_000)
        )
    )

    /** A local diagnostic for callers to verify library loading and JNI/Go response handling. */
    suspend fun selfTest(url: String): TlsResponse = fetchHtml(url, timeoutMs = 8_000)

    fun execute(request: TlsRequest): TlsResponse {
        val parsedUrl = runCatching { URI(request.url) }.getOrNull()
        if (parsedUrl == null || parsedUrl.scheme?.lowercase() !in setOf("http", "https") || parsedUrl.host.isNullOrBlank()) {
            return TlsResponse(error = "url must be a valid absolute HTTP or HTTPS URL")
        }
        if (!isAvailable) {
            return TlsResponse(error = "native tls-client unavailable: ${unavailableReason ?: "library not loaded"}")
        }

        return try {
            val requestJson = json.encodeToString(request)
            val responseJson = requestNative(requestJson)
            json.decodeFromString(responseJson)
        } catch (e: Throwable) {
            TlsResponse(error = "native tls-client execution failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
