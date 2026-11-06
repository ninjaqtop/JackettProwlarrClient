package com.aggregatorx.app.engine.analyzer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Smart Content Classifier - AI-Like Content Detection
 * 
 * Distinguishes between:
 * - Category/Navigation elements (genre lists, menus, filters)
 * - Actual video/content results (watchable items)
 * - Search result containers
 * - Advertisements/promotions
 * - Pagination elements
 * 
 * Uses scoring heuristics, content density analysis, and pattern recognition
 * to achieve high accuracy classification even on complex sites.
 */
@Singleton
class SmartContentClassifier @Inject constructor() {

    companion object {
        // ==========================================
        // CATEGORY INDICATORS - These suggest navigation/filtering
        // ==========================================
        private val CATEGORY_KEYWORDS = listOf(
            "genre", "category", "categories", "type", "filter", "sort",
            "browse", "explore", "discover", "top", "popular", "trending",
            "new", "latest", "recent", "all", "home", "menu", "navigation",
            "action", "comedy", "drama", "horror", "thriller", "romance",
            "sci-fi", "documentary", "animation", "anime", "sports", "news",
            "music", "kids", "family", "adventure", "fantasy", "crime",
            "mystery", "western", "war", "history", "biography"
        )
        
        // ==========================================
        // CONTENT RESULT INDICATORS - These suggest actual content
        // ==========================================
        private val CONTENT_KEYWORDS = listOf(
            "episode", "season", "part", "chapter", "video", "movie",
            "watch", "play", "stream", "download", "quality", "hd", "4k",
            "1080p", "720p", "480p", "subtitle", "sub", "dub", "duration",
            "runtime", "minutes", "hours", "views", "rating", "imdb",
            "year", "release", "cast", "director", "trailer"
        )
        
        // ==========================================
        // AD/PROMOTION INDICATORS
        // ==========================================
        private val AD_INDICATORS = listOf(
            "ad", "ads", "advertisement", "sponsor", "sponsored", "promo",
            "promotion", "banner", "affiliate", "partner", "buy", "shop",
            "subscribe", "premium", "vip", "upgrade", "offer", "deal"
        )
        
        // Score thresholds
        private const val CATEGORY_THRESHOLD = 0.6f
        private const val CONTENT_THRESHOLD = 0.55f
        private const val AD_THRESHOLD = 0.5f

        // Pre-compiled patterns that were rebuilt on every call below.
        // Regex instances are immutable and only read, so sharing is equivalent.
        private val CONTENT_CLASS_REGEX = Regex(".*(result|item|video|movie|episode)s?.*")
        private val CATEGORY_CLASS_REGEX = Regex(".*(categor|genre|filter|nav|menu).*")
        private val WHITESPACE_SPLIT_REGEX = Regex("\\s+")
        private val CONTENT_TEXT_PATTERNS = listOf(
            Regex("\\d+\\s*(min|hour|hr|episode|ep|season|s\\d+)"),
            Regex("\\d{4}"),  // Year
            Regex("\\d+\\.\\d+"),  // Rating like 7.5
            Regex("\\d+\\s*views?"),
            Regex("(watch|play|stream)\\s+(now|online|free)?")
        )
        private val HOME_URL_REGEX = Regex("https?://[^/]+/?$")
        private val YEAR_REGEX = Regex("\\b(19|20)\\d{2}\\b")
        private val NUMBER_REGEX = Regex("\\d+\\.?\\d*")
        private val EPISODE_CODE_REGEX = Regex("s\\d+e\\d+")
        private val CATEGORY_COUNT_REGEX = Regex("\\((\\d+)\\)")
    }
    
    /**
     * Main classification entry point
     */
    suspend fun classifyPageContent(document: Document): PageClassification = withContext(Dispatchers.IO) {
        val body = document.body() ?: return@withContext PageClassification.empty()
        
        // Find all potential content containers
        val containers = findPotentialContainers(body)
        
        // Classify each container
        val classifiedContainers = containers.map { container ->
            classifyContainer(container)
        }
        
        // Identify the best result container vs category containers
        val resultContainers = classifiedContainers
            .filter { it.classification == ContainerType.CONTENT_RESULTS }
            .sortedByDescending { it.confidence }
        
        val categoryContainers = classifiedContainers
            .filter { it.classification == ContainerType.CATEGORY_NAVIGATION }
        
        val adContainers = classifiedContainers
            .filter { it.classification == ContainerType.ADVERTISEMENT }
        
        // Build page classification
        PageClassification(
            mainResultContainer = resultContainers.firstOrNull(),
            alternateResultContainers = resultContainers.drop(1),
            categoryContainers = categoryContainers,
            adContainers = adContainers,
            pageType = determinePageType(document, classifiedContainers),
            resultItems = resultContainers.firstOrNull()?.let { 
                extractResultItems(it.element) 
            } ?: emptyList(),
            categoryItems = categoryContainers.flatMap { 
                extractCategoryItems(it.element) 
            }.distinctBy { it.text }
        )
    }
    
