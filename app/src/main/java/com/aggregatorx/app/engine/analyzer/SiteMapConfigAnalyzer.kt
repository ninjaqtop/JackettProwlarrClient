package com.aggregatorx.app.engine.analyzer

import com.aggregatorx.app.data.model.ScrapingConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/**
 * Deterministic analyzer for a provider's static HTML site map. It extracts only
 * public document structure (search form, result cards, and navigation links); it
 * does not attempt authentication, challenge solving, or protected endpoint access.
 *
 * The result is a serializable mapping that can be shown to the user, exported as
 * JSON, and converted into the existing Room ScrapingConfig model.
 */
object SiteMapConfigAnalyzer {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Serializable
    data class SiteMap(
        val schemaVersion: Int = 1,
        val siteUrl: String,
        val searchMethod: String? = null,
        val searchUrlTemplate: String? = null,
        val queryParameter: String? = null,
        val searchFormSelector: String? = null,
        val queryInputSelector: String? = null,
        val resultSelector: String? = null,
        val titleSelector: String? = null,
        val linkSelector: String? = null,
        val thumbnailSelector: String? = null,
        val categoryLinks: List<SiteLink> = emptyList(),
        val confidence: Float = 0f,
        val notes: List<String> = emptyList()
    )

    @Serializable
    data class SiteLink(val label: String, val url: String)

    fun analyze(html: String, pageUrl: String): SiteMap {
        val doc = Jsoup.parse(html, pageUrl)
        val formMapping = detectSearchForm(doc)
        val cards = detectResultCards(doc)
        val categories = doc.select("nav a[href], [role=navigation] a[href], .categories a[href], .category-list a[href]")
            .mapNotNull { link ->
                val label = link.text().trim().take(100)
                val absolute = link.absUrl("href")
                if (label.isBlank() || !isHttpUrl(absolute) || absolute == pageUrl) null
                else SiteLink(label, absolute)
            }
            .distinctBy { it.url }
            .take(100)

        val score = (if (formMapping != null) 0.55f else 0f) +
            (if (cards != null) 0.35f else 0f) +
            (if (categories.isNotEmpty()) 0.1f else 0f)
        val notes = buildList {
            if (formMapping == null) add("No static GET search form was detected; configure a site-specific search route to enable direct search.")
            if (cards == null) add("No repeated result-card structure was confidently detected; generic parsing will be used.")
            if (categories.isNotEmpty()) add("Found ${categories.size} public navigation/category links.")
        }
        return SiteMap(
            siteUrl = pageUrl,
            searchMethod = formMapping?.method,
            searchUrlTemplate = formMapping?.template,
            queryParameter = formMapping?.queryName,
            searchFormSelector = formMapping?.formSelector,
            queryInputSelector = formMapping?.inputSelector,
            resultSelector = cards?.itemSelector,
            titleSelector = cards?.titleSelector,
            linkSelector = cards?.linkSelector,
            thumbnailSelector = cards?.thumbnailSelector,
            categoryLinks = categories,
            confidence = score.coerceIn(0f, 1f),
            notes = notes
        )
    }

    fun toJson(siteMap: SiteMap): String = json.encodeToString(siteMap)

    /** Converts a mapping into the existing Room config without inventing selectors/routes. */
    fun toScrapingConfig(siteMap: SiteMap, providerId: String): ScrapingConfig {
        return ScrapingConfig(
            providerId = providerId,
            // Leave route selection to the live candidate cascade when no form was found.
            searchUrlTemplate = siteMap.searchUrlTemplate ?: "{baseUrl}/?q={query}&page={page}",
            resultSelector = siteMap.resultSelector ?: "a[href]",
            titleSelector = siteMap.titleSelector ?: "a[href]",
            urlSelector = siteMap.linkSelector ?: "a[href]",
            thumbnailSelector = siteMap.thumbnailSelector,
            timeout = 12_000,
            retryCount = 1
        )
    }

    private data class SearchFormMapping(
        val method: String,
        val template: String,
        val queryName: String,
        val formSelector: String,
        val inputSelector: String
    )

    private fun detectSearchForm(doc: Document): SearchFormMapping? {
        val queryCandidates = listOf(
            "input[type=search][name]", "input[name=q]", "input[name=query]",
            "input[name=search]", "input[name=s]", "input[name=keyword]", "input[name=term]"
        )
        val form = doc.select("form").firstOrNull { candidate ->
            candidate.select(queryCandidates.joinToString(",")).isNotEmpty()
        } ?: return null
        val input = form.select(queryCandidates.joinToString(",")).firstOrNull() ?: return null
        val name = input.attr("name").trim().takeIf(String::isNotBlank) ?: return null
        val method = form.attr("method").ifBlank { "get" }.lowercase()
        // ScrapingConfig's current fetch path supports GET templates only; don't claim POST works.
        if (method != "get") return null
        val action = form.absUrl("action").ifBlank { doc.location() }
        if (!isHttpUrl(action)) return null

        val query = buildString {
            form.select("input[name]").forEach { field ->
                if (field === input || field.attr("disabled").isNotEmpty()) return@forEach
                val fieldName = field.attr("name").trim()
                if (fieldName.isBlank()) return@forEach
                val type = field.attr("type").lowercase()
                if (type in setOf("hidden", "submit") && field.attr("value").isNotBlank()) {
                    if (isNotEmpty()) append('&')
                    append(URLEncoder.encode(fieldName, "UTF-8"))
                    append('=')
                    append(URLEncoder.encode(field.attr("value"), "UTF-8"))
                }
            }
            if (isNotEmpty()) append('&')
            append(URLEncoder.encode(name, "UTF-8"))
            append("={query}")
            if (contains("?")) append("&page={page}") else append("&page={page}")
        }
        val separator = if (action.contains('?')) "&" else "?"
        return SearchFormMapping(
            method = method.uppercase(),
            template = "$action$separator$query",
            queryName = name,
            formSelector = form.cssSelector(),
            inputSelector = input.cssSelector()
        )
    }

    private data class ResultCardMapping(
        val itemSelector: String,
        val titleSelector: String,
        val linkSelector: String,
        val thumbnailSelector: String?
    )

    private fun detectResultCards(doc: Document): ResultCardMapping? {
        val candidates = listOf("article", ".result", ".item", ".card", ".post", "[data-result]")
        val repeated = candidates.mapNotNull { selector ->
            val items = runCatching { doc.select(selector) }.getOrNull().orEmpty()
            if (items.size < 2) return@mapNotNull null
            val valid = items.count { item ->
                item.selectFirst("a[href]")?.let { it.text().isNotBlank() || it.selectFirst("img") != null } == true
            }
            if (valid < 2) null else selector to valid
        }.maxByOrNull { it.second } ?: return null

        val item = doc.select(repeated.first).firstOrNull() ?: return null
        val title = listOf("h1", "h2", "h3", ".title", "[itemprop=name]")
            .firstOrNull { selector -> item.select(selector).any { it.text().isNotBlank() } }
            ?: "a[href]"
        val link = item.selectFirst("a[href]") ?: return null
        val image = item.selectFirst("img[src], img[data-src], [data-thumbnail]")
        return ResultCardMapping(
            itemSelector = repeated.first,
            titleSelector = title,
            linkSelector = link.cssSelector().removePrefix(repeated.first).trim().ifBlank { "a[href]" },
            thumbnailSelector = image?.let { "img[src], img[data-src], [data-thumbnail]" }
        )
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
