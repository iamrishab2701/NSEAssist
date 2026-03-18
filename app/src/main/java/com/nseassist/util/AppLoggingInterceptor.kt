package com.nseassist.util

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that logs every network request + response into AppLogger.
 * Secrets (API keys, tokens, crumbs) are automatically redacted from URLs.
 */
class AppLoggingInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val req   = chain.request()
        val url   = req.url.toString().redactSecrets()
        val start = System.currentTimeMillis()

        AppLogger.d("NET", "→ ${req.method} $url")

        return try {
            val response = chain.proceed(req)
            val ms    = System.currentTimeMillis() - start
            val level = if (response.isSuccessful) AppLogger.Level.D else AppLogger.Level.W
            AppLogger.log(level, "NET", "← ${response.code} ${ms}ms  $url")
            response
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            AppLogger.e("NET", "✗ FAIL ${ms}ms  $url — ${e.message}")
            throw e
        }
    }

    private fun String.redactSecrets(): String =
        replace(Regex("(?i)(apikey|api_key|access_token|crumb|secret|token)=([^&\\s]+)")) { mr ->
            "${mr.groupValues[1]}=***"
        }
}
