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
    //
    // When volumes is non-null and non-empty, uses volume-weighted least squares:
    //   weight_i = volume_i / avg_volume
    // High-volume days carry more signal; low-volume days are discounted.
    // Falls back to ordinary regression when volumes is null (existing callers unaffected).
    fun predictPrice(closes: List<Double>, atr: Double, volumes: List<Double>? = null): PricePrediction {
        if (closes.size < 10) {
            val ltp = closes.lastOrNull() ?: 0.0
            return PricePrediction(ltp + atr, ltp - atr, "Sideways", 50)
        }

        val window = closes.takeLast(30)
        val n = window.size

        // Build weights — use volume if available, uniform otherwise
        val rawVols = volumes?.takeLast(n)
        val weights: List<Double> = if (!rawVols.isNullOrEmpty() && rawVols.size == n) {
            val avgVol = rawVols.average().coerceAtLeast(1.0)
            rawVols.map { (it / avgVol).coerceIn(0.1, 5.0) }  // clamp: outliers don't dominate
        } else {
            List(n) { 1.0 }
        }

        val wSum   = weights.sum().coerceAtLeast(1e-9)
        val xMean  = weights.indices.sumOf { i -> weights[i] * i } / wSum
        val yMean  = weights.indices.sumOf { i -> weights[i] * window[i] } / wSum

        var num = 0.0
        var den = 0.0
        for (i in window.indices) {
            num += weights[i] * (i - xMean) * (window[i] - yMean)
            den += weights[i] * (i - xMean) * (i - xMean)
        }
        val slope = if (den != 0.0) num / den else 0.0
        val predictedClose = window.last() + slope  // next session projection

        val ltp = closes.last()
        val slopePct = if (ltp != 0.0) (slope / ltp) * 100 else 0.0

        // Adaptive ATR multiplier: widen band when stock is more volatile than its recent average
        val last20 = window.takeLast(20)
        val avgDailyMove = if (last20.size >= 2)
            (1 until last20.size).map { kotlin.math.abs(last20[it] - last20[it - 1]) }.average()
        else atr
        val atrMultiplier = if (atr > avgDailyMove * 1.3) 0.75 else 0.5

        val high = predictedClose + atr * atrMultiplier
        val low = predictedClose - atr * atrMultiplier

        val direction = when {
            slopePct > 0.3 -> "Up"
            slopePct < -0.3 -> "Down"
            else -> "Sideways"
        }

        // Confidence based on recent momentum consistency + slope strength
        val last5 = closes.takeLast(5)
        val consistentDays = when (direction) {
            "Up" -> (1 until last5.size).count { last5[it] > last5[it - 1] }
            "Down" -> (1 until last5.size).count { last5[it] < last5[it - 1] }
            else -> 0
        }
        val baseConfidence = when (consistentDays) {
            4 -> 80; 3 -> 70; 2 -> 60; else -> 50
        }
        val slopeBonus = when {
            kotlin.math.abs(slopePct) > 1.0 -> 10
            kotlin.math.abs(slopePct) > 0.6 -> 5
            else -> 0
        }
        val confidence = (baseConfidence + slopeBonus).coerceAtMost(90)

        return PricePrediction(high, low, direction, confidence)
    }

    // ── Bollinger Bands (20-period, 2 std dev) ───────────────────────────────────
    fun bollingerBands(closes: List<Double>, period: Int = 20, multiplier: Double = 2.0): BollingerBands {
        if (closes.size < period) {
            val ltp = closes.lastOrNull() ?: 0.0
            return BollingerBands(ltp, ltp, ltp)
        }
        val window = closes.takeLast(period)
        val mean = window.average()
        val variance = window.sumOf { (it - mean) * (it - mean) } / period
        val stdDev = sqrt(variance)
        return BollingerBands(
            upper = mean + multiplier * stdDev,
            middle = mean,
            lower = mean - multiplier * stdDev,
        )
    }

    // ── ADX (Average Directional Index, 14-period) ───────────────────────────────
    fun adx(highs: List<Double>, lows: List<Double>, closes: List<Double>, period: Int = 14): AdxResult {
        val size = minOf(highs.size, lows.size, closes.size)
        if (size < period + 2) return AdxResult(0.0, 0.0, 0.0)

        val trList = mutableListOf<Double>()
        val dmPlus = mutableListOf<Double>()
        val dmMinus = mutableListOf<Double>()

        for (i in 1 until size) {
            val tr = maxOf(
                highs[i] - lows[i],
                abs(highs[i] - closes[i - 1]),
                abs(lows[i] - closes[i - 1])
            )
            trList += tr
            val upMove = highs[i] - highs[i - 1]
            val downMove = lows[i - 1] - lows[i]
            dmPlus += if (upMove > downMove && upMove > 0) upMove else 0.0
            dmMinus += if (downMove > upMove && downMove > 0) downMove else 0.0
        }

        fun smoothed(list: List<Double>): List<Double> {
            if (list.size < period) return emptyList()
            val result = mutableListOf(list.take(period).sum())
            for (i in period until list.size) {
                result += result.last() - result.last() / period + list[i]
            }
            return result
        }

        val smoothTr = smoothed(trList)
        val smoothPlus = smoothed(dmPlus)
        val smoothMinus = smoothed(dmMinus)
        if (smoothTr.isEmpty()) return AdxResult(0.0, 0.0, 0.0)

        val diPlus = smoothPlus.zip(smoothTr).map { (p, t) -> if (t != 0.0) 100.0 * p / t else 0.0 }
        val diMinus = smoothMinus.zip(smoothTr).map { (m, t) -> if (t != 0.0) 100.0 * m / t else 0.0 }
        val dx = diPlus.zip(diMinus).map { (p, m) ->
            val sum = p + m
            if (sum != 0.0) 100.0 * abs(p - m) / sum else 0.0
        }

        val adxVal = if (dx.size >= period) dx.takeLast(period).average() else dx.average()
        return AdxResult(
            adx = adxVal,
            diPlus = diPlus.lastOrNull() ?: 0.0,
            diMinus = diMinus.lastOrNull() ?: 0.0,
        )
    }

    // ── Candlestick Pattern Detection ────────────────────────────────────────────
    fun detectCandlePattern(
        opens: List<Double>,
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
    ): CandlePattern {
        val size = minOf(opens.size, highs.size, lows.size, closes.size)
        if (size < 2) return CandlePattern("NONE", "No pattern", CandleSignal.NEUTRAL)

        val o = opens[size - 1]; val h = highs[size - 1]
        val l = lows[size - 1]; val c = closes[size - 1]
        val po = opens[size - 2]; val ph = highs[size - 2]
        val pl = lows[size - 2]; val pc = closes[size - 2]

        val body = abs(c - o)
        val range = h - l
        val upperShadow = h - maxOf(o, c)
        val lowerShadow = minOf(o, c) - l
        val prevBody = abs(pc - po)
        val isBullish = c > o
        val isPrevBullish = pc > po

        return when {
            // Doji — very small body
            range > 0 && body / range < 0.1 ->
                CandlePattern("DOJI", "Doji — indecision, potential reversal", CandleSignal.NEUTRAL)

            // Hammer — small body at top, long lower shadow, bullish reversal
            lowerShadow >= body * 2 && upperShadow <= body * 0.3 && !isBullish.not() ->
                CandlePattern("HAMMER", "Hammer — bullish reversal signal", CandleSignal.BULLISH)

            // Shooting Star — small body at bottom, long upper shadow, bearish reversal
            upperShadow >= body * 2 && lowerShadow <= body * 0.3 && isBullish ->
                CandlePattern("SHOOTING_STAR", "Shooting Star — bearish reversal signal", CandleSignal.BEARISH)

            // Bullish Engulfing
            !isBullish.not() && !isPrevBullish && body > prevBody && o <= pc && c >= po ->
                CandlePattern("BULLISH_ENGULFING", "Bullish Engulfing — strong reversal up", CandleSignal.BULLISH)

            // Bearish Engulfing
            !isBullish && isPrevBullish && body > prevBody && o >= pc && c <= po ->
                CandlePattern("BEARISH_ENGULFING", "Bearish Engulfing — strong reversal down", CandleSignal.BEARISH)

            // Morning Star (3-candle) requires size >= 3
            size >= 3 && closes[size - 3] < opens[size - 3] && body < prevBody * 0.5 && isBullish ->
                CandlePattern("MORNING_STAR", "Morning Star — bullish reversal", CandleSignal.BULLISH)

            // Evening Star
            size >= 3 && closes[size - 3] > opens[size - 3] && body < prevBody * 0.5 && !isBullish ->
                CandlePattern("EVENING_STAR", "Evening Star — bearish reversal", CandleSignal.BEARISH)

            else -> CandlePattern("NONE", "No clear pattern", CandleSignal.NEUTRAL)
        }
    }

    // ── Swing High Resistance / Swing Low Support (30-day daily bars) ────────
    //
    // A swing high is a bar whose high is strictly greater than the highs of
    // 'lookback' bars on each side.  We scan the last 'bars' daily candles
    // (excluding the most-recent bar, which may be today's partial intraday
    // data) and return the highest qualifying swing high.
    //
    // Used by "Ready to Trade" mode to detect real-time resistance breakouts:
    //   ltp > swingHighResistance  AND  ltp ≤ resistance * 1.05  → fresh breakout
    //
    fun swingHighResistance(highs: List<Double>, lookback: Int = 2, bars: Int = 30): Double {
        // Drop the last bar (today's partial) and take the preceding 'bars' candles
        val window = highs.dropLast(1).takeLast(bars)
        if (window.size < lookback * 2 + 1) return window.maxOrNull() ?: 0.0
        val swingHighs = mutableListOf<Double>()
        for (i in lookback until window.size - lookback) {
            val h = window[i]
            if ((1..lookback).all { j -> h > window[i - j] && h > window[i + j] }) {
                swingHighs.add(h)
            }
        }
        return swingHighs.maxOrNull() ?: window.maxOrNull() ?: 0.0
    }

    fun swingLowSupport(lows: List<Double>, lookback: Int = 2, bars: Int = 30): Double {
        val window = lows.dropLast(1).takeLast(bars)
        if (window.size < lookback * 2 + 1) return window.minOrNull() ?: 0.0
        val swingLows = mutableListOf<Double>()
        for (i in lookback until window.size - lookback) {
            val l = window[i]
            if ((1..lookback).all { j -> l < window[i - j] && l < window[i + j] }) {
                swingLows.add(l)
            }
        }
        return swingLows.minOrNull() ?: window.minOrNull() ?: 0.0
    }

    // ── Pivot Points (Standard) ───────────────────────────────────────────────
    fun pivotPoints(prevHigh: Double, prevLow: Double, prevClose: Double): PivotPoints {
        val cpp = (prevHigh + prevLow + prevClose) / 3.0
        return PivotPoints(
            cpp = cpp,
            r1  = 2 * cpp - prevLow,
            r2  = cpp + (prevHigh - prevLow),
            s1  = 2 * cpp - prevHigh,
            s2  = cpp - (prevHigh - prevLow),
        )
    }

    // ── Supertrend (period=7, multiplier=3.0) ────────────────────────────────
    // Returns BUY when price is above the lower band, SELL when below upper band.
    fun supertrend(
        highs: List<Double>,
        lows: List<Double>,
        closes: List<Double>,
        period: Int = 7,
        multiplier: Double = 3.0,
    ): SupertrendResult {
        val size = minOf(highs.size, lows.size, closes.size)
        if (size < period + 2) return SupertrendResult("NEUTRAL", closes.lastOrNull() ?: 0.0)

        // Wilder-smoothed ATR
        val tr = (1 until size).map { i ->
            maxOf(highs[i] - lows[i], abs(highs[i] - closes[i - 1]), abs(lows[i] - closes[i - 1]))
        }
        val atrList = mutableListOf(tr.take(period).average())
        for (i in period until tr.size) {
            atrList.add((atrList.last() * (period - 1) + tr[i]) / period)
        }

        // Build Supertrend bands
        val start = period
        val finalUpper = mutableListOf<Double>()
        val finalLower = mutableListOf<Double>()
        val supertrendLine = mutableListOf<Double>()
        val directionUp    = mutableListOf<Boolean>()

        for (i in atrList.indices) {
            val idx  = i + start
            if (idx >= size) break
            val hl2  = (highs[idx] + lows[idx]) / 2.0
            val bu   = hl2 + multiplier * atrList[i]
            val bl   = hl2 - multiplier * atrList[i]

            val fu = if (i > 0 && bu < finalUpper.last()) bu
                     else if (i > 0 && closes[idx - 1] > finalUpper.last()) bu
                     else if (i > 0) finalUpper.last() else bu
            val fl = if (i > 0 && bl > finalLower.last()) bl
                     else if (i > 0 && closes[idx - 1] < finalLower.last()) bl
                     else if (i > 0) finalLower.last() else bl
            finalUpper.add(fu); finalLower.add(fl)

            val up = if (i == 0) true
                     else if (supertrendLine.last() == finalUpper[i - 1]) closes[idx] > fu
                     else closes[idx] >= fl
            directionUp.add(up)
            supertrendLine.add(if (up) fl else fu)
        }

        val isUp = directionUp.lastOrNull() ?: true
        return SupertrendResult(
            signal = if (isUp) "BUY" else "SELL",
            band   = supertrendLine.lastOrNull() ?: closes.last(),
        )
    }

    data class BollingerBands(val upper: Double, val middle: Double, val lower: Double)

    data class AdxResult(val adx: Double, val diPlus: Double, val diMinus: Double) {
        val isTrending: Boolean get() = adx >= 25.0
        val isStrongTrend: Boolean get() = adx >= 40.0
        val isBullishTrend: Boolean get() = isTrending && diPlus > diMinus
        val isBearishTrend: Boolean get() = isTrending && diMinus > diPlus
    }

    enum class CandleSignal { BULLISH, BEARISH, NEUTRAL }

    data class CandlePattern(val type: String, val label: String, val signal: CandleSignal)

    data class PricePrediction(
        val high: Double,
        val low: Double,
        val direction: String,
        val confidence: Int,
    )

    data class PivotPoints(
        val cpp: Double,
        val r1: Double,
        val r2: Double,
        val s1: Double,
        val s2: Double,
    )

    data class SupertrendResult(
        val signal: String,  // "BUY" / "SELL" / "NEUTRAL"
        val band: Double,    // support band if BUY, resistance band if SELL
    )
}
