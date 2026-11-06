package com.aggregatorx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aggregatorx.app.data.model.*
import com.aggregatorx.app.data.repository.AggregatorRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProvidersViewModel @Inject constructor(
    private val repository: AggregatorRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow(ProvidersUiState())
    val uiState: StateFlow<ProvidersUiState> = _uiState.asStateFlow()
    
    val providers: StateFlow<List<Provider>> = repository.getAllProviders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    
    private val _analyzingProviders = MutableStateFlow<Set<String>>(emptySet())
    val analyzingProviders: StateFlow<Set<String>> = _analyzingProviders.asStateFlow()
    
    fun toggleProvider(providerId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.setProviderEnabled(providerId, enabled)
        }
    }
    
    fun analyzeProvider(providerId: String) {
        viewModelScope.launch {
            _analyzingProviders.update { it + providerId }
            
            try {
                val analysis = repository.analyzeProvider(providerId)
                _uiState.update {
                    it.copy(
                        lastAnalysis = analysis,
                        message = "Analysis completed successfully"
                    )
                }
            } catch (e: CancellationException) {
                // Cancellation is not an analysis outcome — never turn
                // it into an "Analysis failed" error state.
                throw e
            } catch (e: Exception) {
                _uiState.update {
                    // Verification rejections carry their specific
                    // reasons in the message and pass through verbatim;
                    // only a message-less failure falls back to a
                    // generic label, and then it names the exception
                    // type so the failure is diagnosable.
                    it.copy(error = e.message ?: "Analysis failed (${e.javaClass.simpleName})")
                }
            } finally {
                _analyzingProviders.update { it - providerId }
            }
        }
    }
    
    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            try {
                repository.deleteProvider(providerId)
                _uiState.update { it.copy(message = "Provider deleted") }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Delete failed") }
            }
        }
    }
    
    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }
    
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}

data class ProvidersUiState(
    val lastAnalysis: SiteAnalysis? = null,
    val message: String? = null,
    val error: String? = null
)
