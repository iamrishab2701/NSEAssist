/**
 * NSEAssist Yahoo Finance + Twelve Data Proxy — Cloudflare Worker v1.2
 *
 * Data source priority (most reliable first):
 *
 *   /quotes   → Yahoo Finance v7  →  Yahoo v8/chart  →  Twelve Data
 *   /history  → Yahoo Finance v8/chart               →  Twelve Data
 *   /screener → Yahoo Finance screener               →  fallback batch quotes (known NSE list)
 *   /profile  → Yahoo Finance quoteSummary           →  empty (optional field, never blocks)
 *
 * Twelve Data key is stored as a Cloudflare secret (never in code).
 * Set it once with:  wrangler secret put TWELVE_DATA_KEY
 * If not set, Twelve Data is silently skipped — existing Yahoo fallbacks still work.
 */

// ── Constants ─────────────────────────────────────────────────────────────────
const YAHOO1   = "https://query1.finance.yahoo.com";
const YAHOO2   = "https://query2.finance.yahoo.com";
const TWELVE   = "https://api.twelvedata.com";
const UA       = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                 "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
const FIELDS   = "regularMarketPrice,regularMarketChangePercent,regularMarketChange," +
                 "regularMarketDayHigh,regularMarketDayLow,regularMarketVolume," +
                 "averageDailyVolume10Day,regularMarketOpen,regularMarketPreviousClose," +
                 "longName,shortName,marketCap";

// ── Yahoo session state ───────────────────────────────────────────────────────
let _cookies   = "";
let _crumb     = "";
let _sessionTs = 0;
const SESSION_TTL = 20 * 60 * 1000;

// ── Screener cache ────────────────────────────────────────────────────────────
let _screenerBody = null;
let _screenerTs   = 0;
let _screenerKey  = "";
const SCREENER_TTL = 5 * 60 * 1000;

// ── Global Trends cache ───────────────────────────────────────────────────────
let _trendsBody = null;
let _trendsTs   = 0;
const TRENDS_TTL = 15 * 60 * 1000;

// ── Indian sector map cache ───────────────────────────────────────────────────
let _indSectors   = null; // Map<sector, ImpactedStock[]>
let _indSectorsTs = 0;
const IND_SECTORS_TTL = 60 * 60 * 1000;

// ── Global index metadata ─────────────────────────────────────────────────────
const GLOBAL_INDEX_META = {
  "^DJI":      { name: "Dow Jones",   group: "us" },
  "^GSPC":     { name: "S&P 500",     group: "us" },
  "^IXIC":     { name: "Nasdaq",      group: "us" },
  "^N225":     { name: "Nikkei 225",  group: "asia" },
  "^HSI":      { name: "Hang Seng",   group: "asia" },
  "^FTSE":     { name: "FTSE 100",    group: "asia" },
  "^NSEI":     { name: "NIFTY 50",    group: "india" },
  "^NSEBANK":  { name: "BANK NIFTY",  group: "india" },
  "^INDIAVIX": { name: "India VIX",   group: "india" },
  "CL=F":      { name: "Crude Oil",   group: "commodities" },
  "GC=F":      { name: "Gold",        group: "commodities" },
  "USDINR=X":  { name: "USD/INR",     group: "forex" },
};

// ── US large-cap stocks by sector — for impact cards when screener is unavailable ──
// chartToQuote (v8/chart) works without crumb, so these are always fetchable.
const FALLBACK_US_STOCKS = [
  // Technology
  { symbol: "AAPL",  name: "Apple Inc.",               sector: "Technology" },
  { symbol: "MSFT",  name: "Microsoft Corp.",           sector: "Technology" },
  { symbol: "NVDA",  name: "NVIDIA Corp.",              sector: "Technology" },
  { symbol: "GOOGL", name: "Alphabet Inc.",             sector: "Technology" },
  { symbol: "META",  name: "Meta Platforms Inc.",       sector: "Technology" },
  { symbol: "AMD",   name: "Advanced Micro Devices",    sector: "Technology" },
  { symbol: "INTC",  name: "Intel Corp.",               sector: "Technology" },
  // Financial Services
  { symbol: "JPM",   name: "JPMorgan Chase & Co.",      sector: "Financial Services" },
  { symbol: "BAC",   name: "Bank of America Corp.",     sector: "Financial Services" },
  { symbol: "GS",    name: "Goldman Sachs Group",       sector: "Financial Services" },
  { symbol: "MS",    name: "Morgan Stanley",            sector: "Financial Services" },
  { symbol: "WFC",   name: "Wells Fargo & Co.",         sector: "Financial Services" },
  // Energy
  { symbol: "XOM",   name: "Exxon Mobil Corp.",         sector: "Energy" },
  { symbol: "CVX",   name: "Chevron Corp.",             sector: "Energy" },
  { symbol: "COP",   name: "ConocoPhillips",            sector: "Energy" },
  // Healthcare
  { symbol: "JNJ",   name: "Johnson & Johnson",         sector: "Healthcare" },
  { symbol: "PFE",   name: "Pfizer Inc.",               sector: "Healthcare" },
  { symbol: "UNH",   name: "UnitedHealth Group",        sector: "Healthcare" },
  { symbol: "ABBV",  name: "AbbVie Inc.",               sector: "Healthcare" },
  // Consumer Cyclical
  { symbol: "AMZN",  name: "Amazon.com Inc.",           sector: "Consumer Cyclical" },
  { symbol: "TSLA",  name: "Tesla Inc.",                sector: "Consumer Cyclical" },
  { symbol: "HD",    name: "Home Depot Inc.",           sector: "Consumer Cyclical" },
  { symbol: "NKE",   name: "Nike Inc.",                 sector: "Consumer Cyclical" },
  // Consumer Defensive
  { symbol: "WMT",   name: "Walmart Inc.",              sector: "Consumer Defensive" },
  { symbol: "PG",    name: "Procter & Gamble Co.",      sector: "Consumer Defensive" },
  { symbol: "KO",    name: "Coca-Cola Co.",             sector: "Consumer Defensive" },
  // Industrials
  { symbol: "CAT",   name: "Caterpillar Inc.",          sector: "Industrials" },
  { symbol: "BA",    name: "Boeing Co.",                sector: "Industrials" },
  { symbol: "GE",    name: "GE Aerospace",              sector: "Industrials" },
  { symbol: "HON",   name: "Honeywell Intl.",           sector: "Industrials" },
  // Basic Materials
  { symbol: "FCX",   name: "Freeport-McMoRan Inc.",     sector: "Basic Materials" },
  { symbol: "NEM",   name: "Newmont Corp.",             sector: "Basic Materials" },
  { symbol: "AA",    name: "Alcoa Corp.",               sector: "Basic Materials" },
  // Communication Services
  { symbol: "NFLX",  name: "Netflix Inc.",              sector: "Communication Services" },
  { symbol: "DIS",   name: "Walt Disney Co.",           sector: "Communication Services" },
  { symbol: "T",     name: "AT&T Inc.",                 sector: "Communication Services" },
  // Utilities
  { symbol: "NEE",   name: "NextEra Energy Inc.",       sector: "Utilities" },
  { symbol: "DUK",   name: "Duke Energy Corp.",         sector: "Utilities" },
  // Real Estate
  { symbol: "PLD",   name: "Prologis Inc.",             sector: "Real Estate" },
  { symbol: "AMT",   name: "American Tower Corp.",      sector: "Real Estate" },
];

