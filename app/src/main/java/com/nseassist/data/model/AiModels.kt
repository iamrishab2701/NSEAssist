package com.nseassist.data.model

enum class AiProvider(val routeValue: String, val label: String, val defaultModel: String) {
    OPENAI("openai", "OpenAI", "gpt-4o-mini"),
    GEMINI("gemini", "Gemini", "gemini-2.5-flash"),
    OPENROUTER("openrouter", "OpenRouter", "openai/gpt-oss-20b:free"),
    GROQ("groq", "Groq", "llama-3.1-8b-instant");

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
) {
    fun configuredProviders(): List<AiProviderConfig> = providers.filter { it.apiKey.isNotBlank() }
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
