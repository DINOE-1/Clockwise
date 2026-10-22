package com.dinoe.clockwise.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class ChatRole(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class ChatToolCall(
    val id: String,
    val name: String,
    val arguments: String,
)

data class ChatMessage(
    val role: ChatRole,
    val content: String? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val toolCallId: String? = null,
    // Reasoning returned by the provider. With tools, DeepSeek-style APIs require this to be
    // echoed back on every earlier assistant message or the next request fails with a 400.
    val reasoningContent: String? = null,
)

data class ChatCompletion(
    val content: String?,
    val toolCalls: List<ChatToolCall>,
    val finishReason: String?,
    val usage: AiUsage,
    val reasoningContent: String? = null,
)

enum class AiErrorKind {
    MISSING_KEY,
    UNAUTHORIZED,
    INSUFFICIENT_CREDIT,
    MODEL_NOT_FOUND,
    RATE_LIMITED,
    SERVER_ERROR,
    NETWORK,
    INVALID_RESPONSE,
    TRUNCATED,
    UNKNOWN,
}

class AiApiException(
    val kind: AiErrorKind,
    val status: Int? = null,
    val detail: String? = null,
) : Exception(detail ?: kind.name)

/**
 * Minimal OpenAI-compatible Chat Completions client. It deliberately uses the JSON tree API and
 * has no Android dependencies so it can be tested against MockWebServer on the JVM.
 */
class OpenAiChatClient(
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json,
) {
    suspend fun complete(
        preset: AiProviderPreset,
        apiKey: String?,
        model: String,
        messages: List<ChatMessage>,
        tools: JsonArray? = null,
        maxTokens: Int = 4096,
        reasoningEffort: AiReasoningEffort = AiReasoningEffort.LOW,
    ): ChatCompletion {
        if (apiKey.isNullOrBlank()) {
            throw AiApiException(AiErrorKind.MISSING_KEY)
        }
        val body = buildJsonObject {
            put("model", model)
            put("messages", buildMessages(messages, reasoningEffort != AiReasoningEffort.OFF))
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            put("stream", false)
            put("max_tokens", JsonPrimitive(maxTokens))
            if (preset.id == AiProviderId.DEEPSEEK) {
                if (reasoningEffort == AiReasoningEffort.OFF) {
                    putJsonObject("thinking") { put("type", "disabled") }
                } else {
                    putJsonObject("thinking") { put("type", "enabled") }
                    reasoningEffort.deepSeekWireValue()?.let { put("reasoning_effort", it) }
                }
            } else {
                reasoningEffort.openAiWireValue()?.let { put("reasoning_effort", it) }
            }
            preset.temperature?.let { put("temperature", JsonPrimitive(it)) }
        }
        val request = Request.Builder()
            .url(preset.chatUrl())
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = execute(request)
        if (response.code !in 200..299) throw errorFor(response.code, response.body)
        return parseCompletion(response.body)
    }

    suspend fun listModels(
        preset: AiProviderPreset,
        apiKey: String?,
    ): List<AiModelInfo> {
        if (preset.requiresKeyForModels && apiKey.isNullOrBlank()) {
            throw AiApiException(AiErrorKind.MISSING_KEY)
        }
        val builder = Request.Builder()
            .url(preset.modelsUrl())
            .header("Accept", "application/json")
            .get()
        if (!apiKey.isNullOrBlank()) {
            builder.header("Authorization", "Bearer ${apiKey.trim()}")
        }
        val response = execute(builder.build())
        if (response.code !in 200..299) throw errorFor(response.code, response.body)
        return parseModels(response.body)
    }

    private fun buildMessages(
        messages: List<ChatMessage>,
        includeReasoningFallback: Boolean,
    ): JsonArray = buildJsonArray {
        messages.forEach { message ->
            add(
                buildJsonObject {
                    put("role", message.role.wire)
                    if (message.role == ChatRole.TOOL) {
                        put("tool_call_id", message.toolCallId ?: "")
                        put("content", message.content ?: "")
                    } else {
                        put("content", message.content ?: "")
                        if (message.role == ChatRole.ASSISTANT) {
                            val reasoning = message.reasoningContent
                            when {
                                !reasoning.isNullOrEmpty() -> put("reasoning_content", reasoning)
                                // Mixed histories (thinking switched on mid-chat) would otherwise be
                                // rejected by reasoning models that require the field on every
                                // earlier assistant message.
                                includeReasoningFallback -> put("reasoning_content", "")
                            }
                        }
                        if (message.role == ChatRole.ASSISTANT && message.toolCalls.isNotEmpty()) {
                            putJsonArray("tool_calls") {
                                message.toolCalls.forEach { call ->
                                    add(
                                        buildJsonObject {
                                            put("id", call.id)
                                            put("type", "function")
                                            putJsonObject("function") {
                                                put("name", call.name)
                                                put("arguments", call.arguments)
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }

    private fun parseCompletion(body: String): ChatCompletion {
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val choice = root["choices"]
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?: throw AiApiException(AiErrorKind.INVALID_RESPONSE, detail = "Missing choices")
            val finishReason = choice["finish_reason"]
                ?.let { element -> (element as? JsonPrimitive)?.contentOrNull }
            if (finishReason == "length") {
                throw AiApiException(
                    AiErrorKind.TRUNCATED,
                    detail = "The model stopped because it reached the output limit.",
                )
            }
            val message = choice["message"]?.jsonObject ?: JsonObject(emptyMap())
            val content = message["content"].assistantText()
            val reasoning = message["reasoning_content"].assistantText()
                ?: message["reasoning"].assistantText()
            val toolCalls = message["tool_calls"]?.let(::parseToolCalls).orEmpty()
            return ChatCompletion(
                content = content,
                toolCalls = toolCalls,
                finishReason = finishReason,
                usage = parseUsage(root["usage"]),
                reasoningContent = reasoning,
            )
        } catch (e: AiApiException) {
            throw e
        } catch (e: Exception) {
            throw AiApiException(
                AiErrorKind.INVALID_RESPONSE,
                detail = e.message ?: "Malformed response",
            )
        }
    }

    private fun parseToolCalls(element: JsonElement): List<ChatToolCall> {
        val array = element as? JsonArray ?: return emptyList()
        return array.mapIndexedNotNull { index, item ->
            val call = item as? JsonObject ?: return@mapIndexedNotNull null
            val function = call["function"] as? JsonObject ?: return@mapIndexedNotNull null
            val name = (function["name"] as? JsonPrimitive)?.contentOrNull
                ?: return@mapIndexedNotNull null
            val argumentsElement = function["arguments"]
            val arguments = when (argumentsElement) {
                is JsonPrimitive -> argumentsElement.content
                is JsonObject, is JsonArray -> argumentsElement.toString()
                else -> "{}"
            }
            val id = (call["id"] as? JsonPrimitive)?.contentOrNull ?: "call_$index"
            ChatToolCall(id = id, name = name, arguments = arguments)
        }
    }

    private fun JsonElement?.assistantText(): String? = when (this) {
        null, JsonNull -> null
        is JsonPrimitive -> content
        is JsonArray -> joinToString("") { part ->
            (part as? JsonObject)?.get("text")
                ?.let { text -> (text as? JsonPrimitive)?.contentOrNull }
                .orEmpty()
        }.ifBlank { null }

        else -> null
    }

    private fun parseModels(body: String): List<AiModelInfo> {
        try {
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"] as? JsonArray ?: return emptyList()
            return data.mapNotNull { item ->
                val model = item as? JsonObject ?: return@mapNotNull null
                val id = (model["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val displayName = (model["name"] as? JsonPrimitive)?.contentOrNull ?: id
                val context = (model["context_length"] as? JsonPrimitive)?.longOrNull
                    ?: (model["context_window"] as? JsonPrimitive)?.longOrNull
                val endpoints = (model["supported_endpoints"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    .orEmpty()
                AiModelInfo(
                    id = id,
                    name = displayName,
                    contextLength = context,
                    supportedEndpoints = endpoints,
                )
            }
        } catch (e: Exception) {
            throw AiApiException(
                AiErrorKind.INVALID_RESPONSE,
                detail = e.message ?: "Malformed model list",
            )
        }
    }

    private fun parseUsage(element: JsonElement?): AiUsage {
        val usage = element as? JsonObject ?: return AiUsage()
        val deepSeekCache = (usage["prompt_cache_hit_tokens"] as? JsonPrimitive)?.longOrNull
        val detailsCache = (usage["prompt_tokens_details"] as? JsonObject)
            ?.get("cached_tokens")
            ?.let { (it as? JsonPrimitive)?.longOrNull }
        val cacheReported = deepSeekCache != null || detailsCache != null
        val cachedTokens = deepSeekCache?.takeIf { it > 0 } ?: detailsCache ?: 0L
        val reasoningFromDetails = (usage["completion_tokens_details"] as? JsonObject)
            ?.get("reasoning_tokens")
            ?.let { (it as? JsonPrimitive)?.longOrNull }
            ?: 0L
        return AiUsage(
            promptTokens = usage.longValue("prompt_tokens"),
            completionTokens = usage.longValue("completion_tokens"),
            cachedTokens = cachedTokens,
            reasoningTokens = reasoningFromDetails,
            cacheReported = cacheReported,
        )
    }

    private fun JsonObject.longValue(key: String): Long =
        (this[key] as? JsonPrimitive)?.longOrNull ?: 0L

    private suspend fun execute(request: Request): HttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                AiApiException(AiErrorKind.NETWORK, detail = e.message),
                            )
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            val body = try {
                                it.body.string()
                            } catch (e: Exception) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(
                                        AiApiException(
                                            AiErrorKind.INVALID_RESPONSE,
                                            detail = e.message,
                                        ),
                                    )
                                }
                                return
                            }
                            if (continuation.isActive) {
                                continuation.resume(HttpResponse(it.code, body))
                            }
                        }
                    }
                },
            )
        }

    private fun errorFor(status: Int, body: String): AiApiException {
        val kind = when {
            status == 401 || status == 403 -> AiErrorKind.UNAUTHORIZED
            status == 402 -> AiErrorKind.INSUFFICIENT_CREDIT
            status == 404 -> AiErrorKind.MODEL_NOT_FOUND
            status == 429 -> AiErrorKind.RATE_LIMITED
            status in 500..599 -> AiErrorKind.SERVER_ERROR
            else -> AiErrorKind.UNKNOWN
        }
        val detail = providerMessage(body) ?: body.take(200).ifBlank { null }
        return AiApiException(kind, status, detail)
    }

    private fun providerMessage(body: String): String? =
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            when (val error = root["error"]) {
                is JsonObject -> (error["message"] as? JsonPrimitive)?.contentOrNull
                is JsonPrimitive -> error.contentOrNull
                else -> (root["message"] as? JsonPrimitive)?.contentOrNull
            }
        }.getOrNull()?.take(400)

    private data class HttpResponse(val code: Int, val body: String)

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .callTimeout(150, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
