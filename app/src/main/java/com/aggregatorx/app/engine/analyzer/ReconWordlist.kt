package com.aggregatorx.app.engine.analyzer

/**
 * The ffuf / gobuster / dirsearch wordlist, curated for the one thing this app
 * needs to discover: a site's search route (spec §1).
 *
 * Every entry is a relative route template carrying {query} (and {page} where
 * the route paginates). Entries are probed same-host, rate-limited, with
 * concurrency <= 3 and against a soft-404 baseline, and a hit only becomes a
 * candidate — never a saved config.
 *
 * Order matters: the toolkit probes fingerprint-prioritized routes first
 * ([StackSignatures.routesFor]), then [GENERIC] in this order, then [JSON_API],
 * then [FEEDS]. [SUGGEST] is probed separately and recorded as autocomplete,
 * never offered as a search candidate.
 */
object ReconWordlist {

    /** HTML search routes, commonest first. */
    val GENERIC: List<String> = listOf(
        // Plain /search with every common parameter name
        "/search?q={query}&page={page}",
        "/search?query={query}&page={page}",
        "/search?s={query}&page={page}",
        "/search?keyword={query}&page={page}",
        "/search?keywords={query}&page={page}",
        "/search?term={query}&page={page}",
        "/search?text={query}&page={page}",
        "/search?k={query}&page={page}",
        "/search?w={query}",
        "/search?wd={query}",
        "/search?name={query}&page={page}",
        "/search?title={query}&page={page}",
        "/search?search={query}&page={page}",
        "/search?search_query={query}&page={page}",
        "/search?searchword={query}",
        "/search?searchterm={query}",
        "/search?find={query}",
        "/search?qry={query}",
        "/search?keys={query}",
        // Root-level query parameters
        "/?s={query}&paged={page}",
        "/?q={query}&page={page}",
        "/?query={query}&page={page}",
        "/?search={query}&page={page}",
        "/?keyword={query}",
        "/?k={query}",
        "/?term={query}",
        "/?name={query}",
        // Path-style search
        "/search/{query}",
        "/search/{query}/page/{page}",
        "/search/{query}/{page}",
        "/s/{query}",
        "/q/{query}",
        "/find/{query}",
        "/browse/{query}",
        "/tag/{query}",
        "/results/{query}",
        // Alternative route names
        "/results?q={query}&page={page}",
        "/results?search_query={query}&page={page}",
        "/find?q={query}&page={page}",
        "/find?query={query}",
        "/finder?q={query}",
        "/lookup?q={query}",
        "/query?q={query}",
        "/explore?q={query}",
        "/discover?q={query}",
        "/filter?q={query}&page={page}",
        "/all?q={query}",
        "/list?q={query}&page={page}",
        "/index?s={query}",
        "/home?q={query}",
        // Script-extension routes (PHP/ASP/JSP shops and forums)
        "/search.php?q={query}&page={page}",
        "/search.php?query={query}",
        "/search.php?s={query}",
        "/search.php?keywords={query}",
        "/search.php?search_query={query}",
        "/search.php?keyword={query}&page={page}",
        "/search.php?do=process&query={query}",
        "/search.php?action=do_search&keywords={query}",
        "/search.html?q={query}",
        "/search.htm?q={query}",
        "/search.asp?q={query}",
        "/search.aspx?q={query}&page={page}",
        "/search.aspx?k={query}",
        "/search.jsp?q={query}",
        "/search.do?q={query}",
        "/search.action?q={query}",
        "/searchresults.aspx?q={query}",
        "/index.php?s={query}",
        "/index.php?q={query}",
        "/index.php?search={query}&page={page}",
        "/index.php?searchword={query}",
        "/index.php?option=com_search&searchword={query}",
        "/index.php?option=com_finder&q={query}",
        "/index.php?route=product/search&search={query}&page={page}",
        "/index.php?page=search&q={query}",
        "/index.php?action=search&q={query}",
        "/index.php?m=search&q={query}",
        "/index.php?mod=search&q={query}",
        "/index.php?do=search&subaction=search&story={query}",
        // Commerce platforms
        "/catalogsearch/result/?q={query}&p={page}",
        "/search?q={query}&type=product",
        "/search?q={query}&type=product&page={page}",
        "/search.php?search_query={query}&page={page}",
        "/search?controller=search&s={query}",
        "/recherche?controller=search&s={query}",
        "/shop?s={query}",
        "/products?q={query}&page={page}",
        "/products/search?q={query}",
        "/collections/all?q={query}",
        "/catalog/search?q={query}&page={page}",
        "/store/search?q={query}",
        "/shop/search?q={query}",
        "/goods/search?q={query}",
        // CMS routes
        "/search/node?keys={query}&page={page}",
        "/search/node/{query}",
        "/search/content?search_api_fulltext={query}",
        "/search?search_api_fulltext={query}&page={page}",
        "/component/search/?searchword={query}",
        "/wiki/Special:Search?search={query}&fulltext=1",
        "/index.php?search={query}&fulltext=1",
        "/Special:Search?search={query}&fulltext=1",
        "/page/{page}/?s={query}",
        "/blog?s={query}",
        "/blog/search?q={query}",
        "/news/search?q={query}",
        "/articles?q={query}&page={page}",
        "/posts?q={query}&page={page}",
        // Forums and boards
        "/search/search?keywords={query}",
        "/search/?q={query}&page={page}",
        "/search?term={query}&in=titlesposts",
        "/forum/search?q={query}",
        "/forums/search/?q={query}",
        "/viewforum.php?search={query}",
        "/index.php?app=core&module=search&q={query}",
        "/index.php?action=search2&search={query}",
        // Media / video / streaming conventions
        "/videos?q={query}&page={page}",
        "/video/search?q={query}",
        "/videos/search?q={query}&page={page}",
        "/watch?q={query}",
        "/movies?q={query}&page={page}",
        "/movie/search?q={query}",
        "/series?q={query}",
        "/tv?q={query}",
        "/anime?q={query}&page={page}",
        "/music?q={query}",
        "/album?q={query}",
        "/gallery?q={query}",
        "/photos?q={query}",
        "/images?q={query}&page={page}",
        "/books?q={query}&page={page}",
        "/library/search?q={query}",
        // Torrent / indexer conventions
        "/torrents?name={query}&page={page}",
        "/torrents/filter?name={query}&page={page}",
        "/torrents?search={query}&page={page}",
        "/torrents.php?searchstr={query}&page={page}",
        "/torrents.php?search={query}",
        "/torrent/search/{query}",
        "/torrent-search/{query}",
        "/search/torrents?q={query}",
        "/browse.php?search={query}&page={page}",
        "/browse.php?do=search&keywords={query}",
        "/tracker.php?nm={query}",
        "/torrents-search.php?search={query}",
        "/index.php?page=torrents&search={query}&active=0",
        "/usearch/{query}/",
        "/search/{query}/1/",
        "/sort-search/{query}/size/1/",
        "/search?search_name={query}",
        // Docs / support / knowledge bases
        "/docs/search?q={query}",
        "/help/search?q={query}",
        "/support/search?q={query}",
        "/kb/search?q={query}",
        "/faq?q={query}",
        // Listing and classifieds conventions
        "/listings?q={query}&page={page}",
        "/ads?q={query}",
        "/classifieds?q={query}",
        "/jobs?q={query}&page={page}",
        "/properties?q={query}",
        "/cars?q={query}",
        "/events?q={query}",
        "/directory?q={query}",
        "/members?q={query}",
        "/users?q={query}"
    )

