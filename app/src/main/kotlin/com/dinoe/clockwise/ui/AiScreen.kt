package com.dinoe.clockwise.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ai.AiChatItem
import com.dinoe.clockwise.ai.AiTokenBudget
import com.dinoe.clockwise.ai.AlarmDeleteDraft
import com.dinoe.clockwise.ai.AlarmDraft
import com.dinoe.clockwise.ai.AlarmStackDraft
import com.dinoe.clockwise.ai.AlarmUpdateDraft
import com.dinoe.clockwise.ai.EventDeleteDraft
import com.dinoe.clockwise.ai.EventDraft
import com.dinoe.clockwise.ai.EventUpdateDraft
import com.dinoe.clockwise.ai.ProposalState
import com.dinoe.clockwise.data.AI_CHAT_SESSION_DEFAULT_TITLE
import com.dinoe.clockwise.data.AiChatSessionSummary
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.EventScope
import com.dinoe.clockwise.data.Tag
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(
    onOpenSettings: () -> Unit,
    viewModel: AiChatViewModel = viewModel(),
) {
    val listState = rememberLazyListState()
    var showUsage by remember { mutableStateOf(false) }
    var showNewChat by remember { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val timePickerStyle by viewModel.timePickerStyle.collectAsStateWithLifecycle()
    val active = viewModel.config?.active()
    val subtitle = if (active?.hasKey == true) {
        stringResource(
            R.string.ai_subtitle_format,
            active.preset.displayName,
            active.modelId,
        )
    } else {
        stringResource(R.string.ai_subtitle_unconfigured)
    }
    val messages = viewModel.items
    val pendingPreview = rememberChatThumbnail(
        cache = viewModel.thumbnails,
        base64 = viewModel.pendingImage?.previewBase64,
    )
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(viewModel::attachImage) }

    LaunchedEffect(messages.size, viewModel.isBusy) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    LaunchedEffect(showSessions) {
        if (showSessions) viewModel.refreshSessions()
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.ai_title),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showSessions = true }) {
                        Icon(
                            Icons.AutoMirrored.Filled.List,
                            contentDescription = stringResource(R.string.ai_sessions_title),
                        )
                    }
                    IconButton(onClick = { showUsage = true }) {
                        Icon(
                            Icons.Filled.Info,
                            contentDescription = stringResource(R.string.ai_usage_title),
                        )
                    }
                    IconButton(
                        onClick = {
                            if (messages.isEmpty()) {
                                viewModel.clearConversation()
                            } else {
                                showNewChat = true
                            }
                        },
                        enabled = !viewModel.isRestoring && !viewModel.hasPendingProposal,
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = stringResource(R.string.ai_new_chat),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.ai_chat_settings),
                        )
                    }
                },
            )
        },
        bottomBar = {
            AiInputBar(
                resetKey = viewModel.inputResetKey,
                isBusy = viewModel.isBusy,
                pendingProposal = viewModel.hasPendingProposal,
                enabled = active?.hasKey == true &&
                    !viewModel.hasPendingProposal &&
                    !viewModel.isBusy &&
                    !viewModel.isRestoring,
                hasPendingImage = viewModel.pendingImage != null,
                imagePreview = pendingPreview.bitmapOrNull,
                imageLoading = viewModel.isImageLoading || pendingPreview.isLoading,
                onAttach = {
                    if (active?.modelSupportsImages == false) {
                        viewModel.showImageUnsupported()
                    } else {
                        imagePicker.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly,
                            ),
                        )
                    }
                },
                onRemoveImage = viewModel::removeImage,
                onSend = viewModel::send,
                onStop = viewModel::stop,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            if (viewModel.needsSetup && messages.isNotEmpty()) {
                SetupCard(
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (viewModel.config == null || viewModel.isRestoring) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (messages.isEmpty() && !viewModel.isBusy) {
                EmptyState(
                    needsSetup = viewModel.needsSetup,
                    onOpenSettings = onOpenSettings,
                    onExample = { text -> viewModel.send(text) },
                    modifier = Modifier.weight(1f),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 12.dp,
                        bottom = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { item ->
                        when (item) {
                            is AiChatItem.User -> UserMessage(
                                text = item.text,
                                image = item.image,
                                thumbnails = viewModel.thumbnails,
                            )
                            is AiChatItem.Assistant -> AssistantMessage(item.text)
                            is AiChatItem.Notice -> NoticeMessage(item.text)
                            is AiChatItem.Error -> ErrorMessage(
                                text = item.text,
                                retryable = item.retryable,
                                onRetry = viewModel::retry,
                            )

                            is AiChatItem.Proposal -> ProposalCard(
                                item = item,
                                busy = viewModel.isBusy && item.state == ProposalState.PENDING,
                                allTags = allTags,
                                timePickerStyle = timePickerStyle,
                                onConfirm = viewModel::confirmProposal,
                                onCancel = viewModel::cancelProposal,
                                onUpdateAlarm = viewModel::updateAlarmDraft,
                                onUpdateAlarmStack = viewModel::updateAlarmStackDraft,
                                onUpdateEvent = viewModel::updateEventDraft,
                            )
                        }
                    }
                    if (viewModel.isBusy && !viewModel.hasPendingProposal) {
                        item(key = -1L) {
                            BusyRow(status = viewModel.statusText)
                        }
                    }
                }
            }
        }
    }

    if (showSessions) {
        SessionsDialog(
            sessions = sessions,
            activeSessionId = viewModel.activeSessionId,
            busy = viewModel.isBusy || viewModel.isRestoring || viewModel.hasPendingProposal,
            onSwitch = viewModel::switchSession,
            onRename = viewModel::renameSession,
            onDelete = viewModel::deleteSession,
            onNewChat = viewModel::clearConversation,
            onDismiss = { showSessions = false },
        )
    }

    if (showUsage) {
        UsageDialog(
            usage = viewModel.usage,
            requestCount = viewModel.requestCount,
            onDismiss = { showUsage = false },
        )
    }

    if (showNewChat) {
        AlertDialog(
            onDismissRequest = { showNewChat = false },
            title = { Text(stringResource(R.string.ai_new_chat_confirm_title)) },
            text = { Text(stringResource(R.string.ai_new_chat_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearConversation()
                        showNewChat = false
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewChat = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SetupCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_setup_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.ai_setup_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.ai_setup_action))
            }
        }
    }
}

