package com.aggregatorx.app.engine.analyzer

import kotlinx.serialization.Serializable
import org.jsoup.nodes.Document

/**
 * WhatWeb / Wappalyzer-style fingerprinting plus the search-route knowledge
 * each fingerprinted stack implies (spec §1, "technology fingerprinting …
 * use the fingerprint to prioritize that stack's known search-route shapes").
 *
 * Two halves:
 *
 *  - [detect]: identify the CMS / framework / commerce platform / forum /
 *    search-stack from response headers, cookies, meta generator, script
 *    sources, HTML markers and JS globals.
 *  - [routesFor] / [SIGNATURES]: for every identified stack, the search routes
 *    that stack actually uses. These are PRIORITIES, not answers: a route only
 *    becomes a candidate after it is probed live with the site's own probe term
 *    (ffuf-style, soft-404 calibrated), and only becomes a saved config after
 *    full verification. Nothing here is ever saved on the strength of the
 *    signature alone.
 *
 * [extractHostedSearch] is the separate case: Algolia, Typesense, Meilisearch,
 * Searchanise, Doofinder, Klevu and Elastic App Search put their application
 * id, search-only API key and index name in the site's own JavaScript. Those
 * services answer on their own hosts, so they are the one allowed same-host
 * exception — and only with credentials the site itself published.
 */
object StackSignatures {

    /** One fingerprintable stack and the search routes it implies. */
    data class Signature(
        val id: String,
        val label: String,
        /** Relative search routes, best first. {query} and {page} are filled in. */
        val routes: List<String>,
        /** Relative routes that answer JSON (probed with Accept: application/json). */
        val jsonRoutes: List<String> = emptyList(),
        /** Autocomplete/suggest routes — captured separately, never treated as search. */
        val suggestRoutes: List<String> = emptyList()
    )

    /** A search service hosted off-site whose credentials the page published. */
    @Serializable
    data class HostedSearch(
        val service: String,
        val url: String,
        val method: String = "POST",
        val body: String? = null,
        val headers: Map<String, String> = emptyMap(),
        val evidence: String = ""
    )

    // ── The signature library ─────────────────────────────────────────

