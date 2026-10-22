package com.dinoe.clockwise.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
import java.util.Locale

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val fireDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.TAIWAN)
private val fireDateWithYearFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.TAIWAN)

private val StackPeekStep = 10.dp
private const val StackPeekLayers = 3

// Snackbar has no custom duration, so show it indefinitely and dismiss after this long.
private const val SAVED_NOTICE_MILLIS = 1_500L

private sealed interface AlarmListItem {
    val key: String

    data class Single(val alarm: Alarm) : AlarmListItem {
        override val key: String = "alarm:${alarm.id}"
    }

    data class Stack(val group: AlarmGroup) : AlarmListItem {
        override val key: String = "stack:${group.key}"
    }

    data class Member(val alarm: Alarm) : AlarmListItem {
        override val key: String = "alarm:${alarm.id}"
    }
}

private fun formatFireDate(epochDay: Long): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val formatter =
        if (date.year == LocalDate.now().year) fireDateFormatter else fireDateWithYearFormatter
    return formatter.format(date)
}

// Renders the countdown with only the units that matter, e.g. "2天3小時", "45分".
private fun formatRingCountdown(context: Context, countdown: RingCountdown): String = when {
    countdown.days > 0 -> when {
        countdown.hours > 0 && countdown.minutes > 0 -> context.getString(
            R.string.duration_days_hours_minutes,
            countdown.days,
            countdown.hours,
            countdown.minutes,
        )
        countdown.hours > 0 -> context.getString(
            R.string.duration_days_hours,
            countdown.days,
            countdown.hours,
        )
        countdown.minutes > 0 -> context.getString(
            R.string.duration_days_minutes,
            countdown.days,
            countdown.minutes,
        )
        else -> context.getString(R.string.duration_days, countdown.days)
    }
    countdown.hours > 0 -> if (countdown.minutes > 0) {
        context.getString(R.string.duration_hours_minutes, countdown.hours, countdown.minutes)
    } else {
        context.getString(R.string.duration_hours, countdown.hours)
    }
    else -> context.getString(R.string.duration_minutes, countdown.minutes)
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

    val listItems = remember(groups, expandedGroupKeys, drawerExpansion) {
        buildList {
            groups.forEach { group ->
                if (group.isStack) {
                    add(AlarmListItem.Stack(group))
                    if (!drawerExpansion && group.key in expandedGroupKeys) {
                        group.members.forEach { add(AlarmListItem.Member(it)) }
                    }
                } else {
                    add(AlarmListItem.Single(group.front))
                }
            }
        }
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var alarmToDelete by remember { mutableStateOf<Alarm?>(null) }
    var alarmToEdit by remember { mutableStateOf<Alarm?>(null) }
    var alarmToView by remember { mutableStateOf<Alarm?>(null) }

    // Save confirmations appear as a Snackbar so they dismiss themselves after a moment.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showSavedNotice: (Boolean, RingCountdown?) -> Unit = { enabled, countdown ->
        val message = if (enabled && countdown != null) {
            context.getString(
                R.string.alarm_saved_ring_in,
                formatRingCountdown(context, countdown),
            )
        } else {
            context.getString(R.string.alarm_saved_disabled)
        }
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

    // Drives the top countdown banner and refreshes it as time passes.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            val millisIntoMinute = now.second * 1_000L + now.nano / 1_000_000L
            delay((60_000L - millisIntoMinute).coerceAtLeast(1_000L))
        }
    }
    val nextRingAlarm = remember(groups, now) { groups.nextRingAlarm(now) }

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
        now = LocalDateTime.now()
    }

    val notificationDeniedMessage = stringResource(R.string.alarm_permission_notification_denied)
    val testScheduledMessage = stringResource(R.string.alarm_test_scheduled)
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

    Scaffold(
        topBar = { AlarmTopBar() },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_alarm))
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize(),
        ) {
            nextRingAlarm?.let { next ->
                AlarmRingBanner(
                    text = stringResource(
                        R.string.alarm_ring_in,
                        formatRingCountdown(context, ringCountdown(next, now)),
                    ),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                )
            }
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
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(listItems, key = { it.key }) { item ->
                        // The drawer animates its own height, so let it move the list
                        // directly instead of chasing it with placement animations.
                        val itemModifier = if (drawerExpansion) Modifier else Modifier.animateItem()
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

                            is AlarmListItem.Stack -> {
                                val group = item.group
                                AlarmStackCard(
                                    group = group,
                                    tags = alarmTagMap[group.front.id].orEmpty(),
                                    modifier = itemModifier,
                                    expanded = group.key in expandedGroupKeys,
                                    onToggleExpanded = { viewModel.toggleGroup(group.key) },
                                    onToggle = { enabled -> viewModel.setEnabled(group.front, enabled) },
                                    onDeleteRequested = { alarmToDelete = group.front },
                                    memberContent = if (drawerExpansion) {
                                        {
                                            group.members.forEach { member ->
                                                key(member.id) {
                                                    AlarmRow(
                                                        alarm = member,
                                                        tags = alarmTagMap[member.id].orEmpty(),
                                                        onClick = {
                                                            if (member.eventId != null) {
                                                                alarmToView = member
                                                            } else {
                                                                alarmToEdit = member
                                                            }
                                                        },
                                                        onToggle = { enabled ->
                                                            viewModel.setEnabled(member, enabled)
                                                        },
                                                        onDeleteRequested = {
                                                            alarmToDelete = member
                                                        },
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }

                            is AlarmListItem.Member -> AlarmRow(
                                alarm = item.alarm,
                                tags = alarmTagMap[item.alarm.id].orEmpty(),
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .animateItem(),
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
            onSave = { seconds, name, note, tags ->
                viewModel.addAlarm(seconds, name, note, tags)
                showAddDialog = false
                showSavedNotice(true, ringCountdown(Alarm(time = seconds)))
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
            onSave = { seconds, name, note, tags ->
                viewModel.updateAlarmDetails(alarm, seconds, name, note, tags)
                alarmToEdit = null
                showSavedNotice(
                    alarm.enabled,
                    if (alarm.enabled) ringCountdown(alarm.copy(time = seconds)) else null,
                )
            },
        )
    }

    alarmToView?.let { alarm ->
        AlertDialog(
            onDismissRequest = { alarmToView = null },
            title = { Text(alarm.name.ifBlank { stringResource(R.string.alarm_title) }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column {
                        Text(
                            text = timeFormatter.format(LocalTime.ofSecondOfDay(alarm.time.toLong())),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        alarm.fireDate?.let { epochDay ->
                            Text(
                                text = formatFireDate(epochDay),
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
                                timeFormatter.format(LocalTime.ofSecondOfDay(alarm.time.toLong()))
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
                if (AlarmScheduler.scheduleTestAlarm(context)) {
                    Toast.makeText(context, testScheduledMessage, Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlarmTopBar() {
    TopAppBar(title = { Text(stringResource(R.string.alarm_title)) })
}

@Composable
private fun AlarmRingBanner(text: String, modifier: Modifier = Modifier) {
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
                text = text,
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
    memberContent: (@Composable () -> Unit)? = null,
) {
    val expansion by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
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
                        .offset(y = StackPeekStep * depth * (1f - expansion)),
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
        if (memberContent != null) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(
                    animationSpec = tween(durationMillis = 220),
                    expandFrom = Alignment.Top,
                ),
                exit = shrinkVertically(
                    animationSpec = tween(durationMillis = 220),
                    shrinkTowards = Alignment.Top,
                ),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    memberContent()
                }
            }
        }
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
    val fireDateLabel = alarm.fireDate?.let(::formatFireDate)
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
                            text = timeFormatter.format(LocalTime.ofSecondOfDay(alarm.time.toLong())),
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
    onSave: (Int, String, String, List<Tag>) -> Unit,
) {
    var secondsOfDay by rememberSaveable { mutableIntStateOf(defaultAlarmTime()) }
    var name by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
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
                        onClick = { onSave(secondsOfDay, name.trim(), note.trim(), selectedTags) },
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
private fun EditAlarmDialog(
    alarm: Alarm,
    allTags: List<Tag>,
    initialTags: List<Tag>,
    timePickerStyle: TimePickerStyle,
    onDismiss: () -> Unit,
    onSave: (Int, String, String, List<Tag>) -> Unit,
) {
    var secondsOfDay by rememberSaveable(alarm.id) { mutableIntStateOf(alarm.time) }
    var name by rememberSaveable(alarm.id) { mutableStateOf(alarm.name) }
    var note by rememberSaveable(alarm.id) { mutableStateOf(alarm.note) }
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
                            onSave(secondsOfDay, name.trim(), note.trim(), selectedTags)
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
private fun TimeSlotButton(
    label: String,
    secondsOfDay: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
                text = timeFormatter.format(LocalTime.ofSecondOfDay(secondsOfDay.toLong())),
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
