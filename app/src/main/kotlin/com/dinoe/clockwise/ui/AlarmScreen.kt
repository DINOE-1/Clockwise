package com.dinoe.clockwise.ui

import android.Manifest
import android.content.res.Resources
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dinoe.clockwise.R
import com.dinoe.clockwise.alarm.AlarmPermissions
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.ScheduleResult
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.MAX_TAGS_PER_ITEM
import com.dinoe.clockwise.data.Tag
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val StackPeekStep = 10.dp
private const val StackPeekLayers = 3

// Snackbar has no custom duration, so show it indefinitely and dismiss after this long.
private const val SAVED_NOTICE_MILLIS = 1_500L

// Height and chevron animations of a stack; members leave the drawer list after this long.
private const val STACK_ANIMATION_MILLIS = 220

internal sealed interface AlarmListItem {
    val key: String

    data class Single(val alarm: Alarm) : AlarmListItem {
        override val key: String = "alarm:${alarm.id}"
    }

    data class Stack(val group: AlarmGroup) : AlarmListItem {
        override val key: String = "stack:${group.key}"
    }

    data class Member(val groupKey: String, val alarm: Alarm) : AlarmListItem {
        override val key: String = "alarm:${alarm.id}"
    }
}

// One flat list entry per alarm, so a stack's members become their own keyed lazy items instead
// of a column inside the stack card. Drawer rows stay listed while their collapse animation
// runs so they can shrink out instead of disappearing.
internal fun buildAlarmListItems(
    groups: List<AlarmGroup>,
    expandedGroupKeys: Set<String>,
    drawerExpansion: Boolean,
    collapsingGroupKeys: Set<String> = emptySet(),
): List<AlarmListItem> = buildList {
    groups.forEach { group ->
        if (!group.isStack) {
            add(AlarmListItem.Single(group.front))
            return@forEach
        }
        add(AlarmListItem.Stack(group))
        val showMembers = group.key in expandedGroupKeys ||
            (drawerExpansion && group.key in collapsingGroupKeys)
        if (showMembers) {
            group.members.forEach { add(AlarmListItem.Member(group.key, it)) }
        }
    }
}

private fun formatFireDate(
    epochDay: Long,
    withoutYear: DateTimeFormatter,
    withYear: DateTimeFormatter,
): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val formatter = if (date.year == LocalDate.now().year) withoutYear else withYear
    return formatter.format(date)
}

// Renders the countdown with only the units that matter, e.g. "2d 3h" or "45 min".
private fun formatRingCountdown(resources: Resources, countdown: RingCountdown): String = when {
    countdown.days > 0 -> when {
        countdown.hours > 0 && countdown.minutes > 0 -> resources.getString(
            R.string.duration_days_hours_minutes,
            countdown.days,
            countdown.hours,
            countdown.minutes,
        )
        countdown.hours > 0 -> resources.getString(
            R.string.duration_days_hours,
            countdown.days,
            countdown.hours,
        )
        countdown.minutes > 0 -> resources.getString(
            R.string.duration_days_minutes,
            countdown.days,
            countdown.minutes,
        )
        else -> resources.getString(R.string.duration_days, countdown.days)
    }
    countdown.hours > 0 -> if (countdown.minutes > 0) {
        resources.getString(R.string.duration_hours_minutes, countdown.hours, countdown.minutes)
    } else {
        resources.getString(R.string.duration_hours, countdown.hours)
    }
    else -> resources.getString(R.string.duration_minutes, countdown.minutes)
}

