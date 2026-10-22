package com.dinoe.clockwise.ai

import kotlinx.serialization.json.JsonArray

data class BuiltContext(
    val messages: List<ChatMessage>,
    val trimmed: Boolean,
    val estimatedInputTokens: Int,
)

class AiContextOverflowException(
    val estimatedTokens: Int,
) : Exception("Context exceeds the hard budget ($estimatedTokens tokens)")

/**
 * Builds the request context in a cache-friendly order:
 * static system prompt -> compact completed turns -> current turn -> dynamic tail.
 * Tool payloads from completed turns are never included.
 */
class AiContextBuilder {
    fun build(
        completedTurns: List<CompletedTurn>,
        currentTurn: List<ChatMessage>,
        dynamicContext: String,
        staticPrompt: String = AiPrompt.STATIC_SYSTEM_PROMPT,
        toolDefinitions: JsonArray? = AiTools.definitions,
        embedDynamicInLastUser: Boolean = false,
    ): BuiltContext {
        val keptTurns = ArrayDeque<CompletedTurn>()
        var historyTokens = 0
        for (turn in completedTurns.asReversed()) {
            if (keptTurns.size >= AiTokenBudget.MAX_COMPLETED_TURNS) break
            val cost = estimateTurn(turn)
            if (keptTurns.isNotEmpty() &&
                historyTokens + cost > AiTokenBudget.MAX_HISTORY_TOKENS
            ) {
                break
            }
            keptTurns.addFirst(turn)
            historyTokens += cost
        }

        val dynamic = AiTokenBudget.truncateToTokens(
            dynamicContext,
            AiTokenBudget.MAX_DYNAMIC_TOKENS,
        )

        val messages = ArrayList<ChatMessage>(keptTurns.size * 2 + currentTurn.size + 2)
        messages += ChatMessage(role = ChatRole.SYSTEM, content = staticPrompt)
        keptTurns.forEach { turn ->
            messages += ChatMessage(role = ChatRole.USER, content = turn.user)
            messages += ChatMessage(
                role = ChatRole.ASSISTANT,
                content = turn.assistant,
                reasoningContent = turn.reasoningContent,
            )
        }
        if (embedDynamicInLastUser) {
            val lastUserIndex = currentTurn.indexOfLast { it.role == ChatRole.USER }
            currentTurn.forEachIndexed { index, message ->
                if (index == lastUserIndex) {
                    val merged = buildString {
                        append(message.content.orEmpty())
                        appendLine()
                        appendLine()
                        append(dynamic)
                    }
                    messages += message.copy(content = merged)
                } else {
                    messages += message
                }
            }
        } else {
            messages += currentTurn
            messages += ChatMessage(role = ChatRole.SYSTEM, content = dynamic)
        }

        val estimated = estimateMessages(messages) +
            (toolDefinitions?.let { AiTokenBudget.estimateTokens(it.toString()) } ?: 0)
        if (estimated > AiTokenBudget.HARD_INPUT_TOKENS) {
            throw AiContextOverflowException(estimated)
        }
        return BuiltContext(
            messages = messages,
            trimmed = keptTurns.size < completedTurns.size,
            estimatedInputTokens = estimated,
        )
    }

    fun estimateTurn(turn: CompletedTurn): Int =
        AiTokenBudget.estimateMessageTokens(turn.user) +
            AiTokenBudget.estimateMessageTokens(turn.assistant) +
            AiTokenBudget.estimateTokens(turn.reasoningContent)

    fun estimateMessages(messages: List<ChatMessage>): Int =
        messages.sumOf { message ->
            AiTokenBudget.estimateMessageTokens(
                content = message.content,
                toolCallTokens = message.toolCalls.sumOf { call ->
                    AiTokenBudget.estimateTokens(call.name) +
                        AiTokenBudget.estimateTokens(call.arguments)
                } + AiTokenBudget.estimateTokens(message.reasoningContent),
            )
        }
}
