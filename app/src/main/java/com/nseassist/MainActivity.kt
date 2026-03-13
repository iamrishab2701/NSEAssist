package com.nseassist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nseassist.ui.screens.HomeScreen
import com.nseassist.ui.screens.ScanScreen
import com.nseassist.ui.screens.StockDetailScreen
import com.nseassist.ui.theme.NSEAssistTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NSEAssistTheme {
                val navController = rememberNavController()
                AppNavHost(navController)
            }
        }
    }
}

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(navController)
        }
        composable("scan/{capital}") { backStackEntry ->
            val capital = backStackEntry.arguments?.getString("capital")?.toDoubleOrNull() ?: 0.0
            ScanScreen(navController, capital)
        }
        composable("stock/{symbol}") { backStackEntry ->
            val symbol = backStackEntry.arguments?.getString("symbol") ?: ""
            StockDetailScreen(navController, symbol)
        }
    }
}
