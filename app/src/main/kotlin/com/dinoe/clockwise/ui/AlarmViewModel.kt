package com.dinoe.clockwise.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.ScheduleLimit
import com.dinoe.clockwise.data.ScheduleLimitException
import com.dinoe.clockwise.data.Tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AlarmViewModel(application: Application) : AndroidViewModel(application) {
    private val alarmDao = (application as ClockwiseApp).database.alarmDao()
    private val eventDao = (application as ClockwiseApp).database.eventDao()
    private val tagDao = (application as ClockwiseApp).database.tagDao()
    private val writer = (application as ClockwiseApp).scheduleWriter
    private val preferences = ThemePreferences(application)
    private val appContext = application.applicationContext

    val alarmExpandStyle: StateFlow<AlarmStackExpandStyle> = preferences.alarmExpandStyle
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            AlarmStackExpandStyle.DRAWER,
        )

    val timePickerStyle: StateFlow<TimePickerStyle> = preferences.timePickerStyle
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TimePickerStyle.WHEEL,
        )

    val alarmGroups: StateFlow<List<AlarmGroup>> =
        combine(alarmDao.observeAll(), eventDao.observeAll()) { alarms, events ->
            buildAlarmGroups(alarms, events)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var expandedGroupKeys: Set<String> by mutableStateOf(emptySet())
        private set

    /** One-shot message for a failed write; the screen shows it and calls [consumeError]. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    fun consumeError() {
        errorMessage = null
    }

    fun toggleGroup(key: String) {
        expandedGroupKeys =
            if (key in expandedGroupKeys) expandedGroupKeys - key else expandedGroupKeys + key
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (error: ScheduleLimitException) {
                reportError(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                reportError(error)
            }
        }
    }

    private fun reportError(error: Throwable) {
        errorMessage = when (error) {
            is ScheduleLimitException -> when (error.kind) {
                ScheduleLimit.OCCURRENCES -> appContext.getString(
                    R.string.write_error_occurrence_limit,
                    error.maximum,
                )

                ScheduleLimit.ACTIVE_ALARMS -> appContext.getString(
                    R.string.write_error_alarm_limit,
                    error.maximum,
                )
            }

            else -> appContext.getString(R.string.write_error_generic)
        }
    }

    val allTags: StateFlow<List<Tag>> = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val alarmTagMap: StateFlow<Map<Long, List<Tag>>> =
        combine(tagDao.observeAlarmTagRefs(), tagDao.observeAll()) { refs, tags ->
            val tagsById = tags.associateBy { it.id }
            refs.groupBy { it.alarmId }
                .mapValues { (_, alarmRefs) ->
                    alarmRefs.mapNotNull { tagsById[it.tagId] }.sortedBy { it.name }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun addAlarm(
        time: Int,
        name: String,
        note: String,
        oneTime: Boolean,
        tags: List<Tag>,
    ) {
        launchWrite {
            writer.addAlarm(
                time = time,
                name = name,
                note = note,
                oneTime = oneTime,
                tags = tags,
            )
        }
    }

    fun addAlarmStack(
        times: List<Int>,
        name: String,
        note: String,
        oneTime: Boolean,
        tags: List<Tag>,
    ) {
        launchWrite {
            writer.addAlarmStack(
                times = times,
                name = name,
                note = note,
                oneTime = oneTime,
                tags = tags,
            )
        }
    }

    fun updateAlarmDetails(
        alarm: Alarm,
        time: Int,
        name: String,
        note: String,
        oneTime: Boolean,
        tags: List<Tag>,
    ) {
        launchWrite {
            writer.updateAlarm(
                alarm = alarm,
                time = time,
                fireDate = alarm.fireDate,
                name = name,
                note = note,
                enabled = alarm.enabled,
                oneTime = oneTime,
                tags = tags,
            )
        }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        launchWrite {
            writer.setAlarmEnabled(alarm, enabled)
        }
    }

    fun deleteAlarm(alarm: Alarm) {
        launchWrite {
            writer.deleteAlarm(alarm)
        }
    }
}
