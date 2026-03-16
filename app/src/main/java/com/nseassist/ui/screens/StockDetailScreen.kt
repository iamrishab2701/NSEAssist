package com.nseassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.analysis.SignalReplay
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiSettings
import com.nseassist.data.model.NewsSentiment
import com.nseassist.data.model.NewsResult
import com.nseassist.data.model.PaperTradeRequest
import com.nseassist.data.model.QuickTake
import com.nseassist.data.model.SingleStockAiAnalysis
import com.nseassist.data.model.StockData
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StockDetailScreen(navController: NavController, symbol: String, vm: MainViewModel) {
    val detailState         by vm.stockDetail.collectAsState()
    val newsState           by vm.stockNews.collectAsState()
    val singleStockAnalysis by vm.singleStockAnalysis.collectAsState()
    val signalReplayState   by vm.signalReplay.collectAsState()
    val aiSettings          by vm.aiSettings.collectAsState()
    val capital             by vm.capital.collectAsState()

    var showAiSheet     by remember { mutableStateOf(false) }
    var showReplaySheet by remember { mutableStateOf(false) }

    LaunchedEffect(symbol) { vm.loadStockDetail(symbol) }

    // ── AI bottom sheet — rendered outside Scaffold so it overlays everything ────
    if (showAiSheet) {
        val successStock = (detailState as? UiState.Success)?.data
        if (successStock != null) {
            SingleStockAiSheet(
                stock          = successStock,
                initialCapital = capital,
                aiSettings     = aiSettings,
                newsState      = newsState,
                analysisState  = singleStockAnalysis,
                onAnalyze      = { provider, cap -> vm.analyzeCurrentStockWithAi(provider, cap) },
                onLogTrade     = { trade -> vm.logPaperTrade(trade) },
                onDismiss      = {
                    showAiSheet = false
                    vm.clearSingleStockAnalysis()
                },
            )
        }
    }

    // ── Signal Replay bottom sheet ────────────────────────────────────────────
    if (showReplaySheet) {
        SignalReplaySheet(
            state    = signalReplayState,
            onDismiss = {
                showReplaySheet = false
                vm.clearSignalReplay()
            },
        )
    }

    AppGradientBackground {
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
        containerColor = Color.Transparent,
        floatingActionButton = {
            if (detailState is UiState.Success) {
                val isAnalyzing = singleStockAnalysis is UiState.Loading
                FloatingActionButton(
                    onClick = { if (!isAnalyzing) showAiSheet = true },
                    containerColor = if (isAnalyzing) BluePrimary.copy(alpha = 0.65f) else BluePrimary,
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (isAnalyzing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("AI", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
        },
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
            is UiState.Success -> StockDetailContent(
                stock           = state.data,
                newsState       = newsState,
                modifier        = Modifier.padding(padding),
                onReplaySignals = {
                    showReplaySheet = true
                    vm.runSignalReplay(state.data)
                },
            )
        }
    }
    } // AppGradientBackground
}

// ── AI Bottom Sheet ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SingleStockAiSheet(
    stock: StockData,
    initialCapital: Double,
    aiSettings: AiSettings,
    newsState: UiState<NewsResult?>,             // used to warn user if news hasn't loaded yet
    analysisState: UiState<SingleStockAiAnalysis>?,
    onAnalyze: (AiProvider, Double) -> Unit,
    onLogTrade: (PaperTradeRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var capitalInput by remember(initialCapital) {
        mutableStateOf(if (initialCapital > 0) initialCapital.toLong().toString() else "")
    }
    val configuredProviders = aiSettings.configuredProviders()
    var selectedProvider by remember(configuredProviders) {
        mutableStateOf(configuredProviders.firstOrNull()?.provider)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetSurface,   // opaque — glass token was too transparent
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Header
            Text(
                "🤖  AI Analysis · ${stock.symbol}",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary,
            )
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

            when (analysisState) {
                // ── Input / Error state ───────────────────────────────────────────
                null, is UiState.Error -> {
                    if (analysisState is UiState.Error) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = RedBear.copy(alpha = 0.12f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "⚠  ${analysisState.message}",
                                color = RedBear,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }

                    if (configuredProviders.isEmpty()) {
                        Text(
                            "No AI provider configured.\nGo to Settings → Model Configuration to add an API key.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    } else {
                        // Capital input
                        OutlinedTextField(
                            value = capitalInput,
                            onValueChange = { capitalInput = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text("Your capital (₹)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )

                        // Provider selector — only shown when more than one is configured
                        if (configuredProviders.size > 1) {
                            Text("Provider", color = TextSecondary, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                configuredProviders.forEach { cfg ->
                                    FilterChip(
                                        selected = selectedProvider == cfg.provider,
                                        onClick = { selectedProvider = cfg.provider },
                                        label = { Text(cfg.provider.label, fontSize = 12.sp) },
                                    )
                                }
                            }
                        } else {
                            // Single provider — just show which one will be used
                            Text(
                                "Provider: ${configuredProviders.first().provider.label}  ·  ${configuredProviders.first().model}",
                                color = TextSecondary,
                                fontSize = 12.sp,
                            )
                        }

                        // News readiness indicator
                        when (newsState) {
                            is UiState.Loading -> Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = AmberWarn.copy(alpha = 0.10f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.5.dp,
                                        color = AmberWarn,
                                    )
                                    Text(
                                        "News still loading — analysis will proceed without it if you tap Analyze now.",
                                        color = AmberWarn,
                                        fontSize = 11.sp,
                                        lineHeight = 16.sp,
                                    )
                                }
                            }
                            is UiState.Success -> if (newsState.data != null) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = GreenBull.copy(alpha = 0.10f),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        "✅  News loaded — will be included in analysis.",
                                        color = GreenBull,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    )
                                }
                            }
                            else -> Unit  // error / idle — no banner, news simply won't be included
                        }

                        // Analyze button
                        val canAnalyze = capitalInput.isNotBlank() &&
                            (selectedProvider != null || configuredProviders.isNotEmpty())
                        Button(
                            onClick = {
                                val cap = capitalInput.toDoubleOrNull() ?: 0.0
                                val provider = selectedProvider ?: configuredProviders.first().provider
                                onAnalyze(provider, cap)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = canAnalyze,
                            colors = ButtonDefaults.buttonColors(containerColor = BluePrimary, contentColor = Color.White),
                        ) {
                            Text("Analyze ${stock.symbol}")
                        }

                        Text(
                            "Uses all technical indicators, ML prediction, and live news already loaded on this screen.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                        )
                    }
                }

                // ── Loading ───────────────────────────────────────────────────────
                is UiState.Loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CircularProgressIndicator(color = BluePrimary)
                            Text("Analyzing ${stock.symbol}…", color = TextSecondary, fontSize = 13.sp)
                            Text("This takes 5 – 20 seconds", color = TextSecondary, fontSize = 11.sp)
                        }
                    }
                }

                // ── Result ────────────────────────────────────────────────────────
                is UiState.Success -> {
                    AiSingleStockResult(result = analysisState.data, stock = stock, onLogTrade = onLogTrade)
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}

// ── Single-stock result composable ────────────────────────────────────────────

@Composable
private fun AiSingleStockResult(
    result: SingleStockAiAnalysis,
    stock: StockData,
    onLogTrade: (PaperTradeRequest) -> Unit,
) {
    val isGo         = result.verdict == "GO"
    val verdictColor = if (isGo) GreenBull else RedBear
    val dirColor     = when (result.direction) {
        "BUY"  -> GreenBull
        "SELL" -> RedBear
        else   -> AmberWarn
    }

    // ── Verdict banner ────────────────────────────────────────────────────────
    Card(
        colors = CardDefaults.cardColors(containerColor = verdictColor.copy(alpha = 0.13f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "${if (isGo) "✅" else "❌"}  ${result.verdict}",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = verdictColor,
            )
            Text(
                result.direction,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = dirColor,
            )
            Spacer(Modifier.height(2.dp))
            LinearProgressIndicator(
                progress = { result.confidence / 100f },
                modifier = Modifier.fillMaxWidth(0.6f),
                color = verdictColor,
                trackColor = verdictColor.copy(alpha = 0.18f),
            )
            Text(
                "Confidence: ${result.confidence}/100",
                color = verdictColor.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
        }
    }

    // ── Trade levels ──────────────────────────────────────────────────────────
    Card(
        colors = CardDefaults.cardColors(containerColor = CardSolid),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AiResultRow("Entry",     result.entry,                                  TextPrimary)
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            AiResultRow("Target",   result.target,                                  GreenBull)
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            AiResultRow("Stop Loss", result.stopLoss,                               RedBear)
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            AiResultRow("Quantity",  "${result.quantity} share${if (result.quantity != 1) "s" else ""}", TextPrimary)
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
            AiResultRow("R:R Ratio", result.rrRatio,                               if (isGo) GreenBull else TextSecondary)
        }
    }

    // ── Reason ────────────────────────────────────────────────────────────────
    if (result.reason.isNotBlank()) {
        Card(
            colors = CardDefaults.cardColors(containerColor = CardSolid),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Why this verdict", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = TextSecondary)
                Text(result.reason, color = TextPrimary, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
    }

    // ── Risk ──────────────────────────────────────────────────────────────────
    if (result.risk.isNotBlank()) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AmberWarn.copy(alpha = 0.10f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("⚠", fontSize = 14.sp)
                Text(result.risk, color = AmberWarn, fontSize = 12.sp, lineHeight = 18.sp)
            }
        }
    }

    // ── Log Paper Trade (GO verdicts only) ───────────────────────────────────
    if (result.verdict == "GO" && result.direction != "SKIP") {
        var tradeLogged by remember { mutableStateOf(false) }
        Button(
            onClick = {
                if (!tradeLogged) {
                    onLogTrade(PaperTradeRequest(
                        symbol       = stock.symbol,
                        companyName  = stock.name,
                        direction    = result.direction,
                        entryPrice   = stock.ltp,
                        targetText   = result.target,
                        stopLossText = result.stopLoss,
                        quantity     = result.quantity,
                        sessionPhase = stock.sessionPhase,
                        aiProvider   = result.provider.label,
                        confidence   = result.confidence,
                        verdict      = result.verdict,
                        rrRatio      = result.rrRatio,
                        reason       = result.reason.take(200),
                    ))
                    tradeLogged = true
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (tradeLogged) GreenBull.copy(alpha = 0.5f) else GreenBull,
                contentColor = Color.White,
            ),
        ) {
            Text(if (tradeLogged) "Logged ✓" else "Log Paper Trade")
        }
    }

    // ── Meta ──────────────────────────────────────────────────────────────────
    Text(
        "by ${result.provider.label}  ·  ${result.model}",
        color = TextSecondary.copy(alpha = 0.6f),
        fontSize = 10.sp,
    )
    Text(
        result.disclaimer,
        color = TextSecondary.copy(alpha = 0.5f),
        fontSize = 10.sp,
        lineHeight = 15.sp,
    )
}

@Composable
private fun AiResultRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

// ── Signal Replay Bottom Sheet ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignalReplaySheet(
    state: UiState<SignalReplay.SignalReplayResult>?,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetSurface,
        dragHandle = { BottomSheetDefaults.DragHandle(color = TextSecondary.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "📊  Historical Signal Replay",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = TextPrimary,
            )
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

            when (state) {
                null, is UiState.Loading -> Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(color = BluePrimary)
                        Text("Replaying signals on historical data…", color = TextSecondary, fontSize = 13.sp)
                    }
                }
                is UiState.Error -> Text(state.message, color = RedBear, fontSize = 13.sp)
                is UiState.Success -> {
                    val r = state.data
                    if (r.totalSignals == 0) {
                        Text(
                            "Not enough historical signals found.\nNeed RSI 45–65 + above EMA20 + MACD bullish to fire at least once in the last 6 months.",
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                        )
                    } else {
                        val winPct = (r.winRate * 100).toInt()
                        // Win Rate banner
                        val winRateColor = when {
                            r.winRate >= 0.6 -> GreenBull
                            r.winRate >= 0.4 -> AmberWarn
                            else             -> RedBear
                        }
                        Card(
                            colors = CardDefaults.cardColors(containerColor = winRateColor.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$winPct%", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = winRateColor)
                                    Text("Historical Win Rate", color = TextSecondary, fontSize = 11.sp)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("${r.totalSignals}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Signals Fired", color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }

                        // Breakdown
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardDark),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Outcome Breakdown", fontWeight = FontWeight.SemiBold, color = TextPrimary, fontSize = 13.sp)
                                HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Win (Target Hit)", color = GreenBull, fontSize = 13.sp)
                                    Text("${r.wins}", color = GreenBull, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Loss (SL Hit)", color = RedBear, fontSize = 13.sp)
                                    Text("${r.losses}", color = RedBear, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("Neutral / Small Move", color = TextSecondary, fontSize = 13.sp)
                                    Text("${r.neutrals}", color = TextSecondary, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }

                        // Signal criteria note
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = BluePrimary.copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "Signal: RSI 45–65 + Price > EMA20 + MACD Bullish\nTarget: ATR × 1.2  ·  Stop: ATR × 0.6  ·  Based on daily candles",
                                color = BluePrimary.copy(alpha = 0.85f),
                                fontSize = 11.sp,
                                lineHeight = 17.sp,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }

                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary),
                    ) { Text("Close") }
                }
            }
        }
    }
}

// ── Stock detail content ───────────────────────────────────────────────────────

@Composable
private fun StockDetailContent(
    stock: StockData,
    newsState: UiState<NewsResult?>,
    modifier: Modifier,
    onReplaySignals: () -> Unit,
) {
    val isUp = stock.changePct >= 0
    val priceColor = if (isUp) GreenBull else RedBear

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Quick Take — plain English summary (shown only when 30-min data is available)
        stock.quickTake?.let { QuickTakeCard(it) }

        // Price header
        Card(colors = CardDefaults.cardColors(containerColor = CardDark)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (stock.name.isNotBlank() && stock.name != stock.symbol) {
                    Text(stock.name, color = TextSecondary, fontSize = 12.sp)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(
                        "₹${String.format("%,.2f", stock.ltp)}",
                        fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TextPrimary,
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            String.format("%+.2f%%", stock.changePct),
                            fontSize = 18.sp, fontWeight = FontWeight.Bold, color = priceColor,
                        )
                        Text(
                            String.format("%+.2f", stock.change),
                            color = priceColor, fontSize = 12.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Text("AI Score", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text("${String.format("%.0f", stock.score)}/100", color = scoreColor, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                }
                LinearProgressIndicator(
                    progress = { (stock.score / 100.0).toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = scoreColor,
                    trackColor = scoreColor.copy(alpha = 0.15f),
                )
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
            if (stock.bollingerUpper > 0.0) {
                val bbPos = when {
                    stock.ltp > stock.bollingerUpper -> "Above upper — overbought"
                    stock.ltp < stock.bollingerLower -> "Below lower — oversold"
                    stock.ltp > stock.bollingerMiddle -> "Above midline"
                    else -> "Below midline"
                }
                val bbColor = when {
                    stock.ltp > stock.bollingerUpper -> RedBear
                    stock.ltp < stock.bollingerLower -> AmberWarn
                    else -> GreenBull
                }
                IndicatorRow("Bollinger", "U:₹${fmt(stock.bollingerUpper)}", bbColor, bbPos)
            }
            if (stock.adx > 0.0) {
                val adxLabel = when {
                    stock.adx >= 40.0 -> "Strong trend"
                    stock.adx >= 25.0 -> "Trending"
                    else -> "Weak/Choppy"
                }
                val adxColor = if (stock.adxDiPlus > stock.adxDiMinus) GreenBull else RedBear
                IndicatorRow("ADX", String.format("%.1f", stock.adx), adxColor, adxLabel)
            }
        }

        // Candlestick Pattern
        if (stock.candlePattern != "NONE") {
            SectionCard("Candlestick Pattern") {
                val color = when (stock.candleSignal) {
                    "BULLISH" -> GreenBull
                    "BEARISH" -> RedBear
                    else -> AmberWarn
                }
                LabelRow(stock.candlePattern.replace("_", " "), stock.candleSignal, color)
                if (stock.candlePatternLabel.isNotBlank()) {
                    Text(stock.candlePatternLabel, color = TextSecondary, fontSize = 12.sp)
                }
            }
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

        // News — loaded independently so technical data shows instantly
        when (val ns = newsState) {
            is UiState.Loading -> SectionCard("📰 24h News Context") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = TextSecondary)
                    Text("Fetching latest news…", color = TextSecondary, fontSize = 12.sp)
                }
            }
            is UiState.Success -> NewsCard(ns.data)
            else               -> NewsCard(null)
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
            if (stock.bollingerUpper > 0.0) {
                CheckRow("Within Bollinger Bands", stock.ltp <= stock.bollingerUpper && stock.ltp >= stock.bollingerLower)
            }
            if (stock.adx > 0.0) {
                CheckRow("ADX trending (>=25)", stock.adx >= 25.0)
                CheckRow("DI+ above DI- (bullish trend)", stock.adxDiPlus > stock.adxDiMinus)
            }
            if (stock.candlePattern != "NONE") {
                CheckRow("Bullish candle pattern", stock.candleSignal == "BULLISH")
            }
        }

        // Signal Replay button — only when deep analysis has run (has enough history)
        if (stock.isDeepEnriched) {
            OutlinedButton(
                onClick = onReplaySignals,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary),
            ) {
                Text("📊  Replay Historical Signals")
            }
        }

        // Bottom padding so FAB doesn't cover the last row
        Spacer(Modifier.height(80.dp))
        Text(
            "⚠ Educational analysis only — not SEBI-registered advice.\nAll trading decisions are solely your responsibility.",
            color = TextSecondary, fontSize = 11.sp,
        )
    }
}

// ── Reusable section composables ──────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextSecondary, letterSpacing = 0.5.sp)
            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)
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
        Icon(
            imageVector = if (passes) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = null,
            tint = if (passes) GreenBull else RedBear.copy(alpha = 0.55f),
            modifier = Modifier.size(15.dp),
        )
        Text(label, color = if (passes) TextPrimary else TextSecondary, fontSize = 12.sp)
    }
}

