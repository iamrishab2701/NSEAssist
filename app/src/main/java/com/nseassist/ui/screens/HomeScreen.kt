package com.nseassist.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.CurrencyRupee
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import com.nseassist.data.local.AiSettingsStore
import com.nseassist.data.local.ThemeMode
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.MarketStatus
import com.nseassist.data.model.ScanCategory
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState

private enum class HomeTab(val label: String) {
    Market("Market"),
    Trends("Trends"),
    Capital("Capital"),
    Short("Short"),
    Search("Search"),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(navController: NavController, vm: MainViewModel) {
    val marketState         by vm.marketOverview.collectAsState()
    val trendsState         by vm.globalTrends.collectAsState()
    val aiSettings          by vm.aiSettings.collectAsState()
    val testMorningSelloff  by vm.testMorningSelloff.collectAsState()
    val dataSource          by vm.dataSource.collectAsState()
    val upstoxTokenValid    by vm.upstoxTokenValid.collectAsState()
    val themeMode by vm.themeMode.collectAsState()
    var capital by remember { mutableStateOf("") }
    var stockQuery by remember { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf(ScanCategory.ALL.routeValue) }
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.Market.name) }
    val currentTab = HomeTab.valueOf(selectedTab)
    val currentCategory = ScanCategory.fromRouteValue(selectedCategory)
    var showHamburger by remember { mutableStateOf(false) }

    // Short mode is active only while the Short tab is visible — all other tabs use normal mode
    LaunchedEffect(currentTab) { vm.setShortMode(currentTab == HomeTab.Short) }

    AppGradientBackground {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NSE Assist", fontWeight = FontWeight.Bold) },
                actions = {
                    if (currentTab == HomeTab.Market) {
                        IconButton(onClick = { vm.loadMarketOverview() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    if (currentTab == HomeTab.Trends) {
                        IconButton(onClick = { vm.refreshGlobalTrends() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh trends")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = CardDark) {
                HomeTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { selectedTab = tab.name },
                        icon = {
                            when (tab) {
                                HomeTab.Market  -> Icon(Icons.Filled.ShowChart, contentDescription = null)
                                HomeTab.Trends  -> Icon(Icons.Filled.Public, contentDescription = null)
                                HomeTab.Capital -> Icon(Icons.Filled.CurrencyRupee, contentDescription = null)
                                HomeTab.Short   -> Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null)
                                HomeTab.Search  -> Icon(Icons.Default.Search, contentDescription = null)
                            }
                        },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = if (tab == HomeTab.Short) RedBear else BluePrimary,
                            selectedTextColor = TextPrimary,
                            indicatorColor = SurfaceDark,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                        ),
                    )
                }
                // Hamburger — Settings, Logs, About
                NavigationBarItem(
                    selected = false,
                    onClick = { showHamburger = true },
                    icon = { Icon(Icons.Default.Menu, contentDescription = "More") },
                    label = { Text("More") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = BluePrimary,
                        selectedTextColor = TextPrimary,
                        indicatorColor = SurfaceDark,
                        unselectedIconColor = TextSecondary,
                        unselectedTextColor = TextSecondary,
                    ),
                )
            }
        },
        containerColor = Color.Transparent,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (currentTab) {
                HomeTab.Market -> MarketOverviewTab(marketState, onRetry = vm::loadMarketOverview)
                HomeTab.Trends -> {
                    androidx.compose.runtime.LaunchedEffect(Unit) { vm.loadGlobalTrends() }
                    MarketTrendTab(trendsState, onRetry = vm::refreshGlobalTrends)
                }
                HomeTab.Capital -> {
                    // ── Upstox token warning banner ───────────────────────────
                    if (dataSource == AiSettingsStore.DATA_SOURCE_UPSTOX && !upstoxTokenValid) {
                        UpstoxTokenBanner(onGoToSettings = { navController.navigate("model_config") })
                    }
                    var scanMode by remember { mutableStateOf("normal") }
                    CapitalInputCard(
                        capital = capital,
                        category = currentCategory,
                        scanMode = scanMode,
                        testMorningSelloff = testMorningSelloff,
                        onCapitalChange = { capital = it },
                        onCategoryChange = { selectedCategory = it.routeValue },
                        onScanModeChange = { scanMode = it },
                        onScan = {
                            val amt = capital.toDoubleOrNull() ?: 0.0
                            if (amt > 0) navController.navigate("scan/$amt/${currentCategory.routeValue}/$scanMode")
                        },
                    )
                }
                HomeTab.Search -> StockSearchCard(
                    query = stockQuery,
                    onQueryChange = { stockQuery = it.uppercase() },
                    onSearch = {
                        val symbol = stockQuery.trim()
                        if (symbol.isNotBlank()) navController.navigate("stock/$symbol")
                    },
                )
                HomeTab.Short -> {
                    if (dataSource == AiSettingsStore.DATA_SOURCE_UPSTOX && !upstoxTokenValid) {
                        UpstoxTokenBanner(onGoToSettings = { navController.navigate("settings/model-config") })
                    }
                    ShortScanCard(
                        capital = capital,
                        category = currentCategory,
                        onCapitalChange = { capital = it },
                        onCategoryChange = { selectedCategory = it.routeValue },
                        onScan = {
                            val amt = capital.toDoubleOrNull() ?: 0.0
                            if (amt > 0) navController.navigate("scan/$amt/${currentCategory.routeValue}/short")
                        },
                    )
                }
            }
        }
    }
    // ── Hamburger bottom sheet ────────────────────────────────────────────────
    if (showHamburger) {
        ModalBottomSheet(
            onDismissRequest = { showHamburger = false },
            containerColor = SheetSurface,
        ) {
            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                Text(
                    "More",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
                HorizontalDivider(color = SurfaceDark)
                // Settings
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showHamburger = false
                            navController.navigate("settings")
                        }
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = null, tint = TextSecondary)
                        Text("Settings", fontSize = 15.sp, color = TextPrimary)
                    }
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondary)
                }
            }
        }
    }
    } // AppGradientBackground
}

