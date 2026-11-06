package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.engine.network.HttpTransport

import com.aggregatorx.app.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Smart Navigation Engine v2
 *
 * Intelligently navigates ANY website structure:
 * - Detects and uses search when available
 * - Crawls tabs/categories/genres when search is absent
 * - Handles popups, overlays, cookie consents
 * - Navigates pagination for more results
 * - Handles dynamic content detection
 * - Keyword-based tab matching for non-search sites
 * - Falls back to homepage scraping as last resort
 */
@Singleton
class SmartNavigationEngine @Inject constructor() {

    companion object {
        // Increased timeouts so that slow-but-valid sites (7-15 s response) succeed.
        // Pre-4.0 regression: QUICK_TIMEOUT was 6 000 ms which caused reliable providers to
        // consistently time out before returning a response, triggering the cooldown path.
        private const val DEFAULT_TIMEOUT = 30000   // generous timeout for slow sites
        private const val QUICK_TIMEOUT  = 20000   // enough for sites that take 7-15s to respond
        // Number of URL patterns checked in parallel per batch
        private const val CONCURRENT_PATTERN_CHECKS = 18
        private val DEFAULT_USER_AGENT = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT

        // Extended search URL patterns (most-to-least common)
        private val SEARCH_URL_PATTERNS = listOf(
            // Standard query-param patterns
            "{base}/search?q={query}",
            "{base}/?s={query}",
            "{base}/search?query={query}",
            "{base}/?q={query}",
            "{base}/search?s={query}",
            "{base}/search?keyword={query}",
            "{base}/search?keywords={query}",
            "{base}/search?term={query}",
            "{base}/search?text={query}",
            "{base}/?search_query={query}",
            "{base}/results?q={query}",
            "{base}/results?search_query={query}",
            "{base}/videos?search={query}",
            "{base}/videos?q={query}",
            "{base}/movies?search={query}",
            "{base}/movies?q={query}",
            "{base}/search.php?q={query}",
            "{base}/search.php?keyword={query}",
            "{base}/search.html?q={query}",
            "{base}/find?q={query}",
            "{base}/find?query={query}",
            "{base}/index.php?s={query}",
            "{base}/index.php?q={query}",
            "{base}/?search={query}",
            "{base}/search?name={query}",
            "{base}/search?title={query}",
            "{base}/search?cat=0&search={query}",
            "{base}/torrents?search={query}",
            "{base}/torrents.php?search={query}",
            "{base}/browse.php?search={query}",
            "{base}/torrent-search?q={query}",
            // API / WordPress REST
            "{base}/api/search?q={query}",
            "{base}/wp-json/wp/v2/search?search={query}",
            "{base}/wp-json/wp/v2/posts?search={query}",
            "{base}/wp-json/wp/v2/media?search={query}",
            "{base}/api/v1/search?q={query}",
            "{base}/api/v2/search?q={query}",
            "{base}/api/v3/search?q={query}",
            // Ghost CMS
            "{base}/ghost/api/v4/content/posts/?filter=title:~%27{query}%27&key=",
            "{base}/ghost/api/content/posts/?filter=title:~%27{query}%27",
            // Strapi CMS (v4 & v5)
            "{base}/api/articles?filters[title][\$containsi]={query}",
            "{base}/api/posts?filters[title][\$containsi]={query}",
            "{base}/api/videos?filters[title][\$containsi]={query}",
            "{base}/api/movies?filters[title][\$containsi]={query}",
            // Directus CMS
            "{base}/items/articles?filter[title][_contains]={query}",
            "{base}/items/posts?filter[title][_contains]={query}",
            "{base}/items/content?filter[title][_contains]={query}",
            // Contentful
            "{base}?content_type=blogPost&fields.title[match]={query}",
            // Generic REST JSON APIs
            "{base}/api/search?keyword={query}",
            "{base}/api/search?title={query}",
            "{base}/api/videos/search?q={query}",
            "{base}/api/movies/search?q={query}",
            // Slug / path-segment patterns
            "{base}/search/{query}",
            "{base}/search/{query_slug}",
            "{base}/search/videos/{query}",
            "{base}/search/movies/{query}",
            "{base}/s/{query}",
            "{base}/q/{query}",
            "{base}/find/{query}",
            // Torrent indexer specific
            "{base}/browse?search={query}&cat=0",
            "{base}/s/?q={query}&cat=0",
            "{base}/search?what={query}",
            "{base}/ajax/movies/search?content={query}",
            "{base}/suggest?q={query}",
            // Localised patterns
            "{base}/buscar?q={query}",
            "{base}/recherche?q={query}",
            "{base}/suche?q={query}",
            "{base}/zoeken?q={query}",
            "{base}/cerca?q={query}",
            "{base}/pesquisar?q={query}"
        )

        // Category page indicators to detect and bypass
        private val CATEGORY_INDICATORS = listOf(
            "/category/", "/categories/", "/cat/",
            "/genre/", "/genres/", "/tag/", "/tags/",
            "/type/", "/types/", "/browse/", "/explore/",
            "/popular", "/trending", "/latest", "/new",
            "/top-rated", "/featured", "/recommended"
        )

        // Selectors that indicate tab/category navigation bars
        private val TAB_NAVIGATION_SELECTORS = listOf(
            ".tabs a", ".tab a", "nav.tabs a", "[role='tablist'] [role='tab']",
            ".nav-tabs a", ".menu-tabs a", ".category-tabs a",
            ".genre-tabs a", ".filter-tabs a", ".section-tabs a",
            "[class*='tab'] a[href]", "ul.tabs > li > a",
            ".categories a", ".category-list a", ".genres a",
            ".genre-list a", ".nav-categories a",
            "nav a[href]", ".sidebar a[href]", ".filters a[href]",
            ".breadcrumb a", ".menu a[href]",
            "ul.menu > li > a", ".navigation a[href]"
        )

        // Popup/overlay selectors to dismiss
        private val POPUP_SELECTORS = listOf(
            ".popup-close", ".modal-close", ".close-button",
            "[class*='popup'] .close", "[class*='modal'] .close",
            ".overlay-close", "#close-popup", "#close-modal",
            "[data-dismiss='modal']", ".cookie-close",
            ".ad-close", ".notification-close", ".banner-close",
            "button[aria-label='Close']", "button[aria-label='Dismiss']",
            ".adblock-close", ".adblocker-close", ".skip-ad", ".skipAds",
            ".ad-skip", ".ad_skip_btn", ".ad-overlay-close", ".ad-popup-close",
            ".interstitial-close", ".promo-close", ".splash-close",
            ".fullscreen-ad-close", ".ad-modal-close", ".ad-dismiss", ".ad-exit",
            "button.skip", "button[title*='Skip']", "button[title*='Dismiss']",
            "button[title*='Close']"
        )

    /**
     * Attempt to close popups/ads with retries and escalation
     */
    suspend fun closePopupsWithRetries(page: Document, maxRetries: Int = 3): Boolean {
        var closedAny = false
        repeat(maxRetries) {
            var closedThisRound = false
            for (selector in POPUP_SELECTORS) {
                val elements = page.select(selector)
                if (elements.isNotEmpty()) {
                    elements.forEach { it.remove() }
                    closedThisRound = true
                }
            }
            if (closedThisRound) closedAny = true
            if (!closedThisRound) return@repeat
        }
        return closedAny
    }
    }
    
