package com.aggregatorx.app.engine.ml

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class DownloadState {
    data object Idle : DownloadState()
    data object Ready : DownloadState()
    data object Queued : DownloadState()
    data class Downloading(val progress: Int, val bytesRead: Long, val totalBytes: Long) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

object ModelDownloadManager {
    const val MODEL_FILE_NAME = "Phi-3-mini-4k-instruct-Q4_K_M.gguf"
    const val MODEL_URL = "https://huggingface.co/bartowski/Phi-3-mini-4k-instruct-GGUF/resolve/main/Phi-3-mini-4k-instruct-Q4_K_M.gguf"
    const val MODEL_SHA256 = "28a89b4ddb5766355f24e362ae4078b4c35b9ca9568df5fc9e6d9aeee4dee834"
    private const val UNIQUE_WORK = "phi3-mini-gguf-download"

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        if (isValidModel(modelFile(context))) {
            _state.value = DownloadState.Ready
            LlamaModelManager.loadIfAvailable(context.applicationContext)
        } else {
            enqueueDownload(context.applicationContext)
        }
    }

    fun ensureModel(context: Context) {
        if (isValidModel(modelFile(context))) {
            _state.value = DownloadState.Ready
            LlamaModelManager.loadIfAvailable(context.applicationContext)
        } else {
            enqueueDownload(context.applicationContext)
        }
    }

    fun retryDownload(context: Context) {
        _state.value = DownloadState.Queued
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.REPLACE,
            createDownloadRequest()
        )
    }

    fun modelFile(context: Context): File = File(context.filesDir, MODEL_FILE_NAME)

    fun updateState(state: DownloadState) {
        _state.value = state
        if (state is DownloadState.Ready) {
            appContext?.let { LlamaModelManager.loadIfAvailable(it) }
        }
    }

    private fun enqueueDownload(context: Context) {
        _state.value = DownloadState.Queued
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_WORK,
            ExistingWorkPolicy.KEEP,
            createDownloadRequest()
        )
    }

    private fun createDownloadRequest() = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

    fun isValidModel(file: File): Boolean = file.exists() && file.isFile && file.length() > 1024L * 1024L && sha256OrNull(file) == MODEL_SHA256

    fun sha256OrNull(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()
}

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val target = ModelDownloadManager.modelFile(applicationContext)
        val partial = File(target.parentFile, "${target.name}.partial")
        runCatching {
            partial.delete()
            val request = Request.Builder()
                .url(ModelDownloadManager.MODEL_URL)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Model download failed: HTTP ${response.code}")
                val body = response.body ?: error("Model download failed: empty body")
                val total = body.contentLength().coerceAtLeast(0L)
                var readTotal = 0L
                body.byteStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            readTotal += read
                            val progress = if (total > 0) ((readTotal * 100) / total).toInt().coerceIn(0, 99) else 0
                            setProgress(Data.Builder().putInt("progress", progress).build())
                            ModelDownloadManager.updateState(DownloadState.Downloading(progress, readTotal, total))
                        }
                    }
                }
            }
            val checksum = ModelDownloadManager.sha256OrNull(partial)
            if (checksum != ModelDownloadManager.MODEL_SHA256) {
                partial.delete()
                error("Model checksum mismatch")
            }
            if (target.exists()) target.delete()
            check(partial.renameTo(target)) { "Could not finalize model file" }
            ModelDownloadManager.updateState(DownloadState.Ready)
            Result.success()
        }.getOrElse { error ->
            partial.delete()
            ModelDownloadManager.updateState(DownloadState.Failed(error.message ?: "Model download failed"))
            Result.retry()
        }
    }
}
