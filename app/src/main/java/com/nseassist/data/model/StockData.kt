package com.nseassist.data.model

// ── News models ───────────────────────────────────────────────────────────────

enum class NewsSentiment { POSITIVE, NEGATIVE, NEUTRAL, NONE }

/**
 * Latest news headline for a stock.
 * Sentiment is keyword-based and shown as an informational indicator only —
 * it does NOT affect the technical confidence score.
 */
data class NewsArticle(
    val headline: String,
    val source: String,
    val ageText: String,
    val sentiment: NewsSentiment,
)

data class NewsResult(
    val sentiment: NewsSentiment,
    val headline: String,
    val ageText: String,
    val stockSentiment: NewsSentiment = NewsSentiment.NONE,
    val sectorSentiment: NewsSentiment = NewsSentiment.NONE,
    val stockArticleCount: Int = 0,
    val sectorArticleCount: Int = 0,
    val sourceCount: Int = 0,
    val sector: String = "",
    val articles: List<NewsArticle> = emptyList(),
)

// ── Stock model ───────────────────────────────────────────────────────────────

data class StockData(
    val symbol: String,
    val name: String,
    val ltp: Double,
    val change: Double,
    val changePct: Double,
    val dayHigh: Double,
    val dayLow: Double,
    val volume: Long,
    val avgVolume: Long,
    val vwap: Double,
    val open: Double,

    // Technical indicators (computed from history)
    val rsi: Double = 0.0,
    val ema20: Double = 0.0,
    val ema50: Double = 0.0,
    val macdLine: Double = 0.0,
    val macdSignal: Double = 0.0,

    // Trend
    val trend6Month: String = "Unknown",       // Bullish / Bearish / Range-Bound
    val trend2Week: String = "Unknown",        // Trending Up / Down / Consolidating
    val trendSignalType: String = "NEUTRAL",   // REVERSAL_BUY, BREAKOUT, etc.
    val trendSignalLabel: String = "",
    val streakDays: Int = 0,

    // Price prediction
    val predictedHigh: Double = 0.0,
    val predictedLow: Double = 0.0,
    val predictedDirection: String = "Sideways",
    val predictionConfidence: Int = 0,
    val atr: Double = 0.0,

    // Bollinger Bands
    val bollingerUpper: Double = 0.0,
    val bollingerMiddle: Double = 0.0,
    val bollingerLower: Double = 0.0,

    // ADX
    val adx: Double = 0.0,
    val adxDiPlus: Double = 0.0,
    val adxDiMinus: Double = 0.0,

    // Candlestick pattern
    val candlePattern: String = "NONE",
    val candlePatternLabel: String = "",
    val candleSignal: String = "NEUTRAL",

    // Support / Resistance
    val support: Double = 0.0,
    val resistance: Double = 0.0,

    // Scoring
    val score: Double = 0.0,
    val optionAction: String = "WAIT",
    val newsImpactScore: Int = 0,

    // Derived flags
    val aboveVwap: Boolean = false,
    val volumeSpike: Boolean = false,
    val gapType: String = "FLAT",   // GAP UP / GAP DOWN / FLAT
    val marketCap: Double = 0.0,
    val sector: String = "",
    val industry: String = "",
    val priceHistory: List<Double> = emptyList(),
    val volumeHistory: List<Long> = emptyList(),

    // News (fetched only during deep analysis — never affects score)
    val news: NewsResult? = null,

    // True when analyseStock() has run on this stock (Phase 2 / detail fetch).
    // Stays false for Phase 1 quick-score stocks even if history happened to be unavailable.
    // Use this — NOT priceHistory.isNotEmpty() — to check "was this stock deep-analysed?"
    val isDeepEnriched: Boolean = false,
)

enum class ScanCategory(val routeValue: String, val label: String, val helperText: String) {
    ALL("all", "All", "All liquid stocks under your capital"),
    LARGE_CAP("large", "Large", "Usually bigger, steadier companies"),
    MID_CAP("mid", "Mid", "Balanced between growth and stability"),
    SMALL_CAP("small", "Small", "Higher growth potential, higher risk"),
    PENNY("penny", "Penny", "Low-priced stocks, usually more volatile");

    companion object {
        fun fromRouteValue(value: String?): ScanCategory =
            values().firstOrNull { it.routeValue.equals(value, ignoreCase = true) } ?: ALL
    }
}

data class MarketOverview(
    val nifty50: Double,
    val nifty50Change: Double,
    val nifty50ChangePct: Double,
    val bankNifty: Double,
    val bankNiftyChange: Double,
    val bankNiftyChangePct: Double,
    val niftyAboveVwap: Boolean,
    val bankNiftyAboveVwap: Boolean,
    val marketStatus: MarketStatus,
    val topGainers: List<MoverItem>,
    val topLosers: List<MoverItem>,
)

data class MoverItem(
    val symbol: String,
    val ltp: Double,
    val changePct: Double,
)

enum class MarketStatus { PRE_OPEN, LIVE, POST_MARKET, WEEKEND }