    val SIGNATURES: List<Signature> = listOf(
        Signature(
            "wordpress", "WordPress",
            routes = listOf("/?s={query}", "/page/{page}/?s={query}", "/search/{query}/"),
            jsonRoutes = listOf(
                "/wp-json/wp/v2/search?search={query}&per_page=20&page={page}",
                "/wp-json/wp/v2/posts?search={query}&per_page=20&page={page}"
            )
        ),
        Signature(
            "woocommerce", "WooCommerce",
            routes = listOf("/?s={query}&post_type=product", "/?s={query}&post_type=product&paged={page}"),
            jsonRoutes = listOf("/wp-json/wc/store/v1/products?search={query}&page={page}")
        ),
        Signature(
            "drupal", "Drupal",
            routes = listOf(
                "/search/node?keys={query}&page={page}",
                "/search/node/{query}",
                "/search?search_api_fulltext={query}&page={page}",
                "/search/content?search_api_fulltext={query}"
            ),
            jsonRoutes = listOf("/jsonapi/node/article?filter[title][operator]=CONTAINS&filter[title][value]={query}")
        ),
        Signature(
            "joomla", "Joomla",
            routes = listOf(
                "/index.php?option=com_search&searchword={query}",
                "/index.php?option=com_finder&view=search&q={query}",
                "/component/search/?searchword={query}",
                "/search?q={query}"
            )
        ),
        Signature(
            "magento", "Magento",
            routes = listOf(
                "/catalogsearch/result/?q={query}",
                "/catalogsearch/result/?q={query}&p={page}"
            )
        ),
        Signature(
            "shopify", "Shopify",
            routes = listOf(
                "/search?q={query}&type=product",
                "/search?q={query}&type=product&page={page}",
                "/search?q={query}"
            ),
            jsonRoutes = listOf("/search/suggest.json?q={query}&resources[type]=product"),
            suggestRoutes = listOf("/search/suggest.json?q={query}")
        ),
        Signature(
            "bigcommerce", "BigCommerce",
            routes = listOf("/search.php?search_query={query}", "/search.php?search_query={query}&page={page}")
        ),
        Signature(
            "prestashop", "PrestaShop",
            routes = listOf(
                "/search?controller=search&s={query}",
                "/index.php?controller=search&s={query}&page={page}",
                "/recherche?controller=search&s={query}"
            )
        ),
        Signature(
            "opencart", "OpenCart",
            routes = listOf(
                "/index.php?route=product/search&search={query}",
                "/index.php?route=product/search&search={query}&page={page}",
                "/search?search={query}"
            )
        ),
        Signature(
            "squarespace", "Squarespace",
            routes = listOf("/search?q={query}"),
            jsonRoutes = listOf("/api/search/GeneralSearch?q={query}", "/search?q={query}&format=json")
        ),
        Signature(
            "wix", "Wix",
            routes = listOf("/search?q={query}", "/search-results?q={query}")
        ),
        Signature(
            "ghost", "Ghost",
            routes = listOf("/search?q={query}"),
            jsonRoutes = listOf("/ghost/api/content/posts/?filter=title:~'{query}'&limit=20")
        ),
        Signature(
            "mediawiki", "MediaWiki",
            routes = listOf(
                "/index.php?search={query}&fulltext=1",
                "/wiki/Special:Search?search={query}&fulltext=1",
                "/Special:Search?search={query}&fulltext=1"
            ),
            jsonRoutes = listOf(
                "/api.php?action=query&list=search&srsearch={query}&format=json",
                "/w/api.php?action=query&list=search&srsearch={query}&format=json"
            ),
            suggestRoutes = listOf("/api.php?action=opensearch&search={query}&format=json")
        ),
        Signature(
            "discourse", "Discourse",
            routes = listOf("/search?q={query}&page={page}"),
            jsonRoutes = listOf("/search.json?q={query}&page={page}")
        ),
        Signature(
            "phpbb", "phpBB",
            routes = listOf(
                "/search.php?keywords={query}",
                "/search.php?keywords={query}&start={page}"
            )
        ),
        Signature(
            "vbulletin", "vBulletin",
            routes = listOf("/search.php?do=process&query={query}", "/search?q={query}")
        ),
        Signature(
            "xenforo", "XenForo",
            routes = listOf("/search/search?keywords={query}", "/search/?q={query}")
        ),
        Signature(
            "mybb", "MyBB",
            routes = listOf("/search.php?action=do_search&keywords={query}")
        ),
        Signature(
            "smf", "SMF",
            routes = listOf("/index.php?action=search2&search={query}", "/index.php?action=search;search={query}")
        ),
        Signature(
            "nodebb", "NodeBB",
            routes = listOf("/search?term={query}&in=titlesposts"),
            jsonRoutes = listOf("/api/search?term={query}&in=titlesposts")
        ),
        Signature(
            "invision", "Invision Community",
            routes = listOf("/search/?q={query}", "/index.php?app=core&module=search&q={query}")
        ),
        Signature(
            "nextjs", "Next.js",
            routes = listOf("/search?q={query}&page={page}"),
            jsonRoutes = listOf("/api/search?q={query}&page={page}", "/api/search?query={query}")
        ),
        Signature(
            "nuxt", "Nuxt",
            routes = listOf("/search?q={query}"),
            jsonRoutes = listOf("/api/search?q={query}", "/_nuxt/api/search?q={query}")
        ),
        Signature(
            "django", "Django",
            routes = listOf("/search/?q={query}&page={page}", "/search?q={query}&page={page}"),
            jsonRoutes = listOf("/api/search/?q={query}", "/api/v1/search/?q={query}")
        ),
        Signature(
            "rails", "Ruby on Rails",
            routes = listOf("/search?q={query}&page={page}", "/search?utf8=%E2%9C%93&q={query}"),
            jsonRoutes = listOf("/search.json?q={query}&page={page}")
        ),
        Signature(
            "laravel", "Laravel",
            routes = listOf("/search?q={query}&page={page}", "/search?query={query}&page={page}"),
            jsonRoutes = listOf("/api/search?q={query}&page={page}")
        ),
        Signature(
            "graphql", "GraphQL endpoint",
            jsonRoutes = listOf("/graphql", "/api/graphql", "/graphql/v1"),
            routes = emptyList()
        ),
        // ── Torrent / indexer families ────────────────────────────────
        Signature(
            "unit3d", "UNIT3D tracker",
            routes = listOf(
                "/torrents?name={query}&page={page}",
                "/torrents/filter?name={query}&page={page}",
                "/torrents?search={query}&page={page}"
            ),
            jsonRoutes = listOf("/api/torrents/filter?name={query}&page={page}")
        ),
        Signature(
            "gazelle", "Gazelle tracker",
            routes = listOf(
                "/torrents.php?searchstr={query}&page={page}",
                "/torrents.php?action=advanced&searchstr={query}"
            ),
            jsonRoutes = listOf("/ajax.php?action=browse&searchstr={query}&page={page}")
        ),
        Signature(
            "torrentpier", "TorrentPier / punBB tracker",
            routes = listOf("/tracker.php?nm={query}", "/search.php?keywords={query}")
        ),
        Signature(
            "xbtit", "XBTIT tracker",
            routes = listOf("/index.php?page=torrents&search={query}&active=0")
        ),
        Signature(
            "torrenttrader", "TorrentTrader",
            routes = listOf("/torrents-search.php?search={query}", "/browse.php?search={query}&page={page}")
        ),
        Signature(
            "tbdev", "TBDev / BitTorrent tracker",
            routes = listOf("/browse.php?search={query}&page={page}", "/browse.php?do=search&keywords={query}")
        ),
        Signature(
            "newznab", "Newznab / NNTmux",
            routes = listOf("/search?search_name={query}"),
            jsonRoutes = listOf("/api?t=search&q={query}&o=json")
        ),
        // ── Hosted search stacks whose own routes sit on the site ─────
        Signature(
            "elasticsearch", "Elasticsearch / OpenSearch proxy",
            jsonRoutes = listOf(
                "/_search?q={query}",
                "/api/search?q={query}",
                "/elasticsearch/_search?q={query}"
            ),
            routes = emptyList()
        ),
        Signature(
            "typesense", "Typesense",
            jsonRoutes = listOf("/collections/*/documents/search?q={query}"),
            routes = emptyList()
        ),
        Signature(
            "meilisearch", "Meilisearch",
            jsonRoutes = listOf("/indexes/*/search?q={query}"),
            routes = emptyList()
        )
    )

