package com.dinoe.clockwise.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ai.EventRecurrencePlanner
import com.dinoe.clockwise.ai.RecurrenceDraftIssue
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.MAX_OCCURRENCES_PER_SERIES
import com.dinoe.clockwise.data.MAX_TAGS_PER_ITEM
import com.dinoe.clockwise.data.RecurrenceRule
import com.dinoe.clockwise.data.Tag
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.FormatStyle
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch


@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = viewModel(),
    pendingEventId: Long? = null,
    onPendingEventConsumed: () -> Unit = {},
) {
    val eventsByDay by viewModel.eventsByDay.collectAsStateWithLifecycle()
    val alarms by viewModel.alarms.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val eventTagMap by viewModel.eventTagMap.collectAsStateWithLifecycle()
    val snapInterval by viewModel.snapInterval.collectAsStateWithLifecycle()
    val timePickerStyle by viewModel.timePickerStyle.collectAsStateWithLifecycle()
    val displayedMonth by viewModel.displayedMonth.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val selectedDayEvents = remember(eventsByDay, selectedDate) {
        eventsByDay[selectedDate.toEpochDay()].orEmpty()
    }
    val viewMode = viewModel.viewMode
    val errorMessage = viewModel.errorMessage
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permissionScope = rememberCoroutineScope()
    val reminderPermissionDenied = stringResource(R.string.reminder_permission_denied)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            permissionScope.launch {
                snackbarHostState.showSnackbar(reminderPermissionDenied)
            }
        }
    }
    val ensureReminderPermission: (Int) -> Unit = { reminderMinutes ->
        if (reminderMinutes > 0 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }

    var showTimePicker by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var addDialogInitialTimes by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var eventToDelete by remember { mutableStateOf<Event?>(null) }
    var eventToEdit by remember { mutableStateOf<Event?>(null) }

    LaunchedEffect(pendingEventId) {
        val id = pendingEventId ?: return@LaunchedEffect
        val event = viewModel.getEventOnce(id)
        onPendingEventConsumed()
        if (event == null) return@LaunchedEffect
        viewModel.selectDate(LocalDate.ofEpochDay(event.date))
        eventToEdit = event
    }

    Scaffold(
        topBar = {
            CalendarTopBar(viewMode = viewMode, onViewModeChange = { viewModel.viewMode = it })
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showTimePicker = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_event))
            }
        },
    ) { innerPadding ->
        val contentModifier = Modifier.padding(innerPadding)
        when (viewMode) {
            ViewMode.MONTH -> MonthView(
                displayedMonth = displayedMonth,
                selectedDate = selectedDate,
                eventsByDay = eventsByDay,
                eventTags = eventTagMap,
                onDaySelected = viewModel::selectDate,
                onMonthChange = viewModel::showMonth,
                onToday = viewModel::selectToday,
                onDayAddRequested = { date ->
                    viewModel.selectDate(date)
                    showTimePicker = true
                },
                onEditRequested = { eventToEdit = it },
                onDeleteRequested = { eventToDelete = it },
                modifier = contentModifier,
            )

            ViewMode.DAY -> DayTimelineView(
                date = selectedDate,
                events = selectedDayEvents,
                eventTags = eventTagMap,
                modifier = contentModifier,
            )
        }
    }

    if (showTimePicker) {
        TimeRangePickerDialog(
            date = selectedDate,
            events = selectedDayEvents,
            eventTags = eventTagMap,
            snapMinutes = snapInterval,
            onDismiss = { showTimePicker = false },
            onConfirm = { start, end ->
                showTimePicker = false
                addDialogInitialTimes = start to end
                showAddDialog = true
            },
        )
    }

    if (showAddDialog) {
        AddEventDialog(
            date = selectedDate,
            allTags = allTags,
            timePickerStyle = timePickerStyle,
            initialStartSeconds = addDialogInitialTimes?.first,
            initialEndSeconds = addDialogInitialTimes?.second,
            onDismiss = {
                showAddDialog = false
                addDialogInitialTimes = null
            },
            onSave = { title, note, start, end, endDayOffset, allDay, withAlarm,
                reminderMinutes, tags, repeat ->
                viewModel.addEvent(
                    title = title,
                    note = note,
                    date = selectedDate,
                    start = start,
                    end = end,
                    withAlarm = withAlarm,
                    tags = tags,
                    repeat = repeat,
                    reminderMinutes = reminderMinutes,
                    endDayOffset = endDayOffset,
                    allDay = allDay,
                )
                ensureReminderPermission(reminderMinutes)
                showAddDialog = false
                addDialogInitialTimes = null
            },
        )
    }

    eventToEdit?.let { event ->
        EditEventDialog(
            event = event,
            allTags = allTags,
            timePickerStyle = timePickerStyle,
            initialTags = eventTagMap[event.id].orEmpty(),
            initialWithAlarm = alarms.any { it.eventId == event.id },
            onDismiss = { eventToEdit = null },
            onSave = { title, note, date, start, end, endDayOffset, allDay, withAlarm,
                reminderMinutes, tags, scope ->
                viewModel.updateEventScoped(
                    event = event,
                    scope = scope,
                    title = title,
                    note = note,
                    date = date,
                    start = start,
                    end = end,
                    withAlarm = withAlarm,
                    tags = tags,
                    reminderMinutes = reminderMinutes,
                    endDayOffset = endDayOffset,
                    allDay = allDay,
                )
                ensureReminderPermission(reminderMinutes)
                eventToEdit = null
            },
        )
    }

    eventToDelete?.let { event ->
        val isSeries = event.seriesId != 0L
        // Series siblings can lie outside the loaded calendar range, so they are read once here.
        var seriesMembers by remember(event.id) { mutableStateOf<List<Event>?>(null) }
        LaunchedEffect(event.id) {
            if (isSeries) seriesMembers = viewModel.getSeriesEvents(event.seriesId)
        }
        val members = seriesMembers.orEmpty()
        val isReady = !isSeries || seriesMembers != null
        var scope by remember(event.id) { mutableStateOf(EventSeriesScope.THIS_EVENT) }
        val affectedEvents = when (scope) {
            EventSeriesScope.THIS_AND_SUBSEQUENT ->
                members.filter { it.occurrenceIndex >= event.occurrenceIndex }

            EventSeriesScope.ENTIRE_SERIES -> members
            EventSeriesScope.THIS_EVENT -> listOf(event)
        }
        val hasLinkedAlarm = affectedEvents.any { candidate ->
            alarms.any { it.eventId == candidate.id }
        }
        var deleteAlarmToo by remember(event.id) { mutableStateOf(true) }
        if (isReady) {
            AlertDialog(
                onDismissRequest = { eventToDelete = null },
                title = { Text(stringResource(R.string.delete_event)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isSeries && scope != EventSeriesScope.THIS_EVENT) {
                            Text(
                                stringResource(
                                    R.string.delete_series_confirmation,
                                    event.title,
                                    affectedEvents.size,
                                ),
                            )
                        } else {
                            Text(stringResource(R.string.delete_event_confirmation, event.title))
                        }
                        if (isSeries) {
                            ScopeSelector(scope = scope, onScopeChange = { scope = it })
                        }
                        if (hasLinkedAlarm) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = deleteAlarmToo,
                                    onCheckedChange = { deleteAlarmToo = it },
                                )
                                Text(stringResource(R.string.delete_event_with_alarm))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteEventScoped(
                                event = event,
                                scope = if (isSeries) scope else EventSeriesScope.THIS_EVENT,
                                deleteAlarm = deleteAlarmToo && hasLinkedAlarm,
                            )
                            eventToDelete = null
                        },
                    ) {
                        Text(
                            stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { eventToDelete = null }) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarTopBar(viewMode: ViewMode, onViewModeChange: (ViewMode) -> Unit) {
    TopAppBar(
        title = { Text(stringResource(R.string.app_name)) },
        actions = {
            FilterChip(
                selected = viewMode == ViewMode.MONTH,
                onClick = { onViewModeChange(ViewMode.MONTH) },
                label = { Text(stringResource(R.string.view_mode_month)) },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = viewMode == ViewMode.DAY,
                onClick = { onViewModeChange(ViewMode.DAY) },
                label = { Text(stringResource(R.string.view_mode_day)) },
            )
            Spacer(Modifier.width(16.dp))
        },
    )
}

@Composable
private fun MonthView(
    displayedMonth: YearMonth,
    selectedDate: LocalDate,
    eventsByDay: Map<Long, List<Event>>,
    eventTags: Map<Long, List<Tag>>,
    onDaySelected: (LocalDate) -> Unit,
    onMonthChange: (YearMonth) -> Unit,
    onToday: () -> Unit,
    onDayAddRequested: (LocalDate) -> Unit,
    onEditRequested: (Event) -> Unit,
    onDeleteRequested: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val today = LocalDate.now()
    val gridRange = monthGridRange(displayedMonth)
    val gridStart = LocalDate.ofEpochDay(gridRange.first)
    val weekRows = ((gridRange.last - gridRange.first + 1) / 7).toInt()

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // Landscape phones have too little height for square cells plus the day list. Split the
        // month and the selected day into panes instead of squeezing both into one column.
        if (maxWidth > maxHeight) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.weight(1.25f).fillMaxHeight()) {
                    MonthHeader(
                        month = displayedMonth,
                        onPrevious = { onMonthChange(displayedMonth.minusMonths(1)) },
                        onNext = { onMonthChange(displayedMonth.plusMonths(1)) },
                        onToday = onToday,
                    )
                    WeekdayHeader()
                    MonthGrid(
                        gridStart = gridStart,
                        displayedMonth = displayedMonth,
                        selectedDate = selectedDate,
                        today = today,
                        eventsByDay = eventsByDay,
                        weekRows = weekRows,
                        fillHeight = true,
                        compact = true,
                        onDaySelected = onDaySelected,
                        onDayAddRequested = onDayAddRequested,
                        modifier = Modifier.weight(1f),
                    )
                }
                VerticalDivider()
                SelectedDayEvents(
                    date = selectedDate,
                    events = eventsByDay[selectedDate.toEpochDay()].orEmpty(),
                    eventTags = eventTags,
                    onEditRequested = onEditRequested,
                    onDeleteRequested = onDeleteRequested,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                MonthHeader(
                    month = displayedMonth,
                    onPrevious = { onMonthChange(displayedMonth.minusMonths(1)) },
                    onNext = { onMonthChange(displayedMonth.plusMonths(1)) },
                    onToday = onToday,
                )
                WeekdayHeader()
                MonthGrid(
                    gridStart = gridStart,
                    displayedMonth = displayedMonth,
                    selectedDate = selectedDate,
                    today = today,
                    eventsByDay = eventsByDay,
                    weekRows = weekRows,
                    fillHeight = false,
                    compact = false,
                    onDaySelected = onDaySelected,
                    onDayAddRequested = onDayAddRequested,
                )
                SelectedDayEvents(
                    date = selectedDate,
                    events = eventsByDay[selectedDate.toEpochDay()].orEmpty(),
                    eventTags = eventTags,
                    onEditRequested = onEditRequested,
                    onDeleteRequested = onDeleteRequested,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MonthHeader(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    val monthFormatter = rememberDatePatternFormatter("yMMMM")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.prev_month),
            )
        }
        Text(
            text = month.format(monthFormatter),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToday) {
            Text(stringResource(R.string.today))
        }
        IconButton(onClick = onNext) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.next_month),
            )
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val weekdayLabels = rememberWeekdayLabels()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
    ) {
        weekdayLabels.forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ColumnScope.MonthGrid(
    gridStart: LocalDate,
    displayedMonth: YearMonth,
    selectedDate: LocalDate,
    today: LocalDate,
    eventsByDay: Map<Long, List<Event>>,
    weekRows: Int,
    fillHeight: Boolean,
    compact: Boolean,
    onDaySelected: (LocalDate) -> Unit,
    onDayAddRequested: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        for (row in 0 until weekRows) {
            Row(
                modifier = if (fillHeight) {
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                } else {
                    Modifier.fillMaxWidth()
                },
            ) {
                for (column in 0 until 7) {
                    val date = gridStart.plusDays((row * 7 + column).toLong())
                    DayCell(
                        date = date,
                        inDisplayedMonth = YearMonth.from(date) == displayedMonth,
                        isSelected = date == selectedDate,
                        isToday = date == today,
                        hasEvent = eventsByDay.containsKey(date.toEpochDay()),
                        onClick = { onDaySelected(date) },
                        onDoubleClick = { onDayAddRequested(date) },
                        square = !fillHeight,
                        compact = compact,
                        modifier = Modifier
                            .weight(1f)
                            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelectedDayEvents(
    date: LocalDate,
    events: List<Event>,
    eventTags: Map<Long, List<Tag>>,
    onEditRequested: (Event) -> Unit,
    onDeleteRequested: (Event) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dayFormatter = rememberDatePatternFormatter("MMMdEEEE")
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider()
        Text(
            text = date.format(dayFormatter),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (events.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(events, key = { it.id }) { event ->
                    EventRow(
                        event = event,
                        tags = eventTags[event.id].orEmpty(),
                        onClick = { onEditRequested(event) },
                        onDeleteRequested = { onDeleteRequested(event) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DayCell(
    date: LocalDate,
    inDisplayedMonth: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvent: Boolean,
    onClick: () -> Unit,
    onDoubleClick: () -> Unit,
    modifier: Modifier = Modifier,
    square: Boolean = true,
    compact: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val numberColor = when {
        isSelected -> colors.onPrimary
        !inDisplayedMonth -> colors.outlineVariant
        isToday -> colors.primary
        else -> colors.onSurface
    }
    val fullDateFormatter = rememberLocalizedDateFormatter(FormatStyle.FULL)
    val selectedLabel = stringResource(R.string.day_cell_selected)
    val todayLabel = stringResource(R.string.today)
    val outsideMonthLabel = stringResource(R.string.day_cell_outside_month)
    val eventsLabel = stringResource(
        if (hasEvent) R.string.day_cell_has_events else R.string.empty_day,
    )
    val description = remember(
        date,
        isSelected,
        isToday,
        inDisplayedMonth,
        hasEvent,
        fullDateFormatter,
        selectedLabel,
        todayLabel,
        outsideMonthLabel,
        eventsLabel,
    ) {
        buildString {
            append(fullDateFormatter.format(date))
            if (isToday) append(", ").append(todayLabel)
            if (isSelected) append(", ").append(selectedLabel)
            if (!inDisplayedMonth) append(", ").append(outsideMonthLabel)
            append(", ").append(eventsLabel)
        }
    }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnDoubleClick by rememberUpdatedState(onDoubleClick)
    Box(
        modifier = modifier
            .then(if (square) Modifier.aspectRatio(1f) else Modifier)
            .padding(if (compact) 1.dp else 2.dp)
            .then(
                when {
                    isSelected -> Modifier.background(colors.primary, CircleShape)
                    isToday -> Modifier.border(1.5.dp, colors.primary, CircleShape)
                    else -> Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description }
            // clickable has no single-tap delay. Select immediately on the first pointer-down and
            // detect the second tap ourselves, so a quick tap on another day cannot be reverted by
            // a delayed click from the previously selected cell.
            .pointerInput(date) {
                var lastDownMillis = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (lastDownMillis != 0L &&
                        down.uptimeMillis - lastDownMillis <= viewConfiguration.doubleTapTimeoutMillis
                    ) {
                        lastDownMillis = 0L
                        currentOnDoubleClick()
                    } else {
                        lastDownMillis = down.uptimeMillis
                        currentOnClick()
                    }
                }
            }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = date.dayOfMonth.toString(),
                color = numberColor,
                style = if (compact) {
                    MaterialTheme.typography.bodySmall
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                maxLines = 1,
            )
            if (hasEvent) {
                if (!compact) Spacer(Modifier.height(2.dp))
                Box(
                    modifier = Modifier
                        .size(if (compact) 3.dp else 5.dp)
                        .background(if (isSelected) colors.onPrimary else colors.primary, CircleShape),
                )
            }
        }
    }
}

private val HourHeight = 96.dp
private val TimelineLabelWidth = 56.dp
private const val TimelineEndOfDaySeconds = 86_340
private const val MinEventDurationSeconds = 300
private const val DefaultDraftDurationSeconds = 3_600
private const val MinLaneDurationSeconds = 60

private data class TimelineInstance(val event: Event, val start: Int, val end: Int)

private data class TimelineEventSlot(val instance: TimelineInstance, val lane: Int, val laneCount: Int)

/** The part of [event] that falls on [epochDay], clamped to that day's midnight boundaries. */
private fun timelineInstance(event: Event, epochDay: Long): TimelineInstance? {
    if (event.allDay || !event.occursOn(epochDay)) return null
    val dayOffset = epochDay - event.date
    val daySeconds = (dayOffset * Event.SECONDS_PER_DAY).toInt()
    val start = (event.start - daySeconds).coerceAtLeast(0)
    if (start >= Event.SECONDS_PER_DAY) return null
    val rawEnd = if (event.hasEndTime) event.end else event.start
    val end = (rawEnd - daySeconds).coerceIn(start, Event.SECONDS_PER_DAY)
    return TimelineInstance(event, start, end)
}

private enum class TimelineDragMode { MOVE, RESIZE_START, RESIZE_END }

private fun snapSeconds(seconds: Int, snapMinutes: Int): Int {
    val step = (snapMinutes * 60).coerceAtLeast(60)
    return (seconds.toFloat() / step).roundToInt() * step
}

private fun defaultDraftTimes(snapMinutes: Int): Pair<Int, Int> {
    val (defaultStart, _) = defaultTimeRange()
    val start = snapSeconds(defaultStart, snapMinutes)
        .coerceIn(0, TimelineEndOfDaySeconds - DefaultDraftDurationSeconds)
    return start to (start + DefaultDraftDurationSeconds)
}

private fun resolveDrag(
    mode: TimelineDragMode,
    start: Int,
    end: Int,
    deltaSeconds: Int,
    snapMinutes: Int,
): Pair<Int, Int> {
    val duration = (end - start).coerceAtLeast(MinEventDurationSeconds)
    return when (mode) {
        TimelineDragMode.MOVE -> {
            val newStart = snapSeconds(start + deltaSeconds, snapMinutes)
                .coerceIn(0, TimelineEndOfDaySeconds - duration)
            newStart to (newStart + duration)
        }

        TimelineDragMode.RESIZE_START -> {
            val newStart = snapSeconds(start + deltaSeconds, snapMinutes)
                .coerceIn(0, end - MinEventDurationSeconds)
            newStart to end
        }

        TimelineDragMode.RESIZE_END -> {
            val newEnd = snapSeconds(end + deltaSeconds, snapMinutes)
                .coerceIn(start + MinEventDurationSeconds, TimelineEndOfDaySeconds)
            start to newEnd
        }
    }
}

private fun autoScrollDelta(
    pointerY: Float,
    viewportHeight: Float,
    margin: Float,
    maxStep: Float,
): Float = when {
    pointerY < margin -> -maxStep * ((margin - pointerY) / margin).coerceIn(0f, 1f)
    pointerY > viewportHeight - margin ->
        maxStep * ((pointerY - (viewportHeight - margin)) / margin).coerceIn(0f, 1f)

    else -> 0f
}

private fun layoutTimelineEvents(instances: List<TimelineInstance>): List<TimelineEventSlot> {
    if (instances.isEmpty()) return emptyList()
    val sorted = instances.sortedWith(compareBy({ it.start }, { it.end }))
    val result = mutableListOf<TimelineEventSlot>()
    val laneEnds = mutableListOf<Int>()
    val cluster = mutableListOf<Pair<TimelineInstance, Int>>()
    var clusterEnd = -1

    fun flushCluster() {
        val laneCount = laneEnds.size
        cluster.forEach { (instance, lane) ->
            result += TimelineEventSlot(instance, lane, laneCount)
        }
        cluster.clear()
        laneEnds.clear()
        clusterEnd = -1
    }

    for (instance in sorted) {
        // An event without an end time is zero-length; give it a lane so simultaneous entries
        // are still shown side by side instead of stacking on top of each other.
        val laneEnd = maxOf(instance.end, instance.start + MinLaneDurationSeconds)
        if (cluster.isNotEmpty() && instance.start >= clusterEnd) flushCluster()
        val lane = laneEnds.indexOfFirst { it <= instance.start }
        if (lane == -1) {
            laneEnds += laneEnd
            cluster += instance to (laneEnds.size - 1)
        } else {
            laneEnds[lane] = laneEnd
            cluster += instance to lane
        }
        clusterEnd = maxOf(clusterEnd, laneEnd)
    }
    if (cluster.isNotEmpty()) flushCluster()
    return result
}

@Composable
private fun DayTimelineView(
    date: LocalDate,
    events: List<Event>,
    eventTags: Map<Long, List<Tag>>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val dayFormatter = rememberDatePatternFormatter("MMMdEEEE")
    val allDayEvents = events.filter { it.allDay }
    val timedEvents = events.filterNot { it.allDay }

    LaunchedEffect(date) {
        val targetSeconds = timedEvents
            .mapNotNull { timelineInstance(it, date.toEpochDay()) }
            .minOfOrNull { it.start }
            ?: if (date == LocalDate.now()) LocalTime.now().toSecondOfDay() else 8 * 3_600
        val targetPx = with(density) { (HourHeight * (targetSeconds / 3_600f)).roundToPx() }
        val initialOffset = (targetPx - with(density) { 48.dp.roundToPx() }).coerceAtLeast(0)
        scrollState.scrollTo(initialOffset)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text(
            text = date.format(dayFormatter),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        HorizontalDivider()
        if (allDayEvents.isNotEmpty()) {
            AllDayEventStrip(events = allDayEvents)
            HorizontalDivider()
        }
        TimelineContent(
            date = date,
            events = timedEvents,
            eventTags = eventTags,
            snapMinutes = DEFAULT_SNAP_INTERVAL_MINUTES,
            draftTimes = null,
            onDraftTimesChange = {},
            scrollState = scrollState,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AllDayEventStrip(events: List<Event>) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        events.forEach { event ->
            Surface(
                shape = MaterialTheme.shapes.small,
                color = colors.surfaceVariant,
            ) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun TimeRangePickerDialog(
    date: LocalDate,
    events: List<Event>,
    eventTags: Map<Long, List<Tag>>,
    snapMinutes: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    val dayFormatter = rememberDatePatternFormatter("MMMdEEEE")
    var draftTimes by remember { mutableStateOf(defaultDraftTimes(snapMinutes)) }

    LaunchedEffect(Unit) {
        val draftTop = with(density) { (HourHeight * (draftTimes.first / 3_600f)).roundToPx() }
        val targetOffset = (draftTop - with(density) { 120.dp.roundToPx() }).coerceAtLeast(0)
        scrollState.scrollTo(targetOffset)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = date.format(dayFormatter),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.select_time),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(8.dp))
                TimelineContent(
                    date = date,
                    events = events,
                    eventTags = eventTags,
                    snapMinutes = snapMinutes,
                    draftTimes = draftTimes,
                    onDraftTimesChange = { draftTimes = it },
                    scrollState = scrollState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DraftActionButton(
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.draft_discard_cd),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        onClick = onDismiss,
                    )
                    DraftActionButton(
                        icon = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.draft_confirm_cd),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        onClick = { onConfirm(draftTimes.first, draftTimes.second) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineContent(
    date: LocalDate,
    events: List<Event>,
    eventTags: Map<Long, List<Tag>>,
    snapMinutes: Int,
    draftTimes: Pair<Int, Int>?,
    onDraftTimesChange: (Pair<Int, Int>) -> Unit,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val slots = remember(events, date) {
        layoutTimelineEvents(
            events.mapNotNull { timelineInstance(it, date.toEpochDay()) },
        )
    }
    val clockFormatter = rememberClockFormatter()
    BoxWithConstraints(modifier = modifier) {
        val panelWidth = maxWidth - TimelineLabelWidth
        val viewportHeightPx = with(LocalDensity.current) { maxHeight.toPx() }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            Column(modifier = Modifier.width(TimelineLabelWidth)) {
                for (hour in 0..23) {
                    Box(
                        modifier = Modifier.height(HourHeight),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Text(
                            text = clockFormatter.format(
                                LocalTime.ofSecondOfDay(hour * 3_600L),
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp, top = 2.dp),
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .width(panelWidth)
                    .height(HourHeight * 24),
            ) {
                for (hour in 1..23) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = HourHeight * hour)
                            .height(1.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            ),
                    )
                }

                slots.forEach { slot ->
                    val instance = slot.instance
                    val event = instance.event
                    val blockHeight =
                        (HourHeight * ((instance.end - instance.start) / 3_600f))
                            .coerceAtLeast(20.dp)
                    val laneWidth = panelWidth / slot.laneCount
                    TimelineEventBlock(
                        event = event,
                        startSeconds = instance.start,
                        endSeconds = instance.end,
                        tags = eventTags[event.id].orEmpty(),
                        modifier = Modifier
                            .offset(
                                x = laneWidth * slot.lane + 1.dp,
                                y = HourHeight * (instance.start / 3_600f),
                            )
                            .width(laneWidth - 2.dp)
                            .height(blockHeight),
                    )
                }

                draftTimes?.let { (draftStart, draftEnd) ->
                    val draftTop = HourHeight * (draftStart / 3_600f)
                    val draftHeight = (HourHeight * ((draftEnd - draftStart) / 3_600f))
                        .coerceAtLeast(20.dp)
                    DraftEventBlock(
                        startSeconds = draftStart,
                        endSeconds = draftEnd,
                        snapMinutes = snapMinutes,
                        scrollState = scrollState,
                        viewportHeightPx = viewportHeightPx,
                        onTimesChange = onDraftTimesChange,
                        modifier = Modifier
                            .offset(x = 2.dp, y = draftTop)
                            .width(panelWidth - 4.dp)
                            .height(draftHeight),
                    )
                }

                if (date == LocalDate.now()) {
                    val nowSeconds = LocalTime.now().toSecondOfDay()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = HourHeight * (nowSeconds / 3_600f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .offset(x = (-3).dp)
                                .size(6.dp)
                                .background(MaterialTheme.colorScheme.error, CircleShape),
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(1.5.dp)
                                .background(MaterialTheme.colorScheme.error),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineEventBlock(
    event: Event,
    startSeconds: Int,
    endSeconds: Int,
    tags: List<Tag>,
    modifier: Modifier = Modifier,
) {
    val tagColors = tags.mapNotNull { tag -> if (tag.color == 0) null else Color(tag.color) }
    val durationSeconds = (endSeconds - startSeconds).coerceAtLeast(0)
    val timeRange = eventTimeRangeLabel(event)
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (tagColors.isNotEmpty()) {
            TagColorStripe(
                colors = tagColors,
                modifier = Modifier
                    .padding(start = 4.dp, top = 6.dp, bottom = 6.dp)
                    .width(TagColorStripeWidth)
                    .fillMaxHeight(),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = if (durationSeconds >= 1_800) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (event.isRecurring) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.series_row_label),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            if (durationSeconds >= 1_800 && timeRange != null) {
                Text(
                    text = timeRange,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun DraftEventBlock(
    startSeconds: Int,
    endSeconds: Int,
    snapMinutes: Int,
    scrollState: ScrollState,
    viewportHeightPx: Float,
    onTimesChange: (Pair<Int, Int>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestStart = rememberUpdatedState(startSeconds)
    val latestEnd = rememberUpdatedState(endSeconds)
    val latestSnap = rememberUpdatedState(snapMinutes)
    val latestChange = rememberUpdatedState(onTimesChange)
    val shape = RoundedCornerShape(8.dp)
    val handleFill = MaterialTheme.colorScheme.surface
    val handleBorder = MaterialTheme.colorScheme.primary
    val clockFormatter = rememberClockFormatter()

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.primary)
            .pointerInput(Unit) { detectTapGestures { } }
            .pointerInput(Unit) {
                coroutineScope {
                    var mode = TimelineDragMode.MOVE
                    var originStart = 0
                    var originEnd = 0
                    var accumulated = 0f
                    var pointerViewportY = 0f
                    val resizeZone = 20.dp.toPx()
                    val pxPerSecond = HourHeight.toPx() / 3_600f
                    val autoScrollMargin = 56.dp.toPx()
                    val maxScrollPerFrame = 16.dp.toPx()

                    fun blockTopInViewport(): Float =
                        HourHeight.toPx() * (latestStart.value / 3_600f) - scrollState.value

                    fun updateTimes() {
                        val deltaSeconds = (accumulated / pxPerSecond).roundToInt()
                        latestChange.value(
                            resolveDrag(
                                mode,
                                originStart,
                                originEnd,
                                deltaSeconds,
                                latestSnap.value,
                            ),
                        )
                    }

                    var scrollJob: Job? = null
                    detectDragGestures(
                        onDragStart = { position ->
                            originStart = latestStart.value
                            originEnd = latestEnd.value
                            accumulated = 0f
                            pointerViewportY = blockTopInViewport() + position.y
                            mode = when {
                                position.y <= resizeZone -> TimelineDragMode.RESIZE_START
                                position.y >= size.height - resizeZone ->
                                    TimelineDragMode.RESIZE_END

                                else -> TimelineDragMode.MOVE
                            }
                            scrollJob?.cancel()
                            scrollJob = launch {
                                while (true) {
                                    val scrollDelta = autoScrollDelta(
                                        pointerY = pointerViewportY,
                                        viewportHeight = viewportHeightPx,
                                        margin = autoScrollMargin,
                                        maxStep = maxScrollPerFrame,
                                    )
                                    if (scrollDelta != 0f) {
                                        val consumed = scrollState.dispatchRawDelta(scrollDelta)
                                        if (consumed != 0f) {
                                            accumulated += consumed
                                            updateTimes()
                                        }
                                    }
                                    withFrameNanos { }
                                }
                            }
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulated += dragAmount.y
                            pointerViewportY = blockTopInViewport() + change.position.y
                            updateTimes()
                        },
                        onDragEnd = {
                            scrollJob?.cancel()
                            scrollJob = null
                        },
                        onDragCancel = {
                            scrollJob?.cancel()
                            scrollJob = null
                        },
                    )
                }
            },
    ) {
        Text(
            text = "${clockFormatter.format(LocalTime.ofSecondOfDay(startSeconds.toLong()))} – " +
                clockFormatter.format(LocalTime.ofSecondOfDay(endSeconds.toLong())),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, end = 28.dp, top = 8.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-6).dp, y = 6.dp)
                .size(14.dp)
                .background(handleFill, CircleShape)
                .border(1.5.dp, handleBorder, CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .offset(x = 6.dp, y = (-6).dp)
                .size(14.dp)
                .background(handleFill, CircleShape)
                .border(1.5.dp, handleBorder, CircleShape),
        )
    }
}

@Composable
private fun DraftActionButton(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = containerColor,
        contentColor = contentColor,
        shadowElevation = 2.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EventRow(
    event: Event,
    tags: List<Tag>,
    onClick: () -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val tagColors = tags.mapNotNull { tag ->
        if (tag.color == 0) null else Color(tag.color)
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDeleteRequested),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
        ) {
            if (tagColors.isNotEmpty()) {
                TagColorStripe(
                    colors = tagColors,
                    modifier = Modifier
                        .padding(start = 8.dp, top = 12.dp, bottom = 12.dp)
                        .width(TagColorStripeWidth)
                        .fillMaxHeight(),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
            ) {
                Text(
                    text = eventScheduleLabel(event),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (event.isRecurring) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.series_row_label),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                if (event.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = event.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    TagPills(tags = tags)
                }
            }
        }
    }
}

@Composable
private fun eventTimeRangeLabel(event: Event): String? {
    if (event.allDay || !event.hasEndTime) return null
    val clockFormatter = rememberClockFormatter()
    val start = clockFormatter.format(LocalTime.ofSecondOfDay(event.start.toLong()))
    val end = clockFormatter.format(LocalTime.ofSecondOfDay(event.endTimeOfDay.toLong()))
    return when (event.endDayOffset) {
        0 -> "$start – $end"
        1 -> stringResource(R.string.event_time_range_next_day, start, end)
        else -> stringResource(
            R.string.event_time_range_days_later,
            start,
            event.endDayOffset,
            end,
        )
    }
}

@Composable
private fun eventScheduleLabel(event: Event): String {
    if (event.allDay) return stringResource(R.string.event_all_day)
    val clockFormatter = rememberClockFormatter()
    val start = clockFormatter.format(LocalTime.ofSecondOfDay(event.start.toLong()))
    return eventTimeRangeLabel(event) ?: start
}

private enum class TimeField { START, END }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AddEventDialog(
    date: LocalDate,
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    initialStartSeconds: Int? = null,
    initialEndSeconds: Int? = null,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        note: String,
        start: LocalTime,
        end: LocalTime,
        endDayOffset: Int,
        allDay: Boolean,
        withAlarm: Boolean,
        reminderMinutes: Int,
        tags: List<Tag>,
        repeat: RecurrenceRule,
    ) -> Unit,
) {
    val (defaultStart, defaultEnd) = remember { defaultTimeRange() }
    val startInit = initialStartSeconds ?: defaultStart
    val endInit = initialEndSeconds ?: defaultEnd
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var startSeconds by rememberSaveable { mutableIntStateOf(startInit) }
    var endSeconds by rememberSaveable { mutableIntStateOf(endInit) }
    var hasEndTime by rememberSaveable { mutableStateOf(true) }
    var allDay by rememberSaveable { mutableStateOf(false) }
    var endDateEpochDay by rememberSaveable {
        mutableLongStateOf(
            date.plusDays(if (endInit <= startInit) 1 else 0).toEpochDay(),
        )
    }
    var timeField by remember { mutableStateOf<TimeField?>(null) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var withAlarm by rememberSaveable { mutableStateOf(false) }
    var reminderMinutes by rememberSaveable { mutableIntStateOf(Event.REMINDER_OFF) }
    var selectedTagIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    var repeatType by rememberSaveable { mutableIntStateOf(Event.REPEAT_NONE) }
    var repeatDayIds by rememberSaveable {
        mutableStateOf(longArrayOf(date.dayOfWeek.value.toLong()))
    }
    var repeatUntilEpochDay by rememberSaveable {
        mutableLongStateOf(date.plusDays(90).toEpochDay())
    }
    var showRepeatDatePicker by remember { mutableStateOf(false) }

    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val onTagToggle: (Tag) -> Unit = { tag ->
        selectedTagIds = when {
            tag.id in selectedTagIds -> selectedTagIds.filter { it != tag.id }.toLongArray()
            selectedTagIds.size < MAX_TAGS_PER_ITEM -> selectedTagIds + tag.id
            else -> selectedTagIds
        }
    }
    val repeatDays = repeatDayIds.map { it.toInt() }.toSet()
    val repeatRule = RecurrenceRule(
        type = repeatType,
        days = repeatDays,
        untilEpochDay = repeatUntilEpochDay,
    )
    val (repeatPlan, recurrenceIssue) = remember(
        date.toEpochDay(),
        repeatType,
        repeatDayIds.toList(),
        repeatUntilEpochDay,
    ) {
        val plan = EventRecurrencePlanner.planOrNull(
            date = date,
            type = repeatType,
            days = repeatDays,
            untilEpochDay = repeatUntilEpochDay,
        )
        plan to EventRecurrencePlanner.issue(
            date = date,
            type = repeatType,
            days = repeatDays,
            untilEpochDay = repeatUntilEpochDay,
            requireWeekdayMatch = false,
            planned = plan,
        )
    }
    val endDayOffset = (endDateEpochDay - date.toEpochDay()).toInt().coerceAtLeast(0)
    val isTimeRangeValid = allDay || !hasEndTime ||
        endDayOffset > 0 || endSeconds > startSeconds
    val isValid = title.isNotBlank() && recurrenceIssue == null && isTimeRangeValid
    val occurrenceCount = repeatPlan?.totalOccurrences ?: 1
    val dayFormatter = rememberDatePatternFormatter("MMMdEEEE")
    val untilDateFormatter = rememberLocalizedDateFormatter()
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
                    text = date.format(dayFormatter),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.add_event),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.event_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.event_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = onTagToggle,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.event_all_day),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (allDay) {
                    Text(
                        text = stringResource(R.string.event_all_day_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeSlotButton(
                            label = stringResource(R.string.start_time_label),
                            secondsOfDay = startSeconds,
                            onClick = { timeField = TimeField.START },
                            modifier = Modifier.weight(1f),
                        )
                        TimeSlotButton(
                            label = stringResource(R.string.end_time_label),
                            secondsOfDay = endSeconds,
                            onClick = { timeField = TimeField.END },
                            enabled = hasEndTime,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.event_has_end_time),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = hasEndTime, onCheckedChange = { hasEndTime = it })
                    }
                    if (!hasEndTime) {
                        Text(
                            text = stringResource(R.string.event_no_end_time_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { showEndDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.event_end_date_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = LocalDate.ofEpochDay(endDateEpochDay)
                                        .format(dayFormatter),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    if (!isTimeRangeValid) {
                        Text(
                            text = stringResource(R.string.invalid_time_range),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
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
                                    text = LocalDate.ofEpochDay(repeatUntilEpochDay)
                                        .format(untilDateFormatter),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        when (recurrenceIssue) {
                            RecurrenceDraftIssue.UNTIL_INVALID -> Text(
                                text = stringResource(R.string.repeat_until_invalid),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )

                            RecurrenceDraftIssue.TOO_MANY_OCCURRENCES -> Text(
                                text = stringResource(
                                    R.string.recurrence_too_large,
                                    MAX_OCCURRENCES_PER_SERIES,
                                ),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )

                            else -> Text(
                                text = stringResource(R.string.series_size_info, occurrenceCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!allDay) {
                    ReminderPicker(
                        reminderMinutes = reminderMinutes,
                        onReminderChange = { reminderMinutes = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.event_alarm_label),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = withAlarm, onCheckedChange = { withAlarm = it })
                    }
                    if (withAlarm) {
                        Text(
                            text = stringResource(R.string.event_alarm_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            val startTime = LocalTime.ofSecondOfDay(startSeconds.toLong())
                            onSave(
                                title.trim(),
                                note.trim(),
                                startTime,
                                if (hasEndTime && !allDay) {
                                    LocalTime.ofSecondOfDay(endSeconds.toLong())
                                } else {
                                    startTime
                                },
                                if (allDay || !hasEndTime) 0 else endDayOffset,
                                allDay,
                                withAlarm && !allDay,
                                if (allDay) Event.REMINDER_OFF else reminderMinutes,
                                selectedTags,
                                repeatRule,
                            )
                        },
                        enabled = isValid,
                    ) {
                        Text(stringResource(R.string.add_event))
                    }
                }
            }
        }
    }

    if (showRepeatDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = repeatUntilEpochDay * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showRepeatDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            repeatUntilEpochDay = millis / 86_400_000L
                        }
                        showRepeatDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRepeatDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndDatePicker) {
        val endDatePickerState = rememberDatePickerState(
            initialSelectedDateMillis = endDateEpochDay * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        endDatePickerState.selectedDateMillis?.let { millis ->
                            val picked = millis / 86_400_000L
                            if (picked >= date.toEpochDay()) {
                                endDateEpochDay = picked.coerceAtMost(
                                    date.toEpochDay() + Event.MAX_EVENT_DAY_SPAN,
                                )
                            }
                        }
                        showEndDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = endDatePickerState)
        }
    }

    timeField?.let { field ->
        TimePickerDialog(
            initialSecondsOfDay = if (field == TimeField.START) startSeconds else endSeconds,
            style = timePickerStyle,
            onDismiss = { timeField = null },
            onConfirm = { picked ->
                if (field == TimeField.START) startSeconds = picked else endSeconds = picked
                timeField = null
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditEventDialog(
    event: Event,
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    initialTags: List<Tag>,
    initialWithAlarm: Boolean,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        note: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        endDayOffset: Int,
        allDay: Boolean,
        withAlarm: Boolean,
        reminderMinutes: Int,
        tags: List<Tag>,
        scope: EventSeriesScope,
    ) -> Unit,
) {
    var title by rememberSaveable(event.id) { mutableStateOf(event.title) }
    var note by rememberSaveable(event.id) { mutableStateOf(event.note) }
    var dateEpochDay by rememberSaveable(event.id) { mutableLongStateOf(event.date) }
    var startSeconds by rememberSaveable(event.id) { mutableIntStateOf(event.start) }
    var endSeconds by rememberSaveable(event.id) { mutableIntStateOf(event.endTimeOfDay) }
    var endDateEpochDay by rememberSaveable(event.id) {
        mutableLongStateOf(event.endDateEpochDay)
    }
    var hasEndTime by rememberSaveable(event.id) { mutableStateOf(event.hasEndTime) }
    var allDay by rememberSaveable(event.id) { mutableStateOf(event.allDay) }
    var withAlarm by rememberSaveable(event.id) { mutableStateOf(initialWithAlarm) }
    var reminderMinutes by rememberSaveable(event.id) {
        mutableIntStateOf(event.reminderMinutes)
    }
    var selectedTagIds by rememberSaveable(event.id) {
        mutableStateOf(initialTags.map { it.id }.toLongArray())
    }
    var scope by rememberSaveable(event.id) {
        mutableStateOf(EventSeriesScope.THIS_EVENT)
    }
    var timeField by remember { mutableStateOf<TimeField?>(null) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    val isSeries = event.seriesId != 0L
    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val onTagToggle: (Tag) -> Unit = { tag ->
        selectedTagIds = when {
            tag.id in selectedTagIds -> selectedTagIds.filter { it != tag.id }.toLongArray()
            selectedTagIds.size < MAX_TAGS_PER_ITEM -> selectedTagIds + tag.id
            else -> selectedTagIds
        }
    }
    val endDayOffset = (endDateEpochDay - dateEpochDay).toInt().coerceAtLeast(0)
    val isTimeRangeValid = allDay || !hasEndTime ||
        endDayOffset > 0 || endSeconds > startSeconds
    val isValid = title.isNotBlank() && isTimeRangeValid
    val dayFormatter = rememberDatePatternFormatter("MMMdEEEE")

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_event),
                    style = MaterialTheme.typography.headlineSmall,
                )
                OutlinedButton(
                    onClick = { showDatePicker = true },
                    enabled = scope != EventSeriesScope.ENTIRE_SERIES,
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
                            text = LocalDate.ofEpochDay(dateEpochDay).format(dayFormatter),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.event_title_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.event_all_day),
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = allDay, onCheckedChange = { allDay = it })
                }
                if (allDay) {
                    Text(
                        text = stringResource(R.string.event_all_day_helper),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TimeSlotButton(
                            label = stringResource(R.string.start_time_label),
                            secondsOfDay = startSeconds,
                            onClick = { timeField = TimeField.START },
                            modifier = Modifier.weight(1f),
                        )
                        TimeSlotButton(
                            label = stringResource(R.string.end_time_label),
                            secondsOfDay = endSeconds,
                            onClick = { timeField = TimeField.END },
                            enabled = hasEndTime,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.event_has_end_time),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = hasEndTime, onCheckedChange = { hasEndTime = it })
                    }
                    if (!hasEndTime) {
                        Text(
                            text = stringResource(R.string.event_no_end_time_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        OutlinedButton(
                            onClick = { showEndDatePicker = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = stringResource(R.string.event_end_date_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = LocalDate.ofEpochDay(endDateEpochDay)
                                        .format(dayFormatter),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    if (!isTimeRangeValid) {
                        Text(
                            text = stringResource(R.string.invalid_time_range),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.event_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = onTagToggle,
                )
                if (!allDay) {
                    ReminderPicker(
                        reminderMinutes = reminderMinutes,
                        onReminderChange = { reminderMinutes = it },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.event_alarm_label),
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = withAlarm, onCheckedChange = { withAlarm = it })
                    }
                    if (withAlarm) {
                        Text(
                            text = stringResource(R.string.event_alarm_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (isSeries) {
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.apply_scope_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    ScopeSelector(scope = scope, onScopeChange = { scope = it })
                    when (scope) {
                        EventSeriesScope.ENTIRE_SERIES -> Text(
                            text = stringResource(R.string.scope_all_series_helper),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        EventSeriesScope.THIS_AND_SUBSEQUENT -> if (dateEpochDay != event.date) {
                            Text(
                                text = stringResource(R.string.scope_subsequent_date_helper),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        EventSeriesScope.THIS_EVENT -> Unit
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            val startTime = LocalTime.ofSecondOfDay(startSeconds.toLong())
                            onSave(
                                title.trim(),
                                note.trim(),
                                LocalDate.ofEpochDay(dateEpochDay),
                                startTime,
                                if (hasEndTime && !allDay) {
                                    LocalTime.ofSecondOfDay(endSeconds.toLong())
                                } else {
                                    startTime
                                },
                                if (allDay || !hasEndTime) 0 else endDayOffset,
                                allDay,
                                withAlarm && !allDay,
                                if (allDay) Event.REMINDER_OFF else reminderMinutes,
                                selectedTags,
                                scope,
                            )
                        },
                        enabled = isValid,
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = dateEpochDay * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val picked = millis / 86_400_000L
                            dateEpochDay = picked
                            endDateEpochDay = endDateEpochDay.coerceIn(
                                picked,
                                picked + Event.MAX_EVENT_DAY_SPAN,
                            )
                        }
                        showDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showEndDatePicker) {
        val endDatePickerState = rememberDatePickerState(
            initialSelectedDateMillis = endDateEpochDay * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        endDatePickerState.selectedDateMillis?.let { millis ->
                            val picked = millis / 86_400_000L
                            if (picked >= dateEpochDay) {
                                endDateEpochDay = picked.coerceAtMost(
                                    dateEpochDay + Event.MAX_EVENT_DAY_SPAN,
                                )
                            }
                        }
                        showEndDatePicker = false
                    },
                ) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        ) {
            DatePicker(state = endDatePickerState)
        }
    }

    timeField?.let { field ->
        TimePickerDialog(
            initialSecondsOfDay = if (field == TimeField.START) startSeconds else endSeconds,
            style = timePickerStyle,
            onDismiss = { timeField = null },
            onConfirm = { picked ->
                if (field == TimeField.START) startSeconds = picked else endSeconds = picked
                timeField = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReminderPicker(
    reminderMinutes: Int,
    onReminderChange: (Int) -> Unit,
) {
    var showCustomDialog by rememberSaveable { mutableStateOf(false) }
    val isCustom = reminderMinutes > 0 && reminderMinutes !in Event.REMINDER_MINUTE_OPTIONS
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.event_reminder_label),
            style = MaterialTheme.typography.titleSmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = reminderMinutes == Event.REMINDER_OFF,
                onClick = { onReminderChange(Event.REMINDER_OFF) },
                label = { Text(stringResource(R.string.reminder_off)) },
            )
            Event.REMINDER_MINUTE_OPTIONS.forEach { minutes ->
                FilterChip(
                    selected = reminderMinutes == minutes,
                    onClick = { onReminderChange(minutes) },
                    label = {
                        Text(stringResource(R.string.reminder_minutes_before, minutes))
                    },
                )
            }
            FilterChip(
                selected = isCustom,
                onClick = { showCustomDialog = true },
                label = {
                    Text(
                        if (isCustom) {
                            stringResource(R.string.reminder_minutes_before, reminderMinutes)
                        } else {
                            stringResource(R.string.reminder_custom)
                        },
                    )
                },
            )
        }
        if (reminderMinutes > 0) {
            Text(
                text = stringResource(R.string.event_reminder_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (showCustomDialog) {
        ReminderCustomDialog(
            initialMinutes = reminderMinutes.takeIf { isCustom },
            onDismiss = { showCustomDialog = false },
            onConfirm = { minutes ->
                onReminderChange(minutes)
                showCustomDialog = false
            },
        )
    }
}

@Composable
private fun ReminderCustomDialog(
    initialMinutes: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initialMinutes?.toString().orEmpty()) }
    val minutes = text.toIntOrNull()
    val valid = minutes != null &&
        minutes in Event.MIN_REMINDER_MINUTES..Event.MAX_REMINDER_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_custom_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { input -> text = input.filter { it in '0'..'9' }.take(5) },
                label = { Text(stringResource(R.string.reminder_custom_minutes_label)) },
                supportingText = {
                    Text(
                        if (text.isNotEmpty() && !valid) {
                            stringResource(
                                R.string.reminder_custom_invalid,
                                Event.MIN_REMINDER_MINUTES,
                                Event.MAX_REMINDER_MINUTES,
                            )
                        } else {
                            stringResource(
                                R.string.reminder_custom_helper,
                                Event.MIN_REMINDER_MINUTES,
                                Event.MAX_REMINDER_MINUTES,
                            )
                        },
                    )
                },
                isError = text.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { minutes?.let(onConfirm) },
                enabled = valid,
            ) {
                Text(stringResource(R.string.ok))
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
private fun TimeSlotButton(
    label: String,
    secondsOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val clockFormatter = rememberClockFormatter()
    OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = clockFormatter.format(LocalTime.ofSecondOfDay(secondsOfDay.toLong())),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

// Defaults for a new event: the next half hour and one hour after that.
private fun defaultTimeRange(): Pair<Int, Int> {
    val now = LocalTime.now().withSecond(0).withNano(0)
    val start = if (now.minute < 30) now.withMinute(30) else now.plusHours(1).withMinute(0)
    return start.toSecondOfDay() to start.plusHours(1).toSecondOfDay()
}

@Composable
private fun ScopeSelector(
    scope: EventSeriesScope,
    onScopeChange: (EventSeriesScope) -> Unit,
) {
    Column {
        EventSeriesScope.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onScopeChange(option) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = scope == option,
                    onClick = { onScopeChange(option) },
                )
                Text(stringResource(option.labelRes()))
            }
        }
    }
}

private fun EventSeriesScope.labelRes(): Int = when (this) {
    EventSeriesScope.THIS_EVENT -> R.string.scope_this_event
    EventSeriesScope.THIS_AND_SUBSEQUENT -> R.string.scope_this_and_subsequent
    EventSeriesScope.ENTIRE_SERIES -> R.string.scope_entire_series
}
