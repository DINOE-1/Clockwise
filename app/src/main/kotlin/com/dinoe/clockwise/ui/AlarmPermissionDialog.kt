package com.dinoe.clockwise.ui

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dinoe.clockwise.R
import com.dinoe.clockwise.alarm.AlarmPermissionStatus
import com.dinoe.clockwise.alarm.AlarmPermissions

@Composable
fun AlarmPermissionDialog(
    status: AlarmPermissionStatus,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onRequestNotifications: () -> Unit,
    onTestAlarm: () -> Unit,
) {
    val accent = if (status.hasAnyIssue) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.86f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        imageVector = if (status.hasAnyIssue) {
                            Icons.Filled.Warning
                        } else {
                            Icons.Filled.CheckCircle
                        },
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(28.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.alarm_permission_title),
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text(
                            text = stringResource(
                                if (status.hasAnyIssue) {
                                    R.string.alarm_permission_summary_missing
                                } else {
                                    R.string.alarm_permission_summary_ok
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (status.hasAnyIssue) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.alarm_permission_section_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    AlarmPermissionRows(
                        status = status,
                        onRequestNotifications = onRequestNotifications,
                    )

                    Text(
                        text = stringResource(R.string.alarm_permission_test_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    OutlinedButton(
                        onClick = onTestAlarm,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.alarm_test_button))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onRefresh) {
                            Text(stringResource(R.string.alarm_permission_recheck))
                        }
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.alarm_permission_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AlarmPermissionRows(
    status: AlarmPermissionStatus,
    onRequestNotifications: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PermissionGroup(title = stringResource(R.string.alarm_android_section_title)) {
            PermissionRow(
                title = stringResource(R.string.alarm_notification_permission_title),
                body = stringResource(R.string.alarm_notification_permission_hint),
                granted = status.notificationsGranted,
                actionLabel = if (status.notificationsGranted) {
                    null
                } else {
                    stringResource(R.string.open_settings)
                },
                onAction = if (status.notificationsGranted) null else onRequestNotifications,
                showDivider = false,
            )
            if (status.channelBlocked) {
                PermissionRow(
                    title = stringResource(R.string.alarm_channel_title),
                    body = stringResource(R.string.alarm_channel_blocked),
                    granted = false,
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.appNotificationSettingsIntent(context),
                        )
                    },
                )
            }
            if (status.dndActive) {
                PermissionRow(
                    title = stringResource(R.string.alarm_dnd_title),
                    body = stringResource(R.string.alarm_dnd_body),
                    granted = false,
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = {
                        AlarmPermissions.open(context, AlarmPermissions.dndAccessIntent())
                    },
                )
            }
            PermissionRow(
                title = stringResource(R.string.alarm_exact_permission_title),
                body = stringResource(R.string.alarm_exact_permission_hint),
                granted = status.exactAlarmsGranted,
                actionLabel = if (status.exactAlarmsGranted) {
                    null
                } else {
                    stringResource(R.string.open_settings)
                },
                onAction = if (status.exactAlarmsGranted) {
                    null
                } else {
                    {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.exactAlarmIntent(context),
                        )
                    }
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PermissionRow(
                    title = stringResource(R.string.alarm_full_screen_permission_title),
                    body = stringResource(R.string.alarm_full_screen_permission_hint),
                    granted = status.fullScreenIntentGranted,
                    actionLabel = if (status.fullScreenIntentGranted) {
                        null
                    } else {
                        stringResource(R.string.open_settings)
                    },
                    onAction = if (status.fullScreenIntentGranted) {
                        null
                    } else {
                        {
                            AlarmPermissions.open(
                                context,
                                AlarmPermissions.fullScreenIntentIntent(context),
                            )
                        }
                    },
                )
            }
            PermissionRow(
                title = stringResource(R.string.alarm_battery_permission_title),
                body = stringResource(R.string.alarm_battery_permission_hint),
                granted = status.batteryOptimizationIgnored,
                actionLabel = if (status.batteryOptimizationIgnored) {
                    null
                } else {
                    stringResource(R.string.open_settings)
                },
                onAction = if (status.batteryOptimizationIgnored) {
                    null
                } else {
                    {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.batteryOptimizationIntent(),
                        )
                    }
                },
            )
            if (status.alarmVolumeZero) {
                PermissionRow(
                    title = stringResource(R.string.alarm_alarm_volume_title),
                    body = stringResource(R.string.alarm_alarm_volume_zero),
                    granted = false,
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.soundSettingsIntent(),
                        )
                    },
                )
            }
        }

        if (status.isVivo) {
            PermissionGroup(title = stringResource(R.string.alarm_vivo_section_title)) {
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_autostart_title),
                    body = stringResource(R.string.alarm_vivo_autostart_body),
                    granted = null,
                    actionLabel = stringResource(R.string.alarm_open_app_info),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.appDetailsIntent(context),
                        )
                    },
                    showDivider = false,
                )
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_background_title),
                    body = stringResource(R.string.alarm_vivo_background_body),
                    granted = null,
                    actionLabel = stringResource(R.string.alarm_open_app_info),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.appDetailsIntent(context),
                        )
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_full_screen_title),
                    body = stringResource(R.string.alarm_vivo_full_screen_body),
                    granted = status.fullScreenIntentGranted,
                    actionLabel = if (status.fullScreenIntentGranted) {
                        null
                    } else {
                        stringResource(R.string.open_settings)
                    },
                    onAction = if (status.fullScreenIntentGranted) {
                        null
                    } else {
                        {
                            AlarmPermissions.open(
                                context,
                                AlarmPermissions.fullScreenIntentIntent(context),
                            )
                        }
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_wake_screen_title),
                    body = stringResource(R.string.alarm_vivo_wake_screen_body),
                    granted = status.vivoWakeScreenOff?.let { !it },
                    actionLabel = stringResource(R.string.alarm_open_app_info),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.appDetailsIntent(context),
                        )
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_lock_screen_title),
                    body = stringResource(R.string.alarm_vivo_lock_screen_body),
                    granted = null,
                    actionLabel = stringResource(R.string.alarm_open_notification_settings),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.appNotificationSettingsIntent(context),
                        )
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_pause_unused_title),
                    body = stringResource(R.string.alarm_vivo_pause_unused_body),
                    granted = null,
                    actionLabel = stringResource(R.string.alarm_open_app_info),
                    onAction = {
                        AlarmPermissions.open(
                            context,
                            AlarmPermissions.appDetailsIntent(context),
                        )
                    },
                )
                PermissionRow(
                    title = stringResource(R.string.alarm_vivo_overlay_title),
                    body = stringResource(R.string.alarm_vivo_overlay_body),
                    granted = null,
                    actionLabel = null,
                    onAction = null,
                )
            }
        }
    }
}

@Composable
private fun PermissionGroup(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    showDivider: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                AlarmStatusPill(granted = granted)
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .padding(top = 2.dp),
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
