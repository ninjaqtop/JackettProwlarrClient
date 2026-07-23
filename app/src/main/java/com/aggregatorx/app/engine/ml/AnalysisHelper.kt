package com.aggregatorx.app.engine.ml

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

object AnalysisHelper {
    fun init() = Unit

    fun analyzeResults(rawData: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.RESULT_PARSING, rawData, providerId)
    fun fixCategory(resultItem: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.CATEGORY_FIX, resultItem, providerId)
    fun validateSavedData(data: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.DATA_VALIDATION, data, providerId)
    fun checkFreshness(newData: String, oldData: String): Flow<AnalysisResult> =
        run(AnalysisMode.FRESHNESS_CHECK, """{"new":$newData,"old":$oldData}""", "global")
    fun repairUrls(resultItem: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.URL_REPAIR, resultItem, providerId)
    fun checkResponseQuality(responseBody: String): Flow<AnalysisResult> =
        run(AnalysisMode.RESPONSE_QUALITY, responseBody, "global")
    fun analyzePagination(responseBody: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.PAGINATION_ANALYSIS, responseBody, providerId)

    private fun run(mode: AnalysisMode, input: String, providerId: String): Flow<AnalysisResult> = flow {
        val json = LlamaRuntime.analyze(input, mode, providerId)
        emit(AnalysisResult(mode, providerId, json, confidence = if (LlamaModelManager.isReady.value) 1f else 0f, usedModel = LlamaModelManager.isReady.value))
    }
}
