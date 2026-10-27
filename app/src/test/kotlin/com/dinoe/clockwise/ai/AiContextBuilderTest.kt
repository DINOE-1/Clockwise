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
    fun buildsStaticThenTurnsThenDynamicInTheFinalUserMessage() {
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
        assertEquals(ChatRole.USER, built.messages.last().role)
        assertTrue(built.messages.last().content!!.startsWith("create alarms"))
        assertTrue(built.messages.last().content!!.contains("CURRENT CONTEXT"))
        assertTrue(built.messages.none { it.role == ChatRole.SYSTEM && it != built.messages.first() })
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER),
            built.messages.subList(1, built.messages.size).map { it.role },
        )
        assertTrue(!built.trimmed)
    }

    @Test
    fun keepsAttachedImageAndCountsItsTokens() {
        val built = builder.build(
            completedTurns = emptyList(),
            currentTurn = listOf(
                ChatMessage(
                    role = ChatRole.USER,
                    content = "read this",
                    images = listOf(ChatImage(base64 = "abc")),
                ),
            ),
            dynamicContext = "CURRENT CONTEXT now",
        )
        val user = built.messages.last()
        assertEquals(1, user.images.size)
        assertTrue(user.content!!.contains("CURRENT CONTEXT"))
        assertTrue(built.estimatedInputTokens > AiTokenBudget.IMAGE_INPUT_TOKENS)
    }

    @Test
    fun separateDynamicUserMessageComesImmediatelyBeforeTheFinalUserMessage() {
        val current = listOf(
            ChatMessage(ChatRole.USER, "one"),
            ChatMessage(ChatRole.ASSISTANT, "ok"),
            ChatMessage(ChatRole.USER, "two"),
        )
        val built = builder.build(
            completedTurns = emptyList(),
            currentTurn = current,
            dynamicContext = "CURRENT CONTEXT now",
            embedDynamicInLastUser = false,
        )
        assertTrue(built.messages.none { it.role == ChatRole.SYSTEM && it != built.messages.first() })
        assertEquals(ChatRole.USER, built.messages.last().role)
        assertEquals("two", built.messages.last().content)
        val dynamicMessage = built.messages[built.messages.lastIndex - 1]
        assertEquals(ChatRole.USER, dynamicMessage.role)
        assertTrue(dynamicMessage.content!!.startsWith("CURRENT CONTEXT"))
        assertEquals(
            listOf(ChatRole.USER, ChatRole.ASSISTANT, ChatRole.USER, ChatRole.USER),
            built.messages.subList(1, built.messages.size).map { it.role },
        )
    }

    @Test
    fun dynamicContextMergesIntoTheLastUserMessageOfAToolRound() {
        val current = listOf(
            ChatMessage(ChatRole.USER, "create alarms"),
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = null,
                toolCalls = listOf(
                    ChatToolCall(id = "call-1", name = AiTools.CREATE_ALARMS, arguments = "{}"),
                ),
            ),
            ChatMessage(role = ChatRole.TOOL, content = "ok", toolCallId = "call-1"),
        )
        val built = builder.build(
            completedTurns = emptyList(),
            currentTurn = current,
            dynamicContext = "CURRENT CONTEXT now",
        )
        assertEquals(ChatRole.TOOL, built.messages.last().role)
        assertTrue(built.messages.none { it.role == ChatRole.SYSTEM && it != built.messages.first() })
        val user = built.messages.first { it.role == ChatRole.USER }
        val userContent = user.content!!
        assertTrue(userContent.contains("create alarms"))
        assertTrue(userContent.contains("CURRENT CONTEXT"))
    }

    @Test
    fun addsADynamicUserMessageWhenTheCurrentTurnHasNoUserMessage() {
        val built = builder.build(
            completedTurns = emptyList(),
            currentTurn = listOf(ChatMessage(ChatRole.ASSISTANT, "orphan")),
            dynamicContext = "CURRENT CONTEXT now",
        )
        assertEquals(ChatRole.USER, built.messages.last().role)
        assertTrue(built.messages.last().content!!.contains("CURRENT CONTEXT"))
        assertTrue(built.messages.none { it.role == ChatRole.SYSTEM && it != built.messages.first() })
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
        val keptHistory = built.messages.count {
            it.role == ChatRole.USER && it.content?.endsWith("x") == true
        }
        assertTrue(keptHistory in 1..5)
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

    @Test
    fun largeModelContextRaisesTheHardCap() {
        val huge = "x".repeat(60_000)
        try {
            builder.build(
                completedTurns = emptyList(),
                currentTurn = listOf(ChatMessage(ChatRole.USER, huge)),
                dynamicContext = "ctx",
            )
            fail("Expected the default budget to reject the request")
        } catch (_: AiContextOverflowException) {
            // expected: the 60k-character turn is larger than the default 12k cap
        }
        val built = builder.build(
            completedTurns = emptyList(),
            currentTurn = listOf(ChatMessage(ChatRole.USER, huge)),
            dynamicContext = "ctx",
            modelContextLength = 1_000_000,
        )
        assertTrue(built.estimatedInputTokens > AiTokenBudget.HARD_INPUT_TOKENS)
        assertEquals(ChatRole.USER, built.messages.last().role)
    }

    @Test
    fun smallModelContextLowersTheHardCap() {
        val content = "x".repeat(20_000)
        builder.build(
            completedTurns = emptyList(),
            currentTurn = listOf(ChatMessage(ChatRole.USER, content)),
            dynamicContext = "ctx",
        )
        try {
            builder.build(
                completedTurns = emptyList(),
                currentTurn = listOf(ChatMessage(ChatRole.USER, content)),
                dynamicContext = "ctx",
                modelContextLength = 8_192,
            )
            fail("Expected AiContextOverflowException for the smaller model context")
        } catch (_: AiContextOverflowException) {
            // expected: output reserve leaves less input room than the default budget
        }
    }

    @Test
    fun hugeModelContextIsStillCapped() {
        val enormous = "x".repeat(200_000)
        try {
            builder.build(
                completedTurns = emptyList(),
                currentTurn = listOf(ChatMessage(ChatRole.USER, enormous)),
                dynamicContext = "ctx",
                modelContextLength = 1_000_000,
            )
            fail("Expected AiContextOverflowException for the derived cap")
        } catch (e: AiContextOverflowException) {
            assertTrue(e.estimatedTokens > AiTokenBudget.MAX_DERIVED_INPUT_TOKENS)
        }
    }
}
