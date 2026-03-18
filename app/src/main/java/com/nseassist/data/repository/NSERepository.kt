package com.nseassist.data.repository

import com.nseassist.util.AppLogger
import com.nseassist.analysis.StockScorer
import com.nseassist.analysis.TechnicalIndicators
import com.nseassist.analysis.TrendDetector
import com.nseassist.data.api.MemoryClient
import com.nseassist.data.api.NewsClient
import com.nseassist.data.api.UpstoxClient
import com.nseassist.data.api.UpstoxTokenExpiredException
import com.nseassist.data.api.YahooFinanceClient
import com.nseassist.data.local.AiSettingsStore
import com.nseassist.data.model.PredictionLogRequest
import kotlinx.coroutines.launch
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.MarketStatus
import com.nseassist.data.model.MoverItem
import com.nseassist.data.model.QuickTake
import com.nseassist.data.model.ScanCategory
import com.nseassist.data.model.StockData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.ZonedDateTime

class NSERepository {

    private val indicators    = TechnicalIndicators()
    private val trendDetector = TrendDetector()
    private val scorer        = StockScorer()

    // ── Data Source (set by MainViewModel when user changes preference) ──────────
    // "yahoo"      → Yahoo Finance only (default)
    // "upstox"     → Upstox for live snapshot; Yahoo Finance for historical data
    var dataSource: String = AiSettingsStore.DATA_SOURCE_YAHOO
    var upstoxAccessToken: String = ""

    /** Called when Upstox returns 401 — token expired or invalidated.
     *  MainViewModel registers this to update UI state and clear the token. */
    var onUpstoxTokenExpired: (() -> Unit)? = null

    // ── Session-level caches (in-memory, cleared on next cold start) ─────────────

    // Asset profiles are fetched once per session — sector/industry never changes intraday
    private val profileCache = HashMap<String, Pair<Long, YahooFinanceClient.AssetProfile?>>()
    private val PROFILE_TTL  = 3_600_000L   // 1 hour

    // NIFTY market condition cached so 15 Phase-2 stocks don't each re-fetch it
    private var niftyConditionCache: StockScorer.MarketCondition? = null
    private var niftyConditionTimestamp = 0L
    private val NIFTY_TTL = 300_000L   // 5 minutes

    // ── Market Overview ─────────────────────────────────────────────────────────

    suspend fun getMarketOverview(): Result<MarketOverview> = withContext(Dispatchers.IO) {
        runCatching {
            // One batch call gets both benchmark indices
            val indices = withTimeoutOrNull(20_000L) {
                YahooFinanceClient.getBatchQuotes(listOf("^NSEI", "^NSEBANK"))
            } ?: emptyList()

            val nifty     = indices.firstOrNull { it.symbol == "^NSEI" }
            val bankNifty = indices.firstOrNull { it.symbol == "^NSEBANK" }

            // Fetch screener once — shared by both gainers and losers sort
            val screenerQuotes = withTimeoutOrNull(30_000L) {
                YahooFinanceClient.screenNseStocks(minVolume = 1_000_000)
            } ?: emptyList()
            val gainers = topMoversFromList(screenerQuotes, isGainers = true)
            val losers  = topMoversFromList(screenerQuotes, isGainers = false)

            val niftyVwap     = nifty?.let     { (it.dayHigh + it.dayLow + it.price) / 3 } ?: 0.0
            val bankNiftyVwap = bankNifty?.let { (it.dayHigh + it.dayLow + it.price) / 3 } ?: 0.0

            MarketOverview(
                nifty50            = nifty?.price     ?: 0.0,
                nifty50Change      = nifty?.change    ?: 0.0,
                nifty50ChangePct   = nifty?.changePct ?: 0.0,
                bankNifty          = bankNifty?.price     ?: 0.0,
                bankNiftyChange    = bankNifty?.change    ?: 0.0,
                bankNiftyChangePct = bankNifty?.changePct ?: 0.0,
                niftyAboveVwap     = (nifty?.price ?: 0.0) > niftyVwap,
                bankNiftyAboveVwap = (bankNifty?.price ?: 0.0) > bankNiftyVwap,
                marketStatus       = currentMarketStatus(),
                topGainers         = gainers,
                topLosers          = losers,
            )
        }
    }

    // ── Price lookup (used by auto-resolve) ───────────────────────────────────

    /** Fetches current LTP for a list of symbols (no .NS suffix needed).
     *  Returns map of symbol → price. Missing symbols are simply absent from the map. */
    suspend fun fetchCurrentPrices(symbols: List<String>): Map<String, Double> =
        withContext(Dispatchers.IO) {
            if (symbols.isEmpty()) return@withContext emptyMap()
            val cleanSymbols = symbols.map { it.removeSuffix(".NS") }

            val nsSymbols = cleanSymbols.map { "$it.NS" }
            val quotes    = YahooFinanceClient.getBatchQuotes(nsSymbols)
            quotes.associate { it.symbol.removeSuffix(".NS") to it.price }
        }

    // ── Stock Scan ────────────────────────────────────────────────────────────

