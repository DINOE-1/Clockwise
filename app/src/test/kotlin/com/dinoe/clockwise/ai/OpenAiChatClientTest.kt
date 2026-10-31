package com.dinoe.clockwise.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
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
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

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
        assertEquals(10, tools.size)
        assertEquals(
            setOf(
                AiTools.LIST_ALARMS,
                AiTools.LIST_EVENTS,
                AiTools.LIST_RINGTONES,
                AiTools.CREATE_ALARMS,
                AiTools.CREATE_ALARM_STACKS,
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
    fun userMessageWithImageSerializesContentParts() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        client.complete(
            preset = AiProviders.commandCode.copy(baseUrl = baseUrl("/provider/v1")),
            apiKey = "user_test",
            model = "vision-model",
            messages = listOf(
                ChatMessage(
                    role = ChatRole.USER,
                    content = "read this",
                    images = listOf(ChatImage(base64 = "aGVsbG8=")),
                ),
            ),
            tools = AiTools.definitions,
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val parts = body["messages"]!!
            .jsonArray
            .first()
            .jsonObject["content"]!!
            .jsonArray
        assertEquals(2, parts.size)
        assertEquals("text", parts[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("read this", parts[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("image_url", parts[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/jpeg;base64,aGVsbG8=",
            parts[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
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
    fun deepSeekEchoesReasoningContentOnEarlierAssistantMessages() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        client.complete(
            preset = AiProviders.deepSeek.copy(baseUrl = baseUrl()),
            apiKey = "sk-test",
            model = "deepseek-flash",
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
    fun deepSeekFillsEmptyReasoningForMixedHistoriesWhenThinkingIsOn() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
            ),
        )
        client.complete(
            preset = AiProviders.deepSeek.copy(baseUrl = baseUrl()),
            apiKey = "sk-test",
            model = "deepseek-flash",
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
    fun nonDeepSeekProviderOmitsReasoningContentAndEmptyFallback() = runTest {
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
                ChatMessage(role = ChatRole.ASSISTANT, content = "old answer"),
                ChatMessage(ChatRole.USER, "third"),
            ),
            tools = AiTools.definitions,
            reasoningEffort = AiReasoningEffort.LOW,
        )
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val messages = body["messages"]!!.jsonArray
        val assistants = messages
            .map { it.jsonObject }
            .filter { it["role"]!!.jsonPrimitive.content == "assistant" }
        assertEquals(2, assistants.size)
        assertTrue(assistants.none { it.containsKey("reasoning_content") })
        assertEquals("answer", assistants[0]["content"]!!.jsonPrimitive.content)
        assertEquals("old answer", assistants[1]["content"]!!.jsonPrimitive.content)
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
    fun listModelsParsesImageInputModalities() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    {"object":"list","data":[
                      {"id":"vision-model","name":"Vision","architecture":{"input_modalities":["text","image"]}},
                      {"id":"text-model","name":"Text","architecture":{"input_modalities":["text"]}},
                      {"id":"legacy-model","name":"Legacy"}
                    ]}
                    """.trimIndent(),
                ),
        )
        val models = client.listModels(
            preset = AiProviders.commandCode.copy(baseUrl = baseUrl("/provider/v1")),
            apiKey = null,
        )
        assertEquals(true, models.first { it.id == "vision-model" }.supportsImages)
        assertEquals(false, models.first { it.id == "text-model" }.supportsImages)
        assertNull(models.first { it.id == "legacy-model" }.supportsImages)
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

    @Test
    fun buildCompletionBodySerializesRequestFields() {
        val body = Json.parseToJsonElement(
            client.buildCompletionBody(
                preset = AiProviders.deepSeek,
                model = "deepseek-flash",
                messages = listOf(
                    ChatMessage(role = ChatRole.SYSTEM, content = "sys"),
                    ChatMessage(
                        role = ChatRole.ASSISTANT,
                        content = "calling",
                        toolCalls = listOf(
                            ChatToolCall(
                                id = "call_1",
                                name = AiTools.CREATE_ALARMS,
                                arguments = """{"alarms":[]}""",
                            ),
                        ),
                        reasoningContent = "because",
                    ),
                    ChatMessage(role = ChatRole.TOOL, content = "ok", toolCallId = "call_1"),
                    ChatMessage(
                        role = ChatRole.USER,
                        content = "next",
                        images = listOf(ChatImage(base64 = "aGVsbG8=")),
                    ),
                ),
                tools = AiTools.definitions,
                maxTokens = 512,
            ),
        ).jsonObject

        assertEquals(10, body["tools"]!!.jsonArray.size)
        assertEquals("auto", body["tool_choice"]!!.jsonPrimitive.content)
        assertEquals(512, body["max_tokens"]!!.jsonPrimitive.int)
        val messages = body["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("system", messages[0]["role"]!!.jsonPrimitive.content)
        assertEquals("because", messages[1]["reasoning_content"]!!.jsonPrimitive.content)
        val function = messages[1]["tool_calls"]!!
            .jsonArray
            .first()
            .jsonObject["function"]!!
            .jsonObject
        assertEquals(AiTools.CREATE_ALARMS, function["name"]!!.jsonPrimitive.content)
        assertEquals("call_1", messages[2]["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals(
            "image_url",
            messages[3]["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun buildCompletionBodyOmitsToolsWhenNoneAreProvided() {
        val withoutTools = Json.parseToJsonElement(
            client.buildCompletionBody(
                preset = AiProviders.commandCode,
                model = "deepseek/deepseek-v4.1-flash",
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                tools = null,
            ),
        ).jsonObject
        assertFalse(withoutTools.containsKey("tools"))
        assertFalse(withoutTools.containsKey("tool_choice"))

        val emptyTools = Json.parseToJsonElement(
            client.buildCompletionBody(
                preset = AiProviders.commandCode,
                model = "deepseek/deepseek-v4.1-flash",
                messages = listOf(ChatMessage(ChatRole.USER, "hello")),
                tools = kotlinx.serialization.json.JsonArray(emptyList()),
            ),
        ).jsonObject
        assertFalse(emptyTools.containsKey("tools"))
    }

    @Test
    fun parseCompletionBodyReadsContentPartsAndToolArgumentShapes() {
        val completion = client.parseCompletionBody(
            """
            {"choices":[{"finish_reason":"tool_calls","message":{
              "content":[{"type":"text","text":"hello "},{"type":"text","text":"world"}],
              "reasoning":"thinking",
              "tool_calls":[
                {"id":"call-1","function":{"name":"create_alarms","arguments":{"alarms":[]}}},
                {"function":{"name":"list_alarms"}}
              ]
            }}],"usage":{"prompt_tokens":3,"completion_tokens":4}}
            """.trimIndent(),
        )
        assertEquals("hello world", completion.content)
        assertEquals("thinking", completion.reasoningContent)
        assertEquals("tool_calls", completion.finishReason)
        assertEquals(2, completion.toolCalls.size)
        assertEquals("call-1", completion.toolCalls[0].id)
        assertEquals("create_alarms", completion.toolCalls[0].name)
        assertEquals("""{"alarms":[]}""", completion.toolCalls[0].arguments)
        assertEquals("call_1", completion.toolCalls[1].id)
        assertEquals("{}", completion.toolCalls[1].arguments)
        assertEquals(3L, completion.usage.promptTokens)
        assertEquals(4L, completion.usage.completionTokens)
    }

    @Test
    fun parseCompletionBodyMapsTruncationAndMalformedPayloads() {
        try {
            client.parseCompletionBody(
                """{"choices":[{"finish_reason":"length","message":{"content":"x"}}]}""",
            )
            fail("Expected AiApiException")
        } catch (e: AiApiException) {
            assertEquals(AiErrorKind.TRUNCATED, e.kind)
        }
        try {
            client.parseCompletionBody("""{"usage":{}}""")
            fail("Expected AiApiException")
        } catch (e: AiApiException) {
            assertEquals(AiErrorKind.INVALID_RESPONSE, e.kind)
            assertEquals("Missing choices", e.detail)
        }
        try {
            client.parseCompletionBody("not json")
            fail("Expected AiApiException")
        } catch (e: AiApiException) {
            assertEquals(AiErrorKind.INVALID_RESPONSE, e.kind)
        }
    }

    @Test
    fun parseModelsBodyReadsCatalogFields() {
        val models = client.parseModelsBody(
            """
            {"object":"list","data":[
              {"id":"a","context_window":4096,"input_modalities":["text","image"]},
              {"id":"b"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("a", "b"), models.map { it.id })
        assertEquals("a", models[0].name)
        assertEquals(4096L, models[0].contextLength)
        assertEquals(true, models[0].supportsImages)
        assertNull(models[1].supportsImages)
        assertTrue(client.parseModelsBody("""{"object":"list"}""").isEmpty())
    }

    @Test
    fun completionBuildsAndParsesJsonOnTheComputeDispatcher() = runTest {
        val recording = RecordingDispatcher()
        try {
            server.enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}""",
                ),
            )
            val preset = AiProviders.deepSeek.copy(baseUrl = baseUrl())
            val dispatchingClient = OpenAiChatClient(computeDispatcher = recording)
            val messages = listOf(ChatMessage(ChatRole.USER, "hello"))
            val result = dispatchingClient.complete(
                preset = preset,
                apiKey = "sk-test",
                model = "deepseek-flash",
                messages = messages,
                tools = AiTools.definitions,
            )
            assertEquals("ok", result.content)
            // Request serialization and response parsing both ran on the compute dispatcher.
            assertTrue(recording.threads.size >= 2)
            assertEquals(setOf(COMPUTE_THREAD), recording.threads.toSet())
            assertEquals(
                dispatchingClient.buildCompletionBody(
                    preset = preset,
                    model = "deepseek-flash",
                    messages = messages,
                    tools = AiTools.definitions,
                ),
                server.takeRequest().body.readUtf8(),
            )
        } finally {
            recording.shutdown()
        }
    }

    private fun baseUrl(path: String = "/"): String =
        server.url(path).toString().trimEnd('/')

    private class RecordingDispatcher : CoroutineDispatcher() {
        private val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, COMPUTE_THREAD)
        }
        val threads = Collections.synchronizedList(mutableListOf<String>())

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            executor.execute {
                threads += Thread.currentThread().name
                block.run()
            }
        }

        fun shutdown() {
            executor.shutdownNow()
        }
    }

    private companion object {
        const val COMPUTE_THREAD = "ai-json-compute"
    }
}