// ── Indian stocks by sector — hardcoded so impact cards never depend on Yahoo returning sector ──
const FALLBACK_INDIA_STOCKS = [
  // Technology
  { symbol: "TCS.NS",        nseSymbol: "TCS",        name: "Tata Consultancy Services", sector: "Technology" },
  { symbol: "INFY.NS",       nseSymbol: "INFY",       name: "Infosys Ltd.",              sector: "Technology" },
  { symbol: "WIPRO.NS",      nseSymbol: "WIPRO",      name: "Wipro Ltd.",                sector: "Technology" },
  { symbol: "HCLTECH.NS",    nseSymbol: "HCLTECH",    name: "HCL Technologies",          sector: "Technology" },
  { symbol: "TECHM.NS",      nseSymbol: "TECHM",      name: "Tech Mahindra",             sector: "Technology" },
  // Financial Services
  { symbol: "HDFCBANK.NS",   nseSymbol: "HDFCBANK",   name: "HDFC Bank Ltd.",            sector: "Financial Services" },
  { symbol: "ICICIBANK.NS",  nseSymbol: "ICICIBANK",  name: "ICICI Bank Ltd.",           sector: "Financial Services" },
  { symbol: "SBIN.NS",       nseSymbol: "SBIN",       name: "State Bank of India",       sector: "Financial Services" },
  { symbol: "AXISBANK.NS",   nseSymbol: "AXISBANK",   name: "Axis Bank Ltd.",            sector: "Financial Services" },
  { symbol: "KOTAKBANK.NS",  nseSymbol: "KOTAKBANK",  name: "Kotak Mahindra Bank",       sector: "Financial Services" },
  { symbol: "BAJFINANCE.NS", nseSymbol: "BAJFINANCE", name: "Bajaj Finance Ltd.",        sector: "Financial Services" },
  { symbol: "PFC.NS",        nseSymbol: "PFC",        name: "Power Finance Corp.",       sector: "Financial Services" },
  { symbol: "RECLTD.NS",     nseSymbol: "RECLTD",     name: "REC Ltd.",                  sector: "Financial Services" },
  // Energy
  { symbol: "RELIANCE.NS",   nseSymbol: "RELIANCE",   name: "Reliance Industries",       sector: "Energy" },
  { symbol: "ONGC.NS",       nseSymbol: "ONGC",       name: "Oil & Natural Gas Corp.",   sector: "Energy" },
  { symbol: "IOC.NS",        nseSymbol: "IOC",        name: "Indian Oil Corp.",          sector: "Energy" },
  { symbol: "BPCL.NS",       nseSymbol: "BPCL",       name: "Bharat Petroleum",         sector: "Energy" },
  { symbol: "GAIL.NS",       nseSymbol: "GAIL",       name: "GAIL (India) Ltd.",         sector: "Energy" },
  { symbol: "COALINDIA.NS",  nseSymbol: "COALINDIA",  name: "Coal India Ltd.",           sector: "Energy" },
  { symbol: "TATAPOWER.NS",  nseSymbol: "TATAPOWER",  name: "Tata Power Co.",            sector: "Energy" },
  // Healthcare
  { symbol: "SUNPHARMA.NS",  nseSymbol: "SUNPHARMA",  name: "Sun Pharmaceutical",       sector: "Healthcare" },
  { symbol: "DRREDDY.NS",    nseSymbol: "DRREDDY",    name: "Dr. Reddy's Laboratories", sector: "Healthcare" },
  { symbol: "CIPLA.NS",      nseSymbol: "CIPLA",      name: "Cipla Ltd.",               sector: "Healthcare" },
  { symbol: "APOLLOHOSP.NS", nseSymbol: "APOLLOHOSP", name: "Apollo Hospitals",         sector: "Healthcare" },
  // Consumer Cyclical
  { symbol: "TATAMOTORS.NS", nseSymbol: "TATAMOTORS", name: "Tata Motors Ltd.",         sector: "Consumer Cyclical" },
  { symbol: "MARUTI.NS",     nseSymbol: "MARUTI",     name: "Maruti Suzuki India",      sector: "Consumer Cyclical" },
  { symbol: "ZOMATO.NS",     nseSymbol: "ZOMATO",     name: "Zomato Ltd.",              sector: "Consumer Cyclical" },
  { symbol: "TITAN.NS",      nseSymbol: "TITAN",      name: "Titan Company Ltd.",       sector: "Consumer Cyclical" },
  // Consumer Defensive
  { symbol: "ITC.NS",        nseSymbol: "ITC",        name: "ITC Ltd.",                 sector: "Consumer Defensive" },
  { symbol: "NESTLEIND.NS",  nseSymbol: "NESTLEIND",  name: "Nestle India Ltd.",        sector: "Consumer Defensive" },
  { symbol: "HINDUNILVR.NS", nseSymbol: "HINDUNILVR", name: "Hindustan Unilever",       sector: "Consumer Defensive" },
  { symbol: "ASIANPAINT.NS", nseSymbol: "ASIANPAINT", name: "Asian Paints Ltd.",        sector: "Consumer Defensive" },
  // Industrials
  { symbol: "LT.NS",         nseSymbol: "LT",         name: "Larsen & Toubro Ltd.",     sector: "Industrials" },
  { symbol: "ADANIPORTS.NS", nseSymbol: "ADANIPORTS", name: "Adani Ports & SEZ",        sector: "Industrials" },
  { symbol: "SIEMENS.NS",    nseSymbol: "SIEMENS",    name: "Siemens Ltd.",             sector: "Industrials" },
  // Basic Materials
  { symbol: "TATASTEEL.NS",  nseSymbol: "TATASTEEL",  name: "Tata Steel Ltd.",          sector: "Basic Materials" },
  { symbol: "JSWSTEEL.NS",   nseSymbol: "JSWSTEEL",   name: "JSW Steel Ltd.",           sector: "Basic Materials" },
  { symbol: "HINDALCO.NS",   nseSymbol: "HINDALCO",   name: "Hindalco Industries",      sector: "Basic Materials" },
  { symbol: "VEDL.NS",       nseSymbol: "VEDL",       name: "Vedanta Ltd.",             sector: "Basic Materials" },
  { symbol: "NMDC.NS",       nseSymbol: "NMDC",       name: "NMDC Ltd.",                sector: "Basic Materials" },
  // Communication Services
  { symbol: "BHARTIARTL.NS", nseSymbol: "BHARTIARTL", name: "Bharti Airtel Ltd.",       sector: "Communication Services" },
  { symbol: "IDEA.NS",       nseSymbol: "IDEA",       name: "Vodafone Idea Ltd.",        sector: "Communication Services" },
  // Utilities
  { symbol: "NTPC.NS",       nseSymbol: "NTPC",       name: "NTPC Ltd.",                sector: "Utilities" },
  { symbol: "POWERGRID.NS",  nseSymbol: "POWERGRID",  name: "Power Grid Corp.",         sector: "Utilities" },
  { symbol: "NHPC.NS",       nseSymbol: "NHPC",       name: "NHPC Ltd.",                sector: "Utilities" },
  { symbol: "ADANIENT.NS",   nseSymbol: "ADANIENT",   name: "Adani Enterprises",        sector: "Utilities" },
  // Real Estate
  { symbol: "DLF.NS",        nseSymbol: "DLF",        name: "DLF Ltd.",                 sector: "Real Estate" },
  { symbol: "GODREJPROP.NS", nseSymbol: "GODREJPROP", name: "Godrej Properties",        sector: "Real Estate" },
];