    /**
     * Find potential content containers with intelligent selection
     */
    private fun findPotentialContainers(body: Element): List<Element> {
        val containers = mutableListOf<Element>()
        
        // Common container selectors
        val containerSelectors = listOf(
            // High confidence result containers
            ".results", "#results", ".search-results", "#search-results",
            ".videos", ".movies", ".content-list", ".video-list", ".movie-list",
            ".items", ".grid", ".list", ".catalog", ".collection",
            "[class*='result']", "[class*='video']", "[class*='movie']",
            "[data-results]", "[data-content]",
            
            // Common category containers
            ".categories", ".genres", ".filters", ".sidebar", 
            "nav", ".navigation", ".menu", ".tabs", ".browse",
            "[class*='category']", "[class*='genre']", "[class*='filter']",
            
            // Generic containers that need classification
            "main", "#main", ".main", "#content", ".content",
            "article", ".container", "section", ".section"
        )
        
        containerSelectors.forEach { selector ->
            try {
                body.select(selector).forEach { element ->
                    // Only include substantial containers
                    if (element.children().size >= 2 || element.text().length > 50) {
                        containers.add(element)
                    }
                }
            } catch (e: Exception) { /* Skip invalid selectors */ }
        }
        
        // Remove nested duplicates - keep outermost container
        return containers.distinctBy { it.hashCode() }
            .filterNot { container ->
                containers.any { other -> 
                    other != container && 
                    other.html().contains(container.html()) &&
                    other.html().length < container.html().length * 2
                }
            }
    }
    
    /**
     * Classify a single container element
     */
    private fun classifyContainer(element: Element): ClassifiedContainer {
        val scores = ContainerScores()
        
        // 1. Analyze element attributes (class, id, data-*)
        analyzeElementAttributes(element, scores)
        
        // 2. Analyze content structure
        analyzeContentStructure(element, scores)
        
        // 3. Analyze child elements
        analyzeChildElements(element, scores)
        
        // 4. Analyze text content
        analyzeTextContent(element, scores)
        
        // 5. Analyze link patterns
        analyzeLinkPatterns(element, scores)
        
        // 6. Calculate final classification
        val (classification, confidence) = determineClassification(scores)
        
        return ClassifiedContainer(
            element = element,
            selector = generateSelector(element),
            classification = classification,
            confidence = confidence,
            scores = scores
        )
    }
    
    /**
     * Analyze class names, IDs, and data attributes
     */
    private fun analyzeElementAttributes(element: Element, scores: ContainerScores) {
        val classNames = element.classNames().joinToString(" ").lowercase()
        val id = element.id().lowercase()
        val attrs = element.attributes().map { it.value.lowercase() }.joinToString(" ")
        val allAttrs = "$classNames $id $attrs"
        
        // Check for category indicators
        CATEGORY_KEYWORDS.forEach { keyword ->
            if (allAttrs.contains(keyword)) {
                scores.categoryScore += 0.15f
            }
        }
        
        // Check for content indicators
        CONTENT_KEYWORDS.forEach { keyword ->
            if (allAttrs.contains(keyword)) {
                scores.contentScore += 0.12f
            }
        }
        
        // Check for ad indicators
        AD_INDICATORS.forEach { keyword ->
            if (allAttrs.contains(keyword)) {
                scores.adScore += 0.2f
            }
        }
        
        // Strong signals from specific attributes
        if (classNames.matches(CONTENT_CLASS_REGEX)) {
            scores.contentScore += 0.25f
        }
        if (classNames.matches(CATEGORY_CLASS_REGEX)) {
            scores.categoryScore += 0.25f
        }
    }
    
