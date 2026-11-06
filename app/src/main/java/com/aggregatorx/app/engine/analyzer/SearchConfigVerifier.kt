package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.ScrapingConfig
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
import com.aggregatorx.app.engine.network.TlsResponse
import com.aggregatorx.app.engine.scraper.SearchResultFilter
import com.aggregatorx.app.engine.util.EngineUtils
import com.aggregatorx.app.engine.validator.ProviderConfigValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Live verification for candidate scraping configs — the gate a config
 * must pass before it is saved for a new provider.
 *
 * A config is only trusted when the site's OWN search proves it:
 *
 *  1. A real probe query (a content term taken from the site's own
 *     inventory by [SiteReconEngine]) is run through the candidate's
 *     search route. What comes back is extracted with the candidate's
 *     selectors (or the JSON field paths it derived) and classified:
 *     item/detail results vs category/listing links. Categories
 *     dominating — or too few items — means the route or selectors are
 *     wrong, not that the site has nothing.
 *  2. When the candidate's selectors extract nothing from the live
 *     probe page, one repair is attempted: selectors re-derived from
 *     that very page (via [ProviderConfigValidator.suggestSelectors]),
 *     plus a JSON-LD pass (the runtime engine reads JSON-LD too, so a
 *     page whose results live in embedded JSON still verifies).
 *  3. The negative control: the same route is run with the nonsense
 *     query [NONSENSE_QUERY]. A route that "finds" items for nonsense
 *     is not exercising the site's search at all — it is ignored-query
 *     junk (a homepage or listing in disguise) and is rejected.
 *
 * The first candidate that passes wins. If none do, the caller gets
 * every candidate's specific failure reason — never a silent save of
 * an unverified config, and never a silent drop either.
 *
 * Everything is bounded: each probe fetch has a short timeout and the
 * whole verification has a total budget. [verify] never throws.
 */
@Singleton
class SearchConfigVerifier @Inject constructor() {

