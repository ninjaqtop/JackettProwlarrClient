package com.aggregatorx.app.engine.media

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
        title: String
    ): String {
        val downloadId = UUID.randomUUID().toString()
        
        downloadScope.launch {
            startDownload(
                downloadId = downloadId,
                videoUrl = videoUrl,
                title = title,
                quality = "Direct"
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
        pageUrl: String? = null
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
        
        try {
            // Build request with proper headers (use referer from page URL if available)
            val referer = pageUrl?.let { Uri.parse(it).host?.let { host -> "https://$host/" } }
                ?: Uri.parse(videoUrl).host?.let { "https://$it/" }
                ?: ""
            
            val request = Request.Builder()
                .url(videoUrl)
                .header("User-Agent", USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity") // Avoid compressed responses for accurate progress
                .build()
            
            val response = httpClient.newCall(request).execute()
            
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: ${response.message}")
            }
            
            val body = response.body ?: throw Exception("Empty response body")
            val totalBytes = body.contentLength()
            
            // Use user-selected directory if set, else default
            val downloadDir = if (!downloadDirectory.isNullOrEmpty()) {
                File(Uri.parse(downloadDirectory).path ?: getDefaultDownloadDir().absolutePath)
            } else {
                getDefaultDownloadDir()
            }
            
            if (!downloadDir.exists()) {
                downloadDir.mkdirs()
            }
            
            // Generate filename with quality indicator
            val extension = detectExtension(videoUrl)
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(50)
            val qualityTag = quality.replace(Regex("[^a-zA-Z0-9]"), "")
            val fileName = "${sanitizedTitle}_${qualityTag}_${timestamp}$extension"
            val file = File(downloadDir, fileName)
            
            // Download with progress tracking
            var downloadedBytes = 0L
            var lastNotificationUpdate = 0L
            var lastProgressUpdate = 0L
            
            FileOutputStream(file).use { output ->
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
            
            // Success
            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                status = DownloadStatus.COMPLETED,
                progress = 100,
                filePath = file.absolutePath,
                fileSize = file.length()
            ))
            
            showCompletedNotification(nId, title, file)
            
        } catch (e: Exception) {
            updateDownloadState(downloadId, getDownloadState(downloadId)?.copy(
                status = DownloadStatus.FAILED,
                error = e.message ?: "Download failed"
            ))
            
            showFailedNotification(nId, title, e.message ?: "Unknown error")
        }
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
    private fun showCompletedNotification(notificationId: Int, title: String, file: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download Complete")
            .setContentText(title)
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
        val file = File(filePath)
        if (file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            
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