    private val BY_ID: Map<String, Signature> = SIGNATURES.associateBy { it.id }

    // ── Detection ─────────────────────────────────────────────────────

    /**
     * Identify every stack this site shows evidence of. [headerLookup] reads one
     * response header; [cookieNames] are the Set-Cookie names; [html] is the
     * homepage body.
     */
    fun detect(
        headerLookup: (String) -> String?,
        cookieNames: Collection<String>,
        html: String,
        doc: Document?
    ): List<String> {
        val found = linkedSetOf<String>()
        val lower = html.take(600_000).lowercase()
        val cookies = cookieNames.map { it.lowercase() }
        val generator = (doc?.selectFirst("meta[name=generator]")?.attr("content")
            ?: GENERATOR_META.find(html)?.groupValues?.get(1)).orEmpty().lowercase()
        val scriptSrcs = doc?.select("script[src]")?.map { it.absUrl("src").lowercase() }.orEmpty()
        val allScriptText = (scriptSrcs + lower)

        fun hit(id: String) { found += id }
        fun anyIn(vararg markers: String) = markers.any { it in lower }
        fun header(name: String) = headerLookup(name)?.lowercase()

        // Headers
        header("x-powered-by")?.let { p ->
            if ("php" in p) hit("php")
            if ("express" in p) hit("express")
            if ("asp.net" in p) hit("aspnet")
            if ("next.js" in p) hit("nextjs")
        }
        header("x-generator")?.let { if ("drupal" in it) hit("drupal") }
        if (header("x-drupal-cache") != null || header("x-drupal-dynamic-cache") != null) hit("drupal")
        if (header("x-shopify-stage") != null || header("x-shopid") != null || header("x-shardid") != null) hit("shopify")
        if (header("x-magento-tags") != null || header("x-magento-cache-debug") != null) hit("magento")
        if (header("x-wix-request-id") != null || header("x-wix-renderer-server") != null) hit("wix")
        if (header("x-ghost-cache-status") != null) hit("ghost")
        if (header("x-discourse-route") != null || header("x-discourse-username") != null) hit("discourse")
        if (header("x-bc-rewrite-rule") != null) hit("bigcommerce")
        if (header("x-squarespace-sid") != null) hit("squarespace")
        if (header("x-nextjs-cache") != null || header("x-nextjs-matched-path") != null) hit("nextjs")
        if (header("x-typesense-api-version") != null) hit("typesense")
        if (header("x-meili-api-key") != null) hit("meilisearch")
        if (header("x-elastic-product") != null) hit("elasticsearch")

        // Cookies
        if (cookies.any { it.startsWith("wordpress") || it.startsWith("wp-") || it == "wp_lang" }) hit("wordpress")
        if (cookies.any { it.startsWith("woocommerce") || it.startsWith("wp_woocommerce") }) hit("woocommerce")
        if (cookies.any { it == "laravel_session" || it == "xsrf-token" }) hit("laravel")
        if (cookies.any { it == "phpsessid" }) hit("php")
        if (cookies.any { it == "frontend" || it.startsWith("mage-") || it == "private_content_version" }) hit("magento")
        if (cookies.any { it == "_shopify_y" || it == "_shopify_s" || it == "cart_currency" }) hit("shopify")
        if (cookies.any { it.startsWith("sessionid") && cookies.contains("csrftoken") }) hit("django")
        if (cookies.any { it == "csrftoken" }) hit("django")
        if (cookies.any { it.startsWith("_") && it.endsWith("_session") }) hit("rails")
        if (cookies.any { it.startsWith("prestashop") || it.startsWith("ps_") }) hit("prestashop")
        if (cookies.any { it.startsWith("ocsessionid") || it == "opencart" }) hit("opencart")
        if (cookies.any { it.startsWith("phpbb3_") || it.startsWith("phpbb_") }) hit("phpbb")
        if (cookies.any { it.startsWith("bb_") || it.startsWith("vbulletin") }) hit("vbulletin")
        if (cookies.any { it.startsWith("xf_") }) hit("xenforo")
        if (cookies.any { it.startsWith("mybb") }) hit("mybb")
        if (cookies.any { it.startsWith("smf") }) hit("smf")
        if (cookies.any { it.startsWith("ips4_") }) hit("invision")
        if (cookies.any { it == "_t" || it == "_forum_session" } && anyIn("discourse")) hit("discourse")

        // Meta generator
        when {
            "wordpress" in generator -> hit("wordpress")
            "drupal" in generator -> hit("drupal")
            "joomla" in generator -> hit("joomla")
            "ghost" in generator -> hit("ghost")
            "mediawiki" in generator -> hit("mediawiki")
            "prestashop" in generator -> hit("prestashop")
            "opencart" in generator -> hit("opencart")
            "xenforo" in generator -> hit("xenforo")
            "phpbb" in generator -> hit("phpbb")
            "vbulletin" in generator -> hit("vbulletin")
            "mybb" in generator -> hit("mybb")
            "smf" in generator -> hit("smf")
            "typo3" in generator -> hit("typo3")
            "hugo" in generator || "jekyll" in generator -> hit("static")
        }

        // HTML / script markers
        if (anyIn("/wp-content/", "/wp-includes/", "wp-json", "wp-emoji")) hit("wordpress")
        if (anyIn("woocommerce", "wc-ajax", "/wp-content/plugins/woocommerce")) hit("woocommerce")
        if (anyIn("drupal.settings", "drupal-settings-json", "/sites/default/files", "data-drupal-")) hit("drupal")
        if (anyIn("/media/jui/", "com_content", "/components/com_", "joomla-script-options")) hit("joomla")
        if (anyIn("mage/cookies", "magento_", "/static/version", "requirejs/mage")) hit("magento")
        if (anyIn("cdn.shopify.com", "shopify.theme", "shopify-section", "/cdn/shop/")) hit("shopify")
        if (anyIn("cdn11.bigcommerce.com", "/stencil/", "bigcommerce")) hit("bigcommerce")
        if (anyIn("prestashop", "/themes/classic/assets")) hit("prestashop")
        if (anyIn("catalog/view/theme", "index.php?route=")) hit("opencart")
        if (anyIn("static1.squarespace.com", "squarespace-cdn", "static.squarespace.com")) hit("squarespace")
        if (anyIn("wixstatic.com", "static.parastorage.com", "wix-warmup-data")) hit("wix")
        if (anyIn("ghost-sdk", "/assets/built/", "content/images/size")) hit("ghost")
        if (anyIn("mediawiki", "mw-content-text", "/load.php?", "wgpagename")) hit("mediawiki")
        if (anyIn("discourse", "data-discourse-present", "/assets/discourse")) hit("discourse")
        if (anyIn("phpbb", "viewtopic.php", "styles/prosilver")) hit("phpbb")
        if (anyIn("vbulletin", "showthread.php?")) hit("vbulletin")
        if (anyIn("xenforo", "xf-")) hit("xenforo")
        if (anyIn("mybb", "showthread.php?tid")) hit("mybb")
        if (anyIn("smf_", "index.php?topic=")) hit("smf")
        if (anyIn("nodebb", "/assets/nodebb")) hit("nodebb")
        if (anyIn("ipsempty", "ips.jslang", "/applications/core/interface")) hit("invision")
        if (anyIn("__next_data__", "/_next/static", "/_next/image")) hit("nextjs")
        if (anyIn("__nuxt", "/_nuxt/", "window.__nuxt__")) hit("nuxt")
        if (anyIn("csrfmiddlewaretoken", "/static/admin/")) hit("django")
        if (anyIn("csrf-param", "data-turbolinks", "/assets/application-", "rails-ujs")) hit("rails")
        if (anyIn("laravel", "/vendor/livewire")) hit("laravel")
        if (anyIn("/graphql")) hit("graphql")
        if (anyIn("data-reactroot", "react-dom", "__react")) hit("react")
        if (anyIn("ng-version", "angular.min.js")) hit("angular")
        if (anyIn("__vue__", "data-v-app")) hit("vue")
        if (anyIn("cf_chl_opt", "challenge-platform", "__cf_bm")) hit("anti-bot-cloudflare")

        // Torrent / indexer families
        if (anyIn("unit3d", "/torrents/filter", "livewire", "torrent-search")) {
            if (anyIn("unit3d") || (anyIn("/torrents/filter") && anyIn("livewire"))) hit("unit3d")
        }
        if (anyIn("torrents.php?searchstr", "gazelle", "ajax.php?action=browse")) hit("gazelle")
        if (anyIn("torrentpier", "tracker.php?nm")) hit("torrentpier")
        if (anyIn("xbtit", "page=torrents")) hit("xbtit")
        if (anyIn("torrenttrader", "torrents-search.php")) hit("torrenttrader")
        if (anyIn("browse.php?search", "tbdev")) hit("tbdev")
        if (anyIn("newznab", "nntmux")) hit("newznab")

        // Hosted search stacks named by the page's own scripts
        if (allScriptText.any { "algolia" in it }) hit("algolia")
        if (allScriptText.any { "typesense" in it }) hit("typesense")
        if (allScriptText.any { "meilisearch" in it }) hit("meilisearch")
        if (allScriptText.any { "searchanise" in it }) hit("searchanise")
        if (allScriptText.any { "doofinder" in it }) hit("doofinder")
        if (allScriptText.any { "klevu" in it }) hit("klevu")
        if (allScriptText.any { "swiftype" in it || "app-search" in it }) hit("swiftype")
        if (allScriptText.any { "elasticsearch" in it || "opensearch-js" in it }) hit("elasticsearch")

        return found.toList()
    }

