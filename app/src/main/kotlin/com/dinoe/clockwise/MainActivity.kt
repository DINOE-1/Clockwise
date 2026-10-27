package com.dinoe.clockwise

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dinoe.clockwise.ui.ApplySystemBarStyle
import com.dinoe.clockwise.ui.ClockwiseTheme
import com.dinoe.clockwise.ui.MainScreen
import com.dinoe.clockwise.ui.ThemeMode
import com.dinoe.clockwise.ui.ThemePreferences
import com.dinoe.clockwise.ui.withAppLocale

class MainActivity : ComponentActivity() {
    private var openEventId by mutableStateOf<Long?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openEventId = intent.openEventId()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val themePreferences = ThemePreferences(applicationContext)
        setContent {
            // Start with the platform theme so the first frame never blocks on DataStore.
            val themeMode by themePreferences.mode.collectAsStateWithLifecycle(
                initialValue = ThemeMode.SYSTEM,
            )
            ClockwiseTheme(themeMode = themeMode) {
                ApplySystemBarStyle(this, themeMode)
                MainScreen(
                    openEventId = openEventId,
                    onOpenEventConsumed = { openEventId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.openEventId()?.let { openEventId = it }
    }

    private fun Intent.openEventId(): Long? {
        if (!hasExtra(EXTRA_OPEN_EVENT_ID)) return null
        return getLongExtra(EXTRA_OPEN_EVENT_ID, -1L).takeIf { it > 0L }
    }

    companion object {
        const val EXTRA_OPEN_EVENT_ID = "com.dinoe.clockwise.extra.OPEN_EVENT_ID"
    }
}
