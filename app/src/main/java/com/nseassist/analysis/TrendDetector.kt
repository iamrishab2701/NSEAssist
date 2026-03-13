package com.nseassist.analysis

import kotlin.math.abs

/**
 * Port of `_detect_trend_signal()` from market.py
 * Detects: REVERSAL_BUY, REVERSAL_SELL, BREAKOUT, BREAKDOWN,
 *          CONTINUATION_UP, CONTINUATION_DN, NEUTRAL
 */
class TrendDetector {

    data class TrendSignal(
        val type: String,
        val label: String,
        val streakDays: Int,
        val direction: String,   // UP / DOWN / NEUTRAL
        val scoreBoost: Double,
    )

    fun detect(closes: List<Double>, volumes: List<Long>): TrendSignal {
        if (closes.size < 5) return neutral()

        // ── Streak detection (automatic — no fixed window) ───────────────────────
        var streakUp = 0
        var streakDown = 0
        for (i in closes.indices.reversed().drop(1)) {
            if (closes[i + 1] > closes[i]) {
                if (streakDown > 0) break
                streakUp++
            } else if (closes[i + 1] < closes[i]) {
                if (streakUp > 0) break
                streakDown++
            } else break
        }

        // ── RSI proxy (14-period) ────────────────────────────────────────────────
        val rsi = rsiProxy(closes)

        // ── Volume ratio (last vs 20-day avg) ────────────────────────────────────
        val avgVol = if (volumes.size >= 20) volumes.takeLast(20).average() else volumes.average()
        val lastVol = volumes.lastOrNull()?.toDouble() ?: avgVol
        val volRatio = if (avgVol > 0) lastVol / avgVol else 1.0

        // ── 20-day consolidation check ───────────────────────────────────────────
        val window20 = closes.takeLast(20)
        val rangeHigh = window20.maxOrNull() ?: 0.0
        val rangeLow = window20.minOrNull() ?: 0.0
        val rangePct = if (rangeLow > 0) (rangeHigh - rangeLow) / rangeLow * 100 else 100.0
        val isConsolidating = rangePct < 10.0
        val nearHigh = closes.last() >= rangeHigh * 0.97
        val nearLow = closes.last() <= rangeLow * 1.03

        // ── Signal logic ─────────────────────────────────────────────────────────
        return when {
            streakDown >= 3 && rsi < 38 ->
                TrendSignal("REVERSAL_BUY", "↓ ${streakDown}-day downtrend · RSI ${fmtRsi(rsi)} oversold → reversal watch", streakDown, "DOWN", 20.0)

            streakUp >= 3 && rsi > 68 ->
                TrendSignal("REVERSAL_SELL", "↑ ${streakUp}-day uptrend · RSI ${fmtRsi(rsi)} overbought → reversal watch", streakUp, "UP", -10.0)

            isConsolidating && nearHigh && volRatio >= 1.5 ->
                TrendSignal("BREAKOUT", "Consolidating · breakout attempt · ${fmtVol(volRatio)}x vol", 0, "UP", 25.0)

            isConsolidating && nearLow && volRatio >= 1.5 ->
                TrendSignal("BREAKDOWN", "Consolidating · breakdown attempt · ${fmtVol(volRatio)}x vol", 0, "DOWN", -15.0)

            streakUp >= 2 && rsi in 45.0..65.0 ->
                TrendSignal("CONTINUATION_UP", "↑ ${streakUp}-day uptrend continuing · RSI ${fmtRsi(rsi)}", streakUp, "UP", 10.0)

            streakDown >= 2 && rsi in 35.0..55.0 ->
                TrendSignal("CONTINUATION_DN", "↓ ${streakDown}-day downtrend continuing · RSI ${fmtRsi(rsi)}", streakDown, "DOWN", -5.0)

            streakUp >= 1 ->
                TrendSignal("NEUTRAL", "${streakUp}-day uptrend · RSI ${fmtRsi(rsi)}", streakUp, "UP", 0.0)

            streakDown >= 1 ->
                TrendSignal("NEUTRAL", "${streakDown}-day downtrend · RSI ${fmtRsi(rsi)}", streakDown, "DOWN", 0.0)

            else -> neutral()
        }
    }

    private fun rsiProxy(closes: List<Double>): Double {
        if (closes.size < 15) return 50.0
        val period = 14
        val diffs = (1 until closes.size).map { closes[it] - closes[it - 1] }
        val gains = diffs.map { if (it > 0) it else 0.0 }
        val losses = diffs.map { if (it < 0) -it else 0.0 }
        val avgGain = gains.takeLast(period).average()
        val avgLoss = losses.takeLast(period).average()
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1 + rs))
    }

    private fun neutral() = TrendSignal("NEUTRAL", "No clear signal", 0, "NEUTRAL", 0.0)
    private fun fmtRsi(r: Double) = String.format("%.1f", r)
    private fun fmtVol(r: Double) = String.format("%.1f", r)
}
