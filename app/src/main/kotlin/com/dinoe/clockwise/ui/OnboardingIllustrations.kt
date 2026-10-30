package com.dinoe.clockwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dinoe.clockwise.R
import java.time.LocalDate
import java.time.LocalTime

@Composable
fun CalendarMonthIllustration(modifier: Modifier = Modifier) {
    val today = LocalDate.now()
    val monthLabel = rememberDatePatternFormatter("yMMMM").format(today)
    val weekdayLabels = rememberWeekdayLabels()
    val firstWeekdayOffset = today.withDayOfMonth(1).dayOfWeek.value - 1
    val daysInMonth = today.lengthOfMonth()
    val weekCount = (firstWeekdayOffset + daysInMonth + 6) / 7
    val eventDays = remember(today) {
        listOf(4, 9, 16, 23).filter { it <= daysInMonth }
    }
    val dotColors = listOf(
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.error,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = monthLabel,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            weekdayLabels.forEach { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            repeat(weekCount) { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    repeat(7) { column ->
                        val day = week * 7 + column - firstWeekdayOffset + 1
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.TopCenter,
                        ) {
                            if (day in 1..daysInMonth) {
                                DayCell(
                                    day = day,
                                    isToday = day == today.dayOfMonth,
                                    dotColor = eventDays.indexOf(day)
                                        .takeIf { it >= 0 }
                                        ?.let { dotColors[it % dotColors.size] },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(day: Int, isToday: Boolean, dotColor: Color?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(
                    if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = day.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        Box(
            modifier = Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(dotColor ?: Color.Transparent),
        )
    }
}

@Composable
fun CalendarDayIllustration(modifier: Modifier = Modifier) {
    val clockFormatter = rememberClockFormatter()
    val hours = listOf(8, 9, 10, 11, 12)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
    ) {
        val rowHeight = maxHeight / hours.size.toFloat()

        Column(modifier = Modifier.fillMaxSize()) {
            hours.forEach { hour ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(rowHeight),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = clockFormatter.format(LocalTime.of(hour, 0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .width(52.dp)
                            .padding(top = 2.dp),
                    )
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }

        TimelineEvent(
            title = stringResource(R.string.onboarding_sample_meeting),
            time = clockFormatter.format(LocalTime.of(8, 30)),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .padding(start = 56.dp, end = 4.dp)
                .offset(y = rowHeight * 0.5f)
                .fillMaxWidth()
                .height(rowHeight * 1.4f),
        )
        TimelineEvent(
            title = stringResource(R.string.onboarding_sample_break),
            time = clockFormatter.format(LocalTime.of(11, 0)),
            container = MaterialTheme.colorScheme.tertiaryContainer,
            content = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier
                .padding(start = 56.dp, end = 32.dp)
                .offset(y = rowHeight * 3f)
                .fillMaxWidth()
                .height(rowHeight * 0.9f),
        )
    }
}

@Composable
private fun TimelineEvent(
    title: String,
    time: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = container,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = content,
                maxLines = 1,
            )
            Text(
                text = time,
                style = MaterialTheme.typography.labelSmall,
                color = content.copy(alpha = 0.8f),
                maxLines = 1,
            )
        }
    }
}

@Composable
fun AlarmIllustration(modifier: Modifier = Modifier) {
    val clockFormatter = rememberClockFormatter()
    val weekdayLabels = rememberWeekdayLabels()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        AlarmPreviewCard(
            time = clockFormatter.format(LocalTime.of(7, 30)),
            days = weekdayLabels.take(5).joinToString(" "),
            checked = true,
        )
        AlarmPreviewCard(
            time = clockFormatter.format(LocalTime.of(9, 0)),
            days = weekdayLabels.take(2).joinToString(" "),
            checked = false,
        )
    }
}

@Composable
private fun AlarmPreviewCard(time: String, days: String, checked: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = time,
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (checked) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                Text(
                    text = days,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}

@Composable
fun AiIllustration(modifier: Modifier = Modifier) {
    val clockFormatter = rememberClockFormatter()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_sample_meeting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(4.dp, 18.dp, 18.dp, 18.dp),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_sample_reply),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_sample_meeting),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                    Text(
                        text = clockFormatter.format(LocalTime.of(10, 0)) +
                            " – " +
                            clockFormatter.format(LocalTime.of(11, 0)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun TagsIllustration(modifier: Modifier = Modifier) {
    val tags = listOf(
        stringResource(R.string.onboarding_sample_tag_work) to MaterialTheme.colorScheme.primary,
        stringResource(R.string.onboarding_sample_tag_personal) to
            MaterialTheme.colorScheme.tertiary,
        stringResource(R.string.onboarding_sample_tag_birthday) to
            MaterialTheme.colorScheme.error,
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        tags.forEach { (name, color) ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(color),
                    )
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsIllustration(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        SettingsPreviewGroup {
            SettingsPreviewRow(
                title = stringResource(R.string.settings_section_appearance),
                showDivider = false,
            )
            SettingsPreviewRow(title = stringResource(R.string.settings_section_language))
        }
        SettingsPreviewGroup {
            SettingsPreviewRow(
                title = stringResource(R.string.settings_section_alarm),
                showDivider = false,
                switchOn = true,
            )
            SettingsPreviewRow(title = stringResource(R.string.settings_section_ai))
        }
    }
}

@Composable
private fun SettingsPreviewGroup(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(content = { content() })
    }
}

@Composable
private fun SettingsPreviewRow(
    title: String,
    showDivider: Boolean = true,
    switchOn: Boolean? = null,
) {
    Column {
        if (showDivider) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (switchOn != null) {
                Switch(checked = switchOn, onCheckedChange = null)
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
