package com.nseassist.data.api

import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.nseassist.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import com.nseassist.util.AppLoggingInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when Upstox returns HTTP 401 — token is expired or invalid. */
class UpstoxTokenExpiredException : Exception(
    "Upstox token has expired. Please generate a new token from Settings."
)

/**
 * Upstox v2 API client — real-time NSE market data.
 *
 * DAILY AUTH FLOW:
 *   1. Open getLoginUrl() in browser → user logs in → browser redirects to
 *      http://127.0.0.1?code=AUTH_CODE
 *   2. User copies AUTH_CODE from browser address bar
 *   3. Call exchangeToken(apiKey, apiSecret, authCode) → returns access_token
 *   4. Store access_token in AiSettingsStore (valid until midnight IST)
 *
 * HYBRID APPROACH:
 *   Upstox → Phase 1 live snapshot (LTP, Change%, Volume, VWAP, Day H/L)
 *   Yahoo Finance → Historical data (RSI, EMA, MACD), screener symbol list, avgVolume
 *
 * INSTRUMENT KEYS:
 *   Upstox uses instrument_key format: NSE_EQ|INE009A01021
 *   We download the instruments CSV once per session to build a
 *   tradingSymbol → instrumentKey map (e.g. "INFY" → "NSE_EQ|INE009A01021")
 */
object UpstoxClient {

    private const val TAG              = "UpstoxClient"
    private const val BASE_URL         = "https://api.upstox.com"
    private const val INSTRUMENTS_URL  =
        "https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz"
    private const val REDIRECT_URI     = "http://127.0.0.1"