// ── Fallback NSE stock list for screener when Yahoo screener is unavailable ───
const FALLBACK_SYMBOLS = [
  "YESBANK.NS","IDEA.NS","RPOWER.NS","NHPC.NS","SJVN.NS",
  "SUZLON.NS","IRFC.NS","PFC.NS","RECLTD.NS","SAIL.NS",
  "NMDC.NS","BANKBARODA.NS","CANBK.NS","UNIONBANK.NS","IOC.NS",
  "MAHABANK.NS","CENTRALBK.NS","INDIANB.NS","PUNJABNATBNK.NS","UCOBANK.NS",
  "ONGC.NS","COALINDIA.NS","NTPC.NS","POWERGRID.NS","GAIL.NS",
  "BPCL.NS","NATIONALUM.NS","HINDCOPPER.NS","TATASTEEL.NS","ADANIPOWER.NS",
  "TATAPOWER.NS","VEDL.NS","JSWSTEEL.NS","HINDALCO.NS","ITC.NS",
  "SBIN.NS","WIPRO.NS","TATAMOTORS.NS","ADANIPORTS.NS","ZOMATO.NS",
  "HCLTECH.NS","TECHM.NS","LT.NS","ADANIENT.NS","BHARTIARTL.NS",
  "AXISBANK.NS","ICICIBANK.NS","HDFCBANK.NS","INFY.NS","KOTAKBANK.NS",
  "BAJFINANCE.NS","MARUTI.NS","ASIANPAINT.NS","SUNPHARMA.NS","DRREDDY.NS",
  "TCS.NS","RELIANCE.NS","TITAN.NS","ULTRACEMCO.NS","NESTLEIND.NS",
];

// ── Helpers ───────────────────────────────────────────────────────────────────

function corsHeaders() {
  return { "Access-Control-Allow-Origin": "*", "Content-Type": "application/json" };
}
function enc(s)          { return encodeURIComponent(s); }
function crumbParam(c)   { return c ? `&crumb=${enc(c)}` : ""; }
function yahooHeaders(cookies) {
  return {
    "User-Agent": UA,
    "Cookie":     cookies || "",
    "Accept":     "application/json, text/plain, */*",
    "Referer":    "https://finance.yahoo.com/",
    "Origin":     "https://finance.yahoo.com",
  };
}
function isValidCrumb(t) {
  return t && t.length >= 4 && t.length <= 30 && !t.startsWith("<") &&
         !t.includes("\n") && !t.includes(" ");
}
function jsonError(msg, status = 500) {
  return new Response(JSON.stringify({ error: msg }), { status, headers: corsHeaders() });
}
function chunk(arr, n) {
  const out = [];
  for (let i = 0; i < arr.length; i += n) out.push(arr.slice(i, i + n));
  return out;
}

// ── Symbol conversion: Yahoo format → Twelve Data format ─────────────────────
// Yahoo: "SBIN.NS"  →  Twelve Data: "SBIN:NSE"
// Yahoo: "^NSEI"    →  Twelve Data: "NIFTY50:IND"  (index)
// Yahoo: "^NSEBANK" →  Twelve Data: "BANKNIFTY:IND"
const INDEX_MAP = {
  "^NSEI":    "NIFTY50:IND",
  "^NSEBANK": "BANKNIFTY:IND",
  "^CNXAUTO": "CNXAUTO:IND",
  "^CNXIT":   "CNXIT:IND",
};

function yahooToTwelve(yahooSymbol) {
  if (INDEX_MAP[yahooSymbol]) return INDEX_MAP[yahooSymbol];
  if (yahooSymbol.endsWith(".NS")) return yahooSymbol.replace(".NS", ":NSE");
  if (yahooSymbol.endsWith(".BO")) return yahooSymbol.replace(".BO", ":BSE");
  return yahooSymbol; // unknown — pass through
}

function twelveToYahooSymbol(twelveSymbol, originalYahoo) {
  return originalYahoo; // always use original Yahoo symbol in responses
}

// ── Yahoo Finance session init (best-effort, never throws) ────────────────────

async function ensureSession() {
  const now = Date.now();
  if (_crumb && (now - _sessionTs) < SESSION_TTL) return;
  if (!_crumb && _sessionTs > 0 && (now - _sessionTs) < 2 * 60 * 1000) return;

  _sessionTs = now;

  // Strategy 1 & 2: direct crumb from query1 / query2 (no warmup)
  for (const base of [YAHOO1, YAHOO2]) {
    const c = await tryFetchCrumb(base, "");
    if (c) { _crumb = c; return; }
  }

  // Strategy 3 & 4: warmup then crumb
  for (const warmupUrl of ["https://finance.yahoo.com/", "https://finance.yahoo.com/quote/RELIANCE.NS/"]) {
    const cookies = await warmupCookies(warmupUrl);
    if (cookies) {
      const c = await tryFetchCrumb(YAHOO1, cookies);
      if (c) { _cookies = cookies; _crumb = c; return; }
    }
  }

  _crumb = ""; // no-crumb mode — Yahoo v8/chart and Twelve Data will handle it
}

async function tryFetchCrumb(base, cookies) {
  try {
    const resp = await fetch(`${base}/v1/test/getcrumb`, {
      headers: { "User-Agent": UA, "Cookie": cookies, "Accept": "*/*" },
    });
    const text = (await resp.text()).trim();
    return isValidCrumb(text) ? text : null;
  } catch { return null; }
}

async function warmupCookies(url) {
  try {
    const resp = await fetch(url, {
      headers: { "User-Agent": UA, "Accept": "text/html,*/*", "Accept-Language": "en-US,en;q=0.9" },
      redirect: "follow",
    });
    const parts = [];
    for (const [k, v] of resp.headers.entries()) {
      if (k.toLowerCase() === "set-cookie") {
        const nv = v.split(";")[0].trim();
        if (nv) parts.push(nv);
      }
    }
    return parts.length > 0 ? parts.join("; ") : "";
  } catch { return ""; }
}

