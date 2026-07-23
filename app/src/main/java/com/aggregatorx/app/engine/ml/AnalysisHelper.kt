package com.aggregatorx.app.engine.ml

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull

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
    fun diffResults(newData: String, oldData: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.RESULT_DIFF, """{"new":$newData,"old":$oldData}""", providerId)
    fun repairUrls(resultItem: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.URL_REPAIR, resultItem, providerId)
    fun checkResponseQuality(responseBody: String): Flow<AnalysisResult> =
        run(AnalysisMode.RESPONSE_QUALITY, responseBody, "global")
    fun analyzePagination(responseBody: String, providerId: String): Flow<AnalysisResult> =
        run(AnalysisMode.PAGINATION_ANALYSIS, responseBody, providerId)

    private fun run(mode: AnalysisMode, input: String, providerId: String): Flow<AnalysisResult> = flow {
        val wasReady = LlamaModelManager.isReady.value
        val json = withTimeoutOrNull(if (wasReady) 60_000L else 2_000L) {
            LlamaService.analyze(input, mode, providerId)
        } ?: LlamaRuntime.analyze(input, mode, providerId)
        emit(AnalysisResult(mode, providerId, json, confidence = if (wasReady) 1f else 0.81f, usedModel = wasReady))
    }
}
