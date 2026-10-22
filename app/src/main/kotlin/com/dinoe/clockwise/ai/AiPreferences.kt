package com.dinoe.clockwise.ai

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val Context.aiDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai")

class AiPreferences(private val context: Context) {
    private val dataStore = context.aiDataStore
    private val cipher = ApiKeyCipher()
    private val json = Json { ignoreUnknownKeys = true }

    private val activeProviderKey = stringPreferencesKey("active_provider")

    val config: Flow<AiConfig> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map(::buildConfig)
        .flowOn(Dispatchers.IO)

    suspend fun apiKey(providerId: AiProviderId): String? {
        val stored = runCatching { dataStore.data.first()[apiKeyKey(providerId)] }
            .getOrNull()
            ?: return null
        return cipher.decrypt(stored)
    }

    suspend fun setActiveProvider(providerId: AiProviderId) {
        dataStore.edit { preferences ->
            preferences[activeProviderKey] = providerId.prefKey
        }
    }

    suspend fun saveApiKey(providerId: AiProviderId, rawKey: String) {
        val key = rawKey.trim()
        dataStore.edit { preferences ->
            if (key.isEmpty()) {
                preferences.remove(apiKeyKey(providerId))
            } else {
                preferences[apiKeyKey(providerId)] = cipher.encrypt(key)
            }
        }
    }

    suspend fun clearApiKey(providerId: AiProviderId) {
        dataStore.edit { preferences ->
            preferences.remove(apiKeyKey(providerId))
        }
    }

    suspend fun setReasoningEffort(
        providerId: AiProviderId,
        effort: AiReasoningEffort,
    ) {
        dataStore.edit { preferences ->
            preferences[reasoningEffortKey(providerId)] = effort.prefKey
        }
    }

    suspend fun setModel(providerId: AiProviderId, modelId: String) {
        val preset = AiProviders.preset(providerId)
        val cleaned = modelId.trim().ifBlank { preset.defaultModel }
        dataStore.edit { preferences ->
            preferences[modelKey(providerId)] = cleaned
        }
    }

    suspend fun saveModels(
        providerId: AiProviderId,
        models: List<AiModelInfo>,
        fetchedAt: Long,
    ) {
        val encoded = buildJsonArray {
            models.take(300).forEach { model ->
                add(
                    buildJsonObject {
                        put("id", model.id)
                        put("name", model.name)
                        model.contextLength?.let { put("context", it) }
                    },
                )
            }
        }.toString()
        dataStore.edit { preferences ->
            preferences[modelsKey(providerId)] = encoded
            preferences[modelsFetchedAtKey(providerId)] = fetchedAt
        }
    }

    private fun buildConfig(preferences: Preferences): AiConfig {
        val active = AiProviderId.fromPrefKey(preferences[activeProviderKey])
        val providers = AiProviders.all.associate { preset ->
            val storedKey = preferences[apiKeyKey(preset.id)]
            val decrypted = storedKey?.let(cipher::decrypt)
            val models = decodeModels(preferences[modelsKey(preset.id)])
                ?.takeIf { it.isNotEmpty() }
                ?: preset.fallbackModels
            preset.id to AiProviderConfig(
                preset = preset,
                hasKey = decrypted != null,
                keyLast4 = decrypted?.takeLast(4)?.takeIf { it.isNotBlank() },
                keyError = storedKey != null && decrypted == null,
                modelId = preferences[modelKey(preset.id)]?.takeIf { it.isNotBlank() }
                    ?: preset.defaultModel,
                models = models,
                modelsFetchedAt = preferences[modelsFetchedAtKey(preset.id)] ?: 0L,
                reasoningEffort = AiReasoningEffort.fromPrefKey(
                    preferences[reasoningEffortKey(preset.id)],
                ),
            )
        }
        return AiConfig(activeProviderId = active, providers = providers)
    }

    private fun decodeModels(raw: String?): List<AiModelInfo>? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val array = json.parseToJsonElement(raw) as? JsonArray ?: return null
            array.mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val id = (item["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                val name = (item["name"] as? JsonPrimitive)?.contentOrNull ?: id
                val context = (item["context"] as? JsonPrimitive)?.longOrNull
                AiModelInfo(id = id, name = name, contextLength = context)
            }
        }.getOrNull()
    }

    private fun apiKeyKey(providerId: AiProviderId) =
        stringPreferencesKey("provider.${providerId.prefKey}.api_key_enc")

    private fun modelKey(providerId: AiProviderId) =
        stringPreferencesKey("provider.${providerId.prefKey}.model_id")

    private fun modelsKey(providerId: AiProviderId) =
        stringPreferencesKey("provider.${providerId.prefKey}.models_json")

    private fun modelsFetchedAtKey(providerId: AiProviderId) =
        longPreferencesKey("provider.${providerId.prefKey}.models_fetched_at")

    private fun reasoningEffortKey(providerId: AiProviderId) =
        stringPreferencesKey("provider.${providerId.prefKey}.reasoning_effort")
}
