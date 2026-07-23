package com.aggregatorx.app.engine.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.aggregatorx.app.engine.network.TlsFingerprintEngine
import com.aggregatorx.app.engine.scraper.HeadlessBrowserHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AggravatedX Enhanced Download Manager
 * 
 * Features:
 * - Automatic highest quality selection
 * - Video extraction with headless browser fallback
 * - Auto-click ad bypass during extraction
 * - Progress tracking with notifications
 * - Concurrent download management
 * - Resume capability
 * - Auto-retry on failure with multiple fallback methods
 * - HLS stream downloading support
 */
@Singleton
class DownloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val videoExtractor: VideoExtractorEngine,
    var downloadDirectory: String? = null
) {
    
    private val httpClient = TlsFingerprintEngine().apply(OkHttpClient.Builder(), TlsFingerprintEngine.Profile.CHROME)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            // Add necessary headers for video downloads
            val request = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Connection", "keep-alive")
                .build()
            chain.proceed(request)
        }
        .build()
    
    private val downloadScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    
    private val _downloads = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadState>> = _downloads.asStateFlow()
    
    private val notificationId = AtomicInteger(1000)
    
    companion object {
        private const val CHANNEL_ID = "aggregatorx_downloads"
        private const val CHANNEL_NAME = "Downloads"
        private val USER_AGENT = com.aggregatorx.app.engine.util.EngineUtils.DEFAULT_USER_AGENT
        
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_RETRY_ATTEMPTS = 3
    }
    
    init {
        createNotificationChannel()
    }
    
    /**
     * Start a download from page URL - extracts video first using enhanced extraction
     * Automatically selects highest quality available
     */
    suspend fun downloadFromPage(
        pageUrl: String,
        title: String
    ): String {
        val downloadId = UUID.randomUUID().toString()
        
        updateDownloadState(downloadId, DownloadState(
            id = downloadId,
            title = title,
            pageUrl = pageUrl,
            status = DownloadStatus.EXTRACTING,
            progress = 0
        ))
        
        downloadScope.launch {
            var retryCount = 0
            var lastError: String? = null
            
            while (retryCount < MAX_RETRY_ATTEMPTS) {
                try {
                    // Use enhanced extraction with automatic highest quality selection
                    val result = videoExtractor.extractVideoUrl(pageUrl)
                    
                    if (result.success && result.videoUrl != null) {
                        // Start actual download with the best quality URL
                        startDownload(
                            downloadId = downloadId,
                            videoUrl = result.videoUrl,
                            title = title,
                            quality = result.quality ?: "Best Quality",
                            pageUrl = pageUrl
                        )
                        return@launch // Success, exit loop
                    } else {
                        lastError = result.error ?: "Failed to extract video"
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Extraction failed"
                }
                
                retryCount++
                
                if (retryCount < MAX_RETRY_ATTEMPTS) {
                    // Wait before retry with exponential backoff
                    delay(1000L * retryCount)
                    
                    // Try with headless browser fallback on retry
                    if (retryCount == 2) {
                        try {
                            val headlessUrls = HeadlessBrowserHelper.extractVideoUrls(pageUrl)
                            if (headlessUrls.isNotEmpty()) {
                                val bestUrl = headlessUrls.first() // Already sorted by quality
                                startDownload(
                                    downloadId = downloadId,
                                    videoUrl = bestUrl,
                                    title = title,
                                    quality = "Best Quality",
                                    pageUrl = pageUrl
                                )
                                return@launch
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            
            // All retries failed
            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                status = DownloadStatus.FAILED,
                error = lastError ?: "Failed to extract video after $MAX_RETRY_ATTEMPTS attempts"
            ))
        }
        
        return downloadId
    }
    
    /**
     * Start download from direct video URL
     */
    suspend fun downloadDirect(
        videoUrl: String,
        title: String,
        headers: Map<String, String> = emptyMap(),
        pageUrl: String? = null
    ): String {
        val downloadId = UUID.randomUUID().toString()
        
        downloadScope.launch {
            startDownload(
                downloadId = downloadId,
                videoUrl = videoUrl,
                title = title,
                quality = "Direct",
                pageUrl = pageUrl,
                requestHeaders = headers
            )
        }
        
        return downloadId
    }
    
    /**
     * Internal download implementation with robust error handling
     */
    private suspend fun startDownload(
        downloadId: String,
        videoUrl: String,
        title: String,
        quality: String,
        pageUrl: String? = null,
        requestHeaders: Map<String, String> = emptyMap()
    ) = withContext(Dispatchers.IO) {
        val nId = notificationId.getAndIncrement()
        
        updateDownloadState(downloadId, DownloadState(
            id = downloadId,
            title = title,
            pageUrl = pageUrl ?: videoUrl,
            videoUrl = videoUrl,
            quality = quality,
            status = DownloadStatus.DOWNLOADING,
            progress = 0,
            notificationId = nId
        ))
        
        showDownloadNotification(nId, title, 0)
        
        var target: DownloadTarget? = null
        try {
            if (videoUrl.contains(".m3u8", ignoreCase = true)) {
                downloadHls(
                    downloadId = downloadId,
                    notificationId = nId,
                    videoUrl = videoUrl,
                    title = title,
                    quality = quality,
                    pageUrl = pageUrl,
                    requestHeaders = requestHeaders
                )
                return@withContext
            }
            if (videoUrl.contains(".mpd", ignoreCase = true)) {
                throw IllegalArgumentException("DASH manifest downloads are not yet a single downloadable media file")
            }

            // Build request with proper headers (use referer from page URL if available)
            val referer = pageUrl?.let { Uri.parse(it).host?.let { host -> "https://$host/" } }
                ?: Uri.parse(videoUrl).host?.let { "https://$it/" }
                ?: ""
            
            val requestBuilder = Request.Builder()
                .url(videoUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity") // Avoid compressed responses for accurate progress
            requestHeaders.forEach { (name, value) -> requestBuilder.header(name, value) }
            val request = requestBuilder.build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            
            val body = response.body ?: throw Exception("Empty response body")
            val totalBytes = body.contentLength()
            
            // Generate filename with quality indicator
            val extension = detectExtension(videoUrl)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
            val qualityTag = quality.replace(Regex("[^a-zA-Z0-9]"), "")
            val fileName = "${sanitizedTitle}_${qualityTag}_${timestamp}$extension"
            target = createDownloadTarget(fileName, mimeTypeFor(extension))
            
            // Download with progress tracking
            var downloadedBytes = 0L
            var lastNotificationUpdate = 0L
            var lastProgressUpdate = 0L
            
            target.output.use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(32768) // 32KB buffer
                    var bytesRead: Int
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        // Check if download was cancelled
                        if (getDownloadState(downloadId)?.status == DownloadStatus.CANCELLED) {
                            throw Exception("Download cancelled")
                        }
                        
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        
                        val progress = if (totalBytes > 0) {
                            ((downloadedBytes * 100) / totalBytes).toInt()
                        } else {
                            -1 // Indeterminate
                        }
                        
                        // Update state every 100ms to avoid too frequent updates
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100) {
                            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                                progress = progress,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes
                            ))
                            lastProgressUpdate = now
                        }
                        
                        // Update notification every 500ms
                        if (now - lastNotificationUpdate > 500) {
                            updateDownloadNotification(nId, title, progress)
                            lastNotificationUpdate = now
                        }
                    }
                }
            }
            finalizeDownloadTarget(target)
            
            // Success
            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                filePath = target.location,
                fileSize = downloadedBytes
            ))
            
            showCompletedNotification(nId, title, target.location)
            
        } catch (e: Exception) {
            target?.let(::deleteDownloadTarget)
            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                status = DownloadStatus.FAILED,
                error = e.message ?: "Download failed"
            ))
            
            showFailedNotification(nId, title, e.message ?: "Unknown error")
        }
    }

    private suspend fun downloadHls(
        downloadId: String,
        notificationId: Int,
        videoUrl: String,
        title: String,
        quality: String,
        pageUrl: String?,
        requestHeaders: Map<String, String>
    ) {
        val initialPlaylist = fetchText(videoUrl, pageUrl, requestHeaders)
        val mediaUrl = selectHlsMediaPlaylist(videoUrl, initialPlaylist)
        val playlist = if (mediaUrl == videoUrl) initialPlaylist else fetchText(mediaUrl, pageUrl, requestHeaders)
        if (!playlist.contains("#EXT-X-ENDLIST")) {
            throw IllegalArgumentException("Live HLS streams cannot be saved as a complete finite video")
        }
        if (playlist.contains("#EXT-X-KEY") && !playlist.contains("METHOD=NONE")) {
            throw IllegalArgumentException("Encrypted HLS downloads are not supported")
        }
        if (playlist.contains("#EXT-X-BYTERANGE")) {
            throw IllegalArgumentException("Byte-range HLS downloads are not supported")
        }

        val initSegment = Regex("""#EXT-X-MAP:.*URI=[\"']([^\"']+)[\"']""")
            .find(playlist)?.groupValues?.getOrNull(1)
        val segmentUrls = buildList {
            initSegment?.let { add(resolveUrl(mediaUrl, it)) }
            playlist.lineSequence()
                .map(String::trim)
                .filter { it.isNotBlank() && !it.startsWith('#') }
                .forEach { add(resolveUrl(mediaUrl, it)) }
        }
        if (segmentUrls.isEmpty()) throw IllegalArgumentException("HLS playlist contains no media segments")

        val extension = if (initSegment != null) ".mp4" else ".ts"
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
        val qualityTag = quality.replace(Regex("[^a-zA-Z0-9]"), "")
        val target = createDownloadTarget(
            "${sanitizedTitle}_${qualityTag}_${timestamp}$extension",
            mimeTypeFor(extension)
        )
        var downloadedBytes = 0L
        try {
            target.output.use { output ->
                segmentUrls.forEachIndexed { index, segmentUrl ->
                    val request = mediaRequest(segmentUrl, pageUrl ?: mediaUrl, requestHeaders)
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error("HLS segment ${index + 1} failed: HTTP ${response.code}")
                        val body = response.body ?: error("HLS segment ${index + 1} had no body")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (getDownloadState(downloadId)?.status == DownloadStatus.CANCELLED) {
                                    error("Download cancelled")
                                }
                                output.write(buffer, 0, read)
                                downloadedBytes += read
                            }
                        }
                    }
                    val progress = ((index + 1) * 100 / segmentUrls.size).coerceIn(0, 100)
                    updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                        progress = progress,
                        downloadedBytes = downloadedBytes,
                        totalBytes = -1L
                    ))
                    updateDownloadNotification(notificationId, title, progress)
                }
            }
            finalizeDownloadTarget(target)
            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                filePath = target.location,
                fileSize = downloadedBytes
            ))
            showCompletedNotification(notificationId, title, target.location)
        } catch (error: Throwable) {
            deleteDownloadTarget(target)
            throw error
        }
    }

    private fun fetchText(url: String, pageUrl: String?, headers: Map<String, String>): String {
        val request = mediaRequest(url, pageUrl ?: url, headers)
        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Manifest request failed: HTTP ${response.code}")
            response.body?.string() ?: error("Manifest response was empty")
        }
    }

    private fun mediaRequest(url: String, referer: String, headers: Map<String, String>): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
        headers.forEach { (name, value) -> builder.header(name, value) }
        return builder.build()
    }

    private fun selectHlsMediaPlaylist(masterUrl: String, playlist: String): String {
        val lines = playlist.lineSequence().map(String::trim).toList()
        val variants = lines.mapIndexedNotNull { index, line ->
            if (!line.startsWith("#EXT-X-STREAM-INF")) return@mapIndexedNotNull null
            val relative = lines.drop(index + 1).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                ?: return@mapIndexedNotNull null
            val bandwidth = Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            bandwidth to resolveUrl(masterUrl, relative)
        }
        return variants.maxByOrNull { it.first }?.second ?: masterUrl
    }

    private fun resolveUrl(baseUrl: String, value: String): String =
        runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)

    private data class DownloadTarget(
        val location: String,
        val output: OutputStream,
        val contentUri: Uri? = null,
        val file: File? = null
    )

    private fun createDownloadTarget(fileName: String, mimeType: String): DownloadTarget {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/AggregatorX")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create download in MediaStore")
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: run {
                    context.contentResolver.delete(uri, null, null)
                    error("Could not open download output")
                }
            return DownloadTarget(uri.toString(), output, contentUri = uri)
        }

        val directory = getDefaultDownloadDir().apply { mkdirs() }
        val file = File(directory, fileName)
        return DownloadTarget(file.absolutePath, FileOutputStream(file), file = file)
    }

    private fun finalizeDownloadTarget(target: DownloadTarget) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && target.contentUri != null) {
            val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            context.contentResolver.update(target.contentUri, values, null, null)
        }
    }

    private fun deleteDownloadTarget(target: DownloadTarget) {
        runCatching { target.output.close() }
        target.contentUri?.let { context.contentResolver.delete(it, null, null) }
        target.file?.delete()
    }

    private fun mimeTypeFor(extension: String): String = when (extension.lowercase()) {
        ".mp4", ".m4v" -> "video/mp4"
        ".webm" -> "video/webm"
        ".mkv" -> "video/x-matroska"
        ".ts" -> "video/mp2t"
        ".mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }
    
    /**
     * Detect file extension from URL
     */
    private fun detectExtension(videoUrl: String): String {
        val urlLower = videoUrl.lowercase()
        return when {
            urlLower.contains(".mp4") -> ".mp4"
            urlLower.contains(".m3u8") -> ".mp4" // HLS streams save as mp4
            urlLower.contains(".webm") -> ".webm"
            urlLower.contains(".mkv") -> ".mkv"
            urlLower.contains(".avi") -> ".avi"
            urlLower.contains(".mov") -> ".mov"
            urlLower.contains(".mpd") -> ".mp4" // DASH streams save as mp4
            else -> ".mp4" // Default to mp4
        }
    }
    
    /**
     * Get default download directory
     */
    private fun getDefaultDownloadDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AggregatorX"
        )
    }
    
    /**
     * Pause a download
     */
    fun pauseDownload(downloadId: String) {
        getDownloadState(downloadId)?.let { state ->
            if (state.status == DownloadStatus.DOWNLOADING) {
                updateDownloadState(downloadId, state.copy(status = DownloadStatus.PAUSED))
            }
        }
    }
    
    /**
     * Cancel a download
     */
    fun cancelDownload(downloadId: String) {
        getDownloadState(downloadId)?.let { state ->
            updateDownloadState(downloadId, state.copy(status = DownloadStatus.CANCELLED))
            state.notificationId?.let { notificationManager.cancel(it) }
        }
    }
    
    /**
     * Remove a download from the list
     */
    fun removeDownload(downloadId: String) {
        _downloads.value = _downloads.value - downloadId
    }
    
    /**
     * Get download state
     */
    private fun getDownloadState(downloadId: String): DownloadState? {
        return _downloads.value[downloadId]
    }
    
    /**
     * Update download state
     */
    private fun updateDownloadState(downloadId: String, state: DownloadState?) {
        if (state != null) {
            _downloads.value = _downloads.value + (downloadId to state)
        }
    }
    
    /**
     * Get download directory
     */
    private fun getDownloadDirectory(): File {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AggregatorX"
        )
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    /**
     * Create notification channel
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Download progress notifications"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Show download progress notification
     */
    private fun showDownloadNotification(notificationId: Int, title: String, progress: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        
        if (progress >= 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        
        notificationManager.notify(notificationId, builder.build())
    }
    
    /**
     * Update download notification
     */
    private fun updateDownloadNotification(notificationId: Int, title: String, progress: Int) {
        showDownloadNotification(notificationId, title, progress)
    }
    
    /**
     * Show completed notification
     */
    private fun showCompletedNotification(notificationId: Int, title: String, location: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText("$title saved to Downloads/AggregatorX")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        
        notificationManager.notify(notificationId, builder.build())
    }
    
    /**
     * Show failed notification
     */
    private fun showFailedNotification(notificationId: Int, title: String, error: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Download Failed")
            .setContentText("$title: $error")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        
        notificationManager.notify(notificationId, builder.build())
    }
    
    /**
     * Open downloaded file
     */
    fun openFile(filePath: String) {
        val uri = if (filePath.startsWith("content://")) {
            Uri.parse(filePath)
        } else {
            val file = File(filePath)
            if (!file.exists()) return
            FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        }
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

data class DownloadState(
    val id: String,
    val title: String,
    val pageUrl: String,
    val videoUrl: String? = null,
    val quality: String? = null,
    val status: DownloadStatus,
    val progress: Int = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val filePath: String? = null,
    val fileSize: Long = 0,
    val error: String? = null,
    val notificationId: Int? = null
)

enum class DownloadStatus {
    PENDING,
    EXTRACTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
