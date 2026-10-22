package com.dinoe.clockwise.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dinoe.clockwise.R
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val WheelItemHeight = 64.dp
private const val WheelVisibleItems = 3
private const val WheelLoopCopies = 5_000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initialSecondsOfDay: Int,
    style: TimePickerStyle,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var wheelSeconds by rememberSaveable { mutableIntStateOf(initialSecondsOfDay) }
    val clockState = rememberTimePickerState(
        initialHour = (initialSecondsOfDay / 3600) % 24,
        initialMinute = (initialSecondsOfDay % 3600) / 60,
        is24Hour = true,
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.select_time),
                    style = MaterialTheme.typography.titleMedium,
                )
                when (style) {
                    TimePickerStyle.WHEEL -> WheelTimePicker(
                        initialHour = (initialSecondsOfDay / 3600) % 24,
                        initialMinute = (initialSecondsOfDay % 3600) / 60,
                        onTimeChange = { hour, minute ->
                            wheelSeconds = hour * 3600 + minute * 60
                        },
                    )

                    TimePickerStyle.CLOCK -> TimePicker(state = clockState)
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
                            onConfirm(
                                when (style) {
                                    TimePickerStyle.WHEEL -> wheelSeconds
                                    TimePickerStyle.CLOCK ->
                                        clockState.hour * 3600 + clockState.minute * 60
                                },
                            )
                        },
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            }
        }
    }
}

@Composable
private fun WheelTimePicker(
    initialHour: Int,
    initialMinute: Int,
    onTimeChange: (hour: Int, minute: Int) -> Unit,
) {
    var hour by remember { mutableIntStateOf(initialHour) }
    var minute by remember { mutableIntStateOf(initialMinute) }
    val hourState = rememberLazyListState(
        initialFirstVisibleItemIndex = loopStartIndex(24, initialHour),
    )
    val minuteState = rememberLazyListState(
        initialFirstVisibleItemIndex = loopStartIndex(60, initialMinute),
    )
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WheelItemHeight * WheelVisibleItems),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelItemHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WheelPicker(
                count = 24,
                listState = hourState,
                onValueChange = { value, _ ->
                    hour = value
                    onTimeChange(hour, minute)
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = ":",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WheelPicker(
                count = 60,
                listState = minuteState,
                onValueChange = { value, crossings ->
                    minute = value
                    if (crossings != 0) {
                        hour = Math.floorMod(hour + crossings, 24)
                        val target = hourState.firstVisibleItemIndex + crossings
                        scope.launch { hourState.scrollToItem(target) }
                    }
                    onTimeChange(hour, minute)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WheelPicker(
    count: Int,
    listState: LazyListState,
    onValueChange: (value: Int, boundaryCrossings: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val itemHeightPx = with(LocalDensity.current) { WheelItemHeight.toPx() }
    val centeredIndex by remember(listState, itemHeightPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex +
                (listState.firstVisibleItemScrollOffset / itemHeightPx).roundToInt()
        }
    }
    val selectedValue = Math.floorMod(centeredIndex, count)
    val currentOnValueChange by rememberUpdatedState(onValueChange)

    LaunchedEffect(listState) {
        var lastIndex = listState.firstVisibleItemIndex
        snapshotFlow { centeredIndex }
            .distinctUntilChanged()
            .collect { index ->
                if (index != lastIndex) {
                    val crossings = Math.floorDiv(index, count) - Math.floorDiv(lastIndex, count)
                    lastIndex = index
                    currentOnValueChange(Math.floorMod(index, count), crossings)
                }
            }
    }

    LazyColumn(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
        contentPadding = PaddingValues(vertical = WheelItemHeight),
        modifier = modifier.fillMaxSize(),
    ) {
        items(count * WheelLoopCopies) { index ->
            val value = Math.floorMod(index, count)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WheelItemHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = value.toString().padStart(2, '0'),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Light,
                    color = if (value == selectedValue) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun loopStartIndex(count: Int, value: Int): Int {
    val midpoint = count * WheelLoopCopies / 2
    return (midpoint / count) * count + value.coerceIn(0, count - 1)
}
