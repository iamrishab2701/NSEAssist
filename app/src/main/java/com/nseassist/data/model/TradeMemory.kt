package com.nseassist.data.model

// ── Paper trade logging ────────────────────────────────────────────────────────

/** Payload sent to POST /memory/trade */
data class PaperTradeRequest(
    val symbol: String,
    val companyName: String,
    val direction: String,         // BUY / SELL / SKIP
    val entryPrice: Double,        // LTP at time of logging
    val targetText: String,        // e.g. "₹875 (+3.3%)"
    val stopLossText: String,      // e.g. "₹835 (-1.4%)"
    val quantity: Int,
    val sessionPhase: String,      // MORNING / MIDDAY / AFTERNOON / CLOSED
    val aiProvider: String,        // provider label
    val confidence: Int,
    val verdict: String,           // GO / NO-GO
    val rrRatio: String,           // e.g. "1 : 2.4"
    val reason: String,
)

/** A logged trade returned from GET /memory/trades */
data class PaperTradeEntry(
    val id: Int,
    val symbol: String,
    val companyName: String,
    val direction: String,
    val entryPrice: Double,
    val targetText: String,
    val stopLossText: String,
    val quantity: Int,
    val sessionPhase: String,
    val aiProvider: String,
    val confidence: Int,
    val verdict: String,
    val rrRatio: String,
    val reason: String,
    val loggedAt: Long,
    val outcome: String,           // OPEN / TARGET_HIT / SL_HIT / EXPIRED
    val outcomePrice: Double?,
)

// ── Signal replay ──────────────────────────────────────────────────────────────

/** One signal outcome from Signal Replay — posted to POST /memory/signal */
data class SignalOutcomeRequest(
    val symbol: String,
    val signalDate: Long,
    val rsi: Double,
    val aboveVwap: Boolean,
    val macdBullish: Boolean,
    val trendSignal: String,
    val sessionPhase: String,
    val marketCondition: String,
    val predictionDirection: String,
    val score: Double,
    val outcome: String,           // WIN / LOSS / NEUTRAL
)

// ── Prediction tracking ────────────────────────────────────────────────────────

/** Payload sent to POST /memory/prediction */
data class PredictionLogRequest(
    val symbol: String,
    val predictionDirection: String,  // Up / Down / Sideways
    val predictedHigh: Double,
    val predictedLow: Double,
    val confidence: Int,
    val actualClose: Double = 0.0,    // non-zero triggers previous prediction verification
)

// ── AI context returned from GET /memory/ai-context ───────────────────────────

data class StockAiContext(
    val symbol: String,
    val hasData: Boolean,
    val signalWinRate: Double = 0.0,
    val signalCount: Int = 0,
    val winCount: Int = 0,
    val bestSession: String = "",
    val predictionAccuracy: Double = 0.0,
    val predictionCount: Int = 0,
    val recentTrades: List<RecentTradeEntry> = emptyList(),
)

data class RecentTradeEntry(
    val verdict: String,
    val direction: String,
    val outcome: String,
    val confidence: Int,
)
