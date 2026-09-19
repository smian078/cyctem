package com.cystem.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.cystem.core.model.GenerationSettings
import com.cystem.core.model.ModelCatalog
import com.cystem.core.model.ReasoningEffort
import com.cystem.core.security.SecureKeyStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cystemDataStore by preferencesDataStore("cystem.settings")

data class AppSettings(
    val darkMode: Boolean = true,
    val accentArgb: Long = 0xFF7C5CFC,
    val customInstructions: String = "",
    val memoryEnabled: Boolean = false,
    val biometricLock: Boolean = false,
    val generation: GenerationSettings = GenerationSettings(),
)

class SettingsStore(
    private val context: Context,
    private val secureKeyStore: SecureKeyStore,
) {
    private object Keys {
        val darkMode = booleanPreferencesKey("dark_mode")
        val accent = longPreferencesKey("accent_argb")
        val instructions = stringPreferencesKey("instructions")
        val memoryEnabled = booleanPreferencesKey("memory_enabled")
        val biometricLock = booleanPreferencesKey("biometric_lock")
        val model = stringPreferencesKey("generation_model")
        val reasoning = stringPreferencesKey("generation_reasoning")
        val seed = longPreferencesKey("generation_seed")
        val temperature = doublePreferencesKey("generation_temperature")
        val topP = doublePreferencesKey("generation_top_p")
        val maxTokens = intPreferencesKey("generation_max_tokens")
        val nvidiaCipher = stringPreferencesKey("nvidia_api_key")
        val geminiCipher = stringPreferencesKey("gemini_api_key")
    }

    val settings: Flow<AppSettings> = context.cystemDataStore.data.map { prefs ->
        AppSettings(
            darkMode = prefs[Keys.darkMode] ?: true,
            accentArgb = prefs[Keys.accent] ?: 0xFF7C5CFC,
            customInstructions = prefs[Keys.instructions].orEmpty(),
            memoryEnabled = prefs[Keys.memoryEnabled] ?: false,
            biometricLock = prefs[Keys.biometricLock] ?: false,
            generation = GenerationSettings(
                model = prefs[Keys.model] ?: ModelCatalog.MAIN,
                reasoningEffort = runCatching {
                    ReasoningEffort.valueOf(
                        prefs[Keys.reasoning] ?: ReasoningEffort.HIGH.name,
                    )
                }.getOrDefault(ReasoningEffort.HIGH),
                seed = prefs[Keys.seed],
                temperature = prefs[Keys.temperature] ?: 0.7,
                topP = prefs[Keys.topP] ?: 0.95,
                maxTokens = prefs[Keys.maxTokens] ?: 4096,
            ),
        )
    }

    suspend fun saveGeneration(settings: GenerationSettings) {
        context.cystemDataStore.edit { prefs ->
            prefs[Keys.model] = settings.model
            prefs[Keys.reasoning] = settings.reasoningEffort.name
            settings.seed?.let { prefs[Keys.seed] = it } ?: prefs.remove(Keys.seed)
            prefs[Keys.temperature] = settings.temperature.coerceIn(0.0, 2.0)
            prefs[Keys.topP] = settings.topP.coerceIn(0.0, 1.0)
            prefs[Keys.maxTokens] = settings.maxTokens.coerceIn(256, 32768)
        }
    }

    suspend fun setDarkMode(enabled: Boolean) =
        context.cystemDataStore.edit { it[Keys.darkMode] = enabled }

    suspend fun setAccent(argb: Long) =
        context.cystemDataStore.edit { it[Keys.accent] = argb }

    suspend fun setCustomInstructions(value: String) =
        context.cystemDataStore.edit { it[Keys.instructions] = value.take(12000) }

    suspend fun setMemoryEnabled(enabled: Boolean) =
        context.cystemDataStore.edit { it[Keys.memoryEnabled] = enabled }

    suspend fun setBiometricLock(enabled: Boolean) =
        context.cystemDataStore.edit { it[Keys.biometricLock] = enabled }

    suspend fun saveNvidiaApiKey(value: String) = saveSecret(Keys.nvidiaCipher, value)
    suspend fun saveGeminiApiKey(value: String) = saveSecret(Keys.geminiCipher, value)
    suspend fun clearNvidiaApiKey() = clearSecret(Keys.nvidiaCipher)
    suspend fun clearGeminiApiKey() = clearSecret(Keys.geminiCipher)

    suspend fun readNvidiaApiKey(): String? = readSecret(Keys.nvidiaCipher)
    suspend fun readGeminiApiKey(): String? = readSecret(Keys.geminiCipher)

    private suspend fun saveSecret(
        key: Preferences.Key<String>,
        plainText: String,
    ) {
        require(plainText.length <= 2048) { "Secret is too long" }
        context.cystemDataStore.edit { prefs ->
            if (plainText.isBlank()) prefs.remove(key)
            else prefs[key] = secureKeyStore.encrypt(plainText.trim())
        }
    }

    private suspend fun clearSecret(key: Preferences.Key<String>) {
        context.cystemDataStore.edit { it.remove(key) }
    }

    private suspend fun readSecret(key: Preferences.Key<String>): String? {
        val cipherText = context.cystemDataStore.data.first()[key]
        if (cipherText.isNullOrBlank()) return null
        return runCatching { secureKeyStore.decrypt(cipherText) }.getOrNull()
    }
}
