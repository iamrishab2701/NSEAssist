package com.nseassist.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import com.nseassist.data.model.AiAnalysisReport
import com.nseassist.data.model.AiTradeDirection
import com.nseassist.ui.viewmodel.ExportState
import androidx.compose.ui.graphics.Color
import com.nseassist.ui.theme.AppGradientBackground
import com.nseassist.ui.theme.BluePrimary
import com.nseassist.ui.theme.CardDark
import com.nseassist.ui.theme.GreenBull
import com.nseassist.ui.theme.RedBear
import com.nseassist.ui.theme.SurfaceDark
import com.nseassist.ui.theme.TextPrimary
import com.nseassist.ui.theme.TextSecondary
import com.nseassist.ui.viewmodel.MainViewModel
import com.nseassist.ui.viewmodel.UiState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiReportScreen(navController: NavController, vm: MainViewModel) {
    val analysisState by vm.aiAnalysis.collectAsState()
    val exportState by vm.exportState.collectAsState()

    AppGradientBackground {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Report") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
            )
        },
        containerColor = Color.Transparent,
    ) { padding ->
        when {
            exportState is ExportState.Preparing -> {
                val prep = exportState as ExportState.Preparing
                LoadingAiState(padding, "Deep analysing top 20 stocks… (${prep.done}/${prep.total})", prep.done.toFloat() / prep.total.toFloat())
            }
            analysisState == null -> EmptyAiReportState(padding)
            analysisState is UiState.Loading -> LoadingAiState(padding, "Analysing with AI…", null)
            analysisState is UiState.Error -> ErrorAiState(message = (analysisState as UiState.Error).message, padding = padding, onBack = { navController.popBackStack() })
            analysisState is UiState.Success -> AiReportContent(report = (analysisState as UiState.Success<AiAnalysisReport>).data, padding = padding)
            else -> EmptyAiReportState(padding)
        }
    }
    } // AppGradientBackground
}

@Composable
private fun EmptyAiReportState(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No AI report available yet.", color = TextSecondary)
    }
}

@Composable
private fun LoadingAiState(padding: PaddingValues, message: String, progress: Float?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = BluePrimary)
        Text(message, color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        if (progress != null) {
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
                color = BluePrimary,
            )
        }
    }
}

@Composable
private fun ErrorAiState(message: String, padding: PaddingValues, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = RedBear)
        TextButton(onClick = onBack) { Text("Go Back") }
    }
}

@Composable
private fun AiReportContent(report: AiAnalysisReport, padding: PaddingValues) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${report.provider.label} · ${report.model}", color = TextSecondary, fontSize = 12.sp)
                    Text(report.summary, color = TextPrimary)
                    if (report.finalVerdict.isNotBlank()) {
                        Text("Verdict: ${report.finalVerdict}", color = TextSecondary, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            RecommendationCard(
                title = "Primary Pick",
                symbol = report.primaryPick.symbol,
                company = report.primaryPick.companyName,
                rationale = report.primaryPick.rationale,
                confidence = report.primaryPick.confidence,
                direction = report.primaryPick.direction,
                setupType = report.primaryPick.setupType,
                entryView = report.primaryPick.entryView,
                stopLoss = report.primaryPick.stopLoss,
                target = report.primaryPick.target,
            )
        }
        report.additionalCandidates.forEachIndexed { index, candidate ->
            item {
                RecommendationCard(
                    title = "Candidate ${index + 1}",
                    symbol = candidate.symbol,
                    company = candidate.companyName,
                    rationale = candidate.rationale,
                    confidence = candidate.confidence,
                    direction = candidate.direction,
                    setupType = candidate.setupType,
                    entryView = candidate.entryView,
                    stopLoss = candidate.stopLoss,
                    target = candidate.target,
                )
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Suggested Allocation", fontWeight = FontWeight.Bold)
                    report.allocations.forEach { allocation ->
                        Text("${allocation.symbol}: Rs ${"%.0f".format(allocation.amount)} - ${allocation.note}", color = TextPrimary)
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Risk Notes", fontWeight = FontWeight.Bold)
                    if (report.riskNotes.isEmpty()) {
                        Text("No additional risks returned.", color = TextSecondary)
                    }
                }
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    report.riskNotes.forEach { note ->
                        Text("- $note", color = TextPrimary)
                    }
                }
            }
        }
        item {
            Text(report.disclaimer, color = TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun RecommendationCard(
    title: String,
    symbol: String,
    company: String,
    rationale: String,
    confidence: Int,
    direction: AiTradeDirection,
    setupType: String,
    entryView: String,
    stopLoss: String,
    target: String,
) {
    val directionColor = when (direction) {
        AiTradeDirection.LONG -> GreenBull
        AiTradeDirection.SHORT -> RedBear
        AiTradeDirection.NO_TRADE -> TextSecondary
    }
    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, fontWeight = FontWeight.Bold)
                Text("Confidence $confidence", color = TextSecondary, fontSize = 12.sp)
            }
            Text(symbol, fontWeight = FontWeight.Bold, color = TextPrimary)
            if (company.isNotBlank()) Text(company, color = TextSecondary, fontSize = 12.sp)
            Text("${direction.name.replace('_', ' ')} · $setupType", color = directionColor, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(rationale, color = TextPrimary)
            if (entryView.isNotBlank()) Text("Entry: $entryView", color = TextSecondary, fontSize = 12.sp)
            if (stopLoss.isNotBlank()) Text("Stop Loss: $stopLoss", color = TextSecondary, fontSize = 12.sp)
            if (target.isNotBlank()) Text("Target: $target", color = TextSecondary, fontSize = 12.sp)
        }
    }
}
