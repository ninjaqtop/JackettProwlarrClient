package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: AggregatorRepository
) : ViewModel() {
    fun updateDownloadDirectory(uri: String) {
        _uiState.update { it.copy(downloadSettings = it.downloadSettings.copy(downloadDirectory = uri)) }
        // TODO: Persist to repository or DataStore if needed
    }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()
    
    val providers: StateFlow<List<Provider>> = repository.getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    fun updateCustomUrl(url: String) {
        _uiState.update {
            it.copy(
                customUrl = url,
                lastAnalysis = if (url.isNotBlank()) null else it.lastAnalysis,
                lastProvider = if (url.isNotBlank()) null else it.lastProvider,
                showAnalysisDetails = if (url.isNotBlank()) false else it.showAnalysisDetails,
                error = null,
                message = null
            )
        }
    }
    
    fun analyzeCustomUrl() {
        val url = _uiState.value.customUrl.trim()
        if (url.isEmpty()) {
            _uiState.update { it.copy(error = "Please enter a URL") }
            return
        }
        
        viewModelScope.launch {
            _uiState.update { it.copy(isAnalyzing = true, error = null) }
            
            try {
                val (provider, analysis) = repository.analyzeNewUrl(url)
                _uiState.update { 
                    it.copy(
                        isAnalyzing = false,
                        lastAnalysis = analysis,
                        lastProvider = provider,
                        customUrl = "",
                        message = "Successfully analyzed and added: ${provider.name}"
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isAnalyzing = false,
                        error = e.message ?: "Analysis failed"
                    ) 
                }
            }
        }
    }
    
    fun refreshAllProviders() {
        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    isRefreshingAll = true, 
                    refreshProgress = 0f,
                    error = null
                ) 
            }
            
            try {
                val providerList = providers.value
                if (providerList.isEmpty()) {
                    _uiState.update { 
                        it.copy(
                            isRefreshingAll = false,
                            error = "No providers to refresh"
                        ) 
                    }
                    return@launch
                }
                
                val results = repository.refreshAllProviders()
                val successful = results.count { it.second.isSuccess }
                val failed = results.count { it.second.isFailure }
                
                _uiState.update { 
                    it.copy(
                        isRefreshingAll = false,
                        refreshProgress = 1f,
                        message = "Refreshed $successful providers${if (failed > 0) ", $failed failed" else ""}"
                    ) 
                }
            } catch (e: Exception) {
                _uiState.update { 
                    it.copy(
                        isRefreshingAll = false,
                        error = e.message ?: "Refresh failed"
                    ) 
                }
            }
        }
    }
    
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
    
    fun showAnalysisDetails(analysis: SiteAnalysis) {
        _uiState.update { it.copy(showAnalysisDetails = true, lastAnalysis = analysis) }
    }
    
    fun hideAnalysisDetails() {
        _uiState.update { it.copy(showAnalysisDetails = false) }
    }
}
