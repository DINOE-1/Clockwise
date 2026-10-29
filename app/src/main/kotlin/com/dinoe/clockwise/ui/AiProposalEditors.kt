package com.dinoe.clockwise.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ai.AiDraftEditor
import com.dinoe.clockwise.ai.AiDraftIssue
import com.dinoe.clockwise.ai.AlarmDraft
import com.dinoe.clockwise.ai.AlarmStackDraft
import com.dinoe.clockwise.ai.EventDraft
import com.dinoe.clockwise.ai.TagRef
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.MAX_OCCURRENCES_PER_SERIES
import com.dinoe.clockwise.data.MAX_TAGS_PER_ITEM
import com.dinoe.clockwise.data.RecurrenceRule
import com.dinoe.clockwise.data.Tag
import com.dinoe.clockwise.data.planRecurrence
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

private const val NO_DATE = Long.MIN_VALUE

@Composable
internal fun AlarmDraftEditDialog(
    draft: AlarmDraft,
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (AlarmDraft) -> Unit,
) {
    var timeSeconds by rememberSaveable { mutableIntStateOf(draft.time.toSecondOfDay()) }
    var dateEpochDay by rememberSaveable {
        mutableLongStateOf(draft.date?.toEpochDay() ?: NO_DATE)
    }
    var name by rememberSaveable { mutableStateOf(draft.name) }
    var note by rememberSaveable { mutableStateOf(draft.note) }
    var enabled by rememberSaveable { mutableStateOf(draft.enabled) }
    var oneTime by rememberSaveable { mutableStateOf(draft.oneTime) }
    var selectedTagIds by rememberSaveable { mutableStateOf(draft.tags.map { it.id }.toLongArray()) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }

    val date = dateEpochDay.takeIf { it != NO_DATE }?.let(LocalDate::ofEpochDay)
    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val current = draft.copy(
        time = LocalTime.ofSecondOfDay(timeSeconds.toLong()),
        date = date,
        name = name,
        note = note,
        enabled = enabled,
        oneTime = oneTime,
        tags = tagRefs(allTags, selectedTagIds),
    )
    val issue = AiDraftEditor.alarmIssue(current, LocalDateTime.now())

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_edit_alarm_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                DraftTimeButton(
                    label = stringResource(R.string.alarm_time_label),
                    secondsOfDay = timeSeconds,
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                DraftDateButton(
                    label = stringResource(R.string.event_date_label),
                    date = date,
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (date != null) {
                    TextButton(onClick = { dateEpochDay = NO_DATE }) {
                        Text(stringResource(R.string.ai_edit_clear_date))
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(AiDraftEditor.MAX_ALARM_NAME_CHARS) },
                    label = { Text(stringResource(R.string.alarm_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(AiDraftEditor.MAX_NOTE_CHARS) },
                    label = { Text(stringResource(R.string.alarm_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = stringResource(R.string.ai_proposal_enabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                SwitchRow(
                    label = stringResource(R.string.alarm_one_time_label),
                    checked = oneTime,
                    onCheckedChange = { oneTime = it },
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = { tag ->
                        selectedTagIds = toggleTag(selectedTagIds, tag.id)
                    },
                )
                DraftIssueText(issue)
                EditDialogButtons(
                    onDismiss = onDismiss,
                    onConfirm = { onSave(current) },
                    enabled = issue == null,
                )
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialSecondsOfDay = timeSeconds,
            style = timePickerStyle,
            onDismiss = { showTimePicker = false },
            onConfirm = { seconds ->
                timeSeconds = seconds
                showTimePicker = false
            },
        )
    }
    if (showDatePicker) {
        DraftDatePickerDialog(
            initialEpochDay = date?.toEpochDay() ?: LocalDate.now().toEpochDay(),
            onDismiss = { showDatePicker = false },
            onConfirm = { picked ->
                dateEpochDay = picked.toEpochDay()
                showDatePicker = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AlarmStackDraftEditDialog(
    draft: AlarmStackDraft,
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (AlarmStackDraft) -> Unit,
) {
    var times by rememberSaveable {
        mutableStateOf(draft.times.map { it.toSecondOfDay() }.toIntArray())
    }
    var name by rememberSaveable { mutableStateOf(draft.name) }
    var note by rememberSaveable { mutableStateOf(draft.note) }
    var enabled by rememberSaveable { mutableStateOf(draft.enabled) }
    var oneTime by rememberSaveable { mutableStateOf(draft.oneTime) }
    var selectedTagIds by rememberSaveable { mutableStateOf(draft.tags.map { it.id }.toLongArray()) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var addingTime by remember { mutableStateOf(false) }

    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val current = draft.copy(
        times = times.map { LocalTime.ofSecondOfDay(it.toLong()) }.sorted(),
        name = name,
        note = note,
        enabled = enabled,
        oneTime = oneTime,
        tags = tagRefs(allTags, selectedTagIds),
    )
    val issue = AiDraftEditor.stackIssue(current)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_edit_stack_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                times.forEachIndexed { index, seconds ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DraftTimeButton(
                            label = stringResource(R.string.alarm_time_label),
                            secondsOfDay = seconds,
                            onClick = { editingIndex = index },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                times = times.filterIndexed { i, _ -> i != index }.toIntArray()
                            },
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(R.string.ai_edit_remove_time),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                TextButton(onClick = { addingTime = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.ai_edit_add_time))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(AiDraftEditor.MAX_ALARM_NAME_CHARS) },
                    label = { Text(stringResource(R.string.alarm_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(AiDraftEditor.MAX_NOTE_CHARS) },
                    label = { Text(stringResource(R.string.alarm_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = stringResource(R.string.ai_proposal_enabled),
                    checked = enabled,
                    onCheckedChange = { enabled = it },
                )
                SwitchRow(
                    label = stringResource(R.string.alarm_one_time_label),
                    checked = oneTime,
                    onCheckedChange = { oneTime = it },
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = { tag ->
                        selectedTagIds = toggleTag(selectedTagIds, tag.id)
                    },
                )
                DraftIssueText(issue)
                EditDialogButtons(
                    onDismiss = onDismiss,
                    onConfirm = { onSave(current) },
                    enabled = issue == null,
                )
            }
        }
    }

    if (addingTime) {
        TimePickerDialog(
            initialSecondsOfDay = LocalTime.now().toSecondOfDay(),
            style = timePickerStyle,
            onDismiss = { addingTime = false },
            onConfirm = { seconds ->
                times = times + seconds
                addingTime = false
            },
        )
    }
    editingIndex?.let { index ->
        TimePickerDialog(
            initialSecondsOfDay = times.getOrElse(index) { LocalTime.now().toSecondOfDay() },
            style = timePickerStyle,
            onDismiss = { editingIndex = null },
            onConfirm = { seconds ->
                times = times.copyOf().also { it[index] = seconds }
                editingIndex = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun EventDraftEditDialog(
    draft: EventDraft,
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (EventDraft) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(draft.title) }
    var note by rememberSaveable { mutableStateOf(draft.note) }
    var dateEpochDay by rememberSaveable { mutableLongStateOf(draft.date.toEpochDay()) }
    var startSeconds by rememberSaveable { mutableIntStateOf(draft.start.toSecondOfDay()) }
    var endSeconds by rememberSaveable { mutableIntStateOf(draft.end.toSecondOfDay()) }
    var endDateEpochDay by rememberSaveable {
        mutableLongStateOf(draft.date.plusDays(draft.endDayOffset.toLong()).toEpochDay())
    }
    var hasEndTime by rememberSaveable {
        mutableStateOf(draft.end.isAfter(draft.start) || draft.endDayOffset > 0)
    }
    var allDay by rememberSaveable { mutableStateOf(draft.allDay) }
    var withAlarm by rememberSaveable { mutableStateOf(draft.withAlarm) }
    var reminderMinutes by rememberSaveable { mutableIntStateOf(draft.reminderMinutes) }
    var selectedTagIds by rememberSaveable { mutableStateOf(draft.tags.map { it.id }.toLongArray()) }
    var repeatType by rememberSaveable { mutableIntStateOf(draft.repeatType) }
    var repeatDayIds by rememberSaveable {
        mutableStateOf(draft.repeatDays.map { it.toLong() }.toLongArray())
    }
    var repeatUntilEpochDay by rememberSaveable {
        mutableLongStateOf(
            draft.repeatUntil?.toEpochDay() ?: draft.date.plusDays(90).toEpochDay(),
        )
    }
    var timeField by remember { mutableStateOf<EventDraftTimeField?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showRepeatDatePicker by remember { mutableStateOf(false) }

    val date = LocalDate.ofEpochDay(dateEpochDay)
    val endDate = LocalDate.ofEpochDay(endDateEpochDay)
    val endDayOffset = (endDateEpochDay - dateEpochDay).toInt().coerceAtLeast(0)
    val until = LocalDate.ofEpochDay(repeatUntilEpochDay)
    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val repeatDays = repeatDayIds.map { it.toInt() }.toSet()
    val rule = RecurrenceRule(
        type = repeatType,
        days = repeatDays,
        untilEpochDay = repeatUntilEpochDay,
    )
    val plan = if (repeatType == Event.REPEAT_NONE) null else planRecurrence(date, rule)
    val startTime = LocalTime.ofSecondOfDay(startSeconds.toLong())
    val current = draft.copy(
        title = title,
        note = note,
        date = date,
        start = startTime,
        end = if (hasEndTime) LocalTime.ofSecondOfDay(endSeconds.toLong()) else startTime,
        endDayOffset = if (allDay || !hasEndTime) 0 else endDayOffset,
        allDay = allDay,
        withAlarm = withAlarm && !allDay,
        repeatType = repeatType,
        repeatDays = if (repeatType == Event.REPEAT_WEEKLY) repeatDays else emptySet(),
        repeatUntil = if (repeatType == Event.REPEAT_NONE) null else until,
        occurrenceCount = plan?.totalOccurrences ?: 1,
        reminderMinutes = reminderMinutes,
        tags = tagRefs(allTags, selectedTagIds),
    )
    val issue = AiDraftEditor.eventIssue(current, LocalDateTime.now())
    val dayFormatter = rememberDatePatternFormatter("MMMdEEEE")
    val weekdayLabels = rememberWeekdayLabels()

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.ai_edit_event_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = stringResource(R.string.event_date_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = date.format(dayFormatter),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(AiDraftEditor.MAX_EVENT_TITLE_CHARS) },
                    label = { Text(stringResource(R.string.event_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                SwitchRow(
                    label = stringResource(R.string.event_all_day),
                    checked = allDay,
                    onCheckedChange = { allDay = it },
                )
                if (allDay) {
                    Text(
                        text = stringResource(R.string.event_all_day_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DraftTimeButton(
                            label = stringResource(R.string.start_time_label),
                            secondsOfDay = startSeconds,
                            onClick = { timeField = EventDraftTimeField.START },
                            modifier = Modifier.weight(1f),
                        )
                        DraftTimeButton(
                            label = stringResource(R.string.end_time_label),
                            secondsOfDay = endSeconds,
                            onClick = { timeField = EventDraftTimeField.END },
                            enabled = hasEndTime,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    SwitchRow(
                        label = stringResource(R.string.event_has_end_time),
                        checked = hasEndTime,
                        onCheckedChange = { hasEndTime = it },
                    )
                    if (!hasEndTime) {
                        Text(
                            text = stringResource(R.string.event_no_end_time_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        DraftDateButton(
                            label = stringResource(R.string.event_end_date_label),
                            date = endDate,
                            onClick = { showEndDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(AiDraftEditor.MAX_NOTE_CHARS) },
                    label = { Text(stringResource(R.string.event_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = { tag ->
                        selectedTagIds = toggleTag(selectedTagIds, tag.id)
                    },
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.repeat_rule_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = repeatType == Event.REPEAT_NONE,
                            onClick = { repeatType = Event.REPEAT_NONE },
                            label = { Text(stringResource(R.string.repeat_none)) },
                        )
                        FilterChip(
                            selected = repeatType == Event.REPEAT_DAILY,
                            onClick = { repeatType = Event.REPEAT_DAILY },
                            label = { Text(stringResource(R.string.repeat_daily)) },
                        )
                        FilterChip(
                            selected = repeatType == Event.REPEAT_WEEKLY,
                            onClick = { repeatType = Event.REPEAT_WEEKLY },
                            label = { Text(stringResource(R.string.repeat_weekly)) },
                        )
                    }
                    if (repeatType == Event.REPEAT_WEEKLY) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            weekdayLabels.forEachIndexed { index, label ->
                                val day = (index + 1).toLong()
                                FilterChip(
                                    selected = day in repeatDayIds,
                                    onClick = {
                                        repeatDayIds = if (day in repeatDayIds) {
                                            repeatDayIds.filter { it != day }.toLongArray()
                                        } else {
                                            repeatDayIds + day
                                        }
                                    },
                                    label = { Text(label) },
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.repeat_weekday_pick_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (repeatType != Event.REPEAT_NONE) {
                        OutlinedButton(
                            onClick = { showRepeatDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.repeat_until_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = until.format(dayFormatter),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                }
                if (!allDay) {
                    ReminderPicker(
                        reminderMinutes = reminderMinutes,
                        onReminderChange = { reminderMinutes = it },
                    )
                    SwitchRow(
                        label = stringResource(R.string.event_alarm_label),
                        checked = withAlarm,
                        onCheckedChange = { withAlarm = it },
                    )
                    if (withAlarm) {
                        Text(
                            text = stringResource(R.string.event_alarm_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                DraftIssueText(issue)
                if (issue == null && repeatType != Event.REPEAT_NONE) {
                    Text(
                        text = stringResource(R.string.series_size_info, current.occurrenceCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                EditDialogButtons(
                    onDismiss = onDismiss,
                    onConfirm = { onSave(current) },
                    enabled = issue == null,
                )
            }
        }
    }

    if (showDatePicker) {
        DraftDatePickerDialog(
            initialEpochDay = dateEpochDay,
            onDismiss = { showDatePicker = false },
            onConfirm = { picked ->
                val pickedEpochDay = picked.toEpochDay()
                dateEpochDay = pickedEpochDay
                endDateEpochDay = endDateEpochDay.coerceIn(
                    pickedEpochDay,
                    pickedEpochDay + Event.MAX_EVENT_DAY_SPAN,
                )
                showDatePicker = false
            },
        )
    }
    if (showEndDatePicker) {
        DraftDatePickerDialog(
            initialEpochDay = endDateEpochDay,
            onDismiss = { showEndDatePicker = false },
            onConfirm = { picked ->
                if (picked.toEpochDay() >= dateEpochDay) {
                    endDateEpochDay = picked.toEpochDay().coerceAtMost(
                        dateEpochDay + Event.MAX_EVENT_DAY_SPAN,
                    )
                }
                showEndDatePicker = false
            },
        )
    }
    if (showRepeatDatePicker) {
        DraftDatePickerDialog(
            initialEpochDay = repeatUntilEpochDay,
            onDismiss = { showRepeatDatePicker = false },
            onConfirm = { picked ->
                repeatUntilEpochDay = picked.toEpochDay()
                showRepeatDatePicker = false
            },
        )
    }
    timeField?.let { field ->
        TimePickerDialog(
            initialSecondsOfDay = if (field == EventDraftTimeField.START) {
                startSeconds
            } else {
                endSeconds
            },
            style = timePickerStyle,
            onDismiss = { timeField = null },
            onConfirm = { seconds ->
                if (field == EventDraftTimeField.START) {
                    startSeconds = seconds
                } else {
                    endSeconds = seconds
                }
                timeField = null
            },
        )
    }
}

private enum class EventDraftTimeField { START, END }

@Composable
private fun DraftTimeButton(
    label: String,
    secondsOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val formatter = rememberClockFormatter()
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = formatter.format(LocalTime.ofSecondOfDay(secondsOfDay.toLong())),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun DraftDateButton(
    label: String,
    date: LocalDate?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatter = rememberDatePatternFormatter("MMMdEEEE")
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = date?.format(formatter) ?: stringResource(R.string.ai_edit_date_none),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DraftIssueText(issue: AiDraftIssue?) {
    if (issue == null) return
    val message = when (issue) {
        AiDraftIssue.ALARM_IN_PAST -> stringResource(R.string.ai_edit_alarm_in_past)
        AiDraftIssue.TITLE_REQUIRED -> stringResource(R.string.ai_edit_title_required)
        AiDraftIssue.INVALID_TIME_RANGE -> stringResource(R.string.invalid_time_range)
        AiDraftIssue.REPEAT_UNTIL_INVALID -> stringResource(R.string.repeat_until_invalid)
        AiDraftIssue.REPEAT_WEEKDAYS_REQUIRED,
        AiDraftIssue.REPEAT_WEEKDAY_MISSING,
        -> stringResource(R.string.ai_edit_repeat_weekday_missing)

        AiDraftIssue.TOO_MANY_OCCURRENCES -> stringResource(
            R.string.recurrence_too_large,
            MAX_OCCURRENCES_PER_SERIES,
        )

        AiDraftIssue.STACK_TIMES_INVALID -> stringResource(R.string.ai_edit_stack_times_invalid)
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun EditDialogButtons(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.cancel))
        }
        Button(onClick = onConfirm, enabled = enabled) {
            Text(stringResource(R.string.ai_edit_confirm))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DraftDatePickerDialog(
    initialEpochDay: Long,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialEpochDay * 86_400_000L,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    state.selectedDateMillis?.let { millis ->
                        onConfirm(LocalDate.ofEpochDay(millis / 86_400_000L))
                    }
                },
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    ) {
        DatePicker(state = state)
    }
}

private fun tagRefs(allTags: List<Tag>, selectedIds: LongArray): List<TagRef> =
    allTags.filter { it.id in selectedIds }.map { TagRef(id = it.id, name = it.name) }

private fun toggleTag(selectedIds: LongArray, id: Long): LongArray = when {
    id in selectedIds -> selectedIds.filter { it != id }.toLongArray()
    selectedIds.size < MAX_TAGS_PER_ITEM -> selectedIds + id
    else -> selectedIds
}
