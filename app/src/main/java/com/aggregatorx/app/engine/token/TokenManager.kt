package com.aggregatorx.app.engine.token

import android.util.Base64
import android.util.Log
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TokenManager — automated token discovery, mutation, and session replay.
 *
 * Capabilities:
 *  - Base64 / Base44 / URL-encoded token extraction from HTML, JS, and headers
 *  - Session cookie capture and replay
 *  - CSRF token harvesting and injection
 *  - Bearer / API key discovery from JS bundles
 *  - Token mutation fuzzing (increment, bit-flip, padding variants)
 *  - Replay attack: re-use captured tokens across provider requests
 */
@Singleton
class TokenManager @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "TokenManager"

        // Regex patterns for token discovery
        private val BASE64_PATTERN   = Regex("""[A-Za-z0-9+/]{20,}={0,2}""")
        private val BEARER_PATTERN   = Regex("""[Bb]earer\s+([A-Za-z0-9\-._~+/]+=*)""")
        private val API_KEY_PATTERN  = Regex("""(?:api[_\-]?key|apikey|access[_\-]?token|auth[_\-]?token)['":\s=]+([A-Za-z0-9\-._~+/]{16,})""", RegexOption.IGNORE_CASE)
        private val CSRF_PATTERN     = Regex("""(?:csrf[_\-]?token|_token|authenticity_token)['":\s=]+([A-Za-z0-9+/\-_]{8,})""", RegexOption.IGNORE_CASE)
        private val SESSION_PATTERN  = Regex("""(?:session[_\-]?id|sess|PHPSESSID|JSESSIONID)[=:\s'"]+([A-Za-z0-9+/\-_]{8,})""", RegexOption.IGNORE_CASE)
        private val BASE44_CHARS     = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJH"
    }

    // In-memory token store: url-host → TokenBundle
    private val tokenStore = ConcurrentHashMap<String, TokenBundle>()

    // Results discovered via token injection (surfaced in TOKENS quick-tab)
    private val tokenDiscoveredUrls = ConcurrentHashMap<String, MutableList<String>>()

    data class TokenBundle(
        val host: String,
        val bearerTokens: List<String> = emptyList(),
        val apiKeys: List<String> = emptyList(),
        val csrfTokens: List<String> = emptyList(),
        val sessionIds: List<String> = emptyList(),
        val base64Blobs: List<String> = emptyList(),
        val cookies: Map<String, String> = emptyMap(),
        val discoveredAt: Long = System.currentTimeMillis()
    )

    // ── PUBLIC API ────────────────────────────────────────────────────────────

    /** Harvest all tokens from a target URL. Returns the discovered bundle. */
    suspend fun harvestTokens(url: String): TokenBundle = withContext(Dispatchers.IO) {
        val host = extractHost(url)
        try {
            val (html, headers, cookies) = fetchWithMeta(url)
            val bundle = TokenBundle(
                host         = host,
                bearerTokens = BEARER_PATTERN.findAll(html).map { it.groupValues[1] }.toList(),
                apiKeys      = API_KEY_PATTERN.findAll(html).map { it.groupValues[1] }.distinct().toList(),
                csrfTokens   = CSRF_PATTERN.findAll(html).map { it.groupValues[1] }.distinct().toList(),
                sessionIds   = SESSION_PATTERN.findAll(html).map { it.groupValues[1] }.distinct().toList(),
                base64Blobs  = discoverBase64Tokens(html),
                cookies      = cookies
            )
            tokenStore[host] = bundle
            Log.d(TAG, "Harvested ${bundle.apiKeys.size} API keys, ${bundle.csrfTokens.size} CSRF, ${bundle.bearerTokens.size} bearer from $host")
            bundle
        } catch (e: Exception) {
            Log.w(TAG, "Token harvest failed for $url: ${e.message}")
            TokenBundle(host = host)
        }
    }

    /** Replay stored tokens against a search URL, returning any new result URLs found. */
    suspend fun replayTokensForSearch(baseUrl: String, query: String): List<String> = withContext(Dispatchers.IO) {
        val host = extractHost(baseUrl)
        val bundle = tokenStore[host] ?: harvestTokens(baseUrl)
        val discovered = mutableListOf<String>()

        // Build injected search URLs using each token type
        val searchUrls = buildTokenInjectedUrls(baseUrl, query, bundle)

        for (injectedUrl in searchUrls) {
            try {
                val request = Request.Builder()
                    .url(injectedUrl)
                    .header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
                    .apply {
                        bundle.bearerTokens.firstOrNull()?.let { header("Authorization", "Bearer $it") }
                        bundle.csrfTokens.firstOrNull()?.let { header("X-CSRF-Token", it) }
                        bundle.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                            .takeIf { it.isNotEmpty() }?.let { header("Cookie", it) }
                    }
                    .build()

                val response = httpClient.newCall(request).execute()
                val body = response.body?.string() ?: continue
                response.close()

                // Extract result links from the response
                val doc = Jsoup.parse(body, injectedUrl)
                val links = doc.select("a[href]").map { it.absUrl("href") }
                    .filter { it.contains(query.take(6), ignoreCase = true) || it.length > 30 }
                discovered.addAll(links)
            } catch (e: Exception) {
                Log.v(TAG, "Token replay failed for $injectedUrl: ${e.message}")
            }
        }

        tokenDiscoveredUrls.getOrPut(host) { mutableListOf() }.addAll(discovered)
        discovered.distinct()
    }

    /** Mutate a known token (increment, bit-flip, padding) and test each variant. */
    suspend fun fuzzToken(token: String, testUrl: String): List<String> = withContext(Dispatchers.IO) {
        val variants = generateTokenVariants(token)
        val valid = mutableListOf<String>()
        for (variant in variants.take(20)) { // cap at 20 to avoid abuse
            try {
                val req = Request.Builder()
                    .url(testUrl)
                    .header("Authorization", "Bearer $variant")
                    .header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
                    .build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) valid.add(variant)
                resp.close()
            } catch (_: Exception) {}
        }
        valid
    }

    /** Return all URLs discovered via token injection (for TOKENS quick-tab). */
    fun getTokenDiscoveredUrls(host: String): List<String> =
        tokenDiscoveredUrls[host]?.toList() ?: emptyList()

    /** Get cached bundle for a host, or null if not yet harvested. */
    fun getCachedBundle(host: String): TokenBundle? = tokenStore[host]

    // ── INTERNAL HELPERS ──────────────────────────────────────────────────────

    private fun discoverBase64Tokens(html: String): List<String> {
        return BASE64_PATTERN.findAll(html)
            .map { it.value }
            .filter { blob ->
                try {
                    val decoded = Base64.decode(blob, Base64.DEFAULT)
                    decoded.size > 8 && decoded.all { it in 32..126 || it == 0.toByte() }
                } catch (_: Exception) { false }
            }
            .distinct()
            .take(10)
            .toList()
    }

    private fun buildTokenInjectedUrls(baseUrl: String, query: String, bundle: TokenBundle): List<String> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val base = baseUrl.trimEnd('/')
        val urls = mutableListOf<String>()

        // Standard search patterns
        urls += "$base/search?q=$encodedQuery"
        urls += "$base/search?query=$encodedQuery"
        urls += "$base/?s=$encodedQuery"
        urls += "$base/api/search?q=$encodedQuery"
        urls += "$base/api/v1/search?q=$encodedQuery"
        urls += "$base/api/v2/search?q=$encodedQuery"

        // Inject API keys into query params
        bundle.apiKeys.take(3).forEach { key ->
            urls += "$base/search?q=$encodedQuery&api_key=$key"
            urls += "$base/api/search?q=$encodedQuery&token=$key"
        }

        // Base64-encoded query variants
        val b64Query = Base64.encodeToString(query.toByteArray(), Base64.NO_WRAP)
        urls += "$base/search?q=$b64Query"

        // Base44-encoded query
        urls += "$base/search?q=${encodeBase44(query)}"

        return urls.distinct()
    }

    private fun generateTokenVariants(token: String): List<String> {
        val variants = mutableListOf<String>()
        // Decode → mutate → re-encode
        try {
            val decoded = Base64.decode(token, Base64.DEFAULT)
            // Increment last byte
            val inc = decoded.copyOf()
            inc[inc.size - 1] = (inc[inc.size - 1] + 1).toByte()
            variants += Base64.encodeToString(inc, Base64.NO_WRAP)
            // Bit-flip first byte
            val flip = decoded.copyOf()
            flip[0] = (flip[0].toInt() xor 0x01).toByte()
            variants += Base64.encodeToString(flip, Base64.NO_WRAP)
        } catch (_: Exception) {}
        // Padding variants
        variants += "$token="
        variants += "${token}=="
        variants += token.dropLast(1)
        // URL-decoded variant
        try { variants += URLDecoder.decode(token, "UTF-8") } catch (_: Exception) {}
        return variants
    }

    private fun encodeBase44(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        var acc = 0
        var bits = 0
        for (b in bytes) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 6) {
                bits -= 6
                sb.append(BASE44_CHARS[(acc shr bits) and 0x3F])
            }
        }
        if (bits > 0) sb.append(BASE44_CHARS[(acc shl (6 - bits)) and 0x3F])
        return sb.toString()
    }

    private data class FetchResult(val html: String, val headers: Map<String, String>, val cookies: Map<String, String>)

    private fun fetchWithMeta(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", EngineUtils.DEFAULT_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()
        val response = httpClient.newCall(request).execute()
        val html = response.body?.string() ?: ""
        val headers = response.headers.toMap()
        val cookies = response.headers("Set-Cookie")
            .mapNotNull { cookie ->
                val parts = cookie.split(";").firstOrNull()?.split("=", limit = 2)
                if (parts?.size == 2) parts[0].trim() to parts[1].trim() else null
            }.toMap()
        response.close()
        return FetchResult(html, headers, cookies)
    }

    private fun extractHost(url: String): String = try {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.host}"
    } catch (_: Exception) { url }
}
