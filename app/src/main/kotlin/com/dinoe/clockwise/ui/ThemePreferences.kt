package com.dinoe.clockwise.ui

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dinoe.clockwise.data.Alarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT,
    GRUVBOX,
}

enum class AlarmStackExpandStyle {
    DRAWER,
    FADE,
}

enum class TimePickerStyle {
    WHEEL,
    CLOCK,
}

const val DEFAULT_SNAP_INTERVAL_MINUTES = 15
const val MIN_SNAP_INTERVAL_MINUTES = 5
const val MAX_SNAP_INTERVAL_MINUTES = 60
const val SNAP_INTERVAL_STEP_MINUTES = 5

private val Context.themeDataStore by preferencesDataStore(name = "theme")

class ThemePreferences(private val context: Context) {
    private val themeModeKey = stringPreferencesKey("theme_mode")
    private val snapIntervalKey = intPreferencesKey("event_snap_interval")
    private val alarmExpandStyleKey = stringPreferencesKey("alarm_expand_style")
    private val timePickerStyleKey = stringPreferencesKey("time_picker_style")
    private val defaultRingtoneKey = stringPreferencesKey("default_alarm_ringtone")

    val mode: Flow<ThemeMode> = context.themeDataStore.data.map { preferences ->
        preferences[themeModeKey]
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM
    }

    val snapInterval: Flow<Int> = context.themeDataStore.data.map { preferences ->
        (preferences[snapIntervalKey] ?: DEFAULT_SNAP_INTERVAL_MINUTES)
            .coerceIn(MIN_SNAP_INTERVAL_MINUTES, MAX_SNAP_INTERVAL_MINUTES)
    }

    val alarmExpandStyle: Flow<AlarmStackExpandStyle> = context.themeDataStore.data.map { preferences ->
        preferences[alarmExpandStyleKey]
            ?.let { stored -> AlarmStackExpandStyle.entries.firstOrNull { it.name == stored } }
            ?: AlarmStackExpandStyle.DRAWER
    }

    val timePickerStyle: Flow<TimePickerStyle> = context.themeDataStore.data.map { preferences ->
        preferences[timePickerStyleKey]
            ?.let { stored -> TimePickerStyle.entries.firstOrNull { it.name == stored } }
            ?: TimePickerStyle.WHEEL
    }

    // Alarm.DEFAULT_RINGTONE means the platform alarm sound; Alarm.SILENT_RINGTONE means no
    // audio; anything else is a ringtone Uri string.
    val defaultRingtone: Flow<String> = context.themeDataStore.data.map { preferences ->
        preferences[defaultRingtoneKey]?.takeIf { it.isNotBlank() } ?: Alarm.DEFAULT_RINGTONE
    }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }

    suspend fun setSnapInterval(minutes: Int) {
        val clamped = minutes.coerceIn(MIN_SNAP_INTERVAL_MINUTES, MAX_SNAP_INTERVAL_MINUTES)
        val rounded =
            ((clamped + SNAP_INTERVAL_STEP_MINUTES / 2) / SNAP_INTERVAL_STEP_MINUTES) *
                SNAP_INTERVAL_STEP_MINUTES
        context.themeDataStore.edit { preferences ->
            preferences[snapIntervalKey] = rounded
        }
    }

    suspend fun setAlarmExpandStyle(style: AlarmStackExpandStyle) {
        context.themeDataStore.edit { preferences ->
            preferences[alarmExpandStyleKey] = style.name
        }
    }

    suspend fun setTimePickerStyle(style: TimePickerStyle) {
        context.themeDataStore.edit { preferences ->
            preferences[timePickerStyleKey] = style.name
        }
    }

    suspend fun setDefaultRingtone(value: String) {
        context.themeDataStore.edit { preferences ->
            preferences[defaultRingtoneKey] = value
        }
    }
}
