package com.aggregatorx.app.engine.ml

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object LlamaModelManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inferenceMutex = Mutex()
    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady
    private var handle: Long = 0L
    private var nativeAvailable = false

    fun loadIfAvailable(context: Context) {
        scope.launch {
            if (_isReady.value) return@launch
            val model = ModelDownloadManager.modelFile(context)
            if (!ModelDownloadManager.isValidModel(model)) return@launch
            nativeAvailable = runCatching { System.loadLibrary("llama") }.isSuccess ||
                runCatching { System.loadLibrary("llama_android") }.isSuccess
            if (!nativeAvailable) {
                _isReady.value = false
                return@launch
            }
            handle = runCatching { nativeLoadModel(model.absolutePath) }.getOrDefault(0L)
            _isReady.value = handle != 0L
        }
    }

    suspend fun infer(prompt: String): String = inferenceMutex.withLock {
        if (!_isReady.value || handle == 0L) {
            return@withLock """{"valid":false,"reason":"llama model not ready","prompt_accepted":true}"""
        }
        runCatching { nativeInfer(handle, prompt) }
            .getOrElse { """{"valid":false,"reason":"${it.message ?: "inference failed"}"}""" }
    }

    fun unload() {
        if (handle != 0L) runCatching { nativeFreeModel(handle) }
        handle = 0L
        _isReady.value = false
    }

    private external fun nativeLoadModel(path: String): Long
    private external fun nativeInfer(handle: Long, prompt: String): String
    private external fun nativeFreeModel(handle: Long)
}
