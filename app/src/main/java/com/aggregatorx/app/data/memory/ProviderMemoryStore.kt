package com.aggregatorx.app.data.memory

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

object ProviderMemoryStore {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private lateinit var db: ProviderMemoryDatabase
    private lateinit var filesDir: File

    fun init(context: Context) {
        db = ProviderMemoryDatabase.get(context)
        filesDir = File(context.filesDir, "providers").apply { mkdirs() }
    }

    suspend fun getProviderContext(providerId: String): String = withContext(Dispatchers.IO) {
        ensureInit()
        val schema = db.providerSchemaDao().get(providerId)
        val corrections = getTopCorrections(providerId, 8)
        buildString {
            appendLine("Provider memory for $providerId:")
            appendLine("Schema: ${schema?.schemaJson ?: "{}"}")
            appendLine("Category mappings: ${schema?.categoryMappings ?: "{}"}")
            appendLine("Thumbnail field: ${schema?.thumbnailField.orEmpty()}")
            appendLine("Title field: ${schema?.titleField.orEmpty()}")
            appendLine("Pagination: ${schema?.paginationType.orEmpty()} via ${schema?.paginationField.orEmpty()}")
            appendLine("Corrections:")
            corrections.forEach { appendLine("- ${it.fieldCorrected}: ${it.originalValue} -> ${it.correctedValue} (${it.confidence})") }
        }
    }

    suspend fun saveCorrection(providerId: String, field: String, original: String, corrected: String, confidence: Float) = withContext(Dispatchers.IO) {
        ensureInit()
        db.correctionLogDao().insert(CorrectionLog(providerId = providerId, fieldCorrected = field, originalValue = original, correctedValue = corrected, confidence = confidence))
        mirror(providerId)
    }

    suspend fun updateProviderSchema(providerId: String, schemaJson: String, confidence: Float) = withContext(Dispatchers.IO) {
        ensureInit()
        val existing = db.providerSchemaDao().get(providerId)
        db.providerSchemaDao().upsert((existing ?: ProviderSchema(providerId = providerId)).copy(
            schemaJson = schemaJson,
            confidenceScore = confidence,
            lastUpdated = System.currentTimeMillis()
        ))
        mirror(providerId)
    }

    suspend fun getTopCorrections(providerId: String, limit: Int): List<CorrectionLog> = withContext(Dispatchers.IO) {
        ensureInit()
        db.correctionLogDao().top(providerId, limit)
    }

    suspend fun savePaginationSchema(providerId: String, paginationType: String, paginationField: String) = withContext(Dispatchers.IO) {
        ensureInit()
        val existing = db.providerSchemaDao().get(providerId)
        db.providerSchemaDao().upsert((existing ?: ProviderSchema(providerId = providerId)).copy(
            paginationType = paginationType,
            paginationField = paginationField,
            lastUpdated = System.currentTimeMillis()
        ))
        mirror(providerId)
    }

    suspend fun saveCategoryMapping(providerId: String, raw: String, corrected: String, confidence: Float) = withContext(Dispatchers.IO) {
        ensureInit()
        val existing = db.providerSchemaDao().get(providerId) ?: ProviderSchema(providerId = providerId)
        val current = runCatching {
            json.parseToJsonElement(existing.categoryMappings).jsonObject
        }.getOrDefault(JsonObject(emptyMap()))
        val merged = buildJsonObject {
            current.forEach { (key, value) -> put(key, value.jsonPrimitive.content) }
            put(raw, corrected)
        }
        db.providerSchemaDao().upsert(existing.copy(
            categoryMappings = merged.toString(),
            confidenceScore = maxOf(existing.confidenceScore, confidence),
            lastUpdated = System.currentTimeMillis()
        ))
        db.correctionLogDao().insert(CorrectionLog(providerId = providerId, fieldCorrected = "category", originalValue = raw, correctedValue = corrected, confidence = confidence))
        mirror(providerId)
    }

    suspend fun getSchema(providerId: String): ProviderSchema? = withContext(Dispatchers.IO) {
        ensureInit()
        db.providerSchemaDao().get(providerId)
    }

    private suspend fun mirror(providerId: String) {
        val schema = db.providerSchemaDao().get(providerId) ?: return
        File(filesDir, "${providerId}_schema.json").writeText(json.encodeToString(schema))
    }

    private fun ensureInit() {
        check(::db.isInitialized) { "ProviderMemoryStore.init(context) was not called" }
    }
}
