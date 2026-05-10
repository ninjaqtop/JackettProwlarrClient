package com.aggregatorx.app.engine.media

import android.content.Context
import android.os.Environment
import com.aggregatorx.app.engine.ai.AICodeInjectionEngine
import com.aggregatorx.app.engine.ai.PageContext
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AggravatedX Enhanced Video Extraction Engine
 * 
 * Features:
 * - Direct video link extraction (mp4, webm, etc.)
 * - HLS streams (.m3u8)
 * - DASH streams (.mpd)
 * - Embedded players (YouTube, Vimeo, etc.)
 * - Custom video players
 * - Headless browser fallback with auto-click ad bypass
 * - Shadow DOM traversal
 * - Auto-selects highest quality available
 * - Intelligent fallback chain
 */
@Singleton
class VideoExtractorEngine @Inject constructor() {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
    
    companion object {
        private val USER_AGENT = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT
        
        // Alternate user agents for fallback — delegates to shared pool
        private val ALTERNATE_USER_AGENTS = EngineUtils.USER_AGENTS
        
        // Video file extensions
        private val VIDEO_EXTENSIONS = listOf(
            ".mp4", ".webm", ".mkv", ".avi", ".mov", ".m4v",
            ".flv", ".wmv", ".3gp", ".ts", ".m3u8", ".mpd"
        )
        
        // Quality preferences (highest first)
        private val QUALITY_ORDER = listOf(
            "2160p", "4k", "1080p", "720p", "480p", "360p", "240p"
        )
        
        // Quality keywords with scores
        private val QUALITY_SCORES = mapOf(
            "4k" to 100, "2160" to 100, "2160p" to 100,
            "1080" to 90, "1080p" to 90, "fullhd" to 90, "full hd" to 90,
            "720" to 70, "720p" to 70, "hd" to 70,
            "480" to 50, "480p" to 50, "sd" to 50,
            "360" to 30, "360p" to 30,
            "240" to 20, "240p" to 20
        )
    }
    
