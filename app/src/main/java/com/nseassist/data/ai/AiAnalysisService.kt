package com.nseassist.data.ai

import com.google.gson.Gson
import com.nseassist.util.AppLoggingInterceptor
import com.nseassist.util.AppLogger
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nseassist.data.api.MemoryClient
import com.nseassist.data.model.AiAnalysisReport
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiRecommendedStock
import com.nseassist.data.model.AiStockAllocation
import com.nseassist.data.model.AiTradeDirection
import com.nseassist.data.model.NewsResult
import com.nseassist.data.model.PaperTradeRequest
import com.nseassist.data.model.ScanCategory
import com.nseassist.data.model.SingleStockAiAnalysis
import com.nseassist.data.model.StockAiContext
import com.nseassist.data.model.StockData
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiAnalysisService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .addInterceptor(AppLoggingInterceptor())
        .build()

    // ── Batch multi-stock analysis (AI Report screen) ─────────────────────────────

    suspend fun analyzeStocks(
        config: AiProviderConfig,
        capital: Double,
        category: ScanCategory,
        stocks: List<StockData>,
        vix: Double = 0.0,
    ): Result<AiAnalysisReport> = withContext(Dispatchers.IO) { runCatching {
        require(config.apiKey.isNotBlank()) { "API key missing for ${config.provider.label}" }
        require(stocks.isNotEmpty()) { "No stocks available for AI analysis" }
        AppLogger.d("AI", "Batch analysis — ${config.provider.label} model=${config.model} stocks=${stocks.size} capital=₹$capital category=${category.routeValue} vix=${"%.2f".format(vix)}")

        // Fetch historical context for top 5 candidates in parallel (4 s timeout each)
        val topSymbols = stocks.sortedByDescending { it.score }.take(5).map { it.symbol }
        val contextMap: Map<String, StockAiContext> = coroutineScope {
            topSymbols.map { sym ->
                async { sym to MemoryClient.getAiContext(sym) }
            }.awaitAll().associate { (sym, ctx) ->
                sym to (ctx ?: StockAiContext(sym, hasData = false))
            }
        }

        val prompt = buildBatchPrompt(capital, category, stocks, contextMap, vix)
        val rawResponse = callProvider(config, SYSTEM_PROMPT, prompt)
        val report = parseReport(rawResponse, config)
        AppLogger.d("AI", "Batch done — primary pick: ${report.primaryPick.symbol} ${report.primaryPick.direction} confidence=${report.primaryPick.confidence}%")

        // Auto-log primary pick recommendation (fire-and-forget)
        launch {
            val pick = report.primaryPick
            if (pick.direction != AiTradeDirection.NO_TRADE) {
                runCatching {
                    MemoryClient.logPaperTrade(PaperTradeRequest(
                        symbol       = pick.symbol,
                        companyName  = pick.companyName,
                        direction    = pick.direction.name,
                        entryPrice   = 0.0,       // not available in batch analysis
                        targetText   = pick.target,
                        stopLossText = pick.stopLoss,
                        quantity     = 0,
                        sessionPhase = "",
                        aiProvider   = config.provider.label,
                        confidence   = pick.confidence,
                        verdict      = "AI_BATCH",
                        rrRatio      = "",
                        reason       = pick.rationale.take(200),
                    ))
                }
            }
        }

        report
    } }

    // ── Single-stock deep-dive (Stock Detail screen FAB) ──────────────────────────

    suspend fun analyzeSingleStock(
        config: AiProviderConfig,
        capital: Double,
        stock: StockData,
        news: NewsResult?,
        vix: Double = 0.0,
        isShortMode: Boolean = false,
    ): Result<SingleStockAiAnalysis> = withContext(Dispatchers.IO) { runCatching {
        require(config.apiKey.isNotBlank()) { "API key missing for ${config.provider.label}" }
        AppLogger.d("AI", "Single-stock — ${config.provider.label} ${stock.symbol} LTP=₹${stock.ltp} vix=${"%.2f".format(vix)} shortMode=$isShortMode")

        // Fetch historical context for this stock (4 s timeout, non-blocking on failure)
        val context = MemoryClient.getAiContext(stock.symbol)

        val prompt = buildSingleStockPrompt(capital, stock, news, context, vix, isShortMode)
        val rawResponse = callProvider(config, SINGLE_STOCK_SYSTEM_PROMPT, prompt)
        val result = parseSingleStockReport(rawResponse, config)
        AppLogger.d("AI", "Single-stock done — ${stock.symbol} verdict=${result.verdict} direction=${result.direction} confidence=${result.confidence}%")

        // Auto-log every verdict to AI Audit (fire-and-forget)
        launch {
            runCatching {
                MemoryClient.logPaperTrade(PaperTradeRequest(
                    symbol       = stock.symbol,
                    companyName  = stock.name,
                    direction    = result.direction,
                    entryPrice   = if (result.verdict == "GO") stock.ltp else 0.0,
                    targetText   = result.target,
                    stopLossText = result.stopLoss,
                    quantity     = result.quantity,
                    sessionPhase = stock.sessionPhase,
                    aiProvider   = config.provider.label,
                    confidence   = result.confidence,
                    verdict      = result.verdict,   // "GO" or "NO-GO"
                    rrRatio      = result.rrRatio,
                    reason       = result.reason.take(200),
                ))
            }
        }

        result
    } }

    // ── Provider router ────────────────────────────────────────────────────────────

    private fun callProvider(config: AiProviderConfig, systemPrompt: String, prompt: String): String =
        when (config.provider) {
            AiProvider.OPENAI      -> callOpenAi(config, systemPrompt, prompt)
            AiProvider.GEMINI      -> callGemini(config, systemPrompt, prompt)
            AiProvider.OPENROUTER  -> callOpenRouter(config, systemPrompt, prompt)
            AiProvider.GROQ        -> callGroq(config, systemPrompt, prompt)
        }

    // ── Provider implementations ───────────────────────────────────────────────────

    private fun callOpenAi(config: AiProviderConfig, systemPrompt: String, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "temperature" to 0.2,
                "response_format" to mapOf("type" to "json_object"),
            )
        )
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(payload, response.code))
            return JsonParser.parseString(payload).asJsonObject
                .getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: error("Empty OpenAI response")
        }
    }

    private fun callGemini(config: AiProviderConfig, systemPrompt: String, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "systemInstruction" to mapOf(
                    "parts" to listOf(mapOf("text" to systemPrompt))
                ),
                "contents" to listOf(
                    mapOf("parts" to listOf(mapOf("text" to prompt)))
                ),
                "generationConfig" to mapOf(
                    "temperature" to 0.2,
                    "responseMimeType" to "application/json",
                ),
            )
        )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(payload, response.code))
            return JsonParser.parseString(payload).asJsonObject
                .getAsJsonArray("candidates")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")
                ?.firstOrNull()?.asJsonObject
                ?.get("text")?.asString
                ?: error("Empty Gemini response")
        }
    }

    private fun callOpenRouter(config: AiProviderConfig, systemPrompt: String, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "temperature" to 0.2,
                "response_format" to mapOf("type" to "json_object"),
            )
        )
        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .header("HTTP-Referer", "https://nseassist.app")
            .header("X-Title", "NSE Assist")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(payload, response.code))
            return JsonParser.parseString(payload).asJsonObject
                .getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: error("Empty OpenRouter response")
        }
    }

    private fun callGroq(config: AiProviderConfig, systemPrompt: String, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemPrompt),
                    mapOf("role" to "user", "content" to prompt),
                ),
                "temperature" to 0.2,
                "response_format" to mapOf("type" to "json_object"),
            )
        )
        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(readError(payload, response.code))
            return JsonParser.parseString(payload).asJsonObject
                .getAsJsonArray("choices")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("message")
                ?.get("content")?.asString
                ?: error("Empty Groq response")
        }
    }

    // ── Parsers ────────────────────────────────────────────────────────────────────

    private fun parseReport(rawJson: String, config: AiProviderConfig): AiAnalysisReport {
        val json = JsonParser.parseString(extractJson(rawJson)).asJsonObject
        val primary = json.getAsJsonObject("primary_pick") ?: error("Missing primary_pick in AI response")
        val additionalCandidates = json.getAsJsonArray("additional_candidates")?.mapNotNull { item ->
            runCatching { item.asJsonObject.toRecommendedStock() }.getOrNull()
        }.orEmpty()
        val allocations = json.getAsJsonArray("allocations")?.mapNotNull { item ->
            runCatching {
                val obj = item.asJsonObject
                AiStockAllocation(
                    symbol = obj.get("symbol")?.asString.orEmpty(),
                    amount = obj.get("amount")?.asDouble ?: 0.0,
                    note = obj.get("note")?.asString.orEmpty(),
                )
            }.getOrNull()
        }.orEmpty()

        val riskNotes = json.getAsJsonArray("risk_notes")?.mapNotNull { it.asString }.orEmpty()

        return AiAnalysisReport(
            provider = config.provider,
            model = config.model,
            summary = json.get("summary")?.asString ?: "No summary returned.",
            primaryPick = primary.toRecommendedStock(),
            additionalCandidates = additionalCandidates,
            allocations = allocations,
            riskNotes = riskNotes,
            finalVerdict = json.get("final_verdict")?.asString ?: "",
            disclaimer = json.get("disclaimer")?.asString ?: DEFAULT_DISCLAIMER,
        )
    }

    private fun parseSingleStockReport(rawJson: String, config: AiProviderConfig): SingleStockAiAnalysis {
        val json = JsonParser.parseString(extractJson(rawJson)).asJsonObject
        return SingleStockAiAnalysis(
            provider   = config.provider,
            model      = config.model,
            verdict    = json.get("verdict")?.asString    ?: "NO-GO",
            direction  = json.get("direction")?.asString  ?: "SKIP",
            entry      = json.get("entry")?.asString      ?: "—",
            target     = json.get("target")?.asString     ?: "—",
            stopLoss   = json.get("stop_loss")?.asString  ?: "—",
            quantity   = json.get("quantity")?.asInt      ?: 0,
            rrRatio    = json.get("rr_ratio")?.asString   ?: "—",
            confidence = json.get("confidence")?.asInt    ?: 0,
            reason     = json.get("reason")?.asString     ?: "No analysis provided.",
            risk       = json.get("risk")?.asString       ?: "No specific risk noted.",
            disclaimer = json.get("disclaimer")?.asString ?: DEFAULT_DISCLAIMER,
        )
    }

    private fun JsonObject.toRecommendedStock(): AiRecommendedStock {
        return AiRecommendedStock(
            symbol = get("symbol")?.asString.orEmpty(),
            companyName = get("company_name")?.asString.orEmpty(),
            rationale = get("rationale")?.asString.orEmpty(),
            confidence = get("confidence")?.asInt ?: 0,
            direction = get("direction")?.asString?.toTradeDirection() ?: AiTradeDirection.NO_TRADE,
            setupType = get("setup_type")?.asString ?: "No Trade",
            entryView = get("entry_view")?.asString ?: "",
            stopLoss = get("stop_loss")?.asString ?: "",
            target = get("target")?.asString ?: "",
        )
    }

    private fun String.toTradeDirection(): AiTradeDirection = when (uppercase()) {
        "LONG"  -> AiTradeDirection.LONG
        "SHORT" -> AiTradeDirection.SHORT
        else    -> AiTradeDirection.NO_TRADE
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) error("AI response was not valid JSON")
        return text.substring(start, end + 1)
    }

    // ── Prompt builders ────────────────────────────────────────────────────────────

    private fun buildSingleStockPrompt(
        capital: Double,
        stock: StockData,
        news: NewsResult?,
        context: StockAiContext? = null,
        vix: Double = 0.0,
        isShortMode: Boolean = false,
    ): String {
        val qty     = if (stock.ltp > 0) kotlin.math.floor(capital / stock.ltp).toInt() else 0
        val maxLoss = capital * 0.02

        // ── IST time + market status ──────────────────────────────────────────────
        val ist     = ZoneId.of("Asia/Kolkata")
        val now     = ZonedDateTime.now(ist)
        val timeStr = now.format(DateTimeFormatter.ofPattern("hh:mm a"))   // e.g. 10:35 AM
        val dateStr = now.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy"))
        val dow     = now.dayOfWeek.value                                  // 1=Mon … 7=Sun
        val mins    = now.hour * 60 + now.minute
        val marketStatus = when {
            dow >= 6                  -> "CLOSED — Weekend (NSE is shut)"
            mins in 540..554          -> "PRE-OPEN (9:00–9:15 AM IST — orders accepted, no execution)"
            mins in 555..929          -> "LIVE ✅ (9:15 AM–3:30 PM IST — market is open right now)"
            mins in 930..960          -> "POST-MARKET (3:30–4:00 PM IST — closing session)"
            else                      -> "CLOSED — Outside market hours"
        }
        val sessionNote = when {
            dow >= 6                  -> "This analysis is for the NEXT trading session (Monday opening)."
            mins < 555                -> "Market opens at 9:15 AM IST. This analysis is for TODAY's opening."
            mins in 555..929          -> "Market is LIVE. Entry prices and signals are actionable RIGHT NOW."
            else                      -> "Market is closed for today. This analysis applies to TOMORROW's session."
        }

        return buildString {
            // ── Short mode banner — injected first so AI reads it before any other data ──
            if (isShortMode) {
                appendLine("=== ⚠ SHORT SCAN MODE — READ THIS FIRST ===")
                appendLine("The user is looking for stocks to SHORT (SELL MIS on Kite — bet that price will fall).")
                appendLine("A BEARISH stock is DESIRED here — falling price, negative momentum, low score = GOOD for shorting.")
                appendLine("Your job: find a clear SELL setup. If the stock shows strong bearish signals, verdict=GO + direction=SELL.")
                appendLine("If the stock is too bullish or the signals are mixed/unclear, verdict=NO-GO + direction=SKIP.")
                appendLine("Rules for SHORT GO: bearish technical signals, price falling/below VWAP, negative change%, Supertrend SELL or EMA bearish structure.")
                appendLine("Stop loss for short = price ABOVE entry (if price rises above stop, the short is failing — exit).")
                appendLine("Target for short = price BELOW entry (you profit when price falls to target).")
                appendLine("===========================================")
                appendLine()
            }

            appendLine("=== SINGLE STOCK INTRADAY ANALYSIS ===")
            appendLine()
            appendLine("--- TIME & MARKET STATUS ---")
            appendLine("Current Time (IST) : $timeStr  |  $dateStr")
            appendLine("NSE Market Status  : $marketStatus")
            appendLine("Session Context    : $sessionNote")
            if (vix > 0.0) {
                val vixLabel = when {
                    vix >= 20.0 -> "DANGER — market too volatile, tighten stops severely"
                    vix >= 17.0 -> "HIGH — caution, tighten stops by 20%"
                    vix >= 13.0 -> "NORMAL — trade as planned"
                    else        -> "LOW — very calm, ideal conditions"
                }
                appendLine("India VIX          : ${"%.2f".format(vix)}  [$vixLabel]")
            }
            appendLine()
            appendLine("Capital Available : Rs ${"%.2f".format(capital)}")
            appendLine("Max Loss (2% rule): Rs ${"%.2f".format(maxLoss)}")
            appendLine("Max Qty at LTP    : $qty shares")
            appendLine()

            appendLine("--- IDENTITY ---")
            appendLine("Symbol   : ${stock.symbol}")
            appendLine("Company  : ${stock.name}")
            appendLine("Sector   : ${stock.sector.ifBlank { "Unknown" }}")
            appendLine("Industry : ${stock.industry.ifBlank { "Unknown" }}")
            appendLine()

            appendLine("--- PRICE DATA ---")
            appendLine("LTP      : Rs ${"%.2f".format(stock.ltp)}")
            appendLine("Change   : ${"%.2f".format(stock.change)} (${"%.2f".format(stock.changePct)}%)")
            appendLine("Open     : Rs ${"%.2f".format(stock.open)}")
            appendLine("Day High : Rs ${"%.2f".format(stock.dayHigh)}")
            appendLine("Day Low  : Rs ${"%.2f".format(stock.dayLow)}")
            appendLine("VWAP     : Rs ${"%.2f".format(stock.vwap)}")
            appendLine("Above VWAP: ${stock.aboveVwap}")
            appendLine("Gap Type : ${stock.gapType}")
            appendLine()

            appendLine("--- VOLUME ---")
            appendLine("Volume Today   : ${stock.volume}")
            appendLine("Avg Volume 30d : ${stock.avgVolume}")
            appendLine("Volume Spike   : ${stock.volumeSpike}  (spike = 1.5x+ average)")
            appendLine()

            appendLine("--- TECHNICAL INDICATORS ---")
            appendLine("RSI (14)     : ${"%.1f".format(stock.rsi)}  (sweet spot 45-65, >70 overbought, <30 oversold)")
            appendLine("EMA 20       : Rs ${"%.2f".format(stock.ema20)}  [Price ${if (stock.ltp > stock.ema20) "ABOVE" else "BELOW"}]")
            appendLine("EMA 50       : Rs ${"%.2f".format(stock.ema50)}  [EMA20 ${if (stock.ema20 > stock.ema50) "ABOVE → BULLISH STRUCTURE" else "BELOW → BEARISH STRUCTURE"}]")
            appendLine("MACD Line    : ${"%.4f".format(stock.macdLine)}")
            appendLine("MACD Signal  : ${"%.4f".format(stock.macdSignal)}")
            appendLine("MACD Status  : ${if (stock.macdLine > stock.macdSignal) "BULLISH (MACD > Signal)" else "BEARISH (MACD < Signal)"}")
            if (stock.bollingerUpper > 0.0) {
                val bbPos = when {
                    stock.ltp > stock.bollingerUpper -> "ABOVE_UPPER — overbought risk"
                    stock.ltp < stock.bollingerLower -> "BELOW_LOWER — oversold / bounce candidate"
                    stock.ltp > stock.bollingerMiddle -> "ABOVE_MID — mild bullish"
                    else -> "BELOW_MID — mild bearish"
                }
                appendLine("Bollinger Upper : Rs ${"%.2f".format(stock.bollingerUpper)}")
                appendLine("Bollinger Mid   : Rs ${"%.2f".format(stock.bollingerMiddle)}")
                appendLine("Bollinger Lower : Rs ${"%.2f".format(stock.bollingerLower)}")
                appendLine("Bollinger Pos   : $bbPos")
            }
            if (stock.adx > 0.0) {
                val adxStr = when {
                    stock.adx >= 40.0 -> "STRONG (>=40)"
                    stock.adx >= 25.0 -> "TRENDING (>=25)"
                    else              -> "WEAK / CHOPPY (<25)"
                }
                appendLine("ADX             : ${"%.1f".format(stock.adx)}  [$adxStr]")
                appendLine("DI+             : ${"%.1f".format(stock.adxDiPlus)}")
                appendLine("DI-             : ${"%.1f".format(stock.adxDiMinus)}")
                appendLine("ADX Direction   : ${if (stock.adxDiPlus > stock.adxDiMinus) "BULLISH (DI+ > DI-)" else "BEARISH (DI- > DI+)"}")
            }
            appendLine()

            appendLine("--- CANDLESTICK PATTERN ---")
            appendLine("Pattern : ${stock.candlePattern}")
            if (stock.candlePattern != "NONE") {
                appendLine("Signal  : ${stock.candleSignal}")
                if (stock.candlePatternLabel.isNotBlank()) appendLine("Meaning : ${stock.candlePatternLabel}")
            }
            appendLine()

            appendLine("--- TREND ANALYSIS ---")
            appendLine("6-Month Trend  : ${stock.trend6Month}")
            appendLine("2-Week Trend   : ${stock.trend2Week}")
            appendLine("Trend Signal   : ${stock.trendSignalType}")
            if (stock.trendSignalLabel.isNotBlank()) appendLine("Signal Detail  : ${stock.trendSignalLabel}")
            appendLine("Streak Length  : ${stock.streakDays} days")
            appendLine()

            appendLine("--- ML PRICE PREDICTION ---")
            appendLine("Direction            : ${stock.predictedDirection}")
            appendLine("Predicted High (day) : Rs ${"%.2f".format(stock.predictedHigh)}")
            appendLine("Predicted Low (day)  : Rs ${"%.2f".format(stock.predictedLow)}")
            appendLine("Prediction Confidence: ${stock.predictionConfidence}%")
            appendLine("ATR (14d avg range)  : Rs ${"%.2f".format(stock.atr)}")
            appendLine()

            appendLine("--- SUPPORT & RESISTANCE (20d) ---")
            appendLine("Support    : Rs ${"%.2f".format(stock.support)}")
            appendLine("Resistance : Rs ${"%.2f".format(stock.resistance)}")
            if (stock.resistance > 0) {
                val gapToRes = (stock.resistance - stock.ltp) / stock.resistance * 100
                appendLine("Gap to Resistance : ${"%.1f".format(gapToRes)}%")
            }
            appendLine()

            appendLine("--- SCORING ---")
            appendLine("Technical Score : ${"%.0f".format(stock.score)}/100")
            appendLine("System Signal   : ${stock.optionAction}")
            appendLine()

            appendLine("--- 15-MIN INTRADAY ANALYSIS ---")
            appendLine("Session Phase   : ${stock.sessionPhase}")
            if (stock.orbHigh > 0.0) {
                appendLine("ORB High (9:15–9:45 AM) : Rs ${"%.2f".format(stock.orbHigh)}")
                appendLine("ORB Low  (9:15–9:45 AM) : Rs ${"%.2f".format(stock.orbLow)}")
                val orbStatus = when {
                    stock.ltp > stock.orbHigh -> "ABOVE ORB — bullish breakout confirmed"
                    stock.ltp < stock.orbLow  -> "BELOW ORB — bearish breakdown confirmed"
                    else                      -> "INSIDE ORB — wait for breakout direction"
                }
                appendLine("Price vs ORB    : $orbStatus")
            } else {
                appendLine("ORB             : Not available (market may be closed or data unavailable)")
            }
            appendLine("15-min Pattern  : ${stock.thirtyMinPattern}")
            if (stock.thirtyMinPattern != "NONE") {
                appendLine("Pattern Signal  : ${stock.thirtyMinSignal}")
                if (stock.thirtyMinPatternLabel.isNotBlank()) appendLine("Pattern Meaning : ${stock.thirtyMinPatternLabel}")
            }
            appendLine("Supertrend (15m): ${stock.supertrendSignal}  (BUY=price above band/bullish, SELL=price below band/bearish)")
            if (stock.pivotCpp > 0.0) {
                appendLine("Pivot CPP       : Rs ${"%.2f".format(stock.pivotCpp)}  (daily neutral level)")
                appendLine("Pivot R1        : Rs ${"%.2f".format(stock.pivotR1)}  (first resistance / initial target)")
                if (stock.pivotR2 > 0.0) appendLine("Pivot R2        : Rs ${"%.2f".format(stock.pivotR2)}  (extended target if R1 broken)")
                appendLine("Pivot S1        : Rs ${"%.2f".format(stock.pivotS1)}  (first support / stop zone)")
                if (stock.pivotS2 > 0.0) appendLine("Pivot S2        : Rs ${"%.2f".format(stock.pivotS2)}  (strong support if S1 broken)")
                val pivotStatus = when {
                    stock.ltp > stock.pivotR1  -> "Above R1 — strong bullish momentum, target R2"
                    stock.ltp < stock.pivotS1  -> "Below S1 — weak/bearish zone, watch S2"
                    stock.ltp > stock.pivotCpp -> "Above CPP — mild bullish bias, target R1"
                    else                       -> "Below CPP — mild bearish bias, watch S1"
                }
                appendLine("Price vs Pivot  : $pivotStatus")
            }
            stock.quickTake?.let { qt ->
                appendLine("Quick Take      : ${qt.action}")
                appendLine("15-min Summary  : ${qt.thirtyMinSummary}")
                appendLine("Quick Confidence: ${qt.confidence}/100")
            }
            appendLine()

            // Historical performance context — injected when sufficient data exists
            if (context != null && context.hasData && context.signalCount >= 3) {
                appendLine("--- HISTORICAL PERFORMANCE CONTEXT (${stock.symbol}, last 30 days) ---")
                appendLine("Signal Occurrences : ${context.signalCount}  (signals that fired)")
                appendLine("Win Rate           : ${"%.0f".format(context.signalWinRate * 100)}%  (${context.winCount}/${context.signalCount} hit target)")
                if (context.bestSession.isNotBlank())
                    appendLine("Best Session Phase : ${context.bestSession}  (highest historical win rate)")
                if (context.predictionCount >= 5)
                    appendLine("Prediction Accuracy: ${"%.0f".format(context.predictionAccuracy * 100)}%  (direction correct out of ${context.predictionCount} days)")
                if (context.recentTrades.isNotEmpty()) {
                    appendLine("Recent AI Calls    :")
                    context.recentTrades.forEach { t ->
                        appendLine("  ${t.verdict} ${t.direction} confidence=${t.confidence} → outcome=${t.outcome}")
                    }
                }
                appendLine("NOTE: Adjust your confidence to be consistent with the historical win rate above.")
                if (context.signalWinRate < 0.45)
                    appendLine("WARNING: Historical win rate is below 45% for this stock — apply extra caution.")
                appendLine()
            }

            news?.let { n ->
                appendLine("--- LIVE NEWS (Last 24h) ---")
                appendLine("Overall Sentiment : ${n.sentiment.name}")
                appendLine("Stock Sentiment   : ${n.stockSentiment.name} (${n.stockArticleCount} articles)")
                appendLine("Sector Sentiment  : ${n.sectorSentiment.name} (${n.sectorArticleCount} articles)")
                appendLine("Latest Headline   : ${n.headline}")
                appendLine("News Age          : ${n.ageText}")
                if (n.articles.isNotEmpty()) {
                    appendLine("Top Headlines:")
                    n.articles.take(3).forEach { a ->
                        appendLine("  • [${a.sentiment.name}] ${a.headline}  —  ${a.source} (${a.ageText})")
                    }
                }
            } ?: appendLine("--- NEWS: Not yet loaded ---")
        }.trimEnd()
    }

    private fun buildBatchPrompt(
        capital: Double,
        category: ScanCategory,
        stocks: List<StockData>,
        contextMap: Map<String, StockAiContext> = emptyMap(),
        vix: Double = 0.0,
    ): String {
        val stockLines = stocks.joinToString("\n") { stock ->
            buildString {
                append("- ${stock.symbol}")
                append(" | name=${stock.name}")
                append(" | ltp=${"%.2f".format(stock.ltp)}")
                append(" | changePct=${"%.2f".format(stock.changePct)}")
                append(" | score=${"%.0f".format(stock.score)}")
                append(" | optionAction=${stock.optionAction}")
                append(" | signal=${stock.trendSignalLabel.ifBlank { stock.trendSignalType }}")
                append(" | predictedDirection=${stock.predictedDirection}")
                append(" | predictionConfidence=${stock.predictionConfidence}")
                append(" | aboveVwap=${stock.aboveVwap}")
                append(" | volumeSpike=${stock.volumeSpike}")
                append(" | gap=${stock.gapType}")
                append(" | rsi=${"%.1f".format(stock.rsi)}")
                append(" | ema20=${"%.2f".format(stock.ema20)}")
                append(" | ema50=${"%.2f".format(stock.ema50)}")
                append(" | macdBullish=${stock.macdLine > stock.macdSignal}")
                append(" | trend6M=${stock.trend6Month}")
                append(" | trend2W=${stock.trend2Week}")
                if (stock.bollingerUpper > 0.0) {
                    val bbPos = when {
                        stock.ltp > stock.bollingerUpper -> "ABOVE_UPPER"
                        stock.ltp < stock.bollingerLower -> "BELOW_LOWER"
                        stock.ltp > stock.bollingerMiddle -> "ABOVE_MID"
                        else -> "BELOW_MID"
                    }
                    append(" | bollingerPos=$bbPos")
                }
                if (stock.adx > 0.0) {
                    append(" | adx=${"%.1f".format(stock.adx)}")
                    append(" | adxTrend=${if (stock.adxDiPlus > stock.adxDiMinus) "BULLISH" else "BEARISH"}")
                    append(" | adxStrength=${when { stock.adx >= 40.0 -> "STRONG"; stock.adx >= 25.0 -> "TRENDING"; else -> "WEAK" }}")
                }
                if (stock.candlePattern != "NONE") {
                    append(" | candlePattern=${stock.candlePattern}")
                    append(" | candleSignal=${stock.candleSignal}")
                }
                if (stock.newsImpactScore != 0) {
                    append(" | newsImpact=${stock.newsImpactScore}")
                }
                stock.news?.let { news ->
                    if (news.stockSentiment.name != "NONE") append(" | stockNews=${news.stockSentiment.name}")
                    if (news.sectorSentiment.name != "NONE") append(" | sectorNews=${news.sectorSentiment.name}")
                }
                // 15-min intraday signals — most time-sensitive, highest priority for same-session entries
                if (stock.thirtyMinPattern != "NONE") {
                    append(" | intraday15m=${stock.thirtyMinPattern}(${stock.thirtyMinSignal})")
                }
                if (stock.supertrendSignal != "NEUTRAL") {
                    append(" | supertrend15m=${stock.supertrendSignal}")
                }
                if (stock.orbHigh > 0.0) {
                    val orbStatus = when {
                        stock.ltp > stock.orbHigh -> "ABOVE_ORB"
                        stock.ltp < stock.orbLow  -> "BELOW_ORB"
                        else                      -> "INSIDE_ORB"
                    }
                    append(" | orbStatus=$orbStatus")
                }
                if (stock.pivotCpp > 0.0) {
                    val pivotStatus = when {
                        stock.ltp > stock.pivotR1  -> "ABOVE_R1"
                        stock.ltp < stock.pivotS1  -> "BELOW_S1"
                        stock.ltp > stock.pivotCpp -> "ABOVE_CPP"
                        else                       -> "BELOW_CPP"
                    }
                    append(" | pivotPos=$pivotStatus")
                }
                if (stock.sessionPhase.isNotBlank() && stock.sessionPhase != "UNKNOWN") {
                    append(" | session=${stock.sessionPhase}")
                }
                append(" | support=${"%.2f".format(stock.support)}")
                append(" | resistance=${"%.2f".format(stock.resistance)}")
                append(" | atr=${"%.2f".format(stock.atr)}")
                append(" | sector=${stock.sector.ifBlank { "Unknown" }}")
                append(" | maxQty=${if (stock.ltp > 0) kotlin.math.floor(capital / stock.ltp).toInt() else 0}")
            }
        }
        // Inject historical context for top candidates (only if data exists)
        val contextBlock = buildString {
            val richContext = contextMap.values.filter { it.hasData && it.signalCount >= 3 }
            if (richContext.isNotEmpty()) {
                appendLine()
                appendLine("--- HISTORICAL PERFORMANCE CONTEXT (last 30 days) ---")
                richContext.forEach { ctx ->
                    append("${ctx.symbol}: signals=${ctx.signalCount}")
                    append(", winRate=${"%.0f".format(ctx.signalWinRate * 100)}%")
                    if (ctx.bestSession.isNotBlank()) append(", bestSession=${ctx.bestSession}")
                    if (ctx.predictionCount >= 5) append(", predictionAccuracy=${"%.0f".format(ctx.predictionAccuracy * 100)}%")
                    if (ctx.recentTrades.isNotEmpty()) {
                        val recent = ctx.recentTrades.take(3)
                        append(", recentTrades=[${recent.joinToString { "${it.verdict}(${it.outcome})" }}]")
                    }
                    appendLine()
                }
                appendLine("Use this historical data to calibrate your confidence. Higher win rates validate current signals; low win rates should reduce confidence.")
            }
        }

        val vixLine = if (vix > 0.0) {
            val vixLabel = when {
                vix >= 20.0 -> "DANGER — recommend skipping or cutting positions; market is in high fear mode"
                vix >= 17.0 -> "HIGH — caution, tighten all stop losses by 20%"
                vix >= 13.0 -> "NORMAL — trade as planned"
                else        -> "LOW — very calm, ideal conditions"
            }
            "India VIX: ${"%.2f".format(vix)}  [$vixLabel]"
        } else ""

        return """
            Capital: Rs ${"%.2f".format(capital)}
            Category: ${category.label}
            ${if (vixLine.isNotBlank()) vixLine else ""}
            Objective: Predict the strongest next intraday setups from the supplied list. Provide 1 strong primary pick and up to 2 additional candidates. The best answer can be LONG, SHORT, reversal, continuation, or NO_TRADE setups.
            Rules:
            - Choose only affordable stocks with ltp less than or equal to capital.
            - A stock currently in loss can still be selected if its reversal or short-continuation setup is stronger.
            - Prefer prediction quality over blunt momentum following.
            - Primary pick is the main trade. Additional candidates are for watching, not simultaneous trading.
            - Total picks including primary must not exceed 3.
            - If India VIX >= 20, strongly prefer NO_TRADE or reduce confidence on all picks by at least 15 points.

            Stocks:
            $stockLines
            $contextBlock
        """.trimIndent()
    }

    private fun readError(payload: String, code: Int): String {
        val message = runCatching {
            JsonParser.parseString(payload).asJsonObject
                .getAsJsonObject("error")
                ?.get("message")?.asString
        }.getOrNull()
        return message ?: "Request failed with HTTP $code"
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val DEFAULT_DISCLAIMER = "AI suggestions are informational only and not financial advice."

        // ── Batch analysis system prompt ──────────────────────────────────────────
        private const val SYSTEM_PROMPT = """
You are an intraday stock analysis assistant. Use only the stock list provided by the user. Never invent stocks, prices, or signals.

Return only valid JSON in this exact shape:
{
  "summary": "short paragraph",
  "primary_pick": {
    "symbol": "ticker",
    "company_name": "name",
    "direction": "LONG",
    "setup_type": "Bullish Reversal",
    "rationale": "why this is the strongest intraday setup",
    "entry_view": "how to approach entry",
    "stop_loss": "stop loss text",
    "target": "target text",
    "confidence": 0
  },
  "additional_candidates": [
    {
      "symbol": "ticker",
      "company_name": "name",
      "direction": "SHORT",
      "setup_type": "Bearish Continuation",
      "rationale": "why this is worth watching",
      "entry_view": "how to approach entry",
      "stop_loss": "stop loss text",
      "target": "target text",
      "confidence": 0
    }
  ],
  "allocations": [
    {
      "symbol": "ticker",
      "amount": 0,
      "note": "how much capital to allocate and why"
    }
  ],
  "risk_notes": ["note 1", "note 2"],
  "final_verdict": "Trade primary pick / No trade / Watch additional candidates",
  "disclaimer": "AI suggestions are informational only and not financial advice."
}

Signal interpretation guide:
- rsi: 45-65 = healthy zone, >70 = overbought, <30 = oversold
- bollingerPos: ABOVE_UPPER = overbought risk, BELOW_LOWER = oversold/bounce candidate, ABOVE_MID = mild bullish
- adxStrength: STRONG(>=40) or TRENDING(>=25) = trending market, WEAK = choppy
- adxTrend: BULLISH = DI+ above DI-, BEARISH = DI- above DI+
- candlePattern + candleSignal: BULLISH patterns (HAMMER, BULLISH_ENGULFING, MORNING_STAR) favor long, BEARISH patterns favor short
- newsImpact: positive = supportive news, negative = adverse news in last 24h
- stockNews/sectorNews: POSITIVE, NEGATIVE, NEUTRAL, NONE
- trend6M: Bullish/Bearish/Range-Bound over 6 months
- trend2W: Trending Up/Down/Consolidating over 2 weeks
- support/resistance: key price levels for stop loss and target planning
- atr: average daily price range, useful for realistic stop loss sizing
- maxQty: maximum shares affordable within capital
- intraday15m: 15-min candle pattern + signal (most time-sensitive — prioritize for same-session entries; BULLISH patterns favor long, BEARISH favor short/avoid)
- supertrend15m: BUY = price above Supertrend band (bullish momentum on 15-min), SELL = below band (bearish)
- orbStatus: ABOVE_ORB = bullish breakout above 9:15-9:45 AM opening range, BELOW_ORB = bearish breakdown, INSIDE_ORB = consolidating, wait for direction
- pivotPos: ABOVE_R1 = strong momentum (target R2), ABOVE_CPP = mild bullish (target R1), BELOW_CPP = mild bearish (watch S1), BELOW_S1 = weak/avoid long (watch S2)
- session: MORNING = strongest momentum (best for entries), MIDDAY = slower/choppy (tighten targets), AFTERNOON = late session (ride trends only), CLOSED = next session analysis
- India VIX: <13 = very calm ideal, 13-17 = normal, 17-20 = high caution (tighten stops), >=20 = danger (prefer NO_TRADE, reduce confidence)

Rules:
- Pick only from the provided list.
- Keep all allocation amounts within the user's total capital.
- Provide 1 strong primary pick.
- Provide 0 to 2 additional candidates in additional_candidates array.
- Total picks must not exceed 3 including primary.
- additional_candidates are for watching, not simultaneous trading.
- The best setup may be LONG, SHORT, reversal, continuation, or NO_TRADE.
- Do not assume only positive movers are good trades.
- A negative stock can be a valid pick if the predicted next move is compelling.
- Never choose a stock whose price is above the user's capital.
- Use all available signals holistically — not just one indicator.
- Candle patterns, Bollinger position, ADX strength, and news together should inform quality of setup.
- Confidence must be an integer from 0 to 100.
- Keep rationale, entry_view, stop_loss, and target in plain English a beginner can understand. No jargon like "pullback to support", "resistance breakout confirmation", or "EMA structure". Instead say: "buy when price dips to around Rs X", "your profit target is Rs X", "if price falls below Rs X, exit immediately".
        """

        // ── Single-stock system prompt — strict GO / NO-GO verdict ────────────────
        private const val SINGLE_STOCK_SYSTEM_PROMPT = """
You are a strict intraday trading advisor for a beginner NSE trader with limited capital.
Analyze ALL data provided and return a precise, honest, GO or NO-GO verdict for today's session.

NON-NEGOTIABLE RULES:
- The prompt includes "Current Time (IST)" and "NSE Market Status". You MUST read these carefully.
- If market is LIVE: signals and entry prices are actionable right now. Say so in your reason.
- If market is CLOSED or WEEKEND: your analysis is for the NEXT session. Clearly state this in your reason — e.g. "Market is closed. This plan is for tomorrow's opening." Do NOT say "trade now."
- If market is PRE-OPEN (9:00–9:15 AM): advise watching the opening range before entering.
- The prompt includes a "15-MIN INTRADAY ANALYSIS" section. These are the MOST TIME-SENSITIVE signals — always analyze them first when market is LIVE.
  - Supertrend (15m) BUY = strong bullish momentum on 15-min timeframe. SELL = avoid buying, potential short.
  - ORB (Opening Range 9:15–9:45 AM): ABOVE ORB = confirmed bullish breakout. BELOW ORB = confirmed bearish breakdown. INSIDE ORB = wait.
  - 15-min candle pattern: treat the same as daily patterns but with higher intraday relevance. MORNING_STAR/BULLISH_ENGULFING = buy. EVENING_STAR/BEARISH_ENGULFING = avoid long.
  - Pivot levels: CPP is the neutral point. Above R1 = strong, Below S1 = weak.
  - Quick Take confidence reflects all 15-min signals combined and is session-adjusted.
- If 15-min signals contradict daily signals, the 15-min signals take priority for intraday decisions.
- verdict must be "GO" or "NO-GO" only.
- "GO" requires ALL of: confidence >= 65 AND R:R >= 1.5 AND quantity >= 1 AND stock LTP <= capital.
- "NO-GO" if any condition fails, or if setup is unclear, choppy, or risky for a beginner.
- direction must be "BUY", "SELL", or "SKIP". Use SKIP only for NO-GO verdicts.
- entry must be a specific narrow price range like "Rs 847 - Rs 852". Never vague.
- target and stop_loss must include percentage like "Rs 875 (+3.3%)" and "Rs 835 (-1.4%)".
- quantity = floor(capital / entry_midpoint), then reduced so max_loss = quantity * (entry - stop_loss) <= 2% of capital.
- rr_ratio must be "1 : X.X" calculated as (target - entry) / (entry - stop_loss). Must be >= 1.5 for GO.
- confidence is 0-100 based on ALL signals holistically. Not just one indicator.
- reason is EXACTLY 2 lines maximum. Write in plain simple English a beginner can understand — no trading jargon. Say what the stock is doing and why it looks like a good trade.
- risk is exactly 1 sentence. Write in plain English. Say exactly what price level would mean the trade is not working and what to do.
- For NO-GO: still provide entry/target/stop_loss as "N/A", quantity as 0, rr_ratio as "N/A".
- Use ONLY the data provided. Never invent prices, levels, or signals not in the input.
- PLAIN ENGLISH RULE: Write reason and risk as if explaining to someone who has never traded before. No terms like "VWAP", "EMA structure", "RSI overbought", "pullback to support", "institutional participation". Instead say things like: "the stock is above today's average price", "trading volume is 2x higher than usual", "the stock has been rising for 2 days", "if price falls below Rs X, exit immediately".

Return ONLY valid JSON — no markdown, no explanation, no extra text:
{
  "verdict": "GO",
  "direction": "BUY",
  "entry": "Rs 847 - Rs 852",
  "target": "Rs 875 (+3.3%)",
  "stop_loss": "Rs 835 (-1.4%)",
  "quantity": 12,
  "rr_ratio": "1 : 2.4",
  "confidence": 78,
  "reason": "The stock is above today's average price and has been rising steadily for 2 days. Today's trading volume is 2x higher than usual, meaning big buyers are active.",
  "risk": "If price falls below Rs 835, exit immediately — that means the trade is no longer working.",
  "disclaimer": "AI suggestions are informational only and not SEBI-registered financial advice."
}
        """
    }
}
