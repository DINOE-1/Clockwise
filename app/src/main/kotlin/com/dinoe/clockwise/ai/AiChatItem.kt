package com.dinoe.clockwise.ai

enum class ProposalState {
    PENDING,
    APPLIED,
    CANCELLED,
}

/**
 * UI transcript items. The API context is built separately from [AiSessionState], so proposal
 * cards and notices never inflate the model prompt.
 */
sealed interface AiChatItem {
    val id: Long

    data class User(
        override val id: Long,
        val text: String,
        // Small JPEG thumbnail (base64), kept only for the transcript. The full image is sent to
        // the model once and never stored.
        val image: String? = null,
    ) : AiChatItem

    data class Assistant(
        override val id: Long,
        val text: String,
    ) : AiChatItem

    data class Notice(
        override val id: Long,
        val text: String,
    ) : AiChatItem

    data class Error(
        override val id: Long,
        val text: String,
        val retryable: Boolean,
    ) : AiChatItem

    data class Proposal(
        override val id: Long,
        val alarms: List<AlarmDraft> = emptyList(),
        val alarmStacks: List<AlarmStackDraft> = emptyList(),
        val events: List<EventDraft> = emptyList(),
        val alarmUpdates: List<AlarmUpdateDraft> = emptyList(),
        val alarmDeletes: List<AlarmDeleteDraft> = emptyList(),
        val eventUpdates: List<EventUpdateDraft> = emptyList(),
        val eventDeletes: List<EventDeleteDraft> = emptyList(),
        val state: ProposalState,
    ) : AiChatItem
}
