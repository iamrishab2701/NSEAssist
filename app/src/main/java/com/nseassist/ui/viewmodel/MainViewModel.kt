package com.nseassist.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nseassist.NSEAssistApp
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.StockData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as NSEAssistApp).repository

    // ── Market Overview ──────────────────────────────────────────────────────────
    private val _marketOverview = MutableStateFlow<UiState<MarketOverview>>(UiState.Loading)
    val marketOverview: StateFlow<UiState<MarketOverview>> = _marketOverview

    // ── Scan Results ─────────────────────────────────────────────────────────────
    private val _scanResults = MutableStateFlow<UiState<List<StockData>>>(UiState.Loading)
    val scanResults: StateFlow<UiState<List<StockData>>> = _scanResults

    // ── Stock Detail ─────────────────────────────────────────────────────────────
    private val _stockDetail = MutableStateFlow<UiState<StockData>>(UiState.Loading)
    val stockDetail: StateFlow<UiState<StockData>> = _stockDetail

    init {
        loadMarketOverview()
    }

    fun loadMarketOverview() {
        viewModelScope.launch {
            _marketOverview.value = UiState.Loading
            _marketOverview.value = repo.getMarketOverview()
                .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(it.message ?: "Network error") })
        }
    }

    fun scanStocks(capital: Double) {
        viewModelScope.launch {
            _scanResults.value = UiState.Loading
            _scanResults.value = repo.scanAffordableStocks(capital)
                .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(it.message ?: "Scan failed") })
        }
    }

    fun loadStockDetail(symbol: String) {
        viewModelScope.launch {
            _stockDetail.value = UiState.Loading
            _stockDetail.value = repo.analyseStock(symbol)
                .fold(onSuccess = { UiState.Success(it) }, onFailure = { UiState.Error(it.message ?: "Analysis failed") })
        }
    }
}
