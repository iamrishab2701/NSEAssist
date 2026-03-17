package com.nseassist.data.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.nseassist.data.model.PaperTradeEntry
import com.nseassist.data.model.PaperTradeRequest
import com.nseassist.data.model.PredictionLogRequest
import com.nseassist.data.model.RecentTradeEntry
import com.nseassist.data.model.SignalOutcomeRequest
import com.nseassist.data.model.StockAiContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object MemoryClient {

    private const val TAG = "MemoryClient"
    private const val WORKER_URL = "https://nseassist-proxy.rishabsingh.workers.dev"
    private val JSON = "application/json".toMediaType()
    private val gson = Gson()

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun logPaperTrade(trade: PaperTradeRequest): Int? = withContext(Dispatchers.IO) {
        runCatching {
            val body = gson.toJson(
                mapOf(
                    "symbol"         to trade.symbol,
                    "company_name"   to trade.companyName,
                    "direction"      to trade.direction,
                    "entry_price"    to trade.entryPrice,
                    "target_text"    to trade.targetText,
                    "stop_loss_text" to trade.stopLossText,
                    "quantity"       to trade.quantity,
                    "session_phase"  to trade.sessionPhase,
                    "ai_provider"    to trade.aiProvider,
                    "confidence"     to trade.confidence,
                    "verdict"        to trade.verdict,
                    "rr_ratio"       to trade.rrRatio,
                    "reason"         to trade.reason,
                )
            )
            val request = Request.Builder()
                .url("$WORKER_URL/memory/trade")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                    .get("id")?.asInt
            }
        }.getOrNull().also { if (it == null) Log.w(TAG, "logPaperTrade failed") }
    }

    suspend fun getTrades(symbol: String? = null): List<PaperTradeEntry> = withContext(Dispatchers.IO) {
        runCatching {
            val url = if (symbol != null) "$WORKER_URL/memory/trades?symbol=$symbol"
                      else "$WORKER_URL/memory/trades"
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching emptyList()
                val json = JsonParser.parseString(response.body?.string() ?: "{}").asJsonObject
                val trades = json.getAsJsonArray("trades") ?: return@runCatching emptyList()
                trades.map { el ->
                    val o = el.asJsonObject
                    PaperTradeEntry(
                        id           = o.get("id")?.asInt ?: 0,
                        symbol       = o.get("symbol")?.asString ?: "",
                        companyName  = o.get("company_name")?.asString ?: "",
                        direction    = o.get("direction")?.asString ?: "",
                        entryPrice   = o.get("entry_price")?.asDouble ?: 0.0,
                        targetText   = o.get("target_text")?.asString ?: "",
                        stopLossText = o.get("stop_loss_text")?.asString ?: "",
                        quantity     = o.get("quantity")?.asInt ?: 0,
                        sessionPhase = o.get("session_phase")?.asString ?: "",
                        aiProvider   = o.get("ai_provider")?.asString ?: "",
                        confidence   = o.get("confidence")?.asInt ?: 0,
                        verdict      = o.get("verdict")?.asString ?: "",
                        rrRatio      = o.get("rr_ratio")?.asString ?: "",
                        reason       = o.get("reason")?.asString ?: "",
                        loggedAt     = o.get("logged_at")?.asLong ?: 0L,
                        outcome      = o.get("outcome")?.asString ?: "OPEN",
                        outcomePrice = o.get("outcome_price")?.takeIf { !it.isJsonNull }?.asDouble,
                    )
                }
            }
        }.onSuccess { list -> Log.d(TAG, "getTrades returned ${list.size} entries") }
         .getOrElse { e -> Log.w(TAG, "getTrades failed: ${e.message}", e); emptyList() }
    }

    suspend fun updateTradeOutcome(id: Int, outcome: String, outcomePrice: Double?) =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = gson.toJson(
                    mapOf("id" to id, "outcome" to outcome, "outcome_price" to outcomePrice)
                )
                val request = Request.Builder()
                    .url("$WORKER_URL/memory/trade-outcome")
                    .method("PATCH", body.toRequestBody(JSON))
                    .build()
                client.newCall(request).execute().use { }
            }.onFailure { Log.w(TAG, "updateTradeOutcome failed: ${it.message}") }
        }

    suspend fun logSignalOutcomes(outcomes: List<SignalOutcomeRequest>) = withContext(Dispatchers.IO) {
        if (outcomes.isEmpty()) return@withContext
        runCatching {
            val body = gson.toJson(
                outcomes.map { s ->
                    mapOf(
                        "symbol"               to s.symbol,
                        "signal_date"          to s.signalDate,
                        "rsi"                  to s.rsi,
                        "above_vwap"           to s.aboveVwap,
                        "macd_bullish"         to s.macdBullish,
                        "trend_signal"         to s.trendSignal,
                        "session_phase"        to s.sessionPhase,
                        "market_condition"     to s.marketCondition,
                        "prediction_direction" to s.predictionDirection,
                        "score"                to s.score,
                        "outcome"              to s.outcome,
                    )
                }
            )
            val request = Request.Builder()
                .url("$WORKER_URL/memory/signal")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { }
        }.onFailure { Log.w(TAG, "logSignalOutcomes failed: ${it.message}") }
    }

    suspend fun logPrediction(req: PredictionLogRequest) = withContext(Dispatchers.IO) {
        runCatching {
            val body = gson.toJson(
                mapOf(
                    "symbol"               to req.symbol,
                    "prediction_direction" to req.predictionDirection,
                    "predicted_high"       to req.predictedHigh,
                    "predicted_low"        to req.predictedLow,
                    "confidence"           to req.confidence,
                    "actual_close"         to req.actualClose,
                )
            )
            val request = Request.Builder()
                .url("$WORKER_URL/memory/prediction")
                .post(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { }
        }.onFailure { Log.w(TAG, "logPrediction failed: ${it.message}") }
    }

    suspend fun getAiContext(symbol: String): StockAiContext? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(4_000L) {
                runCatching {
                    val request = Request.Builder()
                        .url("$WORKER_URL/memory/ai-context?symbol=$symbol")
                        .get()
                        .build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@runCatching null
                        val json = JsonParser.parseString(
                            response.body?.string() ?: "{}"
                        ).asJsonObject
                        if (json.get("has_data")?.asBoolean != true) return@runCatching null
                        val tradesArr = json.getAsJsonArray("recent_trades")
                        val trades = tradesArr?.map { el ->
                            val o = el.asJsonObject
                            RecentTradeEntry(
                                verdict    = o.get("verdict")?.asString   ?: "",
                                direction  = o.get("direction")?.asString ?: "",
                                outcome    = o.get("outcome")?.asString   ?: "OPEN",
                                confidence = o.get("confidence")?.asInt   ?: 0,
                            )
                        } ?: emptyList()
                        StockAiContext(
                            symbol             = symbol,
                            hasData            = true,
                            signalWinRate      = json.get("signal_win_rate")?.asDouble     ?: 0.0,
                            signalCount        = json.get("signal_count")?.asInt           ?: 0,
                            winCount           = json.get("win_count")?.asInt              ?: 0,
                            bestSession        = json.get("best_session")?.asString        ?: "",
                            predictionAccuracy = json.get("prediction_accuracy")?.asDouble ?: 0.0,
                            predictionCount    = json.get("prediction_count")?.asInt       ?: 0,
                            recentTrades       = trades,
                        )
                    }
                }.getOrNull()
            }
        }

    suspend fun clearAllTrades() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$WORKER_URL/memory/clear-all")
                .delete()
                .build()
            client.newCall(request).execute().use { }
        }.onFailure { Log.w(TAG, "clearAllTrades failed: ${it.message}") }
    }

    suspend fun triggerCleanup() = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$WORKER_URL/memory/cleanup")
                .delete()
                .build()
            client.newCall(request).execute().use { }
        }.onFailure { Log.w(TAG, "triggerCleanup failed: ${it.message}") }
    }
}