    /**
     * The search routes implied by the detected stacks, best first, followed by
     * their JSON routes. Returns relative routes carrying {query} (and {page}).
     */
    fun routesFor(detected: Collection<String>): List<String> {
        val html = linkedSetOf<String>()
        val jsonRoutes = linkedSetOf<String>()
        for (id in detected) {
            val signature = BY_ID[id] ?: continue
            html += signature.routes
            jsonRoutes += signature.jsonRoutes
        }
        return (html + jsonRoutes).toList()
    }

    /** Autocomplete/suggest routes for the detected stacks — recorded apart from search. */
    fun suggestRoutesFor(detected: Collection<String>): List<String> =
        detected.mapNotNull { BY_ID[it] }.flatMap { it.suggestRoutes }.distinct()

    fun labelFor(id: String): String = BY_ID[id]?.label ?: id

    // ── Hosted search services (credentials published by the page) ────

    private val ALGOLIA_APP = Regex("""["'`](?:algolia)?[aA]pp(?:lication)?_?[iI][dD]["'`]?\s*[:=]\s*["'`]([A-Z0-9]{8,16})["'`]""")
    private val ALGOLIA_APP2 = Regex("""algolia[^{}\n]{0,80}?["'`]([A-Z0-9]{10})["'`]""")
    private val ALGOLIA_KEY = Regex("""["'`](?:algolia)?(?:[sS]earch)?_?[aA]pi_?[kK]ey["'`]?\s*[:=]\s*["'`]([a-f0-9]{32})["'`]""")
    private val ALGOLIA_INDEX = Regex("""["'`]index(?:_?[nN]ame)?["'`]?\s*[:=]\s*["'`]([A-Za-z0-9_\-.]{2,60})["'`]""")
    private val TYPESENSE_HOST = Regex("""["'`]([a-z0-9-]+\.a1\.typesense\.net)["'`]""")
    private val TYPESENSE_KEY = Regex("""[aA]pi_?[kK]ey["'`]?\s*[:=]\s*["'`]([A-Za-z0-9]{10,60})["'`]""")
    private val MEILI_HOST = Regex("""["'`](https?://[A-Za-z0-9.\-]+(?::\d+)?)["'`][^{}\n]{0,80}meilisearch""", RegexOption.IGNORE_CASE)
    private val SEARCHANISE_KEY = Regex("""[sS]earchanise[^{}\n]{0,80}?["'`]([0-9A-Za-z]{8,24})["'`]""")
    private val DOOFINDER_HASH = Regex("""hashid["'`]?\s*[:=]\s*["'`]([a-f0-9]{32})["'`]""")
    private val DOOFINDER_ZONE = Regex("""["'`](eu1|us1)["'`]""")
    private val KLEVU_KEY = Regex("""(klevu-[0-9]{10,20})""")
    private val SWIFTYPE_KEY = Regex("""(?:engineKey|swiftype[_-]?key)["'`]?\s*[:=]\s*["'`]([A-Za-z0-9_\-]{8,40})["'`]""")

