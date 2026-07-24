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

    suspend fun fetch(url: String, headers: Map<String, String>, timeoutMs: Int): Outcome? = withContext(Dispatchers.IO) {
        // Try native TLS first (if available) but do not let it throw.
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
