package com.nseassist.data.repository

import com.nseassist.analysis.StockScorer
import com.nseassist.analysis.TechnicalIndicators
import com.nseassist.analysis.TrendDetector
import com.nseassist.data.api.YahooFinanceApi
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.MarketStatus
import com.nseassist.data.model.MoverItem
import com.nseassist.data.model.StockData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.ZonedDateTime

class NSERepository {

    private val api = YahooFinanceApi.create()
    private val indicators = TechnicalIndicators()
    private val trendDetector = TrendDetector()
    private val scorer = StockScorer()

    // ── Market Overview ─────────────────────────────────────────────────────────

    suspend fun getMarketOverview(): Result<MarketOverview> = withContext(Dispatchers.IO) {
        runCatching {
            val niftyDeferred = async { fetchQuickSnapshot("^NSEI") }
            val bankNiftyDeferred = async { fetchQuickSnapshot("^NSEBANK") }
            val nifty = niftyDeferred.await()
            val bankNifty = bankNiftyDeferred.await()

            val gainers = fetchTopMovers(isGainers = true)
            val losers = fetchTopMovers(isGainers = false)

            MarketOverview(
                nifty50 = nifty.ltp,
                nifty50Change = nifty.change,
                nifty50ChangePct = nifty.changePct,
                bankNifty = bankNifty.ltp,
                bankNiftyChange = bankNifty.change,
                bankNiftyChangePct = bankNifty.changePct,
                niftyAboveVwap = nifty.aboveVwap,
                bankNiftyAboveVwap = bankNifty.aboveVwap,
                marketStatus = currentMarketStatus(),
                topGainers = gainers,
                topLosers = losers,
            )
        }
    }

    // ── Stock Scan (affordable universe) ────────────────────────────────────────

    suspend fun scanAffordableStocks(capital: Double): Result<List<StockData>> =
        withContext(Dispatchers.IO) {
            runCatching {
                // Known large-cap NSE liquid stocks (NIFTY 100 + BANK NIFTY universe)
                // During market hours this gives a representative 60-stock universe
                val universe = NSE_LIQUID_UNIVERSE

                // Batch fetch quick snapshots in parallel
                val snapshots = coroutineScope {
                    universe.map { (name, ticker) ->
                        async {
                            runCatching { fetchQuickSnapshot(ticker) }
                                .getOrNull()
                                ?.takeIf { it.ltp in 1.0..capital }
                                ?.copy(symbol = name)
                        }
                    }.awaitAll().filterNotNull()
                }

                // Sort by change% descending — movers first
                snapshots.sortedByDescending { kotlin.math.abs(it.changePct) }
            }
        }

    // ── Full Deep Analysis for one stock ───────────────────────────────────────

