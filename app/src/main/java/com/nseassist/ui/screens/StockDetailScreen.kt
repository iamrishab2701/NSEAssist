package com.nseassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nseassist.data.model.StockData
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(navController: NavController, symbol: String, vm: MainViewModel = viewModel()) {
    val detailState by vm.stockDetail.collectAsState()

    LaunchedEffect(symbol) { vm.loadStockDetail(symbol) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(symbol, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
            )
        },
        containerColor = SurfaceDark,
    ) { padding ->
        when (val state = detailState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = BluePrimary)
                    Text("Running deep analysis…", color = TextSecondary)
                    Text("RSI · EMA · MACD · Prediction", color = TextSecondary, fontSize = 12.sp)
                }
            }
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Error: ${state.message}", color = RedBear)
            }
            is UiState.Success -> StockDetailContent(state.data, Modifier.padding(padding))
        }
    }
}

@Composable
private fun StockDetailContent(stock: StockData, modifier: Modifier) {
    val isUp = stock.changePct >= 0
    val priceColor = if (isUp) GreenBull else RedBear

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Price header
        Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("₹${String.format("%,.2f", stock.ltp)}", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(String.format("%+.2f%%", stock.changePct), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = priceColor)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    InfoChip("H ₹${fmt(stock.dayHigh)}", GreenBull)
                    InfoChip("L ₹${fmt(stock.dayLow)}", RedBear)
                    InfoChip(if (stock.aboveVwap) "▲ VWAP" else "▼ VWAP", if (stock.aboveVwap) GreenBull else RedBear)
                    InfoChip(stock.gapType, AmberWarn)
                }
            }
        }

        // Score + Trend Signal
        Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val scoreColor = when {
                    stock.score >= 70 -> GreenBull; stock.score >= 50 -> AmberWarn; else -> RedBear
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("AI Score", fontWeight = FontWeight.Bold)
                    Text("${String.format("%.0f", stock.score)}/100", color = scoreColor, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                Text("${stock.optionAction}  ·  ${stock.trendSignalType}", color = scoreColor, fontSize = 13.sp)
                if (stock.trendSignalLabel.isNotBlank()) {
                    Text(stock.trendSignalLabel, color = TextSecondary, fontSize = 12.sp)
                }
            }
        }

        // Technical Indicators
        SectionCard("Technical Indicators") {
            IndicatorRow("RSI (14)", String.format("%.1f", stock.rsi), rsiColor(stock.rsi), rsiLabel(stock.rsi))
            IndicatorRow("EMA 20", "₹${fmt(stock.ema20)}", if (stock.ltp > stock.ema20) GreenBull else RedBear,
                if (stock.ltp > stock.ema20) "Price above" else "Price below")
            IndicatorRow("EMA 50", "₹${fmt(stock.ema50)}", if (stock.ema20 > stock.ema50) GreenBull else RedBear,
                if (stock.ema20 > stock.ema50) "Bullish structure" else "Bearish structure")
            IndicatorRow("MACD", String.format("%.2f", stock.macdLine),
                if (stock.macdLine > stock.macdSignal) GreenBull else RedBear,
                if (stock.macdLine > stock.macdSignal) "Bullish" else "Bearish")
            IndicatorRow("Volume", if (stock.volumeSpike) "🔥 ${fmtVol(stock.volume)}" else fmtVol(stock.volume),
                if (stock.volumeSpike) AmberWarn else TextSecondary,
                if (stock.volumeSpike) "Volume spike!" else "Normal")
        }

        // Trend History
        SectionCard("Trend History") {
            LabelRow("6-Month", stock.trend6Month, trendColor(stock.trend6Month))
            LabelRow("2-Week", stock.trend2Week, trendColor(stock.trend2Week))
            LabelRow("Streak", "${stock.streakDays}d ${if (stock.trendSignalType.contains("UP") || stock.trendSignalType.contains("BUY")) "UP" else if (stock.trendSignalType.contains("DN") || stock.trendSignalType.contains("SELL")) "DOWN" else ""}", TextSecondary)
        }

        // Price Prediction
        SectionCard("Price Prediction") {
            IndicatorRow("Direction", stock.predictedDirection, predColor(stock.predictedDirection), "${stock.predictionConfidence}% confidence")
            IndicatorRow("Predicted High", "₹${fmt(stock.predictedHigh)}", GreenBull, "")
            IndicatorRow("Predicted Low", "₹${fmt(stock.predictedLow)}", RedBear, "")
            IndicatorRow("ATR (14d)", "₹${fmt(stock.atr)}", TextSecondary, "Avg daily range")
        }

        // Support & Resistance
        SectionCard("Support & Resistance") {
            IndicatorRow("Support", "₹${fmt(stock.support)}", GreenBull, "20-day low")
            IndicatorRow("Resistance", "₹${fmt(stock.resistance)}", RedBear, "20-day high")
        }

        // Signal Checklist
        SectionCard("Signal Checklist") {
            CheckRow("Price above VWAP", stock.aboveVwap)
            CheckRow("RSI in sweet spot (45–65)", stock.rsi in 45.0..65.0)
            CheckRow("Volume spike (1.5x+)", stock.volumeSpike)
            CheckRow("Above EMA 20", stock.ltp > stock.ema20)
            CheckRow("EMA 20 > EMA 50", stock.ema20 > stock.ema50)
            CheckRow("MACD bullish", stock.macdLine > stock.macdSignal)
            CheckRow("6-Month bullish structure", stock.trend6Month == "Bullish")
        }

        Spacer(Modifier.height(32.dp))
        Text(
            "⚠ Educational analysis only — not SEBI-registered advice.\nAll trading decisions are solely your responsibility.",
            color = TextSecondary, fontSize = 11.sp,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Divider(color = DividerColor, thickness = 0.5.dp)
            content()
        }
    }
}

@Composable
private fun IndicatorRow(label: String, value: String, color: Color, sublabel: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, color = TextSecondary, fontSize = 12.sp)
            if (sublabel.isNotBlank()) Text(sublabel, color = TextSecondary, fontSize = 10.sp)
        }
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun LabelRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
    }
}

@Composable
private fun CheckRow(label: String, passes: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (passes) "✅" else "❌", fontSize = 13.sp)
        Text(label, color = if (passes) TextPrimary else TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun InfoChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = color.copy(alpha = 0.15f)) {
        Text(text, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
    }
}

// Helpers
private fun fmt(v: Double) = String.format("%,.2f", v)
private fun fmtVol(v: Long): String = when {
    v >= 10_000_000 -> "${String.format("%.1f", v / 10_000_000.0)} Cr"
    v >= 100_000 -> "${String.format("%.1f", v / 100_000.0)} L"
    else -> v.toString()
}

private fun rsiColor(rsi: Double) = when {
    rsi in 45.0..65.0 -> GreenBull; rsi > 70 -> RedBear; rsi < 30 -> AmberWarn; else -> TextSecondary
}

private fun rsiLabel(rsi: Double) = when {
    rsi > 70 -> "Overbought"; rsi < 30 -> "Oversold"; rsi in 45.0..65.0 -> "Sweet Spot"; else -> "Neutral"
}

private fun trendColor(t: String) = when (t) {
    "Bullish", "Trending Up" -> GreenBull; "Bearish", "Trending Down" -> RedBear; else -> AmberWarn
}

private fun predColor(d: String) = when (d) { "Up" -> GreenBull; "Down" -> RedBear; else -> AmberWarn }
