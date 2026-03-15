package com.nseassist.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.analysis.StockScorer
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.ScanCategory
import com.nseassist.data.model.StockData
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.ExportState
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState
import com.nseassist.util.ScanExportUtil

private enum class ScanSortOption(val label: String) {
    SCORE("Score"),
    CHANGE_PCT("Change %"),
    PRICE_LOW("Price ↑"),
    PRICE_HIGH("Price ↓"),
    VOLUME_SPIKE("Volume Spike"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    navController: NavController,
    capital: Double,
    category: ScanCategory,
    vm: MainViewModel,
) {
    val context = LocalContext.current
    val aiSettings by vm.aiSettings.collectAsState()
    val scanState by vm.scanResults.collectAsState()
    val exportState by vm.exportState.collectAsState()
    val deepEnrichProgress by vm.deepEnrichProgress.collectAsState()
    val scorer = remember { StockScorer() }
    var currentPage by remember(category, capital) { mutableIntStateOf(0) }
    var searchQuery by remember(category, capital) { mutableStateOf("") }
    var sortOption by remember { mutableStateOf(ScanSortOption.SCORE) }
    var showProviderPicker by remember { mutableStateOf(false) }

    LaunchedEffect(capital, category) { vm.scanStocks(capital, category) }

    AppGradientBackground {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${category.label} Stocks  ·  ₹${String.format("%,.0f", capital)}") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        when (val state = scanState) {
            is UiState.Loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(color = BluePrimary)
                    Text("Scanning NSE market…", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    Text("Phase 1 — Fetching all liquid stocks from Yahoo Finance", color = TextSecondary, fontSize = 12.sp)
                    Text("Phase 2 — Full RSI · EMA · MACD · ADX analysis on top picks", color = TextSecondary, fontSize = 12.sp)
                    Text("Takes ~5–10 seconds to show results", color = TextSecondary, fontSize = 11.sp)
                }
            }
            is UiState.Error -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text("⚠️", fontSize = 40.sp)
                    Text(state.message, color = RedBear, fontSize = 13.sp)
                    Button(
                        onClick = { vm.scanStocks(capital, category) },
                        colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                    ) {
                        Text("Retry", fontWeight = FontWeight.Bold)
                    }
                }
            }
            is UiState.Success -> {
                val stocks = state.data
                val filteredStocks = stocks
                    .filter { stock ->
                        val query = searchQuery.trim()
                        query.isBlank() ||
                            stock.symbol.contains(query, ignoreCase = true) ||
                            stock.name.contains(query, ignoreCase = true)
                    }
                    .let { list ->
                        when (sortOption) {
                            ScanSortOption.SCORE -> list.sortedByDescending { it.score }
                            ScanSortOption.CHANGE_PCT -> list.sortedByDescending { it.changePct }
                            ScanSortOption.PRICE_LOW -> list.sortedBy { it.ltp }
                            ScanSortOption.PRICE_HIGH -> list.sortedByDescending { it.ltp }
                            ScanSortOption.VOLUME_SPIKE -> list.sortedByDescending { if (it.volumeSpike) 1 else 0 }
                        }
                    }
                val pageSize = 10
                val pageCount = maxOf(1, (filteredStocks.size + pageSize - 1) / pageSize)
                val safePage = currentPage.coerceIn(0, pageCount - 1)
                if (safePage != currentPage) currentPage = safePage
                val startIndex = safePage * pageSize
                val endIndex = minOf(startIndex + pageSize, filteredStocks.size)
                val pageStocks = filteredStocks.subList(startIndex, endIndex)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        Text(
                            "${stocks.size} ${category.label.lowercase()} stocks within ₹${String.format("%,.0f", capital)} — tap for deep analysis",
                            color = TextSecondary, fontSize = 12.sp,
                        )
                    }
                    // Phase 2 deep-enrich progress banner — shown while top 15 are being analysed
                    deepEnrichProgress?.let { (done, total) ->
                        item {
                            DeepEnrichBanner(done = done, total = total)
                        }
                    }
                    item {
                        AnalyzeWithAiCard(
                            providers = aiSettings.configuredProviders(),
                            exportState = exportState,
                            onAnalyze = { showProviderPicker = true },
                            onExport = {
                                vm.prepareDeepExport(capital, category, stocks) { enrichedStocks ->
                                    ScanExportUtil.shareScanResults(context, capital, category, enrichedStocks)
                                }
                            },
                        )
                    }
                    item {
                        ScanSearchField(
                            query = searchQuery,
                            onQueryChange = {
                                searchQuery = it
                                currentPage = 0
                            },
                        )
                    }
                    item {
                        ScanSortBar(
                            selected = sortOption,
                            onSelect = {
                                sortOption = it
                                currentPage = 0
                            },
                        )
                    }
                    item {
                        PaginationHeader(
                            currentPage = safePage,
                            pageCount = pageCount,
                            totalCount = filteredStocks.size,
                            onPrevious = { currentPage = (safePage - 1).coerceAtLeast(0) },
                            onNext = { currentPage = (safePage + 1).coerceAtMost(pageCount - 1) },
                        )
                    }
                    if (filteredStocks.isEmpty()) {
                        item {
                            Text(
                                text = "No stocks match \"${searchQuery.trim()}\".",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    items(pageStocks) { stock ->
                        ScanStockRow(stock, scorer) {
                            navController.navigate("stock/${stock.symbol}")
                        }
                    }
                }

                if (showProviderPicker) {
                    ProviderPickerDialog(
                        providers = aiSettings.configuredProviders(),
                        onDismiss = { showProviderPicker = false },
                        onProviderSelected = { providerConfig ->
                            showProviderPicker = false
                            navController.navigate("ai-report")
                            vm.prepareDeepAiAnalysis(providerConfig.provider, capital, category, stocks)
                        },
                    )
                }
            }
        }
    }
    } // AppGradientBackground
}

