package com.nseassist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.data.model.PaperTradeEntry
import com.nseassist.ui.theme.AmberWarn
import com.nseassist.ui.theme.AppGradientBackground
import com.nseassist.ui.theme.BluePrimary
import com.nseassist.ui.theme.CardDark
import com.nseassist.ui.theme.DividerColor
import com.nseassist.ui.theme.GreenBull
import com.nseassist.ui.theme.RedBear
import com.nseassist.ui.theme.TextPrimary
import com.nseassist.ui.theme.TextSecondary
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PerformanceScreen(navController: NavController, vm: MainViewModel) {
    val tradesState by vm.trades.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.loadTrades() }

    AppGradientBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Performance") },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { vm.triggerMemoryCleanup() }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear old data (>6 months)", tint = TextSecondary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
                )
            },
            containerColor = Color.Transparent,
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = CardDark,
                    contentColor = BluePrimary,
                    divider = { HorizontalDivider(color = DividerColor, thickness = 0.5.dp) },
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text("Paper Trades", modifier = Modifier.padding(vertical = 12.dp), fontSize = 13.sp)
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text("AI Audit", modifier = Modifier.padding(vertical = 12.dp), fontSize = 13.sp)
                    }
                }

                when (val state = tradesState) {
                    is UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = BluePrimary)
                    }
                    is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message, color = RedBear)
                    }
                    is UiState.Success -> {
                        val all = state.data
                        when (selectedTab) {
                            0 -> PaperTradesTab(trades = all.filter { it.verdict != "AI_BATCH" }, vm = vm)
                            1 -> AiAuditTab(trades = all)
                        }
                    }
                }
            }
        }
    }
}

// ── Paper Trades Tab ──────────────────────────────────────────────────────────

@Composable
private fun PaperTradesTab(trades: List<PaperTradeEntry>, vm: MainViewModel) {
    if (trades.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No paper trades logged yet.\nTap 'Log Paper Trade' after an AI GO verdict.", color = TextSecondary, fontSize = 13.sp)
        }
        return
    }

    val open   = trades.filter { it.outcome == "OPEN" }
    val closed = trades.filter { it.outcome != "OPEN" }
    val wins   = closed.count { it.outcome == "TARGET_HIT" }
    val losses = closed.count { it.outcome == "SL_HIT" }
    val winRate = if (closed.isNotEmpty()) (wins.toDouble() / closed.size * 100).toInt() else 0

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Summary card
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Summary", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBadge("Total", "${trades.size}", TextPrimary)
                        StatBadge("Open", "${open.size}", AmberWarn)
                        StatBadge("Won", "$wins", GreenBull)
                        StatBadge("Lost", "$losses", RedBear)
                        StatBadge("Win Rate", "$winRate%", if (winRate >= 60) GreenBull else if (winRate >= 40) AmberWarn else RedBear)
                    }
                }
            }
        }
        // Open trades
        if (open.isNotEmpty()) {
            item { Text("Open Trades", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            items(open) { trade -> TradeCard(trade, vm) }
        }
        // Closed trades
        if (closed.isNotEmpty()) {
            item { Text("Closed Trades", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)) }
            items(closed) { trade -> TradeCard(trade, vm) }
        }
    }
}

@Composable
private fun TradeCard(trade: PaperTradeEntry, vm: MainViewModel) {
    val outcomeColor = when (trade.outcome) {
        "TARGET_HIT" -> GreenBull
        "SL_HIT"     -> RedBear
        "EXPIRED"    -> TextSecondary
        else         -> AmberWarn
    }
    val dirColor = when (trade.direction) {
        "BUY"  -> GreenBull
        "SELL" -> RedBear
        else   -> TextSecondary
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(trade.symbol, fontWeight = FontWeight.Bold, color = TextPrimary)
                Surface(shape = RoundedCornerShape(4.dp), color = outcomeColor.copy(alpha = 0.15f)) {
                    Text(trade.outcome, color = outcomeColor, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            if (trade.companyName.isNotBlank()) Text(trade.companyName, color = TextSecondary, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(trade.direction, color = dirColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                if (trade.entryPrice > 0) Text("Entry ₹${"%.2f".format(trade.entryPrice)}", color = TextSecondary, fontSize = 12.sp)
                Text("Confidence ${trade.confidence}", color = TextSecondary, fontSize = 12.sp)
            }
            if (trade.targetText.isNotBlank()) Text("Target: ${trade.targetText}  |  SL: ${trade.stopLossText}", color = TextSecondary, fontSize = 11.sp)
            if (trade.rrRatio.isNotBlank()) Text("R:R ${trade.rrRatio}  ·  ${trade.aiProvider}", color = TextSecondary, fontSize = 11.sp)
            Text(formatTimestamp(trade.loggedAt), color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp)

            // Outcome buttons for open trades
            if (trade.outcome == "OPEN") {
                HorizontalDivider(color = DividerColor, thickness = 0.5.dp, modifier = Modifier.padding(top = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutcomeButton("Target Hit", GreenBull) { vm.updateTradeOutcome(trade.id, "TARGET_HIT", trade.entryPrice) }
                    OutcomeButton("SL Hit", RedBear)        { vm.updateTradeOutcome(trade.id, "SL_HIT", trade.entryPrice) }
                    OutcomeButton("Expired", TextSecondary) { vm.updateTradeOutcome(trade.id, "EXPIRED", null) }
                }
            }
        }
    }
}

@Composable
private fun OutcomeButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(label, color = color, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

// ── AI Audit Tab ──────────────────────────────────────────────────────────────

@Composable
private fun AiAuditTab(trades: List<PaperTradeEntry>) {
    if (trades.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No AI recommendations logged yet.\nRecommendations are auto-logged on every AI call.", color = TextSecondary, fontSize = 13.sp)
        }
        return
    }

    // Group by provider for win rate per provider
    val byProvider = trades.filter { it.outcome != "OPEN" }.groupBy { it.aiProvider }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Provider breakdown
        if (byProvider.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Provider Win Rates", fontWeight = FontWeight.Bold, color = TextPrimary)
                        byProvider.forEach { (provider, provTrades) ->
                            val wins = provTrades.count { it.outcome == "TARGET_HIT" }
                            val rate = (wins.toDouble() / provTrades.size * 100).toInt()
                            val rateColor = if (rate >= 60) GreenBull else if (rate >= 40) AmberWarn else RedBear
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(provider.ifBlank { "Unknown" }, color = TextPrimary, fontSize = 13.sp)
                                Text("$wins/${provTrades.size}  ($rate%)", color = rateColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        // All recommendations chronologically
        item { Text("All Recommendations", color = TextSecondary, fontSize = 12.sp) }
        items(trades) { trade ->
            AuditCard(trade)
        }
    }
}

@Composable
private fun AuditCard(trade: PaperTradeEntry) {
    val outcomeColor = when (trade.outcome) {
        "TARGET_HIT" -> GreenBull
        "SL_HIT"     -> RedBear
        "EXPIRED"    -> TextSecondary
        else         -> AmberWarn
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(trade.symbol, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                Text("${trade.direction}  ·  ${trade.aiProvider.ifBlank { "AI" }}  ·  conf ${trade.confidence}", color = TextSecondary, fontSize = 11.sp)
                Text(formatTimestamp(trade.loggedAt), color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp)
            }
            Surface(shape = RoundedCornerShape(4.dp), color = outcomeColor.copy(alpha = 0.15f)) {
                Text(trade.outcome, color = outcomeColor, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun StatBadge(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, color = TextSecondary, fontSize = 10.sp)
    }
}

private fun formatTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return ""
    return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(epochMs))
}
