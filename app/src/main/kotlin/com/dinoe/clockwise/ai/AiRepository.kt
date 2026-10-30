package com.dinoe.clockwise.ai

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonArray
import java.util.concurrent.atomic.AtomicReference

sealed interface AiTestResult {
    data class Success(val latencyMillis: Long, val modelId: String) : AiTestResult
    data class Failure(val kind: AiErrorKind, val detail: String?) : AiTestResult
}

/** Provider configuration plus the decrypted credential resolved once for a whole turn. */
data class AiTurnCredentials(
    val provider: AiProviderConfig,
    val apiKey: String?,
)

class AiRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AiPreferences(appContext)
    private val client = OpenAiChatClient()
    private val credentialCache = AtomicReference<CachedCredential?>(null)

    val config: Flow<AiConfig> = prefs.config.onEach { invalidateCredential() }

    suspend fun apiKey(providerId: AiProviderId): String? = prefs.apiKey(providerId)

    /**
     * Resolves the decrypted key once per provider configuration. Callers that run several
     * requests for one turn (tool rounds) hold the returned value instead of re-reading and
     * re-decrypting the key per request.
     */
    suspend fun resolveCredentials(provider: AiProviderConfig): AiTurnCredentials {
        val providerId = provider.preset.id
        credentialCache.get()?.takeIf { it.providerId == providerId }?.let { cached ->
            return AiTurnCredentials(provider = provider, apiKey = cached.apiKey)
        }
        val key = prefs.apiKey(providerId)
        credentialCache.set(CachedCredential(providerId, key))
        return AiTurnCredentials(provider = provider, apiKey = key)
    }

    /** Drops the cached credential after a configuration change. */
    fun invalidateCredential() {
        credentialCache.set(null)
    }

    suspend fun setActiveProvider(providerId: AiProviderId) = prefs.setActiveProvider(providerId)

    suspend fun saveApiKey(providerId: AiProviderId, rawKey: String) {
        prefs.saveApiKey(providerId, rawKey)
        invalidateCredential()
    }

    suspend fun clearApiKey(providerId: AiProviderId) {
        prefs.clearApiKey(providerId)
        invalidateCredential()
    }

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
        val override = apiKeyOverride?.trim()?.takeIf { it.isNotEmpty() }
        val credentials = if (override != null) {
            AiTurnCredentials(provider = provider, apiKey = override)
        } else {
            resolveCredentials(provider)
        }
        return complete(credentials, messages, tools, maxTokens)
    }

    suspend fun complete(
        credentials: AiTurnCredentials,
        messages: List<ChatMessage>,
        tools: JsonArray? = AiTools.definitions,
        maxTokens: Int = 4096,
    ): ChatCompletion {
        val provider = credentials.provider
        val key = credentials.apiKey?.trim()?.takeIf { it.isNotEmpty() }
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

    private data class CachedCredential(
        val providerId: AiProviderId,
        val apiKey: String?,
    )
}
