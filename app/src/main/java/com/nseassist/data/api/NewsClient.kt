package com.nseassist.data.api

import com.nseassist.util.AppLogger
import com.nseassist.data.model.NewsArticle
import com.nseassist.data.model.NewsResult
import com.nseassist.data.model.NewsSentiment
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import com.nseassist.util.AppLoggingInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Fetches stock news via the Cloudflare Worker (/news endpoint).
 *
 * The Worker handles:
 *  - Fetching all 12 Indian financial RSS feeds in parallel
 *  - Caching each feed for 15 min at Cloudflare's edge (fast, no repeated hits)
 *  - Filtering for stock relevance server-side
 *  - Sentiment classification
 *
 * This client just calls the Worker and maps the JSON response to NewsResult.
 */
object NewsClient {

    private const val TAG        = "NewsClient"
    private const val WORKER_URL = "https://nseassist-proxy.rishabsingh.workers.dev"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor(AppLoggingInterceptor())
        .build()

    /**
     * Fetch news for a stock from the Cloudflare Worker.
     *
     * @param stockSymbol  NSE symbol, e.g. "SBIN"
     * @param companyName  Full name from Yahoo Finance, e.g. "State Bank of India"
     * @param sector       Yahoo Finance sector string (used for display only)
     * @param industry     Yahoo Finance industry string (used for display only)
     */
    suspend fun fetchNews(
        stockSymbol: String,
        companyName: String,
        sector: String = "",
        industry: String = "",
    ): NewsResult? {
        val aliases = StockAliasMap.getAliases(stockSymbol)

        val url = buildString {
            append("$WORKER_URL/news")
            append("?stock=${enc(stockSymbol)}")
            if (companyName.isNotBlank()) append("&company=${enc(companyName)}")
            if (aliases.isNotEmpty())     append("&aliases=${enc(aliases.joinToString(","))}")
        }

        val body = execute(url) ?: run {
            AppLogger.w("NEWS", "$stockSymbol — Worker returned null (network or HTTP error)")
            return null
        }

        return runCatching {
            val json       = JSONObject(body)
            val arr        = json.optJSONArray("articles")
            val stockCount = json.optInt("stockCount", 0)
            val sources    = json.optInt("sourceCount", 0)

            if (arr == null || arr.length() == 0) {
                AppLogger.d("NEWS", "$stockSymbol — no articles found")
                return null
            }

            val articles = (0 until arr.length()).map { i ->
                val obj       = arr.getJSONObject(i)
                val published = obj.optLong("publishedAt", System.currentTimeMillis())
                NewsArticle(
                    headline  = obj.optString("headline").take(120),
                    source    = obj.optString("source"),
                    ageText   = formatAge(published),
                    sentiment = parseSentiment(obj.optString("sentiment")),
                )
            }

            val overallSentiment = aggregateSentiment(articles.map { it.sentiment })

            AppLogger.d("NEWS", "$stockSymbol — articles=${articles.size} sources=$sources sentiment=$overallSentiment")

            NewsResult(
                sentiment          = overallSentiment,
                headline           = articles.firstOrNull()?.headline ?: "",
                ageText            = articles.firstOrNull()?.ageText  ?: "",
                stockSentiment     = overallSentiment,
                sectorSentiment    = NewsSentiment.NONE,
                stockArticleCount  = stockCount,
                sectorArticleCount = 0,
                sourceCount        = sources,
                sector             = industry.ifBlank { sector },
                articles           = articles,
            )
        }.getOrElse {
            AppLogger.e("NEWS", "$stockSymbol — parse failed: ${it.message}")
            null
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")!!

    private fun parseSentiment(s: String) = when (s.uppercase()) {
        "POSITIVE" -> NewsSentiment.POSITIVE
        "NEGATIVE" -> NewsSentiment.NEGATIVE
        "NEUTRAL"  -> NewsSentiment.NEUTRAL
        else       -> NewsSentiment.NONE
    }

    private fun aggregateSentiment(list: List<NewsSentiment>): NewsSentiment {
        if (list.isEmpty()) return NewsSentiment.NONE
        val pos = list.count { it == NewsSentiment.POSITIVE }
        val neg = list.count { it == NewsSentiment.NEGATIVE }
        return when {
            pos > neg            -> NewsSentiment.POSITIVE
            neg > pos            -> NewsSentiment.NEGATIVE
            pos > 0 || neg > 0   -> NewsSentiment.NEUTRAL
            else                 -> NewsSentiment.NONE
        }
    }

    private fun formatAge(publishedAt: Long): String {
        if (publishedAt <= 0L) return ""
        val minutes = ((System.currentTimeMillis() - publishedAt) / 60_000).coerceAtLeast(0)
        return when {
            minutes < 60       -> "${minutes}m ago"
            minutes < 60 * 24  -> "${minutes / 60}h ago"
            else               -> "${minutes / (60 * 24)}d ago"
        }
    }

    private suspend fun execute(url: String): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) return
                response.use { r ->
                    if (!r.isSuccessful) {
                        AppLogger.w("NEWS", "Worker /news HTTP ${r.code}")
                        cont.resume(null)
                    } else {
                        cont.resume(r.body?.string())
                    }
                }
            }
        })
    }
}
