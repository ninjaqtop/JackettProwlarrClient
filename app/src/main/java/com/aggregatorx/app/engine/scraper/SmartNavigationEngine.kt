package com.aggregatorx.app.engine.scraper

import com.aggregatorx.app.data.model.*
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
     * Extract all possible result links from a page
     * (handles both search results and category listings)
     * v2: handles lazy-load images, data attributes, broader selectors
     */
    fun extractContentLinks(document: Document, baseUrl: String): List<ContentLink> {
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

                val title = extractItemTitle(item)
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
                val text = a.text().trim()
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
    private fun extractItemTitle(item: Element): String {
        val titleSelectors = listOf(
            "h1", "h2", "h3", "h4", ".title", ".name",
            "[class*='title']", "a[title]", ".video-title"
        )
        
        for (selector in titleSelectors) {
            val text = item.select(selector).firstOrNull()?.text()?.trim()
            if (!text.isNullOrEmpty() && text.length > 2) {
                return text
            }
        }
        
        // Fallback to link text
        return item.select("a").firstOrNull()?.text()?.trim() ?: ""
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
    
}

data class ContentLink(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val duration: String?
)