    /**
     * Mine the page's own scripts for a hosted search service's public
     * credentials and build the query request that service takes. The site
     * published these values for its own browser-side search, so using them is
     * replaying the site's own search — but they are still only candidates and
     * must pass live verification.
     */
    fun extractHostedSearch(scripts: List<String>): List<HostedSearch> {
        val out = mutableListOf<HostedSearch>()
        val blob = scripts.joinToString("\n").take(3_000_000)

        // Algolia
        val appId = ALGOLIA_APP.find(blob)?.groupValues?.get(1) ?: ALGOLIA_APP2.find(blob)?.groupValues?.get(1)
        val apiKey = ALGOLIA_KEY.find(blob)?.groupValues?.get(1)
        if (appId != null && apiKey != null) {
            val indexes = ALGOLIA_INDEX.findAll(blob).map { it.groupValues[1] }
                .filter { !it.startsWith("http") }.distinct().take(3).toList()
            for (index in indexes.ifEmpty { listOf("") }) {
                if (index.isBlank()) continue
                out += HostedSearch(
                    service = "algolia",
                    url = "https://$appId-dsn.algolia.net/1/indexes/$index/query",
                    method = "POST",
                    body = """{"params":"query={query}&hitsPerPage=20&page={page0}"}""",
                    headers = mapOf(
                        "X-Algolia-Application-Id" to appId,
                        "X-Algolia-API-Key" to apiKey,
                        "Content-Type" to "application/json"
                    ),
                    evidence = "Algolia app $appId, index $index and a search-only key published by the site's own scripts"
                )
            }
        }

        // Typesense
        val tsHost = TYPESENSE_HOST.find(blob)?.groupValues?.get(1)
        if (tsHost != null) {
            val key = TYPESENSE_KEY.find(blob)?.groupValues?.get(1)
            if (key != null) {
                out += HostedSearch(
                    service = "typesense",
                    url = "https://$tsHost/collections/products/documents/search?q={query}&query_by=name&page={page}",
                    method = "GET",
                    headers = mapOf("X-TYPESENSE-API-KEY" to key),
                    evidence = "Typesense host $tsHost and search key published by the site's own scripts"
                )
            }
        }

        // Meilisearch
        MEILI_HOST.find(blob)?.groupValues?.get(1)?.let { host ->
            out += HostedSearch(
                service = "meilisearch",
                url = "$host/indexes/products/search",
                method = "POST",
                body = """{"q":"{query}","limit":20}""",
                headers = mapOf("Content-Type" to "application/json"),
                evidence = "Meilisearch host $host named by the site's own scripts"
            )
        }

        // Searchanise
        SEARCHANISE_KEY.find(blob)?.groupValues?.get(1)?.let { key ->
            out += HostedSearch(
                service = "searchanise",
                url = "https://searchserverapi.com/getresults?api_key=$key&q={query}&maxResults=20&page={page}&output=json",
                method = "GET",
                evidence = "Searchanise api_key $key published by the site's own scripts"
            )
        }

        // Doofinder
        DOOFINDER_HASH.find(blob)?.groupValues?.get(1)?.let { hash ->
            val zone = DOOFINDER_ZONE.find(blob)?.groupValues?.get(1) ?: "eu1"
            out += HostedSearch(
                service = "doofinder",
                url = "https://$zone-search.doofinder.com/5/search?hashid=$hash&query={query}&page={page}&rpp=20",
                method = "GET",
                evidence = "Doofinder hashid $hash published by the site's own scripts"
            )
        }

        // Klevu
        KLEVU_KEY.find(blob)?.groupValues?.get(1)?.let { key ->
            out += HostedSearch(
                service = "klevu",
                url = "https://eucs30v2.ksearchnet.com/cs/v2/search",
                method = "POST",
                body = """{"context":{"apiKeys":["$key"]},"recordQueries":[{"id":"q","typeOfRequest":"SEARCH","settings":{"query":{"term":"{query}"},"limit":20}}]}""",
                headers = mapOf("Content-Type" to "application/json"),
                evidence = "Klevu api key $key published by the site's own scripts"
            )
        }

        // Swiftype / Elastic App Search
        SWIFTYPE_KEY.find(blob)?.groupValues?.get(1)?.let { key ->
            out += HostedSearch(
                service = "swiftype",
                url = "https://search-api.swiftype.com/api/v1/public/engines/search.json?engine_key=$key&q={query}&page={page}",
                method = "GET",
                evidence = "Swiftype engine key $key published by the site's own scripts"
            )
        }

        return out.distinctBy { it.url }.take(MAX_HOSTED)
    }

    private const val MAX_HOSTED = 5
    private val GENERATOR_META =
        Regex("""<meta[^>]+name=["']generator["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
}