    suspend fun scanAffordableStocks(capital: Double, category: ScanCategory): Result<List<StockData>> =
        withContext(Dispatchers.IO) {
            runCatching {
                AppLogger.d("SCAN", "Start — capital=₹$capital category=${category.routeValue} source=$dataSource")

                // Fetch indices for market condition detection in parallel with stock screener
                val indicesDeferred = async {
                    withTimeoutOrNull(15_000L) {
                        YahooFinanceClient.getBatchQuotes(listOf("^NSEI", "^NSEBANK"))
                    } ?: emptyList()
                }
                // Two parallel screener pages → top 500 NSE stocks by market cap
                val allQuotes = withTimeoutOrNull(45_000L) {
                    YahooFinanceClient.screenNseStocks(minVolume = 1_000_000, maxPrice = capital)
                } ?: emptyList()

                val indices = indicesDeferred.await()
                val nifty = indices.firstOrNull { it.symbol == "^NSEI" }
                val marketCondition = scorer.detectMarketCondition(
                    niftyChangePct = nifty?.changePct ?: 0.0,
                    niftyAboveVwap = nifty?.let { (it.dayHigh + it.dayLow + it.price) / 3 < it.price } ?: true,
                )
                AppLogger.d("SCAN", "Market: $marketCondition NIFTY=${String.format("%.2f", nifty?.changePct ?: 0.0)}%")

                AppLogger.d("SCAN", "Screener returned ${allQuotes.size} liquid NSE stocks")

                val snapshots = allQuotes.mapNotNull { q ->
                    if (q.price !in 1.0..capital) return@mapNotNull null
                    val name    = q.symbol.removeSuffix(".NS")
                    val vwap    = (q.dayHigh + q.dayLow + q.price) / 3
                    val gapType = when {
                        q.open > q.prevClose * 1.005 -> "GAP UP"
                        q.open < q.prevClose * 0.995 -> "GAP DOWN"
                        else                         -> "FLAT"
                    }
                    val sd = StockData(
                        symbol      = name,
                        name        = q.name.ifBlank { name },
                        ltp         = q.price,
                        change      = q.change,
                        changePct   = q.changePct,
                        dayHigh     = q.dayHigh,
                        dayLow      = q.dayLow,
                        volume      = q.volume,
                        avgVolume   = q.avgVolume,
                        vwap        = vwap,
                        open        = q.open,
                        aboveVwap   = q.price > vwap,
                        volumeSpike = q.avgVolume > 0 && q.volume > q.avgVolume * 1.5,
                        gapType     = gapType,
                        marketCap   = q.marketCap,
                    )
                    val score  = scorer.score(sd, marketCondition)
                    val option = scorer.optionAction(sd)
                    sd.copy(score = score, optionAction = option)
                }.filter { stock -> matchesCategory(stock, category) }

                if (snapshots.isEmpty()) {
                    if (allQuotes.isEmpty()) {
                        error(
                            "Could not fetch stock data from Yahoo Finance.\n\n" +
                            "• Make sure you have an active internet connection\n" +
                            "• Try again in a few seconds — Yahoo Finance may be throttling\n" +
                            "• If the issue persists, try opening finance.yahoo.com in Chrome first"
                        )
                    } else {
                        val cheapest     = allQuotes.minByOrNull { it.price }
                        val cheapestStr  = cheapest?.let {
                            "₹${String.format("%.0f", it.price)} (${it.symbol.removeSuffix(".NS")})"
                        } ?: "unknown"
                        error(
                            "No ${category.label.lowercase()} stocks found within ₹${String.format("%.0f", capital)}.\n\n" +
                            "Cheapest liquid stock today: $cheapestStr\n\n" +
                            "Stocks like Hindustan Motors or Imagica are excluded — " +
                            "they are illiquid with <1M daily volume, unsafe for intraday.\n\n" +
                            "Minimum capital needed: ₹${String.format("%.0f",
                                cheapest?.price?.let { kotlin.math.ceil(it) } ?: 20.0)}"
                        )
                    }
                }

                AppLogger.d("SCAN", "After price+category filter: ${snapshots.size} stocks")

                // ── Live data overlay: replace Yahoo snapshot with real-time data ──────────
                val finalSnapshots = if (dataSource == AiSettingsStore.DATA_SOURCE_UPSTOX && upstoxAccessToken.isNotBlank()) {
                    overlayUpstoxQuotes(snapshots)
                } else {
                    snapshots
                }