    /**
     * Analyze the structure of the container
     */
    private fun analyzeContentStructure(element: Element, scores: ContainerScores) {
        val children = element.children()
        
        // Count meaningful child types
        val linkCount = element.select("a").size
        val imageCount = element.select("img").size
        val videoCount = element.select("video, iframe").size
        val listItemCount = element.select("li, .item, .card, article").size
        
        // Result containers typically have:
        // - Multiple similar children (items/cards)
        // - Mix of images and text
        // - Sometimes video elements or iframes
        
        if (listItemCount >= 3) {
            scores.contentScore += 0.2f
            // Check if items have similar structure
            if (checkSimilarChildStructure(children)) {
                scores.contentScore += 0.2f
            }
        }
        
        if (imageCount >= 3 && linkCount >= 3) {
            scores.contentScore += 0.15f
        }
        
        // Category containers typically have:
        // - Mostly links with short text
        // - No or few images
        // - Linear structure (list of genres)
        
        if (linkCount >= 5 && imageCount <= 2) {
            val avgLinkTextLength = element.select("a").map { it.text().length }.average()
            if (avgLinkTextLength < 20) {
                scores.categoryScore += 0.25f
            }
        }
    }
    
    /**
     * Check if children have similar HTML structure (indicating list items)
     */
    private fun checkSimilarChildStructure(children: Elements): Boolean {
        if (children.size < 3) return false
        
        // Get structure hashes for first few children
        val structures = children.take(5).map { child ->
            child.children().map { it.tagName() }.sorted().hashCode()
        }
        
        // Check if most structures are similar
        val mostCommon = structures.groupingBy { it }.eachCount().maxByOrNull { it.value }
        return (mostCommon?.value ?: 0) >= (structures.size * 0.6).toInt()
    }
    
    /**
     * Analyze child elements for patterns
     */
    private fun analyzeChildElements(element: Element, scores: ContainerScores) {
        val children = element.children()
        
        // Look for thumbnail + title pattern (common in video results)
        var thumbnailTitlePairs = 0
        children.forEach { child ->
            val hasImage = child.select("img").isNotEmpty()
            val hasTitle = child.select("h1, h2, h3, h4, .title, [class*='title']").isNotEmpty() ||
                          child.select("a").any { it.text().length in 5..100 }
            
            if (hasImage && hasTitle) {
                thumbnailTitlePairs++
            }
        }
        
        if (thumbnailTitlePairs >= 3) {
            scores.contentScore += 0.3f
        }
        
        // Look for metadata patterns (duration, views, date)
        val metadataIndicators = element.select(
            "[class*='duration'], [class*='time'], [class*='views'], " +
            "[class*='date'], [class*='rating'], span.meta"
        ).size
        
        if (metadataIndicators >= 3) {
            scores.contentScore += 0.2f
        }
        
        // Look for quality badges
        val qualityBadges = element.text().lowercase()
            .let { text ->
                listOf("hd", "4k", "1080p", "720p", "480p", "cam", "hdcam", "bluray")
                    .count { text.contains(it) }
            }
        
        if (qualityBadges >= 1) {
            scores.contentScore += 0.15f
        }
    }
    
    /**
     * Analyze text content for classification signals
     */
    private fun analyzeTextContent(element: Element, scores: ContainerScores) {
        val text = element.text().lowercase()
        val words = text.split(WHITESPACE_SPLIT_REGEX)

        // Check for content-specific patterns
        CONTENT_TEXT_PATTERNS.forEach { pattern ->
            if (pattern.containsMatchIn(text)) {
                scores.contentScore += 0.1f
            }
        }
        
        // Category text is usually short descriptive words
        val avgWordLength = words.map { it.length }.average()
        if (avgWordLength < 8 && words.size < 50 && 
            CATEGORY_KEYWORDS.any { text.contains(it) }) {
            scores.categoryScore += 0.15f
        }
    }
    
