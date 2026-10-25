package com.dinoe.clockwise.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.media.RingtoneManager
import android.text.format.DateFormat
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.ui.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class AlarmService : Service() {
    companion object {
        const val ACTION_START = "com.dinoe.clockwise.action.START_ALARM"
        const val ACTION_STOP = "com.dinoe.clockwise.action.STOP_ALARM"
        const val ACTION_SNOOZE = "com.dinoe.clockwise.action.SNOOZE_ALARM"

        const val EXTRA_ALARM_ID = "com.dinoe.clockwise.extra.ALARM_ID"
        const val EXTRA_NAME = "com.dinoe.clockwise.extra.ALARM_NAME"
        const val EXTRA_NOTE = "com.dinoe.clockwise.extra.ALARM_NOTE"
        const val EXTRA_TIME = "com.dinoe.clockwise.extra.ALARM_TIME"

        private const val MAX_DURATION_MILLIS = 10 * 60 * 1000L
        private const val TAG = "AlarmService"

        private val _activeAlarmId = MutableStateFlow<Long?>(null)
        val activeAlarmId: StateFlow<Long?> = _activeAlarmId.asStateFlow()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val stopRunnable = Runnable { stopAlarm() }
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val timeFormatter: DateTimeFormatter by lazy {
        val locale = resources.configuration.locales[0]
        val skeleton = if (DateFormat.is24HourFormat(this)) "Hm" else "hm"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }

    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentAlarmId: Long = -1L
    private var currentName: String = ""
    private var currentNote: String = ""
    private var currentTime: Int = 0
    private var currentOneShot: Boolean = false

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withAppLocale())
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> if (matchesActiveAlarm(intent)) stopAlarm()
            ACTION_SNOOZE -> if (matchesActiveAlarm(intent)) snoozeAlarm()
            ACTION_START -> startAlarm(intent)
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun matchesActiveAlarm(intent: Intent): Boolean {
        val requestedId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        return requestedId <= 0L || currentAlarmId <= 0L || requestedId == currentAlarmId
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun startAlarm(intent: Intent) {
        val alarmId = intent.getLongExtra(EXTRA_ALARM_ID, -1L)
        if (alarmId <= 0L) {
            stopSelf()
            return
        }

        stopPlayback()
        currentAlarmId = alarmId
        currentName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        currentNote = intent.getStringExtra(EXTRA_NOTE).orEmpty()
        currentTime = intent.getIntExtra(EXTRA_TIME, 0)
        currentOneShot = intent.getBooleanExtra(AlarmScheduler.EXTRA_ONE_SHOT, false)

        _activeAlarmId.value = alarmId

        val fullScreenIntent = fullScreenIntent(alarmId, currentTime)
        val notification = AlarmNotifier.buildRingingNotification(
            context = this,
            alarmId = alarmId,
            title = currentName.ifBlank { getString(R.string.alarm_ringing_title) },
            body = currentNote.ifBlank { formatTime(currentTime) },
            fullScreenIntent = fullScreenIntent,
        )

        try {
            startForeground(AlarmNotifier.notificationId(alarmId), notification)
        } catch (_: Exception) {
            AlarmNotifier.showStartFailure(
                this,
                Alarm(
                    id = alarmId,
                    time = currentTime,
                    name = currentName,
                    note = currentNote,
                ),
            )
            stopPlayback()
            stopSelf()
            return
        }

        acquireWakeLock()
        startAudio()
        startVibration()
        handler.postDelayed(stopRunnable, MAX_DURATION_MILLIS)

        // Only now is the alarm actually ringing. A one-shot alarm (including a snooze that has
        // just fired) is disabled here, after startForeground succeeded.
        if (currentOneShot) {
            val app = application as ClockwiseApp
            val id = alarmId
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    val dao = app.database.alarmDao()
                    dao.disableIfNotSnoozed(id)
                }
            }
        }
    }

    /**
     * Persists the snooze before the service goes away, so a process restart or reboot restores
     * the snoozed firing time instead of the alarm's original occurrence.
     */
    private fun snoozeAlarm() {
        val alarmId = currentAlarmId
        if (alarmId <= 0L) {
            stopAlarm()
            return
        }
        val name = currentName
        val note = currentNote
        val time = currentTime
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)

        val app = application as ClockwiseApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = app.database.alarmDao()
                val alarm = dao.getById(alarmId)
                if (alarm != null) {
                    val until = System.currentTimeMillis() + 10 * 60_000L
                    val snoozed = alarm.copy(enabled = true, snoozedUntil = until)
                    dao.update(snoozed)
                    val result = AlarmScheduler.scheduleSnooze(
                        applicationContext,
                        snoozed,
                        minutes = 10,
                    )
                    if (result !is ScheduleResult.Scheduled) {
                        dao.update(snoozed.copy(enabled = false, snoozedUntil = null))
                    }
                } else {
                    AlarmScheduler.scheduleSnooze(
                        context = applicationContext,
                        alarm = Alarm(id = alarmId, time = time, name = name, note = note),
                        minutes = 10,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to persist snooze", e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopAlarm() {
        val alarmId = currentAlarmId
        stopPlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (alarmId <= 0L) {
            stopSelf()
            return
        }
        val app = application as ClockwiseApp
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val alarm = app.database.alarmDao().getById(alarmId)
                if (alarm?.oneTime == true) {
                    app.scheduleWriter.deleteAlarm(alarm)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to delete the one-time alarm", e)
            } finally {
                stopSelf()
            }
        }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(stopRunnable)

        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (_: IllegalStateException) {
            }
            player.release()
        }
        mediaPlayer = null

        vibrator?.cancel()
        vibrator = null

        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null

        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null

        _activeAlarmId.value = null
        currentAlarmId = -1L
        currentName = ""
        currentNote = ""
        currentTime = 0
        currentOneShot = false
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Clockwise:AlarmService",
        ).apply {
            acquire(MAX_DURATION_MILLIS)
        }
    }

    private fun startAudio() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { }
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)

        val uri = alarmSoundUri() ?: return
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(this, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            mediaPlayer = player
        } catch (_: Exception) {
            player.release()
            mediaPlayer = null
        }
    }

    private fun startVibration() {
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (!target.hasVibrator()) return

        vibrator = target
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val attributes = VibrationAttributes.Builder()
                .setUsage(VibrationAttributes.USAGE_ALARM)
                .build()
            target.vibrate(effect, attributes)
        } else {
            target.vibrate(effect)
        }
    }

    private fun fullScreenIntent(alarmId: Long, time: Int): PendingIntent =
        PendingIntent.getActivity(
            this,
            AlarmNotifier.notificationId(alarmId),
            Intent(this, AlarmActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_NAME, currentName)
                putExtra(EXTRA_NOTE, currentNote)
                putExtra(EXTRA_TIME, time)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun alarmSoundUri(): Uri? =
        Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

    private fun formatTime(secondsOfDay: Int): String =
        if (secondsOfDay in 0..86_399) {
            timeFormatter.format(LocalTime.ofSecondOfDay(secondsOfDay.toLong()))
        } else {
            getString(R.string.alarm_unknown_time)
        }
}
