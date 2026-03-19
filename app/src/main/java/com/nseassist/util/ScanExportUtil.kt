package com.nseassist.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import com.nseassist.data.model.ScanCategory
import com.nseassist.data.model.StockData
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ScanExportUtil {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun shareScanResults(context: Context, capital: Double, category: ScanCategory, stocks: List<StockData>, isShortMode: Boolean = false) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val filePrefix = if (isShortMode) "nseassist_short_pack" else "nseassist_ai_pack"
        val exportFile = File(exportDir, "${filePrefix}_$timestamp.txt")

        val payload = mapOf(
            "generatedAt" to timestamp,
            "capital" to capital,
            "category" to category.routeValue,
            "scanMode" to if (isShortMode) "SHORT" else "NORMAL",
            "stockCount" to stocks.size,
            "allowedSymbols" to stocks.map { it.symbol },
            "stocks" to stocks.map { stock ->
                mapOf(
                    "symbol" to stock.symbol,
                    "name" to stock.name,
                    "ltp" to stock.ltp,
                    "change" to stock.change,
                    "changePct" to stock.changePct,
                    "dayHigh" to stock.dayHigh,
                    "dayLow" to stock.dayLow,
                    "volume" to stock.volume,
                    "avgVolume" to stock.avgVolume,
                    "vwap" to stock.vwap,
                    "open" to stock.open,
                    "score" to stock.score,
                    // In short mode, shortScore = 100 - score (higher = better short candidate)
                    "shortScore" to if (isShortMode) (100.0 - stock.score) else null,
                    "optionAction" to stock.optionAction,
                    "predictedDirection" to stock.predictedDirection,
                    "predictionConfidence" to stock.predictionConfidence,
                    "trendSignal" to stock.trendSignalLabel.ifBlank { stock.trendSignalType },
                    "aboveVwap" to stock.aboveVwap,
                    "volumeSpike" to stock.volumeSpike,
                    "gapType" to stock.gapType,
                    "marketCap" to stock.marketCap,
                    "sector" to stock.sector,
                    "industry" to stock.industry,
                    "newsImpactScore" to stock.newsImpactScore,
                    "news" to stock.news?.let { news ->
                        mapOf(
                            "overallSentiment" to news.sentiment.name,
                            "stockSentiment" to news.stockSentiment.name,
                            "sectorSentiment" to news.sectorSentiment.name,
                            "stockArticleCount" to news.stockArticleCount,
                            "sectorArticleCount" to news.sectorArticleCount,
                            "sourceCount" to news.sourceCount,
                            "sector" to news.sector,
                            "articles" to news.articles.map { article ->
                                mapOf(
                                    "headline" to article.headline,
                                    "source" to article.source,
                                    "ageText" to article.ageText,
                                    "sentiment" to article.sentiment.name,
                                )
                            },
                        )
                    },
                    // Technical indicators
                    "rsi" to stock.rsi,
                    "ema20" to stock.ema20,
                    "ema50" to stock.ema50,
                    "macdLine" to stock.macdLine,
                    "macdSignal" to stock.macdSignal,
                    "macdBullish" to (stock.macdLine > stock.macdSignal),
                    "atr" to stock.atr,
                    "support" to stock.support,
                    "resistance" to stock.resistance,

                    // Bollinger Bands
                    "bollingerUpper" to stock.bollingerUpper,
                    "bollingerMiddle" to stock.bollingerMiddle,
                    "bollingerLower" to stock.bollingerLower,
                    "bollingerPos" to when {
                        stock.bollingerUpper <= 0.0 -> "UNKNOWN"
                        stock.ltp > stock.bollingerUpper -> "ABOVE_UPPER"
                        stock.ltp < stock.bollingerLower -> "BELOW_LOWER"
                        stock.ltp > stock.bollingerMiddle -> "ABOVE_MID"
                        else -> "BELOW_MID"
                    },

                    // ADX
                    "adx" to stock.adx,
                    "adxDiPlus" to stock.adxDiPlus,
                    "adxDiMinus" to stock.adxDiMinus,
                    "adxStrength" to when {
                        stock.adx >= 40.0 -> "STRONG"
                        stock.adx >= 25.0 -> "TRENDING"
                        stock.adx > 0.0 -> "WEAK"
                        else -> "UNKNOWN"
                    },
                    "adxTrend" to if (stock.adxDiPlus > stock.adxDiMinus) "BULLISH" else "BEARISH",

                    // Candlestick
                    "candlePattern" to stock.candlePattern,
                    "candleSignal" to stock.candleSignal,
                    "candlePatternLabel" to stock.candlePatternLabel,

                    // Trend
                    "trend6Month" to stock.trend6Month,
                    "trend2Week" to stock.trend2Week,
                    "trendSignalType" to stock.trendSignalType,
                    "streakDays" to stock.streakDays,

                    // Prediction
                    "predictedHigh" to stock.predictedHigh,
                    "predictedLow" to stock.predictedLow,

                    // Affordability
                    "isAffordable" to (stock.ltp <= capital),
                    "maxQtyWithinCapital" to if (stock.ltp > 0) kotlin.math.floor(capital / stock.ltp).toInt() else 0,
                    "maxAmountWithinCapital" to if (stock.ltp > 0) kotlin.math.floor(capital / stock.ltp) * stock.ltp else 0.0,
                )
            },
        )

        exportFile.writeText(buildAiPack(capital, category, stocks.size, isShortMode, payload))

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", exportFile)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "NSE Assist AI analysis pack")
            putExtra(Intent.EXTRA_TEXT, "Upload this single file to your AI tool for actionable analysis.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(Intent.createChooser(shareIntent, "Export AI analysis pack"))
    }

    private fun buildAiPack(
        capital: Double,
        category: ScanCategory,
        stockCount: Int,
        isShortMode: Boolean,
        payload: Map<String, Any?>,
    ): String {
        val shortModeHeader = if (isShortMode) """
⚠ SHORT SCAN MODE — READ THIS BEFORE ANYTHING ELSE ⚠
This data was exported from a SHORT scan. The user wants to SELL (short) stocks, not buy them.
- All stocks in this list are bearish candidates.
- "shortScore" field = how good each stock is for shorting (100 = perfect short, 0 = avoid).
- Direction for ALL picks must be: SELL (Short / MIS)
- Stop loss must be ABOVE the entry price (you lose if price rises).
- Target must be BELOW the entry price (you profit when price falls).
- Do NOT suggest BUY or LONG setups — this is exclusively a short-selling session.
- A stock with low RSI, negative changePct, below VWAP, bearish candle, and adxTrend=BEARISH is IDEAL.
- Avoid stocks with RSI < 20 — they are already oversold and may bounce against you.
- Avoid stocks with RSI > 50 — they are too bullish for shorting.
- The sweet spot for shorting: RSI 25–48, falling below EMA20/EMA50, MACD bearish.
⚠ END SHORT MODE HEADER ⚠
""".trimIndent() else ""

        val objective = if (isShortMode) """
OBJECTIVE:
- Identify the strongest SHORT (SELL MIS) setups from the provided stock list.
- Suggest how much capital to allocate within the available capital.
- Provide 1 strong primary short pick and up to 2 additional short candidates.
- Total picks including primary must not exceed 3.
- Keep the answer actionable and concise.
- ALL directions must be SELL (Short).
""".trimIndent() else """
OBJECTIVE:
- Predict the strongest next intraday setups from the provided stock list.
- Suggest how much capital to allocate within the available capital.
- Provide 1 strong primary pick and up to 2 additional candidates.
- Total picks including primary must not exceed 3.
- Keep the answer actionable and concise.
- The best answer can be LONG, SHORT, reversal, continuation, or No Trade setups.
""".trimIndent()

        val decisionRules = if (isShortMode) """
DECISION RULES:
- Use only stocks present in SCAN_DATA_JSON.
- Use only symbols present in allowedSymbols.
- Do not invent symbols, prices, targets, or reasons outside the provided data.
- Never choose a stock whose isAffordable is false.
- Never choose a stock whose LTP is greater than the user's capital.
- Rank candidates by shortScore (highest shortScore = best short candidate).
- Use ALL available signals to rank short candidates:
  - shortScore (primary ranking), changePct (negative preferred), volumeSpike, aboveVwap (false preferred)
  - rsi (25–48 sweet spot for shorting), ema20, ema50 (price below both = strong bear)
  - macdBullish=false preferred, adxTrend=BEARISH preferred
  - candlePattern + candleSignal (BEARISH patterns = short confirmation)
  - bollingerPos: ABOVE_UPPER = overbought = good short entry
  - newsImpactScore, stock news (negative news supports shorting)
  - maxQtyWithinCapital, maxAmountWithinCapital
- Stop loss must be placed ABOVE entry price.
- Target must be placed BELOW entry price.
- Skip any stock with RSI < 20 (already deeply oversold — bounce risk).
- Skip any stock with RSI > 50 (too bullish for a safe short).
- If no valid short setup exists, output No Trade.
- Provide 1 primary pick and up to 2 additional candidates, max 3 total.
- Additional candidates are for watching, not simultaneous trading.
- The total live allocation must never exceed total capital.
- Each candidate must be individually affordable within the available capital.
""".trimIndent() else """
DECISION RULES:
- Use only stocks present in SCAN_DATA_JSON.
- Use only symbols present in allowedSymbols.
- Do not invent symbols, prices, targets, or reasons outside the provided data.
- If a stock symbol is not present in allowedSymbols, it is forbidden.
- Never choose a stock whose isAffordable is false.
- Never choose a stock whose LTP is greater than the user's capital.
- Do not assume only positive movers are valid trades.
- A stock in loss can still be selected if the predicted next move is a convincing bullish reversal or bearish continuation.
- Use ALL available signals to rank candidates:
  - score, changePct, volumeSpike, aboveVwap, gapType, optionAction, trendSignal, predictedDirection, predictionConfidence
  - rsi, ema20, ema50, macdBullish, atr, support, resistance
  - bollingerPos (ABOVE_UPPER=overbought, BELOW_LOWER=oversold/bounce, ABOVE_MID=mild bullish)
  - adxStrength (STRONG/TRENDING=valid trend, WEAK=choppy), adxTrend (BULLISH/BEARISH)
  - candlePattern + candleSignal (BULLISH patterns favor long, BEARISH favor short)
  - newsImpactScore, stockNews, sectorNews (last 24h)
  - maxQtyWithinCapital, maxAmountWithinCapital
- Use 24-hour stock news and sector news as supporting context.
- Prefer stock-specific news over broad sector news when both exist.
- A positive or negative news cluster can strengthen or weaken the setup, but should not override obviously invalid trade structure.
- Candle patterns near support/resistance with Bollinger and ADX confirmation make stronger setups.
- If no setup looks strong enough, output No Trade.
- Provide 1 primary pick and up to 2 additional candidates, max 3 total.
- Additional candidates are for watching, not simultaneous trading.
- The total live allocation must never exceed total capital.
- Each candidate must be individually affordable within the available capital.
""".trimIndent()

        val prompt = """
$shortModeHeader
EXECUTE THIS TASK DIRECTLY. DO NOT DESCRIBE, SUMMARIZE, OR EXPLAIN THIS FILE.
DO NOT SAY THINGS LIKE "I checked the file", "this file contains", or "here is a breakdown".
USE THE DATA BELOW TO MAKE A DECISION.

ROLE:
You are an intraday stock decision assistant.

$objective

CONTEXT:
- Total capital: Rs ${"%.0f".format(capital)}
- Category: ${category.label}
- Scan mode: ${if (isShortMode) "SHORT (SELL MIS only)" else "NORMAL (BUY/LONG setups)"}
- Stock count in scan: $stockCount
- The user cannot buy any stock whose LTP is greater than Rs ${"%.0f".format(capital)}

$decisionRules

OUTPUT RULES:
- Output only the final answer.
- No intro, no summary, no explanation of the file.
- Follow the template exactly.
- Before finalizing the answer, internally verify that every suggested symbol exists in allowedSymbols and isAffordable=true.
- If validation fails, output No Trade.

REQUIRED OUTPUT TEMPLATE:
Primary Pick:
Symbol:
Direction:
Setup Type:
Reason:
Suggested Allocation:
Entry View:
Stop Loss:
Target:
Confidence:

Additional Candidate 1 (optional - watch only):
Symbol:
Direction:
Setup Type:
Reason:
Suggested Allocation (if primary misses):
Entry View:
Stop Loss:
Target:
Confidence:

Additional Candidate 2 (optional - watch only):
Symbol:
Direction:
Setup Type:
Reason:
Suggested Allocation (if primary misses):
Entry View:
Stop Loss:
Target:
Confidence:

Risk Notes:
- note 1
- note 2

Final Verdict:

Disclaimer:
Informational only, not financial advice.

If no valid trade exists, use this instead:
Primary Pick:
Symbol: No Trade
Direction: No Trade
Setup Type: No Trade
Reason: No strong setup from the provided scan
Suggested Allocation: Rs 0
Entry View: Avoid fresh entry
Stop Loss: N/A
Target: N/A
Confidence: Low

Risk Notes:
- Market conditions do not support a clean setup
- Wait for stronger confirmation

Final Verdict:
No Trade

Disclaimer:
Informational only, not financial advice.
        """.trimIndent()

        return buildString {
            appendLine(prompt)
            appendLine()
            appendLine("SCAN_DATA_JSON:")
            append(gson.toJson(payload))
        }
    }
}
