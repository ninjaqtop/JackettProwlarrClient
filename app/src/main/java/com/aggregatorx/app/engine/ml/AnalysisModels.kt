package com.aggregatorx.app.engine.ml

enum class AnalysisMode {
    RESULT_PARSING,
    CATEGORY_FIX,
    DATA_VALIDATION,
    FRESHNESS_CHECK,
    URL_REPAIR,
    RESPONSE_QUALITY,
    RESULT_DIFF,
    PAGINATION_ANALYSIS,
    GENERAL
}

data class AnalysisResult(
    val mode: AnalysisMode,
    val providerId: String,
    val json: String,
    val confidence: Float = 0f,
    val usedModel: Boolean = false
)
