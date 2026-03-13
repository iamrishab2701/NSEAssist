package com.nseassist.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.MarketStatus
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(navController: NavController, vm: MainViewModel = viewModel()) {
    val marketState by vm.marketOverview.collectAsState()
    var capital by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NSE Assist", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.loadMarketOverview() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
            )
        },
        containerColor = SurfaceDark,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // Market Status Banner
            when (val state = marketState) {
                is UiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                is UiState.Success -> {
                    MarketStatusBanner(state.data.marketStatus)
                    MarketOverviewCard(state.data)
                    TopMoversCard(state.data)
                }
                is UiState.Error -> ErrorCard(state.message) { vm.loadMarketOverview() }
            }

            // Capital input + Scan button
            CapitalInputCard(
                capital = capital,
                onCapitalChange = { capital = it },
                onScan = {
                    val amt = capital.toDoubleOrNull() ?: 0.0
                    if (amt > 0) navController.navigate("scan/$amt")
                },
            )
        }
    }
}

@Composable
private fun MarketStatusBanner(status: MarketStatus) {
    val (label, color) = when (status) {
        MarketStatus.LIVE -> "🟢  MARKET LIVE — Real-time data" to GreenBull
        MarketStatus.PRE_OPEN -> "🟡  PRE-OPEN SESSION (9:00–9:15 AM IST)" to AmberWarn
        MarketStatus.POST_MARKET -> "🔴  MARKET CLOSED — Showing today's close" to RedBear
        MarketStatus.WEEKEND -> "⚪  WEEKEND — Market opens Monday 9:15 AM" to TextSecondary
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label, color = color, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(12.dp), fontSize = 13.sp,
        )
    }
}

@Composable
private fun MarketOverviewCard(data: MarketOverview) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Market Overview", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Divider(color = DividerColor)
            IndexRow("NIFTY 50", data.nifty50, data.nifty50ChangePct, data.niftyAboveVwap)
            IndexRow("BANK NIFTY", data.bankNifty, data.bankNiftyChangePct, data.bankNiftyAboveVwap)
        }
    }
}

@Composable
private fun IndexRow(label: String, price: Double, changePct: Double, aboveVwap: Boolean) {
    val color = if (changePct >= 0) GreenBull else RedBear
    val vwapTag = if (aboveVwap) "▲ VWAP" else "▼ VWAP"
    val vwapColor = if (aboveVwap) GreenBull else RedBear
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text("₹${String.format("%,.1f", price)}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
        Text(String.format("%+.2f%%", changePct), color = color, fontWeight = FontWeight.Bold)
        Text(vwapTag, color = vwapColor, fontSize = 11.sp)
    }
}

@Composable
private fun TopMoversCard(data: MarketOverview) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row {
                Text("Top Gainers", color = GreenBull, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("Top Losers", color = RedBear, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            }
            Divider(color = DividerColor)
            val maxRows = maxOf(data.topGainers.size, data.topLosers.size)
            repeat(maxRows) { i ->
                Row {
                    val g = data.topGainers.getOrNull(i)
                    val l = data.topLosers.getOrNull(i)
                    MoverItem(g?.symbol ?: "", g?.changePct ?: 0.0, GreenBull, Modifier.weight(1f))
                    MoverItem(l?.symbol ?: "", l?.changePct ?: 0.0, RedBear, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun MoverItem(symbol: String, pct: Double, color: Color, modifier: Modifier) {
    if (symbol.isBlank()) { Box(modifier); return }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(
            if (pct >= 0) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
            contentDescription = null, tint = color, modifier = Modifier.size(14.dp),
        )
        Text(symbol, color = TextPrimary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(String.format("%+.1f%%", pct), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CapitalInputCard(capital: String, onCapitalChange: (String) -> Unit, onScan: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Today's Capital", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Only stocks priced ≤ your capital will be scanned.", color = TextSecondary, fontSize = 12.sp)
            OutlinedTextField(
                value = capital,
                onValueChange = onCapitalChange,
                label = { Text("Capital (₹)") },
                prefix = { Text("₹") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                keyboardActions = KeyboardActions(onDone = { onScan() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onScan,
                enabled = (capital.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
            ) {
                Text("Scan Affordable Stocks", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = RedBearBg), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Error: $message", color = RedBear)
            TextButton(onClick = onRetry) { Text("Retry", color = BluePrimary) }
        }
    }
}
