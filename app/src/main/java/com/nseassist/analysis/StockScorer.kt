package com.nseassist.analysis

import com.nseassist.data.model.StockData
import kotlin.math.min

/**
 * Port of `recommendation_score()` from nse_live_prices.py
 * Score: 0–100. Only recommend trades with score >= 70.
 */
class StockScorer {

    fun score(stock: StockData): Double {
        var score = 50.0  // neutral baseline

        // VWAP position (+15 / -15)
        if (stock.aboveVwap) score += 15.0 else score -= 15.0

        // Change% momentum (+10 / -10)
        when {
            stock.changePct > 2.0 -> score += 10.0
            stock.changePct > 0.5 -> score += 5.0
            stock.changePct < -2.0 -> score -= 10.0
            stock.changePct < -0.5 -> score -= 5.0
        }

        // RSI sweet spot 45–65 = good; >70 or <30 = penalise
        when {
            stock.rsi in 45.0..65.0 -> score += 10.0
            stock.rsi > 70.0 -> score -= 15.0
            stock.rsi < 30.0 -> score -= 10.0
            stock.rsi in 30.0..45.0 -> score += 5.0
            stock.rsi in 65.0..70.0 -> score += 3.0
        }

        // EMA alignment
        if (stock.ltp > stock.ema20) score += 8.0 else score -= 5.0
        if (stock.ema20 > stock.ema50) score += 7.0 else score -= 3.0

        // MACD
        if (stock.macdLine > stock.macdSignal) score += 8.0 else score -= 5.0

        // Volume spike
        if (stock.volumeSpike) score += 10.0

        // 6-month trend alignment
        when (stock.trend6Month) {
            "Bullish" -> if (stock.changePct > 0) score += 7.0
            "Bearish" -> if (stock.changePct < 0) score += 3.0
        }

        // Trend signal boost (from TrendDetector)
        score += stock.scoreBoostFromTrendSignal()

        // Prediction confidence bonus
        if (stock.predictionConfidence >= 75) score += 5.0

        return min(score, 100.0).coerceAtLeast(0.0)
    }

    fun optionAction(stock: StockData): String {
        return when {
            stock.rsi > 65 && !stock.aboveVwap -> "PE candidate"
            stock.rsi < 35 && stock.aboveVwap -> "CE candidate"
            stock.score >= 70 -> "Strong trade"
            stock.score >= 55 -> "WAIT"
            else -> "AVOID"
        }
    }

    fun signalLabel(score: Double): String = when {
        score >= 80 -> "STRONG BUY"
        score >= 65 -> "POSSIBLE BUY"
        score >= 50 -> "NEUTRAL"
        score >= 35 -> "WEAK"
        else -> "AVOID"
    }
}

private fun StockData.scoreBoostFromTrendSignal(): Double = when (trendSignalType) {
    "REVERSAL_BUY" -> 20.0
    "BREAKOUT" -> 25.0
    "CONTINUATION_UP" -> 10.0
    "REVERSAL_SELL" -> -10.0
    "BREAKDOWN" -> -15.0
    "CONTINUATION_DN" -> -5.0
    else -> 0.0
}
