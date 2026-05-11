package com.aggregatorx.app.engine.media

import com.aggregatorx.app.engine.network.ProxyVPNEngine
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.*
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VideoStreamResolver — multi-strategy video stream resolution.
 *
 * Resolution chain (in order):
 *   1. AdvancedVideoExtractorEngine (50+ site-specific + generic extractors)
 *   2. VideoExtractorEngine (legacy fast path)
 *   3. Netherlands proxy fetch + extraction
 *   4. HeadlessBrowserHelper rendered extraction
 *   5. Site-specific embed extractors (VidSrc, Filemoon, Doodstream, etc.)
 *   6. Iframe chain following (up to 4 hops)
 */
@Singleton
class VideoStreamResolver @Inject constructor(
    private val proxyVPNEngine: ProxyVPNEngine,
    private val videoExtractorEngine: VideoExtractorEngine,
    private val advancedExtractor: AdvancedVideoExtractorEngine
) {
    companion object {
        private val USER_AGENT = EngineUtils.DEFAULT_USER_AGENT
        private val STREAM_EXTENSIONS = listOf(".m3u8", ".mpd", ".ts")
        private val ERROR_RECOVERY_MAP = mapOf(
            "Source error" to RecoveryStrategy.TRY_PROXY,
            "Playback failed" to RecoveryStrategy.TRY_ALTERNATE_SOURCE,
            "403" to RecoveryStrategy.USE_NETHERLANDS_PROXY,
            "404" to RecoveryStrategy.TRY_ALTERNATE_SOURCE,
            "geo" to RecoveryStrategy.USE_NETHERLANDS_PROXY,
            "blocked" to RecoveryStrategy.USE_NETHERLANDS_PROXY,
            "unavailable" to RecoveryStrategy.TRY_ALL_METHODS,
            "timeout" to RecoveryStrategy.RETRY_WITH_LONGER_TIMEOUT
        )
        private val REFERER_PATTERNS = mapOf(
            "vidsrc" to "https://vidsrc.to/",
            "vidcloud" to "https://vidcloud.co/",
            "streamwish" to "https://streamwish.to/",
            "filemoon" to "https://filemoon.sx/",
            "doodstream" to "https://dood.to/",
            "mixdrop" to "https://mixdrop.co/",
            "upstream" to "https://upstream.to/"
        )
    }

    /**
     * Main entry point. Tries all strategies and returns the best result.
     */
    suspend fun resolveVideoStream(
        pageUrl: String,
        useProxy: Boolean = true,
        preferHighQuality: Boolean = true
    ): VideoStreamResult = withContext(Dispatchers.IO) {
        if (useProxy && proxyVPNEngine.getCurrentProxy() == null) {
            try { proxyVPNEngine.initialize() } catch (_: Exception) {}
        }

        // Strategy 1: AdvancedVideoExtractorEngine (primary)
        try {
            val adv = advancedExtractor.extract(pageUrl)
            if (adv.success && !adv.videoUrl.isNullOrEmpty()) {
                val referer = determineReferer(pageUrl)
                return@withContext VideoStreamResult(
                    success = true,
                    streamUrl = adv.videoUrl,
                    streamType = determineStreamType(adv.videoUrl),
                    quality = adv.quality ?: "Unknown",
                    format = adv.format ?: "mp4",
                    headers = buildPlaybackHeaders(pageUrl, referer),
                    confidence = 0.95f,
                    isValidated = true
                )
            }
        } catch (_: Exception) {}

        // Strategy 2: Legacy VideoExtractorEngine
        try {
            val legacy = videoExtractorEngine.extractVideoUrl(pageUrl)
            if (legacy.success && !legacy.videoUrl.isNullOrEmpty()) {
                val referer = determineReferer(pageUrl)
                return@withContext VideoStreamResult(
                    success = true,
                    streamUrl = legacy.videoUrl,
                    streamType = determineStreamType(legacy.videoUrl),
                    quality = legacy.quality ?: "Unknown",
                    format = legacy.format ?: "mp4",
                    headers = buildPlaybackHeaders(pageUrl, referer),
                    confidence = 0.85f
                )
            }
        } catch (_: Exception) {}

        // Strategy 3: Proxy fetch
        try {
            val proxyResult = resolveWithProxy(pageUrl)
            if (proxyResult.success && proxyResult.streamUrl != null) return@withContext proxyResult
        } catch (_: Exception) {}

        // Strategy 4: Headless browser
        try {
            val headlessResult = resolveWithHeadlessBrowser(pageUrl)
            if (headlessResult.success && headlessResult.streamUrl != null) return@withContext headlessResult
        } catch (_: Exception) {}

        // Strategy 5: Alternate site-specific extractors
        try {
            val altResult = resolveWithAlternateExtractors(pageUrl)
            if (altResult.success && altResult.streamUrl != null) return@withContext altResult
        } catch (_: Exception) {}

        VideoStreamResult(
            success = false,
            error = "All resolution strategies exhausted for $pageUrl",
            suggestedRecovery = RecoveryStrategy.MANUAL_INTERVENTION
        )
    }

    private suspend fun resolveWithProxy(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        try {
            val document = proxyVPNEngine.fetchDocumentWithProxy(pageUrl)
                ?: return@withContext VideoStreamResult(success = false, error = "Proxy fetch failed")
            val videoUrl = extractVideoFromDocument(document, pageUrl)
            if (videoUrl != null) {
                VideoStreamResult(
                    success = true, streamUrl = videoUrl,
                    streamType = determineStreamType(videoUrl),
                    quality = detectQualityFromUrl(videoUrl),
                    headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                    usedProxy = proxyVPNEngine.getCurrentProxy()?.toString(),
                    confidence = 0.7f
                )
            } else VideoStreamResult(success = false, error = "No video found via proxy")
        } catch (e: Exception) {
            VideoStreamResult(success = false, error = "Proxy error: ${e.message}")
        }
    }

    private suspend fun resolveWithHeadlessBrowser(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        try {
            val pageContent = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(
                url = pageUrl, waitSelector = "video, source, [data-video-url]", timeout = 25000
            )
            if (pageContent.isNullOrEmpty()) return@withContext VideoStreamResult(success = false, error = "Headless fetch failed")
            val document = Jsoup.parse(pageContent, pageUrl)
            val videoUrl = extractVideoFromDocument(document, pageUrl)
            if (videoUrl != null) {
                return@withContext VideoStreamResult(
                    success = true, streamUrl = videoUrl,
                    streamType = determineStreamType(videoUrl),
                    quality = detectQualityFromUrl(videoUrl),
                    headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                    confidence = 0.65f
                )
            }
            val capturedVideos = HeadlessBrowserHelper.extractVideoUrls(pageUrl)
            val best = capturedVideos.maxByOrNull { url ->
                when { url.contains("1080") -> 100; url.contains("720") -> 80; url.contains(".m3u8") -> 90; else -> 50 }
            }
            if (best != null) {
                VideoStreamResult(
                    success = true, streamUrl = best,
                    streamType = determineStreamType(best),
                    quality = detectQualityFromUrl(best),
                    headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                    confidence = 0.6f
                )
            } else VideoStreamResult(success = false, error = "No video via headless")
        } catch (e: Exception) {
            VideoStreamResult(success = false, error = "Headless error: ${e.message}")
        }
    }

    private suspend fun resolveWithAlternateExtractors(pageUrl: String): VideoStreamResult = withContext(Dispatchers.IO) {
        val extractors: List<suspend () -> String?> = listOf(
            { extractFromVidSrc(pageUrl) },
            { extractFromFilemoon(pageUrl) },
            { extractFromDoodstream(pageUrl) },
            { extractFromMixdrop(pageUrl) },
            { extractFromStreamwish(pageUrl) },
            { extractFromIframeChain(pageUrl) }
        )
        for (extractor in extractors) {
            try {
                val result = extractor()
                if (!result.isNullOrEmpty()) {
                    return@withContext VideoStreamResult(
                        success = true, streamUrl = result,
                        streamType = determineStreamType(result),
                        quality = detectQualityFromUrl(result),
                        headers = buildPlaybackHeaders(pageUrl, determineReferer(pageUrl)),
                        confidence = 0.5f
                    )
                }
            } catch (_: Exception) {}
        }
        VideoStreamResult(success = false, error = "All alternate extractors failed", suggestedRecovery = RecoveryStrategy.MANUAL_INTERVENTION)
    }

    private fun extractVideoFromDocument(document: Document, baseUrl: String): String? {
        document.select("video[src], video source[src]").firstOrNull()?.let { video ->
            val src = video.attr("src").takeIf { it.isNotEmpty() } ?: video.attr("data-src").takeIf { it.isNotEmpty() }
            if (src != null) return normalizeUrl(src, baseUrl)
        }
        listOf("[data-video-url]","[data-src]","[data-video]","[data-file]","[data-stream]","[data-mp4]","[data-hls]").forEach { sel ->
            document.select(sel).firstOrNull()?.let { elem ->
                val url = elem.attr(sel.removeSurrounding("[","]"))
                if (url.isNotEmpty() && isVideoUrl(url)) return normalizeUrl(url, baseUrl)
            }
        }
        val scripts = document.select("script").html()
        listOf(
            Regex("""(?:src|file|source|url|video_url|videoUrl)['":\s]+['"]?(https?://[^'">\s]+\.(?:mp4|m3u8|webm|mpd)[^'">\s]*)['"]?""", RegexOption.IGNORE_CASE),
            Regex("""file:\s*['"]([^'"]+\.(?:mp4|m3u8|webm|mpd)[^'"]*)['"]""", RegexOption.IGNORE_CASE),
            Regex("""sources:\s*\[\s*\{\s*(?:file|src):\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
        ).forEach { pattern ->
            pattern.find(scripts)?.groupValues?.getOrNull(1)?.let { url ->
                if (isVideoUrl(url)) return normalizeUrl(url.replace("\\",""), baseUrl)
            }
        }
        return null
    }

    private suspend fun validateStreamUrl(url: String, headers: Map<String, String>?): ValidationResult = withContext(Dispatchers.IO) {
        try {
            val client = proxyVPNEngine.createProxyClient()
            val requestBuilder = Request.Builder().url(url).head().header("User-Agent", USER_AGENT)
            headers?.forEach { (k, v) -> requestBuilder.header(k, v) }
            val response = client.newCall(requestBuilder.build()).execute()
            val contentType = response.header("Content-Type") ?: ""
            val contentLength = response.header("Content-Length")?.toLongOrNull() ?: 0
            val isValid = when {
                response.code == 403 || response.code == 405 -> true
                contentType.contains("video") || contentType.contains("mpegurl") -> true
                contentType.contains("dash+xml") || contentType.contains("octet-stream") -> true
                url.contains(".m3u8") || url.contains(".mpd") || url.contains(".mp4") -> true
                contentType.isEmpty() -> true
                else -> response.isSuccessful
            }
            ValidationResult(isValid = isValid, contentType = contentType, bitrate = estimateBitrate(contentLength))
        } catch (_: Exception) { ValidationResult(isValid = true) }
    }

    private suspend fun extractFromVidSrc(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("vidsrc", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                Regex("""file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(scripts)?.groupValues?.get(1)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun extractFromFilemoon(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("filemoon", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            val scripts = doc.select("script").html()
            val deob = advancedExtractor.deobfuscatePackedJs(scripts)
            Regex("""(https?://[^\s'"]+\.m3u8[^\s'"]*)""").find(deob)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private suspend fun extractFromDoodstream(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("dood", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            doc.select("script").html().let { scripts ->
                Regex("""(?:src|file):\s*['"]([^'"]+/(?:download|video)[^'"]*)['"]""").find(scripts)?.groupValues?.get(1)
            }
        } catch (_: Exception) { null }
    }

    private suspend fun extractFromMixdrop(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("mixdrop", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            val scripts = doc.select("script").html()
            val deob = advancedExtractor.deobfuscatePackedJs(scripts)
            Regex("""wurl\s*=\s*['"]([^'"]+)['"]""").find(deob)?.groupValues?.get(1)?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
        } catch (_: Exception) { null }
    }

    private suspend fun extractFromStreamwish(url: String): String? = withContext(Dispatchers.IO) {
        if (!url.contains("streamwish", ignoreCase = true)) return@withContext null
        try {
            val doc = Jsoup.connect(url).userAgent(USER_AGENT).timeout(15000).get()
            val scripts = doc.select("script").html()
            val deob = advancedExtractor.deobfuscatePackedJs(scripts)
            Regex("""sources:\s*\[\s*\{\s*file:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""").find(deob)?.groupValues?.get(1)
        } catch (_: Exception) { null }
    }

    private suspend fun extractFromIframeChain(pageUrl: String, depth: Int = 0): String? = withContext(Dispatchers.IO) {
        if (depth > 4) return@withContext null
        try {
            val doc = Jsoup.connect(pageUrl).userAgent(USER_AGENT).timeout(15000).ignoreHttpErrors(true).get()
            val videoUrl = extractVideoFromDocument(doc, pageUrl)
            if (videoUrl != null) return@withContext videoUrl
            val iframes = doc.select("iframe[src]")
            for (iframe in iframes.take(3)) {
                val src = iframe.attr("src").trim()
                if (src.isEmpty()) continue
                val resolved = normalizeUrl(src, pageUrl)
                val result = extractFromIframeChain(resolved, depth + 1)
                if (result != null) return@withContext result
            }
            null
        } catch (_: Exception) { null }
    }

    private fun determineStreamType(url: String): StreamType {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains("/hls/") -> StreamType.HLS
            lower.contains(".mpd") || lower.contains("/dash/") -> StreamType.DASH
            lower.contains(".mp4") || lower.contains(".webm") -> StreamType.PROGRESSIVE
            else -> StreamType.UNKNOWN
        }
    }

    private fun determineReferer(url: String): String {
        val lower = url.lowercase()
        return REFERER_PATTERNS.entries.firstOrNull { lower.contains(it.key) }?.value
            ?: try { val u = java.net.URI(url); "${u.scheme}://${u.host}/" } catch (_: Exception) { "" }
    }

    private fun buildPlaybackHeaders(pageUrl: String, referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer.ifEmpty { pageUrl },
            "Origin" to try { val u = java.net.URI(pageUrl); "${u.scheme}://${u.host}" } catch (_: Exception) { "" },
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.9"
        )
    }

    private fun detectQualityFromUrl(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> "4K"
            lower.contains("1440") -> "1440p"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            lower.contains(".m3u8") || lower.contains(".mpd") -> "Auto"
            else -> "Unknown"
        }
    }

    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".mp4",".m3u8",".mpd",".webm",".mkv",".ts","/video/","/stream/","/hls/","/dash/").any { lower.contains(it) }
    }

    private fun normalizeUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { java.net.URI(base).resolve(url).toString() } catch (_: Exception) { url }
    }

    private fun estimateBitrate(contentLength: Long): Int {
        return when {
            contentLength > 10_000_000 -> 8000
            contentLength > 5_000_000 -> 4000
            contentLength > 1_000_000 -> 2000
            else -> 1000
        }
    }
}

data class VideoStreamResult(
    val success: Boolean,
    val streamUrl: String? = null,
    val streamType: StreamType = StreamType.UNKNOWN,
    val quality: String = "Unknown",
    val format: String = "mp4",
    val headers: Map<String, String>? = null,
    val usedProxy: String? = null,
    val confidence: Float = 0f,
    val isValidated: Boolean = false,
    val estimatedBitrate: Int = 0,
    val error: String? = null,
    val suggestedRecovery: RecoveryStrategy = RecoveryStrategy.TRY_PROXY
)

data class ValidationResult(
    val isValid: Boolean,
    val contentType: String = "",
    val bitrate: Int = 0,
    val errorCode: Int = 0
)

enum class StreamType { HLS, DASH, PROGRESSIVE, UNKNOWN }

enum class RecoveryStrategy {
    TRY_PROXY, USE_NETHERLANDS_PROXY, TRY_HEADLESS_BROWSER,
    TRY_ALTERNATE_SOURCE, TRY_ALL_METHODS, RETRY_WITH_LONGER_TIMEOUT, MANUAL_INTERVENTION
}
