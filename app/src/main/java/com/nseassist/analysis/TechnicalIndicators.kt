package com.nseassist.analysis

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TechnicalIndicators {

    // ── RSI (14-period) ──────────────────────────────────────────────────────────
    fun rsi(closes: List<Double>, period: Int = 14): Double {
        if (closes.size < period + 1) return 50.0
        val gains = mutableListOf<Double>()
        val losses = mutableListOf<Double>()
        for (i in 1 until closes.size) {
            val diff = closes[i] - closes[i - 1]
            gains.add(max(diff, 0.0))
            losses.add(max(-diff, 0.0))
        }
        var avgGain = gains.takeLast(period).average()
        var avgLoss = losses.takeLast(period).average()
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1 + rs))
    }

    // ── EMA ─────────────────────────────────────────────────────────────────────
    fun ema(closes: List<Double>, period: Int): Double {
        if (closes.size < period) return closes.lastOrNull() ?: 0.0
        val k = 2.0 / (period + 1)
        var emaVal = closes.take(period).average()
        for (i in period until closes.size) {
            emaVal = closes[i] * k + emaVal * (1 - k)
        }
        return emaVal
    }

    private fun emaList(closes: List<Double>, period: Int): List<Double> {
        if (closes.size < period) return emptyList()
        val k = 2.0 / (period + 1)
        val result = mutableListOf(closes.take(period).average())
        for (i in period until closes.size) {
            result.add(closes[i] * k + result.last() * (1 - k))
        }
        return result
    }

    // ── MACD (12, 26, 9) ─────────────────────────────────────────────────────────
    fun macd(closes: List<Double>): Pair<Double, Double> {
        if (closes.size < 35) return Pair(0.0, 0.0)
        val ema12 = emaList(closes, 12)
        val ema26 = emaList(closes, 26)
        val offset = ema12.size - ema26.size
        val macdLine = ema12.drop(offset).zip(ema26).map { (a, b) -> a - b }
        val signal = emaList(macdLine, 9).lastOrNull() ?: 0.0
        return Pair(macdLine.lastOrNull() ?: 0.0, signal)
    }

    // ── ATR (Average True Range) ─────────────────────────────────────────────────
    fun atr(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): Double {
        if (highs.size < 2) return 0.0
        val trList = (1 until minOf(highs.size, lows.size, closes.size)).map { i ->
            val tr1 = highs[i] - lows[i]
            val tr2 = abs(highs[i] - closes[i - 1])
            val tr3 = abs(lows[i] - closes[i - 1])
            maxOf(tr1, tr2, tr3)
        }
        return if (trList.size >= period) trList.takeLast(period).average()
        else trList.average()
    }

    // ── 6-Month Trend ────────────────────────────────────────────────────────────
    fun trend6Month(closes: List<Double>): String {
        if (closes.size < 10) return "Unknown"
        val first = closes.take(closes.size / 3).average()
        val last = closes.takeLast(closes.size / 3).average()
        val pct = (last - first) / first * 100
        return when {
            pct > 8 -> "Bullish"
            pct < -8 -> "Bearish"
            else -> "Range-Bound"
        }
    }

    // ── 2-Week Trend ─────────────────────────────────────────────────────────────
    fun trend2Week(closes: List<Double>): String {
        if (closes.size < 10) return "Unknown"
        val recent = closes.takeLast(10)
        val higherHighs = (1 until recent.size).count { recent[it] > recent[it - 1] }
        return when {
            higherHighs >= 7 -> "Trending Up"
            higherHighs <= 3 -> "Trending Down"
            else -> "Consolidating"
        }
    }

    // ── Price Prediction (Linear Regression + ATR) ───────────────────────────────
    fun predictPrice(closes: List<Double>, atr: Double): PricePrediction {
        if (closes.size < 10) {
            val ltp = closes.lastOrNull() ?: 0.0
            return PricePrediction(ltp + atr, ltp - atr, "Sideways", 50)
        }

        // Simple linear regression on last 30 days
        val window = closes.takeLast(30)
        val n = window.size
        val xMean = (n - 1) / 2.0
        val yMean = window.average()
        var num = 0.0
        var den = 0.0
        for (i in window.indices) {
            num += (i - xMean) * (window[i] - yMean)
            den += (i - xMean) * (i - xMean)
        }
        val slope = if (den != 0.0) num / den else 0.0
        val predictedClose = window.last() + slope  // next session projection

        val ltp = closes.last()
        val high = predictedClose + atr * 0.5
        val low = predictedClose - atr * 0.5
        val slopePct = if (ltp != 0.0) (slope / ltp) * 100 else 0.0

        val direction = when {
            slopePct > 0.3 -> "Up"
            slopePct < -0.3 -> "Down"
            else -> "Sideways"
        }

        // Confidence based on recent momentum consistency
        val last5 = closes.takeLast(5)
        val consistentDays = when (direction) {
            "Up" -> (1 until last5.size).count { last5[it] > last5[it - 1] }
            "Down" -> (1 until last5.size).count { last5[it] < last5[it - 1] }
            else -> 0
        }
        val confidence = when (consistentDays) {
            4 -> 80; 3 -> 70; 2 -> 60; else -> 50
        }

        return PricePrediction(high, low, direction, confidence)
    }

    data class PricePrediction(
        val high: Double,
        val low: Double,
        val direction: String,
        val confidence: Int,
    )
}