@Composable
private fun DeepEnrichBanner(done: Int, total: Int) {
    val progress = if (total > 0) done.toFloat() / total else 0f
    val isComplete = done >= total
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = BluePrimary.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isComplete) "✅  Full analysis complete — scores updated"
                    else "🔍  Deep analysing top $total picks…",
                    color = BluePrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "$done / $total",
                    color = TextSecondary,
                    fontSize = 11.sp,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = BluePrimary,
                trackColor = BluePrimary.copy(alpha = 0.15f),
            )
            Text(
                "RSI · EMA · MACD · Bollinger · ADX · Candles being calculated",
                color = TextSecondary,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
private fun AnalyzeWithAiCard(
    providers: List<AiProviderConfig>,
    exportState: ExportState,
    onAnalyze: () -> Unit,
    onExport: () -> Unit,
) {
    val isPreparing = exportState is ExportState.Preparing
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("AI Analysis", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("You can either analyze in-app or export the current result set for manual upload.", color = TextSecondary, fontSize = 12.sp)
            if (providers.isEmpty()) {
                Text("Add at least one provider API key in Settings to enable AI analysis.", color = TextSecondary, fontSize = 12.sp)
            } else {
                Text(
                    "Analyze the current result set with ${providers.joinToString { it.provider.label }}.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Button(
                    onClick = onAnalyze,
                    enabled = !isPreparing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                ) {
                    Text("Analyze with AI")
                }
            }
            if (isPreparing) {
                val state = exportState as ExportState.Preparing
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Preparing deep analysis for top 20 stocks… (${state.done}/${state.total})",
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    LinearProgressIndicator(
                        progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                        modifier = Modifier.fillMaxWidth(),
                        color = BluePrimary,
                    )
                }
            } else {
                OutlinedButton(
                    onClick = onExport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Export for Manual AI Upload")
                }
                Text("Top 20 stocks will be deep-analysed before export.", color = TextSecondary, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ProviderPickerDialog(
    providers: List<AiProviderConfig>,
    onDismiss: () -> Unit,
    onProviderSelected: (AiProviderConfig) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose AI Provider") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                providers.forEach { config ->
                    OutlinedButton(
                        onClick = { onProviderSelected(config) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("${config.provider.label} · ${config.model}")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanSortBar(selected: ScanSortOption, onSelect: (ScanSortOption) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Sort by", color = TextSecondary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanSortOption.values().forEach { option ->
                FilterChip(
                    selected = selected == option,
                    onClick = { onSelect(option) },
                    label = { Text(option.label, fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
private fun ScanSearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Search results") },
        placeholder = { Text("Symbol or company name") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            imeAction = ImeAction.Search,
        ),
    )
}

@Composable
private fun PaginationHeader(
    currentPage: Int,
    pageCount: Int,
    totalCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    if (totalCount <= 10) {
        Text("Showing all $totalCount stocks", color = TextSecondary, fontSize = 12.sp)
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Page ${currentPage + 1} of $pageCount",
            color = TextSecondary,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPrevious, enabled = currentPage > 0) {
                Text("Previous")
            }
            OutlinedButton(onClick = onNext, enabled = currentPage < pageCount - 1) {
                Text("Next")
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
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            // Left accent bar — colour-coded by score
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(scoreColor),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Symbol + signal label + gap badge
                Column(modifier = Modifier.weight(1f)) {
                    Text(stock.symbol, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stock.trendSignalLabel.take(28), color = TextSecondary, fontSize = 11.sp)
                        if (stock.gapType != "FLAT") {
                            val gapColor = if (stock.gapType == "GAP UP") GreenBull else RedBear
                            Surface(shape = RoundedCornerShape(4.dp), color = gapColor.copy(alpha = 0.13f)) {
                                Text(stock.gapType, color = gapColor, fontSize = 9.sp,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                            }
                        }
                    }
                }

                // Price + change
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Text("₹${String.format("%,.2f", stock.ltp)}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text(String.format("%+.2f%%", stock.changePct), color = priceColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Score badge
                Surface(shape = RoundedCornerShape(8.dp), color = scoreColor.copy(alpha = 0.13f)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(String.format("%.0f", stock.score), color = scoreColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(scorer.signalLabel(stock.score), color = scoreColor, fontSize = 9.sp)
                    }
                }
            }
        }
    }
}
