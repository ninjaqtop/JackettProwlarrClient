package com.aggregatorx.app.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.aggregatorx.app.data.model.LearnedUserProfile
import com.aggregatorx.app.data.model.LikedResult
import com.aggregatorx.app.data.model.Provider
import com.aggregatorx.app.data.model.ScrapingConfig
import com.aggregatorx.app.data.model.SearchHistoryEntry
import com.aggregatorx.app.data.model.SiteAnalysis
import com.aggregatorx.app.data.model.UserPreferences

@Database(
    entities = [
        Provider::class,
        SiteAnalysis::class,
        ScrapingConfig::class,
        SearchHistoryEntry::class,
        UserPreferences::class,
        LikedResult::class,
        LearnedUserProfile::class
    ],
    version = 5,
    exportSchema = true
)
abstract class AggregatorDatabase : RoomDatabase() {
    abstract fun providerDao(): ProviderDao
    abstract fun siteAnalysisDao(): SiteAnalysisDao
    abstract fun scrapingConfigDao(): ScrapingConfigDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun userPreferencesDao(): UserPreferencesDao
    abstract fun likedResultDao(): LikedResultDao
    abstract fun learnedProfileDao(): LearnedProfileDao
}