    suspend fun analyseStock(ticker: String): Result<StockData> = withContext(Dispatchers.IO) {
        runCatching {
            val yTicker = if (ticker.endsWith(".NS")) ticker else "$ticker.NS"

            // Fetch intraday (for VWAP) and 3-month history in parallel
            val intradayDeferred = async { runCatching { api.getIntraday(yTicker) } }
            val historyDeferred = async { runCatching { api.getHistory(yTicker, range = "3mo") } }

            val intraday = intradayDeferred.await().getOrNull()
            val history = historyDeferred.await().getOrNull()

            // Parse history closes + volumes
            val closes = history?.parseCloses() ?: emptyList()
            val volumes = history?.parseVolumes() ?: emptyList()
            val highs = history?.parseHighs() ?: emptyList()
            val lows = history?.parseLows() ?: emptyList()

            // Live snapshot from intraday
            val meta = intraday?.getAsJsonObject("chart")
                ?.getAsJsonArray("result")?.get(0)?.asJsonObject
                ?.getAsJsonObject("meta")

            val ltp = meta?.get("regularMarketPrice")?.asDouble ?: closes.lastOrNull() ?: 0.0
            val prevClose = meta?.get("chartPreviousClose")?.asDouble ?: closes.dropLast(1).lastOrNull() ?: ltp
            val change = ltp - prevClose
            val changePct = if (prevClose != 0.0) (change / prevClose) * 100 else 0.0
            val dayHigh = meta?.get("regularMarketDayHigh")?.asDouble ?: ltp
            val dayLow = meta?.get("regularMarketDayLow")?.asDouble ?: ltp
            val volume = meta?.get("regularMarketVolume")?.asLong ?: 0L
            val avgVolume = meta?.get("averageDailyVolume10Day")?.asLong ?: 0L
            val open = meta?.get("regularMarketOpen")?.asDouble ?: ltp

            // Compute VWAP from 1-min intraday candles
            val vwap = computeVwap(intraday) ?: ((dayHigh + dayLow + ltp) / 3)
            val aboveVwap = ltp > vwap

            // Technical indicators from history
            val rsi = indicators.rsi(closes, period = 14)
            val ema20 = indicators.ema(closes, period = 20)
            val ema50 = indicators.ema(closes, period = 50)
            val (macdLine, macdSignal) = indicators.macd(closes)
            val atr = indicators.atr(highs, lows, closes, period = 14)

            // Support & Resistance from last 20 days
            val last20Highs = highs.takeLast(20)
            val last20Lows = lows.takeLast(20)
            val support = last20Lows.minOrNull() ?: 0.0
            val resistance = last20Highs.maxOrNull() ?: 0.0

            // Trend signals
            val trend = trendDetector.detect(closes, volumes.map { it.toLong() })
            val trend6M = indicators.trend6Month(closes)
            val trend2W = indicators.trend2Week(closes)

            // Price prediction (linear regression + ATR)
            val prediction = indicators.predictPrice(closes, atr)

            // Gap detection
            val gapType = when {
                open > prevClose * 1.005 -> "GAP UP"
                open < prevClose * 0.995 -> "GAP DOWN"
                else -> "FLAT"
            }

            val stockData = StockData(
                symbol = ticker.removeSuffix(".NS"),
                name = ticker.removeSuffix(".NS"),
                ltp = ltp, change = change, changePct = changePct,
                dayHigh = dayHigh, dayLow = dayLow,
                volume = volume, avgVolume = avgVolume,
                vwap = vwap, open = open,
                rsi = rsi, ema20 = ema20, ema50 = ema50,
                macdLine = macdLine, macdSignal = macdSignal,
                trend6Month = trend6M, trend2Week = trend2W,
                trendSignalType = trend.type,
                trendSignalLabel = trend.label,
                streakDays = trend.streakDays,
                predictedHigh = prediction.high,
                predictedLow = prediction.low,
                predictedDirection = prediction.direction,
                predictionConfidence = prediction.confidence,
                atr = atr,
                support = support, resistance = resistance,
                aboveVwap = aboveVwap,
                volumeSpike = avgVolume > 0 && volume > avgVolume * 1.5,
                gapType = gapType,
                priceHistory = closes,
                volumeHistory = volumes.map { it.toLong() },
            )

            val score = scorer.score(stockData)
            val option = scorer.optionAction(stockData)
            stockData.copy(score = score, optionAction = option)
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private suspend fun fetchQuickSnapshot(ticker: String): StockData {
        val json = api.getChart(ticker)
        val result = json.getAsJsonObject("chart")
            .getAsJsonArray("result").get(0).asJsonObject
        val meta = result.getAsJsonObject("meta")

        val ltp = meta.get("regularMarketPrice").asDouble
        val prevClose = meta.get("chartPreviousClose").asDouble
        val change = ltp - prevClose
        val changePct = if (prevClose != 0.0) (change / prevClose) * 100 else 0.0
        val dayHigh = runCatching { meta.get("regularMarketDayHigh").asDouble }.getOrDefault(ltp)
        val dayLow = runCatching { meta.get("regularMarketDayLow").asDouble }.getOrDefault(ltp)
        val volume = runCatching { meta.get("regularMarketVolume").asLong }.getOrDefault(0L)
        val avgVolume = runCatching { meta.get("averageDailyVolume10Day").asLong }.getOrDefault(0L)
        val open = runCatching { meta.get("regularMarketOpen").asDouble }.getOrDefault(ltp)
        val vwap = (dayHigh + dayLow + ltp) / 3
        val gapType = when {
            open > prevClose * 1.005 -> "GAP UP"
            open < prevClose * 0.995 -> "GAP DOWN"
            else -> "FLAT"
        }

        return StockData(
            symbol = ticker.removeSuffix(".NS"),
            name = ticker.removeSuffix(".NS"),
            ltp = ltp, change = change, changePct = changePct,
            dayHigh = dayHigh, dayLow = dayLow,
            volume = volume, avgVolume = avgVolume,
            vwap = vwap, open = open,
            aboveVwap = ltp > vwap,
            volumeSpike = avgVolume > 0 && volume > avgVolume * 1.5,
            gapType = gapType,
        )
    }

    private suspend fun fetchTopMovers(isGainers: Boolean): List<MoverItem> {
        val symbols = if (isGainers) TOP_GAINER_PROXIES else TOP_LOSER_PROXIES
        return coroutineScope {
            symbols.map { ticker ->
                async {
                    runCatching {
                        val snap = fetchQuickSnapshot(ticker)
                        MoverItem(snap.symbol, snap.ltp, snap.changePct)
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull()
                .let { list ->
                    if (isGainers)
                        list.sortedByDescending { it.changePct }
                    else
                        list.sortedBy { it.changePct }
                }.take(5)
        }
    }

    private fun computeVwap(json: com.google.gson.JsonObject?): Double? {
        val result = json?.getAsJsonObject("chart")
            ?.getAsJsonArray("result")?.get(0)?.asJsonObject ?: return null
        val indicators = result.getAsJsonObject("indicators")
        val quotes = indicators.getAsJsonArray("quote").get(0).asJsonObject

        val highs = quotes.getAsJsonArray("high")?.map { if (it.isJsonNull) null else it.asDouble } ?: return null
        val lows = quotes.getAsJsonArray("low")?.map { if (it.isJsonNull) null else it.asDouble } ?: return null
        val closes = quotes.getAsJsonArray("close")?.map { if (it.isJsonNull) null else it.asDouble } ?: return null
        val volumes = quotes.getAsJsonArray("volume")?.map { if (it.isJsonNull) null else it.asLong } ?: return null

        var cumPV = 0.0
        var cumVol = 0L
        for (i in highs.indices) {
            val h = highs[i] ?: continue
            val l = lows[i] ?: continue
            val c = closes[i] ?: continue
            val v = volumes[i] ?: continue
            val tp = (h + l + c) / 3
            cumPV += tp * v
            cumVol += v
        }
        return if (cumVol > 0) cumPV / cumVol else null
    }

    private fun currentMarketStatus(): MarketStatus {
        val ist = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"))
        val dow = ist.dayOfWeek.value  // 1=Mon, 7=Sun
        if (dow >= 6) return MarketStatus.WEEKEND
        val hour = ist.hour
        val minute = ist.minute
        val totalMin = hour * 60 + minute
        return when {
            totalMin < 9 * 60 -> MarketStatus.PRE_OPEN
            totalMin < 9 * 60 + 15 -> MarketStatus.PRE_OPEN
            totalMin <= 15 * 60 + 30 -> MarketStatus.LIVE
            else -> MarketStatus.POST_MARKET
        }
    }

    // ── JSON parse helpers ───────────────────────────────────────────────────────

    private fun com.google.gson.JsonObject.parseCloses(): List<Double> =
        parseOhlcv("close")

    private fun com.google.gson.JsonObject.parseHighs(): List<Double> =
        parseOhlcv("high")

    private fun com.google.gson.JsonObject.parseLows(): List<Double> =
        parseOhlcv("low")

    private fun com.google.gson.JsonObject.parseVolumes(): List<Double> =
        this.getAsJsonObject("chart")
            ?.getAsJsonArray("result")?.get(0)?.asJsonObject
            ?.getAsJsonObject("indicators")
            ?.getAsJsonArray("quote")?.get(0)?.asJsonObject
            ?.getAsJsonArray("volume")
            ?.mapNotNull { if (it.isJsonNull) null else it.asDouble }
            ?: emptyList()

    private fun com.google.gson.JsonObject.parseOhlcv(field: String): List<Double> =
        this.getAsJsonObject("chart")
            ?.getAsJsonArray("result")?.get(0)?.asJsonObject
            ?.getAsJsonObject("indicators")
            ?.getAsJsonArray("quote")?.get(0)?.asJsonObject
            ?.getAsJsonArray(field)
            ?.mapNotNull { if (it.isJsonNull) null else it.asDouble }
            ?: emptyList()

    companion object {
        // NIFTY 100 liquid stocks — Yahoo Finance tickers
        val NSE_LIQUID_UNIVERSE: List<Pair<String, String>> = listOf(
            "RELIANCE" to "RELIANCE.NS", "TCS" to "TCS.NS", "HDFCBANK" to "HDFCBANK.NS",
            "INFY" to "INFY.NS", "ICICIBANK" to "ICICIBANK.NS", "HINDUNILVR" to "HINDUNILVR.NS",
            "SBIN" to "SBIN.NS", "BHARTIARTL" to "BHARTIARTL.NS", "KOTAKBANK" to "KOTAKBANK.NS",
            "LT" to "LT.NS", "AXISBANK" to "AXISBANK.NS", "WIPRO" to "WIPRO.NS",
            "HCLTECH" to "HCLTECH.NS", "ASIANPAINT" to "ASIANPAINT.NS", "MARUTI" to "MARUTI.NS",
            "ULTRACEMCO" to "ULTRACEMCO.NS", "NESTLEIND" to "NESTLEIND.NS", "BAJFINANCE" to "BAJFINANCE.NS",
            "TITAN" to "TITAN.NS", "SUNPHARMA" to "SUNPHARMA.NS", "ONGC" to "ONGC.NS",
            "NTPC" to "NTPC.NS", "POWERGRID" to "POWERGRID.NS", "COALINDIA" to "COALINDIA.NS",
            "TATAMOTORS" to "TATAMOTORS.NS", "TATASTEEL" to "TATASTEEL.NS", "JSWSTEEL" to "JSWSTEEL.NS",
            "ADANIPORTS" to "ADANIPORTS.NS", "HINDALCO" to "HINDALCO.NS", "GRASIM" to "GRASIM.NS",
            "DRREDDY" to "DRREDDY.NS", "CIPLA" to "CIPLA.NS", "DIVISLAB" to "DIVISLAB.NS",
            "EICHERMOT" to "EICHERMOT.NS", "BAJAJ-AUTO" to "BAJAJ-AUTO.NS", "HEROMOTOCO" to "HEROMOTOCO.NS",
            "BRITANNIA" to "BRITANNIA.NS", "APOLLOHOSP" to "APOLLOHOSP.NS", "TATACONSUM" to "TATACONSUM.NS",
            "ITC" to "ITC.NS", "INDUSINDBK" to "INDUSINDBK.NS", "BPCL" to "BPCL.NS",
            "IOC" to "IOC.NS", "SYNGENE" to "SYNGENE.NS", "IRFC" to "IRFC.NS",
            "NHPC" to "NHPC.NS", "PNB" to "PNB.NS", "CANBK" to "CANBK.NS",
            "BANKBARODA" to "BANKBARODA.NS", "IDBI" to "IDBI.NS", "MUTHOOTFIN" to "MUTHOOTFIN.NS",
            "ANGELONE" to "ANGELONE.NS", "JINDALSTEL" to "JINDALSTEL.NS", "BLUESTARCO" to "BLUESTARCO.NS",
            "BHARATFORG" to "BHARATFORG.NS", "HAL" to "HAL.NS", "BEL" to "BEL.NS",
            "BHEL" to "BHEL.NS", "IDFCFIRSTB" to "IDFCFIRSTB.NS", "FEDERALBNK" to "FEDERALBNK.NS",
        )

        // Used for fetching top movers (NIFTY 50 representative subset)
        private val TOP_GAINER_PROXIES = listOf(
            "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "INFY.NS", "ICICIBANK.NS",
            "SBIN.NS", "AXISBANK.NS", "BHARTIARTL.NS", "LT.NS", "WIPRO.NS",
            "NTPC.NS", "POWERGRID.NS", "COALINDIA.NS", "ONGC.NS", "SUNPHARMA.NS",
        )

        private val TOP_LOSER_PROXIES = TOP_GAINER_PROXIES
    }
}
