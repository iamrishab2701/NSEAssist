package com.nseassist.data.ai

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nseassist.data.model.AiAnalysisReport
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiRecommendedStock
import com.nseassist.data.model.AiStockAllocation
import com.nseassist.data.model.AiTradeDirection
import com.nseassist.data.model.ScanCategory
import com.nseassist.data.model.StockData
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiAnalysisService {
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun analyzeStocks(
        config: AiProviderConfig,
        capital: Double,
        category: ScanCategory,
        stocks: List<StockData>,
    ): Result<AiAnalysisReport> = withContext(Dispatchers.IO) { runCatching {
        require(config.apiKey.isNotBlank()) { "API key missing for ${config.provider.label}" }
        require(stocks.isNotEmpty()) { "No stocks available for AI analysis" }

        val prompt = buildPrompt(capital, category, stocks)
        val rawResponse = when (config.provider) {
            AiProvider.OPENAI -> callOpenAi(config, prompt)
            AiProvider.GEMINI -> callGemini(config, prompt)
            AiProvider.OPENROUTER -> callOpenRouter(config, prompt)
            AiProvider.GROQ -> callGroq(config, prompt)
        }

        parseReport(rawResponse, config)
    } }

    private fun callOpenAi(config: AiProviderConfig, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM_PROMPT),
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

    private fun callGemini(config: AiProviderConfig, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "systemInstruction" to mapOf(
                    "parts" to listOf(mapOf("text" to SYSTEM_PROMPT))
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

    private fun callOpenRouter(config: AiProviderConfig, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM_PROMPT),
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

    private fun callGroq(config: AiProviderConfig, prompt: String): String {
        val body = gson.toJson(
            mapOf(
                "model" to config.model,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to SYSTEM_PROMPT),
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
        "LONG" -> AiTradeDirection.LONG
        "SHORT" -> AiTradeDirection.SHORT
        else -> AiTradeDirection.NO_TRADE
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start == -1 || end == -1 || end <= start) error("AI response was not valid JSON")
        return text.substring(start, end + 1)
    }

    private fun buildPrompt(capital: Double, category: ScanCategory, stocks: List<StockData>): String {
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
                append(" | support=${"%.2f".format(stock.support)}")
                append(" | resistance=${"%.2f".format(stock.resistance)}")
                append(" | atr=${"%.2f".format(stock.atr)}")
                append(" | sector=${stock.sector.ifBlank { "Unknown" }}")
                append(" | maxQty=${if (stock.ltp > 0) kotlin.math.floor(capital / stock.ltp).toInt() else 0}")
            }
        }
        return """
            Capital: Rs ${"%.2f".format(capital)}
            Category: ${category.label}
            Objective: Predict the strongest next intraday setups from the supplied list. Provide 1 strong primary pick and up to 2 additional candidates. The best answer can be LONG, SHORT, reversal, continuation, or NO_TRADE setups.
            Rules:
            - Choose only affordable stocks with ltp less than or equal to capital.
            - A stock currently in loss can still be selected if its reversal or short-continuation setup is stronger.
            - Prefer prediction quality over blunt momentum following.
            - Primary pick is the main trade. Additional candidates are for watching, not simultaneous trading.
            - Total picks including primary must not exceed 3.

            Stocks:
            $stockLines
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
- Keep rationale concise and beginner-friendly.
        """
    }
}
