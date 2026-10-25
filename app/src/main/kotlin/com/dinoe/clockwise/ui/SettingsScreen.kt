package com.dinoe.clockwise.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dinoe.clockwise.R
import com.dinoe.clockwise.alarm.AlarmPermissions
import com.dinoe.clockwise.alarm.AlarmScheduler
import com.dinoe.clockwise.alarm.ScheduleResult
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    openAiSettingsRequest: Boolean = false,
    onOpenAiSettingsRequestHandled: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel(),
) {
    var showAiSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openAiSettingsRequest) {
        if (openAiSettingsRequest) {
            showAiSettings = true
            onOpenAiSettingsRequestHandled()
        }
    }
    if (showAiSettings) {
        AiSettingsScreen(onBack = { showAiSettings = false })
        return
    }

    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val snapInterval by viewModel.snapInterval.collectAsStateWithLifecycle()
    val alarmExpandStyle by viewModel.alarmExpandStyle.collectAsStateWithLifecycle()
    val timePickerStyle by viewModel.timePickerStyle.collectAsStateWithLifecycle()
    val aiConfig by viewModel.aiConfig.collectAsStateWithLifecycle()
    val activeAi = aiConfig?.active()
    val aiSummary = if (activeAi?.hasKey == true) {
        stringResource(
            R.string.ai_settings_summary_format,
            activeAi.preset.displayName,
            activeAi.modelId,
        )
    } else {
        stringResource(R.string.ai_settings_entry_unconfigured)
    }
    var sliderValue by remember(snapInterval) { mutableFloatStateOf(snapInterval.toFloat()) }
    val context = LocalContext.current
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
    val testAlarm: () -> Unit = {
        val message = when (AlarmScheduler.scheduleTestAlarm(context)) {
            is ScheduleResult.Scheduled -> testScheduledMessage
            ScheduleResult.ExactPermissionDenied -> testExactMessage
            else -> testFailedMessage
        }
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    val lightScheme = remember(context) { systemLightScheme(context) }
    val darkScheme = remember(context) { systemDarkScheme(context) }
    val activity = LocalActivity.current
    val selectLanguage: (AppLanguage) -> Unit = { selected ->
        if (selected != language) {
            viewModel.setLanguage(selected)
            activity?.recreate()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AlarmReliabilityCard(
                status = permissionStatus,
                onClick = { showPermissionDialog = true },
            )

            SettingsSectionHeader(stringResource(R.string.settings_section_appearance))
            SettingsCard {
                Column(modifier = Modifier.selectableGroup()) {
                    SelectableOptionRow(
                        label = stringResource(R.string.theme_system),
                        selected = themeMode == ThemeMode.SYSTEM,
                        swatches = listOf(Color(0xFFFEF7FF), Color(0xFF141218)),
                        onSelect = { viewModel.setThemeMode(ThemeMode.SYSTEM) },
                    )
                    SettingsDivider()
                    SelectableOptionRow(
                        label = stringResource(R.string.theme_light),
                        selected = themeMode == ThemeMode.LIGHT,
                        swatches = listOf(lightScheme.surface, lightScheme.primary),
                        onSelect = { viewModel.setThemeMode(ThemeMode.LIGHT) },
                    )
                    SettingsDivider()
                    SelectableOptionRow(
                        label = stringResource(R.string.theme_dark),
                        selected = themeMode == ThemeMode.DARK,
                        swatches = listOf(darkScheme.surface, darkScheme.primary),
                        onSelect = { viewModel.setThemeMode(ThemeMode.DARK) },
                    )
                    SettingsDivider()
                    SelectableOptionRow(
                        label = stringResource(R.string.theme_gruvbox),
                        selected = themeMode == ThemeMode.GRUVBOX,
                        swatches = listOf(
                            Color(0xFF1D2021),
                            Color(0xFFD8A657),
                            Color(0xFF89B482),
                        ),
                        onSelect = { viewModel.setThemeMode(ThemeMode.GRUVBOX) },
                    )
                }
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_language))
            SettingsCard {
                Column(modifier = Modifier.selectableGroup()) {
                    SelectableOptionRow(
                        label = stringResource(R.string.language_system),
                        selected = language == AppLanguage.SYSTEM,
                        onSelect = { selectLanguage(AppLanguage.SYSTEM) },
                    )
                    SettingsDivider()
                    SelectableOptionRow(
                        label = stringResource(R.string.language_traditional_chinese),
                        selected = language == AppLanguage.TRADITIONAL_CHINESE,
                        onSelect = { selectLanguage(AppLanguage.TRADITIONAL_CHINESE) },
                    )
                    SettingsDivider()
                    SelectableOptionRow(
                        label = stringResource(R.string.language_english),
                        selected = language == AppLanguage.ENGLISH,
                        onSelect = { selectLanguage(AppLanguage.ENGLISH) },
                    )
                }
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_time))
            SettingsCard {
                SnapIntervalSetting(
                    value = sliderValue,
                    onValueChange = { sliderValue = it },
                    onValueChangeFinished = {
                        viewModel.setSnapInterval(sliderValue.roundToInt())
                    },
                )
                SettingsDivider()
                SegmentedPreference(
                    title = stringResource(R.string.time_picker_style_label),
                    options = listOf(
                        TimePickerStyle.WHEEL to stringResource(R.string.time_picker_style_wheel),
                        TimePickerStyle.CLOCK to stringResource(R.string.time_picker_style_clock),
                    ),
                    selected = timePickerStyle,
                    onSelect = { viewModel.setTimePickerStyle(it) },
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_alarm))
            SettingsCard {
                SegmentedPreference(
                    title = stringResource(R.string.alarm_expand_style_label),
                    options = listOf(
                        AlarmStackExpandStyle.DRAWER to
                            stringResource(R.string.alarm_expand_style_drawer_short),
                        AlarmStackExpandStyle.FADE to
                            stringResource(R.string.alarm_expand_style_fade_short),
                    ),
                    selected = alarmExpandStyle,
                    onSelect = { viewModel.setAlarmExpandStyle(it) },
                    supportingText = stringResource(
                        if (alarmExpandStyle == AlarmStackExpandStyle.DRAWER) {
                            R.string.alarm_expand_style_drawer_hint
                        } else {
                            R.string.alarm_expand_style_fade_hint
                        },
                    ),
                )
            }

            SettingsSectionHeader(stringResource(R.string.settings_section_ai))
            SettingsCard {
                SettingsActionRow(
                    title = stringResource(R.string.ai_settings_entry_title),
                    subtitle = aiSummary,
                    onClick = { showAiSettings = true },
                )
            }

            SettingsBackupSection()

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showPermissionDialog) {
        AlarmPermissionDialog(
            status = permissionStatus,
            onDismiss = { showPermissionDialog = false },
            onRefresh = { permissionStatus = AlarmPermissions.read(context) },
            onRequestNotifications = requestNotifications,
            onTestAlarm = testAlarm,
        )
    }
}

@Composable
internal fun SettingsSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 4.dp, top = 8.dp)
            .semantics { heading() },
    )
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
internal fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_ai),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SnapIntervalSetting(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.snap_interval_label),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.snap_interval_minutes,
                    value.roundToInt(),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = MIN_SNAP_INTERVAL_MINUTES.toFloat()..MAX_SNAP_INTERVAL_MINUTES.toFloat(),
            steps = (MAX_SNAP_INTERVAL_MINUTES - MIN_SNAP_INTERVAL_MINUTES) /
                SNAP_INTERVAL_STEP_MINUTES - 1,
        )
        Text(
            text = stringResource(R.string.snap_interval_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun <T> SegmentedPreference(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    supportingText: String? = null,
) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (supportingText != null) {
            Text(
                text = supportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SelectableOptionRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    swatches: List<Color> = emptyList(),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (swatches.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                swatches.forEach { color ->
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                    )
                }
            }
        }
    }
}