// ── Twelve Data helpers ───────────────────────────────────────────────────────

/**
 * Fetch batch quotes from Twelve Data.
 * symbolMap: { "SBIN:NSE": "SBIN.NS", ... }  (twelveSymbol → original Yahoo symbol)
 * Returns array of Yahoo-compatible quote objects.
 */
async function fetchTwelveQuotes(symbolMap, apiKey) {
  if (!apiKey || Object.keys(symbolMap).length === 0) return [];
  const twelveSymbols = Object.keys(symbolMap).join(",");
  try {
    const url  = `${TWELVE}/quote?symbol=${enc(twelveSymbols)}&apikey=${apiKey}`;
    const resp = await fetch(url, { headers: { "User-Agent": UA } });
    if (!resp.ok) return [];
    const data = await resp.json();

    // Twelve Data returns a single object for 1 symbol, a map for multiple
    const entries = Object.keys(symbolMap).length === 1
      ? { [Object.keys(symbolMap)[0]]: data }
      : data;

    const results = [];
    for (const [twelveKey, td] of Object.entries(entries)) {
      if (td?.status === "error" || !td?.close) continue;
      const yahooSymbol = symbolMap[twelveKey] ?? twelveKey;
      const price     = parseFloat(td.close)          || 0;
      const prevClose = parseFloat(td.previous_close) || 0;
      // Twelve Data returns 0 for change when market is closed — compute from prices
      let change    = parseFloat(td.change)         || 0;
      let changePct = parseFloat(td.percent_change) || 0;
      if (change === 0 && price > 0 && prevClose > 0) {
        change    = price - prevClose;
        changePct = (change / prevClose) * 100;
      }
      results.push({
        symbol:                     yahooSymbol,
        longName:                   td.name ?? "",
        shortName:                  td.name ?? "",
        regularMarketPrice:         price,
        regularMarketChange:        change,
        regularMarketChangePercent: changePct,
        regularMarketDayHigh:       parseFloat(td.high)         || 0,
        regularMarketDayLow:        parseFloat(td.low)          || 0,
        regularMarketVolume:        parseInt(td.volume)         || 0,
        averageDailyVolume10Day:    parseInt(td.average_volume) || 0,
        regularMarketOpen:          parseFloat(td.open)         || 0,
        regularMarketPreviousClose: prevClose,
        marketCap:                  0, // not on free tier
      });
    }
    return results;
  } catch (e) {
    console.log("Twelve Data quotes error:", e.message);
    return [];
  }
}

/**
 * Fetch OHLCV history from Twelve Data for one symbol.
 * Returns Yahoo v8/chart-compatible JSON string, or null on failure.
 */
async function fetchTwelveHistory(yahooSymbol, days, apiKey) {
  if (!apiKey) return null;
  const twelveSymbol = yahooToTwelve(yahooSymbol);
  const outputsize   = Math.min(days + 5, 90); // Twelve Data free: max 90 data points/req
  try {
    const url  = `${TWELVE}/time_series?symbol=${enc(twelveSymbol)}&interval=1day&outputsize=${outputsize}&apikey=${apiKey}`;
    const resp = await fetch(url, { headers: { "User-Agent": UA } });
    if (!resp.ok) return null;
    const data = await resp.json();
    if (data.status === "error" || !data.values?.length) return null;

    // Twelve Data: newest first → reverse to oldest first (Yahoo convention)
    const values = [...data.values].reverse();
    const timestamps = values.map(v => Math.floor(new Date(v.datetime).getTime() / 1000));
    const opens      = values.map(v => parseFloat(v.open)   || 0);
    const highs      = values.map(v => parseFloat(v.high)   || 0);
    const lows       = values.map(v => parseFloat(v.low)    || 0);
    const closes     = values.map(v => parseFloat(v.close)  || 0);
    const volumes    = values.map(v => parseInt(v.volume)   || 0);

    // Return as Yahoo v8/chart JSON (same format parseHistory() in Android expects)
    return JSON.stringify({
      chart: {
        result: [{
          timestamp:  timestamps,
          indicators: {
            quote: [{ open: opens, high: highs, low: lows, close: closes, volume: volumes }],
          },
        }],
        error: null,
      },
    });
  } catch (e) {
    console.log("Twelve Data history error:", e.message);
    return null;
  }
}

// ── Yahoo Finance: chart-to-quote fallback ────────────────────────────────────

async function chartToQuote(symbol) {
  for (const [base, crumb] of [[YAHOO1, _crumb], [YAHOO1, ""], [YAHOO2, ""]]) {
    try {
      const url  = `${base}/v8/finance/chart/${enc(symbol)}?interval=1d&range=5d${crumbParam(crumb)}`;
      const resp = await fetch(url, { headers: yahooHeaders(crumb ? _cookies : "") });
      if (!resp.ok) continue;
      const j    = await resp.json();
      const meta = j?.chart?.result?.[0]?.meta;
      if (!meta?.regularMarketPrice) continue;

      const price     = meta.regularMarketPrice ?? 0;
      const prevClose = meta.chartPreviousClose ?? meta.regularMarketPreviousClose ?? 0;
      // Yahoo returns 0 for change/changePct outside market hours — compute from prices
      let change    = meta.regularMarketChange         ?? 0;
      let changePct = meta.regularMarketChangePercent  ?? 0;
      if (change === 0 && price > 0 && prevClose > 0) {
        change    = price - prevClose;
        changePct = (change / prevClose) * 100;
      }

      return {
        symbol:                     meta.symbol ?? symbol,
        longName:                   meta.longName  ?? meta.shortName ?? "",
        shortName:                  meta.shortName ?? "",
        regularMarketPrice:         price,
        regularMarketChange:        change,
        regularMarketChangePercent: changePct,
        regularMarketDayHigh:       meta.regularMarketDayHigh ?? 0,
        regularMarketDayLow:        meta.regularMarketDayLow  ?? 0,
        regularMarketVolume:        meta.regularMarketVolume  ?? 0,
        averageDailyVolume10Day:    meta.regularMarketVolume  ?? 0,
        regularMarketOpen:          meta.regularMarketOpen    ?? 0,
        regularMarketPreviousClose: prevClose,
        marketCap:                  meta.marketCap            ?? 0,
      };
    } catch { /* try next */ }
  }
  return null;
}

// ── Route handlers ────────────────────────────────────────────────────────────

/**
 * /screener?maxPrice=X&minVolume=Y
 *
 * Primary:  Yahoo Finance screener (2 pages, merged, crumb required)
 * Fallback: batch-quote the hardcoded ~55 liquid NSE stock list via Yahoo v7/v8
 * Note: Twelve Data free tier has no screener API — Yahoo is the only screener path.
 */
