package com.nseassist.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.data.local.AiSettingsStore
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(navController: NavController, vm: MainViewModel) {
    val aiSettings          by vm.aiSettings.collectAsState()
    val testMorningSelloff  by vm.testMorningSelloff.collectAsState()
    val dataSource          by vm.dataSource.collectAsState()
    val upstoxTokenValid    by vm.upstoxTokenValid.collectAsState()
    val exchangingToken     by vm.upstoxExchangingToken.collectAsState()
    val tokenError          by vm.upstoxTokenError.collectAsState()

    AppGradientBackground {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Data & AI Settings", fontWeight = FontWeight.Bold) },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ── Data Source ───────────────────────────────────────────────────
            DataSourceCard(
                dataSource             = dataSource,
                upstoxTokenValid       = upstoxTokenValid,
                exchangingToken        = exchangingToken,
                tokenError             = tokenError,
                onSelectSource         = { vm.saveDataSource(it) },
                onSaveCredentials      = { key, secret -> vm.saveUpstoxCredentials(key, secret) },
                onGetLoginUrl          = { vm.getUpstoxLoginUrl() },
                onExchangeToken        = { vm.exchangeUpstoxToken(it) },
                onClearError           = { vm.clearUpstoxTokenError() },
            )

            Text(
                "Bring your own AI key. Keys are saved only on this device and never shared.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            aiSettings.providers.forEach { config ->
                AiProviderCard(config = config, onSave = vm::upsertAiProvider)
            }

            // ── Developer / Test Settings ─────────────────────────────────────
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Developer Settings", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                    Text("For testing only — not for live trading.", color = TextSecondary, fontSize = 12.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Morning Selloff — Test Mode", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = AmberWarn)
                            Text("Bypass 9:15 AM–12:00 PM time restriction for testing", color = TextSecondary, fontSize = 11.sp)
                        }
                        Switch(
                            checked = testMorningSelloff,
                            onCheckedChange = { vm.setTestMorningSelloff(it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AmberWarn,
                                checkedTrackColor = AmberWarn.copy(alpha = 0.3f),
                            ),
                        )
                    }

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))

                    OutlinedButton(
                        onClick  = { navController.navigate("logs") },
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary),
                    ) {
                        Text("View Session Logs")
                    }
                    Text(
                        "Network calls, navigation, and app events for the current session. Share with developer for debugging.",
                        color = TextSecondary, fontSize = 11.sp,
                    )
                }
            }
        }
    }
    } // AppGradientBackground
}

// ── Data Source Card ─────────────────────────────────────────────────────────────