    // In-memory cache: NSE trading symbol → Upstox instrument_key
    // e.g. "INFY" → "NSE_EQ|INE009A01021"
    private val instrumentKeyMap = HashMap<String, String>()
    private var instrumentsLoaded = false

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)   // instruments CSV is large
        .callTimeout(65, TimeUnit.SECONDS)
        .addInterceptor(AppLoggingInterceptor())
        .build()

    // ── Auth ─────────────────────────────────────────────────────────────────────

    /** Returns the OAuth2 login URL to open in the user's browser.
     *  After login, browser redirects to http://127.0.0.1?code=AUTH_CODE */
    fun getLoginUrl(apiKey: String): String =
        "$BASE_URL/v2/login/authorization/dialog" +
        "?response_type=code" +
        "&client_id=${URLEncoder.encode(apiKey.trim(), "UTF-8")}" +
        "&redirect_uri=${URLEncoder.encode(REDIRECT_URI, "UTF-8")}"

    /** Exchanges an auth code (from the redirect URL) for a daily access token.
     *  Throws an Exception with a human-readable message on failure. */
    suspend fun exchangeToken(
        apiKey: String,
        apiSecret: String,
        authCode: String,
    ): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder()
            .add("code",          authCode.trim())
            .add("client_id",     apiKey.trim())
            .add("client_secret", apiSecret.trim())
            .add("redirect_uri",  REDIRECT_URI)
            .add("grant_type",    "authorization_code")
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/v2/login/authorization/token")
            .post(body)
            .addHeader("Accept", "application/json")
            .build()

        val responseBody = httpClient.newCall(request).await()
        val json = JsonParser.parseString(responseBody).asJsonObject

        // Upstox v2 success response has no "status" field — it directly returns
        // the token payload. "status" is only present on error responses.
        val status = json.get("status")?.asString
        if (status != null && status != "success") {
            val msg = json.get("message")?.asString
                ?: json.get("error_description")?.asString
                ?: "Token exchange failed (status=$status)"
            throw Exception(msg)
        }

        json.get("access_token")?.asString
            ?: throw Exception("No access_token in Upstox response")
    }

    // ── Instruments CSV ──────────────────────────────────────────────────────────

    /** Downloads and parses the Upstox instruments master CSV (once per app session).
     *  Builds instrumentKeyMap: tradingSymbol → instrument_key for all NSE EQ stocks.
     *  Safe to call multiple times — no-ops after first successful load. */
    suspend fun ensureInstrumentMap() = withContext(Dispatchers.IO) {
        if (instrumentsLoaded && instrumentKeyMap.isNotEmpty()) return@withContext

        AppLogger.d("UPSTOX", "Downloading instruments CSV…")
        try {
            val request = Request.Builder()
                .url(INSTRUMENTS_URL)
                // Upstox CDN blocks okhttp's default User-Agent — spoof a browser UA
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
                .build()

            val bytes = httpClient.newCall(request).awaitBytes()
            var count = 0

            // Stream-parse JSON to avoid loading the full ~25 MB into memory at once
            JsonReader(InputStreamReader(GZIPInputStream(ByteArrayInputStream(bytes)))).use { reader ->
                reader.beginArray()
                while (reader.hasNext()) {
                    var segment: String? = null
                    var instrType: String? = null
                    var instrKey: String? = null
                    var tradingSym: String? = null

                    reader.beginObject()
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "segment"         -> segment   = reader.nextString()
                            "instrument_type" -> instrType = reader.nextString()
                            "instrument_key"  -> instrKey  = reader.nextString()
                            "trading_symbol"  -> tradingSym = reader.nextString()
                            else              -> reader.skipValue()
                        }
                    }
                    reader.endObject()

                    // Only NSE equity stocks
                    if (segment == "NSE_EQ" && instrType == "EQ" &&
                        instrKey != null && tradingSym != null) {
                        instrumentKeyMap[tradingSym.trim()] = instrKey.trim()
                        count++
                    }
                }
                reader.endArray()
            }

            instrumentsLoaded = true
            AppLogger.d("UPSTOX", "Instruments loaded: $count NSE EQ stocks in map")
        } catch (e: Exception) {
            AppLogger.e("UPSTOX", "Failed to load instruments: ${e.message}")
            // Non-fatal — caller will fall back to Yahoo Finance
        }
    }

    /** Returns the Upstox instrument_key for a trading symbol, or null if unknown. */
    fun getInstrumentKey(symbol: String): String? =
        instrumentKeyMap[symbol.removeSuffix(".NS").trim()]

    // ── Live Quotes ──────────────────────────────────────────────────────────────

    /** Real-time market quote returned by Upstox. */
    data class UpstoxQuote(
        val symbol: String,       // NSE trading symbol, e.g. "INFY"
        val ltp: Double,          // Last Traded Price (real-time)
        val change: Double,       // Price change in ₹ from prev close
        val changePct: Double,    // Change %
        val open: Double,         // Today's open
        val dayHigh: Double,      // Today's high
        val dayLow: Double,       // Today's low
        val prevClose: Double,    // Previous day's close
        val volume: Long,         // Today's traded volume
        val vwap: Double,         // Volume-Weighted Average Price (average_trade_price)
    )

    /** Fetches live quotes for a list of NSE trading symbols via Upstox v2 API.
     *  - Automatically downloads instrument map if not yet loaded
     *  - Skips symbols not found in the map (graceful degradation)
     *  - Batches up to 500 instrument keys per request
     *  Returns list of UpstoxQuote; may be shorter than input if some symbols unknown. */
    suspend fun getBatchQuotes(
        symbols: List<String>,
        accessToken: String,
    ): List<UpstoxQuote> = withContext(Dispatchers.IO) {
        ensureInstrumentMap()

        // Build instrument_key → trading symbol map for symbols we know
        // Also build a set of clean symbols for response key extraction
        val cleanSymbols = symbols.map { it.removeSuffix(".NS").trim() }.toSet()
        val keyToSymbol = cleanSymbols
            .mapNotNull { sym ->
                val key = instrumentKeyMap[sym] ?: return@mapNotNull null
                key to sym
            }
            .toMap()

        if (keyToSymbol.isEmpty()) {
            AppLogger.w("UPSTOX", "No instrument keys found for ${symbols.size} symbols — map size=${instrumentKeyMap.size}")
            return@withContext emptyList()
        }

        val result = mutableListOf<UpstoxQuote>()

        // Upstox allows up to 500 instrument keys per request
        keyToSymbol.keys.chunked(500).forEach { batch ->
            try {
                // Comma-separated instrument keys — must be URL-encoded
                val keysParam = batch.joinToString(",") { URLEncoder.encode(it, "UTF-8") }

                val request = Request.Builder()
                    .url("$BASE_URL/v2/market-quote/quotes?instrument_key=$keysParam")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .addHeader("Accept", "application/json")
                    .build()

                val responseBody = httpClient.newCall(request).awaitWithTokenCheck()
                val json = JsonParser.parseString(responseBody).asJsonObject

                val status = json.get("status")?.asString
                AppLogger.d("UPSTOX", "Quote response status=$status")
                if (status != "success") {
                    AppLogger.e("UPSTOX", "Quote API error: ${json.get("message")?.asString} | body=${responseBody.take(300)}")
                    return@forEach
                }

                val data = json.getAsJsonObject("data") ?: run {
                    AppLogger.e("UPSTOX", "Quote response has no 'data' field | body=${responseBody.take(300)}")
                    return@forEach
                }

                AppLogger.d("UPSTOX", "data entries=${data.entrySet().size} keys=${data.entrySet().map { it.key }.take(3)}")

                data.entrySet().forEach { (instrKey, quoteEl) ->
                    // Upstox sends NSE_EQ|ISIN in request but returns NSE_EQ:SYMBOL in response
                    // Extract symbol from response key (after ':' or '|'), then verify it was requested
                    val sym = keyToSymbol[instrKey]
                        ?: instrKey.substringAfterLast(":").takeIf { it in cleanSymbols }
                        ?: instrKey.substringAfterLast("|").takeIf { it in cleanSymbols }
                        ?: run {
                            AppLogger.w("UPSTOX", "instrKey '$instrKey' not resolved (cleanSymbols=$cleanSymbols)")
                            return@forEach
                        }
                    runCatching {
                        val q        = quoteEl.asJsonObject
                        val ohlc     = q.getAsJsonObject("ohlc")
                        val ltp      = q.get("last_price")?.asDouble  ?: return@runCatching
                        val open     = ohlc?.get("open")?.asDouble    ?: ltp
                        val high     = ohlc?.get("high")?.asDouble    ?: ltp
                        val low      = ohlc?.get("low")?.asDouble     ?: ltp
                        val prevClose= ohlc?.get("close")?.asDouble   ?: ltp
                        val netChg   = q.get("net_change")?.asDouble  ?: (ltp - prevClose)
                        val chgPct   = if (prevClose > 0) (netChg / prevClose) * 100.0 else 0.0
                        val vol      = q.get("volume")?.asLong        ?: 0L
                        // Upstox returns actual VWAP as average_trade_price
                        val vwap     = q.get("average_trade_price")?.asDouble
                            ?: ((high + low + ltp) / 3.0)

                        result.add(
                            UpstoxQuote(
                                symbol    = sym,
                                ltp       = ltp,
                                change    = netChg,
                                changePct = chgPct,
                                open      = open,
                                dayHigh   = high,
                                dayLow    = low,
                                prevClose = prevClose,
                                volume    = vol,
                                vwap      = vwap,
                            )
                        )
                    }.onFailure { e ->
                        AppLogger.w("UPSTOX", "Failed to parse quote for $sym: ${e.message}")
                    }
                }

                AppLogger.d("UPSTOX", "Batch done — ${result.size} quotes fetched so far")
            } catch (e: Exception) {
                AppLogger.e("UPSTOX", "getBatchQuotes batch failed: ${e.message}")
            }
        }

        result
    }

    // ── OkHttp coroutine extensions ───────────────────────────────────────────────

    /** Like [await] but throws [UpstoxTokenExpiredException] on HTTP 401. */
    private suspend fun Call.awaitWithTokenCheck(): String = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) return
                try {
                    val body = response.body?.string() ?: ""
                    when {
                        response.code == 401 -> cont.resumeWithException(UpstoxTokenExpiredException())
                        !response.isSuccessful -> cont.resumeWithException(Exception("HTTP ${response.code}: $body"))
                        else -> cont.resume(body)
                    }
                } finally {
                    response.close()
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private suspend fun Call.await(): String = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) return
                try {
                    val body = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        cont.resumeWithException(Exception("HTTP ${response.code}: $body"))
                    } else {
                        cont.resume(body)
                    }
                } finally {
                    response.close()
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }

    private suspend fun Call.awaitBytes(): ByteArray = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) return
                try {
                    val bytes = response.body?.bytes() ?: ByteArray(0)
                    if (!response.isSuccessful) {
                        cont.resumeWithException(Exception("HTTP ${response.code}"))
                    } else {
                        cont.resume(bytes)
                    }
                } finally {
                    response.close()
                }
            }
        })
        cont.invokeOnCancellation { cancel() }
    }
}
