package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.memory.ProviderMemoryStore

object LlamaRuntime {
    suspend fun analyze(input: String, mode: AnalysisMode, providerId: String): String {
        val context = ProviderMemoryStore.getProviderContext(providerId)
        val prompt = """
            You are AggregatorX on-device provider analysis.
            Return JSON only.
            Mode: $mode
            $context
            Input:
            $input
        """.trimIndent()
        return LlamaModelManager.infer(prompt)
    }
}
