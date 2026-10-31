package com.dinoe.clockwise.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.ai.AiConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ThemePreferences(application)
    private val languagePreferences = LanguagePreferences(application)
    private val aiRepository = (application as ClockwiseApp).aiRepository

    private val _language = MutableStateFlow(languagePreferences.language)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    val themeMode: StateFlow<ThemeMode> = preferences.mode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val snapInterval: StateFlow<Int> = preferences.snapInterval
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            DEFAULT_SNAP_INTERVAL_MINUTES,
        )

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

    val defaultRingtone: StateFlow<String> = preferences.defaultRingtone
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            com.dinoe.clockwise.data.Alarm.DEFAULT_RINGTONE,
        )

    val aiConfig: StateFlow<AiConfig?> = aiRepository.config
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            preferences.setMode(mode)
        }
    }

    fun setLanguage(language: AppLanguage) {
        languagePreferences.setLanguage(language)
        _language.value = language
    }

    fun setSnapInterval(minutes: Int) {
        viewModelScope.launch {
            preferences.setSnapInterval(minutes)
        }
    }

    fun setAlarmExpandStyle(style: AlarmStackExpandStyle) {
        viewModelScope.launch {
            preferences.setAlarmExpandStyle(style)
        }
    }

    fun setTimePickerStyle(style: TimePickerStyle) {
        viewModelScope.launch {
            preferences.setTimePickerStyle(style)
        }
    }

    fun setDefaultRingtone(value: String) {
        viewModelScope.launch {
            preferences.setDefaultRingtone(value)
        }
    }
}
