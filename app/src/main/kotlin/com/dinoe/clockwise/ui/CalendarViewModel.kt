package com.dinoe.clockwise.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.EventScope
import com.dinoe.clockwise.data.RecurrenceRule
import com.dinoe.clockwise.data.Tag
import com.dinoe.clockwise.data.recurrenceDates
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

enum class ViewMode { MONTH, DAY }

enum class EventSeriesScope { THIS_EVENT, THIS_AND_SUBSEQUENT, ENTIRE_SERIES }

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val eventDao = (application as ClockwiseApp).database.eventDao()
    private val alarmDao = (application as ClockwiseApp).database.alarmDao()
    private val tagDao = (application as ClockwiseApp).database.tagDao()
    private val writer = (application as ClockwiseApp).scheduleWriter
    private val preferences = ThemePreferences(application)

    val allEvents: StateFlow<List<Event>> = eventDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val alarms: StateFlow<List<Alarm>> = alarmDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allTags: StateFlow<List<Tag>> = tagDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val snapInterval: StateFlow<Int> = preferences.snapInterval
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DEFAULT_SNAP_INTERVAL_MINUTES,
        )

    val timePickerStyle: StateFlow<TimePickerStyle> = preferences.timePickerStyle
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            TimePickerStyle.WHEEL,
        )

    val eventTagMap: StateFlow<Map<Long, List<Tag>>> =
        combine(tagDao.observeEventTagRefs(), tagDao.observeAll()) { refs, tags ->
            val tagsById = tags.associateBy { it.id }
            refs.groupBy { it.eventId }
                .mapValues { (_, eventRefs) ->
                    eventRefs.mapNotNull { tagsById[it.tagId] }.sortedBy { it.name }
                }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    var selectedDate: LocalDate by mutableStateOf(LocalDate.now())
        private set

    var viewMode: ViewMode by mutableStateOf(ViewMode.MONTH)

    fun selectDate(date: LocalDate) {
        selectedDate = date
    }

    suspend fun getEventOnce(id: Long): Event? = eventDao.getById(id)

    fun addEvent(
        title: String,
        note: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        withAlarm: Boolean,
        tags: List<Tag>,
        repeat: RecurrenceRule = RecurrenceRule(Event.REPEAT_NONE),
    ) {
        viewModelScope.launch {
            writer.addEvent(
                title = title,
                note = note,
                date = date,
                start = start,
                end = end,
                withAlarm = withAlarm,
                tags = tags,
                repeat = repeat,
            )
        }
    }

    fun updateEventScoped(
        event: Event,
        scope: EventSeriesScope,
        title: String,
        note: String,
        date: LocalDate,
        start: LocalTime,
        end: LocalTime,
        withAlarm: Boolean,
        tags: List<Tag>,
    ) {
        viewModelScope.launch {
            writer.updateEventScoped(
                event = event,
                scope = scope.toWriterScope(),
                title = title,
                note = note,
                date = date,
                start = start,
                end = end,
                withAlarm = withAlarm,
                tags = tags,
            )
        }
    }

    fun deleteEventScoped(
        event: Event,
        scope: EventSeriesScope,
        deleteAlarm: Boolean = false,
    ) {
        viewModelScope.launch {
            writer.deleteEventScoped(
                event = event,
                scope = scope.toWriterScope(),
                deleteAlarms = deleteAlarm,
            )
        }
    }

    private fun EventSeriesScope.toWriterScope(): EventScope = when (this) {
        EventSeriesScope.THIS_EVENT -> EventScope.THIS_EVENT
        EventSeriesScope.THIS_AND_SUBSEQUENT -> EventScope.THIS_AND_SUBSEQUENT
        EventSeriesScope.ENTIRE_SERIES -> EventScope.ENTIRE_SERIES
    }
}
