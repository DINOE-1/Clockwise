package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatSessionCodecTest {
    @Test
    fun roundTripsMessagesTurnsActionsAndUsage() {
        val chat = PersistedChat(
            messages = listOf(
                PersistedChatMessage("user", "hello"),
                PersistedChatMessage("assistant", "hi"),
            ),
            turns = listOf(
                CompletedTurn(
                    user = "hello",
                    assistant = "hi",
                    reasoningContent = "because",
                ),
            ),
            actions = listOf("created 1 alarm"),
            usage = AiUsage(
                promptTokens = 100,
                completionTokens = 20,
                cachedTokens = 40,
                reasoningTokens = 5,
                cacheReported = true,
            ),
            requestCount = 2,
        )
        val decoded = AiChatSessionCodec.decode(AiChatSessionCodec.encode(chat))
        assertEquals(chat, decoded)
    }

    @Test
    fun capsUiMessagesOnEncode() {
        val messages = (1..(AiChatSessionCodec.MAX_UI_MESSAGES + 10)).map {
            PersistedChatMessage("user", "m$it")
        }
        val decoded = AiChatSessionCodec.decode(
            AiChatSessionCodec.encode(PersistedChat(messages = messages)),
        )!!
        assertEquals(AiChatSessionCodec.MAX_UI_MESSAGES, decoded.messages.size)
        assertEquals("m11", decoded.messages.first().text)
    }

    @Test
    fun returnsNullForBlankOrBrokenPayload() {
        assertNull(AiChatSessionCodec.decode(null))
        assertNull(AiChatSessionCodec.decode(""))
        assertNull(AiChatSessionCodec.decode("not json"))
        assertNull(AiChatSessionCodec.decode("[]"))
    }

    @Test
    fun missingOptionalFieldsUseDefaults() {
        val decoded = AiChatSessionCodec.decode(
            """{"messages":[{"role":"user","text":"hi"}],"turns":[]}""",
        )!!
        assertEquals(1, decoded.messages.size)
        assertTrue(decoded.turns.isEmpty())
        assertEquals(AiUsage(), decoded.usage)
        assertEquals(0, decoded.requestCount)
    }
}
