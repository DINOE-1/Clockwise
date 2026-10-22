package com.dinoe.clockwise.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

data class AlarmPermissionStatus(
    val notificationsGranted: Boolean,
    val exactAlarmsGranted: Boolean,
    val fullScreenIntentGranted: Boolean,
    val batteryOptimizationIgnored: Boolean,
    val overlayGranted: Boolean,
    val alarmVolumeZero: Boolean,
    val isVivo: Boolean,
    val vivoWakeScreenOff: Boolean?,
) {
    val hasCriticalMissing: Boolean
        get() = !notificationsGranted || !exactAlarmsGranted || !fullScreenIntentGranted

    val hasAnyIssue: Boolean
        get() = hasCriticalMissing || alarmVolumeZero || (isVivo && !batteryOptimizationIgnored)
}

object AlarmPermissions {
    fun read(context: Context): AlarmPermissionStatus {
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val notificationsGranted =
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
                notificationManager.areNotificationsEnabled()

        val exactAlarmsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        val fullScreenIntentGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                notificationManager.canUseFullScreenIntent()

        val batteryOptimizationIgnored =
            context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName)

        val alarmVolumeZero =
            context.getSystemService(AudioManager::class.java)
                .getStreamVolume(AudioManager.STREAM_ALARM) == 0

        val vivo = isVivo()
        return AlarmPermissionStatus(
            notificationsGranted = notificationsGranted,
            exactAlarmsGranted = exactAlarmsGranted,
            fullScreenIntentGranted = fullScreenIntentGranted,
            batteryOptimizationIgnored = batteryOptimizationIgnored,
            overlayGranted = Settings.canDrawOverlays(context),
            alarmVolumeZero = alarmVolumeZero,
            isVivo = vivo,
            vivoWakeScreenOff = if (vivo) {
                readSystemInt(context, "notification_wake_up")?.let { it == 0 }
            } else {
                null
            },
        )
    }

    fun isVivo(): Boolean =
        Build.MANUFACTURER.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("vivo", ignoreCase = true) ||
            Build.BRAND.equals("iqoo", ignoreCase = true)

    @Suppress("InlinedApi")
    fun exactAlarmIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            packageUri(context),
        )

    fun fullScreenIntentIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Intent(
                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                packageUri(context),
            )
        } else {
            appDetailsIntent(context)
        }

    fun batteryOptimizationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun overlayIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(context))

    fun dndAccessIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    fun soundSettingsIntent(): Intent =
        Intent(Settings.ACTION_SOUND_SETTINGS)

    fun open(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                appDetailsIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private fun packageUri(context: Context): Uri =
        Uri.parse("package:${context.packageName}")

    private fun readSystemInt(context: Context, key: String): Int? =
        try {
            Settings.System.getInt(context.contentResolver, key)
        } catch (_: Settings.SettingNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
}
