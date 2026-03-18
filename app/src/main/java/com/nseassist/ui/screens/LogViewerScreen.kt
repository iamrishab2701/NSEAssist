package com.nseassist.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.util.AppLogger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BgColor     = Color(0xFF0A0E17)
private val EntryBg     = Color(0xFF111827)
private val ColorD      = Color(0xFF9E9EA8)
private val ColorI      = Color(0xFF5B8FF9)
private val ColorW      = Color(0xFFFFB74D)
private val ColorE      = Color(0xFFFF4444)
private val ColorNet    = Color(0xFF00C853)
private val ColorNav    = Color(0xFF80DEEA)
private val timeFmt     = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private val FILTERS = listOf("ALL", "NET", "NAV", "SCAN", "QT", "AI", "UPSTOX", "TRADE", "MARKET", "NEWS", "INIT")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(navController: NavController) {
    val context   = LocalContext.current
    val allEntries by AppLogger.entries.collectAsState()
    var filter    by remember { mutableStateOf("ALL") }
    var showClearDialog by remember { mutableStateOf(false) }

    val filtered = remember(allEntries, filter) {
        if (filter == "ALL") allEntries
        else allEntries.filter { it.tag.startsWith(filter, ignoreCase = true) }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to latest entry
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.lastIndex)
    }

    Scaffold(
        containerColor = BgColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Session Logs", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color.White)
                        Text("${allEntries.size} entries", fontSize = 11.sp, color = ColorD)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    // Copy to clipboard — works on emulator and physical devices
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("NSEAssist Logs", AppLogger.export()))
                        Toast.makeText(context, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.Share, "Copy", tint = ColorI)
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Default.Delete, "Clear", tint = ColorE)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1525)),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(BgColor),
        ) {
            // ── Filter chips ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0D1525))
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FILTERS.forEach { tag ->
                    val selected = filter == tag
                    FilterChip(
                        selected = selected,
                        onClick  = { filter = tag },
                        label    = {
                            val count = if (tag == "ALL") allEntries.size
                                        else allEntries.count { it.tag.startsWith(tag, ignoreCase = true) }
                            Text("$tag ($count)", fontSize = 11.sp)
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor     = Color(0xFF1E3A5F),
                            selectedLabelColor         = ColorI,
                            containerColor             = Color(0xFF1A1F2E),
                            labelColor                 = ColorD,
                        ),
                    )
                }
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.06f))

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No log entries yet.", color = ColorD, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    state   = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(filtered, key = { it.id }) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title   = { Text("Clear Logs") },
            text    = { Text("Delete all ${allEntries.size} log entries for this session?") },
            confirmButton = {
                TextButton(onClick = { AppLogger.clear(); showClearDialog = false }) {
                    Text("Clear", color = ColorE)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            },
            containerColor = Color(0xFF111827),
        )
    }
}

@Composable
private fun LogEntryRow(entry: AppLogger.Entry) {
    val levelColor = when (entry.level) {
        AppLogger.Level.E -> ColorE
        AppLogger.Level.W -> ColorW
        AppLogger.Level.I -> ColorI
        AppLogger.Level.D -> ColorD
    }
    val tagColor = when {
        entry.tag == "NET" -> ColorNet
        entry.tag == "NAV" -> ColorNav
        else               -> levelColor
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .background(EntryBg, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment     = Alignment.Top,
    ) {
        // Timestamp
        Text(
            text       = timeFmt.format(Date(entry.timestamp)),
            color      = Color(0xFF4A5568),
            fontSize   = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.width(72.dp),
        )
        // Level badge
        Text(
            text       = entry.level.label,
            color      = levelColor,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            modifier   = Modifier.width(10.dp),
        )
        // Tag
        Text(
            text       = entry.tag,
            color      = tagColor,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.width(40.dp),
        )
        // Message
        Text(
            text       = entry.message,
            color      = if (entry.level == AppLogger.Level.D) Color(0xFFCDD5E0) else levelColor,
            fontSize   = 10.sp,
            fontFamily = FontFamily.Monospace,
            modifier   = Modifier.weight(1f),
        )
    }
}
