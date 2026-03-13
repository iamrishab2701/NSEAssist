package com.nseassist.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.nseassist.analysis.StockScorer
import com.nseassist.data.model.StockData
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(navController: NavController, capital: Double, vm: MainViewModel = viewModel()) {
    val scanState by vm.scanResults.collectAsState()
    val scorer = remember { StockScorer() }

    LaunchedEffect(capital) { vm.scanStocks(capital) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Affordable Stocks  ·  ₹${String.format("%,.0f", capital)}") },
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
        when (val state = scanState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = BluePrimary)
                    Text("Scanning NSE universe…", color = TextSecondary)
                }
            }
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Error: ${state.message}", color = RedBear)
            }
            is UiState.Success -> {
                val stocks = state.data
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "${stocks.size} stocks within ₹${String.format("%,.0f", capital)} — tap for deep analysis",
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                    items(stocks) { stock ->
                        ScanStockRow(stock, scorer) {
                            navController.navigate("stock/${stock.symbol}")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanStockRow(stock: StockData, scorer: StockScorer, onClick: () -> Unit) {
    val isUp = stock.changePct >= 0
    val priceColor = if (isUp) GreenBull else RedBear
    val scoreColor = when {
        stock.score >= 70 -> GreenBull
        stock.score >= 50 -> AmberWarn
        else -> RedBear
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Symbol + Signal label
            Column(modifier = Modifier.weight(1f)) {
                Text(stock.symbol, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                Text(stock.trendSignalLabel.take(35), color = TextSecondary, fontSize = 11.sp)
            }

            // Price + change
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text("₹${String.format("%,.2f", stock.ltp)}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                Text(String.format("%+.2f%%", stock.changePct), color = priceColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // Score badge
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = scoreColor.copy(alpha = 0.15f),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(String.format("%.0f", stock.score), color = scoreColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(scorer.signalLabel(stock.score), color = scoreColor, fontSize = 9.sp)
                }
            }
        }
    }
}
