package com.aggregatorx.app.di

import android.content.Context
import androidx.room.Room
import com.aggregatorx.app.data.database.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AggregatorDatabase {
        return Room.databaseBuilder(
            context,
            AggregatorDatabase::class.java,
            "aggregator_database"
        )
        // v3 -> v4 is a real migration (providers, analyses and configs survive:
        // spec §3 round-trip rule). Only pre-v3 schemas, which never had a
        // migration path, and downgrades are rebuilt destructively.
        .addMigrations(DatabaseMigrations.MIGRATION_3_4)
        .fallbackToDestructiveMigrationFrom(1, 2)
        .fallbackToDestructiveMigrationOnDowngrade()
        .build()
    }
    
    @Provides
    fun provideProviderDao(database: AggregatorDatabase): ProviderDao {
        return database.providerDao()
    }
    
    @Provides
    fun provideSiteAnalysisDao(database: AggregatorDatabase): SiteAnalysisDao {
        return database.siteAnalysisDao()
    }
    
    @Provides
    fun provideScrapingConfigDao(database: AggregatorDatabase): ScrapingConfigDao {
        return database.scrapingConfigDao()
    }
    
    @Provides
    fun provideSearchHistoryDao(database: AggregatorDatabase): SearchHistoryDao {
        return database.searchHistoryDao()
    }
    
    @Provides
    fun provideUserPreferencesDao(database: AggregatorDatabase): UserPreferencesDao {
        return database.userPreferencesDao()
    }
    
    @Provides
    fun provideLikedResultDao(database: AggregatorDatabase): LikedResultDao {
        return database.likedResultDao()
    }
    
    @Provides
    fun provideLearnedProfileDao(database: AggregatorDatabase): LearnedProfileDao {
        return database.learnedProfileDao()
    }
}
