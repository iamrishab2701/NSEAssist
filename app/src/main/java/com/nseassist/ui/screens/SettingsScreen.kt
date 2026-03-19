package com.nseassist.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.data.local.ThemeMode
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavController, vm: MainViewModel) {
    val themeMode by vm.themeMode.collectAsState()

    AppGradientBackground {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = CardDark),
                )
            },
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                // ── Appearance ────────────────────────────────────────────────
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                                    onClick = { vm.setThemeMode(mode) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (selected) BluePrimary else CardDark,
                                    border = if (!selected) androidx.compose.foundation.BorderStroke(0.5.dp, DividerColor) else null,
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
                                            color = if (selected) androidx.compose.ui.graphics.Color.White else TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Navigation rows ───────────────────────────────────────────
                SettingsRow(
                    icon = Icons.Filled.ShowChart,
                    title = "Performance",
                    subtitle = "Paper trades, signal replay & AI audit",
                    onClick = { navController.navigate("performance") },
                )
                SettingsRow(
                    icon = Icons.Filled.Settings,
                    title = "Data & AI Settings",
                    subtitle = "AI provider keys, data source, Upstox token",
                    onClick = { navController.navigate("settings/model-config") },
                )
                SettingsRow(
                    icon = Icons.Filled.Menu,
                    title = "Session Logs",
                    subtitle = "Network calls, navigation and app events",
                    onClick = { navController.navigate("logs") },
                )
                SettingsRow(
                    icon = Icons.Filled.Info,
                    title = "About NSEAssist",
                    subtitle = "Version, developer info and disclaimer",
                    onClick = { navController.navigate("settings/about") },
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = TextSecondary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                Text(subtitle, fontSize = 12.sp, color = TextSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = TextSecondary)
        }
    }
}