                finalSnapshots.sortedByDescending { kotlin.math.abs(it.changePct) }
            }
        }

    /** Fetches live Upstox quotes for the given stock list and overlays real-time
     *  fields (ltp, change, changePct, dayHigh, dayLow, volume, vwap, aboveVwap, gapType).
     *  Yahoo Finance fields that Upstox doesn't provide (avgVolume, marketCap, name)
     *  are preserved as-is from the Yahoo screener result. */
    private suspend fun overlayUpstoxQuotes(stocks: List<StockData>): List<StockData> {
        val symbols = stocks.map { it.symbol }
        AppLogger.d("UPSTOX", "Overlay: fetching live quotes for ${symbols.size} stocks")
        val upstoxQuotes = runCatching {
            withTimeoutOrNull(20_000L) {
                UpstoxClient.getBatchQuotes(symbols, upstoxAccessToken)
            } ?: emptyList()
        }.getOrElse { e ->
            if (e is UpstoxTokenExpiredException) {
                AppLogger.w("UPSTOX", "401 — token expired, falling back to Yahoo Finance")
                upstoxAccessToken = ""          // stop further Upstox calls this session
                onUpstoxTokenExpired?.invoke()  // notify ViewModel to update UI
            } else {
                AppLogger.e("UPSTOX", "Overlay failed: ${e.message}")
            }
            emptyList()
        }

        if (upstoxQuotes.isEmpty()) {
            AppLogger.w("UPSTOX", "0 quotes returned — keeping Yahoo data")
            return stocks
        }

        val quoteMap = upstoxQuotes.associateBy { it.symbol }
        val overlaid = stocks.map { sd ->
            val uq = quoteMap[sd.symbol] ?: return@map sd
            val gapType = when {
                uq.open > uq.prevClose * 1.005 -> "GAP UP"
                uq.open < uq.prevClose * 0.995 -> "GAP DOWN"
                else                            -> "FLAT"
            }
            sd.copy(
                ltp         = uq.ltp,
                change      = uq.change,
                changePct   = uq.changePct,
                dayHigh     = uq.dayHigh,
                dayLow      = uq.dayLow,
                volume      = uq.volume,
                vwap        = uq.vwap,
                open        = uq.open,
                aboveVwap   = uq.ltp > uq.vwap,
                volumeSpike = sd.avgVolume > 0 && uq.volume > sd.avgVolume * 1.5,
                gapType     = gapType,
            )
        }
        AppLogger.d("UPSTOX", "Overlay applied to ${overlaid.count { quoteMap.containsKey(it.symbol) }}/${overlaid.size} stocks")
        return overlaid
    }

    // ── Full Deep Analysis for one stock ───────────────────────────────────────

    // fetchNews = false during Phase 2 batch scans (saves ~18s per stock when Google News is slow)
    // fetchNews = true only on the Stock Detail screen where the user actually reads the news
    suspend fun analyseStock(ticker: String, fetchNews: Boolean = false): Result<StockData> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSymbol  = ticker.removeSuffix(".NS")
            val yahooSymbol  = "$cleanSymbol.NS"
            AppLogger.d("SCAN", "Deep analysis: $yahooSymbol fetchNews=$fetchNews")

            // Fetch quote, 90-day history, asset profile, and 5-min data in parallel
            val quoteDeferred    = async {
                withTimeoutOrNull(20_000L) { YahooFinanceClient.getBatchQuotes(listOf(yahooSymbol)) }
            }
            val historyDeferred  = async {
                withTimeoutOrNull(25_000L) { YahooFinanceClient.getHistory(yahooSymbol, days = 90) }
            }
            val profileDeferred  = async { cachedProfile(yahooSymbol) }
            val intradayDeferred = async {
                withTimeoutOrNull(15_000L) { YahooFinanceClient.get30MinData(yahooSymbol) }
            }

            val quoteList = quoteDeferred.await()    ?: emptyList()
            val history   = historyDeferred.await()  ?: emptyList()
            val profile   = profileDeferred.await()
            val intraday  = intradayDeferred.await()

            val quote = quoteList.firstOrNull()
                ?: error("No data for $yahooSymbol — check internet connection")

            // ── Live quote overlay for Phase 2 ────────────────────────────────
            // Upstox replaces Yahoo's 15-min-delayed price fields.
            // Historical data (RSI, EMA, MACD) always comes from Yahoo Finance.

            val upstoxQuote: UpstoxClient.UpstoxQuote? = if (
                dataSource == AiSettingsStore.DATA_SOURCE_UPSTOX && upstoxAccessToken.isNotBlank()
            ) {
                runCatching {
                    withTimeoutOrNull(10_000L) {
                        UpstoxClient.getBatchQuotes(listOf(cleanSymbol), upstoxAccessToken)
                            .firstOrNull()
                    }
                }.getOrElse { e ->
                    if (e is UpstoxTokenExpiredException) {
                        AppLogger.w("UPSTOX", "401 in Phase 2 — token expired")
                        upstoxAccessToken = ""
                        onUpstoxTokenExpired?.invoke()
                    } else {
                        AppLogger.e("UPSTOX", "Phase 2 quote failed: ${e.message}")
                    }
                    null
                }
            } else null

            // News only fetched when explicitly requested (detail screen) — skipped during scans
            val news = if (fetchNews) {
                runCatching {
                    withTimeoutOrNull(18_000L) {
                        NewsClient.fetchNews(
                            stockSymbol = cleanSymbol,
                            companyName = quote.name.ifBlank { cleanSymbol },
                            sector = profile?.sector.orEmpty(),
                            industry = profile?.industry.orEmpty(),
                        )
                    }
                }.getOrNull()
            } else null

            val closes  = history.map { it.close }
            val highs   = history.map { it.high }
            val lows    = history.map { it.low }
            val volumes = history.map { it.volume.toDouble() }

            // Priority: Upstox > Yahoo Finance
            val ltp       = upstoxQuote?.ltp       ?: quote.price
            val prevClose = upstoxQuote?.prevClose ?: quote.prevClose
            val change    = upstoxQuote?.change    ?: quote.change
            val changePct = upstoxQuote?.changePct ?: quote.changePct
            val dayHigh   = upstoxQuote?.dayHigh   ?: quote.dayHigh
            val dayLow    = upstoxQuote?.dayLow    ?: quote.dayLow
            val volume    = upstoxQuote?.volume    ?: quote.volume
            val open      = upstoxQuote?.open      ?: quote.open
            val vwap      = upstoxQuote?.vwap      ?: (dayHigh + dayLow + ltp) / 3
            val aboveVwap = ltp > vwap
            if (upstoxQuote != null) { AppLogger.d("UPSTOX", "Phase 2 live: $cleanSymbol LTP=₹$ltp change=${String.format("%.2f", changePct)}%") }

            // Technical indicators
            val rsi                    = indicators.rsi(closes, period = 14)
            val ema20                  = indicators.ema(closes, period = 20)
            val ema50                  = indicators.ema(closes, period = 50)
            val (macdLine, macdSignal) = indicators.macd(closes)
            val atr                    = indicators.atr(highs, lows, closes, period = 14)
            val bollinger              = indicators.bollingerBands(closes)
            val adxResult              = indicators.adx(highs, lows, closes)
            val candleResult           = indicators.detectCandlePattern(history.map { it.open }, highs, lows, closes)

            val resistance = indicators.swingHighResistance(highs)
            val support    = indicators.swingLowSupport(lows)

            val trend  = trendDetector.detect(closes, volumes.map { it.toLong() })
            val trend6M = indicators.trend6Month(closes)
            val trend2W = indicators.trend2Week(closes)

            val prediction = indicators.predictPrice(closes, atr, volumes)

            val gapType = when {
                open > prevClose * 1.005 -> "GAP UP"
                open < prevClose * 0.995 -> "GAP DOWN"
                else -> "FLAT"
            }

            // ── 30-min intraday analysis ─────────────────────────────────────
            val thirtyMinBars = intraday?.candles ?: emptyList()
            val thirtyMinCandle = if (thirtyMinBars.size >= 2) {
                indicators.detectCandlePattern(
                    opens  = thirtyMinBars.map { it.open },
                    highs  = thirtyMinBars.map { it.high },
                    lows   = thirtyMinBars.map { it.low },
                    closes = thirtyMinBars.map { it.close },
                )
            } else null

            val supertrend30m = if (thirtyMinBars.size >= 10) {
                indicators.supertrend(
                    highs   = thirtyMinBars.map { it.high },
                    lows    = thirtyMinBars.map { it.low },
                    closes  = thirtyMinBars.map { it.close },
                )
            } else null

            val pivots = if ((intraday?.pivotCpp ?: 0.0) > 0) {
                TechnicalIndicators.PivotPoints(
                    cpp = intraday!!.pivotCpp,
                    r1  = intraday.pivotR1,
                    r2  = intraday.pivotR2,
                    s1  = intraday.pivotS1,
                    s2  = intraday.pivotS2,
                )
            } else if (highs.size >= 2) {
                // Fallback: compute pivots from daily history if intraday unavailable
                val pH = highs.dropLast(1).last()
                val pL = lows.dropLast(1).last()
                val pC = closes.dropLast(1).last()
                indicators.pivotPoints(pH, pL, pC)
            } else null

            val sessionPhase = currentSessionPhase()

            // Nudge prediction confidence based on 15-min candle + Supertrend signal
            val nudgedConfidence = nudgeConfidence(
                base          = prediction.confidence,
                candleSignal  = thirtyMinCandle?.signal,
                supertrendSig = supertrend30m?.signal ?: "NEUTRAL",
            )

            val stockData = StockData(
                symbol      = cleanSymbol,
                name        = quote.name.ifBlank { cleanSymbol },
                ltp         = ltp, change = change, changePct = changePct,
                dayHigh     = dayHigh, dayLow = dayLow,
                volume      = volume, avgVolume = quote.avgVolume,
                vwap        = vwap, open = open,
                rsi         = rsi, ema20 = ema20, ema50 = ema50,
                macdLine    = macdLine, macdSignal = macdSignal,
                trend6Month = trend6M, trend2Week = trend2W,
                trendSignalType      = trend.type,
                trendSignalLabel     = trend.label,
                streakDays           = trend.streakDays,
                predictedHigh        = prediction.high,
                predictedLow         = prediction.low,
                predictedDirection   = prediction.direction,
                predictionConfidence = nudgedConfidence,
                atr         = atr,
                bollingerUpper  = bollinger.upper,
                bollingerMiddle = bollinger.middle,
                bollingerLower  = bollinger.lower,
                adx         = adxResult.adx,
                adxDiPlus   = adxResult.diPlus,
                adxDiMinus  = adxResult.diMinus,
                candlePattern      = candleResult.type,
                candlePatternLabel = candleResult.label,
                candleSignal       = candleResult.signal.name,
                support     = support, resistance = resistance,
                aboveVwap   = aboveVwap,
                volumeSpike = quote.avgVolume > 0 && volume > quote.avgVolume * 1.5,
                gapType     = gapType,
                marketCap   = quote.marketCap,
                sector      = profile?.sector.orEmpty(),
                industry    = profile?.industry.orEmpty(),
                priceHistory  = closes,
                volumeHistory = volumes.map { it.toLong() },
                thirtyMinPattern      = thirtyMinCandle?.type  ?: "NONE",
                thirtyMinPatternLabel = thirtyMinCandle?.label ?: "",
                thirtyMinSignal       = thirtyMinCandle?.signal?.name ?: "NEUTRAL",
                orbHigh             = intraday?.orbHigh  ?: 0.0,
                orbLow              = intraday?.orbLow   ?: 0.0,
                supertrendSignal    = supertrend30m?.signal ?: "NEUTRAL",
                pivotCpp            = pivots?.cpp ?: 0.0,
                pivotR1             = pivots?.r1  ?: 0.0,
                pivotS1             = pivots?.s1  ?: 0.0,
                sessionPhase        = sessionPhase,
            )

            val marketCondition = cachedMarketCondition()
            val newsImpact = newsImpact(news)
            val score  = (scorer.score(stockData, marketCondition) + newsImpact).coerceIn(0.0, 100.0)
            val option = scorer.optionAction(stockData)
            val enriched = stockData.copy(score = score, optionAction = option, news = news, newsImpactScore = newsImpact, isDeepEnriched = true)
            val quickTake = generateQuickTake(enriched, thirtyMinCandle, pivots, supertrend30m?.signal ?: "NEUTRAL")
            if (quickTake != null) AppLogger.d("QT", "$cleanSymbol → ${quickTake.action} (${quickTake.confidence}%)")
            val result = enriched.copy(quickTake = quickTake)

            // Fire-and-forget prediction log: logs today's prediction and verifies yesterday's
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                runCatching {
                    MemoryClient.logPrediction(PredictionLogRequest(
                        symbol               = cleanSymbol,
                        predictionDirection  = result.predictedDirection,
                        predictedHigh        = result.predictedHigh,
                        predictedLow         = result.predictedLow,
                        confidence           = result.predictionConfidence,
                        actualClose          = result.ltp,
                    ))
                }
            }

            result
        }
    }

    // ── Cached helpers ────────────────────────────────────────────────────────

    /** Returns cached AssetProfile or fetches a fresh one (cached for 1 hour). */
    private suspend fun cachedProfile(symbol: String): YahooFinanceClient.AssetProfile? {
        val now    = System.currentTimeMillis()
        val cached = profileCache[symbol]
        if (cached != null && (now - cached.first) < PROFILE_TTL) return cached.second
        val fresh = withTimeoutOrNull(12_000L) { YahooFinanceClient.getAssetProfile(symbol) }
        profileCache[symbol] = now to fresh
        return fresh
    }

    /** Returns cached NIFTY MarketCondition or fetches a fresh one (cached for 5 min). */
    private suspend fun cachedMarketCondition(): StockScorer.MarketCondition {
        val now = System.currentTimeMillis()
        if (niftyConditionCache != null && (now - niftyConditionTimestamp) < NIFTY_TTL) {
            return niftyConditionCache!!
        }
        val niftyData = withTimeoutOrNull(10_000L) {
            YahooFinanceClient.getBatchQuotes(listOf("^NSEI"))
        } ?: emptyList()
        val nifty = niftyData.firstOrNull()
        val condition = scorer.detectMarketCondition(
            niftyChangePct = nifty?.changePct ?: 0.0,
            niftyAboveVwap = nifty?.let { (it.dayHigh + it.dayLow + it.price) / 3 < it.price } ?: true,
        )
        niftyConditionCache      = condition
        niftyConditionTimestamp  = now
        return condition
    }

    // ── Fast Phase-2 enrichment (reuses Phase-1 quote data) ───────────────────
    //
    // Compared to analyseStock():
    //   ✅ Skips quote re-fetch  — Phase 1 already has fresh LTP/change/volume
    //   ✅ Uses 60-day history   — enough for all indicators, smaller/faster payload
    //   ✅ Profile is cached     — only fetched once per session per stock
    //   ✅ NIFTY is cached       — fetched once, shared across all 15 Phase-2 stocks
    //
    suspend fun analyseStockFromPhase1(phase1: StockData, fetchNews: Boolean = false, orbBreakoutMode: Boolean = false): Result<StockData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanSymbol = phase1.symbol.removeSuffix(".NS")
                val yahooSymbol = "$cleanSymbol.NS"

                // Fetch history (60 days), profile, and 5-min data in parallel — skip quote fetch
                val historyDeferred  = async {
                    withTimeoutOrNull(25_000L) { YahooFinanceClient.getHistory(yahooSymbol, days = 60) }
                }
                val profileDeferred  = async { cachedProfile(yahooSymbol) }
                val intradayDeferred = async {
                    withTimeoutOrNull(15_000L) { YahooFinanceClient.get30MinData(yahooSymbol) }
                }

                val history  = historyDeferred.await()  ?: emptyList()
                val profile  = profileDeferred.await()
                val intraday = intradayDeferred.await()

                // News only on detail screen (same as analyseStock)
                val news = if (fetchNews) {
                    runCatching {
                        withTimeoutOrNull(18_000L) {
                            NewsClient.fetchNews(
                                stockSymbol = cleanSymbol,
                                companyName = phase1.name.ifBlank { cleanSymbol },
                                sector      = profile?.sector.orEmpty(),
                                industry    = profile?.industry.orEmpty(),
                            )
                        }
                    }.getOrNull()
                } else null

                val closes  = history.map { it.close }
                val highs   = history.map { it.high }
                val lows    = history.map { it.low }
                val volumes = history.map { it.volume.toDouble() }

                // Reuse Phase-1 quote fields (already fresh from screener)
                val ltp       = phase1.ltp
                val prevClose = phase1.ltp - phase1.change   // back-derive prevClose
                val vwap      = (phase1.dayHigh + phase1.dayLow + ltp) / 3

                val gapType = when {
                    phase1.open > prevClose * 1.005 -> "GAP UP"
                    phase1.open < prevClose * 0.995 -> "GAP DOWN"
                    else -> "FLAT"
                }

                val rsi                    = indicators.rsi(closes, period = 14)
                val ema20                  = indicators.ema(closes, period = 20)
                val ema50                  = indicators.ema(closes, period = 50)
                val (macdLine, macdSignal) = indicators.macd(closes)
                val atr                    = indicators.atr(highs, lows, closes, period = 14)
                val bollinger              = indicators.bollingerBands(closes)
                val adxResult              = indicators.adx(highs, lows, closes)
                val candleResult           = indicators.detectCandlePattern(
                    history.map { it.open }, highs, lows, closes
                )
                val support    = lows.takeLast(20).minOrNull()  ?: 0.0
                val resistance = highs.takeLast(20).maxOrNull() ?: 0.0
                val trend      = trendDetector.detect(closes, volumes.map { it.toLong() })
                val trend6M    = indicators.trend6Month(closes)
                val trend2W    = indicators.trend2Week(closes)
                val prediction = indicators.predictPrice(closes, atr, volumes)

                // ── 30-min intraday analysis ─────────────────────────────────
                val thirtyMinBars = intraday?.candles ?: emptyList()
                val thirtyMinCandle = if (thirtyMinBars.size >= 2) {
                    indicators.detectCandlePattern(
                        opens  = thirtyMinBars.map { it.open },
                        highs  = thirtyMinBars.map { it.high },
                        lows   = thirtyMinBars.map { it.low },
                        closes = thirtyMinBars.map { it.close },
                    )
                } else null

                val supertrend30m = if (thirtyMinBars.size >= 10) {
                    indicators.supertrend(
                        highs  = thirtyMinBars.map { it.high },
                        lows   = thirtyMinBars.map { it.low },
                        closes = thirtyMinBars.map { it.close },
                    )
                } else null

                val pivots = if ((intraday?.pivotCpp ?: 0.0) > 0) {
                    TechnicalIndicators.PivotPoints(
                        cpp = intraday!!.pivotCpp,
                        r1  = intraday.pivotR1,
                        r2  = intraday.pivotR2,
                        s1  = intraday.pivotS1,
                        s2  = intraday.pivotS2,
                    )
                } else if (highs.size >= 2) {
                    val pH = highs.dropLast(1).last()
                    val pL = lows.dropLast(1).last()
                    val pC = closes.dropLast(1).last()
                    indicators.pivotPoints(pH, pL, pC)
                } else null

                val sessionPhase = currentSessionPhase()

                // Nudge prediction confidence based on 15-min candle + Supertrend signal
                val nudgedConfidence = nudgeConfidence(
                    base          = prediction.confidence,
                    candleSignal  = thirtyMinCandle?.signal,
                    supertrendSig = supertrend30m?.signal ?: "NEUTRAL",
                )

                val stockData = phase1.copy(
                    vwap      = vwap,
                    aboveVwap = ltp > vwap,
                    gapType   = gapType,
                    rsi = rsi, ema20 = ema20, ema50 = ema50,
                    macdLine = macdLine, macdSignal = macdSignal,
                    trend6Month = trend6M, trend2Week = trend2W,
                    trendSignalType  = trend.type,
                    trendSignalLabel = trend.label,
                    streakDays       = trend.streakDays,
                    predictedHigh        = prediction.high,
                    predictedLow         = prediction.low,
                    predictedDirection   = prediction.direction,
                    predictionConfidence = nudgedConfidence,
                    atr = atr,
                    bollingerUpper  = bollinger.upper,
                    bollingerMiddle = bollinger.middle,
                    bollingerLower  = bollinger.lower,
                    adx       = adxResult.adx,
                    adxDiPlus = adxResult.diPlus,
                    adxDiMinus = adxResult.diMinus,
                    candlePattern      = candleResult.type,
                    candlePatternLabel = candleResult.label,
                    candleSignal       = candleResult.signal.name,
                    support = support, resistance = resistance,
                    sector   = profile?.sector.orEmpty().ifBlank { phase1.sector },
                    industry = profile?.industry.orEmpty().ifBlank { phase1.industry },
                    priceHistory  = closes,
                    volumeHistory = volumes.map { it.toLong() },
                    news          = news,
                    thirtyMinPattern      = thirtyMinCandle?.type  ?: "NONE",
                    thirtyMinPatternLabel = thirtyMinCandle?.label ?: "",
                    thirtyMinSignal       = thirtyMinCandle?.signal?.name ?: "NEUTRAL",
                    orbHigh             = intraday?.orbHigh  ?: 0.0,
                    orbLow              = intraday?.orbLow   ?: 0.0,
                    supertrendSignal    = supertrend30m?.signal ?: "NEUTRAL",
                    pivotCpp            = pivots?.cpp ?: 0.0,
                    pivotR1             = pivots?.r1  ?: 0.0,
                    pivotS1             = pivots?.s1  ?: 0.0,
                    sessionPhase        = sessionPhase,
                    isDeepEnriched = true,
                )

                val marketCondition = cachedMarketCondition()
                val newsImpact = newsImpact(news)
                val score  = (scorer.score(stockData, marketCondition) + newsImpact).coerceIn(0.0, 100.0)
                val option = scorer.optionAction(stockData)
                val enriched = stockData.copy(score = score, optionAction = option, newsImpactScore = newsImpact)
                val quickTake = generateQuickTake(enriched, thirtyMinCandle, pivots, supertrend30m?.signal ?: "NEUTRAL", orbBreakoutMode)
                if (quickTake != null) AppLogger.d("QT", "${phase1.symbol} → ${quickTake.action} (${quickTake.confidence}%)")
                enriched.copy(quickTake = quickTake)
            }
        }

    // ── Top Movers ────────────────────────────────────────────────────────────

    private fun topMoversFromList(quotes: List<YahooFinanceClient.Quote>, isGainers: Boolean): List<MoverItem> =
        quotes
            .let { if (isGainers) it.sortedByDescending { q -> q.changePct }
                   else           it.sortedBy           { q -> q.changePct } }
            .take(5)
            .map { q -> MoverItem(q.symbol.removeSuffix(".NS"), q.price, q.changePct) }

    // ── Market Status ─────────────────────────────────────────────────────────

    private fun currentMarketStatus(): MarketStatus {
        val ist      = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        val dow      = ist.dayOfWeek.value   // 1=Mon, 7=Sun
        if (dow >= 6) return MarketStatus.WEEKEND
        val totalMin = ist.hour * 60 + ist.minute
        return when {
            totalMin < 9 * 60 + 15   -> MarketStatus.PRE_OPEN
            totalMin <= 15 * 60 + 30 -> MarketStatus.LIVE
            else                     -> MarketStatus.POST_MARKET
        }
    }

    companion object {
        private const val LARGE_CAP_MIN = 200_000_000_000.0
        private const val MID_CAP_MIN = 50_000_000_000.0
        private const val SMALL_CAP_MIN = 10_000_000_000.0
        private const val PENNY_STOCK_MAX = 50.0
    }

    private fun matchesCategory(stock: StockData, category: ScanCategory): Boolean {
        return when (category) {
            ScanCategory.ALL -> true
            ScanCategory.PENNY -> stock.ltp <= PENNY_STOCK_MAX
            ScanCategory.LARGE_CAP -> stock.marketCap >= LARGE_CAP_MIN
            ScanCategory.MID_CAP -> stock.marketCap >= MID_CAP_MIN && stock.marketCap < LARGE_CAP_MIN
            ScanCategory.SMALL_CAP -> stock.marketCap >= SMALL_CAP_MIN && stock.marketCap < MID_CAP_MIN
        }
    }

    /** Standalone news fetch — called from detail screen after stock data is already shown. */
    suspend fun fetchNewsForStock(
        symbol: String,
        name: String,
        sector: String,
        industry: String,
    ): com.nseassist.data.model.NewsResult? = withContext(Dispatchers.IO) {
        runCatching {
            kotlinx.coroutines.withTimeoutOrNull(18_000L) {
                NewsClient.fetchNews(
                    stockSymbol  = symbol.removeSuffix(".NS"),
                    companyName  = name,
                    sector       = sector,
                    industry     = industry,
                )
            }
        }.getOrNull()
    }

    private fun newsImpact(news: com.nseassist.data.model.NewsResult?): Int {
        if (news == null) return 0
        var impact = when (news.stockSentiment) {
            com.nseassist.data.model.NewsSentiment.POSITIVE -> 4
            com.nseassist.data.model.NewsSentiment.NEGATIVE -> -4
            else -> 0
        }
        impact += when (news.sectorSentiment) {
            com.nseassist.data.model.NewsSentiment.POSITIVE -> 2
            com.nseassist.data.model.NewsSentiment.NEGATIVE -> -2
            else -> 0
        }
        if (news.stockArticleCount >= 4) impact += if (impact > 0) 1 else if (impact < 0) -1 else 0
        return impact.coerceIn(-6, 6)
    }

    // ── Session phase ─────────────────────────────────────────────────────────

    private fun currentSessionPhase(): String {
        val ist    = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        val dow    = ist.dayOfWeek.value
        if (dow >= 6) return "CLOSED"
        val mins   = ist.hour * 60 + ist.minute
        return when {
            mins < 9 * 60 + 15   -> "CLOSED"
            mins < 11 * 60 + 30  -> "MORNING"
            mins < 13 * 60 + 30  -> "MIDDAY"
            mins <= 15 * 60 + 30 -> "AFTERNOON"
            else                 -> "CLOSED"
        }
    }

    // ── Plain-English Quick Take generator ───────────────────────────────────

    private fun generateQuickTake(
        stock: StockData,
        thirtyMinCandle: TechnicalIndicators.CandlePattern?,
        pivots: TechnicalIndicators.PivotPoints?,
        supertrendSignal: String,
        orbBreakoutMode: Boolean = false,
    ): QuickTake {
        fun fmt(v: Double) = "₹${String.format("%,.2f", v)}"
        fun pct(v: Double) = String.format("%.1f", v)

        val ltp = stock.ltp
        val atr = stock.atr.coerceAtLeast(ltp * 0.005)

        // ── SITUATION ────────────────────────────────────────────────────────
        val threeCandle = thirtyMinCandle != null &&
                thirtyMinCandle.type in listOf("MORNING_STAR", "EVENING_STAR")
        val candleSpan = if (threeCandle) "45-min" else "15-min"
        val patternDesc = when {
            thirtyMinCandle != null && thirtyMinCandle.type != "NONE" -> when (thirtyMinCandle.signal) {
                TechnicalIndicators.CandleSignal.BULLISH ->
                    "The last $candleSpan candle shows a ${thirtyMinCandle.type.lowercase().replace("_", " ")} — buyers are stepping in."
                TechnicalIndicators.CandleSignal.BEARISH ->
                    "The last $candleSpan candle shows a ${thirtyMinCandle.type.lowercase().replace("_", " ")} — sellers are taking over."
                else ->
                    "The last $candleSpan candle is a Doji — price is undecided right now."
            }
            stock.aboveVwap -> "Price is above the day's average (VWAP) — buyers are in control."
            else            -> "Price is below the day's average (VWAP) — sellers have the upper hand."
        }
        val headline = "${stock.name} is at ${fmt(ltp)}. $patternDesc"

        // ── ACTION ────────────────────────────────────────────────────────────
        // Resistance breakout: price just crossed above the 30-day swing high resistance.
        // The 5% freshness cap ensures we don't flag stocks that broke out days ago.
        val hasResistanceBreakout = orbBreakoutMode &&
                stock.resistance > 0 &&
                ltp > stock.resistance &&
                ltp <= stock.resistance * 1.05   // fresh — within 5% above resistance
        val isBullish = ((stock.optionAction in listOf("STRONG BUY", "POSSIBLE BUY")) || hasResistanceBreakout) &&
                        (thirtyMinCandle?.signal != TechnicalIndicators.CandleSignal.BEARISH) &&
                        stock.aboveVwap
        val isBearish = stock.optionAction == "AVOID" ||
                        thirtyMinCandle?.signal == TechnicalIndicators.CandleSignal.BEARISH

        val entryPrice = if (isBullish) ltp + atr * 0.1 else ltp - atr * 0.1
        val atrTarget  = ltp + atr * 1.2
        val atrStop    = ltp - atr * 0.6

        // Use pivot R1 as target if it's above LTP and reachable today (within 8%)
        // Use pivot R2 if LTP has already crossed R1
        // Fallback to ATR-based target when pivots are unavailable or too far away
        val targetPrice = when {
            isBullish && pivots != null && pivots.r1 > ltp && pivots.r1 <= ltp * 1.08 -> pivots.r1
            isBullish && pivots != null && ltp >= pivots.r1 && pivots.r2 > ltp        -> pivots.r2
            isBullish -> atrTarget
            else      -> ltp - atr * 1.2
        }

        // Use S1 as stop only when it sits between the ATR stop and LTP (valid support zone)
        val stopPrice = when {
            isBullish && pivots != null && pivots.s1 in atrStop..ltp -> pivots.s1
            isBullish -> atrStop
            else      -> ltp + atr * 0.6
        }

        val targetPct   = (targetPrice - entryPrice) / entryPrice * 100
        val stopPct     = kotlin.math.abs(entryPrice - stopPrice) / entryPrice * 100

        val action = when {
            hasResistanceBreakout && isBullish -> "Buy — Resistance breakout above ${fmt(stock.resistance)}"
            isBullish -> "Buy above ${fmt(entryPrice)}"
            isBearish -> "Avoid buying — wait for the selling to stop first"
            else      -> "Wait — no clear signal yet. Watch if price crosses ${fmt(ltp + atr * 0.15)}"
        }
        val target   = if (isBullish) "Target: ${fmt(targetPrice)} (+${pct(targetPct)}%)"   else "—"
        val stopLoss = if (isBullish) "Stop Loss: ${fmt(stopPrice)} (-${pct(stopPct)}%)" else "—"

        // ── WHY ───────────────────────────────────────────────────────────────
        val rsiDesc = when {
            stock.rsi > 70  -> "The stock has risen a lot recently — it may need a pause. Keep your profit target short."
            stock.rsi < 35  -> "The stock has fallen sharply and may bounce back up soon."
            stock.rsi in 45.0..65.0 -> "The stock has healthy momentum right now — a good sign."
            else            -> "The stock's momentum is neutral — no strong signal either way."
        }
        val volumeDesc = if (stock.volumeSpike) "Today's trading is much busier than usual — big buyers or sellers are active." else "Trading volume is normal today."
        val pivotDesc  = when {
            pivots != null && ltp > pivots.r1  -> "Price has pushed past a key level (${fmt(pivots.r1)}) — momentum is strong today."
            pivots != null && ltp < pivots.s1  -> "Price has fallen below a key level (${fmt(pivots.s1)}) — the stock is in a weak zone today."
            pivots != null && ltp > pivots.cpp -> "Price is above today's midpoint (${fmt(pivots.cpp)}) — buyers are slightly in control."
            pivots != null                     -> "Price is below today's midpoint (${fmt(pivots.cpp)}) — sellers are slightly in control."
            else -> ""
        }
        val orbDesc = when {
            hasResistanceBreakout -> "The stock just broke above a price level (${fmt(stock.resistance)}) it couldn't cross in the last month — strong buying signal."
            stock.orbHigh > 0 && ltp > stock.orbHigh -> "Price broke above the morning's trading range (${fmt(stock.orbHigh)}) — bullish signal."
            stock.orbLow  > 0 && ltp < stock.orbLow  -> "Price fell below the morning's trading range (${fmt(stock.orbLow)}) — avoid buying."
            else -> ""
        }

        val why = buildString {
            append(rsiDesc); append(" "); append(volumeDesc)
            if (orbDesc.isNotEmpty())   { append(" "); append(orbDesc) }
            if (pivotDesc.isNotEmpty()) { append(" "); append(pivotDesc) }
        }.trim()

        // ── WARNING ───────────────────────────────────────────────────────────
        val warning = when {
            stock.rsi > 68  -> "The stock has already risen a lot today. Don't buy if it keeps jumping — wait for a small dip first."
            stock.rsi < 32  -> "The stock has been falling. Wait for it to start going back up before buying — don't buy while it's still dropping."
            !stock.aboveVwap && isBullish -> "The price is still below today's average. Only buy if it crosses above ${fmt(stock.vwap)} first."
            else -> "Exit immediately if price falls below ${fmt(if (isBullish) stopPrice else ltp - atr)} — that means the trade is not working."
        }

        // ── CONFIDENCE (session-adjusted) ────────────────────────────────────
        val adjustedConf = when (stock.sessionPhase) {
            "MIDDAY"    -> (stock.score * 0.82).toInt().coerceIn(0, 100)
            "AFTERNOON" -> (stock.score * 0.90).toInt().coerceIn(0, 100)
            else        -> stock.score.toInt().coerceIn(0, 100)
        }

        // ── SESSION NOTE ──────────────────────────────────────────────────────
        val sessionNote = when (stock.sessionPhase) {
            "MORNING"   -> "Best time to trade. Morning momentum is strongest."
            "MIDDAY"    -> "Midday — momentum slows down. Keep targets tight and avoid new trades."
            "AFTERNOON" -> "Late session — only ride existing trends. Don't start fresh trades."
            else        -> "Market is closed. This analysis is based on the last trading session."
        }

        // ── 15-MIN SUMMARY ────────────────────────────────────────────────────
        val stDesc = when (supertrendSignal) { "BUY" -> " Supertrend: BUY." "SELL" -> " Supertrend: SELL." else -> "" }
        val thirtyMinSummary = buildString {
            append("Last $candleSpan: ")
            if (thirtyMinCandle != null && thirtyMinCandle.type != "NONE") append("${thirtyMinCandle.label}. ")
            append(if (stock.aboveVwap) "Above VWAP — momentum UP." else "Below VWAP — momentum DOWN.")
            append(stDesc)
        }

        return QuickTake(
            headline         = headline,
            action           = action,
            target           = target,
            stopLoss         = stopLoss,
            why              = why,
            warning          = warning,
            confidence       = adjustedConf,
            sessionPhase     = stock.sessionPhase,
            sessionNote      = sessionNote,
            thirtyMinSummary = thirtyMinSummary,
        )
    }

    // ── Confidence nudge ─────────────────────────────────────────────────────

    /**
     * Adjusts the daily prediction confidence based on the 30-min closing candle and Supertrend.
     * Both signals agree bullish  → +10 pts.  One bullish signal → +5 pts.
     * Both signals agree bearish  → −15 pts.  One bearish signal → −8 pts.
     * No 30-min data available    → unchanged.
     */
    private fun nudgeConfidence(
        base: Int,
        candleSignal: TechnicalIndicators.CandleSignal?,
        supertrendSig: String,
    ): Int {
        val candleBull = candleSignal == TechnicalIndicators.CandleSignal.BULLISH
        val candleBear = candleSignal == TechnicalIndicators.CandleSignal.BEARISH
        val stBull     = supertrendSig == "BUY"
        val stBear     = supertrendSig == "SELL"
        return when {
            candleBull && stBull -> (base + 10).coerceIn(0, 100)
            candleBear && stBear -> (base - 15).coerceIn(0, 100)
            candleBull || stBull -> (base + 5).coerceIn(0, 100)
            candleBear || stBear -> (base - 8).coerceIn(0, 100)
            else                 -> base
        }
    }
}
