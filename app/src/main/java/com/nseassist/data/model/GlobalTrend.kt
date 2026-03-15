package com.nseassist.data.model

data class GlobalTrendsData(
    val us: List<GlobalIndexItem>,
    val asia: List<GlobalIndexItem>,
    val india: List<GlobalIndexItem>,
    val commodities: List<GlobalIndexItem>,
    val forex: List<GlobalIndexItem>,
    val impacts: List<ImpactCard>,
    val overallBias: String,   // "BULLISH" | "BEARISH" | "NEUTRAL"
    val cachedAt: Long,
)

data class GlobalIndexItem(
    val symbol: String,
    val name: String,
    val price: Double,
    val changePct: Double,
    val direction: String,     // "UP" | "DOWN"
)

data class ImpactCard(
    val foreignSymbol: String,
    val foreignName: String,
    val foreignChangePct: Double,
    val direction: String,         // "UP" | "DOWN"
    val sector: String,
    val impactDirection: String,   // "POSITIVE" | "NEGATIVE"
    val indianStocks: List<ImpactedStock>,
    val newsHeadlines: List<String>,
)

data class ImpactedStock(
    val symbol: String,
    val nseSymbol: String,
    val name: String,
    val sector: String,
)
