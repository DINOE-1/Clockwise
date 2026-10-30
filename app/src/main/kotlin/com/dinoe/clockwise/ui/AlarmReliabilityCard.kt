package com.dinoe.clockwise.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dinoe.clockwise.R
import com.dinoe.clockwise.alarm.AlarmPermissionStatus

@Composable
fun AlarmReliabilityCard(
    status: AlarmPermissionStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasIssue = status.hasAnyIssue
    val accent = if (hasIssue) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (hasIssue) Icons.Filled.Warning else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.alarm_permission_title),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    AlarmStatusPill(granted = !hasIssue)
                }
                Text(
                    text = stringResource(
                        if (hasIssue) {
                            R.string.alarm_permission_summary_missing
                        } else {
                            R.string.alarm_permission_summary_ok
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasIssue) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Text(
                text = stringResource(R.string.alarm_permission_check),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
internal fun AlarmStatusPill(
    granted: Boolean?,
    modifier: Modifier = Modifier,
) {
    val container = when (granted) {
        true -> MaterialTheme.colorScheme.secondaryContainer
        false -> MaterialTheme.colorScheme.errorContainer
        null -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (granted) {
        true -> MaterialTheme.colorScheme.onSecondaryContainer
        false -> MaterialTheme.colorScheme.onErrorContainer
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = container,
        contentColor = content,
    ) {
        Text(
            text = stringResource(
                when (granted) {
                    true -> R.string.alarm_permission_granted
                    false -> R.string.alarm_permission_missing
                    null -> R.string.alarm_permission_manual
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
