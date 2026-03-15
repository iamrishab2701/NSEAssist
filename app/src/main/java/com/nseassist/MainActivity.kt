package com.nseassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nseassist.data.model.ScanCategory
import com.nseassist.ui.screens.AboutAppScreen
import com.nseassist.ui.screens.AiReportScreen
import com.nseassist.ui.screens.HomeScreen
import com.nseassist.ui.screens.ModelConfigScreen
import com.nseassist.ui.screens.ScanScreen
import com.nseassist.ui.screens.StockDetailScreen
import com.nseassist.ui.theme.NSEAssistTheme
import com.nseassist.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    // Lift VM to activity level so themeMode can drive NSEAssistTheme,
    // which wraps the entire Compose content tree.
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by vm.themeMode.collectAsState()
            NSEAssistTheme(themeMode = themeMode) {
                val navController = rememberNavController()
                AppNavHost(navController, vm)
            }
        }
    }
}

@Composable
fun AppNavHost(navController: NavHostController, vm: MainViewModel) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController, vm)
        }
        composable("scan/{capital}/{category}") { backStackEntry ->
            val capital = backStackEntry.arguments?.getString("capital")?.toDoubleOrNull() ?: 0.0
            val category = ScanCategory.fromRouteValue(backStackEntry.arguments?.getString("category"))
            ScanScreen(navController, capital, category, vm)
        }
        composable("stock/{symbol}") { backStackEntry ->
            val symbol = backStackEntry.arguments?.getString("symbol") ?: ""
            StockDetailScreen(navController, symbol, vm)
        }
        composable("ai-report") {
            AiReportScreen(navController, vm)
        }
        composable("settings/model-config") {
            ModelConfigScreen(navController, vm)
        }
        composable("settings/about") {
            AboutAppScreen(navController)
        }
    }
}
