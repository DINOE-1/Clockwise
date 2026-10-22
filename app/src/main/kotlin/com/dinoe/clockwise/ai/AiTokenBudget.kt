package com.dinoe.clockwise.ai

/**
 * Conservative, tokenizer-free budget estimates. The goal is to cap context growth, not to match
 * a provider's tokenizer exactly: CJK characters count as one token, other characters as one
 * quarter token, plus a small per-message overhead.
 */
object AiTokenBudget {
    const val MAX_STATIC_TOKENS = 3_000
    const val MAX_DYNAMIC_TOKENS = 500
    const val MAX_HISTORY_TOKENS = 1_600
    const val MAX_CURRENT_TOOL_TOKENS = 2_400
    const val MAX_TOOL_RESULT_TOKENS = 1_200
    const val MAX_INPUT_TOKENS = 8_000
    const val HARD_INPUT_TOKENS = 12_000
    const val MAX_TOOL_ROUNDS = 5
    const val MAX_BATCH_ITEMS = 30
    const val MAX_RECURRING_OCCURRENCES = 180
    const val MAX_TOTAL_OCCURRENCES = 200
    const val MAX_TOTAL_AFFECTED = 300
    const val MAX_USER_CHARS = 2_000
    const val MAX_COMPLETED_TURNS = 6
    const val MAX_ACTIONS = 6
    const val COMPLETED_USER_CHARS = 500
    const val COMPLETED_ASSISTANT_CHARS = 800
    const val COMPLETED_REASONING_CHARS = 1_200
    const val MAX_TURN_REASONING_CHARS = 4_000

    private const val MESSAGE_OVERHEAD_TOKENS = 4

    fun estimateTokens(text: String?): Int {
        if (text.isNullOrEmpty()) return 0
        var cjk = 0
        var other = 0
        for (char in text) {
            if (isCjk(char.code)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    fun estimateMessageTokens(content: String?, toolCallTokens: Int = 0): Int =
        MESSAGE_OVERHEAD_TOKENS + estimateTokens(content) + toolCallTokens

    fun truncateToTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (estimateTokens(text) <= maxTokens) return text
        val builder = StringBuilder()
        var tokens = 0.0
        for (char in text) {
            val cost = if (isCjk(char.code)) 1.0 else 0.25
            if (tokens + cost > maxTokens) break
            builder.append(char)
            tokens += cost
        }
        return builder.toString()
    }

    fun truncateChars(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars)

    private fun isCjk(code: Int): Boolean =
        code in 0x2E80..0x9FFF ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFFEF ||
            code in 0x20000..0x2FA1F
}
