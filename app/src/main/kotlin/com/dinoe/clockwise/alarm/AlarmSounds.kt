package com.dinoe.clockwise.alarm

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import android.provider.Settings
import com.dinoe.clockwise.R
import com.dinoe.clockwise.data.Alarm
import com.dinoe.clockwise.ui.ThemePreferences
import kotlinx.coroutines.flow.first

/**
 * Resolves the stored ringtone value of an alarm. A stored value is [DEFAULT], [SILENT], or a
 * platform Uri string. "default" is intentionally resolved at ring time, so changing the default
 * in Settings also changes every alarm that has not overridden it.
 */
object AlarmSounds {
    const val DEFAULT = Alarm.DEFAULT_RINGTONE
    const val SILENT = Alarm.SILENT_RINGTONE

    /** Returns a playable Uri string, or [SILENT] when the alarm must ring without audio. */
    suspend fun resolve(context: Context, ringtone: String): String {
        if (ringtone == SILENT) return SILENT
        if (ringtone != DEFAULT) return ringtone
        // A preference read must never stop an alarm from ringing; fall back to the system sound.
        val configured = runCatching {
            ThemePreferences(context).defaultRingtone.first()
        }.getOrDefault(DEFAULT)
        return when (configured) {
            SILENT -> SILENT
            DEFAULT -> systemDefaultUri()?.toString() ?: SILENT
            else -> configured
        }
    }

    fun systemDefaultUri(): Uri? =
        Settings.System.DEFAULT_ALARM_ALERT_URI
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: Settings.System.DEFAULT_NOTIFICATION_URI

    /** Label for an alarm's own ringtone choice. */
    fun name(context: Context, ringtone: String): String = when (ringtone) {
        DEFAULT -> context.getString(R.string.ringtone_default)
        SILENT -> context.getString(R.string.ringtone_silent)
        else -> title(context, ringtone)
            ?: context.getString(R.string.ringtone_unknown)
    }

    /** Label for the Settings default, which has no "default" level above it. */
    fun defaultName(context: Context, ringtone: String): String = when (ringtone) {
        DEFAULT -> systemDefaultTitle(context)
            ?: context.getString(R.string.ringtone_system_default)
        SILENT -> context.getString(R.string.ringtone_silent)
        else -> title(context, ringtone)
            ?: context.getString(R.string.ringtone_unknown)
    }

    fun title(context: Context, uri: String): String? = runCatching {
        RingtoneManager.getRingtone(context, Uri.parse(uri))
            ?.getTitle(context)
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Every sound the platform offers, deduplicated by Uri, in RingtoneManager order. */
    fun available(context: Context): List<Pair<String, String>> = runCatching {
        val manager = RingtoneManager(context).apply {
            setType(
                RingtoneManager.TYPE_ALARM or
                    RingtoneManager.TYPE_RINGTONE or
                    RingtoneManager.TYPE_NOTIFICATION,
            )
        }
        val cursor = manager.cursor ?: return emptyList()
        cursor.use {
            val seen = HashSet<String>()
            buildList {
                while (it.moveToNext()) {
                    val uri = manager.getRingtoneUri(it.position)?.toString() ?: continue
                    if (!seen.add(uri)) continue
                    val title = it.getString(RingtoneManager.TITLE_COLUMN_INDEX)
                        ?.takeIf(String::isNotBlank)
                        ?: continue
                    add(title to uri)
                }
            }
        }
    }.getOrDefault(emptyList())

    private fun systemDefaultTitle(context: Context): String? =
        systemDefaultUri()?.let { title(context, it.toString()) }
}
