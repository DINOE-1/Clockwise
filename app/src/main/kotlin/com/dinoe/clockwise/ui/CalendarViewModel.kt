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
import com.dinoe.clockwise.data.Event
import com.dinoe.clockwise.data.EventScope
import com.dinoe.clockwise.data.RecurrenceRule
import com.dinoe.clockwise.data.ScheduleLimit
import com.dinoe.clockwise.data.ScheduleLimitException
import com.dinoe.clockwise.data.Tag
import kotlinx.coroutines.CancellationException
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
    private val appContext = application.applicationContext

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

    /** One-shot message for a failed write; the screen shows it and calls [consumeError]. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    fun consumeError() {
        errorMessage = null
    }

    fun selectDate(date: LocalDate) {
        selectedDate = date
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
        reminderMinutes: Int = Event.REMINDER_OFF,
    ) {
        launchWrite {
            writer.addEvent(
                title = title,
                note = note,
                date = date,
                start = start,
                end = end,
                withAlarm = withAlarm,
                tags = tags,
                repeat = repeat,
                reminderMinutes = reminderMinutes,
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
        reminderMinutes: Int = event.reminderMinutes,
    ) {
        launchWrite {
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
                reminderMinutes = reminderMinutes,
            )
        }
    }

    fun deleteEventScoped(
        event: Event,
        scope: EventSeriesScope,
        deleteAlarm: Boolean = false,
    ) {
        launchWrite {
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
