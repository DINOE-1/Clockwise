package com.dinoe.clockwise.alarm

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dinoe.clockwise.R
import com.dinoe.clockwise.ui.ClockwiseTheme
import com.dinoe.clockwise.ui.ThemePreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmActivity : ComponentActivity() {
    private var alarmId = -1L
    private var alarmName = ""
    private var alarmNote = ""
    private var alarmTime = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureWindow()
        readAlarm(intent)
        if (alarmId <= 0L) {
            finish()
            return
        }

        val themeMode = runBlocking { ThemePreferences(applicationContext).mode.first() }
        setContent {
            ClockwiseTheme(themeMode) {
                AlarmRingingScreen(
                    name = alarmName,
                    note = alarmNote,
                    time = alarmTime,
                    onStop = { stopAlarm() },
                    onSnooze = { snoozeAlarm() },
                    onFinish = { finish() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newId = intent.getLongExtra(AlarmService.EXTRA_ALARM_ID, -1L)
        if (newId > 0L && newId != alarmId) {
            recreate()
        }
    }

    private fun configureWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            )
        }
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)
    }

    private fun readAlarm(intent: Intent) {
        alarmId = intent.getLongExtra(AlarmService.EXTRA_ALARM_ID, -1L)
        alarmName = intent.getStringExtra(AlarmService.EXTRA_NAME).orEmpty()
        alarmNote = intent.getStringExtra(AlarmService.EXTRA_NOTE).orEmpty()
        alarmTime = intent.getIntExtra(AlarmService.EXTRA_TIME, 0)
    }

    private fun stopAlarm() {
        startService(
            Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_STOP),
        )
        finish()
    }

    private fun snoozeAlarm() {
        startService(
            Intent(this, AlarmService::class.java).setAction(AlarmService.ACTION_SNOOZE),
        )
        finish()
    }
}

@Composable
private fun AlarmRingingScreen(
    name: String,
    note: String,
    time: Int,
    onStop: () -> Unit,
    onSnooze: () -> Unit,
    onFinish: () -> Unit,
) {
    val activeAlarmId by AlarmService.activeAlarmId.collectAsStateWithLifecycle()
    LaunchedEffect(activeAlarmId) {
        if (activeAlarmId == null) onFinish()
    }

    BackHandler { onSnooze() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = if (time in 0..86_399) {
                    timeFormatter.format(LocalTime.ofSecondOfDay(time.toLong()))
                } else {
                    stringResource(R.string.alarm_unknown_time)
                },
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = name.ifBlank { stringResource(R.string.alarm_ringing_title) },
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            if (note.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onStop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
            ) {
                Text(
                    text = stringResource(R.string.alarm_stop),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onSnooze,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                Text(stringResource(R.string.alarm_snooze_minutes, 10))
            }
        }
    }
}

private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
