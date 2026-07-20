package com.aggregatorx.app.engine.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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

    fun execute(request: TlsRequest): TlsResponse {
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
