package com.aggregatorx.app.engine.ml

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
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
    private var model: LlamaModel? = null
    private var handle: Long = 0L

    fun loadIfAvailable(context: Context) {
        scope.launch {
            if (_isReady.value) return@launch
            val model = ModelDownloadManager.modelFile(context)
            if (!ModelDownloadManager.isValidModel(model)) return@launch
            load(model.absolutePath)
        }
    }

    suspend fun load(path: String) = inferenceMutex.withLock {
        if (_isReady.value && model?.isLoaded == true) return@withLock
        val config = LlamaConfig(
            contextSize = 4096,
            threads = Runtime.getRuntime().availableProcessors().coerceIn(4, 8),
            gpuLayers = 0,
            temperature = 0.1f,
            topP = 0.9f,
            topK = 40,
            seed = -1
        )
        val loaded = Llama.loadModel(path, config)
        model = loaded
        handle = runCatching {
            val method = loaded.javaClass.getDeclaredMethod("getHandle\$library_release")
            method.isAccessible = true
            method.invoke(loaded) as Long
        }.getOrDefault(0L)
        _isReady.value = loaded.isLoaded
    }

    suspend fun infer(prompt: String): String = inferenceMutex.withLock {
        val activeModel = model
        if (!_isReady.value || activeModel == null || !activeModel.isLoaded) {
            return@withLock """{"valid":false,"reason":"llama model not ready","prompt_accepted":true}"""
        }
        runCatching {
            Llama.complete(activeModel, prompt, "<|end|>", 768).text
        }
            .getOrElse { """{"valid":false,"reason":"${it.message ?: "inference failed"}"}""" }
    }

    fun unload() {
        model?.let { runCatching { Llama.releaseModel(it) } }
        model = null
        handle = 0L
        _isReady.value = false
    }
}
