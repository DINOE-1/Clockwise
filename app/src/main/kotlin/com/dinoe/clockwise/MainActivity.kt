package com.dinoe.clockwise

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dinoe.clockwise.ui.ClockwiseTheme
import com.dinoe.clockwise.ui.MainScreen
import com.dinoe.clockwise.ui.ThemePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val themePreferences = ThemePreferences(applicationContext)
        val initialThemeMode = runBlocking { themePreferences.mode.first() }
        setContent {
            val themeMode by themePreferences.mode.collectAsStateWithLifecycle(initialThemeMode)
            ClockwiseTheme(themeMode = themeMode) {
                MainScreen()
            }
        }
    }
}