@Composable
private fun InfoChip(text: String, color: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.13f)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

@Composable
private fun NewsCard(news: NewsResult?) {
    SectionCard("📰 24h News Context") {
        if (news == null) {
            Text("No recent news found", color = TextSecondary, fontSize = 12.sp)
        } else {
            val (icon, label, color) = when (news.sentiment) {
                NewsSentiment.POSITIVE -> Triple("✅", "Positive",  GreenBull)
                NewsSentiment.NEGATIVE -> Triple("⚠️", "Negative",  RedBear)
                NewsSentiment.NEUTRAL  -> Triple("➖", "Neutral",   AmberWarn)
                NewsSentiment.NONE     -> Triple("📰", "No signal", TextSecondary)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("$icon $label", color = color, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                if (news.ageText.isNotBlank())
                    Text(news.ageText, color = TextSecondary, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(news.headline, color = TextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(4.dp))
            LabelRow("Stock News", news.stockSentiment.name, newsColor(news.stockSentiment))
            LabelRow("Sector News", news.sectorSentiment.name, newsColor(news.sectorSentiment))
            LabelRow("Articles", "${news.stockArticleCount} stock · ${news.sectorArticleCount} sector", TextSecondary)
            LabelRow("Sources", news.sourceCount.toString(), TextSecondary)
            if (news.sector.isNotBlank()) {
                LabelRow("Sector", news.sector, TextSecondary)
            }
            Spacer(Modifier.height(4.dp))
            news.articles.forEach { article ->
                Text("• ${article.headline}", color = TextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
                Text("  ${article.source} · ${article.ageText}", color = TextSecondary, fontSize = 10.sp)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Aggregated from multiple business news sources over the last 24 hours. Used as a supporting intraday context signal.",
                color = TextSecondary, fontSize = 10.sp,
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun fmt(v: Double) = String.format("%,.2f", v)
private fun fmtVol(v: Long): String = when {
    v >= 10_000_000 -> "${String.format("%.1f", v / 10_000_000.0)} Cr"
    v >= 100_000 -> "${String.format("%.1f", v / 100_000.0)} L"
    else -> v.toString()
}

@Composable
private fun rsiColor(rsi: Double) = when {
    rsi in 45.0..65.0 -> GreenBull; rsi > 70 -> RedBear; rsi < 30 -> AmberWarn; else -> TextSecondary
}

private fun rsiLabel(rsi: Double) = when {
    rsi > 70 -> "Overbought"; rsi < 30 -> "Oversold"; rsi in 45.0..65.0 -> "Sweet Spot"; else -> "Neutral"
}

@Composable
private fun trendColor(t: String) = when (t) {
    "Bullish", "Trending Up" -> GreenBull; "Bearish", "Trending Down" -> RedBear; else -> AmberWarn
}

@Composable
private fun predColor(d: String) = when (d) { "Up" -> GreenBull; "Down" -> RedBear; else -> AmberWarn }

@Composable
private fun newsColor(sentiment: NewsSentiment) = when (sentiment) {
    NewsSentiment.POSITIVE -> GreenBull
    NewsSentiment.NEGATIVE -> RedBear
    NewsSentiment.NEUTRAL  -> AmberWarn
    NewsSentiment.NONE     -> TextSecondary
}

// ── Quick Take Card ────────────────────────────────────────────────────────────
//
// Plain-English summary card shown at the very top of the stock detail screen.
// Designed for beginners — no jargon, just: what's happening, what to do, why.

@Composable
private fun QuickTakeCard(qt: QuickTake) {
    val sessionColor = when (qt.sessionPhase) {
        "MORNING"   -> GreenBull
        "MIDDAY"    -> AmberWarn
        "AFTERNOON" -> AmberWarn
        else        -> TextSecondary
    }
    val sessionIcon = when (qt.sessionPhase) {
        "MORNING"   -> "🌅"
        "MIDDAY"    -> "☀️"
        "AFTERNOON" -> "🌆"
        else        -> "🌙"
    }
    val confColor = when {
        qt.confidence >= 70 -> GreenBull
        qt.confidence >= 50 -> AmberWarn
        else                -> RedBear
    }
    val isBuy = qt.action.startsWith("Buy")

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isBuy) GreenBull.copy(alpha = 0.07f) else RedBear.copy(alpha = 0.07f),
        ),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Quick Take",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary,
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = sessionColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        "$sessionIcon  ${qt.sessionPhase}",
                        color = sessionColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }

            // Situation headline
            Text(
                qt.headline,
                color = TextPrimary,
                fontSize = 13.sp,
                lineHeight = 20.sp,
            )

            // 30-min signal summary
            if (qt.thirtyMinSummary.isNotBlank()) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = CardDark,
                ) {
                    Text(
                        qt.thirtyMinSummary,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

            // Action + Target + Stop Loss
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Action
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isBuy) GreenBull.copy(alpha = 0.15f) else RedBear.copy(alpha = 0.12f),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Action", color = TextSecondary, fontSize = 10.sp)
                        Text(
                            qt.action,
                            color = if (isBuy) GreenBull else RedBear,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                    }
                }
            }

            if (qt.target != "—") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = GreenBull.copy(alpha = 0.10f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("🎯  Target", color = TextSecondary, fontSize = 10.sp)
                            Text(qt.target, color = GreenBull, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = RedBear.copy(alpha = 0.10f),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("🛑  Stop Loss", color = TextSecondary, fontSize = 10.sp)
                            Text(qt.stopLoss, color = RedBear, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            HorizontalDivider(color = DividerColor, thickness = 0.5.dp)

            // Why
            Text(
                "Why: ${qt.why}",
                color = TextSecondary,
                fontSize = 12.sp,
                lineHeight = 19.sp,
            )

            // Warning
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = AmberWarn.copy(alpha = 0.08f),
            ) {
                Text(
                    "⚠  ${qt.warning}",
                    color = AmberWarn,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            // Confidence + session note
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    qt.sessionNote,
                    color = sessionColor,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = confColor.copy(alpha = 0.15f),
                ) {
                    Text(
                        "Confidence: ${qt.confidence}%",
                        color = confColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