@Composable
private fun DataSourceCard(
    dataSource: String,
    upstoxTokenValid: Boolean,
    exchangingToken: Boolean,
    tokenError: String?,
    onSelectSource: (String) -> Unit,
    onSaveCredentials: (String, String) -> Unit,
    onGetLoginUrl: () -> String,
    onExchangeToken: (String) -> Unit,
    onClearError: () -> Unit,
) {
    val context = LocalContext.current

    // Local state for Upstox credential fields
    var apiKey     by remember { mutableStateOf("") }
    var apiSecret  by remember { mutableStateOf("") }
    var authCode   by remember { mutableStateOf("") }
    var showSecret by remember { mutableStateOf(false) }
    var credsSaved by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = CardDark),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Market Data Source", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Text(
                "Choose where live stock prices are fetched from.",
                color = TextSecondary,
                fontSize = 12.sp,
            )

            // ── Radio buttons ─────────────────────────────────────────────────
            Column(modifier = Modifier.selectableGroup()) {
                DataSourceOption(
                    label       = "Yahoo Finance",
                    description = "Free · No setup needed · ~15 min delayed",
                    selected    = dataSource == AiSettingsStore.DATA_SOURCE_YAHOO,
                    onClick     = { onSelectSource(AiSettingsStore.DATA_SOURCE_YAHOO) },
                )
                Spacer(Modifier.height(6.dp))
                DataSourceOption(
                    label       = "Upstox",
                    description = "Real-time · Requires Upstox account + daily token",
                    selected    = dataSource == AiSettingsStore.DATA_SOURCE_UPSTOX,
                    onClick     = { onSelectSource(AiSettingsStore.DATA_SOURCE_UPSTOX) },
                )
            }

            // ── Upstox setup (only shown when Upstox is selected) ────────────
            if (dataSource == AiSettingsStore.DATA_SOURCE_UPSTOX) {

                HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))

                // Token status badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (upstoxTokenValid) {
                        Icon(Icons.Default.CheckCircle, null, tint = GreenBull, modifier = Modifier.size(16.dp))
                        Text("Token active for today", color = GreenBull, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Icon(Icons.Default.Warning, null, tint = AmberWarn, modifier = Modifier.size(16.dp))
                        Text("No valid token — activate below", color = AmberWarn, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                // Step 1 — API credentials
                Text(
                    "Step 1 — Save API Credentials (one-time)",
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary,
                )
                Text(
                    "Get your API Key and Secret from developer.upstox.com → My Apps.",
                    color = TextSecondary, fontSize = 11.sp,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text("Upstox API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiSecret,
                    onValueChange = { apiSecret = it.trim() },
                    label = { Text("Upstox API Secret") },
                    singleLine = true,
                    visualTransformation = if (showSecret) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showSecret = !showSecret }) {
                            Text(if (showSecret) "Hide" else "Show", fontSize = 11.sp)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        onSaveCredentials(apiKey, apiSecret)
                        credsSaved = true
                    },
                    enabled = apiKey.isNotBlank() && apiSecret.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
                ) {
                    Text(if (credsSaved) "Credentials Saved ✓" else "Save Credentials")
                }

                HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))

                // Step 2 — Daily token via OAuth
                Text(
                    "Step 2 — Activate Today's Token (every morning)",
                    fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary,
                )
                Text(
                    "1. Tap \"Open Login Page\" → log into Upstox in your browser\n" +
                    "2. After login, the browser shows a URL like:\n" +
                    "   http://127.0.0.1?code=XXXXXX\n" +
                    "3. Copy only the code (the part after code=)\n" +
                    "4. Paste it below and tap Activate Token",
                    color = TextSecondary, fontSize = 11.sp, lineHeight = 17.sp,
                )

                OutlinedButton(
                    onClick = {
                        val url = onGetLoginUrl()
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = BluePrimary),
                ) {
                    Text("Open Login Page in Browser")
                }

                OutlinedTextField(
                    value = authCode,
                    onValueChange = { authCode = it.trim() },
                    label = { Text("Paste auth code here") },
                    placeholder = { Text("e.g. Uz7fR0mExample...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Token error message
                if (tokenError != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = RedBear.copy(alpha = 0.12f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.Warning, null, tint = RedBear, modifier = Modifier.size(16.dp))
                            Text(tokenError, color = RedBear, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = onClearError) { Text("Dismiss", fontSize = 11.sp) }
                        }
                    }
                }

                Button(
                    onClick = {
                        onExchangeToken(authCode)
                        authCode = ""
                    },
                    enabled = authCode.isNotBlank() && !exchangingToken,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = GreenBull),
                ) {
                    if (exchangingToken) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Activating…")
                    } else {
                        Text("Activate Token")
                    }
                }

                Text(
                    "⚠ A new token is required every morning before you start trading.",
                    color = AmberWarn.copy(alpha = 0.8f), fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun DataSourceOption(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = null,   // handled by Row's selectable
            colors = RadioButtonDefaults.colors(selectedColor = BluePrimary),
        )
        Column {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
            Text(description, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AiProviderCard(
    config: AiProviderConfig,
    onSave: (AiProvider, String, String) -> Unit,
) {
    var apiKey by remember(config.provider, config.apiKey) { mutableStateOf(config.apiKey) }
    var model  by remember(config.provider, config.model)  { mutableStateOf(config.model) }

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
                colors = ButtonDefaults.buttonColors(containerColor = BluePrimary),
            ) {
                Text(if (config.apiKey.isBlank()) "Save ${config.provider.label}" else "Update ${config.provider.label}")
            }
        }
    }
}
