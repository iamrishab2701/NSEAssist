package com.nseassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings
import com.nseassist.ui.theme.*
import com.nseassist.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(navController: NavController, vm: MainViewModel) {
    val aiSettings by vm.aiSettings.collectAsState()

    AppGradientBackground {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Configuration", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            Text(
                "Bring your own AI key. Keys are saved only on this device and never shared.",
                color = TextSecondary,
                fontSize = 13.sp,
            )
            aiSettings.providers.forEach { config ->
                AiProviderCard(config = config, onSave = vm::upsertAiProvider)
            }
        }
    }
    } // AppGradientBackground
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
