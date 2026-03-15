package com.nseassist.data.api

import android.util.Log
import com.nseassist.data.model.GlobalIndexItem
import com.nseassist.data.model.GlobalTrendsData
import com.nseassist.data.model.ImpactCard
import com.nseassist.data.model.ImpactedStock
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object GlobalTrendsClient {

    private const val TAG        = "GlobalTrendsClient"
    private const val WORKER_URL = "https://nseassist-proxy.rishabsingh.workers.dev"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun fetchGlobalTrends(): GlobalTrendsData? {
        val url  = "$WORKER_URL/global-trends"
        Log.d(TAG, "→ fetching $url")
        val body = execute(url) ?: run {
            Log.w(TAG, "Worker /global-trends returned null")
            return null
        }
        return runCatching {
            val json = JSONObject(body)
            val data = GlobalTrendsData(
                us          = parseIndexList(json.optJSONArray("us")),
                asia        = parseIndexList(json.optJSONArray("asia")),
                india       = parseIndexList(json.optJSONArray("india")),
                commodities = parseIndexList(json.optJSONArray("commodities")),
                forex       = parseIndexList(json.optJSONArray("forex")),
                impacts     = parseImpactCards(json.optJSONArray("impacts")),
                overallBias = json.optString("overallBias", "NEUTRAL"),
                cachedAt    = json.optLong("cachedAt", System.currentTimeMillis()),
            )
            Log.d(TAG, "← us=${data.us.size} asia=${data.asia.size} india=${data.india.size} impacts=${data.impacts.size} bias=${data.overallBias}")
            data
        }.getOrElse {
            Log.e(TAG, "Parse error: ${it.message}")
            null
        }
    }

    private fun parseIndexList(arr: JSONArray?): List<GlobalIndexItem> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GlobalIndexItem(
                symbol    = o.optString("symbol"),
                name      = o.optString("name"),
                price     = o.optDouble("price",     0.0),
                changePct = o.optDouble("changePct", 0.0),
                direction = o.optString("direction", "UP"),
            )
        }
    }

    private fun parseImpactCards(arr: JSONArray?): List<ImpactCard> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ImpactCard(
                foreignSymbol    = o.optString("foreignSymbol"),
                foreignName      = o.optString("foreignName"),
                foreignChangePct = o.optDouble("foreignChangePct", 0.0),
                direction        = o.optString("direction",        "UP"),
                sector           = o.optString("sector"),
                impactDirection  = o.optString("impactDirection",  "POSITIVE"),
                indianStocks     = parseImpactedStocks(o.optJSONArray("indianStocks")),
                newsHeadlines    = parseStringList(o.optJSONArray("newsHeadlines")),
            )
        }
    }

    private fun parseImpactedStocks(arr: JSONArray?): List<ImpactedStock> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ImpactedStock(
                symbol    = o.optString("symbol"),
                nseSymbol = o.optString("nseSymbol"),
                name      = o.optString("name"),
                sector    = o.optString("sector"),
            )
        }
    }

    private fun parseStringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
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
                        Log.w(TAG, "Worker /global-trends HTTP ${r.code}")
                        cont.resume(null)
                    } else {
                        Log.d(TAG, "HTTP ${r.code} cache=${r.header("X-Cache", "?")}")
                        cont.resume(r.body?.string())
                    }
                }
            }
        })
    }
}
