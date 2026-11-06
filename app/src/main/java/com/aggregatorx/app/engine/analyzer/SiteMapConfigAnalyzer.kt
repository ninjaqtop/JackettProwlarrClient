package com.aggregatorx.app.engine.analyzer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

/** Extracts reproducible search/result mappings from a public static page. */
object SiteMapConfigAnalyzer {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    data class SiteMapConfig(
        val schemaVersion: Int = 1,
        val pageUrl: String,
        val searchMethod: String? = null,
        val searchUrlTemplate: String? = null,
        val queryParameter: String? = null,
        val searchFormSelector: String? = null,
        val queryInputSelector: String? = null,
        val resultSelector: String? = null,
        val titleSelector: String? = null,
        val linkSelector: String? = null,
        val thumbnailSelector: String? = null,
        val confidence: Float = 0f,
        val notes: List<String> = emptyList()
    )

    fun analyze(html: String, pageUrl: String): SiteMapConfig {
        if (html.isBlank()) return SiteMapConfig(pageUrl = pageUrl, notes = listOf("No static HTML was available; generic live search candidates will be used."))
        val doc = Jsoup.parse(html, pageUrl)
        val form = doc.select("form").firstOrNull { candidate ->
            candidate.select("input[type=search][name], input[name=q], input[name=query], input[name=search], input[name=s], input[name=keyword], input[name=term]").isNotEmpty()
        }
        val input = form?.selectFirst("input[type=search][name], input[name=q], input[name=query], input[name=search], input[name=s], input[name=keyword], input[name=term]")
        val method = form?.attr("method").orEmpty().ifBlank { "get" }.lowercase()
        val queryName = input?.attr("name")?.takeIf { it.isNotBlank() }
        val action = form?.absUrl("action")?.ifBlank { pageUrl }
        val searchTemplate = if (method == "get" && queryName != null && action != null && isHttpUrl(action)) {
            val fixedFields = form.select("input[name]")
                .filter { it !== input && it.attr("type").lowercase() in setOf("hidden", "submit") && it.attr("value").isNotBlank() }
                .joinToString("&") { "${URLEncoder.encode(it.attr("name"), "UTF-8")}=${URLEncoder.encode(it.attr("value"), "UTF-8")}" }
            val separator = if (action.contains('?')) "&" else "?"
            action + separator + (if (fixedFields.isBlank()) "" else "$fixedFields&") +
                "${URLEncoder.encode(queryName, "UTF-8")}={query}&page={page}"
        } else null

        val resultSelector = listOf("article", ".result", ".item", ".card", ".post", "[data-result]")
            .mapNotNull { selector ->
                val repeated = runCatching { doc.select(selector) }.getOrNull().orEmpty()
                val validCount = repeated.count { item -> item.selectFirst("a[href]")?.let { it.text().isNotBlank() || it.selectFirst("img") != null } == true }
                if (validCount >= 2) selector to validCount else null
            }.maxByOrNull { it.second }?.first
        val firstItem = resultSelector?.let { runCatching { doc.selectFirst(it) }.getOrNull() }
        val titleSelector = firstItem?.let { item ->
            listOf("h1", "h2", "h3", ".title", "[itemprop=name]").firstOrNull { selector -> item.select(selector).any { it.text().isNotBlank() } }
                ?: "a[href]"
        }
        val linkSelector = firstItem?.selectFirst("a[href]")?.let { "a[href]" }
        val thumbnailSelector = firstItem?.selectFirst("img[src], img[data-src], [data-thumbnail]")?.let { "img[src], img[data-src], [data-thumbnail]" }
        val confidence = ((if (searchTemplate != null) 0.55f else 0f) + (if (resultSelector != null) 0.45f else 0f)).coerceIn(0f, 1f)
        val notes = buildList {
            if (searchTemplate == null) add("No static GET search form detected; generic route candidates will be tried.")
            if (resultSelector == null) add("No repeated result-card structure detected; generic parsing will be used.")
        }
        return SiteMapConfig(
            pageUrl = pageUrl,
            searchMethod = if (searchTemplate == null) null else "GET",
            searchUrlTemplate = searchTemplate,
            queryParameter = queryName,
            searchFormSelector = form?.cssSelector(),
            queryInputSelector = input?.cssSelector(),
            resultSelector = resultSelector,
            titleSelector = titleSelector,
            linkSelector = linkSelector,
            thumbnailSelector = thumbnailSelector,
            confidence = confidence,
            notes = notes
        )
    }

    fun encode(mapping: SiteMapConfig): String = json.encodeToString(mapping)

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
