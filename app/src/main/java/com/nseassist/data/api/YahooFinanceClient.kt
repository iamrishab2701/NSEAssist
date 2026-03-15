package com.nseassist.data.api

import android.util.Log
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Yahoo Finance HTTP client — pure OkHttp async, no Retrofit.
 *
 * SESSION INIT (done once):
 *   1. Visit https://finance.yahoo.com/ → Yahoo sets A1/A3/gpp session cookies
 *   2. GET /v1/test/getcrumb            → returns crumb string (needs the cookies)
 *   3. Subsequent API calls send cookie jar + crumb param automatically
 *
 * WHY 401 without warmup:
 *   The crumb endpoint itself returns 401 if there are no session cookies.
 *   Without the crumb, the v7/v8 quote endpoints also return 401.
 *   Solution: visit the homepage first (OkHttp CookieJar stores the cookies),
 *   then the crumb endpoint works.
 *
 * RACE-CONDITION FIX:
 *   A Mutex ensures only ONE coroutine runs session init; all others wait for
 *   the result rather than all firing parallel crumb requests (causing a 401
 *   reset-loop in the old code).
 */
object YahooFinanceClient {

    private const val TAG  = "YahooFinanceClient"
    private const val BASE = "https://query1.finance.yahoo.com"
    private const val UA   =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    // ── Cloudflare Worker proxy ───────────────────────────────────────────────
    // Set this to your deployed Worker URL to route all calls through the proxy.
    // The Worker handles Yahoo Finance session (cookies + crumb) server-side,
    // so the Android app never has to do the auth dance — it's just plain GETs.
    //
    // How to get your Worker URL:
    //   1. Follow the deployment steps in cloudflare-worker/README.md
    //   2. After "wrangler deploy", Cloudflare prints:
    //        Deployed: https://nseassist-proxy.YOUR_NAME.workers.dev
    //   3. Paste that URL below (no trailing slash).
    //
    // Leave blank → falls back to the existing direct Yahoo Finance path.
    private const val WORKER_URL = "https://nseassist-proxy.rishabsingh.workers.dev"

    private val useWorker get() = WORKER_URL.isNotBlank()