    /**
     * Analyze link URL patterns
     */
    private fun analyzeLinkPatterns(element: Element, scores: ContainerScores) {
        val links = element.select("a")
        
        // Extract URL patterns
        val hrefs = links.mapNotNull { it.attr("href").takeIf { h -> h.isNotEmpty() } }
        
        // Video/content URLs typically contain: /watch/, /video/, /movie/, /episode/
        val contentUrlPatterns = listOf(
            "/watch", "/video", "/movie", "/episode", "/play", "/stream",
            "/view", "/v/", "/e/", "/embed"
        )
        
        val contentUrls = hrefs.count { href ->
            contentUrlPatterns.any { pattern -> href.contains(pattern, ignoreCase = true) }
        }
        
        if (contentUrls >= 3) {
            scores.contentScore += 0.25f
        }
        
        // Category URLs typically contain: /genre/, /category/, /browse/, /filter/
        val categoryUrlPatterns = listOf(
            "/genre", "/category", "/browse", "/filter", "/type",
            "/tag", "/search?", "?genre=", "?category="
        )
        
        val categoryUrls = hrefs.count { href ->
            categoryUrlPatterns.any { pattern -> href.contains(pattern, ignoreCase = true) }
        }
        
        if (categoryUrls >= 3) {
            scores.categoryScore += 0.25f
        }
    }
    
    /**
     * Determine final classification based on scores
     */
    private fun determineClassification(scores: ContainerScores): Pair<ContainerType, Float> {
        // Normalize scores
        val maxScore = max(max(scores.categoryScore, scores.contentScore), scores.adScore).coerceAtLeast(0.01f)
        
        val normCategory = scores.categoryScore / maxScore
        val normContent = scores.contentScore / maxScore
        val normAd = scores.adScore / maxScore
        
        return when {
            scores.adScore > AD_THRESHOLD && normAd >= 0.8f -> 
                ContainerType.ADVERTISEMENT to scores.adScore
            
            scores.contentScore > CONTENT_THRESHOLD && normContent > normCategory -> 
                ContainerType.CONTENT_RESULTS to scores.contentScore
            
            scores.categoryScore > CATEGORY_THRESHOLD && normCategory > normContent ->
                ContainerType.CATEGORY_NAVIGATION to scores.categoryScore
            
            scores.contentScore > scores.categoryScore ->
                ContainerType.CONTENT_RESULTS to scores.contentScore
            
            else -> ContainerType.UNKNOWN to 0f
        }
    }
    
    /**
     * Generate a unique CSS selector for an element
     */
    private fun generateSelector(element: Element): String {
        return when {
            element.id().isNotEmpty() -> "#${element.id()}"
            element.classNames().isNotEmpty() -> {
                val classes = element.classNames().take(2).joinToString(".")
                "${element.tagName()}.$classes"
            }
            else -> element.cssSelector()
        }
    }
    
    /**
     * Determine overall page type
     */
    private fun determinePageType(
        document: Document, 
        containers: List<ClassifiedContainer>
    ): PageType {
        val url = document.location() ?: ""
        val title = document.title().lowercase()
        
        // Check URL patterns first
        return when {
            url.contains("/search") || url.contains("?q=") || url.contains("?query=") ->
                PageType.SEARCH_RESULTS
            
            url.contains("/watch") || url.contains("/video/") || url.contains("/play") ->
                PageType.VIDEO_PLAYER
            
            url.contains("/genre/") || url.contains("/category/") || url.contains("/browse") ->
                PageType.CATEGORY_LISTING
            
            containers.any { it.classification == ContainerType.CONTENT_RESULTS && it.confidence > 0.7f } ->
                PageType.CONTENT_LISTING
            
            title.contains("home") || url.endsWith("/") || url.matches(HOME_URL_REGEX) ->
                PageType.HOME_PAGE
            
            else -> PageType.UNKNOWN
        }
    }
    
    /**
     * Extract actual result items from a content container
     */
    private fun extractResultItems(container: Element): List<ResultItem> {
        val items = mutableListOf<ResultItem>()
        
        // Try common item selectors
        val itemSelectors = listOf(
            ".item", ".card", ".video-item", ".movie-item", ".result",
            "article", ".entry", "li", "[class*='item']", "[class*='card']"
        )
        
        for (selector in itemSelectors) {
            val elements = container.select(selector)
            if (elements.size >= 3) {
                elements.forEach { item ->
                    extractResultFromElement(item)?.let { items.add(it) }
                }
                if (items.isNotEmpty()) break
            }
        }
        
        // Fallback: extract from direct children
        if (items.isEmpty()) {
            container.children().forEach { child ->
                if (child.select("a").isNotEmpty() && child.select("img").isNotEmpty()) {
                    extractResultFromElement(child)?.let { items.add(it) }
                }
            }
        }
        
        return items
    }
    
