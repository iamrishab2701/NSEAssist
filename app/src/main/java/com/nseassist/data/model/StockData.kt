package com.nseassist.data.model

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

    // Support / Resistance
    val support: Double = 0.0,
    val resistance: Double = 0.0,

    // Scoring
    val score: Double = 0.0,
    val optionAction: String = "WAIT",

    // Derived flags
    val aboveVwap: Boolean = false,
    val volumeSpike: Boolean = false,
    val gapType: String = "FLAT",   // GAP UP / GAP DOWN / FLAT
    val priceHistory: List<Double> = emptyList(),
    val volumeHistory: List<Long> = emptyList(),
)

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