async function handleScreener(searchParams, apiKey) {
  const maxPrice  = parseFloat(searchParams.get("maxPrice")  ?? "999999");
  const minVolume = parseInt(searchParams.get("minVolume")   ?? "1000000", 10);
  const cacheKey  = `${maxPrice}|${minVolume}`;
  const now       = Date.now();

  if (_screenerBody && _screenerKey === cacheKey && (now - _screenerTs) < SCREENER_TTL) {
    return new Response(_screenerBody, { headers: { ...corsHeaders(), "X-Cache": "HIT" } });
  }

  let quotes = [];

  if (_crumb) {
    const url = `${YAHOO1}/v1/finance/screener?formatted=false&lang=en-US&region=IN&crumb=${enc(_crumb)}`;
    try {
      const [r1, r2] = await Promise.all([
        fetch(url, { method: "POST", headers: { ...yahooHeaders(_cookies), "Content-Type": "application/json" }, body: screenerBody(0,   250, maxPrice) }),
        fetch(url, { method: "POST", headers: { ...yahooHeaders(_cookies), "Content-Type": "application/json" }, body: screenerBody(250, 250, maxPrice) }),
      ]);
      const [j1, j2] = await Promise.all([r1.json(), r2.json()]);
      const seen = new Set();
      for (const q of [...(j1?.finance?.result?.[0]?.quotes ?? []), ...(j2?.finance?.result?.[0]?.quotes ?? [])]) {
        if (q?.symbol && !seen.has(q.symbol)) { seen.add(q.symbol); quotes.push(q); }
      }
    } catch (e) { console.log("Screener error:", e.message); }
  }

  // Fallback: batch-quote the known liquid stock list
  if (quotes.length === 0) {
    const batchResults = await Promise.all(
      chunk(FALLBACK_SYMBOLS, 20).map(batch => fetchQuotesRaw(batch.join(","), _crumb, _cookies))
    );
    const allQ = batchResults.flat();
    quotes = allQ.filter(q => (q?.regularMarketPrice ?? 0) > 0 && (q?.regularMarketPrice ?? 0) <= maxPrice);
    console.log(`Screener fallback via batch quotes: ${quotes.length} stocks ≤ ₹${maxPrice}`);
  }

  const responseJson = JSON.stringify({ finance: { result: [{ quotes }], error: null } });
  _screenerBody = responseJson;
  _screenerKey  = cacheKey;
  _screenerTs   = Date.now();
  return new Response(responseJson, { headers: { ...corsHeaders(), "X-Cache": "MISS" } });
}

/**
 * /quotes?symbols=A.NS,B.NS
 *
 * 1. Yahoo v7/quote  (query1, with crumb)
 * 2. Yahoo v7/quote  (query1, no crumb)
 * 3. Yahoo v7/quote  (query2, no crumb)
 * 4. Yahoo v8/chart  per symbol  (most permissive, works without crumb)
 * 5. Twelve Data     (API key required)
 */
async function handleQuotes(searchParams, apiKey) {
  const symbols = searchParams.get("symbols") ?? "";
  if (!symbols) return jsonError("symbols parameter required", 400);

  // Strategies 1–3: Yahoo v7/quote
  for (const [base, crumb, cookies] of [[YAHOO1, _crumb, _cookies], [YAHOO1, "", ""], [YAHOO2, "", ""]]) {
    try {
      const url  = `${base}/v7/finance/quote?symbols=${enc(symbols)}&fields=${enc(FIELDS)}${crumbParam(crumb)}`;
      const resp = await fetch(url, { headers: yahooHeaders(cookies) });
      if (resp.ok) {
        const body = await resp.text();
        if (body.includes("quoteResponse") && body.includes("regularMarketPrice")) {
          return new Response(body, { headers: { ...corsHeaders(), "X-Source": "yahoo-v7" } });
        }
      }
    } catch { /* next */ }
  }

  // Strategy 4: Yahoo v8/chart per symbol
  const symbolList = decodeURIComponent(symbols).split(",").map(s => s.trim()).filter(Boolean);
  const chartResults = await Promise.all(symbolList.map(sym => chartToQuote(sym)));
  const fromChart    = chartResults.filter(q => q !== null);
  if (fromChart.length > 0) {
    return new Response(
      JSON.stringify({ quoteResponse: { result: fromChart, error: null } }),
      { headers: { ...corsHeaders(), "X-Source": "yahoo-chart" } }
    );
  }

  // Strategy 5: Twelve Data fallback
  if (apiKey) {
    const symbolMap = {};
    for (const sym of symbolList) symbolMap[yahooToTwelve(sym)] = sym;
    // Twelve Data free: max 8 per request — batch accordingly
    const allTwelveQuotes = [];
    for (const batch of chunk(Object.entries(symbolMap), 8)) {
      const batchMap = Object.fromEntries(batch);
      const results  = await fetchTwelveQuotes(batchMap, apiKey);
      allTwelveQuotes.push(...results);
    }
    if (allTwelveQuotes.length > 0) {
      console.log(`Quotes: Twelve Data returned ${allTwelveQuotes.length} results`);
      return new Response(
        JSON.stringify({ quoteResponse: { result: allTwelveQuotes, error: null } }),
        { headers: { ...corsHeaders(), "X-Source": "twelvedata" } }
      );
    }
  }

  // All strategies failed — return empty but valid response (app handles gracefully)
  return new Response(
    JSON.stringify({ quoteResponse: { result: [], error: null } }),
    { headers: corsHeaders() }
  );
}

/**
 * /history?symbol=TCS.NS&days=90
 *
 * 1. Yahoo v8/chart (with crumb)
 * 2. Yahoo v8/chart (no crumb)   ← works from Cloudflare DCs in most regions
 * 3. Yahoo v8/chart (query2, no crumb)
 * 4. Twelve Data time_series
 */
async function handleHistory(searchParams, apiKey) {
  const symbol = searchParams.get("symbol") ?? "";
  const days   = parseInt(searchParams.get("days") ?? "90", 10);
  if (!symbol) return jsonError("symbol parameter required", 400);

  const range = days <= 30 ? "1mo" : days <= 90 ? "3mo" : days <= 180 ? "6mo" : "1y";

  // Strategies 1–3: Yahoo v8/chart
  for (const [base, crumb, cookies] of [[YAHOO1, _crumb, _cookies], [YAHOO1, "", ""], [YAHOO2, "", ""]]) {
    try {
      const url  = `${base}/v8/finance/chart/${enc(symbol)}?interval=1d&range=${range}${crumbParam(crumb)}`;
      const resp = await fetch(url, { headers: yahooHeaders(cookies) });
      if (resp.ok) {
        const body = await resp.text();
        if (body.includes('"chart"') && body.includes('"timestamp"')) {
          return new Response(body, { headers: { ...corsHeaders(), "X-Source": "yahoo-chart" } });
        }
      }
    } catch { /* next */ }
  }

  // Strategy 4: Twelve Data fallback
  if (apiKey) {
    const tdBody = await fetchTwelveHistory(symbol, days, apiKey);
    if (tdBody) {
      console.log(`History: Twelve Data for ${symbol}`);
      return new Response(tdBody, { headers: { ...corsHeaders(), "X-Source": "twelvedata" } });
    }
  }

  return jsonError(`No history data for ${symbol}`, 502);
}

