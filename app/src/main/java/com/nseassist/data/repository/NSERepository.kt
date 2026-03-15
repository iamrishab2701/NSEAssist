package com.nseassist.data.repository

import android.util.Log
import com.nseassist.analysis.StockScorer
import com.nseassist.analysis.TechnicalIndicators
import com.nseassist.analysis.TrendDetector
import com.nseassist.data.api.NewsClient
import com.nseassist.data.api.YahooFinanceClient
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.MarketStatus
import com.nseassist.data.model.MoverItem
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

            val gainers = fetchTopMovers(isGainers = true)
            val losers  = fetchTopMovers(isGainers = false)

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

    // ── Stock Scan ────────────────────────────────────────────────────────────

    suspend fun scanAffordableStocks(capital: Double, category: ScanCategory): Result<List<StockData>> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d("NSERepository", "Screener scan — capital=₹$capital, category=${category.routeValue}")

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
                Log.d("NSERepository", "Market condition: $marketCondition")

                Log.d("NSERepository", "Screener returned ${allQuotes.size} liquid NSE stocks")

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

                Log.d("NSERepository", "Affordable after filter: ${snapshots.size}")
                snapshots.sortedByDescending { kotlin.math.abs(it.changePct) }
            }
        }

    // ── Full Deep Analysis for one stock ───────────────────────────────────────

    // fetchNews = false during Phase 2 batch scans (saves ~18s per stock when Google News is slow)
    // fetchNews = true only on the Stock Detail screen where the user actually reads the news
    suspend fun analyseStock(ticker: String, fetchNews: Boolean = false): Result<StockData> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanSymbol  = ticker.removeSuffix(".NS")
            val yahooSymbol  = "$cleanSymbol.NS"
            Log.d("NSERepository", "Deep analysis via Yahoo Finance: $yahooSymbol")

            // Fetch quote, 90-day history, and asset profile in parallel
            val quoteDeferred   = async {
                withTimeoutOrNull(20_000L) { YahooFinanceClient.getBatchQuotes(listOf(yahooSymbol)) }
            }
            val historyDeferred = async {
                withTimeoutOrNull(25_000L) { YahooFinanceClient.getHistory(yahooSymbol, days = 90) }
            }
            val profileDeferred = async { cachedProfile(yahooSymbol) }

            val quoteList = quoteDeferred.await()  ?: emptyList()
            val history   = historyDeferred.await() ?: emptyList()
            val profile   = profileDeferred.await()

            val quote = quoteList.firstOrNull()
                ?: error("No data for $yahooSymbol — check internet connection")

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

            val ltp       = quote.price
            val prevClose = quote.prevClose
            val change    = quote.change
            val changePct = quote.changePct
            val dayHigh   = quote.dayHigh
            val dayLow    = quote.dayLow
            val volume    = quote.volume
            val open      = quote.open
            val vwap      = (dayHigh + dayLow + ltp) / 3
            val aboveVwap = ltp > vwap

            // Technical indicators
            val rsi                    = indicators.rsi(closes, period = 14)
            val ema20                  = indicators.ema(closes, period = 20)
            val ema50                  = indicators.ema(closes, period = 50)
            val (macdLine, macdSignal) = indicators.macd(closes)
            val atr                    = indicators.atr(highs, lows, closes, period = 14)
            val bollinger              = indicators.bollingerBands(closes)
            val adxResult              = indicators.adx(highs, lows, closes)
            val candleResult           = indicators.detectCandlePattern(history.map { it.open }, highs, lows, closes)

            val support    = lows.takeLast(20).minOrNull()  ?: 0.0
            val resistance = highs.takeLast(20).maxOrNull() ?: 0.0

            val trend  = trendDetector.detect(closes, volumes.map { it.toLong() })
            val trend6M = indicators.trend6Month(closes)
            val trend2W = indicators.trend2Week(closes)

            val prediction = indicators.predictPrice(closes, atr)

            val gapType = when {
                open > prevClose * 1.005 -> "GAP UP"
                open < prevClose * 0.995 -> "GAP DOWN"
                else -> "FLAT"
            }

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
                predictionConfidence = prediction.confidence,
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
            )

            val marketCondition = cachedMarketCondition()
            val newsImpact = newsImpact(news)
            val score  = (scorer.score(stockData, marketCondition) + newsImpact).coerceIn(0.0, 100.0)
            val option = scorer.optionAction(stockData)
            stockData.copy(score = score, optionAction = option, news = news, newsImpactScore = newsImpact, isDeepEnriched = true)
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
    suspend fun analyseStockFromPhase1(phase1: StockData, fetchNews: Boolean = false): Result<StockData> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cleanSymbol = phase1.symbol.removeSuffix(".NS")
                val yahooSymbol = "$cleanSymbol.NS"

                // Fetch history (60 days) and profile in parallel — skip quote fetch
                val historyDeferred = async {
                    withTimeoutOrNull(25_000L) { YahooFinanceClient.getHistory(yahooSymbol, days = 60) }
                }
                val profileDeferred = async { cachedProfile(yahooSymbol) }

                val history = historyDeferred.await() ?: emptyList()
                val profile = profileDeferred.await()

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
                val prediction = indicators.predictPrice(closes, atr)

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
                    predictionConfidence = prediction.confidence,
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
                    isDeepEnriched = true,
                )

                val marketCondition = cachedMarketCondition()
                val newsImpact = newsImpact(news)
                val score  = (scorer.score(stockData, marketCondition) + newsImpact).coerceIn(0.0, 100.0)
                val option = scorer.optionAction(stockData)
                stockData.copy(score = score, optionAction = option, newsImpactScore = newsImpact)
            }
        }

    // ── Top Movers ────────────────────────────────────────────────────────────

    private suspend fun fetchTopMovers(isGainers: Boolean): List<MoverItem> {
        val quotes = withTimeoutOrNull(30_000L) {
            YahooFinanceClient.screenNseStocks(minVolume = 1_000_000)
        } ?: emptyList()

        return quotes
            .let { if (isGainers) it.sortedByDescending { q -> q.changePct }
                   else           it.sortedBy           { q -> q.changePct } }
            .take(5)
            .map { q -> MoverItem(q.symbol.removeSuffix(".NS"), q.price, q.changePct) }
    }

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
}
