package com.aggregatorx.app.engine.validator

import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.ProviderCategory
import com.aggregatorx.app.data.model.ScrapingConfig
import com.aggregatorx.app.data.model.ScrapingStrategy
import com.aggregatorx.app.data.model.SiteAnalysis
import com.aggregatorx.app.engine.network.TlsClient
import com.aggregatorx.app.engine.network.TlsRequest
import com.aggregatorx.app.engine.scraper.SearchResultFilter
import com.aggregatorx.app.engine.util.EngineUtils
import java.net.URLEncoder
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

object ProviderConfigValidator {

    fun validateProvider(provider: Provider): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (provider.name.isBlank()) {
            errors.add("Provider name cannot be empty")
        }
        if (provider.url.isBlank()) {
            errors.add("Provider URL cannot be empty")
        } else {
            val normalizedUrl = EngineUtils.normalizeUrl(provider.url, "")
            if (!normalizedUrl.startsWith("http")) {
                errors.add("Provider URL must start with http:// or https://")
            }
            if (EngineUtils.extractDomain(normalizedUrl).isBlank()) {
                errors.add("Could not extract domain from URL")
            }
        }
        if (provider.baseUrl.isBlank()) {
            warnings.add("Base URL is empty, will use URL instead")
        }
        if (provider.category == ProviderCategory.GENERAL) {
            warnings.add("Category is set to GENERAL - consider setting a specific category for better results")
        }
        if (provider.avgResponseTime < 0) {
            warnings.add("Average response time cannot be negative")
        }
        if (provider.successRate < 0.0f || provider.successRate > 1.0f) {
            warnings.add("Success rate must be between 0 and 1")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            score = calculateProviderScore(provider, errors, warnings)
        )
    }

    fun validateScrapingConfig(config: ScrapingConfig, providerUrl: String, sampleHtml: String? = null): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (config.resultSelector.isBlank()) {
            errors.add("Result selector cannot be empty")
        } else if (!isValidCssSelector(config.resultSelector)) {
            warnings.add("Result selector may have syntax issues: " + config.resultSelector)
        }
        if (config.titleSelector.isBlank()) {
            warnings.add("Title selector is empty - title extraction may fail")
        } else if (!isValidCssSelector(config.titleSelector)) {
            warnings.add("Title selector may have syntax issues: " + config.titleSelector)
        }
        if (config.urlSelector.isBlank()) {
            errors.add("URL selector cannot be empty")
        } else if (!isValidCssSelector(config.urlSelector)) {
            warnings.add("URL selector may have syntax issues: " + config.urlSelector)
        }
        if (config.searchUrlTemplate.isBlank()) {
            errors.add("Search URL template cannot be empty")
        } else if (!config.searchUrlTemplate.contains("{query}")) {
            warnings.add("Search URL template should contain a {query} placeholder")
        }

        if (!sampleHtml.isNullOrBlank()) {
            val document = runCatching { Jsoup.parse(sampleHtml, providerUrl) }.getOrNull()
            if (document != null) {
                val items = runCatching { document.select(config.resultSelector) }.getOrDefault(emptyList())
                if (items.isEmpty()) {
                    warnings.add("Result selector did not match the analyzed page; verify it on a live search page")
                } else {
                    val usableCount = items.count { item ->
                        val hasTitle = runCatching { item.select(config.titleSelector) }
                            .getOrDefault(emptyList())
                            .any { element ->
                                listOf(
                                    element.attr("data-title"),
                                    element.attr("title"),
                                    element.attr("aria-label"),
                                    element.attr("alt"),
                                    element.text()
                                ).any { raw ->
                                    val value = raw.trim()
                                    value.length >= 3 &&
                                        !Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(value) &&
                                        !Regex("^\\d{1,3}\\s*(min|minutes?|hours?)$", RegexOption.IGNORE_CASE).matches(value)
                                }
                            }
                        val hasLink = runCatching { item.select(config.urlSelector) }
                            .getOrDefault(emptyList())
                            .any { element ->
                                val href = element.absUrl("href").ifBlank { element.attr("href") }
                                href.isNotBlank() && !href.startsWith("javascript:", ignoreCase = true)
                            }
                        hasTitle && hasLink
                    }
                    if (usableCount == 0) {
                        warnings.add("The selectors did not map sample items to usable titles and links")
                    } else if (usableCount < items.size) {
                        warnings.add("Some sample items are missing a usable title or link")
                    }
                }
            }
        }

        val validEncodings = listOf("UTF-8", "UTF-16", "ISO-8859-1", "windows-1252")
        if (config.encoding.isNotBlank() && config.encoding !in validEncodings) {
            warnings.add("Unrecognized encoding: " + config.encoding)
        }
        if (config.timeout <= 0) {
            warnings.add("Timeout should be positive")
        } else if (config.timeout > 60000) {
            warnings.add("Timeout is very high (" + config.timeout + "ms) - may cause delays")
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            score = calculateConfigScore(config, errors, warnings)
        )
    }

    fun validateSiteAnalysis(analysis: SiteAnalysis): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (analysis.url.isBlank()) {
            errors.add("Analysis URL cannot be empty")
        }
        if (analysis.providerId.isBlank()) {
            errors.add("Provider ID cannot be empty")
        }
        if (analysis.resultItemSelector.isNullOrBlank()) {
            warnings.add("No result item selector detected - may need manual configuration")
        }
        if (analysis.searchFormSelector.isNullOrBlank()) {
            warnings.add("No search form detected - may need custom search URL")
        }
        if (analysis.requiresJavaScript &&
            analysis.scrapingStrategy != ScrapingStrategy.DYNAMIC_CONTENT &&
            analysis.scrapingStrategy != ScrapingStrategy.HEADLESS_BROWSER
        ) {
            warnings.add("Site requires JavaScript but strategy is set to " + analysis.scrapingStrategy)
        }

        return ValidationResult(
            isValid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
            score = calculateAnalysisScore(analysis, errors, warnings)
        )
    }

    suspend fun testProviderUrl(provider: Provider, timeout: Int = 5000): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val url = provider.url.ifBlank { provider.baseUrl }
            if (EngineUtils.extractDomain(url).isBlank()) {
                return TestResult(success = false, message = "Invalid URL", responseTime = 0L)
            }
            val response = TlsClient.execute(
                TlsRequest(
                    url = url,
                    headers = mapOf("User-Agent" to EngineUtils.DEFAULT_USER_AGENT),
                    timeoutMs = timeout
                )
            )
            val responseTime = System.currentTimeMillis() - startTime
            if (response.error.isNullOrBlank() && response.statusCode in 200 until 400) {
                val contentType = response.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                TestResult(
                    success = true,
                    message = "URL is reachable",
                    responseTime = responseTime,
                    statusCode = response.statusCode,
                    contentType = contentType,
                    finalUrl = response.finalUrl
                )
            } else {
                TestResult(
                    success = false,
                    message = response.error ?: "HTTP " + response.statusCode,
                    responseTime = responseTime,
                    statusCode = response.statusCode,
                    finalUrl = response.finalUrl
                )
            }
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = e.message ?: e.javaClass.simpleName,
                responseTime = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    suspend fun testProviderSearch(provider: Provider, config: ScrapingConfig?, testQuery: String, timeout: Int): TestResult {
        val startTime = System.currentTimeMillis()
        return try {
            val baseUrl = provider.baseUrl.ifBlank { provider.url }
            val template = config?.searchUrlTemplate
            val searchUrl = if (template.isNullOrBlank()) {
                baseUrl.trimEnd('/') + "/search?q=" + URLEncoder.encode(testQuery, "UTF-8")
            } else {
                template
                    .replace("{baseUrl}", baseUrl)
                    .replace("{query}", URLEncoder.encode(testQuery, "UTF-8"))
            }
            val response = TlsClient.execute(
                TlsRequest(
                    url = searchUrl,
                    headers = mapOf("User-Agent" to EngineUtils.DEFAULT_USER_AGENT),
                    timeoutMs = timeout
                )
            )
            val responseTime = System.currentTimeMillis() - startTime
            if (response.error.isNullOrBlank() && response.statusCode in 200 until 400) {
                val message = if (
                    response.body.contains("result", ignoreCase = true) ||
                    response.body.contains("video", ignoreCase = true) ||
                    response.body.contains("movie", ignoreCase = true)
                ) {
                    "Search works and returns results"
                } else {
                    "Search works but no results detected"
                }
                val contentType = response.headers.entries
                    .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                    ?.value
                    ?.firstOrNull()
                TestResult(
                    success = true,
                    message = message,
                    responseTime = responseTime,
                    statusCode = response.statusCode,
                    contentType = contentType,
                    finalUrl = response.finalUrl,
                    bodySize = response.body.length
                )
            } else {
                TestResult(
                    success = false,
                    message = response.error ?: "HTTP " + response.statusCode,
                    responseTime = responseTime,
                    statusCode = response.statusCode
                )
            }
        } catch (e: Exception) {
            TestResult(
                success = false,
                message = e.message ?: e.javaClass.simpleName,
                responseTime = System.currentTimeMillis() - startTime,
                error = e
            )
        }
    }

    fun suggestSelectors(html: String, baseUrl: String): SelectorSuggestions {
        return try {
            val document = Jsoup.parse(html, baseUrl)
            val resultSuggestions = mutableListOf<SelectorSuggestion>()
            val titleSuggestions = mutableListOf<SelectorSuggestion>()
            val urlSuggestions = mutableListOf<SelectorSuggestion>()

            val resultSelectors = listOf(
                ".result", ".item", ".card", ".video-item", ".movie-item", ".media-item",
                ".post", ".entry", "article", ".content-item", ".grid-item",
                "[class*='result']", "[class*='item']", "[class*='card']", "li",
                "div[role='article']", "div[role='listitem']"
            )
            for (selector in resultSelectors) {
                val elements = runCatching { document.select(selector) }.getOrDefault(emptyList())
                val count = elements.count { element ->
                    hasUsableTitle(element) && hasUsableLink(element)
                }
                if (count >= 2) {
                    resultSuggestions.add(SelectorSuggestion(selector, count))
                }
            }

            val titleSelectors = listOf(
                "h1", "h2", "h3", "h4", ".title", ".name", ".headline",
                "[class*='title']", "[class*='name']", "a", "span"
            )
            for (selector in titleSelectors) {
                val elements = runCatching { document.select(selector) }.getOrDefault(emptyList())
                val count = elements.count { element ->
                    listOf(
                        element.attr("data-title"),
                        element.attr("title"),
                        element.attr("aria-label"),
                        element.attr("alt"),
                        element.text()
                    ).any { isUsableTitle(it) }
                }
                if (count > 0) {
                    titleSuggestions.add(SelectorSuggestion(selector, count))
                }
            }

            val urlSelectors = listOf("a[href]", "[href]", "a", "link")
            for (selector in urlSelectors) {
                val elements = runCatching { document.select(selector) }.getOrDefault(emptyList())
                // PORTING-UNCERTAIN: jadx rendered this count loop with duplicated
                // increment blocks ("Code duplicated" warnings in the decompile), so
                // the exact original predicate could not be read cleanly. The only
                // scheme check visible in the bytecode for this loop is the
                // "javascript:" exclusion; hasUsableLink() (used above) additionally
                // excludes "mailto:", which could not be confirmed for this loop.
                val count = elements.count { element ->
                    val href = element.absUrl("href").ifBlank { element.attr("href") }
                    href.isNotBlank() && !href.startsWith("javascript:", ignoreCase = true)
                }
                if (count > 0) {
                    urlSuggestions.add(SelectorSuggestion(selector, count))
                }
            }

            SelectorSuggestions(
                resultSelectors = resultSuggestions.sortedByDescending { it.count }.take(10),
                titleSelectors = titleSuggestions.sortedByDescending { it.count }.take(10),
                urlSelectors = urlSuggestions.sortedByDescending { it.count }.take(10)
            )
        } catch (e: Exception) {
            SelectorSuggestions(emptyList(), emptyList(), emptyList())
        }
    }

    private fun hasUsableTitle(item: Element): Boolean {
        val elements = runCatching {
            item.select("h1, h2, h3, h4, .title, .name, [class*='title'], [itemprop=name], [data-title], a[title], a")
        }.getOrDefault(emptyList())
        return elements.any { element ->
            listOf(
                element.attr("data-title"),
                element.attr("title"),
                element.attr("aria-label"),
                element.attr("alt"),
                element.text()
            ).any { isUsableTitle(it) }
        }
    }

    private fun hasUsableLink(item: Element): Boolean {
        val links = runCatching { item.select("a[href]") }.getOrDefault(emptyList())
        return links.any { element ->
            val href = element.absUrl("href").ifBlank { element.attr("href") }
            href.isNotBlank() &&
                !href.startsWith("javascript:", ignoreCase = true) &&
                !href.startsWith("mailto:", ignoreCase = true)
        }
    }

    private fun isUsableTitle(value: String): Boolean {
        val normalized = Regex("\\s+").replace(value, " ").trim()
        return normalized.length >= 3 &&
            !SearchResultFilter.isLikelyCategory(normalized) &&
            !SearchResultFilter.isLikelyDuration(normalized)
    }

    private fun isValidCssSelector(selector: String): Boolean {
        if (selector.isBlank()) return false
        return runCatching { Jsoup.parse("<html></html>").select(selector) }.isSuccess
    }

    private fun calculateProviderScore(provider: Provider, errors: List<String>, warnings: List<String>): Float {
        var score = 100.0f - (errors.size * 20.0f) - (warnings.size * 5.0f)
        if (provider.baseUrl.isNotBlank()) {
            score += 5.0f
        }
        if (provider.category != ProviderCategory.GENERAL) {
            score += 5.0f
        }
        if (provider.successRate >= 0.8f) {
            score += 10.0f
        }
        return score.coerceIn(0.0f, 100.0f)
    }

    private fun calculateConfigScore(config: ScrapingConfig, errors: List<String>, warnings: List<String>): Float {
        var score = 100.0f - (errors.size * 25.0f) - (warnings.size * 5.0f)
        if (config.resultSelector.isNotBlank() && config.urlSelector.isNotBlank()) {
            score += 10.0f
        }
        if (!config.titleSelector.isNullOrBlank()) {
            score += 5.0f
        }
        if (!config.thumbnailSelector.isNullOrBlank()) {
            score += 5.0f
        }
        return score.coerceIn(0.0f, 100.0f)
    }

    private fun calculateAnalysisScore(analysis: SiteAnalysis, errors: List<String>, warnings: List<String>): Float {
        var score = 100.0f - (errors.size * 25.0f) - (warnings.size * 5.0f)
        if (!analysis.resultItemSelector.isNullOrBlank()) {
            score += 15.0f
        }
        if (!analysis.searchFormSelector.isNullOrBlank()) {
            score += 10.0f
        }
        if (!analysis.titleSelector.isNullOrBlank()) {
            score += 5.0f
        }
        if (analysis.hasSSL) {
            score += 5.0f
        }
        return score.coerceIn(0.0f, 100.0f)
    }

    data class ValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>,
        val score: Float,
        val message: String = if (isValid) "Valid" else "Invalid: " + errors.joinToString(", ")
    )

    data class TestResult(
        val success: Boolean,
        val message: String,
        val responseTime: Long,
        val statusCode: Int? = null,
        val contentType: String? = null,
        val finalUrl: String? = null,
        val bodySize: Int = 0,
        val error: Exception? = null
    )

    data class SelectorSuggestion(
        val selector: String,
        val count: Int
    )

    data class SelectorSuggestions(
        val resultSelectors: List<SelectorSuggestion>,
        val titleSelectors: List<SelectorSuggestion>,
        val urlSelectors: List<SelectorSuggestion>
    )
}