/**
 * /profile?symbol=TCS.NS
 * Sector/industry — optional field, failure is silent in the app.
 */
async function handleProfile(searchParams) {
  const symbol = searchParams.get("symbol") ?? "";
  if (!symbol) return jsonError("symbol parameter required", 400);

  for (const [base, crumb, cookies] of [[YAHOO1, _crumb, _cookies], [YAHOO1, "", ""], [YAHOO2, "", ""]]) {
    try {
      const url  = `${base}/v10/finance/quoteSummary/${enc(symbol)}?modules=assetProfile${crumbParam(crumb)}`;
      const resp = await fetch(url, { headers: yahooHeaders(cookies) });
      if (resp.ok) {
        const body = await resp.text();
        if (body.includes("quoteSummary")) return new Response(body, { headers: corsHeaders() });
      }
    } catch { /* next */ }
  }

  // Return empty profile — app handles missing sector/industry gracefully
  return new Response(
    JSON.stringify({ quoteSummary: { result: [{ assetProfile: {} }], error: null } }),
    { headers: corsHeaders() }
  );
}

// ── Internal helpers ──────────────────────────────────────────────────────────

async function fetchQuotesRaw(symbols, crumb, cookies) {
  for (const [base, c, co] of [[YAHOO1, crumb, cookies], [YAHOO1, "", ""], [YAHOO2, "", ""]]) {
    try {
      const url  = `${base}/v7/finance/quote?symbols=${enc(symbols)}&fields=${enc(FIELDS)}${crumbParam(c)}`;
      const resp = await fetch(url, { headers: yahooHeaders(co) });
      if (resp.ok) {
        const j = await resp.json();
        const r = j?.quoteResponse?.result ?? [];
        if (r.length > 0) return r;
      }
    } catch { /* next */ }
  }
  return [];
}

function screenerBody(offset, size, maxPrice) {
  const priceFilter = (isFinite(maxPrice) && maxPrice < 999998)
    ? [{ operator: "LT", operands: ["intradayprice", maxPrice + 1] }]
    : [];
  return JSON.stringify({
    offset, size,
    sortField: "intradaymarketcap", sortType: "DESC",
    quoteType: "EQUITY", topOperator: "AND",
    query: {
      operator: "AND",
      operands: [{ operator: "EQ", operands: ["exchange", "NSI"] }, ...priceFilter],
    },
    userId: "", userIdType: "guid",
  });
}

// ── News: RSS sources ─────────────────────────────────────────────────────────

const RSS_SOURCES = [
  { name: "Moneycontrol",       url: "https://www.moneycontrol.com/rss/buzzingstocks.xml" },
  { name: "Economic Times",     url: "https://economictimes.indiatimes.com/markets/stocks/rssfeeds/2146842.cms" },
  { name: "ET Markets",         url: "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms" },
  { name: "Business Standard",  url: "https://www.business-standard.com/rss/markets-106.rss" },
  { name: "LiveMint",           url: "https://www.livemint.com/rss/markets" },
  { name: "Financial Express",  url: "https://www.financialexpress.com/market/feed/" },
  { name: "Reuters India",      url: "https://feeds.reuters.com/reuters/INbusinessNews" },
  { name: "Business Today",     url: "https://www.businesstoday.in/rss/topic/markets" },
  { name: "The Hindu Business", url: "https://www.thehindu.com/business/?service=rss" },
  { name: "Hindu BusinessLine", url: "https://www.thehindubusinessline.com/companies/?service=rss" },
  { name: "NDTV Profit",        url: "https://www.ndtvprofit.com/rss" },
  { name: "CNBC TV18",          url: "https://www.cnbctv18.com/rss" },
];

const NEWS_UA   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
const ITEM_RE   = /<item[^>]*>([\s\S]*?)<\/item>/gi;
const TITLE_RE  = /<title[^>]*>([\s\S]*?)<\/title>/i;
const DATE_RE   = /<pubDate[^>]*>([\s\S]*?)<\/pubDate>/i;
const POS_WORDS = ["surge","rally","gain","gains","rise","rises","up","bullish","buy","breakout","profit","growth","strong","upgrade","beat","outperform","record","high","positive","boost"];
const NEG_WORDS = ["fall","falls","drop","drops","crash","decline","declines","down","bearish","sell","breakdown","loss","losses","weak","downgrade","miss","underperform","low","slump","concern","risk"];
const MAX_NEWS_AGE_MS        = 24 * 60 * 60 * 1000; // /news endpoint: 24 hours
const MAX_TRENDS_NEWS_AGE_MS = 12 * 60 * 60 * 1000; // /global-trends impact cards: 12 hours

// ── News helpers ──────────────────────────────────────────────────────────────

function decodeEntities(text) {
  return text
    .replace(/&nbsp;/g, " ").replace(/&ldquo;/g, '"').replace(/&rdquo;/g, '"')
    .replace(/&lsquo;/g, "'").replace(/&rsquo;/g, "'").replace(/&mdash;/g, "—")
    .replace(/&ndash;/g, "–").replace(/&hellip;/g, "…").replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g,   (_, n) => String.fromCharCode(parseInt(n, 10)))
    .replace(/&#x([0-9a-f]+);/gi, (_, h) => String.fromCharCode(parseInt(h, 16)))
    .replace(/<[^>]+>/g, "") // strip any residual HTML tags in title
    .trim();
}

function classifyNewsSentiment(headline) {
  const h = headline.toLowerCase();
  const pos = POS_WORDS.filter(w => h.includes(w)).length;
  const neg = NEG_WORDS.filter(w => h.includes(w)).length;
  if (pos > neg) return "POSITIVE";
  if (neg > pos) return "NEGATIVE";
  if (pos > 0 || neg > 0) return "NEUTRAL";
  return "NONE";
}

function isNewsRelevant(headline, terms) {
  const h = headline.toLowerCase();
  return terms.some(t => t.length >= 2 && h.includes(t.toLowerCase()));
}

function parseRssDate(str) {
  if (!str) return Date.now();
  const t = Date.parse(str.trim()); // JS Date.parse handles RFC 822, ISO 8601, +0530, GMT etc.
  return t > 0 ? t : Date.now();
}

async function fetchCachedRss(url) {
  try {
    // cf.cacheEverything + cacheTtl caches the response at Cloudflare's edge for 15 min.
    // Subsequent requests from any device hit cache — no repeated RSS fetches.
    const resp = await fetch(url, {
      headers: { "User-Agent": NEWS_UA, "Accept": "application/rss+xml, application/xml, text/xml, */*" },
      cf: { cacheEverything: true, cacheTtl: 900 },
    });
    if (!resp.ok) { console.log(`RSS ${resp.status} for ${url}`); return null; }
    return await resp.text();
  } catch (e) {
    console.log(`RSS fetch error for ${url}: ${e.message}`);
    return null;
  }
}

