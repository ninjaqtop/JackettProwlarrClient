package com.aggregatorx.app.di

import android.content.Context
import com.aggregatorx.app.engine.UnifiedContentEngine
import com.aggregatorx.app.engine.ai.AICodeInjectionEngine
import com.aggregatorx.app.engine.ai.AIDecisionEngine
import com.aggregatorx.app.engine.analyzer.SmartContentClassifier
import com.aggregatorx.app.engine.analyzer.SiteAnalyzerEngine
import com.aggregatorx.app.engine.analyzer.UniversalFormatParser
import com.aggregatorx.app.engine.analyzer.EndpointDiscoveryEngine
import com.aggregatorx.app.engine.media.AdvancedVideoExtractorEngine
import com.aggregatorx.app.engine.media.VideoExtractorEngine
import com.aggregatorx.app.engine.media.VideoStreamResolver
import com.aggregatorx.app.engine.nlp.NaturalLanguageQueryProcessor
import com.aggregatorx.app.engine.network.ProxyVPNEngine
import com.aggregatorx.app.engine.network.CloudflareBypassEngine
import com.aggregatorx.app.engine.scraper.SmartNavigationEngine
import com.aggregatorx.app.engine.token.TokenManager
import com.aggregatorx.app.engine.vision.VisionEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

/**
 * Dependency Injection Module for AggregatorX Engine Components
 * Provides all engine-related dependencies with proper scoping
 */
@Module
@InstallIn(SingletonComponent::class)
object EngineModule {
    
    @Provides
    @Singleton
    fun provideProxyVPNEngine(): ProxyVPNEngine {
        return ProxyVPNEngine()
    }
    
    @Provides
    @Singleton
    fun provideCloudflareBypassEngine(): CloudflareBypassEngine {
        return CloudflareBypassEngine()
    }

    @Provides
    @Singleton
    fun provideEndpointDiscoveryEngine(
        cloudflareBypassEngine: CloudflareBypassEngine
    ): EndpointDiscoveryEngine {
        return EndpointDiscoveryEngine(cloudflareBypassEngine)
    }
    
    @Provides
    @Singleton
    fun provideAIDecisionEngine(): AIDecisionEngine {
        return AIDecisionEngine()
    }
    
    @Provides
    @Singleton
    fun provideSmartNavigationEngine(): SmartNavigationEngine {
        return SmartNavigationEngine()
    }
    
    @Provides
    @Singleton
    fun provideVideoExtractorEngine(): VideoExtractorEngine {
        return VideoExtractorEngine()
    }

    @Provides
    @Singleton
    fun provideAdvancedVideoExtractorEngine(): AdvancedVideoExtractorEngine {
        return AdvancedVideoExtractorEngine()
    }

    @Provides
    @Singleton
    fun provideVideoStreamResolver(
        proxyVPNEngine: ProxyVPNEngine,
        videoExtractorEngine: VideoExtractorEngine,
        advancedVideoExtractorEngine: AdvancedVideoExtractorEngine
    ): VideoStreamResolver {
        return VideoStreamResolver(proxyVPNEngine, videoExtractorEngine, advancedVideoExtractorEngine)
    }
    
    @Provides
    @Singleton
    fun provideSiteAnalyzerEngine(): SiteAnalyzerEngine {
        return SiteAnalyzerEngine()
    }
    
    @Provides
    @Singleton
    fun provideSmartContentClassifier(): SmartContentClassifier {
        return SmartContentClassifier()
    }
    
    @Provides
    @Singleton
    fun provideUniversalFormatParser(): UniversalFormatParser {
        return UniversalFormatParser()
    }
    
    @Provides
    @Singleton
    fun provideNaturalLanguageQueryProcessor(): NaturalLanguageQueryProcessor {
        return NaturalLanguageQueryProcessor()
    }
    
    @Provides
    @Singleton
    fun provideAICodeInjectionEngine(): AICodeInjectionEngine {
        return AICodeInjectionEngine()
    }

    @Provides
    @Singleton
    fun provideTokenManager(httpClient: OkHttpClient): TokenManager = TokenManager(httpClient)

    @Provides
    @Singleton
    fun provideVisionEngine(httpClient: OkHttpClient): VisionEngine = VisionEngine(httpClient)

    @Provides
    @Singleton
    fun provideUnifiedContentEngine(
        proxyVPNEngine: ProxyVPNEngine,
        videoStreamResolver: VideoStreamResolver,
        smartContentClassifier: SmartContentClassifier,
        universalFormatParser: UniversalFormatParser,
        siteAnalyzerEngine: SiteAnalyzerEngine
    ): UnifiedContentEngine {
        return UnifiedContentEngine(
            proxyVPNEngine,
            videoStreamResolver,
            smartContentClassifier,
            universalFormatParser,
            siteAnalyzerEngine
        )
    }
}