    /**
     * Smart video preview extraction - optimized for inline preview playback
     * Tries fast methods first, falls back to headless browser with auto-click if needed
     * Returns just the URL string for preview, or null if extraction fails
     */
    suspend fun extractVideoUrlForPreview(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            // Quick check: Is this a direct video URL already?
            if (VIDEO_EXTENSIONS.any { pageUrl.endsWith(it, ignoreCase = true) }) {
                return@withContext pageUrl
            }
            
            // Also check if the URL responds with a video content-type (handles CDN URLs without extensions)
            val directCheck = verifyVideoUrl(pageUrl)
            if (directCheck) {
                return@withContext pageUrl
            }
            
            // Try fast HTML extraction first (no headless browser needed)
            val fastResult = extractVideoUrlFast(pageUrl)
            if (fastResult != null) {
                return@withContext fastResult
            }
            
            // Fall back to full extraction with headless browser + auto-click
            val fullResult = extractVideoUrl(pageUrl)
            if (fullResult.success && fullResult.videoUrl != null) {
                return@withContext fullResult.videoUrl
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Perform a HEAD request to check if a URL serves video content.
     * Returns true if the Content-Type is a video MIME type.
     */
    private fun verifyVideoUrl(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .build()
            val response = httpClient.newCall(request).execute()
            val ct = response.header("Content-Type")?.lowercase() ?: ""
            response.close()
            ct.contains("video") || ct.contains("mpegurl") || ct.contains("dash+xml") || ct.contains("octet-stream")
        } catch (_: Exception) {
            false
        }
    }
    
    /**
     * Fast video extraction without headless browser
     * Used for sites that don't require JavaScript rendering
     */
    private suspend fun extractVideoUrlFast(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val document = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(8000)
                .followRedirects(true)
                .ignoreHttpErrors(true)
                .get()
            
            // Try quick extraction methods
            val allVideos = mutableListOf<VideoUrlInfo>()
            
            extractFromVideoTag(document, pageUrl)?.let { allVideos.add(it) }
            extractFromSourceTag(document, pageUrl)?.let { allVideos.add(it) }
            extractFromScripts(document, pageUrl)?.let { allVideos.add(it) }
            extractFromDataAttributes(document, pageUrl)?.let { allVideos.add(it) }
            extractFromSsrState(document, pageUrl)?.let { allVideos.add(it) }
            extractFromMetaTags(document, pageUrl)?.let { allVideos.add(it) }
            extractFromJsonLd(document, pageUrl)?.let { allVideos.add(it) }
            
            if (allVideos.isNotEmpty()) {
                return@withContext selectHighestQuality(allVideos).url
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Extract video URL from a content page - tries multiple methods
     * Auto-selects the highest quality available
     * Uses headless browser with auto-click ad bypass for JS-heavy sites
     * ENHANCED: Retries with alternate user agents, more extraction patterns
     */
    suspend fun extractVideoUrl(pageUrl: String): VideoExtractionResult = withContext(Dispatchers.IO) {
        try {
            // ── Host-specific fast-path extractors (bypass generic scraping) ──
            val hostResult = extractFromKnownHost(pageUrl)
            if (hostResult != null) return@withContext hostResult

            // First try standard HTML parsing
            val document = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(15000)
                .followRedirects(true)
                .get()
            
            // Try multiple extraction methods and collect all videos
            val allVideos = mutableListOf<VideoUrlInfo>()
            
            extractFromVideoTag(document, pageUrl)?.let { allVideos.add(it) }
            extractFromSourceTag(document, pageUrl)?.let { allVideos.add(it) }
            extractFromIframe(document, pageUrl)?.let { allVideos.add(it) }
            extractFromScripts(document, pageUrl)?.let { allVideos.add(it) }
            extractFromDataAttributes(document, pageUrl)?.let { allVideos.add(it) }
            extractFromJsonLd(document, pageUrl)?.let { allVideos.add(it) }
            extractFromMetaTags(document, pageUrl)?.let { allVideos.add(it) }
            extractFromSsrState(document, pageUrl)?.let { allVideos.add(it) }
            
            // If standard parsing found videos, select best quality
            if (allVideos.isNotEmpty()) {
                val bestVideo = selectHighestQuality(allVideos)
                return@withContext VideoExtractionResult(
                    success = true,
                    videoUrl = bestVideo.url,
                    quality = bestVideo.quality,
                    format = bestVideo.format,
                    isStream = bestVideo.isStream
                )
            }
            
            // Try with alternate user agents (some providers block certain UAs)
            for (ua in ALTERNATE_USER_AGENTS) {
                try {
                    val altDoc = Jsoup.connect(pageUrl)
                        .userAgent(ua)
                        .timeout(12000)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .get()
                    
                    val altVideos = mutableListOf<VideoUrlInfo>()
                    extractFromVideoTag(altDoc, pageUrl)?.let { altVideos.add(it) }
                    extractFromSourceTag(altDoc, pageUrl)?.let { altVideos.add(it) }
                    extractFromScripts(altDoc, pageUrl)?.let { altVideos.add(it) }
                    extractFromDataAttributes(altDoc, pageUrl)?.let { altVideos.add(it) }
                    extractFromSsrState(altDoc, pageUrl)?.let { altVideos.add(it) }
                    
                    if (altVideos.isNotEmpty()) {
                        val bestVideo = selectHighestQuality(altVideos)
                        return@withContext VideoExtractionResult(
                            success = true,
                            videoUrl = bestVideo.url,
                            quality = bestVideo.quality,
                            format = bestVideo.format,
                            isStream = bestVideo.isStream
                        )
                    }
                } catch (e: Exception) {
                    continue
                }
            }
            
            // Fallback to headless browser with auto-click ad bypass
            val headlessResult = extractWithHeadlessBrowser(pageUrl)
            if (headlessResult != null) {
                return@withContext headlessResult
            }
            
            VideoExtractionResult(
                success = false,
                error = "Could not extract video URL from any source"
            )
        } catch (e: Exception) {
            // Try headless browser as last resort
            try {
                val headlessResult = extractWithHeadlessBrowser(pageUrl)
                if (headlessResult != null) {
                    return@withContext headlessResult
                }
            } catch (_: Exception) {}
            
            VideoExtractionResult(
                success = false,
                error = e.message ?: "Extraction failed"
            )
        }
    }
    
    /**
     * Extract video using headless browser with auto-click ad bypass
     * AND AI code injection for advanced extraction.
     * This handles JavaScript-heavy sites and automatically clicks through ads/popups
     */
    private suspend fun extractWithHeadlessBrowser(pageUrl: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val domain = try { URL(pageUrl).host } catch (_: Exception) { "" }

            // ── AI Code Injection phase ──────────────────────────────
            // Get a domain-aware injection plan and run it before extraction
            val injectionEngine = AICodeInjectionEngine()
            val injectionPlan = injectionEngine.getInjectionPlan(domain, PageContext.VIDEO_PLAYER)

            // First pass: fetch with shadow DOM + ad skip (standard)
            val pageContent = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                url = pageUrl,
                waitSelector = "video, source, [data-video-url], iframe[src*='player']",
                timeout = 20000
            )

            if (pageContent.isNullOrEmpty()) {
                return@withContext null
            }

            val document = Jsoup.parse(pageContent, pageUrl)
            val allVideos = mutableListOf<VideoUrlInfo>()

            // Standard extraction from rendered content
            extractFromVideoTag(document, pageUrl)?.let { allVideos.add(it) }
            extractFromSourceTag(document, pageUrl)?.let { allVideos.add(it) }
            extractFromScripts(document, pageUrl)?.let { allVideos.add(it) }
            extractFromDataAttributes(document, pageUrl)?.let { allVideos.add(it) }

            // ── AI-injected extraction via headless page ─────────────
            // Open a new page and run the injection scripts to reveal
            // obfuscated/lazy-loaded video sources
            try {
                val page = HeadlessBrowserHelper.createAntiDetectionPage()
                page.navigate(pageUrl)
                page.waitForLoadState()

                // Execute each injection step from the AI plan
                for (step in injectionPlan.steps) {
                    try {
                        val jsCode = injectionEngine.getTemplateCode(step.templateId) ?: step.code.ifEmpty { null } ?: continue
                        val result = page.evaluate(jsCode)

                        // Parse extracted URLs from the injection result
                        val urls = parseInjectionResult(result)
                        urls.forEach { url ->
                            if (url.isNotEmpty() && VIDEO_EXTENSIONS.any { ext -> url.contains(ext, ignoreCase = true) }) {
                                allVideos.add(VideoUrlInfo(
                                    url = url,
                                    quality = detectQuality(url),
                                    format = detectFormat(url),
                                    isStream = url.contains(".m3u8") || url.contains(".mpd")
                                ))
                            }
                        }

                        // Learn from success/failure
                        if (urls.isNotEmpty()) {
                            injectionEngine.recordSuccess(domain, step.templateId, urls.size)
                        }
                    } catch (e: Exception) {
                        injectionEngine.recordFailure(domain, step.templateId, e.message ?: "execution error")
                    }
                }

                page.close()
            } catch (_: Exception) { /* AI injection is best-effort */ }

            // Also try dedicated headless video URL extraction
            val headlessBrowserVideos = HeadlessBrowserHelper.extractVideoUrls(pageUrl)
            headlessBrowserVideos.forEach { url ->
                allVideos.add(VideoUrlInfo(
                    url = url,
                    quality = detectQuality(url),
                    format = detectFormat(url),
                    isStream = url.contains(".m3u8") || url.contains(".mpd")
                ))
            }
            
            if (allVideos.isEmpty()) {
                return@withContext null
            }
            
            val bestVideo = selectHighestQuality(allVideos)
            VideoExtractionResult(
                success = true,
                videoUrl = bestVideo.url,
                quality = bestVideo.quality,
                format = bestVideo.format,
                isStream = bestVideo.isStream
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Parse the result returned by an injected JS snippet into a list of URLs. */
    private fun parseInjectionResult(result: Any?): List<String> {
        if (result == null) return emptyList()
        return when (result) {
            is String -> if (result.isNotBlank()) listOf(result) else emptyList()
            is List<*> -> result.filterIsInstance<String>().filter { it.isNotBlank() }
            is Map<*, *> -> {
                // Handle { urls: [...] } or { src: "..." } shaped objects
                val urls = mutableListOf<String>()
                result.values.forEach { v ->
                    when (v) {
                        is String -> if (v.isNotBlank()) urls.add(v)
                        is List<*> -> urls.addAll(v.filterIsInstance<String>())
                    }
                }
                urls
            }
            else -> {
                val str = result.toString()
                if (str.startsWith("http") || str.contains(".m3u8") || str.contains(".mp4")) listOf(str)
                else emptyList()
            }
        }
    }
    
    /**
     * Select highest quality video from available options
     */
    private fun selectHighestQuality(videos: List<VideoUrlInfo>): VideoUrlInfo {
        if (videos.isEmpty()) throw IllegalArgumentException("No videos to select from")
        if (videos.size == 1) return videos.first()
        
        return videos.maxByOrNull { video ->
            val qualityScore = QUALITY_SCORES.entries
                .filter { video.quality.lowercase().contains(it.key) || video.url.lowercase().contains(it.key) }
                .maxOfOrNull { it.value } ?: 0
            
            // Prefer non-stream formats for downloads
            val formatBonus = when {
                video.format == "mp4" -> 10
                video.format == "webm" -> 5
                video.isStream -> -5
                else -> 0
            }
            
            qualityScore + formatBonus
        } ?: videos.first()
    }
    
    /**
     * Extract from <video> tag
     */
    private fun extractFromVideoTag(document: Document, baseUrl: String): VideoUrlInfo? {
        val video = document.select("video").firstOrNull() ?: return null
        
        val src = video.attr("src").takeIf { it.isNotEmpty() }
            ?: video.attr("data-src").takeIf { it.isNotEmpty() }
        
        if (src != null) {
            return VideoUrlInfo(
                url = normalizeUrl(src, baseUrl),
                quality = detectQuality(src),
                format = detectFormat(src),
                isStream = src.contains(".m3u8") || src.contains(".mpd")
            )
        }
        
        return null
    }
    
    /**
     * Extract from <source> tags
     */
    private fun extractFromSourceTag(document: Document, baseUrl: String): VideoUrlInfo? {
        val sources = document.select("video source, source[type*='video']")
        // Always select the highest quality available
        val sortedSources = sources.sortedWith(compareBy({
            val src = it.attr("src")
            val label = it.attr("label") ?: it.attr("data-label") ?: ""
            QUALITY_ORDER.indexOfFirst { q -> src.contains(q, ignoreCase = true) || label.contains(q, ignoreCase = true) }
        }, {
            // Prefer mp4 over others if quality is equal
            val src = it.attr("src")
            if (src.endsWith(".mp4")) 0 else 1
        }))
        sortedSources.firstOrNull { it.attr("src").isNotEmpty() }?.let { source ->
            val src = source.attr("src")
            return VideoUrlInfo(
                url = normalizeUrl(src, baseUrl),
                quality = detectQuality(src),
                format = detectFormat(src),
                isStream = src.contains(".m3u8") || src.contains(".mpd")
            )
        }
        return null
    }
    
    /**
     * Extract from iframe embeds
     */
    private suspend fun extractFromIframe(document: Document, baseUrl: String): VideoUrlInfo? {
        val iframes = document.select("iframe[src]")
        
        for (iframe in iframes) {
            val src = iframe.attr("src")
            
            // YouTube
            if (src.contains("youtube.com") || src.contains("youtu.be")) {
                return VideoUrlInfo(
                    url = src,
                    quality = "HD",
                    format = "youtube",
                    isStream = true,
                    isEmbed = true
                )
            }
            
            // Vimeo
            if (src.contains("vimeo.com")) {
                return VideoUrlInfo(
                    url = src,
                    quality = "HD",
                    format = "vimeo",
                    isStream = true,
                    isEmbed = true
                )
            }
            
            // Try to extract from embed page
            if (src.contains("embed") || src.contains("player")) {
                try {
                    val embedDoc = Jsoup.connect(normalizeUrl(src, baseUrl))
                        .userAgent(USER_AGENT)
                        .timeout(15000)
                        .get()
                    
                    val videoUrl = extractFromVideoTag(embedDoc, src)
                        ?: extractFromSourceTag(embedDoc, src)
                        ?: extractFromScripts(embedDoc, src)
                    
                    if (videoUrl != null) return videoUrl
                } catch (e: Exception) {
                    continue
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract video URLs from JavaScript
     */
    private fun extractFromScripts(document: Document, baseUrl: String): VideoUrlInfo? {
        val scripts = document.select("script").html()
        
        // Common patterns for video URLs in JS - EXPANDED for more providers and SPA frameworks
        val patterns = listOf(
            // Standard video URL assignments
            Regex("""(?:src|file|source|url|video_url|videoUrl|stream|streamUrl|playUrl|mediaUrl|hlsUrl|dashUrl|videoSrc|streamSrc)['":\s]+['"]?(https?://[^'"\s]+\.(?:mp4|m3u8|webm|mpd)[^'"\s]*)['"]?""", RegexOption.IGNORE_CASE),
            // Any https URL with a video extension
            Regex("""['"]?(https?://[^'"\s]+\.(?:mp4|m3u8|webm|mpd)[^'"\s]*)['"]?""", RegexOption.IGNORE_CASE),
            // JWPlayer file config
            Regex("""file:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // JWPlayer/Video.js sources list
            Regex("""sources:\s*\[\s*\{\s*(?:file|src):\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // player.src() call
            Regex("""player\.src\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // video: 'url' pattern
            Regex("""video:\s*['"]([^'"]+\.(?:mp4|m3u8|webm))['"]""", RegexOption.IGNORE_CASE),
            // .setup({ file: 'url' }) VideoJS
            Regex("""\.setup\(\s*\{[^}]*(?:file|src)\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // base64 encoded URLs (atob)
            Regex("""atob\(['"]([A-Za-z0-9+/=]{20,})['"]"""),
            // HLS/DASH specific variables
            Regex("""(?:hls|dash)(?:Url|Source|Stream)\s*[=:]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // .load() calls
            Regex("""\.load\(['"]([^'"]+\.(?:mp4|m3u8|webm|mpd))['"]""", RegexOption.IGNORE_CASE),
            // Popular player setup calls (expanded list)
            Regex("""(?:videojs|jwplayer|plyr|clappr|flowplayer|brightcove|kaltura)\s*[.(][^)]*['"]([^'"]+\.(?:mp4|m3u8|webm|mpd))['"]""", RegexOption.IGNORE_CASE),
            // Hls.js loadSource()
            Regex("""new\s+Hls\([^)]*\)\.loadSource\(['"]([^'"]+)['"]\)""", RegexOption.IGNORE_CASE),
            // React/Vue/Angular component props with video URLs
            Regex("""['"](?:videoUrl|hlsUrl|streamUrl|video_url|media_url|playback_url)['"]:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // Common JSON API response fields
            Regex("""['"](?:stream|stream_url|hls|hls_url|mp4|mp4_url|download_url|direct_url)['"]:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // CDN domains: cloudfront / akamai / bunny / fastly / digitalocean spaces
            Regex("""['"]?(https?://[a-z0-9.-]*(?:cloudfront\.net|akamaized\.net|bunnycdn\.com|b-cdn\.net|fastly\.net|digitaloceanspaces\.com)/[^'"\s]+\.(?:mp4|m3u8|webm|mpd)[^'"\s]*)['"]?""", RegexOption.IGNORE_CASE),
            // window.videoConfig / window.playerConfig
            Regex("""window\.(?:videoConfig|playerConfig|mediaConfig|streamConfig)\s*=\s*\{[^}]*['"]?(?:src|url|file)['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        )
        
        // Find all matches and pick best quality
        val foundUrls = mutableListOf<String>()
        
        for (pattern in patterns) {
            pattern.findAll(scripts).forEach { match ->
                val url = match.groupValues.getOrNull(1) ?: match.value
                if (VIDEO_EXTENSIONS.any { url.contains(it, ignoreCase = true) }) {
                    foundUrls.add(url)
                }
            }
        }
        
        // Sort by quality and return best
        val bestUrl = foundUrls
            .distinctBy { it }
            .sortedByDescending { url ->
                QUALITY_ORDER.indexOfFirst { q -> url.contains(q, ignoreCase = true) }
                    .let { if (it == -1) -100 else -it }
            }
            .firstOrNull()
        
        return bestUrl?.let {
            val cleanUrl = it.replace("\\", "").trim('"', '\'')
            VideoUrlInfo(
                url = normalizeUrl(cleanUrl, baseUrl),
                quality = detectQuality(cleanUrl),
                format = detectFormat(cleanUrl),
                isStream = cleanUrl.contains(".m3u8") || cleanUrl.contains(".mpd")
            )
        }
    }
    
    /**
     * Extract from data attributes
     */
    private fun extractFromDataAttributes(document: Document, baseUrl: String): VideoUrlInfo? {
        val dataAttrs = listOf(
            "[data-video-url]", "[data-src]", "[data-video]",
            "[data-file]", "[data-stream]", "[data-mp4]",
            "[data-hls]", "[data-dash]", "[data-source]",
            "[data-url]", "[data-sourceurl]", "[data-media]",
            "[data-playlist]", "[data-videofile]", "[data-streamurl]",
            "video[data-setup]", "video-js[data-setup]",
            "[data-jwplayer-id]", "[data-kaltura]",
            // Brightcove / Video.js config attributes
            "[data-video-id]", "[data-player]"
        )
        
        for (selector in dataAttrs) {
            val elements = document.select(selector)
            for (element in elements) {
                // Try all data-* attributes on the element
                val candidate = element.attributes().filter { it.key.startsWith("data-") }
                    .mapNotNull { attr ->
                        val v = attr.value
                        if (v.isNotEmpty() && VIDEO_EXTENSIONS.any { v.contains(it, ignoreCase = true) }) v
                        else null
                    }.firstOrNull()
                if (candidate != null) {
                    return VideoUrlInfo(
                        url = normalizeUrl(candidate, baseUrl),
                        quality = detectQuality(candidate),
                        format = detectFormat(candidate),
                        isStream = candidate.contains(".m3u8") || candidate.contains(".mpd")
                    )
                }
                // Also check data-setup JSON (Video.js)
                val setupJson = element.attr("data-setup")
                if (setupJson.isNotEmpty()) {
                    Regex("""['"]?(?:src|file)['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                        .find(setupJson)?.groupValues?.getOrNull(1)?.let { url ->
                            if (url.isNotEmpty()) return VideoUrlInfo(
                                url = normalizeUrl(url, baseUrl),
                                quality = detectQuality(url),
                                format = detectFormat(url),
                                isStream = url.contains(".m3u8") || url.contains(".mpd")
                            )
                        }
                }
            }
        }
        
        return null
    }

    /**
     * Extract video URLs from SSR/SPA framework state blobs:
     * window.__NEXT_DATA__ (Next.js), window.__NUXT__ (Nuxt), window.__INITIAL_STATE__,
     * window.__REDUX_STATE__, window.APP_INIT_DATA, etc.
     */
    private fun extractFromSsrState(document: Document, baseUrl: String): VideoUrlInfo? {
        val stateVarPatterns = listOf(
            "window.__NEXT_DATA__",
            "window.__NUXT__",
            "window.__INITIAL_STATE__",
            "window.__REDUX_STATE__",
            "window.APP_INIT_DATA",
            "window.pageData",
            "window.__data__",
            "window.initialProps"
        )

        for (script in document.select("script")) {
            val html = script.html()
            val isStateBLob = stateVarPatterns.any { html.contains(it) } ||
                (script.attr("id") == "__NEXT_DATA__") ||
                (html.trimStart().startsWith("{") && html.length > 100)

            if (!isStateBLob) continue

            // Generic pattern: any key that looks like a video URL field
            val videoUrlPattern = Regex(
                """['"](?:videoUrl|video_url|hlsUrl|hls_url|streamUrl|stream_url|mp4Url|mp4_url|"""
                    + """playback_url|media_url|download_url|directUrl|direct_url|src|file|url)['"]\s*:\s*['"]([^'"]+)['"]""",
                RegexOption.IGNORE_CASE
            )

            val allFound = videoUrlPattern.findAll(html)
                .mapNotNull { it.groupValues.getOrNull(1) }
                .filter { url -> VIDEO_EXTENSIONS.any { url.contains(it, ignoreCase = true) } }
                .toList()

            val bestUrl = allFound
                .sortedByDescending { url ->
                    QUALITY_ORDER.indexOfFirst { q -> url.contains(q, ignoreCase = true) }
                        .let { if (it == -1) -100 else -it }
                }
                .firstOrNull()

            if (bestUrl != null) {
                val clean = bestUrl.replace("\\", "").trim()
                return VideoUrlInfo(
                    url = normalizeUrl(clean, baseUrl),
                    quality = detectQuality(clean),
                    format = detectFormat(clean),
                    isStream = clean.contains(".m3u8") || clean.contains(".mpd")
                )
            }
        }

        return null
    }
    
    /**
     * Extract from JSON-LD schema
     */
    private fun extractFromJsonLd(document: Document, baseUrl: String): VideoUrlInfo? {
        val jsonLd = document.select("script[type='application/ld+json']")
        
        for (script in jsonLd) {
            val json = script.html()
            
            // Look for contentUrl or embedUrl
            val urlPatterns = listOf(
                Regex(""""contentUrl"\s*:\s*"([^"]+)${"\""}"""),
                Regex(""""embedUrl"\s*:\s*"([^"]+)${"\""}"""),
                Regex(""""url"\s*:\s*"([^"]+\.(?:mp4|m3u8|webm))${"\""}"""),
                Regex(""""videoUrl"\s*:\s*"([^"]+)${"\""}"""),
                Regex(""""streamUrl"\s*:\s*"([^"]+)${'"'}""")
            )
            
            for (pattern in urlPatterns) {
                pattern.find(json)?.groupValues?.getOrNull(1)?.let { url ->
                    if (VIDEO_EXTENSIONS.any { ext -> url.contains(ext, ignoreCase = true) } || url.contains("video")) {
                        return VideoUrlInfo(
                            url = normalizeUrl(url, baseUrl),
                            quality = detectQuality(url),
                            format = detectFormat(url),
                            isStream = url.contains(".m3u8") || url.contains(".mpd")
                        )
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Extract from OpenGraph and other meta tags
     */
    private fun extractFromMetaTags(document: Document, baseUrl: String): VideoUrlInfo? {
        // OpenGraph video tags
        val ogVideoSelectors = listOf(
            "meta[property='og:video']",
            "meta[property='og:video:url']",
            "meta[property='og:video:secure_url']",
            "meta[name='twitter:player:stream']",
            "meta[name='twitter:player']",
            "meta[itemprop='contentUrl']",
            "meta[itemprop='embedURL']"
        )
        
        for (selector in ogVideoSelectors) {
            val content = document.select(selector).firstOrNull()?.attr("content")
            if (!content.isNullOrEmpty()) {
                // Only return if it looks like a video URL
                if (VIDEO_EXTENSIONS.any { content.contains(it, ignoreCase = true) } || 
                    content.contains("video") || content.contains("player") || content.contains("stream")) {
                    return VideoUrlInfo(
                        url = normalizeUrl(content, baseUrl),
                        quality = detectQuality(content),
                        format = detectFormat(content),
                        isStream = content.contains(".m3u8") || content.contains(".mpd")
                    )
                }
            }
        }
        
        return null
    }
    
    /**
     * Get video preview/thumbnail URL
     */
    suspend fun getVideoPreviewUrl(pageUrl: String): String? = withContext(Dispatchers.IO) {
        try {
            val document = Jsoup.connect(pageUrl)
                .userAgent(USER_AGENT)
                .timeout(15000)
                .get()
            
            // Look for preview/poster
            val video = document.select("video[poster]").firstOrNull()
            if (video != null) {
                val poster = video.attr("poster")
                if (poster.isNotEmpty()) {
                    return@withContext normalizeUrl(poster, pageUrl)
                }
            }
            
            // Look for og:video or og:image
            document.select("meta[property='og:video']").firstOrNull()?.attr("content")?.let {
                if (it.isNotEmpty()) return@withContext it
            }
            
            document.select("meta[property='og:image']").firstOrNull()?.attr("content")?.let {
                if (it.isNotEmpty()) return@withContext it
            }
            
            // Look for preview gif/webp
            document.select("[data-preview], .preview, .gif-preview").firstOrNull()?.let { elem ->
                elem.attr("data-preview").takeIf { it.isNotEmpty() }
                    ?: elem.attr("src").takeIf { it.isNotEmpty() }
            }?.let { return@withContext normalizeUrl(it, pageUrl) }
            
            null
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Download video to storage
     */
    suspend fun downloadVideo(
        videoUrl: String,
        fileName: String,
        onProgress: (Int) -> Unit
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(videoUrl)
                .header("User-Agent", USER_AGENT)
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext DownloadResult(
                    success = false,
                    error = "HTTP ${response.code}"
                )
            }
            
            val body = response.body ?: return@withContext DownloadResult(
                success = false,
                error = "Empty response"
            )
            
            // Create download directory
            val downloadDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "AggregatorX"
            )
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            // Sanitize filename
            val sanitizedName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val extension = detectFormat(videoUrl).let { 
                if (it.isNotEmpty()) ".$it" else ".mp4" 
            }
            val file = File(downloadDir, "$sanitizedName$extension")
            
            // Download with progress
            val totalBytes = body.contentLength()
            var downloadedBytes = 0L
            
            FileOutputStream(file).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            onProgress(progress)
                        }
                    }
                }
            }
            
            DownloadResult(
                success = true,
                filePath = file.absolutePath,
                fileSize = file.length()
            )
        } catch (e: Exception) {
            DownloadResult(
                success = false,
                error = e.message ?: "Download failed"
            )
        }
    }
    
    // Helper functions
    private fun normalizeUrl(url: String, baseUrl: String): String {
        return when {
            url.startsWith("http") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> {
                val base = URL(baseUrl)
                "${base.protocol}://${base.host}$url"
            }
            else -> {
                val base = URL(baseUrl)
                "${base.protocol}://${base.host}/$url"
            }
        }
    }
    
    private fun detectQuality(url: String): String {
        val urlLower = url.lowercase()
        return QUALITY_ORDER.find { urlLower.contains(it) } ?: "Unknown"
    }
    
    private fun detectFormat(url: String): String {
        val urlLower = url.lowercase()
        return when {
            urlLower.contains(".m3u8") -> "m3u8"
            urlLower.contains(".mpd") -> "mpd"
            urlLower.contains(".mp4") -> "mp4"
            urlLower.contains(".webm") -> "webm"
            urlLower.contains(".mkv") -> "mkv"
            urlLower.contains(".avi") -> "avi"
            urlLower.contains(".mov") -> "mov"
            else -> ""
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Dedicated host-specific video extractors (2026)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun extractFromKnownHost(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        val lowerUrl = url.lowercase()
        when {
            lowerUrl.contains("streamtape")   -> extractFromStreamTape(url)
            lowerUrl.contains("doodstream") ||
            lowerUrl.contains("dood.w") ||
            lowerUrl.contains("dood.re")      -> extractFromDoodStream(url)
            lowerUrl.contains("streamwish") ||
            lowerUrl.contains("swdyu") ||
            lowerUrl.contains("awish")        -> extractFromStreamWish(url)
            lowerUrl.contains("filemoon") ||
            lowerUrl.contains("fmoonembed")   -> extractFromFileMoon(url)
            lowerUrl.contains("mixdrop")      -> extractFromMixDrop(url)
            lowerUrl.contains("voe.sx") ||
            lowerUrl.contains("voe-unblock")  -> extractFromVOE(url)
            lowerUrl.contains("vidsrc") ||
            lowerUrl.contains("vidsrc.to") ||
            lowerUrl.contains("vidsrc.me")    -> extractFromVidSrc(url)
            else -> null
        }
    }

    /** StreamTape — decodes the obfuscated token from the token JS vars */
    private suspend fun extractFromStreamTape(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", "https://streamtape.com/")
                .timeout(12000).ignoreHttpErrors(true).get().html()
            // StreamTape hides its token in two script vars, then concatenates them
            val tokenPattern = Regex("""id="ideoooolink[^"]*"[^>]*>[^/]*(//[^<]+)</""")
            val directLink = tokenPattern.find(html)?.groupValues?.get(1)
            if (directLink != null) {
                val cleaned = "https:" + directLink.trim()
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = cleaned,
                    quality = "auto", format = "mp4", isStream = false
                )
            }
            // Fallback: regex for .mp4 link in JS token concat
            val fallback = Regex("""(https://[a-z0-9.]+/get_video\?[^'"]+)""").find(html)
            if (fallback != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = fallback.groupValues[1],
                    quality = "auto", format = "mp4", isStream = false
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** DoodStream — solves the MD5 token + ?md5= URL construction */
    private suspend fun extractFromDoodStream(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", "https://dood.re/")
                .timeout(12000).ignoreHttpErrors(true).get().html()
            // DoodStream embeds a /pass_md5/ path
            val passPattern = Regex("""['"](/pass_md5/[^'"]+)['"]""")
            val passPath = passPattern.find(html)?.groupValues?.get(1) ?: return@withContext null
            val base = Regex("""https?://[^/]+""").find(url)?.value ?: "https://dood.re"
            val passUrl = base + passPath
            val token = Regex("""[?&]token=([^&'"]+)""").find(html)?.groupValues?.get(1) ?: ""
            val passResponse = Jsoup.connect(passUrl)
                .userAgent(USER_AGENT)
                .header("Referer", url)
                .timeout(10000).ignoreHttpErrors(true).get().text().trim()
            if (passResponse.startsWith("http")) {
                val finalUrl = "$passResponse?token=$token&expiry=${System.currentTimeMillis()}"
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = finalUrl,
                    quality = "auto", format = "mp4", isStream = false
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** StreamWish (& mirrors) — HLS m3u8 extraction from JS config */
    private suspend fun extractFromStreamWish(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", url)
                .timeout(12000).ignoreHttpErrors(true).get().html()
            val hlsPattern = Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
            val m3u8 = hlsPattern.find(html)?.groupValues?.get(1)
            if (m3u8 != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = m3u8,
                    quality = "auto", format = "hls", isStream = true
                )
            }
            // jwplayer file fallback
            val jwPattern = Regex("""file\s*:\s*['"](https?://[^'"]+)['"]""")
            val jwUrl = jwPattern.find(html)?.groupValues?.get(1)
            if (jwUrl != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = jwUrl,
                    quality = "auto", format = detectFormat(jwUrl), isStream = jwUrl.contains(".m3u8")
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** FileMoon — HLS extraction from JS eval'd source */
    private suspend fun extractFromFileMoon(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", url)
                .timeout(12000).ignoreHttpErrors(true).get().html()
            // FileMoon uses jwplayer with a sources array
            val sourcesPattern = Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*['"](https?://[^'"]+)['"]""")
            val fileUrl = sourcesPattern.find(html)?.groupValues?.get(1)
            if (fileUrl != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = fileUrl,
                    quality = "auto", format = if (fileUrl.contains(".m3u8")) "hls" else "mp4",
                    isStream = fileUrl.contains(".m3u8")
                )
            }
            val hlsFallback = Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (hlsFallback != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = hlsFallback,
                    quality = "auto", format = "hls", isStream = true
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** MixDrop — extracts wurl from the obfuscated player JS */
    private suspend fun extractFromMixDrop(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", "https://mixdrop.ag/")
                .timeout(12000).ignoreHttpErrors(true).get().html()
            val wurlPattern = Regex("""wurl\s*=\s*["'](//[^"']+)["']""")
            val wurl = wurlPattern.find(html)?.groupValues?.get(1)
            if (wurl != null) {
                val cleanUrl = "https:$wurl"
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = cleanUrl,
                    quality = "auto", format = "mp4", isStream = false
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** VOE — decodes the obfuscated wurl / hls link */
    private suspend fun extractFromVOE(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", url)
                .timeout(12000).ignoreHttpErrors(true).get().html()
            val hlsPattern = Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
            val m3u8 = hlsPattern.find(html)?.groupValues?.get(1)
            if (m3u8 != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = m3u8,
                    quality = "auto", format = "hls", isStream = true
                )
            }
            val mp4Pattern = Regex("""['"](https?://[^'"]+\.mp4[^'"]*)['"]""")
            val mp4Url = mp4Pattern.find(html)?.groupValues?.get(1)
            if (mp4Url != null) {
                return@withContext VideoExtractionResult(
                    success = true, videoUrl = mp4Url,
                    quality = "auto", format = "mp4", isStream = false
                )
            }
            null
        } catch (_: Exception) { null }
    }

    /** VidSrc — follows the embed chain to extract the HLS/mp4 source */
    private suspend fun extractFromVidSrc(url: String): VideoExtractionResult? = withContext(Dispatchers.IO) {
        try {
            val html = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .header("Referer", "https://vidsrc.to/")
                .timeout(15000).ignoreHttpErrors(true).get().html()
            // Look for nested iframe → follow until we find a media URL
            val iframePattern = Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""")
            val iframeUrl = iframePattern.find(html)?.groupValues?.get(1)
            if (iframeUrl != null && iframeUrl.startsWith("http")) {
                // Recurse once into iframe
                return@withContext extractFromKnownHost(iframeUrl)
                    ?: extractVideoUrlFast(iframeUrl)?.let {
                        VideoExtractionResult(success = true, videoUrl = it, quality = "auto", format = detectFormat(it), isStream = it.contains(".m3u8"))
                    }
            }
            val m3u8 = Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (m3u8 != null) {
                return@withContext VideoExtractionResult(success = true, videoUrl = m3u8, quality = "auto", format = "hls", isStream = true)
            }
            null
        } catch (_: Exception) { null }
    }
}

data class VideoUrlInfo(
    val url: String,
    val quality: String,
    val format: String,
    val isStream: Boolean,
    val isEmbed: Boolean = false
)

data class VideoExtractionResult(
    val success: Boolean,
    val videoUrl: String? = null,
    val quality: String? = null,
    val format: String? = null,
    val isStream: Boolean = false,
    val error: String? = null
)

data class DownloadResult(
    val success: Boolean,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val error: String? = null
)
