package com.nseassist.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nseassist.NSEAssistApp
import com.nseassist.data.model.AiAnalysisReport
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.NewsResult
import com.nseassist.data.model.ScanCategory
import com.nseassist.data.model.SingleStockAiAnalysis
import com.nseassist.data.model.StockData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String) : UiState<Nothing>()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appContainer = app as NSEAssistApp
    private val repo = appContainer.repository
    private val aiSettingsStore = appContainer.aiSettingsStore
    private val aiAnalysisService = appContainer.aiAnalysisService

    // ── Market Overview ──────────────────────────────────────────────────────────
    private val _marketOverview = MutableStateFlow<UiState<MarketOverview>>(UiState.Loading)
    val marketOverview: StateFlow<UiState<MarketOverview>> = _marketOverview

    // ── Scan Results ─────────────────────────────────────────────────────────────
    private val _scanResults = MutableStateFlow<UiState<List<StockData>>>(UiState.Loading)
    val scanResults: StateFlow<UiState<List<StockData>>> = _scanResults

    // ── Stock Detail ─────────────────────────────────────────────────────────────
    private val _stockDetail = MutableStateFlow<UiState<StockData>>(UiState.Loading)
    val stockDetail: StateFlow<UiState<StockData>> = _stockDetail

    // ── Stock News — loaded independently so technical data shows instantly ───────
    private val _stockNews = MutableStateFlow<UiState<NewsResult?>>(UiState.Loading)
    val stockNews: StateFlow<UiState<NewsResult?>> = _stockNews

    // ── Capital — saved when user scans, pre-fills single-stock AI analysis ───────
    private val _capital = MutableStateFlow(0.0)
    val capital: StateFlow<Double> = _capital

    private val _aiSettings = MutableStateFlow(aiSettingsStore.load())
    val aiSettings: StateFlow<AiSettings> = _aiSettings

    private val _aiAnalysis = MutableStateFlow<UiState<AiAnalysisReport>?>(null)
    val aiAnalysis: StateFlow<UiState<AiAnalysisReport>?> = _aiAnalysis

    // ── Single Stock AI Analysis (Stock Detail screen) ────────────────────────────
    private val _singleStockAnalysis = MutableStateFlow<UiState<SingleStockAiAnalysis>?>(null)
    val singleStockAnalysis: StateFlow<UiState<SingleStockAiAnalysis>?> = _singleStockAnalysis

    // ── Deep Enrich Progress (phase 2 of scan — null when idle) ─────────────────
    private val _deepEnrichProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val deepEnrichProgress: StateFlow<Pair<Int, Int>?> = _deepEnrichProgress

    // ── Deep Export State ────────────────────────────────────────────────────────
    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState

    // ── Scan result cache — prevents re-scan on back-navigation ──────────────────
    // When user returns from StockDetail → ScanScreen, LaunchedEffect re-fires.
    // Guard here means: same capital + category within 5 min → instant return, no re-fetch.
    private var scanCacheCapital:   Double       = -1.0
    private var scanCacheCategory:  ScanCategory? = null
    private var scanCacheTimestamp: Long          = 0L
    private val SCAN_CACHE_TTL = 5 * 60 * 1000L   // 5 minutes

    // ── Stock detail cache — back from news/chart sub-screen is instant ───────────
    private var detailCacheSymbol:    String = ""
    private var detailCacheTimestamp: Long   = 0L
    private val DETAIL_CACHE_TTL = 5 * 60 * 1000L // 5 minutes

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

    fun scanStocks(capital: Double, category: ScanCategory) {
        _capital.value = capital   // store so single-stock AI analysis can pre-fill it
        val now = System.currentTimeMillis()
        val cached = capital == scanCacheCapital &&
                     category == scanCacheCategory &&
                     (now - scanCacheTimestamp) < SCAN_CACHE_TTL &&
                     _scanResults.value.let { it is UiState.Success && (it as UiState.Success).data.isNotEmpty() }
        if (cached) return   // back-navigation: results still fresh — skip re-scan

        // Lock cache immediately so concurrent calls from the same recomposition don't double-scan
        scanCacheCapital   = capital
        scanCacheCategory  = category
        scanCacheTimestamp = now

        viewModelScope.launch {
            _scanResults.value = UiState.Loading
            _deepEnrichProgress.value = null
            repo.scanAffordableStocks(capital, category).fold(
                onSuccess = { phase1List ->
                    // Phase 1: show the list immediately with quick scores
                    _scanResults.value = UiState.Success(phase1List)
                    // Phase 2: deep-analyse top 15 in background, update list live
                    deepEnrichScanResults(phase1List)
                },
                onFailure = { _scanResults.value = UiState.Error(it.message ?: "Scan failed") },
            )
        }
    }

    /**
     * Phase 2: enrich the top 15 stocks with full technical indicators.
     *
     * Optimisations vs the old approach:
     *   • Uses analyseStockFromPhase1() — skips quote re-fetch (Phase 1 data reused)
     *   • Runs all 15 in parallel (no chunked batches) — time = max(single stock) not sum
     *   • 60-day history instead of 90 — enough for all indicators, faster payload
     *   • Profile cached — fetched once per session per symbol
     *   • NIFTY condition cached — fetched once, shared across all 15 stocks
     *   • Progress updates per-stock as each one finishes (not at batch boundaries)
     */
    private suspend fun deepEnrichScanResults(phase1List: List<StockData>) {
        val top15 = phase1List.sortedByDescending { it.score }.take(15)
        if (top15.isEmpty()) return

        // ConcurrentHashMap — safe for simultaneous updates from 15 parallel coroutines
        val stockMap = ConcurrentHashMap(phase1List.associateBy { it.symbol })
        val done     = AtomicInteger(0)
        _deepEnrichProgress.value = 0 to top15.size

        coroutineScope {
            top15.map { stock ->
                async {
                    val enriched = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                        repo.analyseStockFromPhase1(stock).getOrNull()
                    } ?: stock   // fall back to Phase-1 version on timeout/error

                    stockMap[enriched.symbol] = enriched
                    _deepEnrichProgress.value = done.incrementAndGet() to top15.size
                    // Re-emit after every single stock — progress feels instant
                    _scanResults.value = UiState.Success(
                        stockMap.values.sortedByDescending { it.score }
                    )
                }
            }.awaitAll()
        }

        _deepEnrichProgress.value = null  // done — banner disappears
    }

    fun loadStockDetail(symbol: String) {
        val now = System.currentTimeMillis()

        // Priority 1: stock already deep-enriched in Phase 2 — show instantly,
        // then load news separately (news is never fetched during Phase 2 batch scan)
        val enrichedFromScan = (_scanResults.value as? UiState.Success)?.data
            ?.firstOrNull { it.symbol == symbol && it.isDeepEnriched }
        if (enrichedFromScan != null) {
            _stockDetail.value   = UiState.Success(enrichedFromScan)
            detailCacheSymbol    = symbol
            detailCacheTimestamp = now
            loadNewsForStock(enrichedFromScan)
            return
        }

        // Priority 2: same stock re-opened within 5 min — technical data instant,
        // but always refresh news (intraday news can change any minute)
        val cached = symbol == detailCacheSymbol &&
                     (now - detailCacheTimestamp) < DETAIL_CACHE_TTL &&
                     _stockDetail.value is UiState.Success
        if (cached) {
            (_stockDetail.value as? UiState.Success)?.data?.let { loadNewsForStock(it) }
            return
        }

        // Priority 3: fresh network fetch (stock NOT in Phase 2 top-15)
        detailCacheSymbol    = symbol
        detailCacheTimestamp = now
        viewModelScope.launch {
            _stockDetail.value = UiState.Loading
            val result = repo.analyseStock(symbol, fetchNews = false)  // technical only — fast
            _stockDetail.value = result.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Analysis failed") }
            )
            // After technical data is shown, fetch news in background
            result.getOrNull()?.let { loadNewsForStock(it) }
        }
    }

    /** Fetches news independently — never blocks technical data display. */
    fun loadNewsForStock(stock: StockData) {
        _stockNews.value = UiState.Loading
        viewModelScope.launch {
            val news = repo.fetchNewsForStock(
                symbol   = stock.symbol,
                name     = stock.name,
                sector   = stock.sector,
                industry = stock.industry,
            )
            _stockNews.value = UiState.Success(news)
        }
    }

    fun upsertAiProvider(provider: AiProvider, apiKey: String, model: String) {
        val updated = _aiSettings.value.providers.map { config ->
            if (config.provider == provider) {
                AiProviderConfig(provider = provider, apiKey = apiKey, model = model)
            } else {
                config
            }
        }
        updated.firstOrNull { it.provider == provider }?.let(aiSettingsStore::save)
        _aiSettings.value = AiSettings(updated)
    }

    fun analyzeStocksWithAi(provider: AiProvider, capital: Double, category: ScanCategory, stocks: List<StockData>) {
        val config = _aiSettings.value.providers.firstOrNull { it.provider == provider && it.apiKey.isNotBlank() }
        if (config == null) {
            _aiAnalysis.value = UiState.Error("Add ${provider.label} API key in Settings first")
            return
        }

        viewModelScope.launch {
            _aiAnalysis.value = UiState.Loading
            _aiAnalysis.value = aiAnalysisService.analyzeStocks(config, capital, category, stocks)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it.message ?: "AI analysis failed") },
                )
        }
    }

    /** Force a fresh scan regardless of cache — call this from a "Refresh" button. */
    fun forceRescan(capital: Double, category: ScanCategory) {
        scanCacheTimestamp = 0L   // invalidate cache
        scanStocks(capital, category)
    }

    fun clearAiAnalysis() {
        _aiAnalysis.value = null
    }

    /**
     * Run a deep single-stock AI analysis for the stock currently shown on StockDetailScreen.
     * Uses all technical data + live news already loaded on that screen.
     */
    fun analyzeCurrentStockWithAi(provider: AiProvider, capital: Double) {
        val stock = (_stockDetail.value as? UiState.Success)?.data ?: return
        val news  = (_stockNews.value  as? UiState.Success)?.data

        val config = _aiSettings.value.providers
            .firstOrNull { it.provider == provider && it.apiKey.isNotBlank() }
        if (config == null) {
            _singleStockAnalysis.value = UiState.Error("Add ${provider.label} API key in Settings → Model Configuration first")
            return
        }

        viewModelScope.launch {
            _singleStockAnalysis.value = UiState.Loading
            _singleStockAnalysis.value = aiAnalysisService
                .analyzeSingleStock(config, capital, stock, news)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it.message ?: "AI analysis failed") },
                )
        }
    }

    fun clearSingleStockAnalysis() {
        _singleStockAnalysis.value = null
    }

    // ── Shared deep enrichment for top 20 stocks ─────────────────────────────────
    // Smart: skips stocks already deep-enriched by Phase 2 of the scan.
    // Example: Phase 2 analysed top 15 → AI needs top 20 → only 5 are fetched, not 20.
    private suspend fun enrichTop20(stocks: List<StockData>, onProgress: (Int, Int) -> Unit): List<StockData> {
        val top20 = stocks.sortedByDescending { it.score }.take(20)

        // isDeepEnriched = true means analyseStock() already ran (Phase 2 or detail screen)
        // Using this flag — NOT priceHistory — because history can be empty even after Phase 2
        // (e.g. Yahoo returned no data for that stock), which would wrongly trigger a re-fetch.
        val alreadyDone    = top20.filter { it.isDeepEnriched }
        val needEnrichment = top20.filter { !it.isDeepEnriched }

        // CopyOnWriteArrayList — thread-safe for concurrent adds from parallel coroutines
        val result = java.util.concurrent.CopyOnWriteArrayList(alreadyDone)
        var done   = alreadyDone.size

        if (needEnrichment.isEmpty()) {
            // All top 20 were already enriched by Phase 2 — nothing to fetch
            onProgress(top20.size, top20.size)
        } else {
            val doneAtomic = AtomicInteger(done)
            onProgress(done, top20.size)
            coroutineScope {
                needEnrichment.map { stock ->
                    async {
                        val enriched = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                            repo.analyseStockFromPhase1(stock).getOrNull()
                        } ?: stock
                        result.add(enriched)
                        onProgress(doneAtomic.incrementAndGet(), top20.size)
                    }
                }.awaitAll()
            }
        }

        val enrichedSymbols = top20.map { it.symbol }.toSet()
        val remaining = stocks.filter { it.symbol !in enrichedSymbols }
        return result + remaining
    }

    fun prepareDeepExport(capital: Double, category: ScanCategory, stocks: List<StockData>, onReady: (List<StockData>) -> Unit) {
        viewModelScope.launch {
            _exportState.value = ExportState.Preparing(0, minOf(20, stocks.size))
            val finalList = enrichTop20(stocks) { done, total ->
                _exportState.value = ExportState.Preparing(done, total)
            }
            _exportState.value = ExportState.Idle
            onReady(finalList)
        }
    }

    fun prepareDeepAiAnalysis(provider: AiProvider, capital: Double, category: ScanCategory, stocks: List<StockData>) {
        val config = _aiSettings.value.providers.firstOrNull { it.provider == provider && it.apiKey.isNotBlank() }
        if (config == null) {
            _aiAnalysis.value = UiState.Error("Add ${provider.label} API key in Settings first")
            return
        }

        viewModelScope.launch {
            _aiAnalysis.value = UiState.Loading
            _exportState.value = ExportState.Preparing(0, minOf(20, stocks.size))

            val enrichedStocks = enrichTop20(stocks) { done, total ->
                _exportState.value = ExportState.Preparing(done, total)
            }
            _exportState.value = ExportState.Idle

            _aiAnalysis.value = aiAnalysisService.analyzeStocks(config, capital, category, enrichedStocks)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = { UiState.Error(it.message ?: "AI analysis failed") },
                )
        }
    }

    fun clearExportState() {
        _exportState.value = ExportState.Idle
    }
}

sealed class ExportState {
    object Idle : ExportState()
    data class Preparing(val done: Int, val total: Int) : ExportState()
}
