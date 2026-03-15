package com.nseassist.analysis

import com.nseassist.data.model.StockData
import kotlin.math.min

/**
 * Score: 0–100. Combines technical indicators, candlestick patterns,
 * Bollinger Bands, ADX trend strength, and market condition awareness.
 */
class StockScorer {

    enum class MarketCondition { BULLISH, BEARISH, CHOPPY }

    fun detectMarketCondition(niftyChangePct: Double, niftyAboveVwap: Boolean): MarketCondition {
        return when {
            niftyChangePct > 0.5 && niftyAboveVwap -> MarketCondition.BULLISH
            niftyChangePct < -0.5 && !niftyAboveVwap -> MarketCondition.BEARISH
            else -> MarketCondition.CHOPPY
        }
    }

    fun score(stock: StockData, marketCondition: MarketCondition = MarketCondition.CHOPPY): Double {
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

        // Bollinger Band position
        if (stock.bollingerUpper > 0.0 && stock.bollingerLower > 0.0) {
            when {
                stock.ltp > stock.bollingerUpper -> score -= 8.0   // overbought
                stock.ltp < stock.bollingerLower -> score -= 5.0   // oversold — could reverse
                stock.ltp > stock.bollingerMiddle -> score += 5.0  // above mid = mild bullish
                else -> score -= 2.0
            }
        }

        // ADX trend strength
        if (stock.adx > 0.0) {
            when {
                stock.adx >= 40.0 -> {
                    // Strong trend — reward alignment
                    if (stock.adxDiPlus > stock.adxDiMinus) score += 8.0
                    else score -= 5.0
                }
                stock.adx >= 25.0 -> {
                    if (stock.adxDiPlus > stock.adxDiMinus) score += 5.0
                    else score -= 3.0
                }
                else -> score -= 3.0  // choppy/weak trend
            }
        }

        // Candlestick pattern signal
        score += when (stock.candleSignal) {
            "BULLISH" -> when (stock.candlePattern) {
                "BULLISH_ENGULFING" -> 10.0
                "MORNING_STAR" -> 8.0
                "HAMMER" -> 6.0
                else -> 4.0
            }
            "BEARISH" -> when (stock.candlePattern) {
                "BEARISH_ENGULFING" -> -10.0
                "EVENING_STAR" -> -8.0
                "SHOOTING_STAR" -> -6.0
                else -> -4.0
            }
            else -> 0.0
        }

        // Market condition awareness
        score += when (marketCondition) {
            MarketCondition.BULLISH -> if (stock.aboveVwap && stock.changePct > 0) 6.0 else 0.0
            MarketCondition.BEARISH -> if (!stock.aboveVwap && stock.changePct < 0) -6.0 else -3.0
            MarketCondition.CHOPPY -> -3.0   // penalize all setups slightly in choppy market
        }

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
