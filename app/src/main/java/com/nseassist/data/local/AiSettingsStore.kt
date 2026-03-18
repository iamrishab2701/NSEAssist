package com.nseassist.data.local

import android.content.Context
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings

class AiSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AiSettings {
        val providers = AiProvider.values().map { provider ->
            AiProviderConfig(
                provider = provider,
                apiKey = prefs.getString(apiKey(provider), "") ?: "",
                model = prefs.getString(modelKey(provider), provider.defaultModel) ?: provider.defaultModel,
            )
        }
        return AiSettings(providers)
    }

    fun save(config: AiProviderConfig) {
        prefs.edit()
            .putString(apiKey(config.provider), config.apiKey.trim())
            .putString(modelKey(config.provider), config.model.trim().ifBlank { config.provider.defaultModel })
            .apply()
    }

    fun loadTestMorningSelloff(): Boolean = prefs.getBoolean(KEY_TEST_MORNING_SELLOFF, false)

    fun saveTestMorningSelloff(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TEST_MORNING_SELLOFF, enabled).apply()
    }

    private fun apiKey(provider: AiProvider) = "${provider.routeValue}_api_key"

    private fun modelKey(provider: AiProvider) = "${provider.routeValue}_model"

    companion object {
        private const val PREFS_NAME = "ai_settings"
        private const val KEY_TEST_MORNING_SELLOFF = "test_morning_selloff"
    }
}
