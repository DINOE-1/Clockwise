package com.dinoe.clockwise.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.dinoe.clockwise.ClockwiseApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

/**
 * Process-wide owner of the bridge server lifecycle. [BridgeService] keeps the process foregrounded
 * and calls [start]/[stop]; the Settings screen observes [running] and [lastError].
 */
object BridgeController {
    private const val TAG = "BridgeController"

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var server: BridgeServer? = null

    /** Starts the HTTP server using the current [BridgeConfig]. Called from [BridgeService]. */
    @Synchronized
    fun start(context: Context) {
        if (server != null) return
        val app = context.applicationContext as ClockwiseApp
        val preferences = BridgePreferences(app)
        val config = runBlocking { preferences.snapshot() }
        val router = BridgeRouter(
            eventDao = app.database.eventDao(),
            alarmDao = app.database.alarmDao(),
            tagDao = app.database.tagDao(),
            configProvider = { runBlocking { preferences.snapshot() } },
            appVersionName = appVersionName(app),
            dbVersionProvider = { app.database.openHelper.writableDatabase.version },
        )
        try {
            BridgeServer(config.port, router::handle).also { bridgeServer ->
                bridgeServer.start()
                server = bridgeServer
                _running.value = true
                _lastError.value = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start the bridge server", e)
            _lastError.value = e.message ?: "Unable to start the bridge server"
        }
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        _running.value = false
    }

    /**
     * Starts the foreground service when the user enabled the bridge. Only call this from
     * foreground app starts or boot/package-replace receivers: elsewhere the system rejects
     * foreground-service starts from the background.
     */
    fun startIfEnabled(context: Context) {
        val appContext = context.applicationContext
        val config = runBlocking { BridgePreferences(appContext).snapshot() }
        if (!config.enabled || config.token.isEmpty()) return
        runCatching {
            appContext.startForegroundService(Intent(appContext, BridgeService::class.java))
        }.onFailure { e ->
            Log.e(TAG, "Unable to start the bridge service", e)
            _lastError.value = e.message ?: "Unable to start the bridge service"
        }
    }

    private fun appVersionName(context: Context): String =
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
}
