package com.aggregatorx.app.engine.analyzer

import android.util.Log
import com.aggregatorx.app.engine.network.HttpTransport
import com.aggregatorx.app.engine.network.TransportRequest
import kotlinx.serialization.Serializable
import java.io.File
import java.net.URI

/**
 * The indexer-definition knowledge base (spec §1 recon, red-team tooling).
 *
 * Jackett's Cardigann YAML definitions and Prowlarr's fork of them describe,
 * for hundreds of real indexer and tracker sites, exactly what this app
 * otherwise has to discover: the search path, its parameters, the result-row
 * selectors, the title/link fields and the pagination rule. When the site being
 * analyzed is one of those sites, that definition is a far better starting
 * point than any amount of probing.
 *
 * How it works, and what it deliberately does NOT do:
 *
 *  - Nothing is bundled in the APK. The definitions are fetched on demand from
 *    raw.githubusercontent.com through [HttpTransport], so APK size is
 *    unaffected. (These two repositories are the named knowledge sources the
 *    build allows off-host; every other fetch stays same-host.)
 *  - A host index (host → definition file) is fetched once and cached on disk
 *    with a TTL, then a single matching definition is fetched and cached.
 *  - A matched definition is translated into a [DefinitionMatch] that becomes a
 *    HIGH-priority candidate. It is still only a candidate: it must pass the
 *    same live verification as everything else before any config is saved.
 *  - Definitions that need a login are matched and recorded, but flagged, since
 *    an unauthenticated probe of a private tracker cannot verify.
 */
object IndexerDefinitionLibrary {

    private const val TAG = "IndexerDefinitions"

    /** What a matched Cardigann definition says about this site's search. */
    @Serializable
    data class DefinitionMatch(
        val indexerId: String,
        val indexerName: String,
        val source: String,
        val definitionUrl: String,
        /** Search path relative to the site root, e.g. "torrents.php". */
        val searchPath: String,
        /** Search URL template with {query} (and {page} when the definition paginates). */
        val urlTemplate: String,
        val method: String = "GET",
        val postBody: String? = null,
        /** The parameter the search term travels in. */
        val queryParam: String?,
        /** Fixed inputs the definition always sends. */
        val fixedParams: Map<String, String> = emptyMap(),
        val rowsSelector: String? = null,
        val titleSelector: String? = null,
        val linkSelector: String? = null,
        /** True when the definition declares a login block. */
        val requiresLogin: Boolean = false,
        val evidence: String = ""
    )

    /** Where the two definition sets live. */
    private data class Repo(
        val id: String,
        val treeApi: String,
        val rawBase: String
    )

    private val REPOS = listOf(
        Repo(
            "jackett",
            "https://api.github.com/repos/Jackett/Jackett/git/trees/master?recursive=1",
            "https://raw.githubusercontent.com/Jackett/Jackett/master/"
        ),
        Repo(
            "prowlarr",
            "https://api.github.com/repos/Prowlarr/Indexers/git/trees/master?recursive=1",
            "https://raw.githubusercontent.com/Prowlarr/Indexers/master/"
        )
    )

    @Volatile private var cacheDir: File? = null

    fun configure(dir: File) {
        cacheDir = File(dir, "indexer-defs").apply { runCatching { mkdirs() } }
    }

