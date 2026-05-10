package com.aggregatorx.app.engine.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * VisionEngine — ML Kit OCR pipeline for thumbnail keyword extraction.
 *
 * For each result thumbnail URL:
 *  1. Downloads the image via OkHttp
 *  2. Runs ML Kit on-device text recognition (Latin script)
 *  3. Returns extracted keywords for ranking / MY AI preference scoring
 *
 * Results are cached in-memory to avoid redundant network + OCR work.
 */
@Singleton
class VisionEngine @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "VisionEngine"
        // Stop-words to filter from OCR output
        private val STOP_WORDS = setOf(
            "the", "a", "an", "and", "or", "of", "in", "on", "at", "to",
            "for", "is", "it", "this", "that", "with", "from", "by", "as"
        )
    }

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val ocrCache   = ConcurrentHashMap<String, List<String>>() // url → keywords

    /**
     * Extract keywords from a thumbnail URL using ML Kit OCR.
     * Returns an empty list if the image cannot be fetched or contains no text.
     */
    suspend fun extractKeywordsFromThumbnail(thumbnailUrl: String): List<String> {
        if (thumbnailUrl.isBlank()) return emptyList()
        ocrCache[thumbnailUrl]?.let { return it }

        return withContext(Dispatchers.IO) {
            try {
                val bitmap = downloadBitmap(thumbnailUrl) ?: return@withContext emptyList()
                val keywords = runOcr(bitmap)
                ocrCache[thumbnailUrl] = keywords
                Log.d(TAG, "OCR extracted ${keywords.size} keywords from $thumbnailUrl")
                keywords
            } catch (e: Exception) {
                Log.w(TAG, "VisionEngine failed for $thumbnailUrl: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Score a search result's relevance to a query using OCR keywords from its thumbnail.
     * Returns a 0–100 float boost score.
     */
    suspend fun scoreResultByThumbnail(thumbnailUrl: String, query: String): Float {
        val keywords = extractKeywordsFromThumbnail(thumbnailUrl)
        if (keywords.isEmpty()) return 0f
        val queryTokens = query.lowercase().split("\\s+".toRegex()).filter { it.length > 2 }
        val matches = queryTokens.count { token -> keywords.any { kw -> kw.contains(token) } }
        return (matches.toFloat() / queryTokens.size.coerceAtLeast(1)) * 100f
    }

    /** Batch-process multiple thumbnails and return a map of url → keywords. */
    suspend fun batchExtract(thumbnailUrls: List<String>): Map<String, List<String>> {
        val results = mutableMapOf<String, List<String>>()
        for (url in thumbnailUrls.distinct().take(30)) { // cap to avoid thermal spike
            results[url] = extractKeywordsFromThumbnail(url)
        }
        return results
    }

    fun clearCache() = ocrCache.clear()

    // ── INTERNAL ──────────────────────────────────────────────────────────────

    private fun downloadBitmap(url: String): Bitmap? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android)")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body?.bytes() ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }

    private suspend fun runOcr(bitmap: Bitmap): List<String> =
        suspendCancellableCoroutine { cont ->
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val words = visionText.text
                        .lowercase()
                        .split("\\s+|\\n+".toRegex())
                        .map { it.replace("[^a-z0-9]".toRegex(), "") }
                        .filter { it.length > 2 && it !in STOP_WORDS }
                        .distinct()
                    cont.resume(words)
                }
                .addOnFailureListener { e ->
                    Log.w(TAG, "OCR failed: ${e.message}")
                    cont.resume(emptyList())
                }
        }
}