    /**
     * Find the best search URL for a site
     */
    suspend fun findSearchUrl(baseUrl: String, query: String): String? = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val slugQuery = query.trim().lowercase().replace(Regex("\\s+"), "-")
        val plusQuery = query.trim().replace(Regex("\\s+"), "+")

        // Step 1: Detect a search form on the homepage (single fast request)
        try {
            val homepage = HttpTransport.connect(baseUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(QUICK_TIMEOUT)
                .get()
            val searchForm = findSearchForm(homepage)
            if (searchForm != null) {
                val builtUrl = buildSearchUrlFromForm(baseUrl, searchForm, query)
                // Validate it returns results
                try {
                    val doc = HttpTransport.connect(builtUrl)
                        .userAgent(DEFAULT_USER_AGENT)
                        .timeout(QUICK_TIMEOUT)
                        .ignoreHttpErrors(true)
                        .get()
                    if (isSearchResultsPage(doc)) return@withContext builtUrl
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Step 2: Try pattern-based search URLs CONCURRENTLY (batches of CONCURRENT_PATTERN_CHECKS)
        // Pre-4.0 regression: patterns were checked SEQUENTIALLY with a 6 s timeout each.
        // With 65+ patterns that means ~7 patterns checked before the 45 s per-provider wall
        // was hit.  Running them in parallel batches means the right pattern is found in
        // at most one timeout period (~15 s) per batch instead of 65 * timeout seconds.
        val candidateUrls = SEARCH_URL_PATTERNS.map { pattern ->
            pattern
                .replace("{base}", baseUrl.trimEnd('/'))
                .replace("{query_slug}", slugQuery)
                .replace("{query_plus}", plusQuery)
                .replace("{query}", encodedQuery)
        }

        for (chunk in candidateUrls.chunked(CONCURRENT_PATTERN_CHECKS)) {
            val batchResult: String? = coroutineScope {
                chunk.map { searchUrl ->
                    async(Dispatchers.IO) {
                        try {
                            val response = HttpTransport.connect(searchUrl)
                                .userAgent(DEFAULT_USER_AGENT)
                                .timeout(QUICK_TIMEOUT)
                                .followRedirects(true)
                                .ignoreHttpErrors(true)
                                .execute()
                            if (response.statusCode() == 200 && isSearchResultsPage(response.parse())) {
                                searchUrl
                            } else null
                        } catch (_: Exception) { null }
                    }
                }.awaitAll().firstOrNull { it != null }
            }
            if (batchResult != null) return@withContext batchResult
        }

        null
    }

    /**
     * Crawl category tabs/navigation to find content matching the query.
     * Used when a site has NO search but organises by tabs/categories.
     * Returns all matching content links found across relevant tabs.
     */
    suspend fun crawlCategoryTabsForQuery(
        baseUrl: String,
        query: String,
        maxTabs: Int = 6
    ): List<ContentLink> = withContext(Dispatchers.IO) {
        val allLinks = mutableListOf<ContentLink>()
        val seen = mutableSetOf<String>()

        try {
            val homepage = HttpTransport.connect(baseUrl)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(DEFAULT_TIMEOUT)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .get()

            // Collect all tab/nav links
            val tabLinks = extractAllNavigationLinks(homepage, baseUrl)
            if (tabLinks.isEmpty()) return@withContext allLinks

            // Score each tab against the query
            val queryWords = query.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
            val scoredTabs = tabLinks.map { link ->
                val score = scoreTabRelevance(link.title, link.url, queryWords)
                Pair(link, score)
            }

            // Sort: best matching first, then all others as fallback
            val sortedTabs = scoredTabs
                .filter { it.second > 0f }
                .sortedByDescending { it.second }
                .take(maxTabs)
                .ifEmpty {
                    // No matches – try generic content tabs (latest/all/movies/videos)
                    scoredTabs
                        .filter { isGenericContentTab(it.first.url, it.first.title) }
                        .take(maxTabs)
                }

            // Crawl each relevant tab concurrently
            coroutineScope {
                sortedTabs.map { (link, _) ->
                    async(Dispatchers.IO) {
                        try {
                            val tabDoc = HttpTransport.connect(link.url)
                                .userAgent(DEFAULT_USER_AGENT)
                                .timeout(DEFAULT_TIMEOUT)
                                .followRedirects(true)
                                .ignoreHttpErrors(true)
                                .get()
                            extractContentLinks(tabDoc, baseUrl)
                        } catch (_: Exception) { emptyList() }
                    }
                }.awaitAll().forEach { links ->
                    links.forEach { link ->
                        if (link.url !in seen) {
                            seen.add(link.url)
                            allLinks.add(link)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        allLinks
    }

    /**
     * Extract ALL navigation/tab/category links from a document
     */
    fun extractAllNavigationLinks(document: Document, baseUrl: String): List<ContentLink> {
        val links = mutableListOf<ContentLink>()
        val seen = mutableSetOf<String>()

        for (selector in TAB_NAVIGATION_SELECTORS) {
            try {
                document.select(selector).forEach { el ->
                    val href = el.attr("href").takeIf { it.isNotEmpty() } ?: return@forEach
                    val url = normalizeUrl(href, baseUrl)
                    if (url in seen || url.startsWith("#") || url.contains("javascript:")) return@forEach
                    if (isNavigationExcluded(url)) return@forEach
                    seen.add(url)
                    links.add(ContentLink(url = url, title = el.text().trim(), thumbnail = null, duration = null))
                }
            } catch (_: Exception) {}
        }

        return links.distinctBy { it.url }
    }

    /**
     * Score how relevant a tab/category is to the query keywords
     */
    private fun scoreTabRelevance(tabText: String, tabUrl: String, queryWords: List<String>): Float {
        val text = tabText.lowercase()
        val url = tabUrl.lowercase()
        var score = 0f

        for (word in queryWords) {
            when {
                text == word -> score += 1f          // exact match
                text.contains(word) -> score += 0.7f // partial text match
                url.contains(word) -> score += 0.5f  // URL match
                areSimilarTokens(text, word) -> score += 0.3f // fuzzy
            }
        }

        return score
    }

    /**
     * Simple token similarity check for tab matching
     */
    private fun areSimilarTokens(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.contains(b) || b.contains(a)) return true
        // Stem check: same first 4 chars
        if (a.length >= 4 && b.length >= 4 && a.take(4) == b.take(4)) return true
        return false
    }

    /**
     * Check if a tab/nav link is a "generic content" tab (all, latest, movies, videos etc)
     * These are useful fallbacks when no query-specific tab exists
     */
    private fun isGenericContentTab(url: String, text: String): Boolean {
        val combined = (url + " " + text).lowercase()
        return listOf("all", "latest", "new", "movies", "videos", "episodes",
            "shows", "series", "popular", "top", "recent", "full").any { combined.contains(it) }
    }

    /**
     * Check if a navigation URL should be excluded
     */
    private fun isNavigationExcluded(url: String): Boolean {
        val excluded = listOf("/login", "/register", "/signup", "/contact",
            "/about", "/privacy", "/terms", "/faq", "mailto:", "tel:",
            "/advertise", "/dmca", "/tos", "/sitemap")
        val lower = url.lowercase()
        return excluded.any { lower.contains(it) }
    }
    
    /**
     * Check if current page is a category page that needs bypassing
     */
    fun isCategoryPage(url: String, document: Document): Boolean {
        // Check URL patterns
        val urlLower = url.lowercase()
        if (CATEGORY_INDICATORS.any { urlLower.contains(it) }) {
            return true
        }
        
        // Check page content
        val hasSearchResults = document.select(
            ".results, .search-results, #results, [class*='result'], " +
            "[class*='item'], .video-list, .movie-list"
        ).isNotEmpty()
        
        val hasCategoryList = document.select(
            ".categories, .category-list, .genre-list, .tags, " +
            "nav.categories, .browse-categories"
        ).size > 5
        
        // If has many categories but few results, it's a category page
        return hasCategoryList && !hasSearchResults
    }
    
    /**
     * Check if page appears to be search results
     */
    fun isSearchResultsPage(document: Document): Boolean {
        // Look for result indicators
        val resultIndicators = listOf(
            ".results", ".search-results", "#search-results",
            "[class*='result-item']", "[class*='search-item']",
            ".video-item", ".movie-item", ".torrent-item",
            "article.item", ".card", ".entry",
            "[class*='search-result']", "[class*='search_result']",
            ".listing-item", ".search-item"
        )

        for (selector in resultIndicators) {
            val elements = document.select(selector)
            if (elements.size >= 3) {
                return true
            }
        }

        // Check for result count text ("12 results found", "showing 1–20 of 50", etc.)
        val bodyText = document.text().lowercase()
        val countPattern = Regex("\\d+\\s*(results?|items?|matches?|found|titles?|videos?|movies?)")
        if (countPattern.containsMatchIn(bodyText)) {
            // Make sure there's actual content alongside the count text
            val anyItem = document.select(".item, .card, article, li[class], tr[class]").size
            if (anyItem >= 2) return true
        }

        // Check for "no results" indicators (still a valid search page)
        if (bodyText.contains("no results") ||
            bodyText.contains("nothing found") ||
            bodyText.contains("0 results") ||
            bodyText.contains("no matches") ||
            bodyText.contains("could not find")) {
            return true
        }

        return false
    }
    
    /**
     * Find search form on page
     */
    fun findSearchForm(document: Document): Element? {
        val formSelectors = listOf(
            "form[action*='search']",
            "form[role='search']",
            "form#search",
            "form.search",
            "form#searchForm",
            "form.search-form",
            "form:has(input[type='search'])",
            "form:has(input[name='q'])",
            "form:has(input[name='query'])",
            "form:has(input[name='search'])",
            "form:has(input[name='s'])"
        )
        
        for (selector in formSelectors) {
            val form = document.select(selector).firstOrNull()
            if (form != null) return form
        }
        
        return null
    }
    
    /**
     * Build search URL from form element
     */
    fun buildSearchUrlFromForm(baseUrl: String, form: Element, query: String): String {
        val action = form.attr("action")
        val method = form.attr("method").lowercase()
        
        // Find input name
        val inputNames = listOf(
            "q", "query", "search", "s", "keyword", "keywords",
            "term", "text", "search_query", "kw", "wd", "k"
        )
        var inputName = "q"
        
        for (name in inputNames) {
            if (form.select("input[name='$name']").isNotEmpty()) {
                inputName = name
                break
            }
        }
        
        // Build URL
        val searchBase = when {
            action.startsWith("http") -> action
            action.startsWith("/") -> "${baseUrl.trimEnd('/')}$action"
            action.isEmpty() -> baseUrl
            else -> "${baseUrl.trimEnd('/')}/$action"
        }
        
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        return if (searchBase.contains("?")) {
            "$searchBase&$inputName=$encodedQuery"
        } else {
            "$searchBase?$inputName=$encodedQuery"
        }
    }
    
    /**
     * Navigate past category page to search
     */
    suspend fun navigatePastCategory(
        baseUrl: String,
        document: Document,
        query: String
    ): Pair<String, Document>? = withContext(Dispatchers.IO) {
        // First try to find search on current page
        val searchForm = findSearchForm(document)
        if (searchForm != null) {
            val searchUrl = buildSearchUrlFromForm(baseUrl, searchForm, query)
            try {
                val newDoc = HttpTransport.connect(searchUrl)
                    .userAgent(DEFAULT_USER_AGENT)
                    .timeout(DEFAULT_TIMEOUT)
                    .get()
                return@withContext Pair(searchUrl, newDoc)
            } catch (e: Exception) {
                // Continue trying other methods
            }
        }
        
        // Try common search URL patterns
        val workingUrl = findSearchUrl(baseUrl, query)
        if (workingUrl != null) {
            try {
                val newDoc = HttpTransport.connect(workingUrl)
                    .userAgent(DEFAULT_USER_AGENT)
                    .timeout(DEFAULT_TIMEOUT)
                    .get()
                return@withContext Pair(workingUrl, newDoc)
            } catch (e: Exception) {
                // Continue
            }
        }
        
        null
    }
    
    /**
     * Extract all possible result links from a page
     * (handles both search results and category listings)
     * v2: handles lazy-load images, data attributes, broader selectors
     */
    fun extractContentLinks(document: Document, baseUrl: String, query: String? = null): List<ContentLink> {
        val links = mutableListOf<ContentLink>()
        val seen = mutableSetOf<String>()

        // Common content item containers – ordered from specific to generic
        val itemSelectors = listOf(
            ".video-item", ".movie-item", ".item", ".card",
            ".result", ".entry", "article", ".post",
            ".torrent", "[class*='video']", "[class*='movie']",
            ".grid-item", ".list-item", ".thumb", ".media-item",
            "[data-id]", "[data-video-id]", "[data-url]",
            "li[class]", "div[class*='item']", "div[class*='card']",
            // Carousel / slider items
            ".swiper-slide", ".owl-item", ".slick-slide",
            // Table-based layouts
            "tr.row", "table tbody tr",
            // More generic patterns
            "[class*='result']", "[class*='search-result']",
            ".listing-item", ".search-result-item",
            ".media", ".media-body",
            "[class*='episode']", "[class*='show']",
            "[class*='series']", "[class*='stream']"
        )

        for (selector in itemSelectors) {
            document.select(selector).forEach { item ->
                val link = item.select("a[href]").firstOrNull() ?: return@forEach
                val href = link.attr("href")
                val fullUrl = normalizeUrl(href, baseUrl)

                if (fullUrl in seen || !isContentUrl(fullUrl)) return@forEach
                seen.add(fullUrl)

                val title = extractItemTitle(item, query)
                val thumbnail = extractItemThumbnail(item, baseUrl)
                val duration = extractItemDuration(item)

                if (title.isNotEmpty() || fullUrl.isNotEmpty()) {
                    links.add(ContentLink(
                        url = fullUrl,
                        title = title,
                        thumbnail = thumbnail,
                        duration = duration
                    ))
                }
            }
        }

        // Fallback: grab all anchor tags that look like content
        if (links.size < 5) {
            document.select("a[href]").forEach { a ->
                val href = a.attr("href")
                val fullUrl = normalizeUrl(href, baseUrl)
                if (fullUrl in seen || !isContentUrl(fullUrl)) return@forEach
                val text = listOf(
                    a.attr("data-title"),
                    a.attr("title"),
                    a.attr("aria-label"),
                    a.selectFirst("img[alt]")?.attr("alt") ?: "",
                    a.text()
                ).firstOrNull {
                    it.trim().length >= 3 && !SearchResultFilter.isLikelyDuration(it) && !SearchResultFilter.isLikelyCategory(it)
                }?.trim() ?: ""
                if (text.length < 3) return@forEach
                seen.add(fullUrl)
                val thumbnail = a.select("img").firstOrNull()?.let { img ->
                    val src = img.attr("src").takeIf { it.isNotEmpty() }
                        ?: img.attr("data-src").takeIf { it.isNotEmpty() }
                        ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                    src?.let { normalizeUrl(it, baseUrl) }
                }
                links.add(ContentLink(url = fullUrl, title = text, thumbnail = thumbnail, duration = null))
            }
        }

        return links.distinctBy { it.url }.take(150)
    }
    
    /**
     * Extract title from item
     */
    private fun extractItemTitle(item: Element, query: String? = null): String {
        val titleSelectors = listOf(
            "[itemprop=name]", "[data-title]", ".video-title", ".movie-title",
            ".item-title", ".entry-title", "h2", "h3", "h4", ".title", ".name",
            "[class*='title']", "a[title]", "a", "img[alt]"
        )

        val rawCandidates = mutableListOf<String>()
        for (selector in titleSelectors) {
            val elements = runCatching { item.select(selector).take(10) }.getOrDefault(emptyList())
            for (element in elements) {
                rawCandidates.addAll(
                    listOf(
                        element.attr("data-title"),
                        element.attr("title"),
                        element.attr("aria-label"),
                        element.attr("alt"),
                        element.text()
                    )
                )
            }
        }

        val candidates = rawCandidates
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length >= 3 && !SearchResultFilter.isLikelyDuration(it) && !SearchResultFilter.isLikelyCategory(it) }
            .distinct()
        if (candidates.isEmpty()) return ""

        val queryWords = (query ?: "")
            .lowercase()
            .map { ch -> if (ch.isLetterOrDigit()) ch else ' ' }
            .joinToString("")
            .split(Regex("\\s+"))
            .filter { it.length >= 2 }

        return candidates.maxByOrNull { candidate ->
            val lower = candidate.lowercase()
            val matches = queryWords.count { word -> lower.contains(word) }
            matches * 40 + minOf(candidate.length, 80) / 10
        } ?: ""
    }
    
    /**
     * Extract thumbnail from item
     * v2: handles all major lazy-load patterns and data attributes
     */
    private fun extractItemThumbnail(item: Element, baseUrl: String): String? {
        val img = item.select("img").firstOrNull()

        val src = img?.attr("src")?.takeIf { it.isNotEmpty() && !it.contains("data:image") }
            ?: img?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("data-original")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("data-lazy")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("data-thumb")?.takeIf { it.isNotEmpty() }
            ?: img?.attr("srcset")?.split(",")?.lastOrNull()?.trim()?.split(" ")?.firstOrNull()?.takeIf { it.isNotEmpty() }
            ?: item.attr("data-thumb").takeIf { it.isNotEmpty() }
            ?: item.attr("data-poster").takeIf { it.isNotEmpty() }
            ?: item.attr("data-image").takeIf { it.isNotEmpty() }
            ?: item.attr("data-bg").takeIf { it.isNotEmpty() }
            ?: item.select("[style*='background-image']").firstOrNull()
                ?.attr("style")
                ?.let { Regex("url\\(['\"]?(https?://[^'\"()]+)['\"]?\\)").find(it)?.groupValues?.getOrNull(1) }

        return src?.let { normalizeUrl(it, baseUrl) }
    }
    
    /**
     * Extract duration from item
     */
    private fun extractItemDuration(item: Element): String? {
        val durationSelectors = listOf(
            ".duration", ".time", ".length", "[class*='duration']",
            "[class*='time']", ".runtime"
        )
        
        for (selector in durationSelectors) {
            val text = item.select(selector).firstOrNull()?.text()?.trim()
            if (!text.isNullOrEmpty() && text.matches(Regex(".*\\d+.*"))) {
                return text
            }
        }
        
        return null
    }
    
    /**
     * Check if URL is likely content (not navigation)
     */
    private fun isContentUrl(url: String): Boolean {
        val excludePatterns = listOf(
            "/category/", "/categories/", "/tag/", "/tags/",
            "/user/", "/login", "/register", "/signup",
            "/about", "/contact", "/privacy", "/terms",
            "javascript:", "mailto:", "tel:",
            "/sitemap", "/feed", "/rss", "/atom"
        )
        // Note: /page/ intentionally excluded from filters – pagination URLs
        // contain real content and must not be treated as category pages.
        val urlLower = url.lowercase()
        if (urlLower == "#" || urlLower.startsWith("#")) return false
        return excludePatterns.none { urlLower.contains(it) }
    }
    
    /**
     * Normalize URL
     */
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "${baseUrl.trimEnd('/')}$url"
            else -> "${baseUrl.trimEnd('/')}/$url"
        }
    }
    
    /**
     * Get pagination links for more results
     * v2: supports "next" buttons, numbered pages, and infinite-scroll triggers
     */
    fun getPaginationLinks(document: Document, baseUrl: String, maxPages: Int = 5): List<String> {
        val links = mutableListOf<String>()

        val paginationSelectors = listOf(
            ".pagination a", ".pager a", ".pages a",
            "nav.pagination a", "[class*='pagination'] a",
            ".page-numbers a", ".page-link",
            "a[rel='next']", "a:contains(Next)", "a:contains(next)",
            "a:contains(>)", "a.next", ".next-page a",
            "a[aria-label='Next']", "button[aria-label='Next']",
            "[class*='next'] a"
        )

        val seen = mutableSetOf<String>()
        for (selector in paginationSelectors) {
            document.select(selector).forEach { link ->
                val href = link.attr("href")
                if (href.isNotEmpty() && !href.startsWith("#")) {
                    val url = normalizeUrl(href, baseUrl)
                    if (url !in seen) {
                        seen.add(url)
                        links.add(url)
                    }
                }
            }
        }

        // Also try numeric page pattern: detect page=1 or /page/1/ and build page 2,3...
        if (links.isEmpty()) {
            // Use the document's own URL when available, fall back to baseUrl
            val currentUrl = document.location().takeIf { it.startsWith("http") } ?: baseUrl
            val pagePatterns = listOf(
                Regex("(.*[?&]page=)(\\d+)(.*)"),
                Regex("(.*[?&]p=)(\\d+)(.*)"),
                Regex("(.*/page/)(\\d+)(/.*)"),
                Regex("(.*/p/)(\\d+)(/.*)"),
                Regex("(.*/)(\\d+)(/?)$")
            )
            for (pattern in pagePatterns) {
                val match = pattern.find(currentUrl) ?: continue
                val (prefix, num, suffix) = match.destructured
                val currentPage = num.toIntOrNull() ?: continue
                for (p in (currentPage + 1)..(currentPage + maxPages)) {
                    links.add("$prefix$p$suffix")
                }
                break
            }
        }

        return links.distinct().take(maxPages)
    }
}

data class ContentLink(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val duration: String?
)
