package com.nseassist.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nseassist.NSEAssistApp
import com.nseassist.analysis.SignalReplay
import com.nseassist.data.api.GlobalTrendsClient
import com.nseassist.data.api.MemoryClient
import com.nseassist.data.api.UpstoxClient
import com.nseassist.data.local.AiSettingsStore
import com.nseassist.util.AppLogger
import com.nseassist.data.local.ThemeMode
import com.nseassist.data.model.AiAnalysisReport
import com.nseassist.data.model.AiProvider
import com.nseassist.data.model.AiProviderConfig
import com.nseassist.data.model.AiSettings
import com.nseassist.data.model.GlobalTrendsData
import com.nseassist.data.model.MarketOverview
import com.nseassist.data.model.NewsResult
import com.nseassist.data.model.PaperTradeEntry
import com.nseassist.data.model.PaperTradeRequest
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
    private val themeStore = appContainer.themeStore

    // ── Market Overview ──────────────────────────────────────────────────────────
    private val _marketOverview = MutableStateFlow<UiState<MarketOverview>>(UiState.Loading)
    val marketOverview: StateFlow<UiState<MarketOverview>> = _marketOverview

    // ── Scan Results ─────────────────────────────────────────────────────────────
    private val _scanResults = MutableStateFlow<UiState<List<StockData>>>(UiState.Loading)
    val scanResults: StateFlow<UiState<List<StockData>>> = _scanResults

    // ── Stock Detail ─────────────────────────────────────────────────────────────
    private val _stockDetail = MutableStateFlow<UiState<StockData>>(UiState.Loading)
    val stockDetail: StateFlow<UiState<StockData>> = _stockDetail

    // ── Global Trends ─────────────────────────────────────────────────────────────
    private val _globalTrends = MutableStateFlow<UiState<GlobalTrendsData>>(UiState.Loading)
    val globalTrends: StateFlow<UiState<GlobalTrendsData>> = _globalTrends
    private var lastTrendsRefreshTime: Long = 0L
    private val TRENDS_REFRESH_COOLDOWN = 5_000L // 5 seconds between manual refreshes
    private var trendsLoadStarted = false  // true once first load has been kicked off

    // ── Stock News — loaded independently so technical data shows instantly ───────
    private val _stockNews = MutableStateFlow<UiState<NewsResult?>>(UiState.Loading)
    val stockNews: StateFlow<UiState<NewsResult?>> = _stockNews

    // ── Capital — saved when user scans, pre-fills single-stock AI analysis ───────
    private val _capital = MutableStateFlow(0.0)
    val capital: StateFlow<Double> = _capital

    private val _aiSettings = MutableStateFlow(aiSettingsStore.load())
    val aiSettings: StateFlow<AiSettings> = _aiSettings

    private val _testMorningSelloff = MutableStateFlow(aiSettingsStore.loadTestMorningSelloff())
    val testMorningSelloff: StateFlow<Boolean> = _testMorningSelloff

    // ── Data Source (Yahoo Finance / Upstox) ─────────────────────────────────────
    private val _dataSource = MutableStateFlow(aiSettingsStore.loadDataSource())
    val dataSource: StateFlow<String> = _dataSource

    // ── Upstox token status ──────────────────────────────────────────────────────
    // true  = a valid token exists for today (IST date matches)
    // false = no token or token is from a previous day
    private val _upstoxTokenValid = MutableStateFlow(aiSettingsStore.isUpstoxTokenValidToday())
    val upstoxTokenValid: StateFlow<Boolean> = _upstoxTokenValid

    // true while the token exchange network call is in progress
    private val _upstoxExchangingToken = MutableStateFlow(false)
    val upstoxExchangingToken: StateFlow<Boolean> = _upstoxExchangingToken

    // Non-null when token exchange fails — shown as an error in the UI
    private val _upstoxTokenError = MutableStateFlow<String?>(null)
    val upstoxTokenError: StateFlow<String?> = _upstoxTokenError

    // ── Theme mode — persisted preference, drives NSEAssistTheme ─────────────────
    private val _themeMode = MutableStateFlow(themeStore.load())
    val themeMode: StateFlow<ThemeMode> = _themeMode

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

    // ── RTT / ORB / Oversold cache — same guard as scanStocks ────────────────────
    private var rttCacheCapital:   Double        = -1.0
    private var rttCacheCategory:  ScanCategory? = null
    private var rttCacheMode:      Boolean       = false
    private var rttCacheTimestamp: Long          = 0L

    private var oversoldCacheCapital:   Double = -1.0
    private var oversoldCacheTimestamp: Long   = 0L

    // ── Stock detail cache — back from news/chart sub-screen is instant ───────────
    private var detailCacheSymbol:    String = ""
    private var detailCacheTimestamp: Long   = 0L
    private val DETAIL_CACHE_TTL = 45_000L // 45 sec — fast for AI-report back-nav, forces re-fetch on re-search
    // Tracks in-flight symbol to block duplicate fetches that slip past the state check
    private var detailFetchInFlightSymbol: String? = null

    init {
        // Apply persisted data source + credentials to repo immediately on start
        val persistedSource = aiSettingsStore.loadDataSource()
        repo.dataSource = persistedSource
        val upstoxValid = aiSettingsStore.isUpstoxTokenValidToday()
        if (upstoxValid) {
            repo.upstoxAccessToken = aiSettingsStore.loadUpstoxAccessToken()
        }
        AppLogger.i("INIT", "Session start — dataSource=$persistedSource upstoxTokenValid=$upstoxValid")
        // When Upstox returns 401, update UI state so the banner shows immediately
        repo.onUpstoxTokenExpired = {
            _upstoxTokenValid.value = false
            AppLogger.w("UPSTOX", "Token expired — banner shown")
        }
        loadMarketOverview()
    }

    // ── Data Source ──────────────────────────────────────────────────────────────

    /** Persists the chosen data source and updates the repository immediately. */
    fun saveDataSource(source: String) {
        aiSettingsStore.saveDataSource(source)
        _dataSource.value = source
        repo.dataSource = source
        if (source != AiSettingsStore.DATA_SOURCE_UPSTOX) {
            repo.upstoxAccessToken = ""
        } else if (aiSettingsStore.isUpstoxTokenValidToday()) {
            repo.upstoxAccessToken = aiSettingsStore.loadUpstoxAccessToken()
        }
    }

    // ── Upstox Auth ──────────────────────────────────────────────────────────────

    /** Returns the Upstox OAuth2 login URL to open in the user's browser. */
    fun getUpstoxLoginUrl(): String {
        val apiKey = aiSettingsStore.loadUpstoxApiKey()
        return UpstoxClient.getLoginUrl(apiKey)
    }

    /** Saves Upstox API Key + Secret to SharedPreferences. */
    fun saveUpstoxCredentials(apiKey: String, apiSecret: String) {
        aiSettingsStore.saveUpstoxApiKey(apiKey)
        aiSettingsStore.saveUpstoxApiSecret(apiSecret)
    }

    /** Exchanges the auth code (copied from the browser redirect URL) for today's access token. */
    fun exchangeUpstoxToken(authCode: String) {
        viewModelScope.launch {
            _upstoxExchangingToken.value = true
            _upstoxTokenError.value = null
            try {
                val apiKey    = aiSettingsStore.loadUpstoxApiKey()
                val apiSecret = aiSettingsStore.loadUpstoxApiSecret()
                if (apiKey.isBlank() || apiSecret.isBlank()) {
                    _upstoxTokenError.value = "Save your API Key and Secret first"
                    return@launch
                }
                val token = UpstoxClient.exchangeToken(apiKey, apiSecret, authCode)
                aiSettingsStore.saveUpstoxAccessToken(token)
                repo.upstoxAccessToken = token
                _upstoxTokenValid.value = true
                AppLogger.d("UPSTOX", "Token activated successfully")
            } catch (e: Exception) {
                _upstoxTokenError.value = e.message ?: "Token activation failed"
                AppLogger.e("UPSTOX", "Token exchange failed: ${e.message}")
            } finally {
                _upstoxExchangingToken.value = false
            }
        }
    }

    /** Clears the Upstox token error message (call after user dismisses it). */
    fun clearUpstoxTokenError() { _upstoxTokenError.value = null }

    fun loadMarketOverview() {
        viewModelScope.launch {
            _marketOverview.value = UiState.Loading
            _marketOverview.value = repo.getMarketOverview()
                .fold(
                    onSuccess = {
                        AppLogger.d("MARKET", "Overview loaded — NIFTY=${String.format("%.2f", it.nifty50ChangePct)}% BankNIFTY=${String.format("%.2f", it.bankNiftyChangePct)}% gainers=${it.topGainers.size} losers=${it.topLosers.size} status=${it.marketStatus}")
                        UiState.Success(it)
                    },
                    onFailure = {
                        AppLogger.e("MARKET", "Overview failed: ${it.message}")
                        UiState.Error(it.message ?: "Network error")
                    },
                )
        }
    }

    fun loadGlobalTrends() {
        // Skip if already loaded or if a fetch has already been kicked off (prevents duplicate
        // fetches from multiple LaunchedEffect fires before the first request completes)
        if (_globalTrends.value is UiState.Success || trendsLoadStarted) {
            AppLogger.d("MARKET", "Global trends cache hit or in-flight — skipping fetch")
            return
        }
        trendsLoadStarted = true
        viewModelScope.launch {
            _globalTrends.value = UiState.Loading
            val data = GlobalTrendsClient.fetchGlobalTrends()
            if (data != null) {
                AppLogger.d("MARKET", "Global trends loaded — bias=${data.overallBias} commodities=${data.commodities.size} forex=${data.forex.size} impacts=${data.impacts.size}")
                _globalTrends.value = UiState.Success(data)
            } else {
                AppLogger.e("MARKET", "Global trends fetch failed")
                _globalTrends.value = UiState.Error("Failed to load global trends")
            }
        }
    }

    fun refreshGlobalTrends() {
        val now = System.currentTimeMillis()
        if (_globalTrends.value is UiState.Loading || (now - lastTrendsRefreshTime) < TRENDS_REFRESH_COOLDOWN) {
            AppLogger.d("MARKET", "Global trends — throttled (cooldown or in-flight), skipping")
            return
        }
        lastTrendsRefreshTime = now
        viewModelScope.launch {
            AppLogger.d("MARKET", "Global trends refresh triggered")
            _globalTrends.value = UiState.Loading
            val data = GlobalTrendsClient.fetchGlobalTrends()
            if (data != null) {
                AppLogger.d("MARKET", "Global trends refreshed — bias=${data.overallBias} commodities=${data.commodities.size} forex=${data.forex.size} impacts=${data.impacts.size}")
                _globalTrends.value = UiState.Success(data)
            } else {
                AppLogger.e("MARKET", "Global trends refresh failed")
                _globalTrends.value = UiState.Error("Failed to load global trends")
            }
        }
    }

    // ── Ready to Trade ─────────────────────────────────────────────────────────
    private val _readyToTradeResults = MutableStateFlow<UiState<List<StockData>>>(UiState.Loading)
    val readyToTradeResults: StateFlow<UiState<List<StockData>>> = _readyToTradeResults

    private val _readyToTradeProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val readyToTradeProgress: StateFlow<Pair<Int, Int>?> = _readyToTradeProgress

    fun scanReadyToTrade(capital: Double, category: ScanCategory, orbBreakoutMode: Boolean = false) {
        _capital.value = capital
        val mode = if (orbBreakoutMode) "ORB" else "ReadyToTrade"
        val now = System.currentTimeMillis()
        val cached = capital == rttCacheCapital &&
                     category == rttCacheCategory &&
                     orbBreakoutMode == rttCacheMode &&
                     (now - rttCacheTimestamp) < SCAN_CACHE_TTL &&
                     _readyToTradeResults.value.let { it is UiState.Success && (it as UiState.Success).data.isNotEmpty() }
        if (cached) {
            val ageS  = (now - rttCacheTimestamp) / 1000
            val count = (_readyToTradeResults.value as? UiState.Success)?.data?.size ?: 0
            AppLogger.d("SCAN", "$mode cache hit — ${count} stocks, age=${ageS}s (TTL=${SCAN_CACHE_TTL/1000}s)")
            return
        }
        rttCacheCapital   = capital
        rttCacheCategory  = category
        rttCacheMode      = orbBreakoutMode
        rttCacheTimestamp = now

        AppLogger.d("SCAN", "$mode start — capital=₹$capital category=${category.routeValue}")
        viewModelScope.launch {
            _readyToTradeResults.value = UiState.Loading
            _readyToTradeProgress.value = null

            repo.scanAffordableStocks(capital, category).fold(
                onSuccess = { phase1List ->
                    // "Ready to Trade" (orbBreakoutMode): pick stocks that are moving
                    // positively today but haven't already made their big move, AND are
                    // still sitting near their day high right now (momentum still active).
                    // Avoids chasing stocks that spiked at 9:30 AM and then faded.
                    //
                    //   changePct in 0.5..5.0  — green but not overextended
                    //   ltp ≥ dayHigh × 0.97   — within 3% of day high = still running
                    //
                    // "Ready for Breakouts" keeps the plain score-based sort.
                    val top20 = if (orbBreakoutMode)
                        phase1List
                            .filter { it.changePct in 0.5..5.0 }
                            .filter { it.dayHigh > 0 && it.ltp >= it.dayHigh * 0.97 }
                            .sortedByDescending { it.score }
                            .take(20)
                    else
                        phase1List.sortedByDescending { it.score }.take(20)
                    AppLogger.d("SCAN", "$mode phase1=${phase1List.size} → top20=${top20.size}")
                    if (top20.isEmpty()) {
                        AppLogger.d("SCAN", "$mode — no candidates after filter")
                        _readyToTradeResults.value = UiState.Success(emptyList())
                        return@fold
                    }
                    val stockMap = ConcurrentHashMap(top20.associateBy { it.symbol })
                    val done = AtomicInteger(0)
                    _readyToTradeProgress.value = 0 to top20.size

                    coroutineScope {
                        top20.map { stock ->
                            async {
                                val enriched = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                                    repo.analyseStockFromPhase1(stock, orbBreakoutMode = orbBreakoutMode).getOrNull()
                                } ?: stock
                                stockMap[enriched.symbol] = enriched
                                _readyToTradeProgress.value = done.incrementAndGet() to top20.size
                                // Sort: BUY first, then WAIT, then AVOID
                                _readyToTradeResults.value = UiState.Success(
                                    stockMap.values.sortedWith(compareBy { quickTakeRank(it) })
                                )
                            }
                        }.awaitAll()
                    }
                    _readyToTradeProgress.value = null
                },
                onFailure = {
                    AppLogger.e("SCAN", "$mode failed: ${it.message}")
                    _readyToTradeResults.value = UiState.Error(it.message ?: "Scan failed")
                },
            )
        }
    }

    /**
     * Morning Selloff scan — Large & Mid Cap stocks that have been heavily sold
     * in the morning session (50–70% of avg daily volume already traded, price down,
     * below VWAP). News is fetched for each stock to filter out bad-news selloffs
     * (falling knife risk). Only meaningful 9:15 AM – 12:00 PM IST.
     */
    fun scanOversoldBounce(capital: Double) {
        _capital.value = capital
        val now = System.currentTimeMillis()
        val cached = capital == oversoldCacheCapital &&
                     (now - oversoldCacheTimestamp) < SCAN_CACHE_TTL &&
                     _readyToTradeResults.value.let { it is UiState.Success && (it as UiState.Success).data.isNotEmpty() }
        if (cached) {
            val ageS  = (now - oversoldCacheTimestamp) / 1000
            val count = (_readyToTradeResults.value as? UiState.Success)?.data?.size ?: 0
            AppLogger.d("SCAN", "MorningSelloff cache hit — ${count} stocks, age=${ageS}s (TTL=${SCAN_CACHE_TTL/1000}s)")
            return
        }
        oversoldCacheCapital   = capital
        oversoldCacheTimestamp = now

        viewModelScope.launch {
            _readyToTradeResults.value = UiState.Loading
            _readyToTradeProgress.value = null

            // Use a high cap (₹100,000) so all large/mid cap stocks are included regardless
            // of the user's actual capital — capital is only used for quantity sizing later
            repo.scanAffordableStocks(100_000.0, ScanCategory.ALL).fold(
                onSuccess = { phase1List ->
                    val f1 = phase1List.filter { it.changePct <= -4.0 }
                    val f2 = f1.filter { !it.aboveVwap }
                    val f3 = f2.filter { it.avgVolume > 0 && it.volume.toDouble() / it.avgVolume >= 0.50 }  // 50%+ of avg daily volume already traded
                    val f4 = f3.filter { it.ltp <= capital }              // must be affordable with user's capital
                    AppLogger.d("SCAN", "MorningSelloff total=${phase1List.size} down4%=${f1.size} belowVwap=${f2.size} vol50%=${f3.size} affordable=${f4.size}")

                    val top20 = f4.sortedBy { it.changePct }.take(20)

                    if (top20.isEmpty()) {
                        _readyToTradeResults.value = UiState.Success(emptyList())
                        return@fold
                    }

                    val stockMap = ConcurrentHashMap(top20.associateBy { it.symbol })
                    val done = AtomicInteger(0)
                    _readyToTradeProgress.value = 0 to top20.size

                    coroutineScope {
                        top20.map { stock ->
                            async {
                                val enriched = kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                                    // fetchNews = true — filter out bad-news selloffs
                                    repo.analyseStockFromPhase1(stock, fetchNews = true, orbBreakoutMode = false).getOrNull()
                                } ?: stock
                                stockMap[enriched.symbol] = enriched
                                _readyToTradeProgress.value = done.incrementAndGet() to top20.size
                                _readyToTradeResults.value = UiState.Success(
                                    stockMap.values.sortedWith(compareBy { oversoldRank(it) })
                                )
                            }
                        }.awaitAll()
                    }
                    _readyToTradeProgress.value = null
                },
                onFailure = {
                    AppLogger.e("SCAN", "MorningSelloff failed: ${it.message}")
                    _readyToTradeResults.value = UiState.Error(it.message ?: "Scan failed")
                },
            )
        }
    }

    /** Sort oversold results: bounce candidates first (WAIT/BUY + no negative news), AVOID last */
    private fun oversoldRank(stock: StockData): Int {
        val hasNegativeNews = stock.news?.sentiment == com.nseassist.data.model.NewsSentiment.NEGATIVE
        return when {
            hasNegativeNews -> 2                                          // bad news → bottom
            stock.quickTake?.action?.startsWith("Avoid", ignoreCase = true) == true -> 2
            stock.quickTake != null -> 0                                  // bounce candidate → top
            else -> 1
        }
    }

    private fun quickTakeRank(stock: StockData): Int = when {
        stock.quickTake?.action?.startsWith("Buy", ignoreCase = true) == true  -> 0
        stock.quickTake?.action?.startsWith("Avoid", ignoreCase = true) == true -> 2
        stock.quickTake != null -> 1
        else -> 3
    }

    fun scanStocks(capital: Double, category: ScanCategory) {
        _capital.value = capital   // store so single-stock AI analysis can pre-fill it
        val now = System.currentTimeMillis()
        val cached = capital == scanCacheCapital &&
                     category == scanCacheCategory &&
                     (now - scanCacheTimestamp) < SCAN_CACHE_TTL &&
                     _scanResults.value.let { it is UiState.Success && (it as UiState.Success).data.isNotEmpty() }
        if (cached) {
            val ageS = (now - scanCacheTimestamp) / 1000
            val count = (_scanResults.value as? UiState.Success)?.data?.size ?: 0
            AppLogger.d("SCAN", "Cache hit — ${count} stocks, age=${ageS}s (TTL=${SCAN_CACHE_TTL/1000}s)")
            return
        }

        // Lock cache immediately so concurrent calls from the same recomposition don't double-scan
        scanCacheCapital   = capital
        scanCacheCategory  = category
        scanCacheTimestamp = now

        viewModelScope.launch {
            AppLogger.d("SCAN", "scanStocks capital=₹$capital category=${category.routeValue}")
            _scanResults.value = UiState.Loading
            _deepEnrichProgress.value = null
            repo.scanAffordableStocks(capital, category).fold(
                onSuccess = { phase1List ->
                    AppLogger.d("SCAN", "Phase 1 done — ${phase1List.size} stocks")
                    // Phase 1: show the list immediately with quick scores
                    _scanResults.value = UiState.Success(phase1List)
                    // Phase 2: deep-analyse top 15 in background, update list live
                    deepEnrichScanResults(phase1List)
                },
                onFailure = {
                    AppLogger.e("SCAN", "Failed: ${it.message}")
                    _scanResults.value = UiState.Error(it.message ?: "Scan failed")
                },
            )
        }
    }

    /**
     * Short scan — same universe as normal scan but filtered for bearish stocks and
     * sorted ascending by score (most bearish first).
     */
    fun scanShort(capital: Double, category: ScanCategory) {
        _capital.value = capital
        // Invalidate normal scan cache so switching back to Buy tab re-fetches correctly
        scanCacheTimestamp = 0L
        viewModelScope.launch {
            AppLogger.d("SCAN", "scanShort capital=₹$capital category=${category.routeValue}")
            _scanResults.value = UiState.Loading
            _deepEnrichProgress.value = null
            repo.scanAffordableStocks(capital, category).fold(
                onSuccess = { phase1List ->
                    // Keep stocks that are bearish: negative change OR below VWAP OR low score
                    val bearish = phase1List
                        .filter { it.changePct < 0.5 || it.score < 55 }
                        .sortedBy { it.score }  // lowest (most bearish) first
                    AppLogger.d("SCAN", "Short Phase 1 done — ${bearish.size} bearish candidates from ${phase1List.size} total")
                    _scanResults.value = UiState.Success(bearish)
                    deepEnrichScanResults(bearish)
                },
                onFailure = {
                    AppLogger.e("SCAN", "Short scan failed: ${it.message}")
                    _scanResults.value = UiState.Error(it.message ?: "Short scan failed")
                },
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
        AppLogger.d("SCAN", "Phase 2 starting — enriching top ${top15.size} stocks")

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

        AppLogger.d("SCAN", "Phase 2 done — all ${top15.size} stocks enriched")
        _deepEnrichProgress.value = null  // done — banner disappears
    }

    fun loadStockDetail(symbol: String) {
        val now = System.currentTimeMillis()

        // Priority 1: stock already deep-enriched in Phase 2 — show instantly,
        // then load news separately (news is never fetched during Phase 2 batch scan)
        val enrichedFromScan = (_scanResults.value as? UiState.Success)?.data
            ?.firstOrNull { it.symbol == symbol && it.isDeepEnriched }
        if (enrichedFromScan != null) {
            AppLogger.d("SCAN", "Detail cache hit (Phase 2) — $symbol served instantly")
            _stockDetail.value   = UiState.Success(enrichedFromScan)
            detailCacheSymbol    = symbol
            detailCacheTimestamp = now
            loadNewsForStock(enrichedFromScan)
            return
        }

        // Priority 2: same stock re-opened within TTL — serve instantly.
        // Also catches: in-flight fetch (detailFetchInFlightSymbol) OR state already Loading/Success.
        // The explicit in-flight symbol check handles the race window where the coroutine hasn't
        // yet set _stockDetail.value = UiState.Loading when a second synchronous call arrives.
        val inFlight = detailFetchInFlightSymbol == symbol
        val cached = symbol == detailCacheSymbol &&
                     (now - detailCacheTimestamp) < DETAIL_CACHE_TTL &&
                     (_stockDetail.value is UiState.Success || _stockDetail.value is UiState.Loading || inFlight)
        if (cached) {
            val ageS = (now - detailCacheTimestamp) / 1000
            AppLogger.d("SCAN", "Detail cache hit (back-nav) — $symbol age=${ageS}s inFlight=$inFlight")
            (_stockDetail.value as? UiState.Success)?.data?.let { loadNewsForStock(it) }
            return
        }

        // Priority 3: fresh network fetch (stock NOT in Phase 2 top-15)
        AppLogger.d("SCAN", "Detail fetch — $symbol (not in Phase 2 cache)")
        detailCacheSymbol         = symbol
        detailCacheTimestamp      = now
        detailFetchInFlightSymbol = symbol   // set BEFORE coroutine to block any same-frame duplicate
        viewModelScope.launch {
            _stockDetail.value = UiState.Loading
            val result = repo.analyseStock(symbol, fetchNews = false)  // technical only — fast
            detailFetchInFlightSymbol = null   // clear after fetch completes or fails
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
            AppLogger.d("NEWS", "${stock.symbol} — ${news?.articles?.size ?: 0} articles sentiment=${news?.sentiment}")
            _stockNews.value = UiState.Success(news)
        }
    }

    fun setTestMorningSelloff(enabled: Boolean) {
        aiSettingsStore.saveTestMorningSelloff(enabled)
        _testMorningSelloff.value = enabled
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
            AppLogger.e("AI", "analyzeStocksWithAi — no API key for ${provider.label}")
            _aiAnalysis.value = UiState.Error("Add ${provider.label} API key in Settings first")
            return
        }
        AppLogger.d("AI", "analyzeStocksWithAi — ${provider.label} stocks=${stocks.size} capital=₹$capital category=${category.routeValue}")

        val vix = (_marketOverview.value as? UiState.Success)?.data?.indiaVix ?: 0.0
        viewModelScope.launch {
            _aiAnalysis.value = UiState.Loading
            _aiAnalysis.value = aiAnalysisService.analyzeStocks(config, capital, category, stocks, vix)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = {
                        AppLogger.e("AI", "Batch analysis failed: ${it.message}")
                        UiState.Error(it.message ?: "AI analysis failed")
                    },
                )
        }
    }

    /** Force a fresh scan regardless of cache — call this from a "Refresh" button. */
    fun forceRescan(capital: Double, category: ScanCategory) {
        AppLogger.d("SCAN", "Force rescan — cache invalidated for capital=₹$capital category=${category.routeValue}")
        scanCacheTimestamp = 0L   // invalidate cache
        scanStocks(capital, category)
    }

    /** Force a fresh stock detail fetch — bypasses ALL caches including Phase 2 scan results. */
    fun refreshStockDetail(symbol: String) {
        detailCacheTimestamp      = 0L
        detailCacheSymbol         = ""
        detailFetchInFlightSymbol = symbol
        _stockNews.value          = UiState.Loading
        viewModelScope.launch {
            _stockDetail.value   = UiState.Loading
            detailCacheSymbol    = symbol
            detailCacheTimestamp = System.currentTimeMillis()
            val result = repo.analyseStock(symbol, fetchNews = false)
            detailFetchInFlightSymbol = null
            _stockDetail.value = result.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Analysis failed") },
            )
            result.getOrNull()?.let { loadNewsForStock(it) }
        }
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
            AppLogger.e("AI", "analyzeCurrentStockWithAi — no API key for ${provider.label}")
            _singleStockAnalysis.value = UiState.Error("Add ${provider.label} API key in Settings → Data & AI Settings first")
            return
        }
        AppLogger.d("AI", "analyzeCurrentStockWithAi — ${provider.label} stock=${stock.symbol} capital=₹$capital")

        val vix = (_marketOverview.value as? UiState.Success)?.data?.indiaVix ?: 0.0
        viewModelScope.launch {
            _singleStockAnalysis.value = UiState.Loading
            _singleStockAnalysis.value = aiAnalysisService
                .analyzeSingleStock(config, capital, stock, news, vix)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = {
                        AppLogger.e("AI", "Single-stock analysis failed (${stock.symbol}): ${it.message}")
                        UiState.Error(it.message ?: "AI analysis failed")
                    },
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

        AppLogger.d("AI", "enrichTop20 — already done=${alreadyDone.size} need=${needEnrichment.size}")
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
        AppLogger.d("AI", "Export prep — enriching top 20 of ${stocks.size} stocks")
        viewModelScope.launch {
            _exportState.value = ExportState.Preparing(0, minOf(20, stocks.size))
            val finalList = enrichTop20(stocks) { done, total ->
                _exportState.value = ExportState.Preparing(done, total)
            }
            AppLogger.d("AI", "Export prep done — ${finalList.size} stocks ready")
            _exportState.value = ExportState.Idle
            onReady(finalList)
        }
    }

    fun prepareDeepAiAnalysis(provider: AiProvider, capital: Double, category: ScanCategory, stocks: List<StockData>) {
        val config = _aiSettings.value.providers.firstOrNull { it.provider == provider && it.apiKey.isNotBlank() }
        if (config == null) {
            AppLogger.e("AI", "prepareDeepAiAnalysis — no API key for ${provider.label}")
            _aiAnalysis.value = UiState.Error("Add ${provider.label} API key in Settings first")
            return
        }
        AppLogger.d("AI", "Deep AI analysis — ${provider.label} enriching top 20 of ${stocks.size} stocks capital=₹$capital category=${category.routeValue}")

        viewModelScope.launch {
            _aiAnalysis.value = UiState.Loading
            _exportState.value = ExportState.Preparing(0, minOf(20, stocks.size))

            val enrichedStocks = enrichTop20(stocks) { done, total ->
                _exportState.value = ExportState.Preparing(done, total)
            }
            AppLogger.d("AI", "Enrichment complete — sending ${enrichedStocks.size} stocks to ${provider.label}")
            _exportState.value = ExportState.Idle

            val vix = (_marketOverview.value as? UiState.Success)?.data?.indiaVix ?: 0.0
            _aiAnalysis.value = aiAnalysisService.analyzeStocks(config, capital, category, enrichedStocks, vix)
                .fold(
                    onSuccess = { UiState.Success(it) },
                    onFailure = {
                        AppLogger.e("AI", "Deep AI analysis failed: ${it.message}")
                        UiState.Error(it.message ?: "AI analysis failed")
                    },
                )
        }
    }

    fun clearExportState() {
        _exportState.value = ExportState.Idle
    }

    // ── Paper Trade Log ───────────────────────────────────────────────────────

    private val _trades = MutableStateFlow<UiState<List<PaperTradeEntry>>>(UiState.Loading)
    val trades: StateFlow<UiState<List<PaperTradeEntry>>> = _trades

    /** Log a paper trade to Cloudflare D1 (fire-and-forget). */
    fun logPaperTrade(trade: PaperTradeRequest) {
        AppLogger.d("TRADE", "Log ${trade.verdict} ${trade.symbol} via ${trade.aiProvider}")
        viewModelScope.launch {
            MemoryClient.logPaperTrade(trade)
        }
    }

    /** Load all logged trades from Cloudflare D1, then auto-resolve any open GO trades
     *  whose current price has hit their target or stop loss. */
    fun loadTrades() {
        viewModelScope.launch {
            _trades.value = UiState.Loading
            val list = runCatching { MemoryClient.getTrades() }.getOrElse {
                AppLogger.e("TRADE", "Failed to load trades: ${it.message}")
                _trades.value = UiState.Error(it.message ?: "Failed to load trades")
                return@launch
            }

            // Auto-resolve: only GO/BATCH trades that still have an entry price and target/SL text
            val openGo = list.filter {
                it.outcome == "OPEN" &&
                (it.verdict == "GO" || it.verdict == "AI_BATCH") &&
                it.entryPrice > 0 &&
                it.targetText.isNotBlank() &&
                it.stopLossText.isNotBlank()
            }

            if (openGo.isEmpty()) {
                AppLogger.d("TRADE", "Loaded ${list.size} trades — no open trades to auto-resolve")
                _trades.value = UiState.Success(list)
                return@launch
            }

            // Fetch current prices for the symbols (best-effort — failures just skip that trade)
            val prices = repo.fetchCurrentPrices(openGo.map { it.symbol }.distinct())

            // Determine outcomes without blocking the UI
            val resolved = mutableMapOf<Int, Pair<String, Double>>() // id → (outcome, ltp)
            openGo.forEach { trade ->
                val ltp    = prices[trade.symbol]            ?: return@forEach
                val target = parsePriceFromText(trade.targetText)  ?: return@forEach
                val sl     = parsePriceFromText(trade.stopLossText) ?: return@forEach
                when {
                    ltp >= target -> resolved[trade.id] = "TARGET_HIT" to ltp
                    ltp <= sl     -> resolved[trade.id] = "SL_HIT"     to ltp
                }
            }

            // Persist to D1 (sequential — typically 0–3 trades, fast)
            resolved.forEach { (id, outcomeAndLtp) ->
                MemoryClient.updateTradeOutcome(id, outcomeAndLtp.first, outcomeAndLtp.second)
            }

            // Merge resolved outcomes into the in-memory list (no second network fetch needed)
            if (resolved.isNotEmpty()) {
                resolved.forEach { (id, outcomeAndLtp) ->
                    val sym = list.firstOrNull { it.id == id }?.symbol ?: "?"
                    AppLogger.d("TRADE", "Auto-resolved #$id $sym → ${outcomeAndLtp.first} @ ₹${outcomeAndLtp.second}")
                }
            }
            AppLogger.d("TRADE", "Loaded ${list.size} trades, auto-resolved ${resolved.size}")
            val finalList = list.map { trade ->
                val r = resolved[trade.id]
                if (r != null) trade.copy(outcome = r.first, outcomePrice = r.second) else trade
            }
            _trades.value = UiState.Success(finalList)
        }
    }

    /** Parses the first price number from strings like "Rs 795 (+3.6%)" or "₹1,105 (-2.3%)". */
    private fun parsePriceFromText(text: String): Double? =
        Regex("[0-9,]+\\.?[0-9]*").find(text)?.value?.replace(",", "")?.toDoubleOrNull()

    /** Update the outcome of a logged trade (e.g. TARGET_HIT / SL_HIT). */
    fun updateTradeOutcome(id: Int, outcome: String, outcomePrice: Double?) {
        AppLogger.d("TRADE", "Update #$id → $outcome" + if (outcomePrice != null) " @ ₹$outcomePrice" else "")
        viewModelScope.launch {
            MemoryClient.updateTradeOutcome(id, outcome, outcomePrice)
            loadTrades()   // refresh list
        }
    }

    /** Delete all paper trades and AI audit entries. */
    fun clearAllTrades() {
        viewModelScope.launch {
            MemoryClient.clearAllTrades()
            loadTrades()
        }
    }

    /** Trigger manual 6-month data cleanup. */
    fun triggerMemoryCleanup() {
        viewModelScope.launch {
            MemoryClient.triggerCleanup()
        }
    }

    // ── Signal Replay ─────────────────────────────────────────────────────────

    private val _signalReplay = MutableStateFlow<UiState<SignalReplay.SignalReplayResult>?>(null)
    val signalReplay: StateFlow<UiState<SignalReplay.SignalReplayResult>?> = _signalReplay

    /**
     * Run signal replay on the stock's existing price/volume history.
     * Only works on deep-enriched stocks (priceHistory non-empty).
     * Logs outcomes to D1 automatically after completion.
     */
    fun runSignalReplay(stock: StockData) {
        if (stock.priceHistory.isEmpty()) {
            AppLogger.w("SCAN", "Signal replay skipped for ${stock.symbol} — no price history (deep analysis required)")
            _signalReplay.value = UiState.Error("Deep analysis required — open stock detail first")
            return
        }
        AppLogger.d("SCAN", "Signal replay started — ${stock.symbol} history=${stock.priceHistory.size} bars")
        viewModelScope.launch {
            _signalReplay.value = UiState.Loading
            val result = SignalReplay().replay(
                symbol  = stock.symbol,
                closes  = stock.priceHistory,
                volumes = stock.volumeHistory.map { it.toDouble() },
                atr     = stock.atr,
            )
            AppLogger.d("SCAN", "Signal replay done — ${stock.symbol} signals=${result.events.size} winRate=${String.format("%.0f", result.winRate * 100)}% wins=${result.wins} losses=${result.losses} neutral=${result.neutrals}")
            _signalReplay.value = UiState.Success(result)
            // Log signal outcomes to D1 (fire-and-forget)
            if (result.toSignalOutcomes.isNotEmpty()) {
                launch { MemoryClient.logSignalOutcomes(result.toSignalOutcomes) }
            }
        }
    }

    fun clearSignalReplay() {
        _signalReplay.value = null
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        themeStore.save(mode)
    }
}

sealed class ExportState {
    object Idle : ExportState()
    data class Preparing(val done: Int, val total: Int) : ExportState()
}