    /** JSON/API search routes — probed with Accept: application/json. */
    val JSON_API: List<String> = listOf(
        "/api/search?q={query}&page={page}",
        "/api/search?query={query}&page={page}",
        "/api/search?term={query}",
        "/api/search?keyword={query}",
        "/api/v1/search?q={query}&page={page}",
        "/api/v2/search?q={query}&page={page}",
        "/api/v3/search?q={query}",
        "/api/search/all?q={query}",
        "/api/search/products?q={query}",
        "/api/products?search={query}&page={page}",
        "/api/products/search?q={query}",
        "/api/posts?search={query}&page={page}",
        "/api/items?q={query}&page={page}",
        "/api/content/search?q={query}",
        "/api/autocomplete?q={query}",
        "/api/query?q={query}",
        "/api/find?q={query}",
        "/search.json?q={query}&page={page}",
        "/search/results.json?q={query}",
        "/results.json?q={query}",
        "/data/search.json?q={query}",
        "/ajax/search?q={query}&page={page}",
        "/ajax/search.php?q={query}",
        "/ajax.php?action=search&q={query}",
        "/ajax.php?action=browse&searchstr={query}&page={page}",
        "/wp-json/wp/v2/search?search={query}&per_page=20&page={page}",
        "/wp-json/wp/v2/posts?search={query}&per_page=20&page={page}",
        "/wp-json/wp/v2/product?search={query}&per_page=20",
        "/wp-json/wc/store/v1/products?search={query}&page={page}",
        "/wp-admin/admin-ajax.php?action=search&s={query}",
        "/jsonapi/node/article?filter[title][operator]=CONTAINS&filter[title][value]={query}",
        "/ghost/api/content/posts/?filter=title:~'{query}'&limit=20",
        "/api.php?action=query&list=search&srsearch={query}&format=json",
        "/w/api.php?action=query&list=search&srsearch={query}&format=json",
        "/api/torrents/filter?name={query}&page={page}",
        "/api?t=search&q={query}&o=json",
        "/_search?q={query}",
        "/api/search/suggest?q={query}",
        "/rest/V1/search?searchCriteria[requestName]=quick_search_container&searchCriteria[filterGroups][0][filters][0][field]=search_term&searchCriteria[filterGroups][0][filters][0][value]={query}",
        "/_next/data/search.json?q={query}",
        "/api/graphql?query={query}",
        "/graphql?query={query}"
    )

