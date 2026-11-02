package com.dinoe.clockwise.bridge

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.bridgeDataStore by preferencesDataStore(name = "bridge")

/** User-controlled bridge configuration stored in the `bridge` DataStore. */
data class BridgeConfig(
    val enabled: Boolean = false,
    val port: Int = BridgePreferences.DEFAULT_PORT,
    val token: String = "",
)

class BridgePreferences(private val context: Context) {

    val config: Flow<BridgeConfig> = context.bridgeDataStore.data.map { preferences ->
        BridgeConfig(
            enabled = preferences[ENABLED_KEY] ?: false,
            port = (preferences[PORT_KEY] ?: DEFAULT_PORT).coerceIn(MIN_PORT, MAX_PORT),
            token = preferences[TOKEN_KEY] ?: "",
        )
    }

    suspend fun snapshot(): BridgeConfig = config.first()

    /**
     * Enables or disables the bridge. Enabling generates the access token on first use so the
     * Settings screen always has a token to show when the bridge is on.
     */
    suspend fun setEnabled(enabled: Boolean) {
        context.bridgeDataStore.edit { preferences ->
            preferences[ENABLED_KEY] = enabled
            if (enabled && (preferences[TOKEN_KEY] ?: "").isBlank()) {
                preferences[TOKEN_KEY] = BridgeAuth.generateToken()
            }
        }
    }

    suspend fun setPort(port: Int) {
        context.bridgeDataStore.edit { preferences ->
            preferences[PORT_KEY] = port.coerceIn(MIN_PORT, MAX_PORT)
        }
    }

    suspend fun rotateToken(): String {
        val token = BridgeAuth.generateToken()
        context.bridgeDataStore.edit { preferences ->
            preferences[TOKEN_KEY] = token
        }
        return token
    }

    companion object {
        const val DEFAULT_PORT = 8787
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535

        private val ENABLED_KEY = booleanPreferencesKey("enabled")
        private val PORT_KEY = intPreferencesKey("port")
        private val TOKEN_KEY = stringPreferencesKey("token")
    }
}
