package com.nseassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nseassist.data.model.GlobalIndexItem
import com.nseassist.data.model.GlobalTrendsData
import com.nseassist.data.model.ImpactCard
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.UiState

@Composable
fun MarketTrendTab(
    trendsState: UiState<GlobalTrendsData>,
    onRetry: () -> Unit,
) {
    when (trendsState) {
        is UiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is UiState.Error   -> TrendsErrorCard(trendsState.message, onRetry)
        is UiState.Success -> GlobalTrendsContent(trendsState.data, onRetry)
    }
}

@Composable
private fun TrendsErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Could not load global trends", color = RedBear, fontWeight = FontWeight.Bold)
            Text(message, color = TextSecondary, fontSize = 13.sp)
            Button(onClick = onRetry) { Text("Retry") }
        }
    }
}

@Composable
private fun GlobalTrendsContent(data: GlobalTrendsData, onRefresh: () -> Unit) {
    // Overall Bias Banner
    OverallBiasBanner(data.overallBias)

    // US Markets
    if (data.us.isNotEmpty()) {
        IndexSection(title = "US Markets", items = data.us)
    }

    // Asian Markets
    if (data.asia.isNotEmpty()) {
        IndexSection(title = "Asian Markets", items = data.asia)
    }

    // India
    if (data.india.isNotEmpty()) {
        IndexSection(title = "India", items = data.india)
    }

    // Commodities & Forex
    if (data.commodities.isNotEmpty() || data.forex.isNotEmpty()) {
        IndexSection(title = "Commodities & Forex", items = data.commodities + data.forex)
    }

    // Impact Cards
    if (data.impacts.isNotEmpty()) {
        Text(
            "Global Impact on Indian Stocks",
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = TextPrimary,
            modifier = Modifier.padding(top = 4.dp),
        )
        data.impacts.forEach { card ->
            ImpactCardView(card)
        }
    } else {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "No significant sector impacts detected right now. Impact cards appear when major foreign movers have matching Indian stocks.",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun OverallBiasBanner(bias: String) {
    val (label, color) = when (bias) {
        "BULLISH" -> "Overall Bias: BULLISH" to GreenBull
        "BEARISH" -> "Overall Bias: BEARISH" to RedBear
        else      -> "Overall Bias: NEUTRAL"  to AmberWarn
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = if (bias == "BULLISH") Icons.Filled.TrendingUp else Icons.Filled.TrendingDown,
                contentDescription = null,
                tint = color,
            )
            Text(label, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable
private fun IndexSection(title: String, items: List<GlobalIndexItem>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
            HorizontalDivider(color = DividerColor)
            items.forEach { item ->
                IndexItemRow(item)
            }
        }
    }
}

@Composable
private fun IndexItemRow(item: GlobalIndexItem) {
    val isUp  = item.direction == "UP"
    val color = if (isUp) GreenBull else RedBear
    val sign  = if (isUp) "+" else ""

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.name, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 13.sp)
            if (item.price > 0) {
                Text(
                    formatPrice(item.symbol, item.price),
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (isUp) Icons.Filled.TrendingUp else Icons.Filled.TrendingDown,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(
                "$sign${"%.2f".format(item.changePct)}%",
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun ImpactCardView(card: ImpactCard) {
    val isPositive = card.impactDirection == "POSITIVE"
    val accentColor = if (isPositive) GreenBull else RedBear
    val sign        = if (card.foreignChangePct >= 0) "+" else ""

    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.30f)),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // Header: foreign stock + change
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        card.foreignName,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${card.foreignSymbol}  ·  ${card.sector}",
                        color = TextSecondary,
                        fontSize = 11.sp,
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        "$sign${"%.2f".format(card.foreignChangePct)}%",
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            // Impact label
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = accentColor.copy(alpha = 0.10f),
            ) {
                Text(
                    if (isPositive) "Likely POSITIVE impact on Indian ${card.sector} stocks"
                    else            "Likely NEGATIVE impact on Indian ${card.sector} stocks",
                    color = accentColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            // Indian stocks
            if (card.indianStocks.isNotEmpty()) {
                Text("Indian stocks to watch:", color = TextSecondary, fontSize = 11.sp)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(card.indianStocks) { stock ->
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = accentColor.copy(alpha = 0.12f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    stock.nseSymbol,
                                    color = accentColor,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                )
                                Text(
                                    stock.name.take(16),
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }

            // News headlines
            if (card.newsHeadlines.isNotEmpty()) {
                HorizontalDivider(color = DividerColor)
                Text("Related news:", color = TextSecondary, fontSize = 11.sp)
                card.newsHeadlines.forEach { headline ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("•", color = TextSecondary, fontSize = 11.sp)
                        Text(
                            headline,
                            color = TextSecondary,
                            fontSize = 11.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private fun formatPrice(symbol: String, price: Double): String = when {
    symbol.endsWith("=X")         -> "%.4f".format(price)         // forex
    symbol == "CL=F" || symbol == "GC=F" -> "$%.2f".format(price) // commodities
    price > 1000                  -> "%.0f".format(price)
    else                          -> "%.2f".format(price)
}
