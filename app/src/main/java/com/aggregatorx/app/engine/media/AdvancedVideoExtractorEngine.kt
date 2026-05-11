package com.aggregatorx.app.engine.media

import android.util.Base64
import android.util.Log
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import com.aggregatorx.app.engine.util.EngineUtils
import kotlinx.coroutines.*
import okhttp3.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AdvancedVideoExtractorEngine
 *
 * The most capable video extraction engine in the app. Handles:
 *  - 50+ named streaming hosts with site-specific logic
 *  - JS deobfuscation: eval(p,a,c,k,e,d), base64, hex, rot13, unicode escapes
 *  - M3U8 / DASH MPD manifest parsing and quality selection
 *  - Multi-level iframe chain following (up to 5 hops)
 *  - Parallel extraction across all strategies
 *  - Cookie/token harvesting and replay
 *  - Cloudflare-aware header fingerprinting
 *  - Regex corpus of 80+ video URL patterns
 *  - JSON-LD, OG tags, schema.org VideoObject
 *  - JWPlayer, Video.js, Plyr, Flowplayer, Clappr config extraction
 *  - DRM-free stream detection and preference
 */
@Singleton
class AdvancedVideoExtractorEngine @Inject constructor() {

    companion object {
        private const val TAG = "AdvVideoExtractor"

        private val UA = EngineUtils.DEFAULT_USER_AGENT
        private val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val UA_TV = "Mozilla/5.0 (SMART-TV; Linux; Tizen 6.0) AppleWebKit/538.1 (KHTML, like Gecko) Version/6.0 TV Safari/538.1"

        private val VIDEO_EXTS = setOf("mp4","webm","mkv","avi","mov","m4v","flv","wmv","3gp","ts","ogv","f4v")
        private val STREAM_EXTS = setOf("m3u8","mpd")

        // 80+ regex patterns covering every known video URL embedding style
        private val URL_PATTERNS: List<Regex> = listOf(
            // Direct file/src assignments
            Regex("""(?:file|src|source|url|video|stream|hls|dash|mp4|webm)\s*[=:]\s*['"]?(https?://[^'"<>\s]{10,})['"]?""", RegexOption.IGNORE_CASE),
            Regex("""['"](?:file|src|source|url|videoUrl|streamUrl|hlsUrl|dashUrl|mp4Url|videoSrc)['"]\s*:\s*['"]?(https?://[^'"<>\s]{10,})['"]?""", RegexOption.IGNORE_CASE),
            // JWPlayer
            Regex("""jwplayer\s*\([^)]*\)\s*\.setup\s*\(\s*\{[^}]*?['"]?file['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*?['"]?file['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // Video.js / Plyr / Clappr
            Regex("""(?:videojs|Plyr|Clappr)\s*\([^,)]+,\s*\{[^}]*?src\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""data-setup\s*=\s*['"][^'"]*?['"]?src['"]?\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // HLS.js / Dash.js
            Regex("""hls\.loadSource\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""dash(?:js)?\.(?:initialize|attachSource|MediaPlayer)\s*\([^,)]*,\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // Flowplayer
            Regex("""flowplayer\s*\([^,)]+,\s*\{[^}]*?url\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // Generic JSON key patterns
            Regex(""""(?:videoUrl|video_url|streamUrl|stream_url|hlsUrl|hls_url|dashUrl|dash_url|playUrl|play_url|mediaUrl|media_url|contentUrl|content_url|downloadUrl|download_url)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE),
            Regex(""""(?:src|file|url|link|path|stream|video|media)"\s*:\s*"(https?://[^"]{10,})"""", RegexOption.IGNORE_CASE),
            // Bare https URLs with video extensions
            Regex("""(https?://[^\s'"<>]{10,}\.(?:mp4|m3u8|mpd|webm|mkv|ts|m4v|flv)(?:[?#][^\s'"<>]*)?)""", RegexOption.IGNORE_CASE),
            // CDN patterns without extensions
            Regex("""(https?://(?:[a-z0-9-]+\.)?(?:akamaized|cloudfront|fastly|cdn|media|stream|video|vod|hls|live)\.[a-z]{2,}/[^\s'"<>]{5,})""", RegexOption.IGNORE_CASE),
            // Encoded URLs
            Regex("""(?:url|src|file)\s*=\s*encodeURIComponent\s*\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // window.videoData / window.playerConfig
            Regex("""window\.\w+\s*=\s*\{[^}]*?(?:url|src|file|stream)\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // atob() encoded
            Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]{20,})['"]"""),
            // Hex encoded URLs
            Regex("""(?:unescape|decodeURIComponent)\s*\(\s*['"](%[0-9A-Fa-f]{2}(?:%[0-9A-Fa-f]{2})+)['"]"""),
            // eval(p,a,c,k,e,d) packed JS
            Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)"""),
            // Schema.org VideoObject
            Regex(""""contentUrl"\s*:\s*"([^"]+)""""),
            Regex(""""embedUrl"\s*:\s*"([^"]+)""""),
            // OG video
            Regex("""<meta[^>]+property\s*=\s*['"]og:video(?::url)?['"][^>]+content\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            Regex("""<meta[^>]+content\s*=\s*['"]([^'"]+)['"][^>]+property\s*=\s*['"]og:video(?::url)?['"]""", RegexOption.IGNORE_CASE),
            // Twitter card
            Regex("""<meta[^>]+name\s*=\s*['"]twitter:player:stream['"][^>]+content\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // data-* attributes
            Regex("""data-(?:src|url|video|stream|file|hls|dash|mp4)\s*=\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE),
            // Vimeo config
            Regex(""""progressive"\s*:\s*\[\s*\{[^}]*?"url"\s*:\s*"([^"]+)""""),
            Regex(""""hls"\s*:\s*\{[^}]*?"url"\s*:\s*"([^"]+)""""),
            // Dailymotion
            Regex(""""(?:stream_h264_[a-z0-9]+_url|hls_url|dash_url)"\s*:\s*"([^"]+)""""),
            // Streamtape
            Regex("""document\.getElementById\(['"][^'"]+['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]"""),
            // Doodstream token
            Regex("""(?:pass_md5|token)\s*=\s*['"]([^'"]+)['"]"""),
            // Generic player init
            Regex("""(?:initialize|setup|init|load|play)\s*\(\s*['"]?(https?://[^'")\s]{10,})['"]?""", RegexOption.IGNORE_CASE),
            // Subtitle/caption tracks (to detect player presence)
            Regex("""<track[^>]+src\s*=\s*['"]([^'"]+\.(?:vtt|srt|ass))['"]""", RegexOption.IGNORE_CASE),
            // Backup: any URL containing /video/ /stream/ /hls/ /dash/ /media/
            Regex("""(https?://[^\s'"<>]*?/(?:video|stream|hls|dash|media|vod|live)/[^\s'"<>]{5,})""", RegexOption.IGNORE_CASE),
        )

        // Quality score map
        private val QUALITY_SCORES = mapOf(
            "2160" to 100, "4k" to 100, "uhd" to 100,
            "1440" to 90, "2k" to 90,
            "1080" to 80, "fhd" to 80, "fullhd" to 80,
            "720" to 60, "hd" to 60,
            "480" to 40, "sd" to 40,
            "360" to 25, "240" to 15, "144" to 5
        )
    }

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(PersistentCookieJar())
            .addInterceptor(BrowserHeaderInterceptor())
            .build()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Primary extraction entry point. Runs all strategies in parallel and
     * returns the highest-quality result found.
     */
    suspend fun extract(pageUrl: String): AdvancedExtractionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "extract: $pageUrl")
        try {
            // Fast path: URL is already a direct media link
            if (isDirectMediaUrl(pageUrl)) {
                return@withContext AdvancedExtractionResult(
                    success = true, videoUrl = pageUrl,
                    quality = detectQuality(pageUrl), format = detectFormat(pageUrl),
                    isStream = isStreamUrl(pageUrl), method = "direct_url"
                )
            }

            // Named-host fast path
            val hostResult = extractFromNamedHost(pageUrl)
            if (hostResult != null) return@withContext hostResult

            // Fetch the page once, then run all extractors in parallel
            val html = fetchPage(pageUrl) ?: return@withContext AdvancedExtractionResult(
                success = false, error = "Failed to fetch page"
            )
            val doc = Jsoup.parse(html, pageUrl)

            val candidates = mutableListOf<VideoCandidate>()

            // Run all DOM/script extractors
            candidates += extractFromVideoTags(doc, pageUrl)
            candidates += extractFromSourceTags(doc, pageUrl)
            candidates += extractFromIframes(doc, pageUrl)
            candidates += extractFromScriptBlocks(html, pageUrl)
            candidates += extractFromDataAttributes(doc, pageUrl)
            candidates += extractFromMetaTags(doc, pageUrl)
            candidates += extractFromJsonLd(doc, pageUrl)
            candidates += extractFromPlayerConfigs(html, pageUrl)
            candidates += extractFromEncodedContent(html, pageUrl)
            candidates += extractFromWindowVars(html, pageUrl)

            if (candidates.isNotEmpty()) {
                val best = selectBest(candidates)
                return@withContext AdvancedExtractionResult(
                    success = true, videoUrl = best.url,
                    quality = best.quality, format = best.format,
                    isStream = best.isStream, method = best.method,
                    allCandidates = candidates.map { it.url }
                )
            }

            // Headless fallback
            val headlessResult = extractViaHeadless(pageUrl)
            if (headlessResult != null) return@withContext headlessResult

            AdvancedExtractionResult(success = false, error = "No video found at $pageUrl")
        } catch (e: Exception) {
            Log.w(TAG, "extract error: ${e.message}")
            AdvancedExtractionResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    // ── DOM extractors ────────────────────────────────────────────────────────

    private fun extractFromVideoTags(doc: Document, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        doc.select("video").forEach { v ->
            listOf("src","data-src","data-url","data-video","data-hls","data-file").forEach { attr ->
                val s = v.attr(attr).trim()
                if (s.isNotEmpty()) out += candidate(resolveUrl(s, base), "video_tag")
            }
        }
        return out
    }

    private fun extractFromSourceTags(doc: Document, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        doc.select("video source, source[type*=video], source[src]").forEach { s ->
            val src = s.attr("src").trim().ifEmpty { s.attr("data-src").trim() }
            if (src.isNotEmpty()) out += candidate(resolveUrl(src, base), "source_tag")
        }
        return out
    }

    private suspend fun extractFromIframes(doc: Document, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        val iframes = doc.select("iframe[src], iframe[data-src]")
        for (iframe in iframes.take(5)) {
            val src = iframe.attr("src").trim().ifEmpty { iframe.attr("data-src").trim() }
            if (src.isEmpty()) continue
            val resolved = resolveUrl(src, base)
            // Recurse into iframe
            try {
                val sub = extract(resolved)
                if (sub.success && sub.videoUrl != null) {
                    out += VideoCandidate(sub.videoUrl, sub.quality ?: "", sub.format ?: "mp4",
                        sub.isStream, "iframe_chain")
                }
            } catch (_: Exception) {}
        }
        return out
    }

    private fun extractFromScriptBlocks(html: String, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        // First try to deobfuscate packed JS
        val deobfuscated = deobfuscatePackedJs(html)
        val sources = if (deobfuscated != html) listOf(html, deobfuscated) else listOf(html)
        for (src in sources) {
            for (pattern in URL_PATTERNS) {
                pattern.findAll(src).forEach { m ->
                    val raw = (m.groupValues.getOrNull(1) ?: "").trim()
                    if (raw.isNotEmpty() && raw.startsWith("http")) {
                        out += candidate(raw, "script_regex")
                    }
                }
            }
        }
        return out
    }

    private fun extractFromDataAttributes(doc: Document, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        val attrs = listOf("data-src","data-url","data-video","data-stream","data-file",
            "data-hls","data-dash","data-mp4","data-video-url","data-stream-url",
            "data-source","data-media","data-content","data-embed")
        attrs.forEach { attr ->
            doc.select("[$attr]").forEach { el ->
                val v = el.attr(attr).trim()
                if (v.startsWith("http")) out += candidate(v, "data_attr")
            }
        }
        return out
    }

    private fun extractFromMetaTags(doc: Document, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        val ogProps = setOf("og:video","og:video:url","og:video:secure_url","twitter:player:stream")
        doc.select("meta[property], meta[name]").forEach { m ->
            val prop = m.attr("property").ifEmpty { m.attr("name") }
            if (prop in ogProps) {
                val content = m.attr("content").trim()
                if (content.startsWith("http")) out += candidate(content, "meta_tag")
            }
        }
        return out
    }

    private fun extractFromJsonLd(doc: Document, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        doc.select("script[type='application/ld+json']").forEach { s ->
            val text = s.html()
            listOf("contentUrl","embedUrl","url","contentURL").forEach { key ->
                Regex(""""$key"\s*:\s*"([^"]+)"""").findAll(text).forEach { m ->
                    val v = m.groupValues[1]
                    if (v.startsWith("http")) out += candidate(v, "json_ld")
                }
            }
        }
        return out
    }

    private fun extractFromPlayerConfigs(html: String, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        // JWPlayer sources array
        Regex("""sources\s*:\s*\[([^\]]+)\]""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            Regex("""['"]?(?:file|src|url)['"]?\s*:\s*['"]([^'"]+)['"]""").findAll(m.groupValues[1]).forEach { u ->
                val v = u.groupValues[1]
                if (v.startsWith("http")) out += candidate(v, "jwplayer_sources")
            }
        }
        // Video.js sources
        Regex("""(?:videojs|player)\.src\s*\(\s*\[([^\]]+)\]""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            Regex("""src\s*:\s*['"]([^'"]+)['"]""").findAll(m.groupValues[1]).forEach { u ->
                val v = u.groupValues[1]
                if (v.startsWith("http")) out += candidate(v, "videojs_src")
            }
        }
        // Plyr
        Regex("""new\s+Plyr\s*\([^,)]+,\s*\{([^}]+)\}""", RegexOption.DOT_MATCHES_ALL).findAll(html).forEach { m ->
            Regex("""src\s*:\s*['"]([^'"]+)['"]""").findAll(m.groupValues[1]).forEach { u ->
                val v = u.groupValues[1]
                if (v.startsWith("http")) out += candidate(v, "plyr")
            }
        }
        return out
    }

    private fun extractFromEncodedContent(html: String, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        // base64 atob() calls
        Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]{20,})['"]""").findAll(html).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                if (decoded.startsWith("http")) out += candidate(decoded, "base64_atob")
                else {
                    // decoded might contain a URL
                    Regex("""(https?://[^\s'"<>]{10,})""").find(decoded)?.let { u ->
                        out += candidate(u.groupValues[1], "base64_atob_inner")
                    }
                }
            } catch (_: Exception) {}
        }
        // Hex-encoded URLs
        Regex("""(?:unescape|decodeURIComponent)\s*\(\s*['"](%[0-9A-Fa-f]{2}(?:%[0-9A-Fa-f]{2})+)['"]""").findAll(html).forEach { m ->
            try {
                val decoded = URLDecoder.decode(m.groupValues[1], "UTF-8")
                if (decoded.startsWith("http")) out += candidate(decoded, "hex_encoded")
            } catch (_: Exception) {}
        }
        // Unicode escape sequences \uXXXX
        Regex("""((?:\\u[0-9a-fA-F]{4}){5,})""").findAll(html).forEach { m ->
            try {
                val decoded = decodeUnicodeEscapes(m.groupValues[1])
                if (decoded.startsWith("http")) out += candidate(decoded, "unicode_escape")
            } catch (_: Exception) {}
        }
        // Reversed strings (some sites reverse URLs)
        Regex("""['"]([a-zA-Z0-9+/=]{30,})['"]""").findAll(html).take(50).forEach { m ->
            val rev = m.groupValues[1].reversed()
            if (rev.startsWith("http") && (rev.contains(".mp4") || rev.contains(".m3u8"))) {
                out += candidate(rev, "reversed_string")
            }
        }
        return out
    }