    suspend fun verify(
        provider: Provider,
        candidates: List<VerifierCandidate>,
        probeQuery: String?
    ): VerificationOutcome = withContext(Dispatchers.IO) {
        if (probeQuery.isNullOrBlank()) {
            return@withContext VerificationOutcome.Rejected(
                listOf("No probe term could be derived from the site's own pages, so its search could not be verified")
            )
        }
        if (candidates.isEmpty()) {
            return@withContext VerificationOutcome.Rejected(
                listOf("No candidate search route could be derived from the site's own data (no search form, sitemap route, or live endpoint found)")
            )
        }

        val reasons = mutableListOf<String>()
        val startedAt = System.currentTimeMillis()
        for ((index, candidate) in candidates.withIndex()) {
            if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MS) {
                for (remaining in candidates.drop(index)) {
                    reasons += "${remaining.sourceLabel}: verification time budget exhausted before this route could be probed"
                }
                break
            }
            val attempt = runCatching { verifyCandidate(provider, candidate, probeQuery) }
            when (val result = attempt.getOrNull()) {
                is CandidateAttempt.Passed -> return@withContext VerificationOutcome.Verified(
                    config = result.config,
                    verdict = result.verdict,
                    repaired = result.repaired
                )
                is CandidateAttempt.Failed -> reasons += result.reason
                null -> {
                    val e = attempt.exceptionOrNull()
                    reasons += "${candidate.sourceLabel}: verification error (${e?.javaClass?.simpleName ?: "unknown"}: ${e?.message ?: "no detail"})"
                }
            }
        }
        VerificationOutcome.Rejected(reasons)
    }

    private sealed class CandidateAttempt {
        data class Passed(val config: ScrapingConfig, val verdict: ProbeVerdict, val repaired: Boolean) : CandidateAttempt()
        data class Failed(val reason: String) : CandidateAttempt()
    }

    private fun verifyCandidate(
        provider: Provider,
        candidate: VerifierCandidate,
        probeQuery: String
    ): CandidateAttempt {
        val label = candidate.sourceLabel
        val config = candidate.config

        val probe = buildRequest(provider, config, probeQuery)
        val t0 = System.currentTimeMillis()
        val response = TlsClient.execute(probe)
        val responseMs = System.currentTimeMillis() - t0
        if (response.error != null || response.statusCode !in 200..399) {
            return CandidateAttempt.Failed(
                "$label: probe search failed (${response.error ?: "HTTP ${response.statusCode}"})"
            )
        }

        val extraction = extractPairs(response, provider, config, allowRepair = true)
        val (items, categories) = classify(extraction.pairs)
        val effectiveConfig = extraction.effectiveConfig

        if (items < MIN_ITEMS) {
            return CandidateAttempt.Failed(
                if (categories > 0 && categories >= items) {
                    "$label: probe results were mostly category/listing links ($categories categories vs $items items) — this route does not lead to results"
                } else {
                    "$label: probe search returned only $items item results (need at least $MIN_ITEMS) — the route or its selectors do not match the site's results page"
                }
            )
        }
        if (items <= categories) {
            return CandidateAttempt.Failed(
                "$label: probe results were mostly category/listing links ($categories categories vs $items items) — this route does not lead to results"
            )
        }

        // Negative control: the site's own search must find ~nothing for nonsense.
        val nonsenseResponse = TlsClient.execute(buildRequest(provider, effectiveConfig, NONSENSE_QUERY))
        if (nonsenseResponse.error != null || nonsenseResponse.statusCode !in 200..399) {
            return CandidateAttempt.Failed(
                "$label: nonsense control could not be completed (${nonsenseResponse.error ?: "HTTP ${nonsenseResponse.statusCode}"}) — the route did not survive verification"
            )
        }
        val nonsenseExtraction = extractPairs(nonsenseResponse, provider, effectiveConfig, allowRepair = false)
        val (nonsenseItems, _) = classify(nonsenseExtraction.pairs)
        val nonsensePass = nonsenseItems == 0 || (nonsenseItems <= 1 && nonsenseItems < items)
        if (!nonsensePass) {
            return CandidateAttempt.Failed(
                "$label: the site's search ignores the query — a nonsense search returned $nonsenseItems items (real search: $items), so this route is not the site's real search"
            )
        }

        val verdict = ProbeVerdict(
            sourceLabel = label,
            probedUrl = probe.url,
            itemCount = items,
            categoryCount = categories,
            nonsenseItemCount = nonsenseItems,
            responseMs = responseMs,
            passed = true,
            reason = "Verified live: $items item results, $categories category links, " +
                "nonsense control returned $nonsenseItems"
        )
        return CandidateAttempt.Passed(
            config = effectiveConfig,
            verdict = verdict,
            repaired = extraction.repaired
        )
    }

    // ─── Request building ─────────────────────────────────────────────

    private fun buildRequest(provider: Provider, config: ScrapingConfig, query: String): TlsRequest {
        val encoded = URLEncoder.encode(query, "UTF-8")
        fun fill(template: String): String = template
            .replace("{baseUrl}", provider.baseUrl)
            .replace("{query}", encoded)
            .replace("{page}", "1")

        val url = fill(config.searchUrlTemplate)
        val postData = config.postData
        return if (postData != null) {
            TlsRequest(
                url = url,
                method = "POST",
                headers = mapOf(
                    "User-Agent" to EngineUtils.DEFAULT_USER_AGENT,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                body = fill(postData),
                timeoutMs = PROBE_TIMEOUT_MS
            )
        } else {
            TlsRequest(
                url = url,
                headers = mapOf("User-Agent" to EngineUtils.DEFAULT_USER_AGENT),
                timeoutMs = PROBE_TIMEOUT_MS
            )
        }
    }

    // ─── Extraction ───────────────────────────────────────────────────

    private data class Extraction(
        val pairs: List<Pair<String, String>>,
        val effectiveConfig: ScrapingConfig,
        val repaired: Boolean
    )

    private fun extractPairs(
        response: TlsResponse,
        provider: Provider,
        config: ScrapingConfig,
        allowRepair: Boolean
    ): Extraction {
        val body = response.body
        val pageBase = response.finalUrl.ifBlank { provider.baseUrl }
        val trimmed = body.trimStart()

        // JSON route: the payload itself is the results.
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
            if (root != null) {
                return Extraction(extractJsonPairs(root, provider.baseUrl), config, repaired = false)
            }
        }

        val doc = runCatching { Jsoup.parse(body, pageBase) }.getOrNull()
            ?: return Extraction(emptyList(), config, repaired = false)

        val bySelectors = extractHtmlPairs(doc, config.resultSelector, config.titleSelector, config.urlSelector)
        if (bySelectors.isNotEmpty()) return Extraction(bySelectors, config, repaired = false)

        // The runtime reads JSON-LD too — a page whose results live in
        // embedded JSON verifies with the candidate's config as-is.
        val byJsonLd = extractJsonLdPairs(doc, provider.baseUrl)
        if (byJsonLd.isNotEmpty()) return Extraction(byJsonLd, config, repaired = false)

        if (allowRepair) {
            val topSuggestion = runCatching {
                ProviderConfigValidator.suggestSelectors(body, provider.baseUrl)
                    .resultSelectors.firstOrNull()
            }.getOrNull()
            if (topSuggestion != null) {
                val repairedPairs = extractHtmlPairs(
                    doc,
                    topSuggestion.selector,
                    REPAIR_TITLE_SELECTOR,
                    REPAIR_URL_SELECTOR
                )
                if (repairedPairs.isNotEmpty()) {
                    val repairedConfig = config.copy(
                        resultSelector = topSuggestion.selector,
                        titleSelector = REPAIR_TITLE_SELECTOR,
                        urlSelector = REPAIR_URL_SELECTOR
                    )
                    return Extraction(repairedPairs, repairedConfig, repaired = true)
                }
            }
        }
        return Extraction(emptyList(), config, repaired = false)
    }

    private fun extractHtmlPairs(
        doc: org.jsoup.nodes.Document,
        resultSelector: String,
        titleSelector: String,
        urlSelector: String
    ): List<Pair<String, String>> {
        val items = runCatching { doc.select(resultSelector) }.getOrDefault(emptyList())
        val pairs = mutableListOf<Pair<String, String>>()
        for (item in items) {
            val title = usableTitle(item, titleSelector) ?: continue
            val url = usableUrl(item, urlSelector) ?: continue
            pairs += title to url
        }
        return pairs.distinctBy { it.second }
    }

    private fun usableTitle(item: Element, titleSelector: String): String? {
        val candidates = mutableListOf<Element>()
        runCatching { item.select(titleSelector) }.getOrDefault(emptyList()).let { candidates += it }
        if (candidates.isEmpty()) {
            runCatching { item.select(TITLE_FALLBACK_SELECTOR) }.getOrDefault(emptyList()).let { candidates += it }
        }
        for (element in candidates) {
            for (raw in listOf(
                element.attr("data-title"),
                element.attr("title"),
                element.attr("aria-label"),
                element.attr("alt"),
                element.text()
            )) {
                val value = raw.replace(Regex("\\s+"), " ").trim()
                if (value.length >= 3 &&
                    !DURATION_REGEX.matches(value) &&
                    !MINUTES_REGEX.matches(value)
                ) {
                    return value
                }
            }
        }
        return null
    }

    private fun usableUrl(item: Element, urlSelector: String): String? {
        val candidates = mutableListOf<Element>()
        runCatching { item.select(urlSelector) }.getOrDefault(emptyList()).let { candidates += it }
        if (candidates.isEmpty() || candidates.none { it.hasAttr("href") }) {
            runCatching { item.select("a[href]") }.getOrDefault(emptyList()).let { candidates += it }
        }
        for (element in candidates) {
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            if (href.isNotBlank() &&
                !href.startsWith("javascript:", ignoreCase = true) &&
                !href.startsWith("mailto:", ignoreCase = true)
            ) {
                return href
            }
        }
        return null
    }

    // ─── JSON extraction (mirrors ScrapingEngine's key conventions) ───

    private fun extractJsonPairs(root: JsonElement, baseUrl: String): List<Pair<String, String>> {
        val objects = mutableListOf<JsonObject>()
        fun walk(element: JsonElement, depth: Int) {
            if (depth > 8 || objects.size >= 500) return
            when (element) {
                is JsonObject -> {
                    objects += element
                    element.values.forEach { walk(it, depth + 1) }
                }
                is JsonArray -> element.forEach { walk(it, depth + 1) }
                else -> Unit
            }
        }
        walk(root, 0)

        val pairs = mutableListOf<Pair<String, String>>()
        for (obj in objects) {
            val title = obj.elementFor(TITLE_KEYS).stringValue()?.trim().orEmpty()
            val rawUrl = obj.elementFor(URL_KEYS).stringValue()?.trim().orEmpty()
            if (title.length < 3 || rawUrl.isBlank()) continue
            val url = EngineUtils.normalizeUrl(rawUrl, baseUrl)
            if (!url.startsWith("http")) continue
            pairs += title to url
        }
        return pairs.distinctBy { it.second }
    }

    private fun extractJsonLdPairs(doc: org.jsoup.nodes.Document, baseUrl: String): List<Pair<String, String>> {
        val pairs = mutableListOf<Pair<String, String>>()
        for (script in doc.select("script[type=application/ld+json]")) {
            val root = runCatching { json.parseToJsonElement(script.data()) }.getOrNull() ?: continue
            // JSON-LD uses name/url keys; the shared walker covers them,
            // including ItemList → itemListElement and @graph nesting.
            pairs += extractJsonPairs(root, baseUrl)
        }
        return pairs.distinctBy { it.second }
    }

    private fun JsonObject.elementFor(keys: List<String>): JsonElement? = entries
        .firstOrNull { entry -> keys.any { it.equals(entry.key, ignoreCase = true) } }
        ?.value

    private fun JsonElement?.stringValue(): String? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonObject -> elementFor(listOf("url", "src", "href"))?.stringValue()
        is JsonArray -> firstOrNull()?.stringValue()
        else -> null
    }

    // ─── Classification: items vs category/listing links ──────────────

    private fun classify(pairs: List<Pair<String, String>>): Pair<Int, Int> {
        var categories = 0
        for ((title, url) in pairs) {
            if (SearchResultFilter.isLikelyCategory(title) ||
                SearchResultFilter.isLikelyDuration(title) ||
                isCategoryLikeUrl(url)
            ) {
                categories++
            }
        }
        return (pairs.size - categories) to categories
    }

    private fun isCategoryLikeUrl(url: String): Boolean {
        val path = runCatching { java.net.URL(url).path }.getOrNull() ?: return false
        val segments = path.lowercase().split('/').filter { it.isNotBlank() }
        return segments.any { it in CATEGORY_SEGMENTS }
    }

    companion object {
        const val NONSENSE_QUERY = "zqxv123nonexistent"
        private const val PROBE_TIMEOUT_MS = 8_000
        private const val TOTAL_BUDGET_MS = 40_000L
        private const val MIN_ITEMS = 3

        private const val TITLE_FALLBACK_SELECTOR = "h1, h2, h3, h4, .title, .name, [class*='title'], a"
        private const val REPAIR_TITLE_SELECTOR = "a, h1, h2, h3, h4, .title, [class*='title']"
        private const val REPAIR_URL_SELECTOR = "a[href]"

        private val DURATION_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")
        private val MINUTES_REGEX = Regex("^\\d{1,3}\\s*(min|minutes?|hours?)$", RegexOption.IGNORE_CASE)
        private val CATEGORY_SEGMENTS = setOf("category", "categories", "genre", "genres", "tag", "tags")

        private val TITLE_KEYS = listOf("title", "name", "headline", "label")
        private val URL_KEYS = listOf("url", "link", "href", "permalink", "webpage_url", "canonicalUrl")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/** A candidate config plus where it came from, for failure reporting. */
data class VerifierCandidate(
    val config: ScrapingConfig,
    val sourceLabel: String
)

sealed class VerificationOutcome {
    data class Verified(
        val config: ScrapingConfig,
        val verdict: ProbeVerdict,
        val repaired: Boolean
    ) : VerificationOutcome()

    data class Rejected(
        val reasons: List<String>
    ) : VerificationOutcome()
}

/** The evidence from one candidate's live probe. */
data class ProbeVerdict(
    val sourceLabel: String,
    val probedUrl: String,
    val itemCount: Int,
    val categoryCount: Int,
    val nonsenseItemCount: Int,
    val responseMs: Long,
    val passed: Boolean,
    val reason: String
)
