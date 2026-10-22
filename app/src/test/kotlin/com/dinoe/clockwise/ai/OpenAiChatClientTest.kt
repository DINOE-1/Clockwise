package com.dinoe.clockwise.ai

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class OpenAiChatClientTest {
    private lateinit var server: MockWebServer
    private val client = OpenAiChatClient()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun deepSeekRequestUsesChatCompletionsAndDisabledThinking() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"choices":[{
                       "message":{"content":"hi","reasoning_content":"thought"},
                       "finish_reason":"stop"
                     }],
                     "usage":{
                       "prompt_tokens":10,
                       "completion_tokens":2,
                       "prompt_cache_hit_tokens":6,
                       "completion_tokens_details":{"reasoning_tokens":3}
                     }}
                    """.trimIndent(),
                ),
        )
        val preset = AiProviders.deepSeek.copy(baseUrl = baseUrl())
        val result = client.complete(
            preset = preset,
            apiKey = "sk-test",
            model = "deepseek-flash",
            messages = listOf(ChatMessage(ChatRole.USER, "hello")),
            tools = AiTools.definitions,
        )
        assertEquals("hi", result.content)
        assertEquals("thought", result.reasoningContent)
        assertEquals("stop", result.finishReason)
        assertEquals(10L, result.usage.promptTokens)
        assertEquals(6L, result.usage.cachedTokens)
        assertEquals(0.6, result.usage.cacheHitRate!!, 0.0001)
        assertEquals(3L, result.usage.reasoningTokens)

        val request = server.takeRequest()
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("deepseek-flash", body["model"]!!.jsonPrimitive.content)
        assertEquals(
            "enabled",
            body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content,
        )
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
        val tools = body["tools"]!!.jsonArray
        assertEquals(8, tools.size)
        assertEquals(
            setOf(
                AiTools.LIST_ALARMS,
                AiTools.LIST_EVENTS,
                AiTools.CREATE_ALARMS,
                AiTools.CREATE_EVENTS,
                AiTools.UPDATE_ALARMS,
                AiTools.DELETE_ALARMS,
                AiTools.UPDATE_EVENTS,
                AiTools.DELETE_EVENTS,
            ),
            tools.map { element ->
                element.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
            }.toSet(),
        )
        assertFalse(body["stream"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun commandCodeRequestUsesProviderRouteAndParsesToolCalls() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"choices":[{
                      "finish_reason":"tool_calls",
                      "message":{
                        "content":null,
                        "tool_calls":[{
                          "id":"call-1",
                          "type":"function",
                          "function":{
                            "name":"create_alarms",
                            "arguments":{"alarms":[{"time":"07:00"}]}
                          }
                        }]
                      }
                    }],
                    "usage":{
                      "prompt_tokens":20,
                      "completion_tokens":5,
                      "prompt_tokens_details":{"cached_tokens":8}
                    }}
                    """.trimIndent(),
                ),
        )
        val preset = AiProviders.commandCode.copy(baseUrl = baseUrl("/provider/v1"))
        val result = client.complete(
            preset = preset,
            apiKey = "user_test",
            model = "deepseek/deepseek-v4.1-flash",
            messages = listOf(ChatMessage(ChatRole.USER, "create")),
            tools = AiTools.definitions,
        )
        assertNull(result.content)
        assertEquals(1, result.toolCalls.size)
        val call = result.toolCalls.single()
        assertEquals("create_alarms", call.name)
        assertEquals(
            "07:00",
            Json.parseToJsonElement(call.arguments)
                .jsonObject["alarms"]!!
                .let { it as kotlinx.serialization.json.JsonArray }
                .first()
                .jsonObject["time"]!!
                .jsonPrimitive
                .content,
        )
        assertEquals(8L, result.usage.cachedTokens)
        assertTrue(result.usage.cacheReported)

        val request = server.takeRequest()
        assertEquals("/provider/v1/chat/completions", request.path)
        assertEquals("Bearer user_test", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse(body.containsKey("thinking"))
        assertEquals("low", body["reasoning_effort"]!!.jsonPrimitive.content)
    }

    @Test
    fun deepSeekOffDisablesThinkingAndOmitsEffort() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"hi"},"finish_reason":"stop"}]}""",
            ),
        )
        client.complete(
            preset = AiProviders.deepSeek.copy(baseUrl = baseUrl()),
            apiKey = "sk-test",
            model = "deepseek-flash",
            messages = listOf(ChatMessage(ChatRole.USER, "hello")),
            tools = AiTools.definitions,
            reasoningEffort = AiReasoningEffort.OFF,
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("disabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertFalse(body.containsKey("reasoning_effort"))
    }

    @Test
    fun echoesReasoningContentOnEarlierAssistantMessages() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        client.complete(
            preset = AiProviders.commandCode.copy(baseUrl = baseUrl("/provider/v1")),
            apiKey = "user_test",
            model = "deepseek/deepseek-v4.1-flash",
            messages = listOf(
                ChatMessage(ChatRole.USER, "first"),
                ChatMessage(
                    role = ChatRole.ASSISTANT,
                    content = "answer",
                    reasoningContent = "because",
                ),
                ChatMessage(ChatRole.USER, "second"),
            ),
            tools = AiTools.definitions,
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(
            "because",
            messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun fillsEmptyReasoningForMixedHistoriesWhenThinkingIsOn() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        client.complete(
            preset = AiProviders.commandCode.copy(baseUrl = baseUrl("/provider/v1")),
            apiKey = "user_test",
            model = "deepseek/deepseek-v4.1-flash",
            messages = listOf(
                ChatMessage(ChatRole.USER, "first"),
                ChatMessage(role = ChatRole.ASSISTANT, content = "old answer"),
                ChatMessage(ChatRole.USER, "second"),
            ),
            tools = AiTools.definitions,
            reasoningEffort = AiReasoningEffort.LOW,
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals("", messages[1].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    @Test
    fun unauthorizedErrorIsMappedAndDoesNotExposeTheKey() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid key"}}"""),
        )
        try {
            client.complete(
                preset = AiProviders.deepSeek.copy(baseUrl = baseUrl()),
                apiKey = "sk-secret-value",
                model = "deepseek-flash",
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
            )
            fail("Expected AiApiException")
        } catch (e: AiApiException) {
            assertEquals(AiErrorKind.UNAUTHORIZED, e.kind)
            assertEquals(401, e.status)
            assertEquals("Invalid key", e.detail)
            assertFalse(e.detail.orEmpty().contains("sk-secret-value"))
        }
    }

    @Test
    fun listModelsParsesCatalogAndFiltersChatModels() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"object":"list","data":[
                      {"id":"claude-sonnet-5","name":"Claude Sonnet 5","supported_endpoints":["/messages"]},
                      {"id":"deepseek/deepseek-v4.1-flash","name":"DeepSeek V4.1 Flash","context_length":1000000,"supported_endpoints":["/chat/completions","/responses"]},
                      {"id":"legacy-model","name":"Legacy"}
                    ]}
                    """.trimIndent(),
                ),
        )
        val raw = client.listModels(
            preset = AiProviders.commandCode.copy(baseUrl = baseUrl("/provider/v1")),
            apiKey = null,
        )
        assertEquals(3, raw.size)
        val chatModels = AiModelCatalog.chatCompletions(raw)
        assertEquals(listOf("deepseek/deepseek-v4.1-flash", "legacy-model"), chatModels.map { it.id })
        assertEquals(1_000_000L, chatModels.first().contextLength)

        val request = server.takeRequest()
        assertEquals("/provider/v1/models", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun deepSeekModelListRequiresKey() = runTest {
        try {
            client.listModels(AiProviders.deepSeek, apiKey = null)
            fail("Expected AiApiException")
        } catch (e: AiApiException) {
            assertEquals(AiErrorKind.MISSING_KEY, e.kind)
        }
    }

    private fun baseUrl(path: String = "/"): String =
        server.url(path).toString().trimEnd('/')
}
