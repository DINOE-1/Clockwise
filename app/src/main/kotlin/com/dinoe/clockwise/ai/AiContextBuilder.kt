package com.dinoe.clockwise.ai

import kotlinx.serialization.json.JsonArray

data class BuiltContext(
    val messages: List<ChatMessage>,
    val trimmed: Boolean,
    val estimatedInputTokens: Int,
)

class AiContextOverflowException(
    val estimatedTokens: Int,
    val inputBudgetTokens: Int = AiTokenBudget.HARD_INPUT_TOKENS,
) : Exception(
    "Context uses $estimatedTokens tokens, over the $inputBudgetTokens-token input budget",
)

/**
 * Builds the request context in a cache-friendly order:
 * static system prompt -> compact completed turns -> current turn (with the dynamic tail folded
 * into a user message). A request never ends with a system message: dynamic context is either
 * merged into the final user message or emitted as its own user message immediately before it.
 * Tool payloads from completed turns are never included.
 */
class AiContextBuilder {
    fun build(
        completedTurns: List<CompletedTurn>,
        currentTurn: List<ChatMessage>,
        dynamicContext: String,
        staticPrompt: String = AiPrompt.STATIC_SYSTEM_PROMPT,
        toolDefinitions: JsonArray? = AiTools.definitions,
        embedDynamicInLastUser: Boolean = true,
        modelContextLength: Long? = null,
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
        appendCurrentTurn(
            messages = messages,
            currentTurn = currentTurn,
            dynamic = dynamic,
            embedDynamicInLastUser = embedDynamicInLastUser,
        )
        // Defensive invariant: a caller-supplied system message must never be the last message.
        if (messages.lastOrNull()?.role == ChatRole.SYSTEM) {
            messages += ChatMessage(
                role = ChatRole.USER,
                content = dynamic.ifBlank { "Continue." },
            )
        }

        val estimated = estimateMessages(messages) +
            (toolDefinitions?.let { AiTokenBudget.estimateTokens(it.toString()) } ?: 0)
        val hardInputTokens = AiTokenBudget.inputBudgetFor(modelContextLength)
        if (estimated > hardInputTokens) {
            throw AiContextOverflowException(estimated, hardInputTokens)
        }
        return BuiltContext(
            messages = messages,
            trimmed = keptTurns.size < completedTurns.size,
            estimatedInputTokens = estimated,
        )
    }

    private fun appendCurrentTurn(
        messages: MutableList<ChatMessage>,
        currentTurn: List<ChatMessage>,
        dynamic: String,
        embedDynamicInLastUser: Boolean,
    ) {
        val lastUserIndex = currentTurn.indexOfLast { it.role == ChatRole.USER }
        if (lastUserIndex < 0) {
            messages += currentTurn
            messages += ChatMessage(role = ChatRole.USER, content = dynamic)
            return
        }
        if (dynamic.isBlank()) {
            messages += currentTurn
            return
        }
        if (embedDynamicInLastUser) {
            currentTurn.forEachIndexed { index, message ->
                messages += if (index == lastUserIndex) {
                    message.copy(
                        content = buildString {
                            append(message.content.orEmpty())
                            appendLine()
                            appendLine()
                            append(dynamic)
                        },
                    )
                } else {
                    message
                }
            }
        } else {
            currentTurn.forEachIndexed { index, message ->
                if (index == lastUserIndex) {
                    messages += ChatMessage(role = ChatRole.USER, content = dynamic)
                }
                messages += message
            }
        }
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