function parseRssItems(xml, sourceName) {
  const items = [];
  ITEM_RE.lastIndex = 0;
  let m;
  while ((m = ITEM_RE.exec(xml)) !== null) {
    const block    = m[1];
    const titleM = TITLE_RE.exec(block);
    if (!titleM) continue;
    // Unwrap CDATA markers if present, then clean
    let rawTitle = titleM[1].trim();
    if (rawTitle.startsWith("<![CDATA[")) rawTitle = rawTitle.slice(9);
    if (rawTitle.endsWith("]]>"))        rawTitle = rawTitle.slice(0, -3);
    const headline = decodeEntities(rawTitle.trim());
    if (!headline) continue;
    const dateM    = DATE_RE.exec(block);
    items.push({
      headline,
      source:      sourceName,
      publishedAt: parseRssDate(dateM?.[1] ?? ""),
      sentiment:   classifyNewsSentiment(headline),
    });
  }
  return items;
}

/**
 * GET /news?stock=SBIN&company=State+Bank+of+India&aliases=SBI
 *
 * Fetches all 12 RSS sources in parallel (each cached 15 min at Cloudflare edge),
 * filters for stock relevance, returns JSON array of up to 10 articles.
 */
async function handleNews(searchParams) {
  const stock   = (searchParams.get("stock")   ?? "").trim().toUpperCase();
  const company = (searchParams.get("company") ?? "").trim();
  const aliases = (searchParams.get("aliases") ?? "").split(",").map(s => s.trim()).filter(Boolean);

  if (!stock) return jsonError("stock parameter required", 400);

  const terms = [...new Set([stock, company, ...aliases].filter(Boolean))];

  // Fetch all RSS sources in parallel — each is independently cached for 15 min
  const results = await Promise.all(
    RSS_SOURCES.map(async ({ name, url }) => {
      const xml = await fetchCachedRss(url);
      return xml ? parseRssItems(xml, name) : [];
    })
  );

  const allArticles = results.flat();
  const now         = Date.now();

  // Filter: stock-relevant + within last 24 hours
  const relevant = allArticles.filter(a =>
    (now - a.publishedAt) <= MAX_NEWS_AGE_MS && isNewsRelevant(a.headline, terms)
  );

  // Deduplicate by first 60 chars of headline, sort newest first, cap at 10
  const seen   = new Set();
  const unique = relevant
    .sort((a, b) => b.publishedAt - a.publishedAt)
    .filter(a => {
      const key = a.headline.toLowerCase().slice(0, 60);
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    })
    .slice(0, 10);

  const sourceCount = new Set(unique.map(a => a.source)).size;
  console.log(`News [${stock}] matched=${unique.length} total=${allArticles.length} sources=${sourceCount}`);

  return new Response(
    JSON.stringify({ articles: unique, stockCount: unique.length, totalFetched: allArticles.length, sourceCount }),
    { headers: corsHeaders() },
  );
}

// ── Global Trends helpers ─────────────────────────────────────────────────────

/**
 * Fetch global index quotes — uses v8/chart (chartToQuote) in parallel for all symbols.
 * chartToQuote is more reliable than v7/quote for indices + commodities + forex because:
 *   - Works without a crumb (v8/chart is more permissive than v7/quote)
 *   - Correctly handles closed-market change% by computing from price - prevClose
 *   - Used by the existing /quotes handler as Strategy 4 (proven reliable)
 */
async function fetchGlobalIndexQuotes() {
  const symbols = Object.keys(GLOBAL_INDEX_META);
  const results = await Promise.all(symbols.map(sym => chartToQuote(sym)));
  return results.filter(q => q !== null && (q.regularMarketPrice ?? 0) > 0);
}

/** Re-usable: fetch all 12 RSS sources in parallel (each edge-cached 15 min). */
async function fetchAllRssArticles() {
  const results = await Promise.all(
    RSS_SOURCES.map(async ({ name, url }) => {
      const xml = await fetchCachedRss(url);
      return xml ? parseRssItems(xml, name) : [];
    })
  );
  return results.flat();
}

/** Yahoo v7/quote with custom fields (used to retrieve sector data). */
async function fetchQuotesWithFields(symbols, fields) {
  for (const [base, crumb, cookies] of [[YAHOO1, _crumb, _cookies], [YAHOO1, "", ""], [YAHOO2, "", ""]]) {
    try {
      const url  = `${base}/v7/finance/quote?symbols=${enc(symbols)}&fields=${enc(fields)}${crumbParam(crumb)}`;
      const resp = await fetch(url, { headers: yahooHeaders(cookies) });
      if (resp.ok) {
        const j = await resp.json();
        const r = j?.quoteResponse?.result ?? [];
        if (r.length > 0) return r;
      }
    } catch { /* next */ }
  }
  return [];
}

/**
 * Build Map<sector, ImpactedStock[]> from FALLBACK_INDIA_STOCKS (hardcoded).
 * Sectors are pre-assigned so this never depends on Yahoo returning sector metadata.
 * Cached for 1 hour.
 */
async function buildIndianSectorMap() {
  const now = Date.now();
  if (_indSectors && (now - _indSectorsTs) < IND_SECTORS_TTL) return _indSectors;

  const sectorMap = new Map();
  for (const stock of FALLBACK_INDIA_STOCKS) {
    if (!sectorMap.has(stock.sector)) sectorMap.set(stock.sector, []);
    sectorMap.get(stock.sector).push({
      symbol:    stock.symbol,
      nseSymbol: stock.nseSymbol,
      name:      stock.name,
      sector:    stock.sector,
    });
  }

  _indSectors   = sectorMap;
  _indSectorsTs = now;
  console.log(`Indian sector map built: ${sectorMap.size} sectors (hardcoded)`);
  return sectorMap;
}

/**
 * Fetch top US movers.
 * Primary:  Yahoo screener (requires crumb) — dynamic, any stock.
 * Fallback: chartToQuote for FALLBACK_US_STOCKS (no crumb needed) — always works.
 */