    /**
     * Extract a single result item from an element
     */
    private fun extractResultFromElement(element: Element): ResultItem? {
        // Find the main link
        val mainLink = element.select("a").maxByOrNull { it.text().length } ?: return null
        val href = mainLink.attr("href")
        if (href.isEmpty()) return null
        
        // Find title
        val title = element.select("h1, h2, h3, h4, .title, [class*='title']")
            .firstOrNull()?.text()
            ?: mainLink.text()
            ?: return null
        
        if (title.length < 2) return null
        
        // Find thumbnail
        val thumbnail = element.select("img").firstOrNull()?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }
        
        // Find metadata
        val duration = element.select("[class*='duration'], [class*='time'], .length")
            .firstOrNull()?.text()
        
        val quality = element.text().let { text ->
            listOf("4K", "2160p", "1080p", "720p", "480p", "HD", "CAM")
                .firstOrNull { text.contains(it, ignoreCase = true) }
        }
        
        val year = YEAR_REGEX.find(element.text())?.value

        val rating = element.select("[class*='rating'], [class*='imdb'], .score")
            .firstOrNull()?.text()
            ?.let { NUMBER_REGEX.find(it)?.value }
        
        return ResultItem(
            title = title.trim(),
            url = href,
            thumbnail = thumbnail,
            duration = duration,
            quality = quality,
            year = year,
            rating = rating,
            type = determineContentType(element, href)
        )
    }
    
    /**
     * Determine if content is movie, series, episode, etc.
     */
    private fun determineContentType(element: Element, url: String): ContentType {
        val text = "${element.text()} $url".lowercase()
        
        return when {
            text.contains("episode") || text.contains(EPISODE_CODE_REGEX) -> ContentType.EPISODE
            text.contains("season") || text.contains("series") -> ContentType.SERIES
            text.contains("movie") || text.contains("film") -> ContentType.MOVIE
            text.contains("trailer") -> ContentType.TRAILER
            else -> ContentType.VIDEO
        }
    }
    
    /**
     * Extract category items from a category container
     */
    private fun extractCategoryItems(container: Element): List<CategoryItem> {
        return container.select("a").mapNotNull { link ->
            val text = link.text().trim()
            val href = link.attr("href")
            
            if (text.isNotEmpty() && href.isNotEmpty() && text.length < 50) {
                CategoryItem(
                    text = text,
                    url = href,
                    count = CATEGORY_COUNT_REGEX.find(link.text())?.groupValues?.get(1)?.toIntOrNull()
                )
            } else null
        }
    }
}

// ==========================================
// Data Classes
// ==========================================

data class PageClassification(
    val mainResultContainer: ClassifiedContainer?,
    val alternateResultContainers: List<ClassifiedContainer>,
    val categoryContainers: List<ClassifiedContainer>,
    val adContainers: List<ClassifiedContainer>,
    val pageType: PageType,
    val resultItems: List<ResultItem>,
    val categoryItems: List<CategoryItem>
) {
    companion object {
        fun empty() = PageClassification(
            mainResultContainer = null,
            alternateResultContainers = emptyList(),
            categoryContainers = emptyList(),
            adContainers = emptyList(),
            pageType = PageType.UNKNOWN,
            resultItems = emptyList(),
            categoryItems = emptyList()
        )
    }
}

data class ClassifiedContainer(
    val element: Element,
    val selector: String,
    val classification: ContainerType,
    val confidence: Float,
    val scores: ContainerScores
)

data class ContainerScores(
    var categoryScore: Float = 0f,
    var contentScore: Float = 0f,
    var adScore: Float = 0f
)

enum class ContainerType {
    CONTENT_RESULTS,
    CATEGORY_NAVIGATION,
    ADVERTISEMENT,
    UNKNOWN
}

enum class PageType {
    HOME_PAGE,
    SEARCH_RESULTS,
    CONTENT_LISTING,
    CATEGORY_LISTING,
    VIDEO_PLAYER,
    UNKNOWN
}

data class ResultItem(
    val title: String,
    val url: String,
    val thumbnail: String?,
    val duration: String?,
    val quality: String?,
    val year: String?,
    val rating: String?,
    val type: ContentType
)

enum class ContentType {
    MOVIE,
    SERIES,
    EPISODE,
    VIDEO,
    TRAILER
}

data class CategoryItem(
    val text: String,
    val url: String,
    val count: Int?
)
