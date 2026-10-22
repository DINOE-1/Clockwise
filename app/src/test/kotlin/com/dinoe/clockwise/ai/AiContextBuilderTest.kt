package com.dinoe.clockwise.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiContextBuilderTest {
    private val builder = AiContextBuilder()

    @Test
    fun staticPromptAndToolsStayWithinBudget() {
        val tokens = AiTokenBudget.estimateTokens(AiPrompt.STATIC_SYSTEM_PROMPT) +
            AiTokenBudget.estimateTokens(AiTools.definitions.toString())
        assertTrue(
            "static prompt + tools used $tokens estimated tokens",
            tokens <= AiTokenBudget.MAX_STATIC_TOKENS,
        )
    }

    @Test
    fun buildsStaticThenTurnsThenDynamicTail() {
        val completed = listOf(
            CompletedTurn(user = "hello", assistant = "hi"),
            CompletedTurn(user = "again", assistant = "sure"),
        )
        val current = listOf(ChatMessage(role = ChatRole.USER, content = "create alarms"))
        val built = builder.build(
            completedTurns = completed,
            currentTurn = current,
            dynamicContext = "CURRENT CONTEXT now",
        )
        assertEquals(ChatRole.SYSTEM, built.messages.first().role)
        assertEquals(AiPrompt.STATIC_SYSTEM_PROMPT, built.messages.first().content)
        assertEquals(ChatRole.SYSTEM, built.messages.last().role)
        assertTrue(built.messages.last().content!!.startsWith("CURRENT CONTEXT"))
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            built.messages.subList(1, built.messages.size - 1).map { it.role },
        )
        assertTrue(!built.trimmed)
    }

    @Test
    fun keepsAtMostSixCompletedTurns() {
        val completed = (1..8).map { CompletedTurn("u$it", "a$it") }
        val built = builder.build(
            completedTurns = completed,
            currentTurn = listOf(ChatMessage(ChatRole.USER, "now")),
            dynamicContext = "ctx",
        )
        val started = built.messages.count { it.role == ChatRole.USER } - 1
        assertEquals(6, started)
        assertTrue(built.trimmed)
        assertTrue(built.messages.any { it.content == "u3" })
        assertTrue(built.messages.none { it.content == "u1" })
    }

    @Test
    fun trimsByHistoryBudget() {
        val long = "x".repeat(4_000)
        val completed = (1..6).map { CompletedTurn("$it$long", "$it$long") }
        val built = builder.build(
            completedTurns = completed,
            currentTurn = listOf(ChatMessage(ChatRole.USER, "now")),
            dynamicContext = "ctx",
        )
        assertTrue(built.trimmed)
        val completedMessages = built.messages.count {
            it.role == ChatRole.USER && it.content != "now"
        }
        assertTrue(completedMessages < 6)
    }

    @Test
    fun includesReasoningForCompletedAssistantMessages() {
        val built = builder.build(
            completedTurns = listOf(
                CompletedTurn(user = "u", assistant = "a", reasoningContent = "because"),
            ),
            currentTurn = listOf(ChatMessage(ChatRole.USER, "now")),
            dynamicContext = "ctx",
        )
        val assistant = built.messages.first { it.role == ChatRole.ASSISTANT }
        assertEquals("because", assistant.reasoningContent)
    }

    @Test
    fun canEmbedDynamicContextInTheLastUserMessage() {
        val current = listOf(
            ChatMessage(ChatRole.USER, "one"),
            ChatMessage(ChatRole.ASSISTANT, "ok"),
            ChatMessage(ChatRole.USER, "two"),
        )
        val built = builder.build(
            completedTurns = emptyList(),
            currentTurn = current,
            dynamicContext = "CURRENT CONTEXT now",
            embedDynamicInLastUser = true,
        )
        assertEquals(ChatRole.USER, built.messages.last().role)
        assertTrue(built.messages.last().content!!.contains("CURRENT CONTEXT"))
        assertTrue(built.messages.none { it.role == ChatRole.SYSTEM && it.content!!.startsWith("CURRENT") })
    }

    @Test
    fun throwsWhenContextExceedsTheHardCap() {
        val huge = "x".repeat(60_000)
        try {
            builder.build(
                completedTurns = emptyList(),
                currentTurn = listOf(ChatMessage(ChatRole.USER, huge)),
                dynamicContext = "ctx",
            )
            fail("Expected AiContextOverflowException")
        } catch (_: AiContextOverflowException) {
            // expected
        }
    }
}