async function fetchTopUSMovers() {
  // Primary: Yahoo screener when crumb is available
  if (_crumb) {
    const url = `${YAHOO1}/v1/finance/screener?formatted=false&lang=en-US&region=US&crumb=${enc(_crumb)}`;
    const body = JSON.stringify({
      offset: 0, size: 25,
      sortField: "percentchange", sortType: "DESC",
      quoteType: "EQUITY", topOperator: "AND",
      query: {
        operator: "AND",
        operands: [
          { operator: "OR", operands: [
            { operator: "EQ", operands: ["exchange", "NMS"] },
            { operator: "EQ", operands: ["exchange", "NYQ"] },
          ]},
          { operator: "GT", operands: ["intradayprice",        5]      },
          { operator: "GT", operands: ["avgdailyvolume3month", 500000] },
        ],
      },
      userId: "", userIdType: "guid",
    });
    try {
      const resp = await fetch(url, {
        method: "POST",
        headers: { ...yahooHeaders(_cookies), "Content-Type": "application/json" },
        body,
      });
      if (resp.ok) {
        const j = await resp.json();
        const quotes = j?.finance?.result?.[0]?.quotes ?? [];
        const movers = quotes
          .filter(q => q?.sector && Math.abs(q?.regularMarketChangePercent ?? 0) >= 0.5)
          .slice(0, 15)
          .map(q => ({
            symbol:    q.symbol ?? "",
            name:      q.longName ?? q.shortName ?? q.symbol ?? "",
            changePct: q.regularMarketChangePercent ?? 0,
            sector:    q.sector ?? "",
            direction: (q.regularMarketChangePercent ?? 0) >= 0 ? "UP" : "DOWN",
          }));
        if (movers.length > 0) {
          console.log(`US movers: screener returned ${movers.length}`);
          return movers;
        }
      }
    } catch (e) {
      console.log("US movers screener error:", e.message);
    }
  }

  // Fallback: chartToQuote for known US large-caps — no crumb needed
  console.log("US movers: using fallback chartToQuote for known large-caps");
  const results = await Promise.all(
    FALLBACK_US_STOCKS.map(async ({ symbol, name, sector }) => {
      const q = await chartToQuote(symbol);
      if (!q || Math.abs(q.regularMarketChangePercent ?? 0) < 0.5) return null;
      return {
        symbol,
        name:      q.longName || q.shortName || name,
        changePct: q.regularMarketChangePercent ?? 0,
        sector,
        direction: (q.regularMarketChangePercent ?? 0) >= 0 ? "UP" : "DOWN",
      };
    })
  );
  const movers = results.filter(Boolean);
  console.log(`US movers: fallback returned ${movers.length} movers`);
  return movers;
}

function computeOverallBias(items) {
  const weight = { "^DJI": 2, "^GSPC": 2, "^IXIC": 2, "^N225": 1, "^HSI": 1, "^FTSE": 1, "^NSEI": 1.5, "^NSEBANK": 1.5 };
  let score = 0;
  for (const { symbol, changePct } of items) {
    const w = weight[symbol] ?? 1;
    score += (changePct >= 0 ? 1 : -1) * w;
  }
  if (score > 2)  return "BULLISH";
  if (score < -2) return "BEARISH";
  return "NEUTRAL";
}

/**
 * GET /global-trends
 *
 * Returns global market indices + impact cards linking foreign movers to Indian stocks.
 * Full response cached 15 min in Worker memory. Indian sector map cached 1 hour.
 */
async function handleGlobalTrends() {
  const now = Date.now();
  if (_trendsBody && (now - _trendsTs) < TRENDS_TTL) {
    return new Response(_trendsBody, { headers: { ...corsHeaders(), "X-Cache": "HIT" } });
  }

  // Fetch all data in parallel
  const [globalQuotes, usMovers, allRssArticles, indSectorMap] = await Promise.all([
    fetchGlobalIndexQuotes(),   // chartToQuote per-symbol — reliable for indices/forex/commodities
    fetchTopUSMovers(),
    fetchAllRssArticles(),
    buildIndianSectorMap(),
  ]);

  // ── Bucket global indices by group ────────────────────────────────────────
  const groups = { us: [], asia: [], india: [], commodities: [], forex: [] };
  for (const q of globalQuotes) {
    const meta = GLOBAL_INDEX_META[q.symbol];
    if (!meta) continue;
    groups[meta.group]?.push({
      symbol:    q.symbol,
      name:      meta.name,
      price:     q.regularMarketPrice         ?? 0,
      changePct: q.regularMarketChangePercent ?? 0,
      direction: (q.regularMarketChangePercent ?? 0) >= 0 ? "UP" : "DOWN",
    });
  }

  // ── Build impact cards ────────────────────────────────────────────────────
  // One card per sector — pick the US mover with highest |changePct| per sector
  const sectorBest = new Map();
  for (const mover of usMovers) {
    const existing = sectorBest.get(mover.sector);
    if (!existing || Math.abs(mover.changePct) > Math.abs(existing.changePct)) {
      sectorBest.set(mover.sector, mover);
    }
  }

  const impacts = [];
  for (const [sector, mover] of sectorBest) {
    const indianStocks = indSectorMap.get(sector) ?? [];
    if (indianStocks.length === 0) continue;

    // News headlines that mention the company name or sector keywords
    const terms = [
      mover.name.split(" ").slice(0, 2).join(" "), // first two words of company name
      mover.symbol.replace(/\.\w+$/, ""),           // ticker without exchange suffix
      sector,
    ].filter(t => t.length >= 3);
    const headlines = allRssArticles
      .filter(a => (now - a.publishedAt) <= MAX_TRENDS_NEWS_AGE_MS && isNewsRelevant(a.headline, terms))
      .sort((a, b) => b.publishedAt - a.publishedAt)
      .slice(0, 3)
      .map(a => a.headline);

    impacts.push({
      foreignSymbol:    mover.symbol,
      foreignName:      mover.name,
      foreignChangePct: mover.changePct,
      direction:        mover.direction,
      sector:           sector,
      impactDirection:  mover.direction === "UP" ? "POSITIVE" : "NEGATIVE",
      indianStocks:     indianStocks.slice(0, 5),
      newsHeadlines:    headlines,
    });
  }
  // Biggest movers first
  impacts.sort((a, b) => Math.abs(b.foreignChangePct) - Math.abs(a.foreignChangePct));

  const biasInput = [...groups.us, ...groups.asia, ...groups.india];
  const overallBias = computeOverallBias(biasInput);

  const payload = {
    us:          groups.us,
    asia:        groups.asia,
    india:       groups.india,
    commodities: groups.commodities,
    forex:       groups.forex,
    impacts:     impacts.slice(0, 8),
    overallBias: overallBias,
    cachedAt:    now,
  };

  console.log(`GlobalTrends: indices=${globalQuotes.length} movers=${usMovers.length} impacts=${impacts.length} bias=${overallBias}`);
  _trendsBody = JSON.stringify(payload);
  _trendsTs   = now;
  return new Response(_trendsBody, { headers: { ...corsHeaders(), "X-Cache": "MISS" } });
}

// ── Main entry point ──────────────────────────────────────────────────────────

export default {
  async fetch(request, env) {
    const url    = new URL(request.url);
    const apiKey = env?.TWELVE_DATA_KEY ?? ""; // set via: wrangler secret put TWELVE_DATA_KEY

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    // Best-effort Yahoo session init (never crashes if Yahoo is blocked)
    await ensureSession();

    try {
      const { pathname, searchParams } = url;
      if (pathname === "/screener") return handleScreener(searchParams, apiKey);
      if (pathname === "/quotes")   return handleQuotes(searchParams, apiKey);
      if (pathname === "/history")  return handleHistory(searchParams, apiKey);
      if (pathname === "/profile")  return handleProfile(searchParams);
      if (pathname === "/news")          return handleNews(searchParams);
      if (pathname === "/global-trends") return handleGlobalTrends();
      return jsonError(`Unknown endpoint: ${pathname}`, 404);
    } catch (e) {
      return jsonError(e.message, 500);
    }
  },
};