    // Simple HTTP client used for Worker calls — no cookie jar needed (session is server-side)
    private val workerClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30,    TimeUnit.SECONDS)
        .callTimeout(35,    TimeUnit.SECONDS)
        .build()

    // ── Cookie store — domain-aware (handles .yahoo.com cross-subdomain) ────
    //
    // Yahoo sets cookies with domain=.yahoo.com so they must be sent to ALL
    // *.yahoo.com subdomains (finance.yahoo.com, query1.finance.yahoo.com, …).
    // A simple hostname-keyed map misses this; we use a flat list + domain match.

    private val allCookies = mutableListOf<Cookie>()
    private val cookieJar  = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(allCookies) {
                cookies.forEach { new ->
                    // Replace any existing cookie with same name+domain+path
                    allCookies.removeAll { it.name == new.name && it.domain == new.domain && it.path == new.path }
                    allCookies.add(new)
                }
                Log.d(TAG, "Cookies: ${allCookies.map { "${it.name}@${it.domain}" }}")
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return synchronized(allCookies) {
                allCookies.filter { cookie ->
                    // Send cookie if host equals the domain OR is a subdomain of it
                    // e.g. domain="yahoo.com" matches "query1.finance.yahoo.com"
                    url.host == cookie.domain || url.host.endsWith(".${cookie.domain}")
                }
            }
        }
    }

    // ── OkHttp client ────────────────────────────────────────────────────────

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent",      UA)
                    .header("Accept",          "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    // NOTE: Do NOT set Accept-Encoding here.
                    // When OkHttp adds it automatically, it also decompresses the response.
                    // If we set it manually, OkHttp skips auto-decompression and we get raw gzip bytes.
                    .build()
            )
        }
        .connectTimeout(8,  TimeUnit.SECONDS)
        .readTimeout(15,    TimeUnit.SECONDS)
        .callTimeout(20,    TimeUnit.SECONDS)
        .build()

    // ── API-specific client (adds Yahoo Referer/Origin headers) ──────────────

    private val apiClient = client.newBuilder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept",  "application/json, text/plain, */*")
                    .header("Referer", "https://finance.yahoo.com/")
                    .header("Origin",  "https://finance.yahoo.com")
                    .build()
            )
        }
        .build()

    // ── Session state (Mutex prevents concurrent init races) ─────────────────

    private val sessionMutex = Mutex()
    // sessionDone is only set true when we have a VALID crumb (not empty).
    // An empty-crumb init is considered failed and can be retried.
    @Volatile private var sessionDone  = false
    @Volatile private var cachedCrumb  = ""

    /** Called to force a fresh session init on the next API call. */
    private fun resetSession() {
        sessionDone = false
        cachedCrumb = ""
        synchronized(allCookies) { allCookies.clear() }
        Log.d(TAG, "Session reset — will re-init on next call")
    }

    /**
     * Ensures the Yahoo Finance session is initialized (cookies + crumb).
     * Only runs once per valid session; all parallel callers wait on the Mutex.
     * If the crumb comes back empty, sessionDone stays false so the next call retries.
     */
    private suspend fun ensureSession(): String {
        if (sessionDone) return cachedCrumb
        return sessionMutex.withLock {
            if (sessionDone) return@withLock cachedCrumb

            Log.d(TAG, "── Yahoo Finance session init ──────────────────")

            // STEP 1a: Warmup main homepage (8 s)
            Log.d(TAG, "Step 1a: warming up finance.yahoo.com…")
            val warmed = withTimeoutOrNull(8_000L) {
                runCatching { executeRaw("https://finance.yahoo.com/") != null }.getOrDefault(false)
            } ?: false
            Log.d(TAG, "Step 1a: ${if (warmed) "✓ ok" else "✗ timed out"}")

            // STEP 1b: If no cookies yet, try a quote page as alternative warmup
            val hasCookies = synchronized(allCookies) { allCookies.isNotEmpty() }
            if (!hasCookies) {
                Log.d(TAG, "Step 1b: no cookies — trying quote page warmup…")
                withTimeoutOrNull(6_000L) {
                    runCatching { executeRaw("https://finance.yahoo.com/quote/RELIANCE.NS/") }.getOrNull()
                }
                Log.d(TAG, "Step 1b: cookies now: ${synchronized(allCookies) { allCookies.size }}")
            }
            Log.d(TAG, "Cookie domains: ${synchronized(allCookies) { allCookies.map { it.domain }.distinct() }}")

            // STEP 2: Fetch crumb (5 s)
            Log.d(TAG, "Step 2: fetching crumb…")
            val crumb = withTimeoutOrNull(5_000L) {
                runCatching {
                    val body = executeApi("$BASE/v1/test/getcrumb") ?: ""
                    val t = body.trim()
                    if (t.length in 4..30 && !t.startsWith("<") && !t.contains("\n")) t else ""
                }.getOrDefault("")
            } ?: ""

            cachedCrumb = crumb
            // Only mark session done if we actually got a crumb — allows retry if empty
            if (crumb.isNotEmpty()) sessionDone = true
            Log.d(TAG, "Session ${if (crumb.isNotEmpty()) "ready" else "PARTIAL (no crumb)"} — " +
                        "crumb='${crumb.take(8)}${if (crumb.length > 8) "…" else ""}'")
            Log.d(TAG, "────────────────────────────────────────────────")
            crumb
        }
    }

    private fun crumbParam(crumb: String) =
        if (crumb.isNotEmpty()) "&crumb=${URLEncoder.encode(crumb, "UTF-8")}" else ""

    // ── Public API ────────────────────────────────────────────────────────────

    data class Quote(
        val symbol    : String,
        val name      : String,
        val price     : Double,
        val change    : Double,
        val changePct : Double,
        val dayHigh   : Double,
        val dayLow    : Double,
        val volume    : Long,
        val avgVolume : Long,
        val open      : Double,
        val prevClose : Double,
        val marketCap : Double = 0.0,
    )

    data class Bar(
        val open   : Double,
        val high   : Double,
        val low    : Double,
        val close  : Double,
        val volume : Long,
    )

    data class AssetProfile(
        val sector: String = "",
        val industry: String = "",
    )

    /**
     * Fetch quotes for up to 20 symbols in a single HTTP request.
     * When [WORKER_URL] is set, calls the Cloudflare Worker (no auth needed).
     * Otherwise falls back to direct Yahoo Finance with session management.
     * Returns an empty list on any error (never throws).
     */
    suspend fun getBatchQuotes(symbols: List<String>): List<Quote> {
        if (symbols.isEmpty()) return emptyList()

        // ── Worker path ──────────────────────────────────────────────────────
        if (useWorker) {
            val joined = URLEncoder.encode(symbols.joinToString(","), "UTF-8")
            val url    = "$WORKER_URL/quotes?symbols=$joined"
            Log.d(TAG, "→ [worker] batch ${symbols.size} symbols")
            return runCatching {
                val body = executeWith(workerClient, url) ?: return emptyList()
                parseBatchQuotes(body)
            }.getOrElse {
                Log.w(TAG, "Worker batch quote error: ${it.message}")
                emptyList()
            }
        }

        // ── Direct Yahoo Finance path ────────────────────────────────────────
        val crumb  = ensureSession()
        val joined = URLEncoder.encode(symbols.joinToString(","), "UTF-8")
        val fields = "regularMarketPrice,regularMarketChangePercent,regularMarketChange," +
                     "regularMarketDayHigh,regularMarketDayLow,regularMarketVolume," +
                     "averageDailyVolume10Day,regularMarketOpen,regularMarketPreviousClose," +
                     "longName,shortName"
        val url = "$BASE/v7/finance/quote?symbols=$joined&fields=$fields${crumbParam(crumb)}"
        Log.d(TAG, "→ batch ${symbols.size} symbols (crumb=${crumb.isNotEmpty()})")
        return runCatching {
            val body = executeApi(url) ?: return emptyList()
            parseBatchQuotes(body)
        }.getOrElse {
            Log.w(TAG, "Batch quote error: ${it.message}")
            emptyList()
        }
    }

    /**
     * Fetch daily OHLCV history. Returns bars oldest→newest.
     * When [WORKER_URL] is set, calls the Cloudflare Worker.
     * Never throws — returns empty list on error.
     */
    suspend fun getHistory(symbol: String, days: Int = 90): List<Bar> {
        // ── Worker path ──────────────────────────────────────────────────────
        if (useWorker) {
            val url = "$WORKER_URL/history?symbol=${URLEncoder.encode(symbol, "UTF-8")}&days=$days"
            Log.d(TAG, "→ [worker] history $symbol ($days days)")
            return runCatching {
                val body = executeWith(workerClient, url) ?: return emptyList()
                parseHistory(body).takeLast(days)
            }.getOrElse {
                Log.w(TAG, "Worker history error for $symbol: ${it.message}")
                emptyList()
            }
        }

        // ── Direct Yahoo Finance path ────────────────────────────────────────
        val crumb = ensureSession()
        val range = when {
            days <= 30  -> "1mo"
            days <= 90  -> "3mo"
            days <= 180 -> "6mo"
            else        -> "1y"
        }
        val url = "$BASE/v8/finance/chart/$symbol?interval=1d&range=$range${crumbParam(crumb)}"
        Log.d(TAG, "→ history $symbol ($range)")
        return runCatching {
            val body = executeApi(url) ?: return emptyList()
            parseHistory(body).takeLast(days)
        }.getOrElse {
            Log.w(TAG, "History error for $symbol: ${it.message}")
            emptyList()
        }
    }

    suspend fun getAssetProfile(symbol: String): AssetProfile? {
        // ── Worker path ──────────────────────────────────────────────────────
        if (useWorker) {
            val url = "$WORKER_URL/profile?symbol=${URLEncoder.encode(symbol, "UTF-8")}"
            Log.d(TAG, "→ [worker] asset profile $symbol")
            return runCatching {
                val body = executeWith(workerClient, url) ?: return null
                parseAssetProfile(body)
            }.getOrElse {
                Log.w(TAG, "Worker profile error for $symbol: ${it.message}")
                null
            }
        }

        // ── Direct Yahoo Finance path ────────────────────────────────────────
        val crumb = ensureSession()
        val url = "$BASE/v10/finance/quoteSummary/$symbol?modules=assetProfile${crumbParam(crumb)}"
        Log.d(TAG, "→ asset profile $symbol")
        return runCatching {
            val body = executeApi(url) ?: return null
            parseAssetProfile(body)
        }.getOrElse {
            Log.w(TAG, "Asset profile error for $symbol: ${it.message}")
            null
        }
    }

    /**
     * Screens the full NSE market via Yahoo Finance's screener API.
     *
     * Server-side filters applied:
     *   • exchange = NSI (NSE India only)
     *   • intradayprice ≤ maxPrice  ← key: Yahoo returns ONLY affordable stocks
     *
     * Because the price filter is on the server, the 250 slots per page are used
     * exclusively for affordable stocks — so for any capital level we get ALL of them,
     * not just the ones that happen to appear in the top 250 by market cap.
     *
     * Two pages are fired in parallel (offset 0 + 250) to handle edge cases where
     * the user's capital is high enough to afford 250+ stocks.
     *
     * Volume filter is applied client-side as a safety net.
     * No hardcoded list. Never throws; returns empty list on error.
     */
    suspend fun screenNseStocks(
        minVolume : Long   = 1_000_000,
        maxPrice  : Double = Double.MAX_VALUE,
    ): List<Quote> {
        Log.d(TAG, "→ NSE screener (maxPrice=₹$maxPrice, minVol=${minVolume / 1_000_000}M)")

        // ── Worker path ──────────────────────────────────────────────────────
        // The Worker does both pages + 5-min cache server-side, so one GET is enough.
        if (useWorker) {
            val params = buildString {
                if (maxPrice.isFinite() && maxPrice < Double.MAX_VALUE)
                    append("maxPrice=$maxPrice&")
                append("minVolume=$minVolume")
            }
            val url = "$WORKER_URL/screener?$params"
            Log.d(TAG, "→ [worker] screener")
            val result = runCatching {
                val body = executeWith(workerClient, url) ?: return emptyList()
                parseScreenerQuotes(body)
                    .filter { it.avgVolume >= minVolume || it.volume >= minVolume }
            }.getOrElse { e ->
                Log.w(TAG, "Worker screener error: ${e.message}")
                emptyList()
            }
            if (result.isNotEmpty()) {
                Log.d(TAG, "[worker] screener: ${result.size} liquid stocks ≤ ₹$maxPrice")
                return result
            }
            // Worker failed — fall through to direct Yahoo Finance below
            Log.w(TAG, "[worker] screener returned empty — falling back to direct Yahoo Finance")
        }

        // ── Direct Yahoo Finance path ────────────────────────────────────────
        // Attempt screener up to 2 times — retry once if session was bad
        for (attempt in 1..2) {
            val crumb   = ensureSession()
            val crumbQs = if (crumb.isNotEmpty()) "&crumb=${URLEncoder.encode(crumb, "UTF-8")}" else ""
            val url     = "$BASE/v1/finance/screener?formatted=false&lang=en-US&region=IN$crumbQs"

            val result = runCatching {
                coroutineScope {
                    val page1 = async { executeApiPost(url, screenerBody(0,   250, maxPrice)) }
                    val page2 = async { executeApiPost(url, screenerBody(250, 250, maxPrice)) }
                    val quotes1 = page1.await()?.let { parseScreenerQuotes(it) } ?: emptyList()
                    val quotes2 = page2.await()?.let { parseScreenerQuotes(it) } ?: emptyList()
                    (quotes1 + quotes2)
                        .distinctBy { it.symbol }
                        .filter { it.avgVolume >= minVolume || it.volume >= minVolume }
                }
            }.getOrElse { e ->
                Log.w(TAG, "Screener attempt $attempt error: ${e.message}")
                emptyList()
            }

            if (result.isNotEmpty()) {
                Log.d(TAG, "Screener attempt $attempt: ${result.size} liquid stocks ≤ ₹$maxPrice")
                return result
            }

            // Screener returned empty — reset session and retry once
            if (attempt == 1) {
                Log.w(TAG, "Screener returned empty on attempt 1 — resetting session for retry")
                resetSession()
            }
        }

        // Both screener attempts failed → fall back to batch-quoting known liquid NSE stocks
        Log.w(TAG, "Screener failed after 2 attempts — using fallback batch quote list")
        return fallbackBatchQuotes(maxPrice = maxPrice, minVolume = minVolume)
    }

    /**
     * Fallback when the screener API is unavailable.
     * Batch-fetches ~60 known liquid NSE stocks and filters to [maxPrice] client-side.
     * Covers a wide price range so it works for any capital level.
     */
    private suspend fun fallbackBatchQuotes(maxPrice: Double, minVolume: Long): List<Quote> {
        // ~60 highly liquid NSE stocks spanning ₹5 – ₹3500+
        val symbols = listOf(
            // ₹5–₹30 (very low capital)
            "YESBANK.NS", "IDEA.NS", "RPOWER.NS", "NHPC.NS", "SJVN.NS",
            // ₹30–₹100
            "SUZLON.NS", "IRFC.NS", "PFC.NS", "RECLTD.NS", "SAIL.NS",
            "NMDC.NS", "BANKBARODA.NS", "CANBK.NS", "UNIONBANK.NS", "UCOBANK.NS",
            "MAHABANK.NS", "CENTRALBK.NS", "INDIANB.NS", "PUNJABNATBNK.NS", "IOC.NS",
            // ₹100–₹300
            "ONGC.NS", "COALINDIA.NS", "NTPC.NS", "POWERGRID.NS", "GAIL.NS",
            "BPCL.NS", "NATIONALUM.NS", "HINDCOPPER.NS", "TATASTEEL.NS", "ADANIPOWER.NS",
            "TATAPOWER.NS", "VEDL.NS", "JSWSTEEL.NS", "HINDALCO.NS", "ITC.NS",
            // ₹300–₹800
            "SBIN.NS", "WIPRO.NS", "TATAMOTORS.NS", "ADANIPORTS.NS", "ZOMATO.NS",
            "HCLTECH.NS", "TECHM.NS", "LT.NS", "ADANIENT.NS", "BHARTIARTL.NS",
            // ₹800–₹2000
            "AXISBANK.NS", "ICICIBANK.NS", "HDFCBANK.NS", "INFY.NS", "KOTAKBANK.NS",
            "BAJFINANCE.NS", "MARUTI.NS", "ASIANPAINT.NS", "SUNPHARMA.NS", "DRREDDY.NS",
            // ₹2000+
            "TCS.NS", "RELIANCE.NS", "TITAN.NS", "ULTRACEMCO.NS", "NESTLEIND.NS",
        )

        // Batch in groups of 20 (Yahoo Finance limit)
        val allQuotes = symbols.chunked(20).flatMap { batch ->
            runCatching { getBatchQuotes(batch) }.getOrElse { emptyList() }
        }

        return allQuotes
            .filter { it.price in 1.0..maxPrice }
            .filter { it.avgVolume >= minVolume || it.volume >= minVolume }
            .distinctBy { it.symbol }
            .also { Log.d(TAG, "Fallback batch: ${it.size} stocks ≤ ₹$maxPrice") }
    }

    /**
     * Builds the Yahoo Finance screener POST body.
     * Always filters by exchange=NSI. Adds a price ceiling when [maxPrice] is finite.
     */
    private fun screenerBody(offset: Int, size: Int, maxPrice: Double = Double.MAX_VALUE): String {
        val priceFilter = if (maxPrice.isFinite() && maxPrice < Double.MAX_VALUE) {
            // Add 1 rupee buffer so a stock at exactly ₹capital passes (rounding safety)
            """,{ "operator": "LT", "operands": ["intradayprice", ${maxPrice + 1.0}] }"""
        } else {
            ""
        }
        return """
            {
              "offset": $offset,
              "size": $size,
              "sortField": "intradaymarketcap",
              "sortType": "DESC",
              "quoteType": "EQUITY",
              "topOperator": "AND",
              "query": {
                "operator": "AND",
                "operands": [
                  { "operator": "EQ", "operands": ["exchange", "NSI"] }$priceFilter
                ]
              },
              "userId": "",
              "userIdType": "guid"
            }
        """.trimIndent()
    }

    // ── JSON parsers ──────────────────────────────────────────────────────────

    private fun parseBatchQuotes(json: String): List<Quote> = runCatching {
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        val results = JsonParser.parseReader(reader).asJsonObject
            .getAsJsonObject("quoteResponse")
            ?.getAsJsonArray("result") ?: return emptyList()
        results.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                Quote(
                    symbol    = o.get("symbol")?.asString ?: return@mapNotNull null,
                    name      = o.get("longName")?.takeIf  { !it.isJsonNull }?.asString
                                ?: o.get("shortName")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    price     = o.get("regularMarketPrice")?.takeIf { !it.isJsonNull }
                                  ?.asDouble ?: return@mapNotNull null,
                    change    = o.get("regularMarketChange")?.takeIf            { !it.isJsonNull }?.asDouble ?: 0.0,
                    changePct = o.get("regularMarketChangePercent")?.takeIf     { !it.isJsonNull }?.asDouble ?: 0.0,
                    dayHigh   = o.get("regularMarketDayHigh")?.takeIf           { !it.isJsonNull }?.asDouble ?: 0.0,
                    dayLow    = o.get("regularMarketDayLow")?.takeIf            { !it.isJsonNull }?.asDouble ?: 0.0,
                    volume    = o.get("regularMarketVolume")?.takeIf            { !it.isJsonNull }?.asLong   ?: 0L,
                    avgVolume = o.get("averageDailyVolume10Day")?.takeIf        { !it.isJsonNull }?.asLong   ?: 0L,
                    open      = o.get("regularMarketOpen")?.takeIf              { !it.isJsonNull }?.asDouble ?: 0.0,
                    prevClose = o.get("regularMarketPreviousClose")?.takeIf     { !it.isJsonNull }?.asDouble ?: 0.0,
                    marketCap = o.get("marketCap")?.takeIf                      { !it.isJsonNull }?.asDouble ?: 0.0,
                )
            }.getOrNull()
        }
    }.getOrElse {
        Log.w(TAG, "parseBatchQuotes failed: ${it.message}")
        emptyList()
    }

    private fun parseHistory(json: String): List<Bar> = runCatching {
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        val result = JsonParser.parseReader(reader).asJsonObject
            .getAsJsonObject("chart")
            ?.getAsJsonArray("result")?.get(0)?.asJsonObject ?: return emptyList()
        val timestamps = result.getAsJsonArray("timestamp") ?: return emptyList()
        val q = result.getAsJsonObject("indicators")
            ?.getAsJsonArray("quote")?.get(0)?.asJsonObject ?: return emptyList()

        val opens   = q.getAsJsonArray("open")
        val highs   = q.getAsJsonArray("high")
        val lows    = q.getAsJsonArray("low")
        val closes  = q.getAsJsonArray("close")
        val volumes = q.getAsJsonArray("volume")

        timestamps.mapIndexedNotNull { i, _ ->
            val c = closes?.get(i)?.takeIf { !it.isJsonNull } ?: return@mapIndexedNotNull null
            runCatching {
                Bar(
                    open   = opens?.get(i)?.takeIf   { !it.isJsonNull }?.asDouble ?: 0.0,
                    high   = highs?.get(i)?.takeIf   { !it.isJsonNull }?.asDouble ?: 0.0,
                    low    = lows?.get(i)?.takeIf    { !it.isJsonNull }?.asDouble ?: 0.0,
                    close  = c.asDouble,
                    volume = volumes?.get(i)?.takeIf { !it.isJsonNull }?.asLong   ?: 0L,
                )
            }.getOrNull()
        }
    }.getOrElse {
        Log.w(TAG, "parseHistory failed: ${it.message}")
        emptyList()
    }

    private fun parseScreenerQuotes(json: String): List<Quote> = runCatching {
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        // Response: { "finance": { "result": [ { "quotes": [...] } ] } }
        val quotes = JsonParser.parseReader(reader).asJsonObject
            .getAsJsonObject("finance")
            ?.getAsJsonArray("result")?.get(0)?.asJsonObject
            ?.getAsJsonArray("quotes") ?: return emptyList()

        quotes.mapNotNull { el ->
            runCatching {
                val o = el.asJsonObject
                Quote(
                    symbol    = o.get("symbol")?.asString ?: return@mapNotNull null,
                    name      = o.get("shortName")?.takeIf { !it.isJsonNull }?.asString
                                ?: o.get("longName")?.takeIf { !it.isJsonNull }?.asString ?: "",
                    price     = o.get("regularMarketPrice")?.takeIf { !it.isJsonNull }
                                  ?.asDouble ?: return@mapNotNull null,
                    change    = o.get("regularMarketChange")?.takeIf            { !it.isJsonNull }?.asDouble ?: 0.0,
                    changePct = o.get("regularMarketChangePercent")?.takeIf     { !it.isJsonNull }?.asDouble ?: 0.0,
                    dayHigh   = o.get("regularMarketDayHigh")?.takeIf           { !it.isJsonNull }?.asDouble ?: 0.0,
                    dayLow    = o.get("regularMarketDayLow")?.takeIf            { !it.isJsonNull }?.asDouble ?: 0.0,
                    volume    = o.get("regularMarketVolume")?.takeIf            { !it.isJsonNull }?.asLong   ?: 0L,
                    avgVolume = o.get("averageDailyVolume3Month")?.takeIf       { !it.isJsonNull }?.asLong
                                ?: o.get("averageDailyVolume10Day")?.takeIf     { !it.isJsonNull }?.asLong   ?: 0L,
                    open      = o.get("regularMarketOpen")?.takeIf              { !it.isJsonNull }?.asDouble ?: 0.0,
                    prevClose = o.get("regularMarketPreviousClose")?.takeIf     { !it.isJsonNull }?.asDouble ?: 0.0,
                    marketCap = o.get("intradaymarketcap")?.takeIf              { !it.isJsonNull }?.asDouble
                                ?: o.get("marketCap")?.takeIf                    { !it.isJsonNull }?.asDouble ?: 0.0,
                )
            }.getOrNull()
        }
    }.getOrElse {
        Log.w(TAG, "parseScreenerQuotes failed: ${it.message}")
        emptyList()
    }

    private fun parseAssetProfile(json: String): AssetProfile? = runCatching {
        val reader = JsonReader(StringReader(json)).apply { isLenient = true }
        val profile = JsonParser.parseReader(reader).asJsonObject
            .getAsJsonObject("quoteSummary")
            ?.getAsJsonArray("result")?.get(0)?.asJsonObject
            ?.getAsJsonObject("assetProfile") ?: return null

        AssetProfile(
            sector = profile.get("sector")?.takeIf { !it.isJsonNull }?.asString ?: "",
            industry = profile.get("industry")?.takeIf { !it.isJsonNull }?.asString ?: "",
        )
    }.getOrElse {
        Log.w(TAG, "parseAssetProfile failed: ${it.message}")
        null
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /**
     * Plain browser-like GET (used for homepage warmup).
     * Uses the base [client] with Accept: text/html headers.
     */
    private suspend fun executeRaw(url: String): String? = executeWith(client, url)

    /**
     * API GET with Yahoo Finance headers (Referer/Origin).
     * Returns body string, or null on 4xx/5xx.
     * Throws [IOException] on network failure.
     */
    private suspend fun executeApi(url: String): String? = executeWith(apiClient, url)

    /** API POST — used for the screener endpoint. */
    private suspend fun executeApiPost(url: String, jsonBody: String): String? =
        suspendCancellableCoroutine { cont ->
            val body = jsonBody.toRequestBody("application/json".toMediaType())
            val call = apiClient.newCall(Request.Builder().url(url).post(body).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) return
                    response.use { resp ->
                        if (resp.code == 200) cont.resume(resp.body?.string())
                        else { Log.w(TAG, "HTTP ${resp.code} (POST) for $url"); cont.resume(null) }
                    }
                }
            })
        }

    private suspend fun executeWith(httpClient: OkHttpClient, url: String): String? =
        suspendCancellableCoroutine { cont ->
            val call = httpClient.newCall(Request.Builder().url(url).build())
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (!cont.isActive) return
                    response.use { resp ->
                        when {
                            resp.code == 200 -> cont.resume(resp.body?.string())
                            else -> {
                                Log.w(TAG, "HTTP ${resp.code} for $url")
                                cont.resume(null)
                            }
                        }
                    }
                }
            })
        }
}