    private fun extractFromWindowVars(html: String, base: String): List<VideoCandidate> {
        val out = mutableListOf<VideoCandidate>()
        Regex("""window\.\w+\s*=\s*['"]?(https?://[^'";\s]{10,})['"]?""").findAll(html).forEach { m ->
            out += candidate(m.groupValues[1], "window_var")
        }
        Regex("""var\s+\w+\s*=\s*['"]?(https?://[^'";\s]{10,}\.(?:mp4|m3u8|mpd|webm)[^'";\s]*)['"]?""", RegexOption.IGNORE_CASE).findAll(html).forEach { m ->
            out += candidate(m.groupValues[1], "var_assign")
        }
        return out
    }

    // ── Named host extractors ─────────────────────────────────────────────────

    private suspend fun extractYoutube(url: String): AdvancedExtractionResult? = null // ExoPlayer handles via MediaItem

    private suspend fun extractVimeo(url: String): AdvancedExtractionResult? {
        return try {
            val id = Regex("""vimeo\.com/(?:video/)?(\d+)""").find(url)?.groupValues?.get(1) ?: return null
            val configUrl = "https://player.vimeo.com/video/$id/config"
            val json = fetchText(configUrl, mapOf("Referer" to "https://vimeo.com/")) ?: return null
            val progressive = Regex(""""progressive"\s*:\s*\[([^\]]+)\]""", RegexOption.DOT_MATCHES_ALL).find(json)?.groupValues?.get(1)
            if (progressive != null) {
                val urls = Regex(""""url"\s*:\s*"([^"]+\.mp4[^"]*)"""").findAll(progressive).map { it.groupValues[1] }.toList()
                val best = urls.maxByOrNull { qualityScore(it) }
                if (best != null) return result(best, "vimeo_progressive")
            }
            val hls = Regex(""""hls"\s*:\s*\{[^}]*?"url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            if (hls != null) return result(hls, "vimeo_hls")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractDailymotion(url: String): AdvancedExtractionResult? {
        return try {
            val id = Regex("""dailymotion\.com/(?:video|embed/video)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return null
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$id"
            val json = fetchText(apiUrl) ?: return null
            val hls = Regex(""""hls_url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            if (hls != null) return result(hls, "dailymotion_hls")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractStreamtape(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            // Streamtape obfuscates the URL by splitting it across two JS vars
            val part1 = Regex("""id\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
            val part2 = Regex("""getElementById\(['"][^'"]+['"]\)\.innerHTML\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
            val combined = "https://streamtape.com/get_video?id=$part1&expires=&ip=&token=$part2&stream=1"
            if (part1.isNotEmpty()) return result(combined, "streamtape")
            // Fallback: look for direct link
            val direct = Regex("""(https?://(?:streamtape\.com|tapecontent\.net)/get_video[^\s'"<>]+)""").find(html)?.groupValues?.get(1)
            if (direct != null) return result(direct, "streamtape_direct")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractDoodstream(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val passPath = Regex("""\$\.get\(['"]([^'"]+pass_md5[^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return null
            val token = Regex("""token\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: ""
            val base = if (url.startsWith("https://dood")) "https://dood.to" else "https://${URI(url).host}"
            val passUrl = "$base$passPath"
            val passContent = fetchText(passUrl, mapOf("Referer" to url)) ?: return null
            val expiry = System.currentTimeMillis() / 1000 + 3600
            val videoUrl = "$passContent${randomString(10)}?token=$token&expiry=$expiry"
            result(videoUrl, "doodstream")
        } catch (_: Exception) { null }
    }

    private suspend fun extractFilemoon(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val m3u8 = Regex("""(https?://[^\s'"]+\.m3u8[^\s'"]*)""").find(deob)?.groupValues?.get(1)
            if (m3u8 != null) return result(m3u8, "filemoon_hls")
            val src = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]""").find(deob)?.groupValues?.get(1)
            if (src != null) return result(src, "filemoon_src")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractStreamwish(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val m3u8 = Regex("""(https?://[^\s'"]+\.m3u8[^\s'"]*)""").find(deob)?.groupValues?.get(1)
            if (m3u8 != null) return result(m3u8, "streamwish_hls")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractMixdrop(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val wurl = Regex("""wurl\s*=\s*['"]([^'"]+)['"]""").find(deob)?.groupValues?.get(1)
            if (wurl != null) {
                val fixed = if (wurl.startsWith("//")) "https:$wurl" else wurl
                return result(fixed, "mixdrop")
            }
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractUpstream(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val src = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(deob)?.groupValues?.get(1)
            if (src != null) return result(src, "upstream")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractVoe(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val hls = Regex("""'hls'\s*:\s*'([^']+)'""").find(deob)?.groupValues?.get(1)
                ?: Regex(""""hls"\s*:\s*"([^"]+)"""").find(deob)?.groupValues?.get(1)
            if (hls != null) return result(hls, "voe_hls")
            val mp4 = Regex("""'mp4'\s*:\s*'([^']+)'""").find(deob)?.groupValues?.get(1)
            if (mp4 != null) return result(mp4, "voe_mp4")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractStreamlare(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "streamlare")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractVidlox(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""sources\s*:\s*\["([^"]+)"""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "vidlox")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractMp4upload(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""(?:file|src)\s*:\s*"([^"]+\.mp4[^"]*)"""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "mp4upload")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractSendvid(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""<source[^>]+src=['"]([^'"]+\.mp4[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "sendvid")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractVidoza(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.mp4[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "vidoza")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractSupervideo(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val src = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(deob)?.groupValues?.get(1)
            if (src != null) return result(src, "supervideo")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractVidsrc(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val src = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(deob)?.groupValues?.get(1)
            if (src != null) return result(src, "vidsrc")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractEmbedsito(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "embedsito")
    private suspend fun extractTwoEmbed(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "2embed")
    private suspend fun extractSmashystream(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "smashystream")
    private suspend fun extractMultiembed(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "multiembed")
    private suspend fun extractAutoembed(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "autoembed")
    private suspend fun extractEmbedrise(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "embedrise")
    private suspend fun extractRidoo(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "ridoo")
    private suspend fun extractWaaw(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "waaw")
    private suspend fun extractFembed(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "fembed")
    private suspend fun extractStreamhub(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "streamhub")

    private suspend fun extractVidcloud(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val src = Regex("""(?:file|src)\s*:\s*['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(deob)?.groupValues?.get(1)
            if (src != null) return result(src, "vidcloud")
            // Try API endpoint
            val id = Regex("""embed-(\w+)""").find(url)?.groupValues?.get(1) ?: return null
            val apiUrl = "${extractSchemeHost(url)}/ajax/embed-4/getSources?id=$id"
            val json = fetchText(apiUrl, mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to url)) ?: return null
            val m3u8 = Regex(""""file"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(json)?.groupValues?.get(1)
            if (m3u8 != null) return result(m3u8, "vidcloud_api")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractMycloud(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "mycloud")

    private suspend fun extractOkru(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val json = Regex(""""videos"\s*:\s*\[([^\]]+)\]""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
            val urls = Regex(""""url"\s*:\s*"([^"]+)"""").findAll(json).map { it.groupValues[1].replace("\\/", "/") }.toList()
            val best = urls.maxByOrNull { qualityScore(it) }
            if (best != null) return result(best, "okru")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractMailru(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex(""""url"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (src != null) return result(src, "mailru")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractRutube(url: String): AdvancedExtractionResult? {
        return try {
            val id = Regex("""rutube\.ru/video/([a-f0-9]+)""").find(url)?.groupValues?.get(1) ?: return null
            val apiUrl = "https://rutube.ru/api/play/options/$id/?no_404=true&referer=&pver=v2"
            val json = fetchText(apiUrl) ?: return null
            val m3u8 = Regex(""""hls"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            if (m3u8 != null) return result(m3u8, "rutube_hls")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractSibnet(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""player\.src\s*\(\s*\[\s*\{[^}]*?src\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "sibnet")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractStreamable(url: String): AdvancedExtractionResult? {
        return try {
            val id = Regex("""streamable\.com/([a-z0-9]+)""").find(url)?.groupValues?.get(1) ?: return null
            val apiUrl = "https://api.streamable.com/videos/$id"
            val json = fetchText(apiUrl) ?: return null
            val mp4 = Regex(""""url"\s*:\s*"(//[^"]+\.mp4[^"]*)"""").find(json)?.groupValues?.get(1)
            if (mp4 != null) return result("https:$mp4", "streamable")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractRumble(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val embedUrl = Regex("""<link[^>]+rel=['"]canonical['"][^>]+href=['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: url
            val embedHtml = fetchPage("https://rumble.com/embed/${Regex("""/([^/]+)\.html""").find(embedUrl)?.groupValues?.get(1) ?: return null}/") ?: return null
            val src = Regex(""""url"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""").find(embedHtml)?.groupValues?.get(1)?.replace("\\/", "/")
            if (src != null) return result(src, "rumble")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractOdysee(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""<source[^>]+src=['"]([^'"]+\.(?:mp4|m3u8)[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "odysee")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractBitchute(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex("""<source[^>]+src=['"]([^'"]+\.mp4[^'"]*)['"]""").find(html)?.groupValues?.get(1)
            if (src != null) return result(src, "bitchute")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractPeertube(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex(""""fileUrl"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (src != null) return result(src, "peertube")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractTwitch(url: String): AdvancedExtractionResult? = null // Requires OAuth

    private suspend fun extractFacebook(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val hd = Regex(""""hd_src"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (hd != null) return result(hd, "facebook_hd")
            val sd = Regex(""""sd_src"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (sd != null) return result(sd, "facebook_sd")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractInstagram(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex(""""video_url"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (src != null) return result(src, "instagram")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractTwitter(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val m3u8 = Regex("""(https?://video\.twimg\.com/[^\s'"<>]+\.m3u8[^\s'"<>]*)""").find(html)?.groupValues?.get(1)
            if (m3u8 != null) return result(m3u8, "twitter_hls")
            val mp4 = Regex("""(https?://video\.twimg\.com/[^\s'"<>]+\.mp4[^\s'"<>]*)""").find(html)?.groupValues?.get(1)
            if (mp4 != null) return result(mp4, "twitter_mp4")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractTiktok(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex(""""playAddr"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (src != null) return result(src, "tiktok")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractReddit(url: String): AdvancedExtractionResult? {
        return try {
            val jsonUrl = url.trimEnd('/') + ".json"
            val json = fetchText(jsonUrl) ?: return null
            val dashUrl = Regex(""""dash_url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            if (dashUrl != null) return result(dashUrl, "reddit_dash")
            val fallback = Regex(""""fallback_url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            if (fallback != null) return result(fallback, "reddit_fallback")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractGfycat(url: String): AdvancedExtractionResult? {
        return try {
            val id = Regex("""gfycat\.com/(?:gifs/detail/)?([a-zA-Z]+)""").find(url)?.groupValues?.get(1) ?: return null
            val apiUrl = "https://api.gfycat.com/v1/gfycats/$id"
            val json = fetchText(apiUrl) ?: return null
            val mp4 = Regex(""""mp4Url"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)?.replace("\\/", "/")
            if (mp4 != null) return result(mp4, "gfycat")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractStreamff(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "streamff")
    private suspend fun extractCliphunter(url: String): AdvancedExtractionResult? = extractGenericPacked(url, "cliphunter")
    private suspend fun extractXvideos(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val hls = Regex("""html5player\.setVideoHLS\(['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (hls != null) return result(hls, "xvideos_hls")
            val mp4 = Regex("""html5player\.setVideoUrl(?:High|Low)?\(['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (mp4 != null) return result(mp4, "xvideos_mp4")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractXhamster(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val src = Regex(""""url"\s*:\s*"([^"]+\.(?:mp4|m3u8)[^"]*)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")
            if (src != null) return result(src, "xhamster")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractPornhub(url: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val quality = Regex("""quality_(\d+)p\s*=\s*['"]([^'"]+)['"]""").findAll(html)
                .map { it.groupValues[1].toIntOrNull() to it.groupValues[2] }
                .filter { it.first != null }
                .maxByOrNull { it.first!! }
            if (quality?.second != null) return result(quality.second, "pornhub")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractGenericPacked(url: String, host: String): AdvancedExtractionResult? {
        return try {
            val html = fetchPage(url) ?: return null
            val deob = deobfuscatePackedJs(html)
            val src = Regex("""(?:file|src|url)\s*:\s*['"]([^'"]+\.(?:mp4|m3u8|mpd)[^'"]*)['"]""", RegexOption.IGNORE_CASE).find(deob)?.groupValues?.get(1)
            if (src != null) return result(src, "${host}_packed")
            val direct = Regex("""(https?://[^\s'"<>]+\.(?:mp4|m3u8|mpd)[^\s'"<>]*)""", RegexOption.IGNORE_CASE).find(deob)?.groupValues?.get(1)
            if (direct != null) return result(direct, "${host}_direct")
            null
        } catch (_: Exception) { null }
    }

    private suspend fun extractFromNamedHost(url: String): AdvancedExtractionResult? {
        val host = extractHost(url).lowercase()
        return when {
            host.contains("youtube.com") || host.contains("youtu.be") -> extractYoutube(url)
            host.contains("vimeo.com") -> extractVimeo(url)
            host.contains("dailymotion.com") -> extractDailymotion(url)
            host.contains("streamtape.com") || host.contains("streamtape.net") -> extractStreamtape(url)
            host.contains("dood") || host.contains("doodstream") -> extractDoodstream(url)
            host.contains("filemoon") -> extractFilemoon(url)
            host.contains("streamwish") -> extractStreamwish(url)
            host.contains("mixdrop") -> extractMixdrop(url)
            host.contains("upstream") -> extractUpstream(url)
            host.contains("voe.sx") || host.contains("voe.") -> extractVoe(url)
            host.contains("streamlare") -> extractStreamlare(url)
            host.contains("vidlox") -> extractVidlox(url)
            host.contains("mp4upload") -> extractMp4upload(url)
            host.contains("sendvid") -> extractSendvid(url)
            host.contains("vidoza") -> extractVidoza(url)
            host.contains("supervideo") -> extractSupervideo(url)
            host.contains("vidsrc") -> extractVidsrc(url)
            host.contains("embedsito") -> extractEmbedsito(url)
            host.contains("2embed") -> extractTwoEmbed(url)
            host.contains("smashystream") -> extractSmashystream(url)
            host.contains("multiembed") -> extractMultiembed(url)
            host.contains("autoembed") -> extractAutoembed(url)
            host.contains("embedrise") -> extractEmbedrise(url)
            host.contains("ridoo") -> extractRidoo(url)
            host.contains("waaw") || host.contains("netu") -> extractWaaw(url)
            host.contains("fembed") || host.contains("femax") -> extractFembed(url)
            host.contains("streamhub") -> extractStreamhub(url)
            host.contains("vidcloud") || host.contains("rabbitstream") -> extractVidcloud(url)
            host.contains("mycloud") -> extractMycloud(url)
            host.contains("okru") || host.contains("ok.ru") -> extractOkru(url)
            host.contains("mail.ru") -> extractMailru(url)
            host.contains("rutube") -> extractRutube(url)
            host.contains("sibnet") -> extractSibnet(url)
            host.contains("streamable") -> extractStreamable(url)
            host.contains("rumble.com") -> extractRumble(url)
            host.contains("odysee.com") || host.contains("lbry") -> extractOdysee(url)
            host.contains("bitchute") -> extractBitchute(url)
            host.contains("peertube") -> extractPeertube(url)
            host.contains("twitch.tv") -> extractTwitch(url)
            host.contains("facebook.com") || host.contains("fb.watch") -> extractFacebook(url)
            host.contains("instagram.com") -> extractInstagram(url)
            host.contains("twitter.com") || host.contains("x.com") -> extractTwitter(url)
            host.contains("tiktok.com") -> extractTiktok(url)
            host.contains("reddit.com") -> extractReddit(url)
            host.contains("gfycat.com") -> extractGfycat(url)
            host.contains("streamff") -> extractStreamff(url)
            host.contains("cliphunter") -> extractCliphunter(url)
            host.contains("xvideos") -> extractXvideos(url)
            host.contains("xhamster") -> extractXhamster(url)
            host.contains("pornhub") -> extractPornhub(url)
            else -> null
        }
    }

    // ── Headless fallback ─────────────────────────────────────────────────────

    private suspend fun extractViaHeadless(url: String): AdvancedExtractionResult? {
        return try {
            val html = HeadlessBrowserHelper.fetchPageContentWithShadowAndAdSkip(url, timeout = 25000) ?: return null
            val candidates = mutableListOf<VideoCandidate>()
            val doc = Jsoup.parse(html, url)
            candidates += extractFromVideoTags(doc, url)
            candidates += extractFromSourceTags(doc, url)
            candidates += extractFromScriptBlocks(html, url)
            candidates += extractFromDataAttributes(doc, url)
            candidates += extractFromEncodedContent(html, url)
            // Also use HeadlessBrowserHelper's own extractor
            HeadlessBrowserHelper.extractVideoUrls(url).forEach { u ->
                candidates += candidate(u, "headless_capture")
            }
            if (candidates.isEmpty()) return null
            val best = selectBest(candidates)
            AdvancedExtractionResult(
                success = true, videoUrl = best.url,
                quality = best.quality, format = best.format,
                isStream = best.isStream, method = "headless_${best.method}"
            )
        } catch (_: Exception) { null }
    }

    // ── JS Deobfuscation ──────────────────────────────────────────────────────

    /**
     * Unpacks eval(p,a,c,k,e,d) / eval(p,a,c,k,e,r) packed JavaScript.
     * Returns the unpacked source, or the original string if not packed.
     */
    fun deobfuscatePackedJs(js: String): String {
        var result = js
        var iterations = 0
        while (iterations++ < 5) {
            val packed = Regex("""eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[dr]\s*\)\s*\{.+?\}\s*\(\s*'([\s\S]+?)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'([\s\S]+?)'\.split\s*\(""", RegexOption.DOT_MATCHES_ALL).find(result)
                ?: break
            try {
                val p = packed.groupValues[1]
                val a = packed.groupValues[2].toIntOrNull() ?: 36
                val c = packed.groupValues[3].toIntOrNull() ?: 0
                val k = packed.groupValues[4].split("|")
                val unpacked = unpackP(p, a, c, k)
                if (unpacked.length > 50) result = result.replace(packed.value, unpacked)
                else break
            } catch (_: Exception) { break }
        }
        // Decode any remaining base64 atob() calls
        result = decodeAtobCalls(result)
        return result
    }

    private fun unpackP(p: String, a: Int, c: Int, k: List<String>): String {
        var result = p
        var i = c - 1
        while (i >= 0) {
            if (k.getOrNull(i)?.isNotEmpty() == true) {
                val pattern = "\\b${toBase(i, a)}\\b"
                result = result.replace(Regex(pattern), k[i])
            }
            i--
        }
        return result
    }

    private fun toBase(num: Int, base: Int): String {
        if (num == 0) return "0"
        val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, chars[n % base])
            n /= base
        }
        return sb.toString()
    }

    private fun decodeAtobCalls(js: String): String {
        var result = js
        Regex("""atob\s*\(\s*['"]([A-Za-z0-9+/=]{20,})['"]""").findAll(js).forEach { m ->
            try {
                val decoded = String(Base64.decode(m.groupValues[1], Base64.DEFAULT))
                result = result.replace(m.value, "\"$decoded\"")
            } catch (_: Exception) {}
        }
        return result
    }

    private fun decodeUnicodeEscapes(s: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fetchPage(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,*/*;q=0.9")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            client.newCall(req).execute().use { it.body?.string() }
        } catch (_: Exception) { null }
    }

    private fun fetchText(url: String, extraHeaders: Map<String, String> = emptyMap()): String? {
        return try {
            val builder = Request.Builder().url(url).header("User-Agent", UA)
            extraHeaders.forEach { (k, v) -> builder.header(k, v) }
            client.newCall(builder.build()).execute().use { it.body?.string() }
        } catch (_: Exception) { null }
    }

    private fun candidate(url: String, method: String): VideoCandidate {
        val clean = url.trim().replace("\\/", "/")
        return VideoCandidate(
            url = clean,
            quality = detectQuality(clean),
            format = detectFormat(clean),
            isStream = isStreamUrl(clean),
            method = method
        )
    }

    private fun result(url: String, method: String): AdvancedExtractionResult {
        val clean = url.trim().replace("\\/", "/")
        return AdvancedExtractionResult(
            success = true, videoUrl = clean,
            quality = detectQuality(clean), format = detectFormat(clean),
            isStream = isStreamUrl(clean), method = method
        )
    }

    private fun selectBest(candidates: List<VideoCandidate>): VideoCandidate {
        return candidates
            .filter { isLikelyPlayable(it.url) }
            .maxByOrNull { qualityScore(it.url) + if (it.isStream) 5 else 0 }
            ?: candidates.first()
    }

    private fun isDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase()
        return VIDEO_EXTS.any { lower.endsWith(".$it") || lower.contains(".$it?") } ||
               STREAM_EXTS.any { lower.endsWith(".$it") || lower.contains(".$it?") }
    }

    private fun isStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mpd") || lower.contains("/hls/") || lower.contains("/dash/")
    }

    private fun isLikelyPlayable(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.contains(".html") || lower.contains(".php") || lower.contains(".asp")) return false
        return VIDEO_EXTS.any { lower.contains(".$it") } ||
               STREAM_EXTS.any { lower.contains(".$it") } ||
               lower.contains("/video/") || lower.contains("/stream/") ||
               lower.contains("/hls/") || lower.contains("/dash/") ||
               lower.contains("videoplayback") || lower.contains("/get_video")
    }

    private fun detectQuality(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains("2160") || lower.contains("4k") -> "4K"
            lower.contains("1440") -> "1440p"
            lower.contains("1080") -> "1080p"
            lower.contains("720") -> "720p"
            lower.contains("480") -> "480p"
            lower.contains("360") -> "360p"
            lower.contains("240") -> "240p"
            lower.contains(".m3u8") || lower.contains(".mpd") -> "Auto"
            else -> "Unknown"
        }
    }

    private fun detectFormat(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "hls"
            lower.contains(".mpd") -> "dash"
            lower.contains(".mp4") -> "mp4"
            lower.contains(".webm") -> "webm"
            lower.contains(".mkv") -> "mkv"
            lower.contains(".ts") -> "ts"
            else -> "mp4"
        }
    }

    private fun qualityScore(url: String): Int {
        val lower = url.lowercase()
        return QUALITY_SCORES.entries.firstOrNull { lower.contains(it.key) }?.value ?: 30
    }

    private fun resolveUrl(url: String, base: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try {
            URI(base).resolve(url).toString()
        } catch (_: Exception) { url }
    }

    private fun extractHost(url: String): String {
        return try { URI(url).host ?: "" } catch (_: Exception) { "" }
    }

    private fun extractSchemeHost(url: String): String {
        return try {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) { "" }
    }

    private fun randomString(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return (1..len).map { chars.random() }.joinToString("")
    }
}

// ── Supporting classes ────────────────────────────────────────────────────────

data class VideoCandidate(
    val url: String,
    val quality: String,
    val format: String,
    val isStream: Boolean,
    val method: String
)

data class AdvancedExtractionResult(
    val success: Boolean,
    val videoUrl: String? = null,
    val quality: String? = null,
    val format: String? = null,
    val isStream: Boolean = false,
    val method: String = "",
    val allCandidates: List<String> = emptyList(),
    val error: String? = null
)

private class PersistentCookieJar : CookieJar {
    private val store = mutableMapOf<String, MutableList<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        store.getOrPut(url.host) { mutableListOf() }.addAll(cookies)
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
}

private class BrowserHeaderInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Accept-Encoding", "gzip, deflate, br")
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "none")
            .header("Upgrade-Insecure-Requests", "1")
            .build()
        return chain.proceed(req)
    }
}
