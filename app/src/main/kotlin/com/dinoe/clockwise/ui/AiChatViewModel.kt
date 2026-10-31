package com.dinoe.clockwise.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.R
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.AlarmSounds
import com.dinoe.clockwise.alarm.ReminderScheduler
import com.dinoe.clockwise.ai.AiApiException
import com.dinoe.clockwise.ai.AiChatItem
import com.dinoe.clockwise.ai.AiChatSessionStore
import com.dinoe.clockwise.ai.AiConfig
import com.dinoe.clockwise.ai.AiContextBuilder
import com.dinoe.clockwise.ai.AiContextOverflowException
import com.dinoe.clockwise.ai.AiDraftEditor
import com.dinoe.clockwise.ai.AiErrorKind
import com.dinoe.clockwise.ai.AiIdChunks
import com.dinoe.clockwise.ai.AiImageAttachment
import com.dinoe.clockwise.ai.AiImageProcessor
import com.dinoe.clockwise.ai.AiPrompt
import com.dinoe.clockwise.ai.AiProviderConfig
import com.dinoe.clockwise.ai.AiSessionState
import com.dinoe.clockwise.ai.AiTokenBudget
import com.dinoe.clockwise.ai.AiToolParseException
import com.dinoe.clockwise.ai.AiToolParser
import com.dinoe.clockwise.ai.AiToolPayload
import com.dinoe.clockwise.ai.AiToolReferences
import com.dinoe.clockwise.ai.AiTools
import com.dinoe.clockwise.ai.AiTurnCredentials
import com.dinoe.clockwise.ai.AiUsage
import com.dinoe.clockwise.ai.AlarmDeleteDraft
import com.dinoe.clockwise.ai.AlarmDraft
import com.dinoe.clockwise.ai.AlarmStackDraft
import com.dinoe.clockwise.ai.AlarmUpdateDraft
import com.dinoe.clockwise.ai.ChatImage
import com.dinoe.clockwise.ai.ChatMessage
import com.dinoe.clockwise.ai.ChatPersistenceSnapshot
import com.dinoe.clockwise.ai.ChatRole
import com.dinoe.clockwise.ai.ChatToolCall
import com.dinoe.clockwise.ai.EventDeleteDraft
import com.dinoe.clockwise.ai.EventDraft
import com.dinoe.clockwise.ai.EventUpdateDraft
import com.dinoe.clockwise.ai.ProposalState
import com.dinoe.clockwise.ai.ReferencedIds
import com.dinoe.clockwise.ai.RestoredChatSession
import com.dinoe.clockwise.ai.SessionOpenResult
import com.dinoe.clockwise.ai.TagRef
import com.dinoe.clockwise.data.AI_CHAT_SESSION_DEFAULT_TITLE
import com.dinoe.clockwise.data.AiChatSessionSummary
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.AlarmTagCrossRef
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.EventScope
import com.dinoe.clockwise.data.EventTagCrossRef
import com.dinoe.clockwise.data.Tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class AiChatViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ClockwiseApp
    private val repository = app.aiRepository
    private val writer = app.scheduleWriter
    private val tagDao = app.database.tagDao()
    private val alarmDao = app.database.alarmDao()
    private val eventDao = app.database.eventDao()
    private val sessionStore = AiChatSessionStore(app.database.chatSessionDao())
    private val imageProcessor = AiImageProcessor(app)
    private val preferences = ThemePreferences(app)

    val sessions: StateFlow<List<AiChatSessionSummary>> = sessionStore.summaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<Tag>> = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val timePickerStyle: StateFlow<TimePickerStyle> = preferences.timePickerStyle
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TimePickerStyle.WHEEL)

    val items = mutableStateListOf<AiChatItem>()
    var isBusy by mutableStateOf(false)
        private set
    var statusText by mutableStateOf<String?>(null)
        private set
    var config by mutableStateOf<AiConfig?>(null)
        private set
    var pendingProposalId by mutableStateOf<Long?>(null)
        private set
    var usage by mutableStateOf(AiUsage())
        private set
    var requestCount by mutableStateOf(0)
        private set
    var isRestoring by mutableStateOf(true)
        private set
    var activeSessionId by mutableStateOf<Long?>(null)
        private set
    var pendingImage by mutableStateOf<AiImageAttachment?>(null)
        private set
    var isImageLoading by mutableStateOf(false)
        private set

    /** Bumped when the open conversation changes so the input bar drops its local draft. */
    var inputResetKey by mutableIntStateOf(0)
        private set

    /** Decoded chat thumbnails shared with the screen; entries follow the open session. */
    val thumbnails = ChatThumbnailCache()

    private val chatSession = AiSessionState()
    private val contextBuilder = AiContextBuilder()
    private var currentTurn = mutableListOf<ChatMessage>()
    private var currentUserText = ""
    private var pendingWrite: PendingWrite? = null
    private var turnJob: Job? = null
    private var imageJob: Job? = null
    private var lastErrorId: Long? = null
    private var nextItemId = 1L
    private var trimNoticeShown = false
    private var readSummaryThisTurn: String? = null
    private var sessionTitle = AI_CHAT_SESSION_DEFAULT_TITLE
    private var sessionCreatedAt = System.currentTimeMillis()
    private var saveFailureNotified = false

    val activeProvider: AiProviderConfig? get() = config?.active()
    val needsSetup: Boolean get() = config?.active()?.hasKey == false
    val hasPendingProposal: Boolean get() = pendingProposalId != null

    init {
        viewModelScope.launch {
            repository.config.collect { value -> config = value }
        }
        viewModelScope.launch { restoreLatestSession() }
    }

    fun attachImage(uri: Uri) {
        if (isBusy || isRestoring || isImageLoading || pendingProposalId != null) return
        imageJob?.cancel()
        thumbnails.evict(pendingImage?.previewBase64)
        imageJob = viewModelScope.launch {
            isImageLoading = true
            try {
                pendingImage = imageProcessor.load(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                addNotice(app.getString(R.string.ai_image_load_failed))
            } finally {
                isImageLoading = false
                imageJob = null
            }
        }
    }

    fun removeImage() {
        if (isImageLoading) return
        thumbnails.evict(pendingImage?.previewBase64)
        pendingImage = null
    }

    fun showImageUnsupported() {
        addNotice(app.getString(R.string.ai_image_unsupported))
    }

    fun send(typed: String) {
        if (isBusy || isRestoring || isImageLoading || pendingProposalId != null) return
        val provider = activeProvider ?: return
        val text = typed.take(AiTokenBudget.MAX_USER_CHARS).trim()
        val image = pendingImage
        if (text.isEmpty() && image == null) return
        if (!provider.hasKey) {
            addError(app.getString(R.string.ai_error_missing_key), retryable = false)
            return
        }
        val content = text.ifEmpty { app.getString(R.string.ai_image_only_prompt) }
        pendingImage = null
        lastErrorId = null
        trimNoticeShown = false
        readSummaryThisTurn = null
        items += AiChatItem.User(nextItemId(), content, image?.previewBase64)
        currentUserText = content
        currentTurn = mutableListOf(
            ChatMessage(
                role = ChatRole.USER,
                content = content,
                images = image?.let {
                    listOf(ChatImage(base64 = it.requestBase64, mimeType = it.mimeType))
                }.orEmpty(),
            ),
        )
        viewModelScope.launch { persistSafely() }
        startTurn(provider)
    }

    fun stop() {
        if (turnJob == null) return
        turnJob?.cancel()
        turnJob = null
        val stopped = app.getString(R.string.ai_stopped)
        addNotice(stopped)
        // Do not create a synthetic assistant turn here: a stopped request has no reasoning
        // content to echo back, and a tools-bearing reasoning model would reject that history.
        currentTurn.clear()
        currentUserText = ""
        isBusy = false
        statusText = null
    }

    fun retry() {
        val provider = activeProvider ?: return
        if (isBusy || pendingWrite != null || currentTurn.isEmpty()) return
        lastErrorId?.let { id -> items.removeAll { it.id == id } }
        lastErrorId = null
        startTurn(provider)
    }

    fun confirmProposal() {
        val pending = pendingWrite ?: return
        if (isBusy) return
        isBusy = true
        statusText = app.getString(R.string.ai_status_creating)
        viewModelScope.launch {
            try {
                val result = executePending(pending)
                val summary = summarize(result)
                items += AiChatItem.Assistant(nextItemId(), summary)
                markProposal(pending.proposalId, ProposalState.APPLIED)
                chatSession.addAction(result.actionLine)
                chatSession.completeTurn(pending.userText, summary, pending.reasoningContent)
                currentTurn.clear()
                currentUserText = ""
                pendingWrite = null
                pendingProposalId = null
                persistSafely()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addError(
                    app.getString(R.string.ai_error_unknown) + detailSuffix(e.message),
                    retryable = false,
                )
            } finally {
                isBusy = false
                statusText = null
            }
        }
    }

    fun cancelProposal() {
        val pending = pendingWrite ?: return
        if (isBusy) return
        val summary = app.getString(R.string.ai_cancelled_summary)
        items += AiChatItem.Assistant(nextItemId(), summary)
        markProposal(pending.proposalId, ProposalState.CANCELLED)
        chatSession.addAction(
            "cancelled proposal: ${pending.alarms.size} alarms, " +
                "${pending.alarmStacks.size} stacks, ${pending.events.size} events",
        )
        chatSession.completeTurn(pending.userText, summary, pending.reasoningContent)
        currentTurn.clear()
        currentUserText = ""
        pendingWrite = null
        pendingProposalId = null
        viewModelScope.launch { persistSafely() }
    }

    fun updateAlarmDraft(index: Int, draft: AlarmDraft) {
        val pending = pendingWrite ?: return
        if (isBusy || index !in pending.alarms.indices) return
        val alarms = pending.alarms.toMutableList().apply {
            this[index] = AiDraftEditor.normalizeAlarm(draft)
        }
        pendingWrite = pending.copy(alarms = alarms)
        updateProposalItem(pending.proposalId) { it.copy(alarms = alarms) }
    }

    fun updateAlarmStackDraft(index: Int, draft: AlarmStackDraft) {
        val pending = pendingWrite ?: return
        if (isBusy || index !in pending.alarmStacks.indices) return
        val alarmStacks = pending.alarmStacks.toMutableList().apply {
            this[index] = AiDraftEditor.normalizeStack(draft)
        }
        pendingWrite = pending.copy(alarmStacks = alarmStacks)
        updateProposalItem(pending.proposalId) { it.copy(alarmStacks = alarmStacks) }
    }

    fun updateEventDraft(index: Int, draft: EventDraft) {
        val pending = pendingWrite ?: return
        if (isBusy || index !in pending.events.indices) return
        val events = pending.events.toMutableList().apply {
            this[index] = AiDraftEditor.normalizeEvent(draft)
        }
        pendingWrite = pending.copy(events = events)
        updateProposalItem(pending.proposalId) { it.copy(events = events) }
    }

    fun clearConversation() {
        if (isRestoring) return
        if (isBusy && pendingWrite != null) return
        startNewDraft()
        isRestoring = false
    }

    fun refreshSessions() {
        viewModelScope.launch {
            runCatching { sessionStore.touchAndClean(activeSessionId) }
        }
    }

    fun switchSession(id: Long) {
        if (isBusy || isRestoring || id == activeSessionId) return
        viewModelScope.launch {
            isRestoring = true
            try {
                val result = sessionStore.open(id)
                if (result is SessionOpenResult.Opened) {
                    applyRestoredSession(result.session)
                }
            } finally {
                isRestoring = false
            }
        }
    }

    fun deleteSession(id: Long) {
        if (isBusy || isRestoring) return
        viewModelScope.launch {
            isRestoring = true
            try {
                val wasActive = id == activeSessionId
                val restored = sessionStore.deleteAndReopen(id, wasActive)
                when {
                    restored != null -> applyRestoredSession(restored)
                    wasActive -> startNewDraft()
                }
            } finally {
                isRestoring = false
            }
        }
    }

    fun renameSession(id: Long, rawTitle: String) {
        val cleaned = rawTitle.trim().ifBlank {
            AI_CHAT_SESSION_DEFAULT_TITLE
        }.take(60)
        viewModelScope.launch {
            runCatching {
                sessionStore.rename(id, cleaned)
                if (id == activeSessionId) sessionTitle = cleaned
            }
        }
    }

    private fun startNewDraft() {
        turnJob?.cancel()
        turnJob = null
        imageJob?.cancel()
        imageJob = null
        inputResetKey++
        pendingImage = null
        thumbnails.clear()
        isImageLoading = false
        activeSessionId = null
        sessionTitle = AI_CHAT_SESSION_DEFAULT_TITLE
        sessionCreatedAt = System.currentTimeMillis()
        items.clear()
        chatSession.clear()
        currentTurn.clear()
        currentUserText = ""
        pendingWrite = null
        pendingProposalId = null
        isBusy = false
        statusText = null
        lastErrorId = null
        trimNoticeShown = false
        readSummaryThisTurn = null
        usage = AiUsage()
        requestCount = 0
        saveFailureNotified = false
    }

    private suspend fun restoreLatestSession() {
        try {
            val restored = sessionStore.openLatest()
            if (restored != null) {
                applyRestoredSession(restored)
            } else {
                startNewDraft()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            startNewDraft()
        } finally {
            isRestoring = false
        }
    }

    /**
     * Publishes an already decoded session. Runs as one Main-thread update: the payload was
     * decoded off Main by [AiChatSessionStore], so no suspension happens between the state writes.
     */
    private fun applyRestoredSession(restored: RestoredChatSession) {
        turnJob?.cancel()
        turnJob = null
        imageJob?.cancel()
        imageJob = null
        inputResetKey++
        pendingImage = null
        isImageLoading = false
        activeSessionId = restored.id
        sessionTitle = restored.title
        sessionCreatedAt = restored.createdAt
        chatSession.restore(restored.turns, restored.actions, restored.usage, restored.requestCount)
        usage = chatSession.usage
        requestCount = chatSession.requestCount
        val restoredItems = restored.messages.mapNotNull { message ->
            when (message.role) {
                "user" -> AiChatItem.User(nextItemId(), message.text, message.image)
                "assistant" -> AiChatItem.Assistant(nextItemId(), message.text)
                else -> null
            }
        }
        items.clear()
        items.addAll(restoredItems)
        thumbnails.retainOnly(restoredItems.mapNotNull { (it as? AiChatItem.User)?.image })
        if (restored.corrupt) {
            addNotice(app.getString(R.string.ai_session_corrupt))
        }
        if (restored.endsWithUserMessage) {
            addNotice(app.getString(R.string.ai_session_interrupted))
        }
        currentTurn.clear()
        currentUserText = ""
        pendingWrite = null
        pendingProposalId = null
        isBusy = false
        statusText = null
        lastErrorId = null
        trimNoticeShown = false
        readSummaryThisTurn = null
        saveFailureNotified = false
    }

    private suspend fun persistSafely() {
        try {
            persistSession()
            saveFailureNotified = false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            if (!saveFailureNotified) {
                addNotice(app.getString(R.string.ai_session_save_failed))
                saveFailureNotified = true
            }
        }
    }

    private suspend fun persistSession() {
        val sessionId = sessionStore.persist {
            // Snapshot only; conversion, preview and encoding happen inside the store off Main.
            ChatPersistenceSnapshot(
                items = items.toList(),
                turns = chatSession.completedTurns,
                actions = chatSession.actionLines,
                usage = chatSession.usage,
                requestCount = chatSession.requestCount,
                sessionId = activeSessionId,
                title = sessionTitle,
                createdAt = sessionCreatedAt,
            )
        }
        if (sessionId != null) activeSessionId = sessionId
    }

    private fun startTurn(provider: AiProviderConfig) {
        if (isBusy) return
        isBusy = true
        statusText = app.getString(R.string.ai_status_thinking)
        turnJob = viewModelScope.launch {
            try {
                // One provider configuration and one decrypted credential per turn; every tool
                // round below reuses them instead of re-reading the DataStore.
                runLoop(repository.resolveCredentials(provider))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiApiException) {
                handleApiError(e)
            } catch (e: AiContextOverflowException) {
                addError(app.getString(R.string.ai_error_too_long), retryable = false)
            } catch (e: Exception) {
                addError(
                    app.getString(R.string.ai_error_unknown) + detailSuffix(e.message),
                    retryable = true,
                )
            } finally {
                isBusy = false
                statusText = null
                turnJob = null
            }
        }
    }

    private suspend fun runLoop(credentials: AiTurnCredentials) {
        val provider = credentials.provider
        var rounds = 0
        while (rounds < AiTokenBudget.MAX_TOOL_ROUNDS) {
            rounds++
            val tags = tagDao.getAll()
            val actionLines = chatSession.actionLines
            val completedTurns = chatSession.completedTurns
            val turnMessages = currentTurn.toList()
            val modelContextLength = provider.models
                .firstOrNull { it.id == provider.modelId }
                ?.contextLength
            // Prompt assembly, history trimming and token estimation are pure CPU work over
            // immutable snapshots, so they stay off Main.
            val built = withContext(Dispatchers.Default) {
                val dynamic = AiPrompt.buildDynamicContext(
                    now = ZonedDateTime.now(),
                    tags = tags.map { it.name },
                    actions = actionLines,
                )
                contextBuilder.build(
                    completedTurns = completedTurns,
                    currentTurn = turnMessages,
                    dynamicContext = dynamic,
                    modelContextLength = modelContextLength,
                )
            }
            if (built.trimmed && !trimNoticeShown) {
                addNotice(app.getString(R.string.ai_history_trimmed))
                trimNoticeShown = true
            }

            statusText = app.getString(R.string.ai_status_thinking)
            // Do not ask a small-context model for 4096 output tokens; that combination can
            // exceed its context even when the input budget is respected.
            val maxOutputTokens = modelContextLength?.let { context ->
                (context - built.estimatedInputTokens - 256L)
                    .coerceIn(256L, 4_096L)
                    .toInt()
            } ?: 4_096
            val completion = repository.complete(
                credentials = credentials,
                messages = built.messages,
                tools = AiTools.definitions,
                maxTokens = maxOutputTokens,
            )
            recordUsage(completion.usage)

            val assistantText = completion.content?.trim().orEmpty()
            if (completion.toolCalls.isEmpty()) {
                val answer = assistantText.ifBlank { app.getString(R.string.ai_empty_reply) }
                items += AiChatItem.Assistant(nextItemId(), answer)
                finishTurn(answer, completion.reasoningContent)
                return
            }

            currentTurn += ChatMessage(
                role = ChatRole.ASSISTANT,
                content = completion.content,
                toolCalls = completion.toolCalls,
                reasoningContent = completion.reasoningContent?.let {
                    AiTokenBudget.truncateChars(it, AiTokenBudget.MAX_TURN_REASONING_CHARS)
                },
            )
            if (assistantText.isNotEmpty()) {
                items += AiChatItem.Assistant(nextItemId(), assistantText)
            }

            val readCalls = completion.toolCalls.filter { it.name in AiTools.readTools }
            val writeCalls = completion.toolCalls.filter { it.name in AiTools.writeTools }
            val unknownCalls = completion.toolCalls.filter {
                it.name !in AiTools.readTools && it.name !in AiTools.writeTools
            }

            // Tool payloads already carried by this turn count against the aggregate budget, so a
            // later read is trimmed instead of pushing the request over its input budget.
            var toolResultTokens = contextBuilder.estimateToolResultTokens(currentTurn)
            for (call in readCalls) {
                statusText = when (call.name) {
                    AiTools.LIST_ALARMS -> app.getString(R.string.ai_status_reading_alarms)
                    AiTools.LIST_EVENTS -> app.getString(R.string.ai_status_reading_events)
                    AiTools.LIST_RINGTONES ->
                        app.getString(R.string.ai_status_reading_ringtones)
                    else -> app.getString(R.string.ai_status_thinking)
                }
                val result = executeReadTool(
                    call = call,
                    tags = tags,
                    maxTokens = AiTokenBudget.toolResultAllowance(toolResultTokens),
                )
                toolResultTokens += AiTokenBudget.estimateTokens(result.body)
                currentTurn += ChatMessage(
                    role = ChatRole.TOOL,
                    content = result.body,
                    toolCallId = call.id,
                )
                result.summary?.let { summary -> readSummaryThisTurn = summary }
                result.notice?.let(::addNotice)
            }
            val unknownResults = withContext(Dispatchers.Default) {
                unknownCalls.map { call ->
                    ToolCallResult(
                        callId = call.id,
                        content = errorToolResult("unknown_tool", "Unknown tool ${call.name}"),
                    )
                }
            }
            unknownResults.forEach { result ->
                toolResultTokens += AiTokenBudget.estimateTokens(result.content)
                currentTurn += ChatMessage(
                    role = ChatRole.TOOL,
                    content = result.content,
                    toolCallId = result.callId,
                )
            }

            if (writeCalls.isNotEmpty()) {
                statusText = app.getString(R.string.ai_status_correcting)
                // Row loading, argument parsing, validation and rejection payloads are CPU and
                // JSON work; only the resulting state changes below touch Main.
                val outcome = withContext(Dispatchers.Default) {
                    prepareWriteCalls(writeCalls, tags)
                }
                when (outcome) {
                    is WriteOutcome.Rejected -> {
                        outcome.results.forEach { result ->
                            currentTurn += ChatMessage(
                                role = ChatRole.TOOL,
                                content = result.content,
                                toolCallId = result.callId,
                            )
                        }
                        addNotice(app.getString(R.string.ai_correcting_notice))
                        statusText = null
                        continue
                    }

                    is WriteOutcome.Ready -> {
                        val drafts = outcome.drafts
                        val proposal = AiChatItem.Proposal(
                            id = nextItemId(),
                            alarms = drafts.alarms,
                            alarmStacks = drafts.alarmStacks,
                            events = drafts.events,
                            alarmUpdates = drafts.alarmUpdates,
                            alarmDeletes = drafts.alarmDeletes,
                            eventUpdates = drafts.eventUpdates,
                            eventDeletes = drafts.eventDeletes,
                            state = ProposalState.PENDING,
                        )
                        items += proposal
                        pendingWrite = PendingWrite(
                            proposalId = proposal.id,
                            userText = currentUserText,
                            alarms = drafts.alarms,
                            alarmStacks = drafts.alarmStacks,
                            events = drafts.events,
                            alarmUpdates = drafts.alarmUpdates,
                            alarmDeletes = drafts.alarmDeletes,
                            eventUpdates = drafts.eventUpdates,
                            eventDeletes = drafts.eventDeletes,
                            reasoningContent = completion.reasoningContent?.let {
                                AiTokenBudget.truncateChars(it, AiTokenBudget.COMPLETED_REASONING_CHARS)
                            },
                        )
                        pendingProposalId = proposal.id
                        statusText = null
                        return
                    }
                }
            }
        }
        addError(app.getString(R.string.ai_error_tool_rounds), retryable = true)
    }

    /**
     * Loads one page of the requested rows and builds the tool payload off Main. Ordering, range
     * filtering and the row cap happen in SQL, tag references are fetched only for the returned
     * ids, and the payload is trimmed to the remaining token budget before the caller appends it.
     */
    private suspend fun executeReadTool(
        call: ChatToolCall,
        tags: List<Tag>,
        maxTokens: Int,
    ): ReadToolResult =
        withContext(Dispatchers.Default) {
            when (call.name) {
                AiTools.LIST_ALARMS -> readAlarms(tags, maxTokens)
                AiTools.LIST_EVENTS -> readEvents(call.arguments, tags, maxTokens)
                AiTools.LIST_RINGTONES -> readRingtones(maxTokens)
                else -> ReadToolResult(
                    body = errorToolResult("unknown_tool", "Unknown tool ${call.name}"),
                    notice = null,
                    summary = null,
                )
            }
        }

    private suspend fun readAlarms(tags: List<Tag>, maxTokens: Int): ReadToolResult {
        val total = alarmDao.count()
        val page = alarmDao.getOrderedPage(READ_RESULT_LIMIT)
        val tagsById = tags.associateBy { it.id }
        val tagNames = alarmTagRefsById(page.map { it.id }, tagsById)
            .mapValues { (_, refs) -> refs.map { ref -> ref.name } }
        val shown = AiToolPayload.fittingPrefix(page, maxTokens) { candidate ->
            AiTokenBudget.estimateTokens(alarmsPayload(total, candidate, tagNames))
        }
        val notice = if (total > shown.size) {
            app.getString(R.string.ai_read_alarms_truncated, shown.size, total)
        } else {
            app.getString(R.string.ai_read_alarms, shown.size)
        }
        return ReadToolResult(
            body = alarmsPayload(total, shown, tagNames),
            notice = notice,
            summary = "alarms ($total)",
        )
    }

    private fun alarmsPayload(
        total: Int,
        shown: List<Alarm>,
        tagNames: Map<Long, List<String>>,
    ): String = buildJsonObject {
        put("total", total)
        put("truncated", total > shown.size)
        putJsonArray("alarms") {
            shown.forEach { alarm ->
                add(
                    buildJsonObject {
                        put("id", alarm.id)
                        put("time", formatSeconds(alarm.time))
                        alarm.fireDate?.let { fireDate ->
                            put("date", LocalDate.ofEpochDay(fireDate).toString())
                        }
                        put("enabled", alarm.enabled)
                        put("oneTime", alarm.oneTime)
                        put("name", AiTokenBudget.truncateChars(alarm.name, 80))
                        if (alarm.note.isNotBlank()) {
                            put("note", AiTokenBudget.truncateChars(alarm.note, 120))
                        }
                        put("fromEvent", alarm.eventId != null)
                        alarm.eventId?.let { put("eventId", it) }
                        putJsonArray("tags") {
                            tagNames[alarm.id].orEmpty().forEach { add(it) }
                        }
                    },
                )
            }
        }
    }.toString()

    private fun readRingtones(maxTokens: Int): ReadToolResult {
        val all = AlarmSounds.available(app)
        val shown = AiToolPayload.fittingPrefix(all, maxTokens) { candidate ->
            AiTokenBudget.estimateTokens(ringtonesPayload(all.size, candidate))
        }
        val notice = if (all.size > shown.size) {
            app.getString(R.string.ai_read_ringtones_truncated, shown.size, all.size)
        } else {
            app.getString(R.string.ai_read_ringtones, shown.size)
        }
        return ReadToolResult(
            body = ringtonesPayload(all.size, shown),
            notice = notice,
            summary = "ringtones (${all.size})",
        )
    }

    private fun ringtonesPayload(
        total: Int,
        shown: List<Pair<String, String>>,
    ): String = buildJsonObject {
        put("total", total)
        put("truncated", total > shown.size)
        putJsonArray("ringtones") {
            shown.forEach { (title, uri) ->
                add(
                    buildJsonObject {
                        put("title", AiTokenBudget.truncateChars(title, 80))
                        put("uri", uri)
                    },
                )
            }
        }
    }.toString()

    private suspend fun readEvents(
        arguments: String,
        tags: List<Tag>,
        maxTokens: Int,
    ): ReadToolResult {
        val range = try {
            parseEventRange(arguments)
        } catch (e: Exception) {
            return ReadToolResult(
                body = errorToolResult("invalid_range", e.message ?: "invalid date range"),
                notice = null,
                summary = null,
            )
        }
        val from = range.first.toEpochDay()
        val to = range.second.toEpochDay()
        val total = eventDao.countBetween(from, to)
        val page = eventDao.getBetweenPage(from, to, READ_RESULT_LIMIT)
        val tagsById = tags.associateBy { it.id }
        val pageIds = page.map { it.id }
        val alarmEventIds = AiIdChunks.of(pageIds)
            .flatMap { alarmDao.getLinkedEventIdsFor(it) }
            .toSet()
        val tagNames = eventTagRefsById(pageIds, tagsById)
            .mapValues { (_, refs) -> refs.map { ref -> ref.name } }
        val shown = AiToolPayload.fittingPrefix(page, maxTokens) { candidate ->
            AiTokenBudget.estimateTokens(
                eventsPayload(total, candidate, alarmEventIds, tagNames),
            )
        }
        val notice = if (total > shown.size) {
            app.getString(R.string.ai_read_events_truncated, shown.size, total)
        } else {
            app.getString(R.string.ai_read_events, shown.size)
        }
        return ReadToolResult(
            body = eventsPayload(total, shown, alarmEventIds, tagNames),
            notice = notice,
            summary = "events ${range.first}..${range.second} ($total)",
        )
    }

    private fun eventsPayload(
        total: Int,
        shown: List<Event>,
        alarmEventIds: Set<Long>,
        tagNames: Map<Long, List<String>>,
    ): String = buildJsonObject {
        put("total", total)
        put("truncated", total > shown.size)
        putJsonArray("events") {
            shown.forEach { event ->
                add(
                    buildJsonObject {
                        put("id", event.id)
                        put("date", LocalDate.ofEpochDay(event.date).toString())
                        if (event.allDay) {
                            put("allDay", true)
                        } else {
                            put("start", formatSeconds(event.start))
                            if (event.hasEndTime) {
                                put("end", formatSeconds(event.endTimeOfDay))
                                if (event.endDayOffset > 0) {
                                    put(
                                        "endDate",
                                        LocalDate.ofEpochDay(event.endDateEpochDay).toString(),
                                    )
                                }
                            }
                        }
                        put("title", AiTokenBudget.truncateChars(event.title, 80))
                        if (event.note.isNotBlank()) {
                            put("note", AiTokenBudget.truncateChars(event.note, 120))
                        }
                        put("repeat", repeatLabel(event.repeatType))
                        if (event.repeatType == Event.REPEAT_WEEKLY) {
                            putJsonArray("repeatDays") {
                                event.repeatDays.split(',')
                                    .mapNotNull { it.trim().toIntOrNull() }
                                    .forEach { add(it) }
                            }
                        }
                        put("hasAlarm", event.id in alarmEventIds)
                        put("reminderMinutes", event.reminderMinutes)
                        putJsonArray("tags") {
                            tagNames[event.id].orEmpty().forEach { add(it) }
                        }
                    },
                )
            }
        }
    }.toString()

    private fun parseEventRange(arguments: String): Pair<LocalDate, LocalDate> {
        val root = runCatching {
            Json.parseToJsonElement(arguments).jsonObject
        }.getOrNull() ?: JsonObject(emptyMap())
        val from = root.dateArg("from") ?: LocalDate.now()
        val to = root.dateArg("to") ?: from.plusDays(7)
        if (to.isBefore(from)) throw IllegalArgumentException("to must not be before from")
        if (ChronoUnit.DAYS.between(from, to) > MAX_READ_RANGE_DAYS) {
            throw IllegalArgumentException("date range must be 366 days or fewer")
        }
        return from to to
    }

    private fun JsonObject.dateArg(key: String): LocalDate? {
        val raw = (this[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return try {
            LocalDate.parse(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("$key must be YYYY-MM-DD")
        }
    }

    private suspend fun finishTurn(assistantText: String, reasoningContent: String?) {
        chatSession.completeTurn(currentUserText, assistantText, reasoningContent)
        readSummaryThisTurn?.let { summary -> chatSession.addAction("last read: $summary") }
        currentTurn.clear()
        currentUserText = ""
        readSummaryThisTurn = null
        persistSafely()
    }

    private suspend fun executePending(pending: PendingWrite): ExecutionResult =
        withContext(Dispatchers.IO) {
            // Resolve every referenced row before the first write. A stale id must abort the whole
            // proposal instead of producing a partial result built by runCatching.
            val alarmUpdates = pending.alarmUpdates.map { draft ->
                val alarm = alarmDao.getById(draft.alarmId)
                    ?: error("alarm ${draft.alarmId} no longer exists")
                draft to alarm
            }
            val alarmDeletes = pending.alarmDeletes.map { draft ->
                val alarm = alarmDao.getById(draft.alarmId)
                    ?: error("alarm ${draft.alarmId} no longer exists")
                draft to alarm
            }
            val eventUpdates = pending.eventUpdates.map { draft ->
                val event = eventDao.getById(draft.eventId)
                    ?: error("event ${draft.eventId} no longer exists")
                draft to event
            }
            val eventDeletes = pending.eventDeletes.map { draft ->
                val event = eventDao.getById(draft.eventId)
                    ?: error("event ${draft.eventId} no longer exists")
                draft to event
            }

            val result = try {
                // One outer transaction makes every row change all-or-nothing. ScheduleWriter's
                // nested withTransaction calls join this transaction; alarm scheduling side
                // effects are restored below if the outer transaction fails.
                app.database.withTransaction {
                    var createdPlainAlarms = 0
                    var createdEventAlarms = 0
                    var createdEvents = 0
                    var updatedAlarms = 0
                    var updatedEvents = 0
                    var deletedAlarms = 0
                    var deletedEvents = 0
                    var scheduledAlarms = 0
                    var unscheduledAlarms = 0
                    val notes = mutableListOf<String>()

                    pending.alarms.forEach { draft ->
                        val creation = writer.addAlarm(
                            time = draft.time.toSecondOfDay(),
                            fireDate = draft.date?.toEpochDay(),
                            name = draft.name,
                            note = draft.note,
                            enabled = draft.enabled,
                            oneTime = draft.oneTime,
                            tags = draft.tags.map { Tag(id = it.id, name = it.name) },
                            ringtone = draft.ringtone,
                        )
                        createdPlainAlarms++
                        if (creation.alarm.enabled) {
                            if (creation.scheduled) scheduledAlarms++ else unscheduledAlarms++
                        }
                        if (notes.size < 5) {
                            notes += if (draft.date != null) {
                                "created ${draft.date} ${draft.time} ${draft.name}".trim()
                            } else {
                                "created ${draft.time} next ${draft.name}".trim()
                            }
                        }
                    }

                    pending.alarmStacks.forEach { draft ->
                        val creations = writer.addAlarmStack(
                            times = draft.times.map { it.toSecondOfDay() },
                            name = draft.name,
                            note = draft.note,
                            enabled = draft.enabled,
                            oneTime = draft.oneTime,
                            tags = draft.tags.map { Tag(id = it.id, name = it.name) },
                            ringtone = draft.ringtone,
                        )
                        createdPlainAlarms += creations.size
                        creations.forEach { creation ->
                            if (creation.alarm.enabled) {
                                if (creation.scheduled) {
                                    scheduledAlarms++
                                } else {
                                    unscheduledAlarms++
                                }
                            }
                        }
                        if (notes.size < 5) {
                            notes += "created alarm stack of ${creations.size} ${draft.name}".trim()
                        }
                    }

                    pending.events.forEach { draft ->
                        val creation = writer.addEvent(
                            title = draft.title,
                            note = draft.note,
                            date = draft.date,
                            start = draft.start,
                            end = draft.end,
                            withAlarm = draft.withAlarm,
                            tags = draft.tags.map { Tag(id = it.id, name = it.name) },
                            repeat = draft.recurrenceRule(),
                            reminderMinutes = draft.reminderMinutes,
                            endDayOffset = draft.endDayOffset,
                            allDay = draft.allDay,
                        )
                        createdEvents += creation.events.size
                        createdEventAlarms += creation.alarms.size
                        scheduledAlarms += creation.alarms.count { it.scheduled }
                        unscheduledAlarms += creation.alarms.count { !it.scheduled }
                    }

                    alarmUpdates.forEach { (draft, alarm) ->
                        val creation = writer.updateAlarm(
                            alarm = alarm,
                            time = draft.time.toSecondOfDay(),
                            fireDate = draft.fireDate?.toEpochDay(),
                            name = draft.name,
                            note = draft.note,
                            enabled = draft.enabled,
                            oneTime = draft.oneTime,
                            tags = draft.tags.map { Tag(id = it.id, name = it.name) },
                            ringtone = draft.ringtone,
                        )
                        updatedAlarms++
                        if (creation.alarm.enabled) {
                            if (creation.scheduled) scheduledAlarms++ else unscheduledAlarms++
                        }
                        if (notes.size < 5) {
                            notes += "updated alarm ${draft.time} ${draft.name}".trim()
                        }
                    }

                    alarmDeletes.forEach { (draft, alarm) ->
                        writer.deleteAlarm(alarm)
                        deletedAlarms++
                        if (notes.size < 5) {
                            notes += "deleted alarm ${draft.time} ${draft.name}".trim()
                        }
                    }

                    eventUpdates.forEach { (draft, event) ->
                        val mutation = writer.updateEventScoped(
                            event = event,
                            scope = draft.scope,
                            title = draft.title,
                            note = draft.note,
                            date = draft.date,
                            start = draft.start,
                            end = draft.end,
                            withAlarm = draft.withAlarm,
                            tags = draft.tags.map { Tag(id = it.id, name = it.name) },
                            reminderMinutes = draft.reminderMinutes,
                            endDayOffset = draft.endDayOffset,
                            allDay = draft.allDay,
                        )
                        updatedEvents += mutation.affectedEvents
                        deletedAlarms += mutation.deletedAlarms
                        scheduledAlarms += mutation.scheduledAlarms
                        unscheduledAlarms += mutation.unscheduledAlarms
                        if (notes.size < 5) notes += "updated event ${draft.title}".trim()
                    }

                    eventDeletes.forEach { (draft, event) ->
                        val mutation = writer.deleteEventScoped(
                            event = event,
                            scope = draft.scope,
                            deleteAlarms = !draft.keepAlarms,
                        )
                        deletedEvents += mutation.affectedEvents
                        deletedAlarms += mutation.deletedAlarms
                        if (notes.size < 5) notes += "deleted event ${draft.title}".trim()
                    }

                    val totalCreatedAlarms = createdPlainAlarms + createdEventAlarms
                    val actionLine = buildString {
                        append("created ")
                        append(totalCreatedAlarms)
                        append(" alarms, ")
                        append(createdEvents)
                        append(" events; updated ")
                        append(updatedAlarms)
                        append(" alarms, ")
                        append(updatedEvents)
                        append(" events; deleted ")
                        append(deletedAlarms)
                        append(" alarms, ")
                        append(deletedEvents)
                        append(" events")
                        if (notes.isNotEmpty()) {
                            append(": ")
                            append(notes.joinToString("; "))
                        }
                    }
                    ExecutionResult(
                        createdAlarms = totalCreatedAlarms,
                        createdEvents = createdEvents,
                        updatedAlarms = updatedAlarms,
                        updatedEvents = updatedEvents,
                        deletedAlarms = deletedAlarms,
                        deletedEvents = deletedEvents,
                        unscheduledAlarms = unscheduledAlarms,
                        failed = 0,
                        actionLine = actionLine,
                    )
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { restoreAlarmsAfterFailedProposal() }
                throw e
            } catch (e: Exception) {
                restoreAlarmsAfterFailedProposal()
                throw e
            }
            result
        }

    /**
     * A failed proposal rolls the database back, but AlarmManager calls made inside the
     * transaction are not transactional. Cancel and rebuild every system alarm from the restored
     * rows so a stale PendingIntent cannot ring with pre-rollback data.
     */
    private suspend fun restoreAlarmsAfterFailedProposal() {
        runCatching {
            val alarms = alarmDao.getAll()
            alarms.forEach { alarm -> AlarmScheduler.cancelPending(app, alarm) }
            AlarmScheduler.rescheduleAllEnabled(app)
            ReminderScheduler.rescheduleAllUpcoming(app)
        }
    }

    private fun summarize(result: ExecutionResult): String {
        val parts = mutableListOf<String>()
        if (result.createdAlarms > 0) {
            parts += app.getString(R.string.ai_summary_created_alarms, result.createdAlarms)
        }
        if (result.createdEvents > 0) {
            parts += app.getString(R.string.ai_summary_created_events, result.createdEvents)
        }
        if (result.updatedAlarms > 0) {
            parts += app.getString(R.string.ai_summary_updated_alarms, result.updatedAlarms)
        }
        if (result.updatedEvents > 0) {
            parts += app.getString(R.string.ai_summary_updated_events, result.updatedEvents)
        }
        if (result.deletedAlarms > 0) {
            parts += app.getString(R.string.ai_summary_deleted_alarms, result.deletedAlarms)
        }
        if (result.deletedEvents > 0) {
            parts += app.getString(R.string.ai_summary_deleted_events, result.deletedEvents)
        }
        if (result.failed > 0) {
            parts += app.getString(R.string.ai_summary_failed, result.failed)
        }
        if (result.unscheduledAlarms > 0) {
            parts += app.getString(R.string.ai_summary_unscheduled, result.unscheduledAlarms)
        }
        if (parts.isEmpty()) return app.getString(R.string.ai_created_nothing)
        return app.getString(R.string.ai_summary_done, parts.joinToString("；"))
    }

    private fun handleApiError(e: AiApiException) {
        val message = when (e.kind) {
            AiErrorKind.MISSING_KEY -> app.getString(R.string.ai_error_missing_key)
            AiErrorKind.UNAUTHORIZED -> app.getString(R.string.ai_error_unauthorized)
            AiErrorKind.INVALID_REQUEST -> app.getString(R.string.ai_error_invalid_request)
            AiErrorKind.INSUFFICIENT_CREDIT -> app.getString(R.string.ai_error_credit)
            AiErrorKind.MODEL_NOT_FOUND -> app.getString(R.string.ai_error_model_not_found)
            AiErrorKind.RATE_LIMITED -> app.getString(R.string.ai_error_rate_limited)
            AiErrorKind.SERVER_ERROR -> app.getString(R.string.ai_error_server)
            AiErrorKind.NETWORK -> app.getString(R.string.ai_error_network)
            AiErrorKind.INVALID_RESPONSE -> app.getString(R.string.ai_error_invalid_response)
            AiErrorKind.TRUNCATED -> app.getString(R.string.ai_error_truncated)
            AiErrorKind.UNKNOWN -> app.getString(R.string.ai_error_unknown)
        }
        val retryable = e.kind == AiErrorKind.NETWORK ||
            e.kind == AiErrorKind.RATE_LIMITED ||
            e.kind == AiErrorKind.SERVER_ERROR
        addError(message + detailSuffix(e.detail), retryable)
    }

    private fun detailSuffix(detail: String?): String {
        val cleaned = detail?.trim()?.take(240)
        return if (cleaned.isNullOrEmpty()) {
            ""
        } else {
            "\n" + app.getString(R.string.ai_error_detail, cleaned)
        }
    }

    private fun markProposal(id: Long, state: ProposalState) {
        updateProposalItem(id) { it.copy(state = state) }
    }

    private fun updateProposalItem(
        id: Long,
        transform: (AiChatItem.Proposal) -> AiChatItem.Proposal,
    ) {
        val index = items.indexOfFirst { it is AiChatItem.Proposal && it.id == id }
        if (index < 0) return
        items[index] = transform(items[index] as AiChatItem.Proposal)
    }

    /**
     * Parses the write tool calls and validates the whole batch. Runs on [Dispatchers.Default],
     * so it only reads database rows and returns immutable drafts; the caller creates the
     * proposal item and its Compose state on Main.
     */
    private suspend fun prepareWriteCalls(
        calls: List<ChatToolCall>,
        tags: List<Tag>,
    ): WriteOutcome {
        val tagsById = tags.associateBy { it.id }
        val rows = loadWriteRows(AiToolReferences.collect(calls), tagsById)
        val parser = AiToolParser(
            now = LocalDateTime.now(),
            availableTags = tags.associate { it.name to it.id },
            alarmsById = rows.alarmsById,
            eventsById = rows.eventsById,
            alarmTagsById = rows.alarmTagsById,
            eventTagsById = rows.eventTagsById,
            availableRingtones = AlarmSounds.available(app).map { it.second }.toSet(),
        )
        val alarms = mutableListOf<AlarmDraft>()
        val alarmStacks = mutableListOf<AlarmStackDraft>()
        val events = mutableListOf<EventDraft>()
        val alarmUpdates = mutableListOf<AlarmUpdateDraft>()
        val alarmDeletes = mutableListOf<AlarmDeleteDraft>()
        val eventUpdates = mutableListOf<EventUpdateDraft>()
        val eventDeletes = mutableListOf<EventDeleteDraft>()
        val parseErrors = mutableMapOf<String, AiToolParseException>()

        for (call in calls) {
            try {
                when (call.name) {
                    AiTools.CREATE_ALARMS -> alarms += parser.parseAlarms(call.arguments)
                    AiTools.CREATE_ALARM_STACKS ->
                        alarmStacks += parser.parseAlarmStacks(call.arguments)

                    AiTools.CREATE_EVENTS -> events += parser.parseEvents(call.arguments)
                    AiTools.UPDATE_ALARMS ->
                        alarmUpdates += parser.parseAlarmUpdates(call.arguments)

                    AiTools.DELETE_ALARMS ->
                        alarmDeletes += parser.parseAlarmDeletes(call.arguments)

                    AiTools.UPDATE_EVENTS ->
                        eventUpdates += parser.parseEventUpdates(call.arguments)

                    AiTools.DELETE_EVENTS ->
                        eventDeletes += parser.parseEventDeletes(call.arguments)
                }
            } catch (e: AiToolParseException) {
                parseErrors[call.id] = e
            }
        }

        if (parseErrors.isNotEmpty()) {
            return WriteOutcome.Rejected(
                calls.map { call ->
                    val error = parseErrors[call.id]
                    ToolCallResult(
                        callId = call.id,
                        content = errorToolResult(
                            code = error?.code ?: "batch_rejected",
                            message = error?.let { "${it.code}: ${it.message}" }
                                ?: "batch_rejected: another item in this batch was invalid",
                        ),
                    )
                },
            )
        }

        val totalActions = alarms.size + events.size +
            alarmStacks.sumOf { it.times.size } +
            alarmUpdates.size + alarmDeletes.size +
            eventUpdates.size + eventDeletes.size
        if (totalActions == 0) {
            return rejectWriteCalls(calls, "empty", "there are no changes to apply")
        }
        if (totalActions > AiTokenBudget.MAX_BATCH_ITEMS) {
            return rejectWriteCalls(
                calls,
                "too_many_items",
                "at most ${AiTokenBudget.MAX_BATCH_ITEMS} actions are allowed per proposal",
            )
        }

        val affectedOccurrences = events.sumOf { it.occurrenceCount } +
            eventUpdates.sumOf { it.affectedCount } +
            eventDeletes.sumOf { it.affectedCount }
        if (affectedOccurrences > AiTokenBudget.MAX_TOTAL_AFFECTED) {
            return rejectWriteCalls(
                calls,
                "too_many_occurrences",
                "this proposal would affect $affectedOccurrences event occurrences; " +
                    "keep the total under ${AiTokenBudget.MAX_TOTAL_AFFECTED}",
            )
        }

        val updateAlarmIds = alarmUpdates.map { it.alarmId }
        val deleteAlarmIds = alarmDeletes.map { it.alarmId }
        val updateEventIds = eventUpdates.map { it.eventId }
        val deleteEventIds = eventDeletes.map { it.eventId }
        val conflict = updateAlarmIds.any { it in deleteAlarmIds } ||
            updateEventIds.any { it in deleteEventIds } ||
            updateAlarmIds.size != updateAlarmIds.distinct().size ||
            deleteAlarmIds.size != deleteAlarmIds.distinct().size ||
            updateEventIds.size != updateEventIds.distinct().size ||
            deleteEventIds.size != deleteEventIds.distinct().size
        if (conflict) {
            return rejectWriteCalls(
                calls,
                "conflicting_actions",
                "the same alarm or event cannot be changed and deleted, or changed twice, " +
                    "in one proposal",
            )
        }

        return WriteOutcome.Ready(
            PreparedDrafts(
                alarms = alarms,
                alarmStacks = alarmStacks,
                events = events,
                alarmUpdates = alarmUpdates,
                alarmDeletes = alarmDeletes,
                eventUpdates = eventUpdates,
                eventDeletes = eventDeletes,
            ),
        )
    }

    private fun rejectWriteCalls(
        calls: List<ChatToolCall>,
        code: String,
        message: String,
    ): WriteOutcome.Rejected = WriteOutcome.Rejected(
        calls.map { call ->
            ToolCallResult(callId = call.id, content = errorToolResult(code, message))
        },
    )

    /**
     * Loads only the rows a write batch can reference: the ids parsed out of the calls, the whole
     * series of each referenced event (scope expansion needs the siblings) and the alarms linked
     * to those events. Every `IN (...)` lookup is chunked because SQLite bounds statement
     * parameters.
     */
    private suspend fun loadWriteRows(
        references: ReferencedIds,
        tagsById: Map<Long, Tag>,
    ): WriteRows {
        val referencedEvents = AiIdChunks.of(references.eventIds)
            .flatMap { eventDao.getByIds(it) }
        val seriesIds = referencedEvents.map { it.seriesId }.filter { it != 0L }
        val seriesEvents = AiIdChunks.of(seriesIds)
            .flatMap { eventDao.getBySeriesIds(it) }
        val eventsById = (seriesEvents + referencedEvents).associateBy { it.id }
        val eventIds = eventsById.keys.toList()
        val alarmsById = (
            AiIdChunks.of(references.alarmIds).flatMap { alarmDao.getByIds(it) } +
                AiIdChunks.of(eventIds).flatMap { alarmDao.getByEventIds(it) }
            ).associateBy { it.id }
        return WriteRows(
            alarmsById = alarmsById,
            eventsById = eventsById,
            alarmTagsById = alarmTagRefsById(alarmsById.keys.toList(), tagsById),
            eventTagsById = eventTagRefsById(eventIds, tagsById),
        )
    }

    private suspend fun alarmTagRefsById(
        ids: List<Long>,
        tagsById: Map<Long, Tag>,
    ): Map<Long, List<TagRef>> = AiIdChunks.of(ids)
        .flatMap { tagDao.getAlarmTagRefsFor(it) }
        .groupBy { it.alarmId }
        .mapValues { (_, refs) -> refs.toAlarmTagRefs(tagsById) }

    private suspend fun eventTagRefsById(
        ids: List<Long>,
        tagsById: Map<Long, Tag>,
    ): Map<Long, List<TagRef>> = AiIdChunks.of(ids)
        .flatMap { tagDao.getEventTagRefsFor(it) }
        .groupBy { it.eventId }
        .mapValues { (_, refs) -> refs.toEventTagRefs(tagsById) }

    private fun List<AlarmTagCrossRef>.toAlarmTagRefs(
        tagsById: Map<Long, Tag>,
    ): List<TagRef> = mapNotNull { tagsById[it.tagId] }.map { TagRef(id = it.id, name = it.name) }

    private fun List<EventTagCrossRef>.toEventTagRefs(
        tagsById: Map<Long, Tag>,
    ): List<TagRef> = mapNotNull { tagsById[it.tagId] }.map { TagRef(id = it.id, name = it.name) }

    private fun addNotice(text: String) {
        items += AiChatItem.Notice(nextItemId(), text)
    }

    private fun addError(text: String, retryable: Boolean) {
        val id = nextItemId()
        items += AiChatItem.Error(id, text, retryable)
        lastErrorId = id
    }

    private fun recordUsage(value: AiUsage) {
        chatSession.recordUsage(value)
        usage = chatSession.usage
        requestCount = chatSession.requestCount
    }

    private fun errorToolResult(code: String, message: String): String = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", AiTokenBudget.truncateChars(message, 300))
    }.toString()

    private fun formatSeconds(seconds: Int): String =
        LocalTime.ofSecondOfDay(seconds.toLong()).format(TIME_FORMATTER)

    private fun repeatLabel(repeatType: Int): String = when (repeatType) {
        com.dinoe.clockwise.data.Event.REPEAT_DAILY -> "daily"
        com.dinoe.clockwise.data.Event.REPEAT_WEEKLY -> "weekly"
        else -> "none"
    }

    private fun nextItemId(): Long = nextItemId++

    override fun onCleared() {
        thumbnails.clear()
    }

    private data class PendingWrite(
        val proposalId: Long,
        val userText: String,
        val alarms: List<AlarmDraft>,
        val alarmStacks: List<AlarmStackDraft>,
        val events: List<EventDraft>,
        val alarmUpdates: List<AlarmUpdateDraft>,
        val alarmDeletes: List<AlarmDeleteDraft>,
        val eventUpdates: List<EventUpdateDraft>,
        val eventDeletes: List<EventDeleteDraft>,
        val reasoningContent: String?,
    )

    private data class ReadToolResult(
        val body: String,
        val notice: String?,
        val summary: String?,
    )

    /** One tool result payload, built off Main and appended to the turn on Main. */
    private data class ToolCallResult(
        val callId: String,
        val content: String,
    )

    private sealed interface WriteOutcome {
        data class Rejected(val results: List<ToolCallResult>) : WriteOutcome

        data class Ready(val drafts: PreparedDrafts) : WriteOutcome
    }

    private data class PreparedDrafts(
        val alarms: List<AlarmDraft>,
        val alarmStacks: List<AlarmStackDraft>,
        val events: List<EventDraft>,
        val alarmUpdates: List<AlarmUpdateDraft>,
        val alarmDeletes: List<AlarmDeleteDraft>,
        val eventUpdates: List<EventUpdateDraft>,
        val eventDeletes: List<EventDeleteDraft>,
    )

    /** The subset of rows a write batch can reference, loaded before parsing. */
    private data class WriteRows(
        val alarmsById: Map<Long, Alarm>,
        val eventsById: Map<Long, Event>,
        val alarmTagsById: Map<Long, List<TagRef>>,
        val eventTagsById: Map<Long, List<TagRef>>,
    )

    private data class ExecutionResult(
        val createdAlarms: Int,
        val createdEvents: Int,
        val updatedAlarms: Int,
        val updatedEvents: Int,
        val deletedAlarms: Int,
        val deletedEvents: Int,
        val unscheduledAlarms: Int,
        val failed: Int,
        val actionLine: String,
    )

    private companion object {
        const val READ_RESULT_LIMIT = 30
        const val MAX_READ_RANGE_DAYS = 366L
        val TIME_FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm")
    }
}
