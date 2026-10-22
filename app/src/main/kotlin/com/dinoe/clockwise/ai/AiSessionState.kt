package com.dinoe.clockwise.ai

data class CompletedTurn(
    val user: String,
    val assistant: String,
    // Kept so the next tools-bearing request can echo reasoning for earlier assistant messages.
    val reasoningContent: String? = null,
)

/**
 * Deterministic, in-memory memory for one chat. Raw tool calls and tool results are intentionally
 * kept out of this structure: the model only receives compact completed turns plus the action log
 * injected in the dynamic context.
 */
class AiSessionState {
    private val turns = ArrayDeque<CompletedTurn>()
    private val actions = ArrayDeque<String>()

    var usage: AiUsage = AiUsage()
        private set
    var requestCount: Int = 0
        private set
    private var historyTrimmed = false

    val completedTurns: List<CompletedTurn> get() = turns.toList()
    val actionLines: List<String> get() = actions.toList()

    fun completeTurn(
        userText: String,
        assistantText: String,
        reasoningContent: String? = null,
    ) {
        val turn = CompletedTurn(
            user = AiTokenBudget.truncateChars(userText.trim(), AiTokenBudget.COMPLETED_USER_CHARS),
            assistant = AiTokenBudget.truncateChars(
                assistantText.trim(),
                AiTokenBudget.COMPLETED_ASSISTANT_CHARS,
            ),
            reasoningContent = reasoningContent?.let {
                AiTokenBudget.truncateChars(it, AiTokenBudget.COMPLETED_REASONING_CHARS)
            },
        )
        turns.addLast(turn)
        while (turns.size > AiTokenBudget.MAX_COMPLETED_TURNS) {
            turns.removeFirst()
            historyTrimmed = true
        }
    }

    fun restore(
        restoredTurns: List<CompletedTurn>,
        restoredActions: List<String>,
        restoredUsage: AiUsage,
        restoredRequestCount: Int,
    ) {
        turns.clear()
        restoredTurns.takeLast(AiTokenBudget.MAX_COMPLETED_TURNS).forEach(turns::addLast)
        actions.clear()
        restoredActions.takeLast(AiTokenBudget.MAX_ACTIONS).forEach(actions::addLast)
        usage = restoredUsage
        requestCount = restoredRequestCount
        historyTrimmed = false
    }

    fun addAction(line: String) {
        val compact = AiTokenBudget.truncateChars(line.trim(), 220)
        if (compact.isBlank()) return
        actions.addLast(compact)
        while (actions.size > AiTokenBudget.MAX_ACTIONS) actions.removeFirst()
    }

    fun recordUsage(requestUsage: AiUsage) {
        usage += requestUsage
        requestCount++
    }

    fun consumeHistoryTrimmed(): Boolean {
        val value = historyTrimmed
        historyTrimmed = false
        return value
    }

    fun clear() {
        turns.clear()
        actions.clear()
        usage = AiUsage()
        requestCount = 0
        historyTrimmed = false
    }
}
