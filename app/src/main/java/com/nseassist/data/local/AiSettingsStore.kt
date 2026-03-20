package com.nseassist.data.local

import android.content.Context
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings
import java.time.LocalDate
import java.time.ZoneId

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
        val primary = AiProvider.fromRouteValue(prefs.getString(KEY_PRIMARY_PROVIDER, null))
        return AiSettings(providers = providers, primaryProvider = primary)
    }

    fun savePrimaryProvider(provider: AiProvider) {
        prefs.edit().putString(KEY_PRIMARY_PROVIDER, provider.routeValue).apply()
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

    // ── Data Source (Yahoo Finance vs Upstox) ────────────────────────────────────

    /** "yahoo" or "upstox" */
    fun loadDataSource(): String = prefs.getString(KEY_DATA_SOURCE, DATA_SOURCE_YAHOO) ?: DATA_SOURCE_YAHOO

    fun saveDataSource(source: String) {
        prefs.edit().putString(KEY_DATA_SOURCE, source).apply()
    }

    // ── Upstox Credentials ───────────────────────────────────────────────────────

    fun loadUpstoxApiKey(): String = prefs.getString(KEY_UPSTOX_API_KEY, "") ?: ""

    fun saveUpstoxApiKey(key: String) {
        prefs.edit().putString(KEY_UPSTOX_API_KEY, key.trim()).apply()
    }

    fun loadUpstoxApiSecret(): String = prefs.getString(KEY_UPSTOX_API_SECRET, "") ?: ""

    fun saveUpstoxApiSecret(secret: String) {
        prefs.edit().putString(KEY_UPSTOX_API_SECRET, secret.trim()).apply()
    }

    // ── Upstox Daily Access Token ─────────────────────────────────────────────────

    fun loadUpstoxAccessToken(): String = prefs.getString(KEY_UPSTOX_TOKEN, "") ?: ""

    /** Saves the access token and stamps it with today's IST date. */
    fun saveUpstoxAccessToken(token: String) {
        val today = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
        prefs.edit()
            .putString(KEY_UPSTOX_TOKEN, token.trim())
            .putString(KEY_UPSTOX_TOKEN_DATE, today)
            .apply()
    }

    /** Returns true only if a token was saved today (IST date match). */
    fun isUpstoxTokenValidToday(): Boolean {
        val today     = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString()
        val tokenDate = prefs.getString(KEY_UPSTOX_TOKEN_DATE, "") ?: ""
        return tokenDate == today && loadUpstoxAccessToken().isNotBlank()
    }

    private fun apiKey(provider: AiProvider) = "${provider.routeValue}_api_key"

    private fun modelKey(provider: AiProvider) = "${provider.routeValue}_model"

    companion object {
        private const val PREFS_NAME               = "ai_settings"
        private const val KEY_PRIMARY_PROVIDER      = "primary_ai_provider"
        private const val KEY_TEST_MORNING_SELLOFF = "test_morning_selloff"
        private const val KEY_DATA_SOURCE          = "data_source"
        private const val KEY_UPSTOX_API_KEY       = "upstox_api_key"
        private const val KEY_UPSTOX_API_SECRET    = "upstox_api_secret"
        private const val KEY_UPSTOX_TOKEN         = "upstox_access_token"
        private const val KEY_UPSTOX_TOKEN_DATE    = "upstox_token_date"

        const val DATA_SOURCE_YAHOO  = "yahoo"
        const val DATA_SOURCE_UPSTOX = "upstox"
    }
}