    /** Feed routes that accept a search term — a legitimate result source. */
    val FEEDS: List<String> = listOf(
        "/feed?s={query}",
        "/?s={query}&feed=rss2",
        "/rss?q={query}",
        "/rss/search?q={query}",
        "/feed/search?q={query}",
        "/search/feed/rss2?q={query}",
        "/atom?q={query}",
        "/opensearch.xml"
    )

    /**
     * Autocomplete / suggest endpoints. These return fragments, not result
     * pages: they are probed and RECORDED, but never promoted to a search
     * candidate, because mistaking a suggest endpoint for the search endpoint
     * is the classic way to save a config that can never paginate.
     */
    val SUGGEST: List<String> = listOf(
        "/search/suggest.json?q={query}",
        "/autocomplete?q={query}",
        "/api/autocomplete?q={query}",
        "/api/suggest?q={query}",
        "/suggest?q={query}",
        "/ac?q={query}",
        "/api.php?action=opensearch&search={query}&format=json",
        "/index.php?route=product/search/autocomplete&filter_name={query}"
    )

    /** Everything probed in priority order after the fingerprint's own routes. */
    fun all(): List<String> = (GENERIC + JSON_API + FEEDS).distinct()

    /** Routes that should be probed with an Accept: application/json header. */
    fun isJsonRoute(route: String): Boolean =
        route.contains("/api", ignoreCase = true) ||
            route.contains("json", ignoreCase = true) ||
            route.contains("/ajax", ignoreCase = true) ||
            route.contains("graphql", ignoreCase = true) ||
            route.contains("format=json", ignoreCase = true)

    val SIZE: Int = all().size + SUGGEST.size
}