@Composable
fun AlarmScreen(
    viewModel: AlarmViewModel = viewModel(),
    onOpenEvent: (Long) -> Unit = {},
) {
    val groups by viewModel.alarmGroups.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val alarmTagMap by viewModel.alarmTagMap.collectAsStateWithLifecycle()
    val expandStyle by viewModel.alarmExpandStyle.collectAsStateWithLifecycle()
    val timePickerStyle by viewModel.timePickerStyle.collectAsStateWithLifecycle()
    val expandedGroupKeys = viewModel.expandedGroupKeys
    val drawerExpansion = expandStyle == AlarmStackExpandStyle.DRAWER
    val context = LocalContext.current
    val resources = LocalResources.current
    val errorMessage = viewModel.errorMessage
    val clockFormatter = rememberClockFormatter()
    val fireDateFormatter = rememberDatePatternFormatter("MMMdEEEE")
    val fireDateWithYearFormatter = rememberDatePatternFormatter("yMMMdEEEE")

    // Keys of the stacks whose drawer rows are growing into or still shrinking out of the list.
    var expandingGroupKeys by remember { mutableStateOf(emptySet<String>()) }
    var collapsingGroupKeys by remember { mutableStateOf(emptySet<String>()) }
    val listItems = remember(groups, expandedGroupKeys, drawerExpansion, collapsingGroupKeys) {
        buildAlarmListItems(groups, expandedGroupKeys, drawerExpansion, collapsingGroupKeys)
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showAddStackDialog by remember { mutableStateOf(false) }
    var addMenuExpanded by remember { mutableStateOf(false) }
    var alarmToDelete by remember { mutableStateOf<Alarm?>(null) }
    var alarmToEdit by remember { mutableStateOf<Alarm?>(null) }
    var alarmToView by remember { mutableStateOf<Alarm?>(null) }

    // Save confirmations appear as a Snackbar so they dismiss themselves after a moment.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = withTimeoutOrNull(SAVED_NOTICE_MILLIS) {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Indefinite,
                )
            }
            if (result == null) {
                snackbarHostState.currentSnackbarData?.dismiss()
            }
        }
    }
    val showSavedNotice: (Boolean, RingCountdown?) -> Unit = { enabled, countdown ->
        val message = if (enabled && countdown != null) {
            resources.getString(
                R.string.alarm_saved_ring_in,
                formatRingCountdown(resources, countdown),
            )
        } else {
            resources.getString(R.string.alarm_saved_disabled)
        }
        showNotice(message)
    }

    LaunchedEffect(errorMessage) {
        val message = errorMessage ?: return@LaunchedEffect
        snackbarHostState.currentSnackbarData?.dismiss()
        snackbarHostState.showSnackbar(message)
        viewModel.consumeError()
    }

    // Drawer rows are listed on their own, so they animate in on expand and stay listed until
    // their shrink finishes; both windows last as long as the row animation.
    val toggleGroup: (String) -> Unit = { key ->
        val collapsing = drawerExpansion && key in expandedGroupKeys
        if (drawerExpansion) {
            if (collapsing) {
                collapsingGroupKeys = collapsingGroupKeys + key
            } else {
                expandingGroupKeys = expandingGroupKeys + key
            }
            scope.launch {
                delay(STACK_ANIMATION_MILLIS.toLong())
                if (collapsing) {
                    collapsingGroupKeys = collapsingGroupKeys - key
                } else {
                    expandingGroupKeys = expandingGroupKeys - key
                }
            }
        }
        viewModel.toggleGroup(key)
    }

    // Bumped on every resume so the permission hints re-evaluate after returning from settings.
    var resumeCount by remember { mutableIntStateOf(0) }
    var permissionStatus by remember { mutableStateOf(AlarmPermissions.read(context)) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumeCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumeCount) {
        permissionStatus = AlarmPermissions.read(context)
    }

    val notificationDeniedMessage = stringResource(R.string.alarm_permission_notification_denied)
    val testScheduledMessage = stringResource(R.string.alarm_test_scheduled)
    val testExactMessage = stringResource(R.string.alarm_test_exact_required)
    val testFailedMessage = stringResource(R.string.alarm_test_failed)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        permissionStatus = AlarmPermissions.read(context)
        if (!granted) {
            Toast.makeText(context, notificationDeniedMessage, Toast.LENGTH_SHORT).show()
        }
    }
    val requestNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            context.startActivity(AlarmPermissions.appNotificationSettingsIntent(context))
        }
    }

    val addMenuRotation by animateFloatAsState(
        targetValue = if (addMenuExpanded) 45f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "addMenuRotation",
    )

    Scaffold(
        topBar = { AlarmTopBar() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AnimatedVisibility(
                    visible = addMenuExpanded,
                    enter = expandVertically(
                        expandFrom = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 180),
                    ) + fadeIn(tween(durationMillis = 180)),
                    exit = shrinkVertically(
                        shrinkTowards = Alignment.Bottom,
                        animationSpec = tween(durationMillis = 140),
                    ) + fadeOut(tween(durationMillis = 140)),
                ) {
                    Column(
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                addMenuExpanded = false
                                showAddStackDialog = true
                            },
                            icon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.List,
                                    contentDescription = null,
                                )
                            },
                            text = { Text(stringResource(R.string.add_alarm_stack)) },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        ExtendedFloatingActionButton(
                            onClick = {
                                addMenuExpanded = false
                                showAddDialog = true
                            },
                            icon = {
                                Icon(Icons.Filled.Notifications, contentDescription = null)
                            },
                            text = { Text(stringResource(R.string.add_alarm_single)) },
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
                FloatingActionButton(onClick = { addMenuExpanded = !addMenuExpanded }) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = stringResource(R.string.add_alarm),
                        modifier = Modifier.rotate(addMenuRotation),
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            AlarmRingBanner(
                groups = groups,
                refreshSignal = resumeCount,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )
            if (permissionStatus.hasAnyIssue) {
                AlarmReliabilityCard(
                    status = permissionStatus,
                    onClick = { showPermissionDialog = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            if (listItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.alarm_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
                    // Drawer rows bring their own gap, so a row that shrinks out takes it with it.
                    verticalArrangement = if (drawerExpansion) {
                        Arrangement.Top
                    } else {
                        Arrangement.spacedBy(8.dp)
                    },
                ) {
                    itemsIndexed(listItems, key = { _, item -> item.key }) { index, item ->
                        // The drawer animates its own height, so let it move the list
                        // directly instead of chasing it with placement animations.
                        val itemModifier = when {
                            !drawerExpansion -> Modifier.animateItem()
                            index == 0 -> Modifier
                            else -> Modifier.padding(top = 8.dp)
                        }
                        when (item) {
                            is AlarmListItem.Single -> AlarmRow(
                                alarm = item.alarm,
                                tags = alarmTagMap[item.alarm.id].orEmpty(),
                                modifier = itemModifier,
                                onClick = {
                                    if (item.alarm.eventId != null) {
                                        alarmToView = item.alarm
                                    } else {
                                        alarmToEdit = item.alarm
                                    }
                                },
                                onToggle = { enabled -> viewModel.setEnabled(item.alarm, enabled) },
                                onDeleteRequested = { alarmToDelete = item.alarm },
                            )

                            is AlarmListItem.Stack -> AlarmStackCard(
                                group = item.group,
                                tags = alarmTagMap[item.group.front.id].orEmpty(),
                                modifier = itemModifier,
                                expanded = item.group.key in expandedGroupKeys,
                                onToggleExpanded = { toggleGroup(item.group.key) },
                                onToggle = { enabled -> viewModel.setEnabled(item.group.front, enabled) },
                                onDeleteRequested = { alarmToDelete = item.group.front },
                            )

                            is AlarmListItem.Member -> AlarmMemberRow(
                                alarm = item.alarm,
                                tags = alarmTagMap[item.alarm.id].orEmpty(),
                                drawer = drawerExpansion,
                                expanded = item.groupKey in expandedGroupKeys,
                                animateIn = item.groupKey in expandingGroupKeys,
                                onClick = {
                                    if (item.alarm.eventId != null) {
                                        alarmToView = item.alarm
                                    } else {
                                        alarmToEdit = item.alarm
                                    }
                                },
                                onToggle = { enabled -> viewModel.setEnabled(item.alarm, enabled) },
                                onDeleteRequested = { alarmToDelete = item.alarm },
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAlarmDialog(
            allTags = allTags,
            timePickerStyle = timePickerStyle,
            onDismiss = { showAddDialog = false },
            onSave = { seconds, name, note, oneTime, ringtone, tags ->
                viewModel.addAlarm(seconds, name, note, oneTime, ringtone, tags)
                showAddDialog = false
                showSavedNotice(true, ringCountdown(Alarm(time = seconds)))
                if (!permissionStatus.notificationsGranted) {
                    requestNotifications()
                }
            },
        )
    }

    if (showAddStackDialog) {
        AddAlarmStackDialog(
            allTags = allTags,
            timePickerStyle = timePickerStyle,
            onDismiss = { showAddStackDialog = false },
            onSave = { times, name, note, oneTime, ringtone, tags ->
                viewModel.addAlarmStack(times, name, note, oneTime, ringtone, tags)
                showAddStackDialog = false
                showNotice(resources.getString(R.string.alarm_stack_saved, times.size))
                if (!permissionStatus.notificationsGranted) {
                    requestNotifications()
                }
            },
        )
    }

    alarmToEdit?.takeIf { it.eventId == null }?.let { alarm ->
        EditAlarmDialog(
            alarm = alarm,
            allTags = allTags,
            initialTags = alarmTagMap[alarm.id].orEmpty(),
            timePickerStyle = timePickerStyle,
            onDismiss = { alarmToEdit = null },
            onSave = { seconds, name, note, oneTime, ringtone, tags ->
                viewModel.updateAlarmDetails(
                    alarm,
                    seconds,
                    name,
                    note,
                    oneTime,
                    ringtone,
                    tags,
                )
                alarmToEdit = null
                showSavedNotice(
                    alarm.enabled,
                    if (alarm.enabled) ringCountdown(alarm.copy(time = seconds)) else null,
                )
            },
        )
    }

    alarmToView?.let { alarm ->
        var viewedRingtone by remember(alarm.id) { mutableStateOf(alarm.ringtone) }
        AlertDialog(
            onDismissRequest = { alarmToView = null },
            title = { Text(alarm.name.ifBlank { stringResource(R.string.alarm_title) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(
                            text = clockFormatter.format(LocalTime.ofSecondOfDay(alarm.time.toLong())),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        alarm.fireDate?.let { epochDay ->
                            Text(
                                text = formatFireDate(
                                    epochDay,
                                    fireDateFormatter,
                                    fireDateWithYearFormatter,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (alarm.note.isNotBlank()) {
                        Text(
                            text = alarm.note,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TagPills(tags = alarmTagMap[alarm.id].orEmpty())
                    RingtoneSettingRow(
                        title = stringResource(R.string.alarm_ringtone_label),
                        value = viewedRingtone,
                        supportingText = if (alarm.stackId != null) {
                            stringResource(R.string.alarm_ringtone_stack_hint)
                        } else {
                            null
                        },
                        onValueChange = { value ->
                            viewedRingtone = value
                            viewModel.setRingtone(alarm, value)
                        },
                    )
                    Text(
                        text = stringResource(R.string.alarm_auto_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        alarm.eventId?.let(onOpenEvent)
                        alarmToView = null
                    },
                ) {
                    Text(stringResource(R.string.edit_source_event))
                }
            },
            dismissButton = {
                TextButton(onClick = { alarmToView = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    alarmToDelete?.let { alarm ->
        val isAuto = alarm.eventId != null
        AlertDialog(
            onDismissRequest = { alarmToDelete = null },
            title = {
                Text(
                    stringResource(
                        if (isAuto) R.string.alarm_auto_delete_warning_title else R.string.delete_alarm,
                    ),
                )
            },
            text = {
                Text(
                    if (isAuto) {
                        stringResource(
                            R.string.alarm_auto_delete_warning_text,
                            alarm.name.ifBlank {
                                clockFormatter.format(LocalTime.ofSecondOfDay(alarm.time.toLong()))
                            },
                        )
                    } else {
                        stringResource(R.string.alarm_delete_confirmation)
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAlarm(alarm)
                        alarmToDelete = null
                    },
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { alarmToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (showPermissionDialog) {
        AlarmPermissionDialog(
            status = permissionStatus,
            onDismiss = { showPermissionDialog = false },
            onRefresh = { permissionStatus = AlarmPermissions.read(context) },
            onRequestNotifications = requestNotifications,
            onTestAlarm = {
                val message = when (AlarmScheduler.scheduleTestAlarm(context)) {
                    is ScheduleResult.Scheduled -> testScheduledMessage
                    ScheduleResult.ExactPermissionDenied -> testExactMessage
                    else -> testFailedMessage
                }
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTopBar() {
    TopAppBar(title = { Text(stringResource(R.string.alarm_title)) })
}

// Owns the minute ticker and the next-ring lookup so only the banner, not the whole list,
// refreshes as time passes. [refreshSignal] restarts the tick when the screen resumes.
@Composable
private fun AlarmRingBanner(
    groups: List<AlarmGroup>,
    refreshSignal: Int,
    modifier: Modifier = Modifier,
) {
    val resources = LocalResources.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(refreshSignal) {
        while (true) {
            val current = LocalDateTime.now()
            now = current
            val millisIntoMinute = current.second * 1_000L + current.nano / 1_000_000L
            delay((60_000L - millisIntoMinute).coerceAtLeast(1_000L))
        }
    }
    val nextRingAlarm = remember(groups, now) { groups.nextRingAlarm(now) } ?: return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                text = stringResource(
                    R.string.alarm_ring_in,
                    formatRingCountdown(resources, ringCountdown(nextRingAlarm, now)),
                ),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun AlarmStackCard(
    group: AlarmGroup,
    tags: List<Tag>,
    modifier: Modifier = Modifier,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = STACK_ANIMATION_MILLIS),
        label = "stackExpansion",
    )
    val peekLayers = minOf(StackPeekLayers, group.alarms.size - 1)
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = StackPeekStep * peekLayers * (1f - expansion)),
        ) {
            // Draw the deepest lip first so the layers in front paint over it. Expanding
            // slides each lip back under the front card.
            for (depth in peekLayers downTo 1) {
                Surface(
                    modifier = Modifier
                        .matchParentSize()
                        .offset {
                            IntOffset(
                                x = 0,
                                y = (StackPeekStep * depth * (1f - expansion)).roundToPx(),
                            )
                        },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shadowElevation = 4.dp,
                ) {}
            }
            AlarmRow(
                alarm = group.front,
                tags = tags,
                stackCount = group.alarms.size,
                stackExpanded = expanded,
                shadowElevation = 6.dp,
                onClick = onToggleExpanded,
                onToggle = onToggle,
                onDeleteRequested = onDeleteRequested,
            )
        }
    }
}

// One stack member. Drawer rows are listed on their own, so each reveals itself; fade rows are
// added and removed with the group and animate their placement instead.
@Composable
private fun LazyItemScope.AlarmMemberRow(
    alarm: Alarm,
    tags: List<Tag>,
    drawer: Boolean,
    expanded: Boolean,
    animateIn: Boolean,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDeleteRequested: () -> Unit,
) {
    if (!drawer) {
        AlarmRow(
            alarm = alarm,
            tags = tags,
            modifier = Modifier
                .padding(start = 12.dp)
                .animateItem(),
            onClick = onClick,
            onToggle = onToggle,
            onDeleteRequested = onDeleteRequested,
        )
        return
    }
    // Only a row added by an expanding stack starts collapsed; a row that merely changed place,
    // or one restored by scrolling, keeps its expanded height.
    var revealed by rememberSaveable { mutableStateOf(!animateIn) }
    LaunchedEffect(expanded) { revealed = expanded }
    AnimatedVisibility(
        visible = revealed,
        enter = expandVertically(
            animationSpec = tween(durationMillis = STACK_ANIMATION_MILLIS),
            expandFrom = Alignment.Top,
        ),
        exit = shrinkVertically(
            animationSpec = tween(durationMillis = STACK_ANIMATION_MILLIS),
            shrinkTowards = Alignment.Top,
        ),
    ) {
        AlarmRow(
            alarm = alarm,
            tags = tags,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp),
            onClick = onClick,
            onToggle = onToggle,
            onDeleteRequested = onDeleteRequested,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlarmRow(
    alarm: Alarm,
    tags: List<Tag>,
    modifier: Modifier = Modifier,
    stackCount: Int? = null,
    stackExpanded: Boolean = false,
    shadowElevation: Dp = 0.dp,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDeleteRequested: () -> Unit,
) {
    val textColor = if (alarm.enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tagColors = tags.mapNotNull { tag ->
        if (tag.color == 0) null else Color(tag.color)
    }
    val clockFormatter = rememberClockFormatter()
    val fireDateFormatter = rememberDatePatternFormatter("MMMdEEEE")
    val fireDateWithYearFormatter = rememberDatePatternFormatter("yMMMdEEEE")
    val fireDateLabel = alarm.fireDate?.let {
        formatFireDate(it, fireDateFormatter, fireDateWithYearFormatter)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onDeleteRequested),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = shadowElevation,
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
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = clockFormatter.format(LocalTime.ofSecondOfDay(alarm.time.toLong())),
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Medium,
                            color = textColor,
                        )
                        if (fireDateLabel != null) {
                            Text(
                                text = fireDateLabel,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                            )
                        }
                        if (alarm.oneTime) {
                            Text(
                                text = stringResource(R.string.alarm_one_time_badge),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                            )
                        }
                    }
                    if (alarm.name.isNotBlank()) {
                        Text(
                            text = alarm.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = textColor,
                        )
                    }
                    if (alarm.note.isNotBlank()) {
                        Text(
                            text = alarm.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (tags.isNotEmpty()) {
                        TagPills(tags = tags, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (stackCount != null) {
                        val chevronRotation by animateFloatAsState(
                            targetValue = if (stackExpanded) 180f else 0f,
                            animationSpec = tween(durationMillis = 220),
                            label = "stackChevron",
                        )
                        Row(
                            modifier = Modifier.padding(top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.alarm_stack_count, stackCount),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = stringResource(
                                    if (stackExpanded) {
                                        R.string.alarm_stack_collapse
                                    } else {
                                        R.string.alarm_stack_expand
                                    },
                                ),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.rotate(chevronRotation),
                            )
                        }
                    }
                }
                if (stackCount == null || stackExpanded) {
                    Switch(checked = alarm.enabled, onCheckedChange = onToggle)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAlarmDialog(
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (Int, String, String, Boolean, String, List<Tag>) -> Unit,
) {
    var secondsOfDay by rememberSaveable { mutableIntStateOf(defaultAlarmTime()) }
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var oneTime by rememberSaveable { mutableStateOf(false) }
    var ringtone by rememberSaveable { mutableStateOf(Alarm.DEFAULT_RINGTONE) }
    var selectedTagIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    var showTimePicker by remember { mutableStateOf(false) }

    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val onTagToggle: (Tag) -> Unit = { tag ->
        selectedTagIds = when {
            tag.id in selectedTagIds -> selectedTagIds.filter { it != tag.id }.toLongArray()
            selectedTagIds.size < MAX_TAGS_PER_ITEM -> selectedTagIds + tag.id
            else -> selectedTagIds
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_alarm),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TimeSlotButton(
                    label = stringResource(R.string.alarm_time_label),
                    secondsOfDay = secondsOfDay,
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.alarm_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.alarm_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OneTimeAlarmToggle(
                    checked = oneTime,
                    onCheckedChange = { oneTime = it },
                )
                RingtoneSettingRow(
                    title = stringResource(R.string.alarm_ringtone_label),
                    value = ringtone,
                    onValueChange = { ringtone = it },
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = onTagToggle,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onSave(
                                secondsOfDay,
                                name.trim(),
                                note.trim(),
                                oneTime,
                                ringtone,
                                selectedTags,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialSecondsOfDay = secondsOfDay,
            style = timePickerStyle,
            onDismiss = { showTimePicker = false },
            onConfirm = { picked ->
                secondsOfDay = picked
                showTimePicker = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddAlarmStackDialog(
    allTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (List<Int>, String, String, Boolean, String, List<Tag>) -> Unit,
) {
    var times by rememberSaveable { mutableStateOf(listOf(defaultAlarmTime())) }
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var oneTime by rememberSaveable { mutableStateOf(false) }
    var ringtone by rememberSaveable { mutableStateOf(Alarm.DEFAULT_RINGTONE) }
    var selectedTagIds by rememberSaveable { mutableStateOf(longArrayOf()) }
    var timePickerIndex by remember { mutableStateOf<Int?>(null) }

    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val onTagToggle: (Tag) -> Unit = { tag ->
        selectedTagIds = when {
            tag.id in selectedTagIds -> selectedTagIds.filter { it != tag.id }.toLongArray()
            selectedTagIds.size < MAX_TAGS_PER_ITEM -> selectedTagIds + tag.id
            else -> selectedTagIds
        }
    }
    val hasDuplicateTimes = times.distinct().size != times.size
    val canSave = times.size >= 2 && !hasDuplicateTimes

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.add_alarm_stack_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stringResource(R.string.alarm_stack_times_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                times.forEachIndexed { index, seconds ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TimeSlotButton(
                            label = stringResource(R.string.alarm_stack_time_number, index + 1),
                            secondsOfDay = seconds,
                            onClick = { timePickerIndex = index },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                times = times.filterIndexed { timeIndex, _ ->
                                    timeIndex != index
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(
                                    R.string.alarm_stack_remove_time,
                                ),
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = { times = times + nextStackTime(times) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.alarm_stack_add_time),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                if (times.size < 2) {
                    Text(
                        text = stringResource(R.string.alarm_stack_min_times),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (hasDuplicateTimes) {
                    Text(
                        text = stringResource(R.string.alarm_stack_duplicate_time),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.alarm_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.alarm_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OneTimeAlarmToggle(
                    checked = oneTime,
                    onCheckedChange = { oneTime = it },
                )
                RingtoneSettingRow(
                    title = stringResource(R.string.alarm_ringtone_label),
                    value = ringtone,
                    supportingText = stringResource(R.string.alarm_ringtone_stack_hint),
                    onValueChange = { ringtone = it },
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = onTagToggle,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                times,
                                name.trim(),
                                note.trim(),
                                oneTime,
                                ringtone,
                                selectedTags,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    timePickerIndex?.let { index ->
        TimePickerDialog(
            initialSecondsOfDay = times.getOrElse(index) { defaultAlarmTime() },
            style = timePickerStyle,
            onDismiss = { timePickerIndex = null },
            onConfirm = { picked ->
                if (index in times.indices) {
                    times = times.toMutableList().also { it[index] = picked }
                }
                timePickerIndex = null
            },
        )
    }
}

@Composable
private fun EditAlarmDialog(
    alarm: Alarm,
    allTags: List<Tag>,
    initialTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (Int, String, String, Boolean, String, List<Tag>) -> Unit,
) {
    var secondsOfDay by rememberSaveable(alarm.id) { mutableIntStateOf(alarm.time) }
    var name by rememberSaveable(alarm.id) { mutableStateOf(alarm.name) }
    var note by rememberSaveable(alarm.id) { mutableStateOf(alarm.note) }
    var oneTime by rememberSaveable(alarm.id) { mutableStateOf(alarm.oneTime) }
    var ringtone by rememberSaveable(alarm.id) { mutableStateOf(alarm.ringtone) }
    var selectedTagIds by rememberSaveable(alarm.id) {
        mutableStateOf(initialTags.map { it.id }.toLongArray())
    }
    var showTimePicker by remember { mutableStateOf(false) }

    val selectedTags = allTags.filter { it.id in selectedTagIds }
    val onTagToggle: (Tag) -> Unit = { tag ->
        selectedTagIds = when {
            tag.id in selectedTagIds -> selectedTagIds.filter { it != tag.id }.toLongArray()
            selectedTagIds.size < MAX_TAGS_PER_ITEM -> selectedTagIds + tag.id
            else -> selectedTagIds
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.edit_alarm),
                    style = MaterialTheme.typography.headlineSmall,
                )
                TimeSlotButton(
                    label = stringResource(R.string.alarm_time_label),
                    secondsOfDay = secondsOfDay,
                    onClick = { showTimePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.alarm_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.alarm_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OneTimeAlarmToggle(
                    checked = oneTime,
                    onCheckedChange = { oneTime = it },
                )
                RingtoneSettingRow(
                    title = stringResource(R.string.alarm_ringtone_label),
                    value = ringtone,
                    supportingText = if (alarm.stackId != null) {
                        stringResource(R.string.alarm_ringtone_stack_hint)
                    } else {
                        null
                    },
                    onValueChange = { ringtone = it },
                )
                TagPicker(
                    allTags = allTags,
                    selectedTags = selectedTags,
                    onToggle = onTagToggle,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.cancel))
                    }
                    TextButton(
                        onClick = {
                            onSave(
                                secondsOfDay,
                                name.trim(),
                                note.trim(),
                                oneTime,
                                ringtone,
                                selectedTags,
                            )
                        },
                    ) {
                        Text(stringResource(R.string.save))
                    }
                }
            }
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            initialSecondsOfDay = secondsOfDay,
            style = timePickerStyle,
            onDismiss = { showTimePicker = false },
            onConfirm = { picked ->
                secondsOfDay = picked
                showTimePicker = false
            },
        )
    }
}

@Composable
private fun OneTimeAlarmToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.alarm_one_time_label),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = stringResource(R.string.alarm_one_time_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TimeSlotButton(
    label: String,
    secondsOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clockFormatter = rememberClockFormatter()
    OutlinedButton(onClick = onClick, modifier = modifier) {
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

// Default for a new alarm: the next five-minute mark.
private fun defaultAlarmTime(): Int {
    val now = LocalTime.now()
    val minutes = ((now.minute / 5) + 1) * 5
    return now.withSecond(0).withNano(0).withMinute(0).plusMinutes(minutes.toLong()).toSecondOfDay()
}

private const val STACK_TIME_STEP_SECONDS = 5 * 60
private const val SECONDS_PER_DAY = 24 * 60 * 60

// Next suggested stack slot: five minutes after the latest time, skipping times already used.
private fun nextStackTime(times: List<Int>): Int {
    val latest = times.maxOrNull() ?: defaultAlarmTime()
    var candidate = (latest + STACK_TIME_STEP_SECONDS) % SECONDS_PER_DAY
    var attempts = 0
    while (candidate in times && attempts < SECONDS_PER_DAY / STACK_TIME_STEP_SECONDS) {
        candidate = (candidate + STACK_TIME_STEP_SECONDS) % SECONDS_PER_DAY
        attempts++
    }
    return candidate
}
