package com.aggregatorx.app.engine.ml

import kotlinx.coroutines.flow.Flow

object AnalysisHelper {
    fun init() = DeterministicAnalysisHelper.init()

    fun analyzeResults(rawData: String, providerId: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.analyzeResults(rawData, providerId)

    fun fixCategory(resultItem: String, providerId: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.fixCategory(resultItem, providerId)

    fun validateSavedData(data: String, providerId: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.validateSavedData(data, providerId)

    fun checkFreshness(newData: String, oldData: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.checkFreshness(newData, oldData)

    fun diffResults(newData: String, oldData: String, providerId: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.diffResults(newData, oldData, providerId)

    fun repairUrls(resultItem: String, providerId: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.repairUrls(resultItem, providerId)

    fun checkResponseQuality(responseBody: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.checkResponseQuality(responseBody)

    fun analyzePagination(responseBody: String, providerId: String): Flow<AnalysisResult> =
        DeterministicAnalysisHelper.analyzePagination(responseBody, providerId)
}
