package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import com.aggregatorx.app.data.repository.ConfigVerificationException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
                // No UI-layer timeout wrapper here. The repository
                // pipeline bounds every stage itself (site analysis,
                // recon, live verification — each with its own timeout
                // and budget), and a hard 75 s race previously failed
                // slow or JS-heavy sites while their verification was
                // still progressing normally. The add is observed to
                // completion instead: it always ends in success or one
                // of the specific reasons below, never a bare timeout.
                val (provider, _) = repository.analyzeNewUrl(url)
                // Re-read the stored record: it now carries the verification
                // evidence and the saved config the report screen shows.
                val stored = repository.getLatestAnalysis(provider.id)
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        lastAnalysis = stored,
                        lastProvider = provider,
                        customUrl = "",
                        message = "Verified live and saved: ${provider.name}"
                    )
                }
            } catch (e: ConfigVerificationException) {
                // The provider WAS saved, enabled, and stays visible —
                // only its search config was withheld because no route
                // could be proven live. The exception's message carries
                // the specific per-route reasons; show it verbatim
                // instead of replacing it with a generic failure, and
                // keep the stored record so the report shows the evidence.
                val savedProvider = e.providerId?.let { id -> repository.getProviderById(id) }
                val stored = e.providerId?.let { id -> repository.getLatestAnalysis(id) }
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        lastAnalysis = stored ?: it.lastAnalysis,
                        lastProvider = savedProvider ?: it.lastProvider,
                        error = e.message
                            ?: "The provider was saved, but its search configuration could not be verified."
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        error = "Analysis stopped before it could finish. The provider was saved; run Analyze again from the Providers screen to complete its configuration."
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isAnalyzing = false,
                        error = "Analysis could not finish for this address (${e.javaClass.simpleName}: " +
                            "${e.message ?: "no detail"}). If the provider appears in the Providers list it was saved; " +
                            "re-run Analyze from there to retry its configuration."
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
