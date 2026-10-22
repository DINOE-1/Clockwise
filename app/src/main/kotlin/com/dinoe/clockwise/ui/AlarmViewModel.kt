package com.dinoe.clockwise.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Tag
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

    fun toggleGroup(key: String) {
        expandedGroupKeys =
            if (key in expandedGroupKeys) expandedGroupKeys - key else expandedGroupKeys + key
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

    fun addAlarm(time: Int, name: String, note: String, tags: List<Tag>) {
        viewModelScope.launch {
            writer.addAlarm(time = time, name = name, note = note, tags = tags)
        }
    }

    fun updateAlarmDetails(
        alarm: Alarm,
        time: Int,
        name: String,
        note: String,
        tags: List<Tag>,
    ) {
        viewModelScope.launch {
            writer.updateAlarm(
                alarm = alarm,
                time = time,
                fireDate = alarm.fireDate,
                name = name,
                note = note,
                enabled = alarm.enabled,
                tags = tags,
            )
        }
    }

    fun setEnabled(alarm: Alarm, enabled: Boolean) {
        viewModelScope.launch {
            writer.setAlarmEnabled(alarm, enabled)
        }
    }

    fun deleteAlarm(alarm: Alarm) {
        viewModelScope.launch {
            writer.deleteAlarm(alarm)
        }
    }
}
