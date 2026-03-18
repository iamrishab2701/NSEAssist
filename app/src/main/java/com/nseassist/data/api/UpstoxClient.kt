package com.nseassist.data.api

import com.google.gson.JsonParser
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
import java.io.BufferedReader
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
        "https://assets.upstox.com/market-assets/instruments/v1/complete.csv.gz"
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

        val status = json.get("status")?.asString
        if (status != "success") {
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
                .build()

            val bytes = httpClient.newCall(request).awaitBytes()

            // The file is gzip-compressed; decompress manually
            val reader = BufferedReader(
                InputStreamReader(GZIPInputStream(ByteArrayInputStream(bytes)))
            )

            var header: List<String>? = null
            var count = 0

            reader.useLines { lines ->
                lines.forEach { line ->
                    val cols = line.split(",")
                    if (header == null) {
                        header = cols
                        return@forEach
                    }
                    val h = header ?: return@forEach
                    if (cols.size < h.size) return@forEach

                    val instrKey     = cols.getOrNull(h.indexOf("instrument_key"))  ?: return@forEach
                    val tradingSym   = cols.getOrNull(h.indexOf("tradingsymbol"))   ?: return@forEach
                    val instrType    = cols.getOrNull(h.indexOf("instrument_type")) ?: return@forEach

                    // Only NSE equity (not F&O, not currency, not commodity)
                    if (instrType.trim() == "EQ" && instrKey.trim().startsWith("NSE_EQ|")) {
                        instrumentKeyMap[tradingSym.trim()] = instrKey.trim()
                        count++
                    }
                }
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
        val keyToSymbol = symbols
            .map { it.removeSuffix(".NS").trim() }
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
                if (status != "success") {
                    AppLogger.e("UPSTOX", "Quote API error: ${json.get("message")?.asString}")
                    return@forEach
                }

                val data = json.getAsJsonObject("data") ?: return@forEach

                data.entrySet().forEach { (instrKey, quoteEl) ->
                    val sym = keyToSymbol[instrKey] ?: return@forEach
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
