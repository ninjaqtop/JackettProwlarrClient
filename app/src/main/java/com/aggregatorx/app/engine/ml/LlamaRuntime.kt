package com.aggregatorx.app.engine.ml

import com.aggregatorx.app.data.memory.ProviderMemoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object LlamaRuntime {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun analyze(input: String, mode: AnalysisMode, providerId: String): String {
        val context = ProviderMemoryStore.getProviderContext(providerId)
        if (!LlamaModelManager.isReady.value) {
            return deterministicFallback(input, mode, providerId)
        }
        val prompt = """
            <|system|>
            You are AggregatorX on-device provider analysis running fully on Android.
            Return ONLY compact JSON. Do not add markdown, prose, comments, or code fences.
            Mode: $mode
            $context
            Required schema:
            ${schemaFor(mode)}
            <|end|>
            <|user|>
            Input:
            $input
            <|end|>
            <|assistant|>
        """.trimIndent()
        val raw = LlamaModelManager.infer(prompt)
        val extracted = extractJson(raw)
        persistLearning(extracted, mode, providerId)
        return extracted
    }

    private suspend fun persistLearning(output: String, mode: AnalysisMode, providerId: String) {
        val root = runCatching { json.parseToJsonElement(output).jsonObject }.getOrNull() ?: return
        when (mode) {
            AnalysisMode.CATEGORY_FIX -> {
                val corrected = root["corrected_category"]?.jsonPrimitive?.content.orEmpty()
                val confidence = root["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f
                val original = root["original_category"]?.jsonPrimitive?.content.orEmpty()
                if (corrected.isNotBlank() && original.isNotBlank()) {
                    ProviderMemoryStore.saveCategoryMapping(providerId, original, corrected, confidence)
                }
            }
            AnalysisMode.PAGINATION_ANALYSIS, AnalysisMode.RESULT_PARSING -> {
                val pagination = root["pagination"]?.jsonObject ?: root
                val type = pagination["type"]?.jsonPrimitive?.content
                    ?: pagination["pagination_type"]?.jsonPrimitive?.content
                val field = pagination["field"]?.jsonPrimitive?.content
                    ?: pagination["pagination_field"]?.jsonPrimitive?.content
                if (!type.isNullOrBlank() && !field.isNullOrBlank()) {
                    ProviderMemoryStore.savePaginationSchema(providerId, type, field)
                }
                ProviderMemoryStore.updateProviderSchema(providerId, output, root["confidence"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.82f)
            }
            AnalysisMode.URL_REPAIR -> {
                val repaired = root["repaired_result"]?.toString().orEmpty()
                if (repaired.isNotBlank()) ProviderMemoryStore.saveCorrection(providerId, "url_repair", "", repaired, 0.85f)
            }
            else -> Unit
        }
    }

    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) return trimmed
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        return if (start >= 0 && end > start) trimmed.substring(start, end + 1) else """{"valid":false,"reason":"model returned non-json"}"""
    }

    private fun schemaFor(mode: AnalysisMode): String = when (mode) {
        AnalysisMode.RESULT_PARSING -> """{"results":[{"title":"","category":"","thumbnail":"","description":"","confidence":0.0}],"issues":[],"pagination":{"type":"","field":"","nextValue":""}}"""
        AnalysisMode.CATEGORY_FIX -> """{"original_category":"","corrected_category":"","confidence":0.0,"reasoning":""}"""
        AnalysisMode.DATA_VALIDATION -> """{"valid":true,"problems":[],"suggested_fixes":[]}"""
        AnalysisMode.FRESHNESS_CHECK -> """{"is_fresh":true,"confidence":0.0,"diff_summary":""}"""
        AnalysisMode.URL_REPAIR -> """{"repaired_result":{},"flagged_links":[],"issues":[]}"""
        AnalysisMode.RESPONSE_QUALITY -> """{"is_valid":true,"quality_score":0.0,"reason":""}"""
        AnalysisMode.RESULT_DIFF -> """{"is_fresh":true,"changed_items":[],"unchanged_items":[],"freshness_score":0.0}"""
        AnalysisMode.PAGINATION_ANALYSIS -> """{"pagination_type":"","next_value":"","has_more":false,"confidence":0.0}"""
        AnalysisMode.GENERAL -> """{"findings":[],"confidence":0.0}"""
    }

    private fun deterministicFallback(input: String, mode: AnalysisMode, providerId: String): String = when (mode) {
        AnalysisMode.RESPONSE_QUALITY -> {
            val lower = input.lowercase()
            val invalid = listOf("not found", "404", "login", "sign in", "captcha", "just a moment", "enable javascript").any { lower.contains(it) }
            """{"is_valid":${!invalid},"quality_score":${if (invalid) 0.25 else 0.82},"reason":"deterministic on-device fallback while model is loading"}"""
        }
        AnalysisMode.FRESHNESS_CHECK, AnalysisMode.RESULT_DIFF ->
            """{"is_fresh":true,"confidence":0.81,"diff_summary":"live request bypassed cache; model fallback accepted"}"""
        AnalysisMode.PAGINATION_ANALYSIS ->
            """{"pagination_type":"page","next_value":"","has_more":true,"confidence":0.81}"""
        AnalysisMode.CATEGORY_FIX ->
            """{"original_category":"","corrected_category":"","confidence":0.0,"reasoning":"model not ready"}"""
        AnalysisMode.URL_REPAIR ->
            """{"repaired_result":$input,"flagged_links":[],"issues":[]}"""
        AnalysisMode.RESULT_PARSING ->
            """{"results":[],"issues":["model not ready"],"pagination":{"type":"page","field":"page","nextValue":""}}"""
        AnalysisMode.DATA_VALIDATION ->
            """{"valid":true,"problems":[],"suggested_fixes":[]}"""
        AnalysisMode.GENERAL ->
            """{"findings":["model not ready"],"confidence":0.0}"""
    }
}
