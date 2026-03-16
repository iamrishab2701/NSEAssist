package com.nseassist.analysis

import com.nseassist.data.model.SignalOutcomeRequest

/**
 * Signal Replay engine.
 *
 * Slides a window over historical daily OHLCV data and asks:
 * "If our indicator combination fired on day i, what happened on day i+1?"
 *
 * Signal fires when ALL of:
 *   - RSI in 45–65 (healthy zone, not overbought/oversold)
 *   - Price (close) above EMA20 (bullish structure)
 *   - MACD line > signal line (bullish momentum)
 *
 * Outcome:
 *   WIN    — next-day close >= entry + ATR × 1.2 (target)
 *   LOSS   — next-day close <= entry − ATR × 0.6 (stop loss)
 *   NEUTRAL — between target and stop loss
 *
 * This uses only closes + volumes (already present in StockData.priceHistory /
 * volumeHistory) so it needs no extra network calls.
 */
class SignalReplay {

    private val indicators = TechnicalIndicators()

    data class SignalEvent(
        val dayIndex: Int,
        val entryPrice: Double,
        val targetPrice: Double,
        val stopPrice: Double,
        val outcome: String,              // WIN / LOSS / NEUTRAL
        val signalDate: Long = 0L,        // epoch ms if available
        val rsi: Double = 0.0,
        val macdBullish: Boolean = false,
    )

    data class SignalReplayResult(
        val symbol: String,
        val totalSignals: Int,
        val wins: Int,
        val losses: Int,
        val neutrals: Int,
        val winRate: Double,              // 0.0 – 1.0
        val events: List<SignalEvent>,
        val toSignalOutcomes: List<SignalOutcomeRequest>,
    )

    /**
     * Run signal replay over the supplied price/volume history.
     *
     * @param symbol      Cleaned stock symbol (e.g. "RELIANCE")
     * @param closes      Daily close prices, oldest first (60–90 values)
     * @param volumes     Daily volumes, same order as closes
     * @param atr         Current ATR (used for target/SL bands)
     */
    fun replay(
        symbol: String,
        closes: List<Double>,
        volumes: List<Double>,
        atr: Double,
    ): SignalReplayResult {
        val events     = mutableListOf<SignalEvent>()
        val signalReqs = mutableListOf<SignalOutcomeRequest>()

        // Need at least 35 candles for MACD + 1 forward candle for outcome
        if (closes.size < 37 || atr <= 0.0) {
            return SignalReplayResult(symbol, 0, 0, 0, 0, 0.0, emptyList(), emptyList())
        }

        // Slide window from index 35 to size-2 (keep last candle for outcome check)
        for (i in 35 until closes.size - 1) {
            val window  = closes.subList(0, i + 1)
            val volWin  = if (volumes.size > i) volumes.subList(0, i + 1) else emptyList()

            val rsi  = indicators.rsi(window)
            val ema20 = indicators.ema(window, 20)
            val (macdLine, macdSig) = indicators.macd(window)

            val rsiOk    = rsi in 45.0..65.0
            val emaOk    = closes[i] > ema20
            val macdOk   = macdLine > macdSig

            if (!rsiOk || !emaOk || !macdOk) continue

            // Signal fired — evaluate next candle outcome
            val entry    = closes[i]
            val target   = entry + atr * 1.2
            val stopLoss = entry - atr * 0.6
            val nextClose = closes[i + 1]

            val outcome = when {
                nextClose >= target   -> "WIN"
                nextClose <= stopLoss -> "LOSS"
                else                  -> "NEUTRAL"
            }

            val event = SignalEvent(
                dayIndex    = i,
                entryPrice  = entry,
                targetPrice = target,
                stopPrice   = stopLoss,
                outcome     = outcome,
                rsi         = rsi,
                macdBullish = macdOk,
            )
            events.add(event)

            signalReqs.add(SignalOutcomeRequest(
                symbol               = symbol,
                signalDate           = System.currentTimeMillis() - ((closes.size - 1 - i).toLong() * 86_400_000L),
                rsi                  = rsi,
                aboveVwap            = emaOk,        // EMA20 used as VWAP proxy (no highs/lows stored)
                macdBullish          = macdOk,
                trendSignal          = "REPLAY",
                sessionPhase         = "",
                marketCondition      = "",
                predictionDirection  = "Up",
                score                = if (rsiOk && emaOk && macdOk) 70.0 else 50.0,
                outcome              = outcome,
            ))
        }

        val wins     = events.count { it.outcome == "WIN" }
        val losses   = events.count { it.outcome == "LOSS" }
        val neutrals = events.count { it.outcome == "NEUTRAL" }
        val total    = events.size
        val winRate  = if (total > 0) wins.toDouble() / total else 0.0

        return SignalReplayResult(
            symbol          = symbol,
            totalSignals    = total,
            wins            = wins,
            losses          = losses,
            neutrals        = neutrals,
            winRate         = winRate,
            events          = events,
            toSignalOutcomes = signalReqs,
        )
    }
}
