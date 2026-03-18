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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
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
    var selectedTab   by remember { mutableIntStateOf(0) }
    var showClearDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadTrades() }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear all entries?") },
            text  = { Text("This will permanently delete all Paper Trades and AI Audit entries. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        vm.clearAllTrades()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedBear),
                ) { Text("Clear All") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
        )
    }

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
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear all entries", tint = TextSecondary)
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
                            0 -> PaperTradesTab(trades = all.filter { it.verdict == "GO" }, vm = vm)
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
    var pendingOutcome  by remember { mutableStateOf("") }        // "TARGET_HIT" or "SL_HIT"
    var outcomePrice    by remember { mutableStateOf("") }
    var showPriceDialog by remember { mutableStateOf(false) }

    if (showPriceDialog) {
        val isTargetHit = pendingOutcome == "TARGET_HIT"
        val label = if (isTargetHit) "Target Hit Price" else "SL Hit Price"
        AlertDialog(
            onDismissRequest = { showPriceDialog = false; outcomePrice = "" },
            title   = { Text(label) },
            text    = {
                OutlinedTextField(
                    value         = outcomePrice,
                    onValueChange = { outcomePrice = it },
                    label         = { Text("Exit price (₹)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine    = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val price = outcomePrice.toDoubleOrNull()
                        if (price != null && price > 0) {
                            vm.updateTradeOutcome(trade.id, pendingOutcome, price)
                            showPriceDialog = false
                            outcomePrice    = ""
                        }
                    },
                    enabled = outcomePrice.toDoubleOrNull()?.let { it > 0 } == true,
                ) { Text("Confirm", color = if (isTargetHit) GreenBull else RedBear) }
            },
            dismissButton = {
                TextButton(onClick = { showPriceDialog = false; outcomePrice = "" }) { Text("Cancel") }
            },
        )
    }

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
                    OutcomeButton("Target Hit", GreenBull) {
                        pendingOutcome  = "TARGET_HIT"
                        outcomePrice    = if (trade.entryPrice > 0) "%.2f".format(trade.entryPrice) else ""
                        showPriceDialog = true
                    }
                    OutcomeButton("SL Hit", RedBear) {
                        pendingOutcome  = "SL_HIT"
                        outcomePrice    = if (trade.entryPrice > 0) "%.2f".format(trade.entryPrice) else ""
                        showPriceDialog = true
                    }
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
            Text(
                "No AI recommendations logged yet.\nRecommendations are auto-logged on every AI call.",
                color = TextSecondary, fontSize = 13.sp,
            )
        }
        return
    }

    val goTrades     = trades.filter { it.verdict == "GO" || it.verdict == "AI_BATCH" }
    val noGoTrades   = trades.filter { it.verdict == "NO-GO" }
    val closedGo     = goTrades.filter { it.outcome != "OPEN" }
    val wins         = closedGo.count { it.outcome == "TARGET_HIT" }
    val winRate      = if (closedGo.isNotEmpty()) (wins.toDouble() / closedGo.size * 100).toInt() else 0
    val winRateColor = if (winRate >= 60) GreenBull else if (winRate >= 40) AmberWarn else RedBear

    // Provider breakdown — only for closed GO/BATCH trades
    val byProvider = goTrades.filter { it.outcome != "OPEN" }.groupBy { it.aiProvider }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Summary card
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("AI Audit Summary", fontWeight = FontWeight.Bold, color = TextPrimary)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        StatBadge("Total", "${trades.size}", TextPrimary)
                        StatBadge("GO", "${goTrades.size}", GreenBull)
                        StatBadge("NO-GO", "${noGoTrades.size}", RedBear)
                        StatBadge("Win Rate", if (closedGo.isEmpty()) "—" else "$winRate%", winRateColor)
                    }
                    if (closedGo.isEmpty()) {
                        Text(
                            "Win rate appears once you mark outcomes on GO trades.",
                            color = TextSecondary, fontSize = 11.sp,
                        )
                    }
                }
            }
        }

        // Provider win rates (only when there are closed trades)
        if (byProvider.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Provider Win Rates", fontWeight = FontWeight.Bold, color = TextPrimary)
                        byProvider.forEach { (provider, provTrades) ->
                            val provWins = provTrades.count { it.outcome == "TARGET_HIT" }
                            val rate     = (provWins.toDouble() / provTrades.size * 100).toInt()
                            val color    = if (rate >= 60) GreenBull else if (rate >= 40) AmberWarn else RedBear
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(provider.ifBlank { "Unknown" }, color = TextPrimary, fontSize = 13.sp)
                                Text(
                                    "$provWins/${provTrades.size}  ($rate%)",
                                    color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }

        // All recommendations newest first
        item { Text("All Recommendations", color = TextSecondary, fontSize = 12.sp) }
        items(trades.sortedByDescending { it.loggedAt }) { trade ->
            AuditCard(trade)
        }
    }
}

@Composable
private fun AuditCard(trade: PaperTradeEntry) {
    val isGo      = trade.verdict == "GO" || trade.verdict == "AI_BATCH"
    val isBatch   = trade.verdict == "AI_BATCH"

    val verdictColor = if (isGo) GreenBull else RedBear
    val verdictLabel = when (trade.verdict) {
        "AI_BATCH" -> "BATCH"
        "NO-GO"    -> "NO-GO"
        else       -> "GO"
    }

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
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            // Header row: symbol + verdict badge
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(trade.symbol, fontWeight = FontWeight.Bold, color = TextPrimary, fontSize = 14.sp)
                    if (trade.companyName.isNotBlank()) {
                        Text(trade.companyName, color = TextSecondary, fontSize = 11.sp)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Source label
                    Text(
                        if (isBatch) "Batch AI" else "Single",
                        color = TextSecondary.copy(alpha = 0.7f), fontSize = 10.sp,
                    )
                    // Verdict badge
                    Surface(shape = RoundedCornerShape(4.dp), color = verdictColor.copy(alpha = 0.15f)) {
                        Text(
                            verdictLabel, color = verdictColor, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            // Direction + provider + confidence
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (isGo && trade.direction.isNotBlank() && trade.direction != "SKIP") {
                    val dirColor = if (trade.direction == "BUY") GreenBull else RedBear
                    Text(trade.direction, color = dirColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "${trade.aiProvider.ifBlank { "AI" }}  ·  conf ${trade.confidence}",
                    color = TextSecondary, fontSize = 12.sp,
                )
            }

            // Target / SL (only for GO trades with data)
            if (isGo && trade.targetText.isNotBlank()) {
                Text("Target: ${trade.targetText}  |  SL: ${trade.stopLossText}", color = TextSecondary, fontSize = 11.sp)
            }

            // Reason for NO-GO
            if (!isGo && trade.reason.isNotBlank()) {
                Text(trade.reason, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
            }

            // Footer: timestamp + outcome badge for GO trades
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimestamp(trade.loggedAt), color = TextSecondary.copy(alpha = 0.6f), fontSize = 10.sp)
                if (isGo) {
                    Surface(shape = RoundedCornerShape(4.dp), color = outcomeColor.copy(alpha = 0.15f)) {
                        Text(
                            trade.outcome, color = outcomeColor, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
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
