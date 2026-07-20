package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.engine.network.TlsFingerprintEngine
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.token.TokenManager
import com.aggregatorx.app.engine.vision.VisionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.HttpsURLConnection
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Advanced Site Analyzer Engine v2
 *
 * Performs deep analysis of websites including:
 * - Security analysis (SSL, headers, CSP, etc.)
 * - DOM structure analysis
 * - Pattern detection (search forms, result lists, video players, etc.)
 * - API endpoint detection (static + runtime network intercept)
 * - Tab/category navigation discovery for no-search sites
 * - Content mapping for streaming and media sites
 * - Performance metrics
 * - Results cached per URL to avoid redundant network calls
 */
@Singleton
class SiteAnalyzerEngine @Inject constructor(
    private val endpointDiscoveryEngine: EndpointDiscoveryEngine,
    private val tokenManager: TokenManager,
    private val visionEngine: VisionEngine,
    private val tlsFingerprintEngine: TlsFingerprintEngine
) {

    /** Cache: url → (SiteAnalysis, timestamp) */
    private val analysisCache = mutableMapOf<String, Pair<SiteAnalysis, Long>>()
    
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }
    
    companion object {
        private const val DEFAULT_TIMEOUT = 30000
        private const val ANALYSIS_CACHE_TTL_MS = 3_600_000L // 1 hour
        const val CAPABILITY_REPORT_HEADER = "AggregatorX-Capability-Report"
        private val DEFAULT_USER_AGENT = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT
        
        // Common selectors for pattern detection
        private val SEARCH_FORM_SELECTORS = listOf(
            "form[action*='search']", "form[role='search']", "form#search", 
            "form.search", ".search-form", "#searchForm", "form[method='get']"
        )
        private val SEARCH_INPUT_SELECTORS = listOf(
            "input[type='search']", "input[name*='search']", "input[name='q']",
            "input[name='query']", "input[placeholder*='search' i]", "#search-input"
        )
        private val RESULT_CONTAINER_SELECTORS = listOf(
            ".results", "#results", ".search-results", "#search-results",
            ".result-list", ".content-list", ".items", ".videos", ".movies"
        )
        private val RESULT_ITEM_SELECTORS = listOf(
            ".result", ".item", ".card", ".video-item", ".movie-item",
            ".torrent", ".row", "article", ".entry", ".post"
        )
        private val PAGINATION_SELECTORS = listOf(
            ".pagination", ".pager", ".page-numbers", ".pages",
            "nav.pagination", ".paginate", "[class*='pagination']"
        )
        private val VIDEO_PLAYER_SELECTORS = listOf(
            "video", "iframe[src*='player']", ".video-player", "#player",
            "iframe[src*='youtube']", "iframe[src*='vimeo']", ".jwplayer", ".plyr"
        )
        private val NAVIGATION_SELECTORS = listOf(
            "nav", ".navigation", ".menu", "#menu", ".navbar", "header nav"
        )

        // CMS detection patterns
        private val CMS_PATTERNS = mapOf(
            "WordPress" to listOf("wp-content", "wp-includes", "wp-json", "/wp/"),
            "Ghost" to listOf("ghost.io", "content/ghost", "/assets/built/"),
            "Drupal" to listOf("drupal.js", "drupal.min.js", "sites/default/"),
            "Joomla" to listOf("/templates/", "joomla", "com_content"),
            "Wix" to listOf("wixstatic.com", "wix.com", "X-Wix-"),
            "Squarespace" to listOf("squarespace.com", "squarespace-cdn.com"),
            "Shopify" to listOf("shopify.com", "cdn.shopify.com", "Shopify.theme"),
            "Strapi" to listOf("/api/", "strapi", "_strapi"),
            "Contentful" to listOf("contentful.com", "ctfassets.net"),
            "Webflow" to listOf("webflow.com", "webflow.io"),
            "Magento" to listOf("mage", "magento", "Magento"),
            "PrestaShop" to listOf("prestashop", "prestashop.com")
        )

        // Modern Accept headers that modern browsers send (helps bypass blocks)
        private val MODERN_REQUEST_HEADERS = mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
            "Accept-Language" to "en-US,en;q=0.9",
            "Accept-Encoding" to "gzip, deflate, br",
            "sec-ch-ua" to "\"Chromium\";v=\"132\", \"Google Chrome\";v=\"132\", \"Not-A.Brand\";v=\"99\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "Sec-Fetch-Dest" to "document",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "none",
            "Sec-Fetch-User" to "?1",
            "Upgrade-Insecure-Requests" to "1",
            "Cache-Control" to "no-cache"
        )
    }
    
    /**
     * Perform comprehensive site analysis, returning a cached result if fresh.
     */
    suspend fun analyzeSite(url: String, providerId: String): SiteAnalysis = withContext(Dispatchers.IO) {
        val normalizedKey = normalizeUrl(url)
        // Return cached analysis if still fresh
        analysisCache[normalizedKey]?.let { (cached, ts) ->
            if (System.currentTimeMillis() - ts < ANALYSIS_CACHE_TTL_MS) return@withContext cached
        }

        val startTime = System.currentTimeMillis()

        try {
            // Normalize URL
            val normalizedUrl = normalizeUrl(url)
            val baseUrl = extractBaseUrl(normalizedUrl)
            
            // Fetch the page with modern browser headers, then fall back to
            // rendered content so JS-heavy sites can still be analyzed.
            val fetchedPage = fetchAnalyzerPage(normalizedUrl)
            val renderedHtml = try {
                HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                    url = normalizedUrl,
                    waitSelector = "body",
                    timeout = DEFAULT_TIMEOUT
                )
            } catch (_: Exception) {
                null
            }
            val activeHtml = renderedHtml ?: fetchedPage.html
            val document = Jsoup.parse(activeHtml, normalizedUrl)
            val loadTime = System.currentTimeMillis() - startTime
            
            // Perform all analyses
            val securityAnalysis = analyzeSecurityHeaders(normalizedUrl, fetchedPage.headers)
            val domAnalysis = analyzeDOMStructure(document)
            val patterns = detectPatterns(document)
            val mediaAnalysis = analyzeMediaContent(document)
            val apiAnalysis = detectAPIEndpoints(document, activeHtml)
            val navigationStructure = analyzeNavigation(document)
            val scrapingStrategy = determineScrapingStrategy(document, patterns)
            val capabilityReport = buildCapabilityReport(
                url = normalizedUrl,
                document = document,
                html = activeHtml,
                headers = fetchedPage.headers,
                mediaAnalysis = mediaAnalysis,
                apiAnalysis = apiAnalysis,
                loadTime = loadTime
            )
            val enrichedHeaders = fetchedPage.headers.toMutableMap().apply {
                put(CAPABILITY_REPORT_HEADER, json.encodeToString(capabilityReport))
            }
            
            val result = SiteAnalysis(
                providerId = providerId,
                url = normalizedUrl,
                analyzedAt = System.currentTimeMillis(),
                
                // Security
                securityScore = securityAnalysis.score,
                hasSSL = normalizedUrl.startsWith("https"),
                sslVersion = securityAnalysis.sslVersion,
                hasCSP = securityAnalysis.hasCSP,
                hasXFrameOptions = securityAnalysis.hasXFrameOptions,
                hasHSTS = securityAnalysis.hasHSTS,
                cookieFlags = securityAnalysis.cookieFlags,
                
                // DOM Structure
                domDepth = domAnalysis.maxDepth,
                totalElements = domAnalysis.totalElements,
                uniqueTags = domAnalysis.uniqueTags,
                formCount = domAnalysis.formCount,
                linkCount = domAnalysis.linkCount,
                scriptCount = domAnalysis.scriptCount,
                iframeCount = domAnalysis.iframeCount,
                imageCount = domAnalysis.imageCount,
                videoCount = domAnalysis.videoCount,
                
                // Patterns
                detectedPatterns = json.encodeToString(patterns),
                navigationStructure = json.encodeToString(navigationStructure),
                contentAreas = json.encodeToString(domAnalysis.contentAreas),
                searchFormSelector = patterns.find { it.type == PatternType.SEARCH_FORM }?.selector,
                searchInputSelector = findSearchInput(document),
                resultContainerSelector = patterns.find { it.type == PatternType.RESULT_LIST }?.selector,
                resultItemSelector = patterns.find { it.type == PatternType.RESULT_ITEM }?.selector,
                paginationSelector = patterns.find { it.type == PatternType.PAGINATION }?.selector,
                
                // Media
                videoPlayerType = mediaAnalysis.playerType,
                videoSourcePattern = mediaAnalysis.sourcePattern,
                thumbnailSelector = mediaAnalysis.thumbnailSelector,
                titleSelector = findTitleSelector(document, patterns),
                descriptionSelector = findDescriptionSelector(document),
                dateSelector = findDateSelector(document),
                ratingSelector = findRatingSelector(document),
                
                // API
                hasAPI = apiAnalysis.hasAPI,
                apiEndpoints = json.encodeToString(apiAnalysis.endpoints),
                apiType = apiAnalysis.type,
                
                // Performance
                loadTime = loadTime,
                resourceCount = document.select("script, link, img, video").size,
                totalSize = activeHtml.length.toLong(),
                
                // Scraping Config
                scrapingStrategy = scrapingStrategy,
                requiresJavaScript = detectJavaScriptRequirement(document),
                requiresAuth = detectAuthRequirement(document),
                
                // Raw data
                rawHtml = document.html().take(50000), // Limit storage
                headers = json.encodeToString(enrichedHeaders),
                cookies = json.encodeToString(fetchedPage.cookies)
            )
            analysisCache[normalizedKey] = result to System.currentTimeMillis()
            result
        } catch (e: Exception) {
            // Return minimal analysis on failure
            SiteAnalysis(
                providerId = providerId,
                url = url,
                securityScore = 0f
            )
        }
    }

    private suspend fun fetchAnalyzerPage(url: String): AnalyzerFetchedPage {
        return try {
            val response = Jsoup.connect(url)
                .userAgent(DEFAULT_USER_AGENT)
                .timeout(DEFAULT_TIMEOUT)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .ignoreContentType(false)
                .headers(MODERN_REQUEST_HEADERS)
                .execute()
            AnalyzerFetchedPage(
                html = response.body(),
                headers = response.headers(),
                cookies = response.cookies()
            )
        } catch (e: Exception) {
            val renderedHtml = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                url = url,
                waitSelector = "body",
                timeout = DEFAULT_TIMEOUT
            ) ?: throw e
            AnalyzerFetchedPage(
                html = renderedHtml,
                headers = emptyMap(),
                cookies = emptyMap()
            )
        }
    }
    
    /**
     * Security Header Analysis
     */
    private fun analyzeSecurityHeaders(url: String, headers: Map<String, String>): SecurityAnalysisResult {
        var score = 0f
        var sslVersion: String? = null
        
        // Check SSL
        if (url.startsWith("https")) {
            score += 20f
            sslVersion = getSSLVersion(url)
        }
        
        // Check security headers
        val hasCSP = headers.keys.any { it.equals("Content-Security-Policy", ignoreCase = true) }
        if (hasCSP) score += 20f
        
        val hasXFrameOptions = headers.keys.any { it.equals("X-Frame-Options", ignoreCase = true) }
        if (hasXFrameOptions) score += 15f
        
        val hasHSTS = headers.keys.any { it.equals("Strict-Transport-Security", ignoreCase = true) }
        if (hasHSTS) score += 20f
        
        val hasXContentType = headers.keys.any { it.equals("X-Content-Type-Options", ignoreCase = true) }
        if (hasXContentType) score += 10f
        
        val hasXXSS = headers.keys.any { it.equals("X-XSS-Protection", ignoreCase = true) }
        if (hasXXSS) score += 10f
        
        val hasReferrerPolicy = headers.keys.any { it.equals("Referrer-Policy", ignoreCase = true) }
        if (hasReferrerPolicy) score += 5f
        
        // Check cookie flags
        val setCookie = headers.entries.find { it.key.equals("Set-Cookie", ignoreCase = true) }?.value
        val cookieFlags = analyzeCookieFlags(setCookie)
        
        return SecurityAnalysisResult(
            score = score,
            sslVersion = sslVersion,
            hasCSP = hasCSP,
            hasXFrameOptions = hasXFrameOptions,
            hasHSTS = hasHSTS,
            cookieFlags = cookieFlags
        )
    }
    
    private fun getSSLVersion(url: String): String? {
        return try {
            val connection = URL(url).openConnection() as? HttpsURLConnection
            connection?.connect()
            // Get cipher suite which indicates TLS version
            val cipherSuite = connection?.cipherSuite
            connection?.disconnect()
            when {
                cipherSuite?.contains("TLS13") == true -> "TLSv1.3"
                cipherSuite?.contains("TLS12") == true -> "TLSv1.2"
                cipherSuite?.contains("TLS11") == true -> "TLSv1.1"
                cipherSuite?.contains("TLS") == true -> "TLSv1.0"
                cipherSuite?.contains("SSL") == true -> "SSL"
                else -> "TLSv1.2" // Default assumption for modern servers
            }
        } catch (e: Exception) {
            null
        }
    }
    
    private fun analyzeCookieFlags(cookie: String?): String {
        if (cookie == null) return "No cookies"
        val flags = mutableListOf<String>()
        if (cookie.contains("Secure", ignoreCase = true)) flags.add("Secure")
        if (cookie.contains("HttpOnly", ignoreCase = true)) flags.add("HttpOnly")
        if (cookie.contains("SameSite", ignoreCase = true)) flags.add("SameSite")
        return flags.joinToString(", ").ifEmpty { "No security flags" }
    }
    
    /**
     * DOM Structure Analysis
     */
    private fun analyzeDOMStructure(document: Document): DOMAnalysisResult {
        val allElements = document.allElements
        val uniqueTags = allElements.map { it.tagName() }.distinct().size
        
        // Calculate max depth
        var maxDepth = 0
        fun calculateDepth(element: Element, depth: Int) {
            if (depth > maxDepth) maxDepth = depth
            element.children().forEach { calculateDepth(it, depth + 1) }
        }
        document.body()?.let { calculateDepth(it, 0) }
        
        // Find content areas
        val contentAreas = findContentAreas(document)
        
        return DOMAnalysisResult(
            totalElements = allElements.size,
            uniqueTags = uniqueTags,
            maxDepth = maxDepth,
            formCount = document.select("form").size,
            linkCount = document.select("a").size,
            scriptCount = document.select("script").size,
            iframeCount = document.select("iframe").size,
            imageCount = document.select("img").size,
            videoCount = document.select("video").size,
            contentAreas = contentAreas
        )
    }
    
    private fun findContentAreas(document: Document): List<ContentArea> {
        val areas = mutableListOf<ContentArea>()
        
        // Look for main content areas
        val mainSelectors = listOf(
            "main", "#main", ".main", "#content", ".content",
            "article", ".articles", "#articles", ".container"
        )
        
        mainSelectors.forEach { selector ->
            document.select(selector).firstOrNull()?.let { element ->
                areas.add(ContentArea(
                    selector = selector,
                    tagName = element.tagName(),
                    childCount = element.children().size,
                    textLength = element.text().length,
                    confidence = calculateContentConfidence(element)
                ))
            }
        }
        
        return areas.sortedByDescending { it.confidence }
    }
    
    private fun calculateContentConfidence(element: Element): Float {
        var confidence = 0f
        
        // More text = higher confidence it's a content area
        val textLength = element.text().length
        if (textLength > 500) confidence += 0.3f
        if (textLength > 2000) confidence += 0.2f
        
        // Has links and images = likely content
        if (element.select("a").isNotEmpty()) confidence += 0.2f
        if (element.select("img").isNotEmpty()) confidence += 0.15f
        
        // Has articles or items
        if (element.select("article, .item, .card").isNotEmpty()) confidence += 0.15f
        
        return confidence.coerceAtMost(1f)
    }
    
    /**
     * Pattern Detection
     */
    private fun detectPatterns(document: Document): List<DetectedPattern> {
        val patterns = mutableListOf<DetectedPattern>()
        
        // Search Form
        SEARCH_FORM_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.SEARCH_FORM,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    sampleContent = elements.first()?.outerHtml()?.take(200),
                    occurrences = elements.size
                ))
            }
        }
        
        // Result Lists
        RESULT_CONTAINER_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.RESULT_LIST,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    sampleContent = elements.first()?.outerHtml()?.take(200),
                    occurrences = elements.size
                ))
            }
        }
        
        // Result Items
        detectResultItems(document)?.let { patterns.add(it) }
        
        // Pagination
        PAGINATION_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.PAGINATION,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    sampleContent = elements.first()?.outerHtml()?.take(200),
                    occurrences = elements.size
                ))
            }
        }
        
        // Video Players
        detectVideoPlayer(document)?.let { patterns.add(it) }
        
        // Navigation
        NAVIGATION_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                patterns.add(DetectedPattern(
                    type = PatternType.NAVIGATION,
                    selector = selector,
                    confidence = calculateSelectorConfidence(elements, selector),
                    occurrences = elements.size
                ))
            }
        }
        
        // Additional patterns
        detectAdditionalPatterns(document, patterns)
        
        return patterns.sortedByDescending { it.confidence }
    }
    
    private fun detectResultItems(document: Document): DetectedPattern? {
        // Look for repeating structures
        val candidates = mutableMapOf<String, Int>()
        
        // Check common item selectors
        RESULT_ITEM_SELECTORS.forEach { selector ->
            val count = document.select(selector).size
            if (count >= 3) { // At least 3 items suggests a list
                candidates[selector] = count
            }
        }
        
        // Also look for data attributes
        document.select("[data-id], [data-item], [data-result]").let {
            if (it.isNotEmpty() && it.size >= 3) {
                val selector = it.first()?.let { el ->
                    when {
                        el.hasAttr("data-id") -> "[data-id]"
                        el.hasAttr("data-item") -> "[data-item]"
                        else -> "[data-result]"
                    }
                }
                selector?.let { candidates[it] = it.length }
            }
        }
        
        // Return the best candidate
        return candidates.maxByOrNull { it.value }?.let { (selector, count) ->
            DetectedPattern(
                type = PatternType.RESULT_ITEM,
                selector = selector,
                confidence = (count.toFloat() / 20).coerceAtMost(1f),
                occurrences = count
            )
        }
    }
    
    private fun detectVideoPlayer(document: Document): DetectedPattern? {
        VIDEO_PLAYER_SELECTORS.forEach { selector ->
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                return DetectedPattern(
                    type = PatternType.VIDEO_PLAYER,
                    selector = selector,
                    confidence = 0.9f,
                    sampleContent = elements.first()?.outerHtml()?.take(300),
                    occurrences = elements.size
                )
            }
        }
        return null
    }
    
    private fun detectAdditionalPatterns(document: Document, patterns: MutableList<DetectedPattern>) {
        // Infinite scroll detection
        if (document.select("[data-infinite-scroll], .infinite-scroll, [class*='infinite']").isNotEmpty() ||
            document.html().contains("IntersectionObserver") ||
            document.html().contains("infinite")) {
            patterns.add(DetectedPattern(
                type = PatternType.INFINITE_SCROLL,
                selector = "[data-infinite-scroll]",
                confidence = 0.7f
            ))
        }
        
        // Load more button
        document.select("button:contains(Load More), a:contains(Load More), .load-more, #load-more").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.LOAD_MORE_BUTTON,
                selector = it.cssSelector(),
                confidence = 0.9f
            ))
        }
        
        // Thumbnail grid
        document.select(".thumbnails, .thumb-grid, .video-grid, .image-grid").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.THUMBNAIL_GRID,
                selector = it.cssSelector(),
                confidence = 0.85f
            ))
        }
        
        // Card layout
        document.select(".cards, .card-container, .card-grid").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.CARD_LAYOUT,
                selector = it.cssSelector(),
                confidence = 0.85f
            ))
        }
        
        // Filter panel
        document.select(".filters, .filter-panel, #filters, [class*='filter']").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.FILTER_PANEL,
                selector = it.cssSelector(),
                confidence = 0.8f
            ))
        }
        
        // Category list
        document.select(".categories, .category-list, #categories").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.CATEGORY_LIST,
                selector = it.cssSelector(),
                confidence = 0.85f
            ))
        }
        
        // Rating system
        document.select(".rating, .stars, [class*='rating'], [data-rating]").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.RATING_SYSTEM,
                selector = it.cssSelector(),
                confidence = 0.8f
            ))
        }
        
        // Login form
        document.select("form[action*='login'], form#login, .login-form, form:has(input[type='password'])").firstOrNull()?.let {
            patterns.add(DetectedPattern(
                type = PatternType.LOGIN_FORM,
                selector = it.cssSelector(),
                confidence = 0.9f
            ))
        }
    }
    
    /**
     * Media Content Analysis
     */
    private fun analyzeMediaContent(document: Document): MediaAnalysisResult {
        // Dismiss overlays/popups/ads before extracting media
        val cleanedDoc = dismissOverlaysAndAds(document)

        var playerType: String? = null
        var sourcePattern: String? = null
        var thumbnailSelector: String? = null

        // Detect video player type
        when {
            cleanedDoc.select(".jwplayer, #jwplayer").isNotEmpty() -> playerType = "JWPlayer"
            cleanedDoc.select(".video-js, .vjs-tech").isNotEmpty() -> playerType = "VideoJS"
            cleanedDoc.select(".plyr").isNotEmpty() -> playerType = "Plyr"
            cleanedDoc.select("iframe[src*='youtube']").isNotEmpty() -> playerType = "YouTube"
            cleanedDoc.select("iframe[src*='vimeo']").isNotEmpty() -> playerType = "Vimeo"
            cleanedDoc.select("video").isNotEmpty() -> playerType = "HTML5"
        }

        // Detect video source patterns
        cleanedDoc.select("video source, video[src]").firstOrNull()?.let {
            val src = it.attr("src").ifEmpty { it.attr("data-src") }
            if (src.isNotEmpty()) {
                sourcePattern = extractUrlPattern(src)
            }
        }

        // Also check for m3u8 or streaming patterns in scripts
        val scripts = cleanedDoc.select("script").html()
        when {
            scripts.contains(".m3u8") -> sourcePattern = "HLS (m3u8)"
            scripts.contains(".mpd") -> sourcePattern = "DASH (mpd)"
            scripts.contains("rtmp://") -> sourcePattern = "RTMP"
        }

        // Find thumbnail selectors
        thumbnailSelector = listOf(
            ".thumbnail img", ".thumb img", "img.thumbnail",
            ".poster", "img.poster", "[data-poster]"
        ).firstOrNull { cleanedDoc.select(it).isNotEmpty() }

        return MediaAnalysisResult(
            playerType = playerType,
            sourcePattern = sourcePattern,
            thumbnailSelector = thumbnailSelector
        )
    }

    /**
     * Remove overlays/popups/ads and auto-click close/dismiss buttons
     */
    private fun dismissOverlaysAndAds(document: Document): Document {
        val popupSelectors = listOf(
            ".popup, .modal, .overlay, .ad, .banner, .cookie, .notification, .interstitial",
            "[class*='popup']", "[class*='modal']", "[class*='overlay']", "[class*='ad']",
            "[id*='popup']", "[id*='modal']", "[id*='overlay']", "[id*='ad']"
        )
        val closeButtonSelectors = listOf(
            ".close, .dismiss, .exit, .btn-close, .close-btn, .close-button, .modal-close, .popup-close",
            "button[aria-label='Close']", "button[aria-label='Dismiss']", "[data-dismiss]", "[data-close]"
        )

        // Remove overlays/popups/ads
        popupSelectors.forEach { selector ->
            document.select(selector).forEach { it.remove() }
        }

        // Simulate auto-clicking close/dismiss buttons
        closeButtonSelectors.forEach { selector ->
            document.select(selector).forEach { it.remove() }
        }

        return document
    }
    
    /**
     * API Endpoint Detection
     */
    private fun detectAPIEndpoints(document: Document, html: String): APIAnalysisResult {
        val endpoints = mutableListOf<String>()
        var apiType: String? = null
        val detectedTypes = mutableSetOf<String>()

        // Collect all inline script content for analysis
        val scripts = document.select("script").html()
        val allText = html

        // --- REST API patterns (fetch, axios, jQuery ajax, XHR, Angular http) ---
        val restPatterns = listOf(
            Regex("""(?:fetch|axios\.get|axios\.post|http\.get|http\.post)\s*\(\s*['"`](\/api\/[^'"`\s\)]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""(?:\$\.ajax|XMLHttpRequest)[^'"]*url['":\s]+['"`](\/[^'"`\s,\)]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""url\s*:\s*['"`](\/api\/[^'"`\s,\)]+)['"`]""", RegexOption.IGNORE_CASE),
            Regex("""['"`](\/api\/v\d+\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/rest\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/wp-json\/[^'"`\s]+)['"`]"""),  // WordPress REST
            Regex("""['"`](\/ghost\/api\/[^'"`\s]+)['"`]"""),  // Ghost CMS
            Regex("""['"`](\/admin\/api\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/content\/api\/[^'"`\s]+)['"`]"""),
            Regex("""['"`](\/cms\/api\/[^'"`\s]+)['"`]""")
        )
        restPatterns.forEach { pattern ->
            pattern.findAll(scripts).forEach { match ->
                val ep = match.groupValues[1]
                if (ep.length > 3 && !ep.contains("//")) {
                    endpoints.add(ep)
                    detectedTypes.add("REST")
                }
            }
        }

        // --- GraphQL detection ---
        val graphqlIndicators = listOf("graphql", "gql`", " query {", " mutation {", " subscription {", "ApolloClient", "urql")
        if (graphqlIndicators.any { scripts.contains(it, ignoreCase = true) }) {
            detectedTypes.add("GraphQL")
            listOf(
                Regex("""['"`](\/graphql[^'"`\s]*)['"`]"""),
                Regex("""['"](https?:\/\/[^'"`\s]+\/graphql[^'"`\s]*)['"]""")
            ).forEach { p ->
                p.findAll(allText).forEach { m -> endpoints.add(m.groupValues[1]) }
            }
            // Look for Apollo/GraphQL endpoint config
            Regex("""uri\s*:\s*['"`]([^'"`\s]+)['"`]""").findAll(scripts).forEach { m ->
                if (m.groupValues[1].length > 3) endpoints.add(m.groupValues[1])
            }
        }

        // --- WebSocket endpoint detection ---
        val wsPattern = Regex("""new WebSocket\s*\(\s*['"`](wss?:\/\/[^'"`\s]+)['"`]""")
        wsPattern.findAll(scripts).forEach { match ->
            endpoints.add(match.groupValues[1])
            detectedTypes.add("WebSocket")
        }

        // --- Strapi CMS ---
        if (allText.contains("strapi", ignoreCase = true)) {
            detectedTypes.add("Strapi")
            Regex("""['"`](\/api\/[a-z-]+(?:\?[^'"`\s]*)?)['"`]""").findAll(scripts)
                .forEach { m -> endpoints.add(m.groupValues[1]) }
        }

        // --- Directus CMS ---
        if (allText.contains("directus", ignoreCase = true)) {
            detectedTypes.add("Directus")
            Regex("""['"`](\/items\/[^'"`\s]+)['"`]""").findAll(scripts)
                .forEach { m -> endpoints.add(m.groupValues[1]) }
        }

        // --- JSON data endpoints embedded in HTML ---
        listOf(
            Regex("""['"`](https?:\/\/[^'"`\s]+\.json[^'"`\s]*)['"`]"""),
            Regex("""['"`](\/[^'"`\s]+\.json[^'"`\s]*)['"`]"""),
            Regex("""data-src=['"`](https?:\/\/[^'"`\s]+)['"`]""")
        ).forEach { p ->
            p.findAll(allText).forEach { m ->
                val ep = m.groupValues[1]
                if (ep.contains("json") || ep.contains("api")) {
                    endpoints.add(ep)
                    detectedTypes.add("REST")
                }
            }
        }

        // --- Data attributes ---
        document.select("[data-api], [data-url], [data-endpoint], [data-src-url], [data-ajax-url]").forEach { el ->
            listOf("data-api", "data-url", "data-endpoint", "data-src-url", "data-ajax-url").forEach { attr ->
                el.attr(attr).takeIf { it.isNotEmpty() && it.startsWith("/") }?.let {
                    endpoints.add(it)
                    detectedTypes.add("REST")
                }
            }
        }

        // --- Link tags with API/JSON type ---
        document.select("link[type='application/json'], link[type='application/ld+json']").forEach { el ->
            el.attr("href").takeIf { it.isNotEmpty() }?.let { endpoints.add(it) }
        }

        // Determine primary API type
        apiType = when {
            "GraphQL" in detectedTypes -> "GraphQL"
            "WebSocket" in detectedTypes -> "WebSocket"
            "Strapi" in detectedTypes -> "Strapi"
            "Directus" in detectedTypes -> "Directus"
            "REST" in detectedTypes -> "REST"
            else -> null
        }

        return APIAnalysisResult(
            hasAPI = endpoints.isNotEmpty(),
            endpoints = endpoints.distinct().take(20),  // cap to avoid noise
            type = apiType
        )
    }

    /**
     * Detect CMS / site platform from HTML content and response headers.
     */
    fun detectCMS(html: String, headers: Map<String, String> = emptyMap()): String {
        val allContent = html + headers.values.joinToString(" ")
        for ((cms, signals) in CMS_PATTERNS) {
            if (signals.any { allContent.contains(it, ignoreCase = true) }) return cms
        }
        // Framework-level JS detection
        return when {
            html.contains("__NEXT_DATA__") || html.contains("/_next/") -> "Next.js"
            html.contains("__NUXT__") || html.contains("_nuxt/") -> "Nuxt.js"
            html.contains("data-reactroot") || html.contains("_ReactDOM") -> "React SPA"
            html.contains("ng-version") || html.contains("ng-app") -> "Angular"
            html.contains("data-v-") && html.contains("__vue_") -> "Vue.js"
            html.contains("sveltekit") || html.contains("__sveltekit") -> "SvelteKit"
            html.contains("astro-island") -> "Astro"
            html.contains("window.SolidJS") || html.contains("solid-js") -> "SolidJS"
            html.contains("_app.js") && html.contains("gatsby") -> "Gatsby"
            else -> "Unknown"
        }
    }
    
    /**
     * Navigation Structure Analysis
     */
    private fun analyzeNavigation(document: Document): NavigationStructure {
        val menuItems = mutableListOf<NavigationItem>()
        
        // Find main navigation
        val nav = document.select("nav, .navigation, #nav, .menu, #menu").first()
        
        nav?.select("a")?.forEach { link ->
            menuItems.add(NavigationItem(
                text = link.text(),
                url = link.attr("href"),
                hasSubmenu = link.parent()?.select("ul, .submenu, .dropdown")?.isNotEmpty() == true
            ))
        }
        
        // Find categories
        val categories = document.select(".categories a, .category-list a, nav.categories a")
            .map { it.text() to it.attr("href") }
            .filter { it.first.isNotEmpty() }
        
        return NavigationStructure(
            mainMenu = menuItems,
            categories = categories.map { NavigationItem(it.first, it.second, false) }
        )
    }
    
    /**
     * Determine optimal scraping strategy — now includes TAB_CRAWL for no-search sites.
     */
    private fun determineScrapingStrategy(document: Document, patterns: List<DetectedPattern>): ScrapingStrategy {
        val requiresJS = detectJavaScriptRequirement(document)
        val hasAPI = patterns.any { it.type == PatternType.API_ENDPOINT }
        val hasInfiniteScroll = patterns.any { it.type == PatternType.INFINITE_SCROLL }
        val hasSearchForm = patterns.any { it.type == PatternType.SEARCH_FORM }
        val hasNavTabs = document.select(
            "nav a, .nav a, ul.tabs a, [role='tablist'] a, .categories a, .menu a"
        ).size >= 3

        return when {
            hasAPI                         -> ScrapingStrategy.API_BASED
            hasInfiniteScroll              -> ScrapingStrategy.DYNAMIC_CONTENT
            requiresJS && hasAPI           -> ScrapingStrategy.HYBRID
            requiresJS                     -> ScrapingStrategy.HEADLESS_BROWSER
            !hasSearchForm && hasNavTabs   -> ScrapingStrategy.TAB_CRAWL   // no search → crawl tabs
            else                           -> ScrapingStrategy.HTML_PARSING
        }
    }
    
    private fun detectJavaScriptRequirement(document: Document): Boolean {
        val html = document.html()
        // 2026-era SPA and SSR framework indicators
        val indicators = listOf(
            // Angular (2+)
            "ng-app", "ng-version", "[_nghost", "[_ngcontent",
            // Next.js / React
            "__NEXT_DATA__", "/_next/static", "data-reactroot", "__react",
            // Nuxt.js / Vue
            "__NUXT__", "/_nuxt/", "data-v-", "__vue_",
            // SvelteKit
            "__sveltekit", "sveltekit", "svelte-",
            // Astro
            "astro-island", "astro:load", "/@astro/",
            // Remix
            "__remixContext", "/__remix-",
            // SolidJS
            "solid-js", "window._solid",
            // Gatsby
            "___gatsby", "gatsby-runtime",
            // Qwik
            "qwik-", "q:base",
            // Generic SSR/SPA injection
            "window.__INITIAL_STATE__", "window.__PRELOADED_STATE__",
            "window.__APP_STATE__", "window.__SERVER_DATA__",
            // JSON embedded state blobs
            "application/json\">{",
            // CloudFlare JS challenge
            "cf-chl-bypass", "__cf_chl_"
        )

        if (indicators.any { html.contains(it) }) return true

        // If body text is sparse but scripts are heavy → JS-rendered
        val bodyText = document.body()?.text() ?: ""
        val scriptCount = document.select("script[src]").size
        if (bodyText.length < 200 && scriptCount >= 3) return true

        // Check for noscript fallback warning (classic SPA pattern)
        val noscript = document.select("noscript").text()
        if (noscript.contains("JavaScript", ignoreCase = true) && bodyText.length < 500) return true

        return false
    }
    
    private fun detectAuthRequirement(document: Document): Boolean {
        val loginIndicators = listOf(
            "form[action*='login']", "form#login", ".login-form",
            "input[name='password']", "input[type='password']",
            ".auth-required", "#login-required"
        )
        
        return loginIndicators.any { document.select(it).isNotEmpty() }
    }
    
    // Helper functions
    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }
    
    private fun extractBaseUrl(url: String): String {
        return try {
            val u = URL(url)
            "${u.protocol}://${u.host}"
        } catch (e: Exception) {
            url
        }
    }
    
    private fun calculateSelectorConfidence(elements: Elements, selector: String): Float {
        var confidence = 0.5f
        
        // ID selectors are very specific
        if (selector.startsWith("#")) confidence += 0.3f
        
        // Class selectors with meaningful names
        if (selector.contains("search") || selector.contains("result") || 
            selector.contains("item") || selector.contains("content")) {
            confidence += 0.2f
        }
        
        // Multiple matches reduce confidence slightly
        if (elements.size > 5) confidence -= 0.1f
        
        return confidence.coerceIn(0f, 1f)
    }

    private suspend fun buildCapabilityReport(
        url: String,
        document: Document,
        html: String,
        headers: Map<String, String>,
        mediaAnalysis: MediaAnalysisResult,
        apiAnalysis: APIAnalysisResult,
        loadTime: Long
    ): AnalyzerCapabilityReport {
        val baseUrl = extractBaseUrl(url)
        val tokenBundle = try { tokenManager.harvestTokens(url) } catch (_: Exception) { null }
        val deepEndpoints = try { endpointDiscoveryEngine.deepDiscoverEndpoints(baseUrl, "test") } catch (_: Exception) { null }
        val thumbnails = collectThumbnailUrls(document, baseUrl)
        val ocrKeywords = try { visionEngine.batchExtract(thumbnails.take(8)) } catch (_: Exception) { emptyMap() }
        val network = analyzeNetworkTopology(url, headers)
        val tlsProfile = tlsFingerprintEngine.defaultProfileInfo()
        val nativeTls = tlsFingerprintEngine.nativeImpersonationInfo()
        val waf = analyzeWafFingerprint(headers, html)
        val jsBundle = analyzeJsBundles(document, html, baseUrl)
        val hiddenInputs = document.select("input[type=hidden]").size
        val formFields = document.select("input, textarea, select").size
        val lazyLoadCount = document.select("[loading=lazy], [data-src], [data-lazy-src], [data-original], [data-srcset]").size
        val hasInfiniteScroll = html.contains("IntersectionObserver", ignoreCase = true) ||
            html.contains("infinite", ignoreCase = true) ||
            document.select("[class*=infinite], [data-infinite], [data-load-more], .load-more").isNotEmpty()
        val shadowDomSignals = html.contains("attachShadow", ignoreCase = true) ||
            html.contains("shadowRoot", ignoreCase = true) ||
            document.select("template[shadowroot], template[shadowrootmode]").isNotEmpty()
        val iframeDepth = calculateIframeDepth(document)
        val mediaUrls = extractMediaUrls(document, html, baseUrl)
        val drmSignals = listOf("widevine", "playready", "fairplay", "encrypted-media", "eme")
            .filter { html.contains(it, ignoreCase = true) }

        return AnalyzerCapabilityReport(
            generatedAt = System.currentTimeMillis(),
            sections = listOf(
                AnalyzerCapabilitySection(
                    title = "Security Analysis",
                    checks = listOf(
                        capability("SSL/TLS", if (url.startsWith("https")) "active" else "missing", if (url.startsWith("https")) "HTTPS transport enabled" else "HTTP transport only"),
                        capability("CSP", if (headers.keys.any { it.equals("Content-Security-Policy", true) }) "detected" else "missing", headers.entries.firstOrNull { it.key.equals("Content-Security-Policy", true) }?.value?.take(120) ?: "No CSP header"),
                        capability("HSTS", if (headers.keys.any { it.equals("Strict-Transport-Security", true) }) "detected" else "missing", headers.entries.firstOrNull { it.key.equals("Strict-Transport-Security", true) }?.value ?: "No HSTS header"),
                        capability("X-Frame-Options", if (headers.keys.any { it.equals("X-Frame-Options", true) }) "detected" else "missing", headers.entries.firstOrNull { it.key.equals("X-Frame-Options", true) }?.value ?: "No frame protection header"),
                        capability("CORS", if (headers.keys.any { it.equals("Access-Control-Allow-Origin", true) }) "detected" else "not_detected", headers.entries.firstOrNull { it.key.equals("Access-Control-Allow-Origin", true) }?.value ?: "No public CORS header")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "DOM Structure",
                    checks = listOf(
                        capability("Shadow DOM", if (shadowDomSignals) "detected" else "not_detected", "Static signals: attachShadow/shadowRoot/template shadowroot"),
                        capability("iFrame Depth", if (iframeDepth > 0) "detected" else "not_detected", iframeDepth.toString()),
                        capability("Form Fields", if (formFields > 0) "detected" else "not_detected", "$formFields fields, $hiddenInputs hidden"),
                        capability("Script Sources", if (document.select("script[src]").isNotEmpty()) "detected" else "not_detected", "${document.select("script[src]").size} external scripts")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "Pattern Detection",
                    checks = listOf(
                        capability("Search Forms", if (findSearchInput(document) != null) "detected" else "not_detected", findSearchInput(document) ?: "No search input selector"),
                        capability("Result Lists", if (detectPatterns(document).any { it.type == PatternType.RESULT_ITEM || it.type == PatternType.RESULT_LIST }) "detected" else "not_detected", "Selector pattern analysis"),
                        capability("Pagination", if (detectPatterns(document).any { it.type == PatternType.PAGINATION }) "detected" else "not_detected", "Pagination selectors"),
                        capability("Lazy Load", if (lazyLoadCount > 0) "detected" else "not_detected", "$lazyLoadCount lazy-load elements"),
                        capability("Infinite Scroll", if (hasInfiniteScroll) "detected" else "not_detected", "IntersectionObserver/load-more/static infinite-scroll signals")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "Media Detection",
                    checks = listOf(
                        capability("Streams", if (mediaUrls.isNotEmpty()) "detected" else "not_detected", mediaUrls.take(5).joinToString(", ").ifBlank { "No direct media URLs" }),
                        capability("Embedded Players", if (!mediaAnalysis.playerType.isNullOrBlank()) "detected" else "not_detected", mediaAnalysis.playerType ?: "No known player"),
                        capability("CDN Origins", if (network.cdnProvider != "Unknown") "detected" else "not_detected", network.cdnProvider),
                        capability("DRM Type", if (drmSignals.isNotEmpty()) "detected" else "not_detected", drmSignals.joinToString(", ").ifBlank { "No static DRM/EME signal" }),
                        capability("Thumbnail URLs", if (thumbnails.isNotEmpty()) "detected" else "not_detected", "${thumbnails.size} thumbnail candidates")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "API & Endpoint Discovery",
                    checks = listOf(
                        capability("Static API Detection", if (apiAnalysis.hasAPI) "detected" else "not_detected", "${apiAnalysis.endpoints.size} endpoints, type=${apiAnalysis.type ?: "unknown"}"),
                        capability("Deep Endpoint Discovery", if ((deepEndpoints?.searchEndpoints.orEmpty() + deepEndpoints?.apiEndpoints.orEmpty()).isNotEmpty()) "detected" else "not_detected", "${deepEndpoints?.searchEndpoints?.size ?: 0} search, ${deepEndpoints?.apiEndpoints?.size ?: 0} API"),
                        capability("GraphQL", if ((apiAnalysis.type == "GraphQL") || deepEndpoints?.hasGraphQL == true) "detected" else "not_detected", "GraphQL endpoint/signature scan"),
                        capability("WebSocket", if (apiAnalysis.endpoints.any { it.startsWith("ws") }) "detected" else "not_detected", "Static WebSocket constructor scan")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "Performance Metrics",
                    checks = listOf(
                        capability("TTFB/Load Time", "active", "${loadTime}ms"),
                        capability("Resource Count", "active", document.select("script, link, img, video, iframe").size.toString()),
                        capability("Page Weight", "active", "${html.length} bytes"),
                        capability("Render Blocking", if (document.select("script:not([async]):not([defer]), link[rel=stylesheet]").isNotEmpty()) "detected" else "not_detected", "${document.select("script:not([async]):not([defer]), link[rel=stylesheet]").size} candidates"),
                        capability("CDN Detection", if (network.cdnProvider != "Unknown") "detected" else "not_detected", network.cdnProvider)
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "Token & Auth Harvesting",
                    checks = listOf(
                        capability("Bearer Tokens", if (!tokenBundle?.bearerTokens.isNullOrEmpty()) "detected" else "not_detected", "${tokenBundle?.bearerTokens?.size ?: 0} found"),
                        capability("API Keys", if (!tokenBundle?.apiKeys.isNullOrEmpty()) "detected" else "not_detected", "${tokenBundle?.apiKeys?.size ?: 0} found"),
                        capability("CSRF Tokens", if (!tokenBundle?.csrfTokens.isNullOrEmpty()) "detected" else "not_detected", "${tokenBundle?.csrfTokens?.size ?: 0} found"),
                        capability("Session IDs", if (!tokenBundle?.sessionIds.isNullOrEmpty() || !tokenBundle?.cookies.isNullOrEmpty()) "detected" else "not_detected", "${tokenBundle?.sessionIds?.size ?: 0} IDs, ${tokenBundle?.cookies?.size ?: 0} cookies"),
                        capability("Base64/Base44 Blobs", if (!tokenBundle?.base64Blobs.isNullOrEmpty()) "detected" else "not_detected", "${tokenBundle?.base64Blobs?.size ?: 0} candidates")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "WAF Fingerprinting",
                    checks = listOf(
                        capability("Detected WAF/CDN", if (waf.detected) "detected" else "not_detected", waf.provider),
                        capability("Bypass Strategy", "active", waf.strategy)
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "JS Bundle Analysis",
                    checks = listOf(
                        capability("Webpack Chunks", if (jsBundle.webpackChunks > 0) "detected" else "not_detected", "${jsBundle.webpackChunks} chunk signals"),
                        capability("Obfuscated Endpoints", if (jsBundle.obfuscatedEndpointSignals > 0) "detected" else "not_detected", "${jsBundle.obfuscatedEndpointSignals} endpoint-like strings"),
                        capability("Source Maps", if (jsBundle.sourceMaps > 0) "detected" else "not_detected", "${jsBundle.sourceMaps} source-map refs")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "Network Topology",
                    checks = listOf(
                        capability("Resolved IPs", if (network.ips.isNotEmpty()) "detected" else "error", network.ips.joinToString(", ").ifBlank { "No DNS resolution" }),
                        capability("CDN Provider", if (network.cdnProvider != "Unknown") "detected" else "not_detected", network.cdnProvider),
                        capability("Reverse Proxy", if (network.reverseProxySignals.isNotEmpty()) "detected" else "not_detected", network.reverseProxySignals.joinToString(", ").ifBlank { "No static proxy headers" }),
                        capability("ASN/GeoIP", "native_limited", "Requires external IP intelligence service; DNS resolution is active")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "Browser Fingerprint Evasion",
                    checks = listOf(
                        capability("Header Rotation", "active", "Browser-like User-Agent and request headers applied"),
                        capability("Cookie Persistence", "active", "Native helper keeps an in-memory cookie jar; WebView cookies enabled"),
                        capability("Navigator Spoof", "active", "WebView JS override for webdriver/platform/hardware/device memory"),
                        capability("Canvas/WebGL Spoof", "active", "WebView JS overrides for canvas noise and WebGL vendor/renderer"),
                        capability("TLS Fingerprint", "active", "Chromium WebView and OkHttp TLS profile used for rendered fallback fetches"),
                        capability("OkHttp TLS Profile Rotation", "active", "${tlsProfile.profile}: ${tlsProfile.description}"),
                        capability(
                            "Native TLS Impersonation",
                            if (nativeTls.available) "active" else "packaged_fallback",
                            if (nativeTls.available) {
                                "Go tls-client JNI bridge available; default=${nativeTls.defaultProfile}; profiles=${nativeTls.supportedProfiles.joinToString(", ")}"
                            } else {
                                "Go tls-client bridge packaged for arm64-v8a; runtime load pending/unavailable: ${nativeTls.unavailableReason ?: "unknown"}"
                            }
                        ),
                        capability("Custom JA3 Extension Order", if (nativeTls.available) "active" else "packaged_fallback", "Native Go tls-client supports browser ClientHello/profile impersonation; Android API fallback cannot override every ClientHello field")
                    )
                ),
                AnalyzerCapabilitySection(
                    title = "OCR Vision Scan",
                    checks = listOf(
                        capability("Thumbnail OCR", if (ocrKeywords.values.any { it.isNotEmpty() }) "detected" else if (thumbnails.isNotEmpty()) "active" else "not_detected", "${ocrKeywords.values.sumOf { it.size }} keywords from ${ocrKeywords.size} thumbnails"),
                        capability("Quality Labels", if (ocrKeywords.values.flatten().any { it.contains("720") || it.contains("1080") || it.contains("hd") }) "detected" else "not_detected", "ML Kit OCR keyword scan")
                    )
                )
            )
        )
    }

    private fun capability(name: String, status: String, detail: String): AnalyzerCapabilityCheck =
        AnalyzerCapabilityCheck(name = name, status = status, detail = detail)

    private fun collectThumbnailUrls(document: Document, baseUrl: String): List<String> {
        return document.select("img, source, video, [data-thumb], [data-thumbnail], [data-poster], [data-image], [style*='background-image']")
            .mapNotNull { element -> extractImageCandidate(element, baseUrl) }
            .distinct()
            .take(30)
    }

    private fun extractImageCandidate(element: Element, baseUrl: String): String? {
        val srcset = element.attr("srcset").takeIf { it.isNotBlank() }
            ?: element.attr("data-srcset").takeIf { it.isNotBlank() }
        val fromSrcset = srcset?.split(",")
            ?.map { it.trim().split(Regex("\\s+")).firstOrNull().orEmpty() }
            ?.firstOrNull { it.isNotBlank() && !it.startsWith("data:", true) }
        val raw = listOf(
            element.attr("src"),
            element.attr("data-src"),
            element.attr("data-lazy-src"),
            element.attr("data-original"),
            element.attr("data-thumb"),
            element.attr("data-thumbnail"),
            element.attr("data-poster"),
            element.attr("data-image"),
            element.attr("poster"),
            fromSrcset,
            Regex("""background(?:-image)?\s*:\s*url\(['"]?([^'")]+)['"]?\)""", RegexOption.IGNORE_CASE)
                .find(element.attr("style"))?.groupValues?.getOrNull(1)
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:", true) } ?: return null
        return normalizeAssetUrl(raw, baseUrl)
    }

    private fun extractMediaUrls(document: Document, html: String, baseUrl: String): List<String> {
        val urls = linkedSetOf<String>()
        document.select("video[src], source[src], a[href], iframe[src]").forEach { element ->
            val raw = element.attr("src").ifBlank { element.attr("href") }
            if (raw.containsMediaSignal()) urls.add(normalizeAssetUrl(raw, baseUrl))
        }
        Regex("""['"`]([^'"`]+(?:\.m3u8|\.mpd|\.mp4|\.webm)[^'"`]*)['"`]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match -> urls.add(normalizeAssetUrl(match.groupValues[1].replace("\\/", "/"), baseUrl)) }
        return urls.take(20)
    }

    private fun String.containsMediaSignal(): Boolean {
        val lower = lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".webm", "videoplayback", "manifest").any { lower.contains(it) }
    }

    private fun calculateIframeDepth(document: Document): Int {
        val iframeCount = document.select("iframe").size
        return when {
            iframeCount == 0 -> 0
            iframeCount < 3 -> 1
            iframeCount < 8 -> 2
            else -> 3
        }
    }

    private fun analyzeNetworkTopology(url: String, headers: Map<String, String>): NetworkTopologyResult {
        val host = try { URL(url).host } catch (_: Exception) { "" }
        val ips = try { InetAddress.getAllByName(host).mapNotNull { it.hostAddress }.distinct() } catch (_: Exception) { emptyList() }
        val headerText = headers.entries.joinToString(" ") { "${it.key}:${it.value}" }.lowercase()
        val cdn = when {
            "cloudflare" in headerText || headers.keys.any { it.equals("CF-RAY", true) } -> "Cloudflare"
            "akamai" in headerText -> "Akamai"
            "fastly" in headerText -> "Fastly"
            "cloudfront" in headerText || "x-amz-cf" in headerText -> "Amazon CloudFront"
            "bunny" in headerText -> "BunnyCDN"
            "cdn" in headerText -> "Generic CDN"
            else -> "Unknown"
        }
        val proxySignals = headers.keys.filter { key ->
            key.equals("Via", true) || key.equals("X-Forwarded-For", true) ||
                key.equals("X-Real-IP", true) || key.equals("X-Cache", true)
        }
        return NetworkTopologyResult(ips = ips, cdnProvider = cdn, reverseProxySignals = proxySignals)
    }

    private fun analyzeWafFingerprint(headers: Map<String, String>, html: String): WafFingerprintResult {
        val combined = (headers.entries.joinToString(" ") { "${it.key}:${it.value}" } + " " + html.take(5000)).lowercase()
        val provider = when {
            "cf-ray" in combined || "cloudflare" in combined -> "Cloudflare"
            "akamai" in combined || "_abck" in combined -> "Akamai"
            "imperva" in combined || "incapsula" in combined -> "Imperva/Incapsula"
            "sucuri" in combined -> "Sucuri"
            "datadome" in combined -> "DataDome"
            "perimeterx" in combined || "_px" in combined -> "PerimeterX"
            else -> "None detected"
        }
        val strategy = if (provider == "None detected") {
            "Standard browser-like headers + retries"
        } else {
            "CloudflareBypassEngine/native cookie replay/headless-helper fallback"
        }
        return WafFingerprintResult(detected = provider != "None detected", provider = provider, strategy = strategy)
    }

    private fun analyzeJsBundles(document: Document, html: String, baseUrl: String): JsBundleResult {
        val scripts = document.select("script[src]").map { normalizeAssetUrl(it.attr("src"), baseUrl) }
        val webpackSignals = scripts.count { it.contains("chunk", true) || it.contains("webpack", true) || it.contains("_next/", true) } +
            Regex("""webpackJsonp|__webpack_require__|webpackChunk""").findAll(html).count()
        val sourceMaps = Regex("""sourceMappingURL=([^*\s]+)""").findAll(html).count() +
            scripts.count { it.endsWith(".map", true) }
        val endpointSignals = Regex("""['"`]([^'"`]*(?:api|ajax|graphql|search|endpoint)[^'"`]*)['"`]""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .count()
        return JsBundleResult(webpackChunks = webpackSignals, sourceMaps = sourceMaps, obfuscatedEndpointSignals = endpointSignals)
    }

    private fun normalizeAssetUrl(url: String, baseUrl: String): String = when {
        url.startsWith("http") -> url
        url.startsWith("//") -> "https:$url"
        url.startsWith("/") -> "${extractBaseUrl(baseUrl).trimEnd('/')}$url"
        else -> "${baseUrl.trimEnd('/')}/$url"
    }
    
    private fun findSearchInput(document: Document): String? {
        SEARCH_INPUT_SELECTORS.forEach { selector ->
            if (document.select(selector).isNotEmpty()) {
                return selector
            }
        }
        return null
    }
    
    private fun findTitleSelector(document: Document, patterns: List<DetectedPattern>): String? {
        val candidates = listOf(
            "h1", "h2.title", ".title", "#title", "[class*='title']",
            ".name", ".item-title", ".video-title", ".movie-title"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun findDescriptionSelector(document: Document): String? {
        val candidates = listOf(
            ".description", "#description", ".desc", ".synopsis",
            ".summary", "[class*='description']", "p.info"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun findDateSelector(document: Document): String? {
        val candidates = listOf(
            ".date", ".time", ".timestamp", "[datetime]",
            ".posted", ".published", "[class*='date']"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun findRatingSelector(document: Document): String? {
        val candidates = listOf(
            ".rating", ".stars", ".score", "[data-rating]",
            ".imdb-rating", "[class*='rating']"
        )
        return candidates.firstOrNull { document.select(it).isNotEmpty() }
    }
    
    private fun extractUrlPattern(url: String): String {
        return when {
            url.contains(".m3u8") -> "HLS"
            url.contains(".mpd") -> "DASH"
            url.contains(".mp4") -> "MP4"
            url.contains(".webm") -> "WebM"
            else -> "Unknown"
        }
    }
    
    // Data classes for internal use
    data class AnalyzerFetchedPage(
        val html: String,
        val headers: Map<String, String>,
        val cookies: Map<String, String>
    )

    data class SecurityAnalysisResult(
        val score: Float,
        val sslVersion: String?,
        val hasCSP: Boolean,
        val hasXFrameOptions: Boolean,
        val hasHSTS: Boolean,
        val cookieFlags: String
    )
    
    data class DOMAnalysisResult(
        val totalElements: Int,
        val uniqueTags: Int,
        val maxDepth: Int,
        val formCount: Int,
        val linkCount: Int,
        val scriptCount: Int,
        val iframeCount: Int,
        val imageCount: Int,
        val videoCount: Int,
        val contentAreas: List<ContentArea>
    )
    
    data class MediaAnalysisResult(
        val playerType: String?,
        val sourcePattern: String?,
        val thumbnailSelector: String?
    )
    
    data class APIAnalysisResult(
        val hasAPI: Boolean,
        val endpoints: List<String>,
        val type: String?
    )

    data class NetworkTopologyResult(
        val ips: List<String>,
        val cdnProvider: String,
        val reverseProxySignals: List<String>
    )

    data class WafFingerprintResult(
        val detected: Boolean,
        val provider: String,
        val strategy: String
    )

    data class JsBundleResult(
        val webpackChunks: Int,
        val sourceMaps: Int,
        val obfuscatedEndpointSignals: Int
    )
}

@kotlinx.serialization.Serializable
data class ContentArea(
    val selector: String,
    val tagName: String,
    val childCount: Int,
    val textLength: Int,
    val confidence: Float
)

@kotlinx.serialization.Serializable
data class NavigationStructure(
    val mainMenu: List<NavigationItem>,
    val categories: List<NavigationItem>
)

@kotlinx.serialization.Serializable
data class NavigationItem(
    val text: String,
    val url: String,
    val hasSubmenu: Boolean
)

@Serializable
data class AnalyzerCapabilityReport(
    val generatedAt: Long,
    val sections: List<AnalyzerCapabilitySection>
)

@Serializable
data class AnalyzerCapabilitySection(
    val title: String,
    val checks: List<AnalyzerCapabilityCheck>
)

@Serializable
data class AnalyzerCapabilityCheck(
    val name: String,
    val status: String,
    val detail: String
)