@Composable
private fun EmptyState(
    needsSetup: Boolean,
    onOpenSettings: () -> Unit,
    onExample: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(20.dp))
        Icon(
            painter = painterResource(R.drawable.ic_ai),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.ai_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.ai_image_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        if (needsSetup) {
            Spacer(modifier = Modifier.height(16.dp))
            SetupCard(onOpenSettings = onOpenSettings)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.ai_disclaimer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                stringResource(R.string.ai_example_1),
                stringResource(R.string.ai_example_2),
                stringResource(R.string.ai_example_3),
                stringResource(R.string.ai_example_4),
                stringResource(R.string.ai_example_5),
            ).forEach { example ->
                AssistChip(
                    onClick = { onExample(example) },
                    enabled = !needsSetup,
                    label = {
                        Text(
                            text = example,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun UserMessage(text: String, image: String?, thumbnails: ChatThumbnailCache) {
    val thumbnail = rememberChatThumbnail(cache = thumbnails, base64 = image)
    val bitmap = thumbnail.bitmapOrNull
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
        ) {
            Column {
                if (bitmap != null) {
                    val ratio = bitmap.width.toFloat() / bitmap.height
                    val imageWidth = if (ratio >= 1f) 280.dp else (280f * ratio).dp
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.ai_image_attached),
                        modifier = Modifier
                            .padding(start = 6.dp, top = 6.dp, end = 6.dp)
                            .width(imageWidth)
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit,
                    )
                } else if (thumbnail.isLoading) {
                    ThumbnailPlaceholder()
                }
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThumbnailPlaceholder() {
    Box(
        modifier = Modifier
            .padding(start = 6.dp, top = 6.dp, end = 6.dp)
            .width(168.dp)
            .height(120.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun AssistantMessage(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 360.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
        ) {
            SelectionContainer {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun NoticeMessage(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun ErrorMessage(
    text: String,
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (retryable) {
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.ai_retry))
                }
            }
        }
    }
}

@Composable
private fun BusyRow(status: String?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = status ?: stringResource(R.string.ai_status_thinking),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProposalCard(
    item: AiChatItem.Proposal,
    busy: Boolean,
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onUpdateAlarm: (Int, AlarmDraft) -> Unit,
    onUpdateAlarmStack: (Int, AlarmStackDraft) -> Unit,
    onUpdateEvent: (Int, EventDraft) -> Unit,
) {
    var editAlarmIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    var editAlarmStackIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    var editEventIndex by remember(item.id) { mutableStateOf<Int?>(null) }
    val editable = item.state == ProposalState.PENDING && !busy
    val createCount = item.alarms.size + item.alarmStacks.size + item.events.size
    val updateCount = item.alarmUpdates.size + item.eventUpdates.size
    val deleteCount = item.alarmDeletes.size + item.eventDeletes.size
    val summary = listOfNotNull(
        if (createCount > 0) {
            stringResource(R.string.ai_proposal_create_summary, createCount)
        } else {
            null
        },
        if (updateCount > 0) {
            stringResource(R.string.ai_proposal_update_summary, updateCount)
        } else {
            null
        },
        if (deleteCount > 0) {
            stringResource(R.string.ai_proposal_delete_summary, deleteCount)
        } else {
            null
        },
    ).joinToString(" · ")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.ai_proposal_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                when (item.state) {
                    ProposalState.APPLIED -> ProposalStateLabel(
                        text = stringResource(R.string.ai_proposal_applied),
                        color = MaterialTheme.colorScheme.primary,
                    )

                    ProposalState.CANCELLED -> ProposalStateLabel(
                        text = stringResource(R.string.ai_proposal_cancelled),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    ProposalState.PENDING -> Unit
                }
            }
            Text(
                text = summary.ifBlank { stringResource(R.string.ai_proposal_empty) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (item.alarms.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.ai_proposal_alarms, item.alarms.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.alarms.forEachIndexed { index, alarm ->
                    AlarmDraftRow(
                        alarm = alarm,
                        onEdit = if (editable) ({ editAlarmIndex = index }) else null,
                    )
                }
            }

            if (item.alarmStacks.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(
                        R.string.ai_proposal_alarm_stacks,
                        item.alarmStacks.size,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.alarmStacks.forEachIndexed { index, stack ->
                    AlarmStackDraftRow(
                        stack = stack,
                        onEdit = if (editable) ({ editAlarmStackIndex = index }) else null,
                    )
                }
            }

            if (item.events.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.ai_proposal_events, item.events.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.events.forEachIndexed { index, event ->
                    EventDraftRow(
                        event = event,
                        onEdit = if (editable) ({ editEventIndex = index }) else null,
                    )
                }
            }

            if (item.alarmUpdates.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.ai_proposal_update_alarms, item.alarmUpdates.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.alarmUpdates.forEach { update -> AlarmUpdateRow(update) }
            }

            if (item.eventUpdates.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.ai_proposal_update_events, item.eventUpdates.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                item.eventUpdates.forEach { update -> EventUpdateRow(update) }
            }

            if (item.alarmDeletes.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.ai_proposal_delete_alarms, item.alarmDeletes.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                item.alarmDeletes.forEach { delete -> AlarmDeleteRow(delete) }
            }

            if (item.eventDeletes.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.ai_proposal_delete_events, item.eventDeletes.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                item.eventDeletes.forEach { delete -> EventDeleteRow(delete) }
            }

            if (item.state == ProposalState.PENDING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onCancel, enabled = !busy) {
                        Text(stringResource(R.string.ai_proposal_cancel))
                    }
                    Button(onClick = onConfirm, enabled = !busy) {
                        Text(
                            text = if (busy) {
                                stringResource(R.string.ai_status_creating)
                            } else if (updateCount > 0 || deleteCount > 0) {
                                stringResource(R.string.ai_proposal_apply)
                            } else {
                                stringResource(R.string.ai_proposal_confirm)
                            },
                        )
                    }
                }
            }
        }
    }

    editAlarmIndex?.let { index ->
        item.alarms.getOrNull(index)?.let { draft ->
            AlarmDraftEditDialog(
                draft = draft,
                allTags = allTags,
                timePickerStyle = timePickerStyle,
                onDismiss = { editAlarmIndex = null },
                onSave = { updated ->
                    editAlarmIndex = null
                    onUpdateAlarm(index, updated)
                    onConfirm()
                },
            )
        }
    }
    editAlarmStackIndex?.let { index ->
        item.alarmStacks.getOrNull(index)?.let { draft ->
            AlarmStackDraftEditDialog(
                draft = draft,
                allTags = allTags,
                timePickerStyle = timePickerStyle,
                onDismiss = { editAlarmStackIndex = null },
                onSave = { updated ->
                    editAlarmStackIndex = null
                    onUpdateAlarmStack(index, updated)
                    onConfirm()
                },
            )
        }
    }
    editEventIndex?.let { index ->
        item.events.getOrNull(index)?.let { draft ->
            EventDraftEditDialog(
                draft = draft,
                allTags = allTags,
                timePickerStyle = timePickerStyle,
                onDismiss = { editEventIndex = null },
                onSave = { updated ->
                    editEventIndex = null
                    onUpdateEvent(index, updated)
                    onConfirm()
                },
            )
        }
    }
}

@Composable
private fun ProposalStateLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun AlarmDraftRow(alarm: AlarmDraft, onEdit: (() -> Unit)? = null) {
    val time = formatTime(alarm.time)
    val whenText = if (alarm.date == null) {
        stringResource(R.string.ai_format_next_time, time)
    } else {
        stringResource(R.string.ai_format_dated_time, formatDate(alarm.date), time)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Notifications,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "$whenText  ${alarm.name.ifBlank {
                    stringResource(R.string.ai_proposal_alarm_name_fallback)
                }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            val details = listOfNotNull(
                if (!alarm.enabled) stringResource(R.string.ai_proposal_disabled) else null,
                if (alarm.oneTime) stringResource(R.string.alarm_one_time_badge) else null,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (alarm.note.isNotBlank()) {
                Text(
                    text = alarm.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (alarm.tags.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_tags,
                        alarm.tags.joinToString("、") { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DraftEditButton(onEdit)
    }
}

@Composable
private fun AlarmStackDraftRow(stack: AlarmStackDraft, onEdit: (() -> Unit)? = null) {
    val clockFormatter = rememberClockFormatter()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Filled.List,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stack.times.joinToString(" · ") { clockFormatter.format(it) },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stack.name.ifBlank {
                    stringResource(R.string.ai_proposal_alarm_name_fallback)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val details = listOfNotNull(
                stringResource(R.string.alarm_stack_count, stack.times.size),
                if (!stack.enabled) stringResource(R.string.ai_proposal_disabled) else null,
                if (stack.oneTime) stringResource(R.string.alarm_one_time_badge) else null,
            )
            Text(
                text = details.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stack.note.isNotBlank()) {
                Text(
                    text = stack.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (stack.tags.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_tags,
                        stack.tags.joinToString("、") { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DraftEditButton(onEdit)
    }
}

@Composable
private fun EventDraftRow(event: EventDraft, onEdit: (() -> Unit)? = null) {
    val locale = rememberLocale()
    val dateText = formatDate(event.date)
    val timeRange = formatEventTimeRange(
        allDay = event.allDay,
        start = event.start,
        end = event.end,
        endDayOffset = event.endDayOffset,
    )
    val weekdayText = event.repeatDays.sorted().joinToString(", ") {
        weekdayLabel(it, locale)
    }
    val repeatText = when (event.repeatType) {
        Event.REPEAT_DAILY -> stringResource(R.string.ai_proposal_repeat_daily)
        Event.REPEAT_WEEKLY -> stringResource(R.string.ai_proposal_repeat_weekly, weekdayText)
        else -> null
    }
    val untilDate = event.repeatUntil?.let { formatDate(it) }
    val untilText = untilDate?.let { stringResource(R.string.ai_proposal_until, it) }
    val occurrencesText = if (event.occurrenceCount > 1) {
        stringResource(R.string.ai_proposal_occurrences, event.occurrenceCount)
    } else {
        null
    }
    val alarmText = if (event.withAlarm) {
        stringResource(R.string.ai_proposal_alarm_at_start)
    } else {
        null
    }
    val reminderText = if (event.reminderMinutes > 0) {
        stringResource(R.string.ai_proposal_reminder_before, event.reminderMinutes)
    } else {
        null
    }
    val details = listOfNotNull(repeatText, untilText, occurrencesText, alarmText, reminderText)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_ai),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "$dateText $timeRange",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (event.note.isNotBlank()) {
                Text(
                    text = event.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (event.tags.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_tags,
                        event.tags.joinToString("、") { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DraftEditButton(onEdit)
    }
}

@Composable
private fun DraftEditButton(onEdit: (() -> Unit)?) {
    if (onEdit == null) return
    IconButton(onClick = onEdit) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.ai_proposal_edit),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun AlarmUpdateRow(update: AlarmUpdateDraft) {
    val previousWhen = alarmWhenText(update.previousTime, update.previousFireDate)
    val nextWhen = alarmWhenText(update.time, update.fireDate)
    val previousName = update.previousName.ifBlank {
        stringResource(R.string.ai_proposal_alarm_name_fallback)
    }
    val nextName = update.name.ifBlank {
        stringResource(R.string.ai_proposal_alarm_name_fallback)
    }
    val notRinging = update.enabled &&
        update.fireDate?.atTime(update.time)?.isBefore(LocalDateTime.now()) == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            val timeChanged = update.previousTime != update.time ||
                update.previousFireDate != update.fireDate
            Text(
                text = if (timeChanged) {
                    stringResource(R.string.ai_proposal_alarm_change, previousWhen, nextWhen)
                } else {
                    nextWhen
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (previousName != nextName) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_alarm_change,
                        previousName,
                        nextName,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = nextName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (update.enabled != update.previousEnabled) {
                Text(
                    text = stringResource(
                        if (update.enabled) R.string.ai_proposal_enabled
                        else R.string.ai_proposal_disabled,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (update.oneTime != update.previousOneTime) {
                Text(
                    text = stringResource(
                        if (update.oneTime) R.string.ai_proposal_one_time_added
                        else R.string.ai_proposal_one_time_removed,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (notRinging) {
                Text(
                    text = stringResource(R.string.ai_proposal_will_not_ring),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (update.tags.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_tags,
                        update.tags.joinToString("、") { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AlarmDeleteRow(delete: AlarmDeleteDraft) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${alarmWhenText(delete.time, delete.fireDate)}  " +
                    delete.name.ifBlank {
                        stringResource(R.string.ai_proposal_alarm_name_fallback)
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            if (delete.isAuto) {
                Text(
                    text = stringResource(R.string.ai_proposal_auto_alarm_delete_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EventUpdateRow(update: EventUpdateDraft) {
    val before = "${formatDate(update.previousDate)} " + formatEventTimeRange(
        allDay = update.previousAllDay,
        start = update.previousStart,
        end = update.previousEnd,
        endDayOffset = update.previousEndDayOffset,
    )
    val after = "${formatDate(update.date)} " + formatEventTimeRange(
        allDay = update.allDay,
        start = update.start,
        end = update.end,
        endDayOffset = update.endDayOffset,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (update.title != update.previousTitle) {
                    stringResource(
                        R.string.ai_proposal_alarm_change,
                        update.previousTitle,
                        update.title,
                    )
                } else {
                    update.title
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            val timeChanged = update.previousDate != update.date ||
                update.previousStart != update.start ||
                update.previousEnd != update.end ||
                update.previousEndDayOffset != update.endDayOffset ||
                update.previousAllDay != update.allDay
            Text(
                text = if (timeChanged) {
                    stringResource(R.string.ai_proposal_alarm_change, before, after)
                } else {
                    after
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(
                    R.string.ai_proposal_scope,
                    scopeLabel(update.scope),
                ) + " · " + stringResource(
                    R.string.ai_proposal_affected_events,
                    update.affectedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (update.withAlarm) {
                Text(
                    text = stringResource(R.string.ai_proposal_alarm_at_start),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (update.previousWithAlarm) {
                Text(
                    text = stringResource(R.string.ai_proposal_event_alarm_removed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            val reminderChange = update.reminderMinutes
                ?.takeIf { it != update.previousReminderMinutes }
            if (reminderChange != null) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_reminder_change,
                        reminderLabel(update.previousReminderMinutes),
                        reminderLabel(reminderChange),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (update.tags.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.ai_proposal_tags,
                        update.tags.joinToString("、") { it.name },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EventDeleteRow(delete: EventDeleteDraft) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            Icons.Filled.Delete,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${delete.title}  ${formatDate(delete.date)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(
                    R.string.ai_proposal_scope,
                    scopeLabel(delete.scope),
                ) + " · " + stringResource(
                    R.string.ai_proposal_affected_events,
                    delete.affectedCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (delete.linkedAlarms > 0) {
                Text(
                    text = if (delete.keepAlarms) {
                        stringResource(R.string.ai_proposal_keep_alarms)
                    } else {
                        stringResource(
                            R.string.ai_proposal_linked_alarms_deleted,
                            delete.linkedAlarms,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (delete.keepAlarms) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun alarmWhenText(time: LocalTime, fireDate: LocalDate?): String =
    if (fireDate == null) {
        stringResource(R.string.ai_format_next_time, formatTime(time))
    } else {
        stringResource(R.string.ai_format_dated_time, formatDate(fireDate), formatTime(time))
    }

@Composable
private fun scopeLabel(scope: EventScope): String = stringResource(
    when (scope) {
        EventScope.THIS_EVENT -> R.string.ai_proposal_scope_this
        EventScope.THIS_AND_SUBSEQUENT -> R.string.ai_proposal_scope_subsequent
        EventScope.ENTIRE_SERIES -> R.string.ai_proposal_scope_series
    },
)

@Composable
private fun reminderLabel(minutes: Int): String =
    if (minutes > 0) {
        stringResource(R.string.ai_proposal_reminder_before, minutes)
    } else {
        stringResource(R.string.ai_proposal_reminder_off)
    }

@Composable
private fun AiInputBar(
    resetKey: Int,
    isBusy: Boolean,
    pendingProposal: Boolean,
    enabled: Boolean,
    hasPendingImage: Boolean,
    imagePreview: ImageBitmap?,
    imageLoading: Boolean,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
) {
    var text by rememberSaveable(resetKey) { mutableStateOf("") }
    val canSend = (text.isNotBlank() || hasPendingImage) &&
        enabled &&
        !isBusy &&
        !imageLoading &&
        !pendingProposal
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (imagePreview != null || imageLoading) {
                AttachmentPreview(
                    image = imagePreview,
                    loading = imageLoading,
                    onRemove = onRemoveImage,
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                IconButton(
                    onClick = onAttach,
                    enabled = enabled && !imageLoading,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_photo),
                        contentDescription = stringResource(R.string.ai_image_attach),
                    )
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(AiTokenBudget.MAX_USER_CHARS) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                    placeholder = {
                        Text(
                            text = stringResource(
                                if (pendingProposal) {
                                    R.string.ai_input_pending
                                } else {
                                    R.string.ai_input_hint
                                },
                            ),
                        )
                    },
                    maxLines = 5,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                Spacer(modifier = Modifier.size(8.dp))
                FilledIconButton(
                    onClick = if (isBusy) {
                        onStop
                    } else {
                        {
                            val typed = text
                            text = ""
                            onSend(typed)
                        }
                    },
                    enabled = if (isBusy) !pendingProposal else canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    if (isBusy && !pendingProposal) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.ai_stop),
                        )
                    } else {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.ai_send),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentPreview(
    image: ImageBitmap?,
    loading: Boolean,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.ai_image_attached),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Text(
            text = stringResource(
                if (loading) R.string.ai_image_processing else R.string.ai_image_ready,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, enabled = !loading) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.ai_image_remove),
            )
        }
    }
}

@Composable
private fun SessionsDialog(
    sessions: List<AiChatSessionSummary>,
    activeSessionId: Long?,
    busy: Boolean,
    onSwitch: (Long) -> Unit,
    onRename: (Long, String) -> Unit,
    onDelete: (Long) -> Unit,
    onNewChat: () -> Unit,
    onDismiss: () -> Unit,
) {
    var editing by remember { mutableStateOf<AiChatSessionSummary?>(null) }
    var deleting by remember { mutableStateOf<AiChatSessionSummary?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_sessions_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.ai_sessions_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.ai_sessions_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(sessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                active = session.id == activeSessionId,
                                enabled = !busy,
                                onOpen = {
                                    onSwitch(session.id)
                                    onDismiss()
                                },
                                onEdit = { editing = session },
                                onDelete = { deleting = session },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    onClick = {
                        onNewChat()
                        onDismiss()
                    },
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.ai_new_chat))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.alarm_permission_close))
                }
            }
        },
    )

    editing?.let { session ->
        SessionTitleDialog(
            session = session,
            onConfirm = { title ->
                onRename(session.id, title)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { session ->
        SessionDeleteDialog(
            session = session,
            onConfirm = {
                onDelete(session.id)
                deleting = null
                onDismiss()
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SessionRow(
    session: AiChatSessionSummary,
    active: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val title = session.title.ifBlank {
        stringResource(R.string.ai_sessions_default_title)
    }
    val secondaryColor = if (active) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onOpen),
        shape = MaterialTheme.shapes.medium,
        color = if (active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (active) {
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.ai_sessions_active),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
                if (session.preview.isNotBlank()) {
                    Text(
                        text = session.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.ai_sessions_last_active,
                        formatSessionTime(session.updatedAt),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = secondaryColor,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.ai_sessions_edit_title),
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.ai_sessions_delete),
                )
            }
        }
    }
}

@Composable
private fun SessionTitleDialog(
    session: AiChatSessionSummary,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val defaultTitle = stringResource(R.string.ai_sessions_default_title)
    var title by remember(session.id) {
        mutableStateOf(session.title.ifBlank { defaultTitle })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_sessions_edit_title)) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(60) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.ai_sessions_title_label)) },
                placeholder = { Text(defaultTitle) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun SessionDeleteDialog(
    session: AiChatSessionSummary,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = session.title.ifBlank {
        stringResource(R.string.ai_sessions_default_title)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_sessions_delete_title)) },
        text = { Text(stringResource(R.string.ai_sessions_delete_text, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun UsageDialog(
    usage: com.dinoe.clockwise.ai.AiUsage,
    requestCount: Int,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_usage_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (requestCount == 0) {
                    Text(stringResource(R.string.ai_usage_empty))
                } else {
                    Text(
                        stringResource(
                            R.string.ai_usage_input,
                            formatTokens(usage.promptTokens),
                        ),
                    )
                    Text(
                        stringResource(
                            R.string.ai_usage_output,
                            formatTokens(usage.completionTokens),
                        ),
                    )
                    if (usage.cacheReported && usage.promptTokens > 0) {
                        Text(
                            stringResource(
                                R.string.ai_usage_cached,
                                formatTokens(usage.cachedTokens),
                            ),
                        )
                    }
                    val cacheRate = usage.cacheHitRate
                    Text(
                        text = stringResource(
                            R.string.ai_usage_cache_rate,
                            cacheRate?.let(::formatPercent)
                                ?: stringResource(R.string.ai_usage_cache_unknown),
                        ),
                    )
                    if (cacheRate != null) {
                        LinearProgressIndicator(
                            progress = { cacheRate.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }
                    if (usage.reasoningTokens > 0) {
                        Text(
                            stringResource(
                                R.string.ai_usage_reasoning,
                                formatTokens(usage.reasoningTokens),
                            ),
                        )
                    }
                    Text(stringResource(R.string.ai_usage_requests, requestCount))
                    Text(
                        text = stringResource(R.string.ai_usage_cache_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.ai_usage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.alarm_permission_close))
            }
        },
    )
}

@Composable
private fun formatTime(time: LocalTime): String = rememberClockFormatter().format(time)

@Composable
private fun formatEventTimeRange(
    allDay: Boolean,
    start: LocalTime,
    end: LocalTime,
    endDayOffset: Int,
): String {
    if (allDay) return stringResource(R.string.event_all_day)
    if (!end.isAfter(start) && endDayOffset == 0) return formatTime(start)
    val startText = formatTime(start)
    val endText = formatTime(end)
    return when (endDayOffset) {
        0 -> "$startText–$endText"
        1 -> stringResource(R.string.event_time_range_next_day, startText, endText)
        else -> stringResource(
            R.string.event_time_range_days_later,
            startText,
            endDayOffset,
            endText,
        )
    }
}

@Composable
private fun formatDate(date: LocalDate): String =
    rememberDatePatternFormatter("MMMdEEEE").format(date)

private fun weekdayLabel(isoDay: Int, locale: Locale): String =
    DayOfWeek.of(isoDay).getDisplayName(TextStyle.SHORT, locale)

private fun formatTokens(value: Long): String =
    String.format(Locale.getDefault(), "%,d", value)

private fun formatPercent(rate: Double): String =
    String.format(Locale.getDefault(), "%.1f%%", rate * 100)

@Composable
private fun formatSessionTime(millis: Long): String =
    rememberDatePatternFormatter("MMMdHm")
        .format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
