package com.aggregatorx.app.engine.analyzer

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

/** Extracts reproducible search/result mappings from a public static page. */
object SiteMapConfigAnalyzer {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    // Shared constants for sets that were rebuilt on every call/iteration below.
    // They are immutable and only read, so sharing cannot change results.
    private val FORM_FIELD_TYPES_IGNORED = setOf("hidden", "submit")
    private val HTTP_SCHEMES = setOf("http", "https")

    @Serializable
    data class SiteMapConfig(
        val schemaVersion: Int = 1,
        val pageUrl: String,
        val searchMethod: String? = null,
        val searchUrlTemplate: String? = null,
        val postData: String? = null,
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
                .filter { it !== input && it.attr("type").lowercase() in FORM_FIELD_TYPES_IGNORED && it.attr("value").isNotBlank() }
                .joinToString("&") { "${URLEncoder.encode(it.attr("name"), "UTF-8")}=${URLEncoder.encode(it.attr("value"), "UTF-8")}" }
            val separator = if (action.contains('?')) "&" else "?"
            action + separator + (if (fixedFields.isBlank()) "" else "$fixedFields&") +
                "${URLEncoder.encode(queryName, "UTF-8")}={query}&page={page}"
        } else null

        // A POST search form cannot be expressed as a URL template. Mirror the
        // phone build's buildSearchRequestTemplate instead: the saved config's
        // searchUrlTemplate is the bare form action and the body template
        // (hidden/fixed fields plus the query parameter) rides in postData, so
        // the provider is searched with a real POST rather than generic GET
        // guesses.
        val postData = if (method == "post" && form != null && queryName != null && action != null && isHttpUrl(action)) {
            buildPostData(form, queryName)
        } else null
        val effectiveTemplate = searchTemplate ?: postData?.let { action }

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
        val confidence = ((if (effectiveTemplate != null) 0.55f else 0f) + (if (resultSelector != null) 0.45f else 0f)).coerceIn(0f, 1f)
        val notes = buildList {
            if (effectiveTemplate == null) add("No static GET search form detected; generic route candidates will be tried.")
            if (postData != null) add("POST search form detected; the saved config carries a postData body template (hidden/fixed fields plus the query parameter).")
            if (resultSelector == null) add("No repeated result-card structure detected; generic parsing will be used.")
        }
        return SiteMapConfig(
            pageUrl = pageUrl,
            searchMethod = when {
                postData != null -> "POST"
                searchTemplate != null -> "GET"
                else -> null
            },
            searchUrlTemplate = effectiveTemplate,
            postData = postData,
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

    /**
     * Body template for a POST search form: every named field the form would
     * submit (submit/button/image/file/password inputs and unchecked
     * checkbox/radio inputs excluded), with the query field replaced by
     * {query} and a page field by {page}; page={page} is appended when the
     * form has no page field. Mirrors the sync branch's
     * buildSearchRequestTemplate field handling.
     */
    private fun buildPostData(form: Element, queryName: String): String {
        val pairs = form.select("input[name], select[name], textarea[name]").mapNotNull { element ->
            val name = element.attr("name").trim()
            if (name.isBlank()) return@mapNotNull null
            val tag = element.tagName().lowercase()
            val type = element.attr("type").lowercase()
            if (tag == "input" && type in setOf("submit", "button", "image", "file", "password")) {
                return@mapNotNull null
            }
            if (tag == "input" && type in setOf("checkbox", "radio") && !element.hasAttr("checked")) {
                return@mapNotNull null
            }
            val isQueryField = name.equals(queryName, ignoreCase = true)
            val isPageField = name.equals("page", ignoreCase = true) ||
                name.equals("paged", ignoreCase = true) ||
                name.equals("p", ignoreCase = true) ||
                name.equals("page_num", ignoreCase = true)
            val value = when {
                isQueryField -> "{query}"
                isPageField -> "{page}"
                tag == "select" -> element.selectFirst("option[selected]")?.attr("value")
                    ?: element.selectFirst("option")?.attr("value")
                    ?: ""
                tag == "textarea" -> element.text()
                else -> element.attr("value")
            }
            if (value.isNotBlank() || isQueryField || isPageField) name to value else null
        }.distinctBy { it.first }

        val parts = pairs
            .filter { !it.first.equals(queryName, ignoreCase = true) }
            .map { (fieldName, fieldValue) -> "${encodeTemplateField(fieldName)}=${encodeTemplateField(fieldValue)}" }
            .toMutableList()
        parts.add("${encodeTemplateField(queryName)}={query}")
        if (pairs.none { it.second == "{page}" }) {
            parts.add("page={page}")
        }
        return parts.joinToString("&")
    }

    private fun encodeTemplateField(value: String): String {
        val encoded = URLEncoder.encode(
            value
                .replace("{query}", "__AGGREGATOR_QUERY_TOKEN__")
                .replace("{page}", "__AGGREGATOR_PAGE_TOKEN__"),
            "UTF-8"
        )
        return encoded
            .replace("__AGGREGATOR_QUERY_TOKEN__", "{query}")
            .replace("__AGGREGATOR_PAGE_TOKEN__", "{page}")
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()
    }.getOrDefault(false)
}