// ── Upstox token expired / not activated banner ──────────────────────────────────

@Composable
private fun UpstoxTokenBanner(onGoToSettings: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = AmberWarn.copy(alpha = 0.13f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarn.copy(alpha = 0.5f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onGoToSettings() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("⚠", fontSize = 18.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Upstox token not active",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = AmberWarn,
                )
                Text(
                    "Real-time data unavailable — using Yahoo Finance (15-min delay). " +
                    "Tap here to activate today's token.",
                    fontSize = 11.sp,
                    color = AmberWarn.copy(alpha = 0.85f),
                    lineHeight = 15.sp,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Go to settings",
                tint = AmberWarn,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun MarketOverviewTab(marketState: UiState<MarketOverview>, onRetry: () -> Unit) {
    when (marketState) {
        is UiState.Loading -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        is UiState.Success -> {
            MarketStatusBanner(marketState.data.marketStatus)
            if (marketState.data.indiaVix >= 20.0) VixDangerBanner(marketState.data.indiaVix)
            MarketOverviewCard(marketState.data)
            MarketSnapshotCard(marketState.data)
            TopMoversCard(marketState.data)
        }
        is UiState.Error -> ErrorCard(marketState.message, onRetry)
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
private fun VixDangerBanner(vix: Double) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = RedBear.copy(alpha = 0.15f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚠", fontSize = 16.sp)
            Column {
                Text(
                    "High Volatility — India VIX ${String.format("%.1f", vix)}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = RedBear,
                )
                Text(
                    "Market is very volatile today. Consider skipping intraday trades or reducing position size.",
                    fontSize = 11.sp,
                    color = RedBear.copy(alpha = 0.85f),
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun MarketOverviewCard(data: MarketOverview) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Market Overview", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            HorizontalDivider(color = DividerColor)
            IndexRow("NIFTY 50", data.nifty50, data.nifty50ChangePct, data.niftyAboveVwap)
            IndexRow("BANK NIFTY", data.bankNifty, data.bankNiftyChangePct, data.bankNiftyAboveVwap)
            if (data.indiaVix > 0.0) {
                HorizontalDivider(color = DividerColor)
                VixRow(data.indiaVix)
            }
        }
    }
}

@Composable
private fun VixRow(vix: Double) {
    val (label, color) = when {
        vix < 13.0 -> "Very Calm" to GreenBull
        vix < 17.0 -> "Normal"    to GreenBull
        vix < 20.0 -> "Caution"   to AmberWarn
        else       -> "DANGER"    to RedBear
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("India VIX", color = TextSecondary, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format("%.2f", vix), color = color, fontWeight = FontWeight.Bold)
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = color.copy(alpha = 0.15f),
            ) {
                Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = androidx.compose.ui.Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun MarketSnapshotCard(data: MarketOverview) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Market Snapshot", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            HorizontalDivider(color = DividerColor)
            SnapshotRow("NIFTY Day Change", String.format("%+.1f pts", data.nifty50Change), data.nifty50Change)
            SnapshotRow("BANK NIFTY Change", String.format("%+.1f pts", data.bankNiftyChange), data.bankNiftyChange)
            SnapshotRow("NIFTY VWAP Bias", if (data.niftyAboveVwap) "Above VWAP" else "Below VWAP", if (data.niftyAboveVwap) 1.0 else -1.0)
            SnapshotRow("BANK VWAP Bias", if (data.bankNiftyAboveVwap) "Above VWAP" else "Below VWAP", if (data.bankNiftyAboveVwap) 1.0 else -1.0)
        }
    }
}

@Composable
private fun SnapshotRow(label: String, value: String, trend: Double) {
    val color = if (trend >= 0) GreenBull else RedBear
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = color, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
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
            HorizontalDivider(color = DividerColor)
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
private fun StockSearchCard(query: String, onQueryChange: (String) -> Unit, onSearch: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Stock Search", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Text("Enter an NSE symbol to open the stock details screen.", color = TextSecondary, fontSize = 12.sp)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text("Symbol") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrect = false,
                    imeAction = ImeAction.Search,
                    keyboardType = KeyboardType.Text,
                ),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = onSearch,
                enabled = query.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary, contentColor = Color.White),
            ) {
                Text("Search Stock", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapitalInputCard(
    capital: String,
    category: ScanCategory,
    scanMode: String,
    testMorningSelloff: Boolean = false,
    onCapitalChange: (String) -> Unit,
    onCategoryChange: (ScanCategory) -> Unit,
    onScanModeChange: (String) -> Unit,
    onScan: () -> Unit,
) {
    val isBreakouts     = scanMode == "breakouts"
    val isReadyToTrade  = scanMode == "trade"
    val isOversold      = scanMode == "oversold"

    // Morning Selloff only active 9:15 AM – 12:00 PM IST on weekdays (bypass via test mode)
    val isMorningSelloffTime = testMorningSelloff || remember {
        val ist  = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Kolkata"))
        val dow  = ist.dayOfWeek.value          // 1=Mon … 7=Sun
        val mins = ist.hour * 60 + ist.minute
        dow < 6 && mins >= 9 * 60 + 15 && mins < 12 * 60
    }

    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Today's Capital", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
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
            Text(
                "Stock Type",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = if (isOversold) TextSecondary.copy(alpha = 0.4f) else TextPrimary,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanCategory.values().forEach { option ->
                    FilterChip(
                        selected = category == option,
                        onClick = { if (!isOversold) onCategoryChange(option) },
                        enabled = !isOversold,
                        label = { Text(option.label) },
                    )
                }
            }
            Text(
                if (isOversold) "Morning Selloff scans Large, Mid & Small Cap — stock type ignored"
                else category.helperText,
                color = if (isOversold) AmberWarn.copy(alpha = 0.8f) else TextSecondary,
                fontSize = 12.sp,
            )

            // ── Ready for Breakouts toggle ────────────────────────────────────
            ScanModeToggle(
                title = "Ready for Breakouts",
                description = if (isBreakouts)
                    "Top 20 stocks — full Phase 2 analysis · score-based BUY signals"
                else
                    "Deep scan: BUY where score ≥ 65, above VWAP, bullish candle",
                checked = isBreakouts,
                onCheckedChange = { onScanModeChange(if (it) "breakouts" else "normal") },
                activeColor = BluePrimary,
            )

            // ── Ready to Trade toggle (real-time resistance breakout) ──────────
            ScanModeToggle(
                title = "Ready to Trade",
                description = if (isReadyToTrade)
                    "Top 20 — BUY on 30-day resistance breakout OR score ≥ 65, above VWAP"
                else
                    "Real-time resistance breakout · works all day, not just mornings",
                checked = isReadyToTrade,
                onCheckedChange = { onScanModeChange(if (it) "trade" else "normal") },
                activeColor = GreenBull,
            )

            // ── Morning Selloff toggle (oversold bounce) ───────────────────────
            ScanModeToggle(
                title = "Morning Selloff",
                description = when {
                    isOversold          -> "Large, Mid & Small Cap · 50–70% daily volume sold · bounce expected"
                    isMorningSelloffTime -> "Large, Mid & Small Cap stocks heavily sold this morning — bounce watch"
                    else                -> "Only active 9:15 AM – 12:00 PM · market must be open"
                },
                checked = isOversold,
                onCheckedChange = { onScanModeChange(if (it) "oversold" else "normal") },
                activeColor = AmberWarn,
                enabled = isMorningSelloffTime || isOversold,
            )

            Button(
                onClick = onScan,
                enabled = (capital.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when (scanMode) {
                        "trade"     -> GreenBull
                        "breakouts" -> BluePrimary
                        "oversold"  -> AmberWarn
                        else        -> BluePrimary
                    },
                    contentColor = Color.White,
                ),
            ) {
                Text(
                    when (scanMode) {
                        "trade"    -> "Find Ready to Trade Stocks"
                        "breakouts"-> "Find Breakout Stocks"
                        "oversold" -> "Find Morning Selloff Stocks"
                        else       -> "Scan Affordable Stocks"
                    },
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// ── Short Mode scan card ──────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShortScanCard(
    capital: String,
    category: ScanCategory,
    onCapitalChange: (String) -> Unit,
    onCategoryChange: (ScanCategory) -> Unit,
    onScan: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        border = BorderStroke(1.dp, RedBear.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null, tint = RedBear)
                Text("Short Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = RedBear)
            }
            Text(
                "Finds stocks going DOWN. Use SELL (MIS) in Kite to short them.",
                color = TextSecondary, fontSize = 12.sp,
            )
            // Capital input
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
            Text("Stock Type", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanCategory.values().forEach { option ->
                    FilterChip(
                        selected = category == option,
                        onClick = { onCategoryChange(option) },
                        label = { Text(option.label) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = RedBear.copy(alpha = 0.15f),
                            selectedLabelColor = RedBear,
                        ),
                    )
                }
            }
            // Warning info
            Surface(
                color = RedBear.copy(alpha = 0.08f),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "⚠ Only short when NIFTY is falling. Never short on a strongly bullish day.",
                    color = RedBear.copy(alpha = 0.9f),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(10.dp),
                )
            }
            Button(
                onClick = onScan,
                enabled = (capital.toDoubleOrNull() ?: 0.0) > 0,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = RedBear, contentColor = Color.White),
            ) {
                Icon(Icons.AutoMirrored.Filled.TrendingDown, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Find Stocks to Short", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ScanModeToggle(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    activeColor: Color,
    enabled: Boolean = true,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = when {
            checked  -> activeColor.copy(alpha = 0.10f)
            !enabled -> SurfaceDark.copy(alpha = 0.5f)
            else     -> SurfaceDark
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = when {
                        checked  -> activeColor
                        !enabled -> TextSecondary.copy(alpha = 0.5f)
                        else     -> TextPrimary
                    },
                )
                Text(description, color = TextSecondary.copy(alpha = if (enabled) 1f else 0.5f), fontSize = 11.sp)
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = activeColor,
                    checkedTrackColor = activeColor.copy(alpha = 0.3f),
                ),
            )
        }
    }
}

@Composable
private fun AiSettingsTab(
    themeMode:  ThemeMode,
    onTheme:    (ThemeMode) -> Unit,
    onNavigate: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {

        // ── Appearance ────────────────────────────────────────────────────────
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Appearance", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Text("Choose how the app looks", fontSize = 12.sp, color = TextSecondary)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.values().forEach { mode ->
                        val selected = mode == themeMode
                        val label = when (mode) {
                            ThemeMode.SYSTEM -> "System"
                            ThemeMode.LIGHT  -> "Light"
                            ThemeMode.DARK   -> "Dark"
                        }
                        val icon = when (mode) {
                            ThemeMode.SYSTEM -> "⚙"
                            ThemeMode.LIGHT  -> "☀"
                            ThemeMode.DARK   -> "🌙"
                        }
                        Surface(
                            onClick = { onTheme(mode) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) BluePrimary else CardDark,
                            border = if (!selected) BorderStroke(0.5.dp, DividerColor) else null,
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(icon, fontSize = 16.sp)
                                Text(
                                    label,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) Color.White else TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Navigation rows ───────────────────────────────────────────────────
        SettingsMenuRow(
            title = "Performance",
            subtitle = "Paper trades, signal replay history and AI audit",
            onClick = { onNavigate("performance") },
        )
        SettingsMenuRow(
            title = "Data & AI Settings",
            subtitle = "Add or update your AI provider API keys",
            onClick = { onNavigate("settings/model-config") },
        )
        SettingsMenuRow(
            title = "About App",
            subtitle = "Version, developer info and disclaimer",
            onClick = { onNavigate("settings/about") },
        )
    }
}

@Composable
private fun SettingsMenuRow(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Text(subtitle, fontSize = 12.sp, color = TextSecondary)
            }
            Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = TextSecondary,
            )
        }
    }
}

@Composable
private fun AiProviderSettingsCard(
    config: AiProviderConfig,
    onSave: (AiProvider, String, String) -> Unit,
) {
    var apiKey by remember(config.provider, config.apiKey) { mutableStateOf(config.apiKey) }
    var model by remember(config.provider, config.model) { mutableStateOf(config.model) }

    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(config.provider.label, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it.trim() },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("Model") },
                supportingText = { Text("Default: ${config.provider.defaultModel}") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSave(config.provider, apiKey, model) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary, contentColor = Color.White),
            ) {
                Text(if (config.apiKey.isBlank()) "Save ${config.provider.label}" else "Update ${config.provider.label}")
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
