package com.dinoe.clockwise.ai

enum class AiProviderId(val prefKey: String) {
    DEEPSEEK("deepseek"),
    COMMANDCODE("commandcode"),
    ;

    companion object {
        fun fromPrefKey(value: String?): AiProviderId =
            entries.firstOrNull { it.prefKey == value } ?: DEEPSEEK
    }
}

/**
 * Thinking effort sent to the provider. DeepSeek uses thinking.enabled/disabled plus
 * reasoning_effort low/high/max; OpenAI-compatible gateways use reasoning_effort low/medium/high.
 */
enum class AiReasoningEffort(val prefKey: String) {
    OFF("off"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        fun fromPrefKey(value: String?): AiReasoningEffort =
            entries.firstOrNull { it.prefKey == value } ?: LOW
    }

    fun deepSeekWireValue(): String? = when (this) {
        OFF -> null
        LOW -> "low"
        MEDIUM -> "high"
        HIGH -> "max"
    }

    fun openAiWireValue(): String? = when (this) {
        OFF -> null
        LOW -> "low"
        MEDIUM -> "medium"
        HIGH -> "high"
    }
}

data class AiUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val cachedTokens: Long = 0,
    val reasoningTokens: Long = 0,
    // False when the provider did not report cache usage at all; show "—" instead of 0%.
    val cacheReported: Boolean = false,
) {
    val totalTokens: Long get() = promptTokens + completionTokens
    val cacheHitRate: Double?
        get() = if (cacheReported && promptTokens > 0) {
            (cachedTokens.toDouble() / promptTokens).coerceIn(0.0, 1.0)
        } else {
            null
        }

    operator fun plus(other: AiUsage): AiUsage = AiUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        reasoningTokens = reasoningTokens + other.reasoningTokens,
        cacheReported = cacheReported || other.cacheReported,
    )
}

data class AiModelInfo(
    val id: String,
    val name: String = id,
    val contextLength: Long? = null,
    // Empty when the provider does not advertise endpoints (DeepSeek) or the field is absent.
    val supportedEndpoints: List<String> = emptyList(),
    // Null when the model catalog does not advertise input modalities.
    val supportsImages: Boolean? = null,
)

data class AiProviderPreset(
    val id: AiProviderId,
    val displayName: String,
    val baseUrl: String,
    val defaultModel: String,
    val keyHint: String,
    val fallbackModels: List<AiModelInfo>,
    val requiresKeyForModels: Boolean,
    val chatPath: String = "/chat/completions",
    val modelsPath: String = "/models",
    val temperature: Double? = null,
) {
    fun chatUrl(): String = baseUrl.trimEnd('/') + chatPath

    fun modelsUrl(): String = baseUrl.trimEnd('/') + modelsPath
}

data class AiProviderConfig(
    val preset: AiProviderPreset,
    val hasKey: Boolean = false,
    val keyLast4: String? = null,
    val keyError: Boolean = false,
    val modelId: String = preset.defaultModel,
    val models: List<AiModelInfo> = preset.fallbackModels,
    val modelsFetchedAt: Long = 0L,
    val reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW,
) {
    /** False only when the catalog explicitly says the selected model takes no image input. */
    val modelSupportsImages: Boolean?
        get() = models.firstOrNull { it.id == modelId }?.supportsImages
}

data class AiConfig(
    val activeProviderId: AiProviderId,
    val providers: Map<AiProviderId, AiProviderConfig>,
) {
    fun active(): AiProviderConfig =
        providers[activeProviderId] ?: AiProviders.require(activeProviderId)

    fun provider(id: AiProviderId): AiProviderConfig =
        providers[id] ?: AiProviders.require(id)
}

object AiProviders {
    val deepSeek = AiProviderPreset(
        id = AiProviderId.DEEPSEEK,
        displayName = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        defaultModel = "deepseek-flash",
        keyHint = "sk-...",
        fallbackModels = listOf(
            AiModelInfo("deepseek-flash", "DeepSeek Flash"),
            AiModelInfo("deepseek-v4-pro", "DeepSeek V4 Pro"),
        ),
        requiresKeyForModels = true,
        temperature = 0.3,
    )

    val commandCode = AiProviderPreset(
        id = AiProviderId.COMMANDCODE,
        displayName = "Command Code (GOAT)",
        baseUrl = "https://api.commandcode.ai/provider/v1",
        defaultModel = "deepseek/deepseek-v4.1-flash",
        keyHint = "user_...",
        fallbackModels = listOf(
            AiModelInfo("deepseek/deepseek-v4.1-flash", "DeepSeek V4.1 Flash"),
            AiModelInfo("deepseek/deepseek-v4-pro", "DeepSeek V4 Pro"),
            AiModelInfo("xai/grok-4.6", "Grok 4.6"),
            AiModelInfo("moonshotai/Kimi-K3", "Kimi K3"),
            AiModelInfo("z-ai/glm-5.3-flash", "GLM-5.3 Flash"),
            AiModelInfo("Qwen/Qwen3.8-Flash", "Qwen 3.8 Flash"),
        ),
        requiresKeyForModels = false,
    )

    val all: List<AiProviderPreset> = listOf(deepSeek, commandCode)

    fun require(id: AiProviderId): AiProviderConfig {
        val preset = all.first { it.id == id }
        return AiProviderConfig(preset = preset)
    }

    fun preset(id: AiProviderId): AiProviderPreset = all.first { it.id == id }
}

/**
 * Mirrors the routing rule used by the Command Code pi provider: a model is only useful to this
 * app when it can answer on the OpenAI Chat Completions wire. Models that advertise endpoints but
 * not /chat/completions (for example Claude on /messages) are hidden.
 */
object AiModelCatalog {
    fun isChatCompletionsModel(model: AiModelInfo): Boolean {
        if (model.supportedEndpoints.isNotEmpty()) {
            return "/chat/completions" in model.supportedEndpoints
        }
        return !model.id.startsWith("claude-")
    }

    fun chatCompletions(models: List<AiModelInfo>): List<AiModelInfo> =
        models
            .filter(::isChatCompletionsModel)
            .distinctBy { it.id }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
}
