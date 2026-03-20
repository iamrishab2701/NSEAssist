package com.nseassist.data.model

enum class AiProvider(val routeValue: String, val label: String, val defaultModel: String) {
    OPENAI("openai", "OpenAI", "gpt-4o-mini"),
    GEMINI("gemini", "Gemini", "gemini-2.5-flash"),
    OPENROUTER("openrouter", "OpenRouter", "openai/gpt-oss-20b:free"),
    GROQ("groq", "Groq", "llama-3.1-8b-instant"),
    CLAUDE("claude", "Claude", "claude-sonnet-4-6");

    companion object {
        fun fromRouteValue(value: String?): AiProvider? =
            values().firstOrNull { it.routeValue.equals(value, ignoreCase = true) }
    }
}

data class AiProviderConfig(
    val provider: AiProvider,
    val apiKey: String = "",
    val model: String = provider.defaultModel,
)

data class AiSettings(
    val providers: List<AiProviderConfig> = AiProvider.values().map { AiProviderConfig(provider = it) },
    val primaryProvider: AiProvider? = null,
) {
    fun configuredProviders(): List<AiProviderConfig> = providers.filter { it.apiKey.isNotBlank() }
    fun primaryConfig(): AiProviderConfig? =
        providers.firstOrNull { it.provider == primaryProvider && it.apiKey.isNotBlank() }
}

data class AiStockAllocation(
    val symbol: String,
    val amount: Double,
    val note: String,
)

enum class AiTradeDirection {
    LONG,
    SHORT,
    NO_TRADE,
}

data class AiRecommendedStock(
    val symbol: String,
    val companyName: String,
    val rationale: String,
    val confidence: Int,
    val direction: AiTradeDirection = AiTradeDirection.NO_TRADE,
    val setupType: String = "No Trade",
    val entryView: String = "",
    val stopLoss: String = "",
    val target: String = "",
)

data class AiAnalysisReport(
    val provider: AiProvider,
    val model: String,
    val summary: String,
    val primaryPick: AiRecommendedStock,
    val additionalCandidates: List<AiRecommendedStock> = emptyList(),
    val allocations: List<AiStockAllocation> = emptyList(),
    val riskNotes: List<String> = emptyList(),
    val finalVerdict: String = "",
    val disclaimer: String,
)

/** Result of a single-stock AI deep-dive from the Stock Detail screen. */
data class SingleStockAiAnalysis(
    val provider: AiProvider,
    val model: String,
    val verdict: String,        // "GO" or "NO-GO"
    val direction: String,      // "BUY" / "SELL" / "SKIP"
    val entry: String,          // e.g. "₹847 – ₹852"
    val target: String,         // e.g. "₹875 (+3.3%)"
    val stopLoss: String,       // e.g. "₹835 (-1.4%)"
    val quantity: Int,          // shares affordable within capital
    val rrRatio: String,        // e.g. "1 : 2.4"
    val confidence: Int,        // 0–100
    val reason: String,         // max 2 lines — only technical facts
    val risk: String,           // 1 specific thing to watch
    val disclaimer: String,
)
