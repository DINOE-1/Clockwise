package com.dinoe.clockwise.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ai.AiApiException
import com.dinoe.clockwise.ai.AiConfig
import com.dinoe.clockwise.ai.AiErrorKind
import com.dinoe.clockwise.ai.AiProviderId
import com.dinoe.clockwise.ai.AiReasoningEffort
import com.dinoe.clockwise.ai.AiTestResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AiTestState {
    data object Idle : AiTestState
    data class Testing(val providerId: AiProviderId) : AiTestState
    data class Success(val providerId: AiProviderId, val latencyMillis: Long) : AiTestState
    data class Failure(val providerId: AiProviderId, val message: String) : AiTestState
}

class AiSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClockwiseApp
    private val repository = app.aiRepository

    val config: StateFlow<AiConfig?> = repository.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var message by mutableStateOf<String?>(null)
        private set
    var testState by mutableStateOf<AiTestState>(AiTestState.Idle)
        private set
    var refreshingProvider by mutableStateOf<AiProviderId?>(null)
        private set

    fun clearMessage() {
        message = null
    }

    fun resetTestState() {
        testState = AiTestState.Idle
    }

    fun saveKey(providerId: AiProviderId, rawKey: String) {
        val key = rawKey.trim()
        if (key.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.saveApiKey(providerId, key)
                message = app.getString(R.string.ai_settings_api_key_saved)
            } catch (e: Exception) {
                message = app.getString(R.string.ai_settings_error, e.message.orEmpty())
            }
        }
    }

    fun clearKey(providerId: AiProviderId) {
        viewModelScope.launch {
            try {
                repository.clearApiKey(providerId)
                message = app.getString(R.string.ai_settings_api_key_cleared)
                resetTestState()
            } catch (e: Exception) {
                message = app.getString(R.string.ai_settings_error, e.message.orEmpty())
            }
        }
    }

    fun setActive(providerId: AiProviderId) {
        viewModelScope.launch {
            try {
                repository.setActiveProvider(providerId)
                message = app.getString(R.string.ai_settings_active_done)
            } catch (e: Exception) {
                message = app.getString(R.string.ai_settings_error, e.message.orEmpty())
            }
        }
    }

    fun setModel(providerId: AiProviderId, modelId: String) {
        viewModelScope.launch {
            try {
                repository.setModel(providerId, modelId)
                message = app.getString(R.string.ai_settings_saved)
            } catch (e: Exception) {
                message = app.getString(R.string.ai_settings_error, e.message.orEmpty())
            }
        }
    }

    fun setReasoningEffort(providerId: AiProviderId, effort: AiReasoningEffort) {
        viewModelScope.launch {
            try {
                repository.setReasoningEffort(providerId, effort)
                message = app.getString(R.string.ai_settings_saved)
            } catch (e: Exception) {
                message = app.getString(R.string.ai_settings_error, e.message.orEmpty())
            }
        }
    }

    fun refreshModels(providerId: AiProviderId, keyOverride: String? = null) {
        if (refreshingProvider != null) return
        refreshingProvider = providerId
        viewModelScope.launch {
            try {
                val models = repository.fetchModels(providerId, keyOverride)
                message = app.getString(R.string.ai_settings_model_updated, models.size)
            } catch (e: AiApiException) {
                val text = if (e.kind == AiErrorKind.MISSING_KEY) {
                    app.getString(R.string.ai_settings_key_required_models)
                } else {
                    errorText(e.kind, e.detail)
                }
                message = app.getString(R.string.ai_settings_model_update_failed, text)
            } catch (e: Exception) {
                message = app.getString(
                    R.string.ai_settings_model_update_failed,
                    e.message.orEmpty(),
                )
            } finally {
                refreshingProvider = null
            }
        }
    }

    fun testConnection(
        providerId: AiProviderId,
        modelId: String,
        keyOverride: String? = null,
        reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW,
    ) {
        if (testState is AiTestState.Testing) return
        testState = AiTestState.Testing(providerId)
        viewModelScope.launch {
            try {
                when (
                    val result = repository.testConnection(
                        providerId = providerId,
                        modelId = modelId,
                        apiKeyOverride = keyOverride,
                        reasoningEffort = reasoningEffort,
                    )
                ) {
                    is AiTestResult.Success -> {
                        testState = AiTestState.Success(providerId, result.latencyMillis)
                    }

                    is AiTestResult.Failure -> {
                        val text = when (result.kind) {
                            AiErrorKind.MISSING_KEY -> app.getString(
                                R.string.ai_settings_test_need_key,
                            )

                            else -> errorText(result.kind, result.detail)
                        }
                        testState = AiTestState.Failure(providerId, text)
                    }
                }
            } catch (e: Exception) {
                testState = AiTestState.Failure(providerId, e.message.orEmpty())
            }
        }
    }

    private fun errorText(kind: AiErrorKind, detail: String?): String {
        val base = when (kind) {
            AiErrorKind.MISSING_KEY -> app.getString(R.string.ai_error_missing_key)
            AiErrorKind.UNAUTHORIZED -> app.getString(R.string.ai_error_unauthorized)
            AiErrorKind.INSUFFICIENT_CREDIT -> app.getString(R.string.ai_error_credit)
            AiErrorKind.MODEL_NOT_FOUND -> app.getString(R.string.ai_error_model_not_found)
            AiErrorKind.RATE_LIMITED -> app.getString(R.string.ai_error_rate_limited)
            AiErrorKind.SERVER_ERROR -> app.getString(R.string.ai_error_server)
            AiErrorKind.NETWORK -> app.getString(R.string.ai_error_network)
            AiErrorKind.INVALID_RESPONSE -> app.getString(R.string.ai_error_invalid_response)
            AiErrorKind.TRUNCATED -> app.getString(R.string.ai_error_truncated)
            AiErrorKind.UNKNOWN -> app.getString(R.string.ai_error_unknown)
        }
        val cleaned = detail?.trim()?.take(160)
        return if (cleaned.isNullOrEmpty()) base else "$base（$cleaned）"
    }
}
