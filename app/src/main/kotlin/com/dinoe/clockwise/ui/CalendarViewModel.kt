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
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ViewMode { MONTH, DAY }

enum class EventSeriesScope { THIS_EVENT, THIS_AND_SUBSEQUENT, ENTIRE_SERIES }

class CalendarViewModel(application: Application) : AndroidViewModel(application) {
    private val eventDao = (application as ClockwiseApp).database.eventDao()
    private val alarmDao = (application as ClockwiseApp).database.alarmDao()
    private val tagDao = (application as ClockwiseApp).database.tagDao()
    private val writer = (application as ClockwiseApp).scheduleWriter
    private val preferences = ThemePreferences(application)
    private val appContext = application.applicationContext

    private val mutableSelectedDate = MutableStateFlow(LocalDate.now())
    private val mutableDisplayedMonth = MutableStateFlow(YearMonth.from(mutableSelectedDate.value))

    val selectedDate: StateFlow<LocalDate> = mutableSelectedDate.asStateFlow()

    val displayedMonth: StateFlow<YearMonth> = mutableDisplayedMonth.asStateFlow()

    /**
     * Events of every day the calendar can currently show, indexed by epoch day.
     *
     * The queried range is the visible month grid plus the selected day, so navigating to another
     * month keeps the selected day's list loaded while the index stays bounded.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val eventsByDay: StateFlow<Map<Long, List<Event>>> =
        combine(mutableDisplayedMonth, mutableSelectedDate) { month, date ->
            val grid = monthGridRange(month)
            val day = date.toEpochDay()
            minOf(grid.first, day)..maxOf(grid.last, day)
        }
            // Selecting another day inside the loaded grid keeps the same range; do not resubscribe
            // the database query (and drop the derived map) for an unchanged range.
            .distinctUntilChanged()
            .flatMapLatest { range ->
                eventDao.observeBetween(range.first, range.last)
                    .map { events -> events to range }
            }
            .map { (events, range) -> buildEventsByDay(events, range) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

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

    var viewMode: ViewMode by mutableStateOf(ViewMode.MONTH)

    /** One-shot message for a failed write; the screen shows it and calls [consumeError]. */
    var errorMessage: String? by mutableStateOf(null)
        private set

    fun consumeError() {
        errorMessage = null
    }

    fun selectDate(date: LocalDate) {
        // Re-selecting the current day must keep the month the user navigated to, so the month
        // only follows an actual selection change.
        if (mutableSelectedDate.value == date) return
        mutableSelectedDate.value = date
        mutableDisplayedMonth.value = YearMonth.from(date)
    }

    fun showMonth(month: YearMonth) {
        mutableDisplayedMonth.value = month
    }

    fun selectToday() {
        val today = LocalDate.now()
        mutableSelectedDate.value = today
        mutableDisplayedMonth.value = YearMonth.from(today)
    }

    private fun launchWrite(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                // Room work and AlarmManager effects both leave the main thread here; only the
                // final result and error reporting return to the ViewModel's Main context.
                withContext(Dispatchers.IO) { block() }
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

    suspend fun getSeriesEvents(seriesId: Long): List<Event> = eventDao.getBySeries(seriesId)

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
        endDayOffset: Int = 0,
        allDay: Boolean = false,
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
                endDayOffset = endDayOffset,
                allDay = allDay,
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
        endDayOffset: Int = event.endDayOffset,
        allDay: Boolean = event.allDay,
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
                endDayOffset = endDayOffset,
                allDay = allDay,
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

/** Epoch days of [month]'s Monday-first grid, including leading and trailing days. */
internal fun monthGridRange(month: YearMonth): LongRange {
    val firstOfMonth = month.atDay(1)
    val gridStart = firstOfMonth.minusDays((firstOfMonth.dayOfWeek.value - 1).toLong())
    // Most months need five rows, some six.
    val weekRows = (((month.atEndOfMonth().toEpochDay() - gridStart.toEpochDay()) / 7) + 1)
        .coerceAtLeast(5L)
    val start = gridStart.toEpochDay()
    return start until start + weekRows * 7
}

/**
 * Expands [events] over every day they cover inside [range] and sorts each day's list with the
 * month and day view order.
 */
internal fun buildEventsByDay(events: List<Event>, range: LongRange): Map<Long, List<Event>> {
    if (events.isEmpty()) return emptyMap()
    val byDay = HashMap<Long, MutableList<Event>>()
    events.forEach { event ->
        var day = maxOf(event.date, range.first)
        val lastDay = minOf(event.lastCoveredEpochDay, range.last)
        while (day <= lastDay) {
            byDay.getOrPut(day) { mutableListOf() } += event
            day++
        }
    }
    return byDay.mapValues { (day, dayEvents) -> dayEvents.sortedWith(eventDayComparator(day)) }
}

/** Day order used by the month and day views: all-day first, then start, then title. */
internal fun eventDayComparator(epochDay: Long): Comparator<Event> =
    compareByDescending<Event> { it.allDay }
        .thenBy { eventStartOnDay(it, epochDay) }
        .thenBy { it.title }

/** Start second shown for [event] on [epochDay]; the tail of a cross-day event starts at 00:00. */
internal fun eventStartOnDay(event: Event, epochDay: Long): Int {
    if (event.allDay) return 0
    val daySeconds = ((epochDay - event.date) * Event.SECONDS_PER_DAY).toInt()
    return (event.start - daySeconds).coerceAtLeast(0)
}