    /**
     * Find a definition whose `links`/`legacylinks` include [host].
     *
     * Bounded: one index fetch (cached for [INDEX_TTL_MS]) and at most
     * [MAX_DEFINITION_FETCHES] definition fetches, all inside [deadlineAt].
     * Returns null when nothing matches, when the lookup is out of budget, or
     * when the sources cannot be reached — never throws.
     */
    fun lookup(host: String, deadlineAt: Long): DefinitionMatch? {
        val bare = host.removePrefix("www.").lowercase()
        if (bare.isBlank()) return null
        return try {
            for (repo in REPOS) {
                if (System.currentTimeMillis() > deadlineAt - 3_000) return null
                val paths = definitionPaths(repo, deadlineAt) ?: continue
                // Match on the file name first (fast, no fetch): most
                // definitions are named after the site's second-level label.
                val label = bare.substringBefore('.')
                val ranked = paths.filter { path ->
                    val name = path.substringAfterLast('/').substringBeforeLast('.')
                        .lowercase().replace("-", "").replace("_", "")
                    val target = label.replace("-", "").replace("_", "")
                    name == target || name.startsWith(target) || target.startsWith(name)
                }.take(MAX_DEFINITION_FETCHES)
                for (path in ranked) {
                    if (System.currentTimeMillis() > deadlineAt - 2_000) return null
                    val yaml = definitionBody(repo, path, deadlineAt) ?: continue
                    if (!declaresHost(yaml, bare)) continue
                    translate(yaml, repo, path)?.let { return it }
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "definition lookup for $bare failed: ${e.message}")
            null
        }
    }

    // ── Index of definition file paths, cached on disk ────────────────

    private fun definitionPaths(repo: Repo, deadlineAt: Long): List<String>? {
        val cached = readCache("${repo.id}-index.txt", INDEX_TTL_MS)
        if (cached != null) return cached.lineSequence().filter { it.isNotBlank() }.toList()
        val response = fetch(repo.treeApi, INDEX_TIMEOUT_MS, deadlineAt, json = true) ?: return null
        if (!response.isHttpOk) return null
        // The tree API answers one big JSON blob; the paths are all we need.
        val paths = DEFINITION_PATH.findAll(response.body)
            .map { it.groupValues[1] }
            .filter { it.endsWith(".yml", true) || it.endsWith(".yaml", true) }
            .distinct()
            .toList()
        if (paths.isEmpty()) return null
        writeCache("${repo.id}-index.txt", paths.joinToString("\n"))
        Log.i(TAG, "${repo.id}: indexed ${paths.size} definition files")
        return paths
    }

    private fun definitionBody(repo: Repo, path: String, deadlineAt: Long): String? {
        val key = "${repo.id}-" + path.substringAfterLast('/')
        readCache(key, DEFINITION_TTL_MS)?.let { return it }
        val response = fetch(repo.rawBase + path, DEFINITION_TIMEOUT_MS, deadlineAt) ?: return null
        if (!response.isHttpOk || response.body.isBlank()) return null
        writeCache(key, response.body)
        return response.body
    }

    private fun fetch(url: String, timeoutMs: Int, deadlineAt: Long, json: Boolean = false) =
        if (System.currentTimeMillis() > deadlineAt - 1_000) null
        else runCatching {
            HttpTransport.fetchBlocking(
                TransportRequest(
                    url = url,
                    headers = HttpTransport.defaultHeaders(json),
                    timeoutMs = timeoutMs.coerceAtMost((deadlineAt - System.currentTimeMillis()).toInt().coerceAtLeast(1_000))
                )
            )
        }.getOrNull()

    // ── Minimal Cardigann YAML reading ────────────────────────────────

    /** True when the definition's links/legacylinks name this host. */
    private fun declaresHost(yaml: String, host: String): Boolean {
        val linksBlock = SECTION.find(yaml)?.value ?: yaml
        return LINK_URL.findAll(linksBlock).any { match ->
            runCatching { URI(match.groupValues[1].trim()).host?.removePrefix("www.")?.lowercase() }
                .getOrNull() == host
        }
    }

    /**
     * Translate the definition's search block into a candidate. Cardigann's
     * grammar is large; this reads the parts that map onto a ScrapingConfig and
     * ignores the rest (its templating language, category maps, login flows).
     * An unreadable definition yields null rather than a guess.
     */
    private fun translate(yaml: String, repo: Repo, path: String): DefinitionMatch? {
        val id = scalar(yaml, "id") ?: path.substringAfterLast('/').substringBeforeLast('.')
        val name = scalar(yaml, "name") ?: id
        val searchBlock = SEARCH_BLOCK.find(yaml)?.value ?: return null
        val searchPath = (scalar(searchBlock, "path") ?: PATHS_FIRST.find(searchBlock)?.groupValues?.get(1))
            ?.trim()?.trim('"', '\'') ?: return null
        if (searchPath.isBlank() || searchPath.contains("{{") && searchPath.contains("range")) return null

        val inputsBlock = INPUTS_BLOCK.find(searchBlock)?.value.orEmpty()
        val inputs = INPUT_PAIR.findAll(inputsBlock)
            .map { it.groupValues[1].trim() to it.groupValues[2].trim().trim('"', '\'') }
            .toList()

        // The input whose value is the keywords template is the query parameter.
        val queryEntry = inputs.firstOrNull { (_, value) ->
            value.contains(".Keywords") || value.contains(".Query.Keywords")
        }
        val queryParam = queryEntry?.first
        val fixed = inputs.filter { it !== queryEntry && !it.second.contains("{{") }
            .associate { it.first to it.second }
        val pageEntry = inputs.firstOrNull { (name2, _) ->
            name2.lowercase() in setOf("page", "paged", "pagenum", "p")
        }

        val method = (scalar(searchBlock, "method") ?: "get").trim().lowercase()
        val isPost = method == "post"

        // Build the template. Without a keywords input the definition drives
        // search through its path template, which this reader does not execute:
        // such a definition is recorded but cannot produce a usable template.
        if (queryParam == null) return null

        val cleanPath = "/" + searchPath.trimStart('/')
        val pairs = buildList {
            add("$queryParam={query}")
            fixed.forEach { (k, v) -> add("$k=$v") }
            pageEntry?.let { add("${it.first}={page}") }
        }
        val urlTemplate: String
        val postBody: String?
        if (isPost) {
            urlTemplate = "{baseUrl}$cleanPath"
            postBody = pairs.joinToString("&")
        } else {
            val sep = if (cleanPath.contains('?')) "&" else "?"
            urlTemplate = "{baseUrl}$cleanPath$sep" + pairs.joinToString("&")
            postBody = null
        }

        val rowsBlock = ROWS_BLOCK.find(searchBlock)?.value.orEmpty()
        val rows = scalar(rowsBlock, "selector")
        val fieldsBlock = FIELDS_BLOCK.find(searchBlock)?.value.orEmpty()
        val title = fieldSelector(fieldsBlock, "title")
        val link = fieldSelector(fieldsBlock, "details") ?: fieldSelector(fieldsBlock, "download")

        return DefinitionMatch(
            indexerId = id,
            indexerName = name,
            source = repo.id,
            definitionUrl = repo.rawBase + path,
            searchPath = cleanPath,
            urlTemplate = urlTemplate,
            method = if (isPost) "POST" else "GET",
            postBody = postBody,
            queryParam = queryParam,
            fixedParams = fixed,
            rowsSelector = rows,
            titleSelector = title,
            linkSelector = link,
            requiresLogin = yaml.contains("\nlogin:"),
            evidence = "matched the ${repo.id} Cardigann definition \"$name\" ($path): " +
                "search path $cleanPath, query parameter $queryParam" +
                (if (rows != null) ", rows selector $rows" else "") +
                (if (yaml.contains("\nlogin:")) " — the definition declares a login, so an unauthenticated probe may not verify" else "")
        )
    }

    private fun scalar(block: String, key: String): String? =
        Regex("(?m)^\\s*" + Regex.escape(key) + ":\\s*(.+)$").find(block)
            ?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.takeIf { it.isNotBlank() && !it.startsWith("|") }

    private fun fieldSelector(fieldsBlock: String, field: String): String? =
        Regex("(?m)^\\s{2,}" + Regex.escape(field) + ":\\s*\\n(?:\\s+.*\\n)*?\\s+selector:\\s*(.+)$")
            .find(fieldsBlock)?.groupValues?.get(1)?.trim()?.trim('"', '\'')

    // ── Disk cache ────────────────────────────────────────────────────

    private fun readCache(name: String, ttlMs: Long): String? {
        val file = File(cacheDir ?: return null, name)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > ttlMs) return null
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun writeCache(name: String, content: String) {
        val dir = cacheDir ?: return
        runCatching {
            dir.mkdirs()
            File(dir, name).writeText(content)
        }
    }

    private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val DEFINITION_TTL_MS = 7L * 24 * 60 * 60 * 1000
    private const val INDEX_TIMEOUT_MS = 12_000
    private const val DEFINITION_TIMEOUT_MS = 8_000
    private const val MAX_DEFINITION_FETCHES = 4

    private val DEFINITION_PATH = Regex("\"path\"\\s*:\\s*\"((?:src/Jackett\\.Common/Definitions|definitions/v\\d+)/[^\"]+)\"")
    private val SECTION = Regex("(?s)(?:^|\\n)(?:legacy)?links:.*?(?=\\n[a-z]+:)")
    private val LINK_URL = Regex("-\\s*(https?://[^\\s\"']+)")
    private val SEARCH_BLOCK = Regex("(?s)\\nsearch:\\n.*?(?=\\n[a-z]+:\\n|\\z)")
    private val PATHS_FIRST = Regex("(?m)^\\s*-\\s*path:\\s*(.+)$")
    private val INPUTS_BLOCK = Regex("(?s)\\n\\s+inputs:\\n.*?(?=\\n\\s{0,4}[a-z]+:|\\z)")
    private val INPUT_PAIR = Regex("(?m)^\\s+([A-Za-z0-9_\\[\\]\\-.]+):\\s*(.+)$")
    private val ROWS_BLOCK = Regex("(?s)\\n\\s+rows:\\n.*?(?=\\n\\s{2,4}[a-z]+:|\\z)")
    private val FIELDS_BLOCK = Regex("(?s)\\n\\s+fields:\\n.*\\z")
}
