package com.aggregatorx.app.engine.network

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A safe network fetcher that first attempts to use the native TLS client (if available)
 * and falls back to Jsoup's standard fetch on any failure. This makes network fetching
 * resilient to native library load/runtime failures which previously could cause
 * provider-wide failures.
 */
object NetworkFetcher {
    private const val TAG = "NetworkFetcher"

    data class Outcome(
        val document: Document,
        val body: String,
        val finalUrl: String,
        val statusCode: Int,
        val transport: String
    )

    /**
     * Fetch a URL using native TLS impersonation when allowed, otherwise fall back to Jsoup.
     * allowNative: when true, attempt native TLS if TlsClient.isAvailable. When false, skip native.
     */
    suspend fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int, allowNative: Boolean = true): Outcome? = withContext(Dispatchers.IO) {
        // Try native TLS first (if available and allowed) but do not let it throw.
        if (allowNative) {
            try {
                if (TlsClient.isAvailable) {
                    try {
                        val response = TlsClient.execute(
                            TlsRequest(
                                url = url,
                                headers = headers,
                                clientProfile = TlsClient.DEFAULT_PROFILE,
                                timeoutMs = timeoutMs
                            )
                        )
                        val body = response.body
                        if (response.error.isNullOrBlank() && response.statusCode in 200..399 && !body.isNullOrBlank() && body.length >= 80) {
                            val finalUrl = response.finalUrl.ifBlank { url }
                            Log.i(TAG, "fetch success transport=native-tls url=$url status=${response.statusCode} bytes=${body.length}")
                            return@withContext Outcome(
                                document = Jsoup.parse(body, finalUrl),
                                body = body,
                                finalUrl = finalUrl,
                                statusCode = response.statusCode,
                                transport = "native-tls"
                            )
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "Native TLS fetch failed for $url: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Native TLS fetch pre-check failed for $url: ${t.message}")
            }
        }

        // Fallback to Jsoup standard fetch
        try {
            val response = Jsoup.connect(url)
                .headers(headers)
                .timeout(timeoutMs)
                .followRedirects(true)
                .ignoreContentType(true)
                .ignoreHttpErrors(true)
                .maxBodySize(8 * 1024 * 1024)
                .execute()
            val body = response.body()
            if (response.statusCode() in 200..399 && !body.isNullOrBlank() && body.length >= 80) {
                val finalUrl = response.url().toString()
                Log.i(TAG, "fetch success transport=jsoup url=$url status=${response.statusCode()} bytes=${body.length}")
                return@withContext Outcome(
                    document = Jsoup.parse(body, finalUrl),
                    body = body,
                    finalUrl = finalUrl,
                    statusCode = response.statusCode(),
                    transport = "jsoup"
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Jsoup fetch failed for $url: ${t.message}")
        }

        return@withContext null
    }
}
