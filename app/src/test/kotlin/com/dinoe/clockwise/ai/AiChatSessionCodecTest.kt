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
    fun roundTripsMessageThumbnail() {
        val chat = PersistedChat(
            messages = listOf(PersistedChatMessage("user", "photo", "abc123")),
        )
        val decoded = AiChatSessionCodec.decode(AiChatSessionCodec.encode(chat))!!
        assertEquals("abc123", decoded.messages.single().image)
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

    @Test
    fun snapshotKeepsOnlyTranscriptItems() {
        val items = listOf<AiChatItem>(
            AiChatItem.User(1, "hello", "thumb"),
            AiChatItem.Proposal(2, state = ProposalState.PENDING),
            AiChatItem.Assistant(3, "hi"),
            AiChatItem.Notice(4, "notice"),
            AiChatItem.Error(5, "boom", retryable = true),
        )
        val messages = AiChatSessionStore.messagesOf(items)
        assertEquals(
            listOf(
                PersistedChatMessage("user", "hello", "thumb"),
                PersistedChatMessage("assistant", "hi"),
            ),
            messages,
        )
    }

    @Test
    fun snapshotCapsVisibleHistory() {
        val items = (1..(AiChatSessionCodec.MAX_UI_MESSAGES + 10)).map {
            AiChatItem.User(it.toLong(), "m$it")
        }
        val messages = AiChatSessionStore.messagesOf(items)
        assertEquals(AiChatSessionCodec.MAX_UI_MESSAGES, messages.size)
        assertEquals("m11", messages.first().text)
        assertEquals(
            AiChatSessionCodec.MAX_UI_MESSAGES,
            AiChatSessionStore.chatOf(ChatPersistenceSnapshot(items = items)).messages.size,
        )
    }

    @Test
    fun snapshotConversionCarriesModelMemory() {
        val snapshot = ChatPersistenceSnapshot(
            items = listOf(AiChatItem.User(1, "hello")),
            turns = listOf(CompletedTurn("hello", "hi", "because")),
            actions = listOf("created 1 alarm"),
            usage = AiUsage(promptTokens = 7, cacheReported = true),
            requestCount = 3,
        )
        val chat = AiChatSessionStore.chatOf(snapshot)
        assertEquals(listOf(PersistedChatMessage("user", "hello")), chat.messages)
        assertEquals(1, chat.turns.size)
        assertEquals(listOf("created 1 alarm"), chat.actions)
        assertEquals(AiUsage(promptTokens = 7, cacheReported = true), chat.usage)
        assertEquals(3, chat.requestCount)
        // The converted snapshot still round-trips through the persisted schema.
        assertEquals(chat, AiChatSessionCodec.decode(AiChatSessionCodec.encode(chat)))
    }

    @Test
    fun previewUsesLastMessageAndStaysShort() {
        assertEquals(
            "second",
            AiChatSessionStore.previewOf(
                PersistedChat(
                    messages = listOf(
                        PersistedChatMessage("user", "first"),
                        PersistedChatMessage("assistant", "  second  "),
                    ),
                ),
            ),
        )
        val preview = AiChatSessionStore.previewOf(
            PersistedChat(messages = listOf(PersistedChatMessage("user", "x".repeat(500)))),
        )
        assertEquals(AiChatSessionStore.PREVIEW_CHARS, preview.length)
        assertEquals("", AiChatSessionStore.previewOf(PersistedChat()))
    }

    @Test
    fun restorePrefersStoredTranscript() {
        val chat = PersistedChat(
            messages = listOf(PersistedChatMessage("user", "kept")),
            turns = listOf(CompletedTurn("kept", "answer")),
        )
        assertEquals(
            listOf(PersistedChatMessage("user", "kept")),
            AiChatSessionStore.transcriptOf(chat),
        )
    }

    @Test
    fun restoreRebuildsTranscriptFromTurnsWhenMessagesAreMissing() {
        val chat = PersistedChat(
            turns = listOf(
                CompletedTurn("hello", "hi"),
                CompletedTurn("again", "sure"),
            ),
        )
        assertEquals(
            listOf(
                PersistedChatMessage("user", "hello"),
                PersistedChatMessage("assistant", "hi"),
                PersistedChatMessage("user", "again"),
                PersistedChatMessage("assistant", "sure"),
            ),
            AiChatSessionStore.transcriptOf(chat),
        )
    }
}
