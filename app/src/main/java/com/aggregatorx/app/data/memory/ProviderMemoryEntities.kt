package com.aggregatorx.app.data.memory

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "provider_schema")
@Serializable
data class ProviderSchema(
    @PrimaryKey val providerId: String,
    val schemaJson: String = "{}",
    val categoryMappings: String = "{}",
    val thumbnailField: String = "",
    val titleField: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
    val confidenceScore: Float = 0f,
    val resultsPerPage: Int = 50,
    val paginationField: String = "",
    val paginationType: String = ""
)

@Entity(tableName = "correction_log")
@Serializable
data class CorrectionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: String,
    val fieldCorrected: String,
    val originalValue: String,
    val correctedValue: String,
    val confidence: Float,
    val timestamp: Long = System.currentTimeMillis()
)
