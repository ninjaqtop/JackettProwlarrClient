package com.aggregatorx.app.engine.network

import android.util.Log
import kotlinx.serialization.Serializable
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

/**
 * The native TLS engine: Go `libtlsclient.so` (bogdanfinn/tls-client, browser-grade
 * ClientHello) behind the JNI bridge `libtlsclientbridge.so`.
 *
 * Load order matters and is explicit: the Go library is loaded FIRST, then the
 * bridge that depends on it. Both outcomes are recorded so the transport layer
 * and the UI can show exactly which step failed and why.
 *
 * App code must not call [execute] directly for page fetches — go through
 * [HttpTransport], which bounds every native call, applies the per-provider
 * timeout, and owns the (logged, user-visible) degradation to OkHttp.
 */
object TlsClient {
    private const val TAG = "TlsClient"
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

    /** Result of loading the Go library (step 1). Null = loaded. */
    val goLibraryLoadError: Throwable? = runCatching {
        System.loadLibrary("tlsclient")
    }.exceptionOrNull()

    /** Result of loading the JNI bridge (step 2). Null = loaded. */
    val bridgeLoadError: Throwable? = runCatching {
        System.loadLibrary("tlsclientbridge")
    }.exceptionOrNull()

    init {
        if (goLibraryLoadError != null || bridgeLoadError != null) {
            Log.e(TAG, "Native TLS client unavailable: $unavailableReasonInternal")
        } else {
            Log.i(TAG, "Native TLS client loaded (libtlsclient.so, then libtlsclientbridge.so)")
        }
    }

    /**
     * Set when a native call itself failed to bind (UnsatisfiedLinkError) even though
     * both libraries loaded — e.g. a JNI symbol mismatch. Once set, the engine is
     * reported unavailable instead of failing on every request.
     */
    @Volatile
    private var bindError: Throwable? = null

    val isAvailable: Boolean
        get() = goLibraryLoadError == null && bridgeLoadError == null && bindError == null

    private val unavailableReasonInternal: String
        get() = when {
            goLibraryLoadError != null ->
                "libtlsclient.so failed to load: ${goLibraryLoadError.message ?: goLibraryLoadError.javaClass.simpleName}"
            bridgeLoadError != null ->
                "libtlsclientbridge.so failed to load: ${bridgeLoadError.message ?: bridgeLoadError.javaClass.simpleName}"
            bindError != null ->
                "JNI bind failed: ${bindError?.message ?: bindError?.javaClass?.simpleName}"
            else -> ""
        }

    val unavailableReason: String?
        get() = unavailableReasonInternal.ifBlank { null }

    /**
     * JNI entry point implemented in app/src/main/cpp/native-lib.cpp as
     * `Java_com_aggregatorx_app_engine_network_TlsClient_requestNative` (instance
     * method on this object, so the native side receives the singleton as `jobject`).
     * The bridge is compiled from source by CMake for this exact class; CI checks the
     * exported symbol name against this package (scripts/native_pair_check.py).
     */
    private external fun requestNative(json: String): String

    /**
     * Blocking native request. Callers must run this off the main thread and bound
     * it — [HttpTransport] does both.
     */
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
            json.decodeFromString<TlsResponse>(responseJson)
        } catch (e: UnsatisfiedLinkError) {
            bindError = e
            Log.e(TAG, "Native TLS JNI bind failed", e)
            TlsResponse(error = "native tls-client unavailable: JNI bind failed: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Throwable) {
            TlsResponse(error = "native tls-client execution failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }
}
