package com.dinoe.clockwise.ai

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonArray

sealed interface AiTestResult {
    data class Success(val latencyMillis: Long, val modelId: String) : AiTestResult
    data class Failure(val kind: AiErrorKind, val detail: String?) : AiTestResult
}

class AiRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AiPreferences(appContext)
    private val client = OpenAiChatClient()

    val config: Flow<AiConfig> = prefs.config

    suspend fun apiKey(providerId: AiProviderId): String? = prefs.apiKey(providerId)

    suspend fun setActiveProvider(providerId: AiProviderId) = prefs.setActiveProvider(providerId)

    suspend fun saveApiKey(providerId: AiProviderId, rawKey: String) =
        prefs.saveApiKey(providerId, rawKey)

    suspend fun clearApiKey(providerId: AiProviderId) = prefs.clearApiKey(providerId)

    suspend fun setModel(providerId: AiProviderId, modelId: String) =
        prefs.setModel(providerId, modelId)

    suspend fun setReasoningEffort(providerId: AiProviderId, effort: AiReasoningEffort) =
        prefs.setReasoningEffort(providerId, effort)

    suspend fun complete(
        provider: AiProviderConfig,
        messages: List<ChatMessage>,
        tools: JsonArray? = AiTools.definitions,
        apiKeyOverride: String? = null,
        maxTokens: Int = 4096,
    ): ChatCompletion {
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.apiKey(provider.preset.id)
            ?: throw AiApiException(AiErrorKind.MISSING_KEY)
        return client.complete(
            preset = provider.preset,
            apiKey = key,
            model = provider.modelId,
            messages = messages,
            tools = tools,
            maxTokens = maxTokens,
            reasoningEffort = provider.reasoningEffort,
        )
    }

    suspend fun fetchModels(
        providerId: AiProviderId,
        apiKeyOverride: String? = null,
    ): List<AiModelInfo> {
        val preset = AiProviders.preset(providerId)
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.apiKey(providerId)
        if (preset.requiresKeyForModels && key.isNullOrBlank()) {
            throw AiApiException(AiErrorKind.MISSING_KEY)
        }
        val models = AiModelCatalog.chatCompletions(client.listModels(preset, key))
        if (models.isNotEmpty()) {
            prefs.saveModels(providerId, models, System.currentTimeMillis())
        }
        return models
    }

    suspend fun testConnection(
        providerId: AiProviderId,
        modelId: String,
        apiKeyOverride: String? = null,
        reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW,
    ): AiTestResult {
        val preset = AiProviders.preset(providerId)
        val key = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
            ?: prefs.apiKey(providerId)
            ?: return AiTestResult.Failure(AiErrorKind.MISSING_KEY, null)
        val started = System.currentTimeMillis()
        return try {
            client.complete(
                preset = preset,
                apiKey = key,
                model = modelId,
                messages = listOf(
                    ChatMessage(role = ChatRole.SYSTEM, content = "Reply with OK."),
                    ChatMessage(role = ChatRole.USER, content = "ping"),
                ),
                tools = null,
                maxTokens = 32,
                reasoningEffort = reasoningEffort,
            )
            AiTestResult.Success(
                latencyMillis = System.currentTimeMillis() - started,
                modelId = modelId,
            )
        } catch (e: AiApiException) {
            AiTestResult.Failure(e.kind, e.detail)
        }
    }
}
