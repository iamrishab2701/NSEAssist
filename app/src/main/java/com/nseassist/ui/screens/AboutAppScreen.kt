package com.nseassist.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.nseassist.BuildConfig
import com.nseassist.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutAppScreen(navController: NavController) {
    AppGradientBackground {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About App", fontWeight = FontWeight.Bold) },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AboutRow("App",       "NSE Assist")
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))
                    AboutRow("Version",   "v${BuildConfig.VERSION_NAME}")
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))
                    AboutRow("Developer", "Rishab Singh")
                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f))
                    AboutRow("Contact",   "singhrishab166@gmail.com", valueColor = BluePrimary)
                }
            }

            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Disclaimer", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                    Text(
                        "NSE Assist is built for personal intraday trading use only. It is not SEBI-registered financial advice. " +
                        "All trading decisions and risks are solely the user's responsibility. " +
                        "Never trade money you cannot afford to lose.",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
    } // AppGradientBackground
}

@Composable
private fun AboutRow(
    label: String,
    value: String,
    valueColor: Color = Color.Unspecified,   // Color.Unspecified → defaults to TextPrimary
) {
    val effectiveColor = if (valueColor == Color.Unspecified) TextPrimary else valueColor
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Text(value, color = effectiveColor, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
