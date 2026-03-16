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

// ── Intraday 15-min cache (per symbol) ────────────────────────────────────────
const _intradayCache = new Map();   // symbol → { body: string, ts: number }
const INTRADAY_TTL   = 15 * 60 * 1000;  // 15 minutes (matches candle interval)

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

// ── Lean US stock list for Global Trends impact cards ─────────────────────────
// 1-2 key stocks per sector, used in handleGlobalTrends to stay under the
// Cloudflare Workers 50-subrequest limit. (Full list used only by /screener fallback.)
const TRENDS_US_STOCKS = [
  { symbol: "AAPL",  name: "Apple Inc.",           sector: "Technology"            },
  { symbol: "NVDA",  name: "NVIDIA Corp.",          sector: "Technology"            },
  { symbol: "JPM",   name: "JPMorgan Chase",        sector: "Financial Services"    },
  { symbol: "GS",    name: "Goldman Sachs",         sector: "Financial Services"    },
  { symbol: "XOM",   name: "Exxon Mobil",           sector: "Energy"                },
  { symbol: "UNH",   name: "UnitedHealth Group",    sector: "Healthcare"            },
  { symbol: "AMZN",  name: "Amazon.com Inc.",       sector: "Consumer Cyclical"     },
  { symbol: "WMT",   name: "Walmart Inc.",          sector: "Consumer Defensive"    },
  { symbol: "CAT",   name: "Caterpillar Inc.",      sector: "Industrials"           },
  { symbol: "FCX",   name: "Freeport-McMoRan",      sector: "Basic Materials"       },
  { symbol: "NFLX",  name: "Netflix Inc.",          sector: "Communication Services"},
  { symbol: "NEE",   name: "NextEra Energy",        sector: "Utilities"             },
  { symbol: "PLD",   name: "Prologis Inc.",         sector: "Real Estate"           },
];

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

// ── Indian ADRs listed on NYSE/NASDAQ ─────────────────────────────────────────
// These are the most direct pre-market signals for how Indian stocks will open.
// ADR close on NYSE = strong predictor for NSE open next morning.
const INDIAN_ADRS = [
  { symbol: "INFY",  nseSymbol: "INFY",       name: "Infosys Ltd."           },
  { symbol: "WIT",   nseSymbol: "WIPRO",      name: "Wipro Ltd."             },
  { symbol: "HDB",   nseSymbol: "HDFCBANK",   name: "HDFC Bank Ltd."         },
  { symbol: "IBN",   nseSymbol: "ICICIBANK",  name: "ICICI Bank Ltd."        },
  { symbol: "RDY",   nseSymbol: "DRREDDY",    name: "Dr. Reddy's Labs"       },
  { symbol: "TTM",   nseSymbol: "TATAMOTORS", name: "Tata Motors Ltd."       },
];

// ── Commodity → Indian stock correlation map ──────────────────────────────────
// invertImpact: crude UP → OMC refiners sell off (pass-through lag), so impact is NEGATIVE
const COMMODITY_INDIA_MAP = {
  "CL=F": {
    sector: "Energy",
    invertImpact: true,   // crude UP = bad for refining OMCs
    indianStocks: [
      { symbol: "ONGC.NS",     nseSymbol: "ONGC",     name: "ONGC",          sector: "Energy" },
      { symbol: "BPCL.NS",     nseSymbol: "BPCL",     name: "BPCL",          sector: "Energy" },
      { symbol: "IOC.NS",      nseSymbol: "IOC",       name: "Indian Oil",    sector: "Energy" },
      { symbol: "RELIANCE.NS", nseSymbol: "RELIANCE",  name: "Reliance",      sector: "Energy" },
      { symbol: "GAIL.NS",     nseSymbol: "GAIL",      name: "GAIL",          sector: "Energy" },
    ],
    keywords: ["crude", "oil", "petroleum", "OPEC", "energy", "brent"],
  },
  "GC=F": {
    sector: "Precious Metals",
    invertImpact: false,  // gold UP = positive for jewelry/gold finance stocks
    indianStocks: [
      { symbol: "TITAN.NS",      nseSymbol: "TITAN",      name: "Titan Company",   sector: "Consumer Cyclical" },
      { symbol: "MUTHOOTFIN.NS", nseSymbol: "MUTHOOTFIN", name: "Muthoot Finance", sector: "Financial Services" },
      { symbol: "MANAPPURAM.NS", nseSymbol: "MANAPPURAM", name: "Manappuram Fin.", sector: "Financial Services" },
    ],
    keywords: ["gold", "bullion", "precious metals", "yellow metal"],
  },
};

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
  // ── Core Indian financial news ──────────────────────────────────────────────
  { name: "Moneycontrol",         url: "https://www.moneycontrol.com/rss/buzzingstocks.xml" },
  { name: "MC Market Reports",    url: "https://www.moneycontrol.com/rss/marketreports.xml" },
  { name: "MC Results",           url: "https://www.moneycontrol.com/rss/results.xml" },
  { name: "Economic Times",       url: "https://economictimes.indiatimes.com/markets/stocks/rssfeeds/2146842.cms" },
  { name: "ET Markets",           url: "https://economictimes.indiatimes.com/markets/rssfeeds/1977021501.cms" },
  { name: "ET Derivatives",       url: "https://economictimes.indiatimes.com/markets/derivatives/rssfeeds/2146846.cms" },
  { name: "Business Standard",    url: "https://www.business-standard.com/rss/markets-106.rss" },
  { name: "BS Companies",         url: "https://www.business-standard.com/rss/companies-101.rss" },
  { name: "LiveMint",             url: "https://www.livemint.com/rss/markets" },
  { name: "Financial Express",    url: "https://www.financialexpress.com/market/feed/" },
  { name: "Reuters India",        url: "https://feeds.reuters.com/reuters/INbusinessNews" },
  { name: "Business Today",       url: "https://www.businesstoday.in/rss/topic/markets" },
  { name: "The Hindu Business",   url: "https://www.thehindu.com/business/?service=rss" },
  { name: "Hindu BusinessLine",   url: "https://www.thehindubusinessline.com/companies/?service=rss" },
  { name: "NDTV Profit",          url: "https://www.ndtvprofit.com/rss" },
  { name: "CNBC TV18",            url: "https://www.cnbctv18.com/rss" },
  // ── Additional intraday-focused sources ─────────────────────────────────────
  { name: "BQ Prime",             url: "https://www.bqprime.com/rss/markets" },
  { name: "Zee Business",         url: "https://zeebiz.com/markets/rss" },
  { name: "Investing.com India",  url: "https://in.investing.com/rss/news_25.rss" },
  { name: "Firstpost Business",   url: "https://www.firstpost.com/rss/business.xml" },
  { name: "Outlook Business",     url: "https://www.outlookindia.com/business/rss" },
  { name: "The Print Economy",    url: "https://theprint.in/category/economy/feed/" },
];

const NEWS_UA   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36";
const ITEM_RE   = /<item[^>]*>([\s\S]*?)<\/item>/gi;
const TITLE_RE  = /<title[^>]*>([\s\S]*?)<\/title>/i;
const DATE_RE   = /<pubDate[^>]*>([\s\S]*?)<\/pubDate>/i;
const POS_WORDS = [
  // Price action
  "surge","surges","rally","rallies","gain","gains","rise","rises","up","jumps","jump","soars","soar","spike",
  // Momentum / technical
  "bullish","buy","breakout","breakouts","multibagger","52-week high","all-time high","upper circuit",
  // Fundamentals
  "profit","profits","growth","strong","record","beat","beats","outperform","positive","boost","boost",
  "revenue up","earnings up","profit up","beats estimate","beats expectations","better than expected",
  // Corporate actions (direct intraday movers)
  "dividend","buyback","bonus","stock split","acquisition","merger","contract","order win","order wins",
  "bulk buy","block buy","insider buying","stake increase","promoter buying","fii buying","dii buying",
  "upgrade","target raised","target hike","rating upgrade","strong buy","overweight","accumulate",
  // Results
  "q1 profit","q2 profit","q3 profit","q4 profit","quarterly profit","net profit up","results beat",
];
const NEG_WORDS = [
  // Price action
  "fall","falls","drop","drops","crash","crashes","decline","declines","down","slumps","slump","plunge","plunges","lower circuit",
  // Momentum / technical
  "bearish","sell","breakdown","breakdowns","52-week low","all-time low",
  // Fundamentals
  "loss","losses","weak","concern","risk","miss","misses","underperform","negative",
  "revenue down","earnings down","profit down","misses estimate","misses expectations","below expected",
  // Corporate / regulatory risk (direct intraday movers)
  "default","writeoff","write-off","fraud","probe","notice","penalty","sebi notice","tax demand",
  "downgrade","target cut","rating downgrade","sell rating","underweight","reduce","avoid",
  "bulk sell","block sell","insider selling","stake sale","promoter selling","fii selling",
  "restructure","debt","npa","haircut","insolvency","nclt","bankruptcy",
  // Results
  "q1 loss","q2 loss","q3 loss","q4 loss","quarterly loss","net loss","results miss",
];
const MAX_NEWS_AGE_MS        = 12 * 60 * 60 * 1000; // /news endpoint: 12 hours (intraday focus)
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

  // Filter: stock-relevant + within last 12 hours (intraday focus)
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

/**
 * Fetch US movers using the lean TRENDS_US_STOCKS list.
 * 13 chartToQuote calls = 13 subrequests (vs 36 for fetchTopUSMovers with screener fallback).
 */
async function fetchTrendsUSMovers() {
  const results = await Promise.all(
    TRENDS_US_STOCKS.map(async ({ symbol, name, sector }) => {
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
  console.log(`Trends US movers: ${movers.length}/${TRENDS_US_STOCKS.length}`);
  return movers;
}

/**
 * Fetch Indian ADR quotes.
 * Primary:  Twelve Data /quote — reliable, no crumb needed, works from Cloudflare IPs.
 * Fallback: Yahoo Finance v7/quote batch with explicit fields.
 *
 * symbolMap for Twelve Data: { "INFY:NYSE": "INFY", "WIT:NYSE": "WIT", ... }
 * The "INFY:NYSE" key disambiguates NYSE ADR from NSE "INFY:NSE".
 */
async function fetchAdrBatch(apiKey) {
  // Primary: Twelve Data — proven to work from Cloudflare Workers.
  // US stocks don't need exchange suffix on Twelve Data; bare ticker is sufficient.
  if (apiKey) {
    const symbolMap = Object.fromEntries(
      INDIAN_ADRS.map(a => [a.symbol, a.symbol])   // "INFY" → "INFY"
    );
    const results = await fetchTwelveQuotes(symbolMap, apiKey);
    if (results.length > 0) {
      console.log(`ADR batch (Twelve Data): ${results.length}/${INDIAN_ADRS.length}`);
      return results;
    }
    console.log(`ADR batch: Twelve Data returned empty (apiKey=${apiKey ? "set" : "missing"}), trying Yahoo`);
  }

  // Fallback: Yahoo Finance v7/quote batch with explicit fields
  const symbols = INDIAN_ADRS.map(a => a.symbol).join(",");
  const fields  = "regularMarketPrice,regularMarketChangePercent,regularMarketPreviousClose";
  for (const [base, crumb, cookies] of [
    [YAHOO1, _crumb, _cookies],
    [YAHOO1, "",     ""       ],
    [YAHOO2, "",     ""       ],
  ]) {
    try {
      const url  = `${base}/v7/finance/quote?symbols=${enc(symbols)}&fields=${enc(fields)}${crumbParam(crumb)}`;
      const resp = await fetch(url, { headers: yahooHeaders(cookies) });
      if (!resp.ok) continue;
      const j   = await resp.json();
      const res = j?.quoteResponse?.result ?? [];
      if (res.length > 0) {
        console.log(`ADR batch (Yahoo): ${res.length}/${INDIAN_ADRS.length}`);
        return res;
      }
    } catch (e) { console.log("ADR Yahoo error:", e.message); }
  }
  console.log("ADR batch: all sources failed");
  return [];
}

/** Returns "STRONG" for sectors with well-known US→India correlations, else "MODERATE". */
function getSectorCorrelationStrength(sector) {
  const strong = new Set(["Technology", "Financial Services", "Energy", "Healthcare", "Basic Materials"]);
  return strong.has(sector) ? "STRONG" : "MODERATE";
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
async function handleGlobalTrends(apiKey = "") {
  const now = Date.now();
  if (_trendsBody && (now - _trendsTs) < TRENDS_TTL) {
    return new Response(_trendsBody, { headers: { ...corsHeaders(), "X-Cache": "HIT" } });
  }

  // ── Budget-aware parallel fetch ───────────────────────────────────────────
  // Cloudflare Workers free tier: 50 subrequests per invocation.
  //   fetchGlobalIndexQuotes : 12  (one chartToQuote per symbol)
  //   fetchTrendsUSMovers    : 13  (lean list — one per sector)
  //   fetchAdrBatch          :  1  (single Twelve Data batch)
  //   buildIndianSectorMap   :  0  (hardcoded, no network)
  // Total: ~26 subrequests. Well under the limit.
  // RSS is intentionally skipped here; all 22 sources are used by the /news endpoint.
  const [globalQuotes, usMovers, indSectorMap, rawAdrQuotes] = await Promise.all([
    fetchGlobalIndexQuotes(),
    fetchTrendsUSMovers(),
    buildIndianSectorMap(),
    fetchAdrBatch(apiKey),
  ]);
  const allRssArticles = [];  // no RSS in trends — saves ~22 subrequests

  // ── Build Indian ADR items ─────────────────────────────────────────────────
  const adrMap = new Map(rawAdrQuotes.map(q => [q.symbol, q]));
  const adrs = INDIAN_ADRS.map(adr => {
    const q = adrMap.get(adr.symbol);
    if (!q) return null;
    const price = q.regularMarketPrice ?? 0;
    if (price === 0) return null;
    const prevClose = q.regularMarketPreviousClose ?? 0;
    let changePct   = q.regularMarketChangePercent ?? 0;
    // Recompute if Yahoo returns 0 outside market hours
    if (changePct === 0 && price > 0 && prevClose > 0) {
      changePct = ((price - prevClose) / prevClose) * 100;
    }
    return {
      symbol:    adr.symbol,
      name:      `${adr.name} (→ ${adr.nseSymbol})`,
      price,
      changePct,
      direction: changePct >= 0 ? "UP" : "DOWN",
    };
  }).filter(Boolean);
  console.log(`Indian ADRs fetched: ${adrs.length}/${INDIAN_ADRS.length}`);

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
      foreignSymbol:       mover.symbol,
      foreignName:         mover.name,
      foreignChangePct:    mover.changePct,
      direction:           mover.direction,
      sector:              sector,
      impactDirection:     mover.direction === "UP" ? "POSITIVE" : "NEGATIVE",
      indianStocks:        indianStocks.slice(0, 5),
      newsHeadlines:       headlines,
      correlationStrength: getSectorCorrelationStrength(sector),
    });
  }

  // ── Commodity impact cards ────────────────────────────────────────────────
  for (const [sym, meta] of Object.entries(COMMODITY_INDIA_MAP)) {
    const cq = globalQuotes.find(q => q.symbol === sym);
    if (!cq) continue;
    const changePct = cq.regularMarketChangePercent ?? 0;
    if (Math.abs(changePct) < 0.5) continue;
    const direction = changePct >= 0 ? "UP" : "DOWN";
    const impactDirection = meta.invertImpact
      ? (direction === "UP" ? "NEGATIVE" : "POSITIVE")
      : (direction === "UP" ? "POSITIVE" : "NEGATIVE");

    const headlines = allRssArticles
      .filter(a => (now - a.publishedAt) <= MAX_TRENDS_NEWS_AGE_MS && isNewsRelevant(a.headline, meta.keywords))
      .sort((a, b) => b.publishedAt - a.publishedAt)
      .slice(0, 3)
      .map(a => a.headline);

    impacts.push({
      foreignSymbol:       sym,
      foreignName:         GLOBAL_INDEX_META[sym]?.name ?? sym,
      foreignChangePct:    changePct,
      direction,
      sector:              meta.sector,
      impactDirection,
      indianStocks:        meta.indianStocks,
      newsHeadlines:       headlines,
      correlationStrength: "STRONG",
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
    adrs:        adrs,
    impacts:     impacts.slice(0, 10),
    overallBias: overallBias,
    cachedAt:    now,
  };

  console.log(`GlobalTrends: indices=${globalQuotes.length} movers=${usMovers.length} impacts=${impacts.length} bias=${overallBias}`);
  _trendsBody = JSON.stringify(payload);
  _trendsTs   = now;
  return new Response(_trendsBody, { headers: { ...corsHeaders(), "X-Cache": "MISS" } });
}

// ── Intraday 5-min candles ────────────────────────────────────────────────────
//
// Returns today's 5-min OHLCV bars for a symbol plus:
//   orbHigh / orbLow  — Opening Range (first 2 candles: 9:15–9:25 AM)
//   pivotCpp / pivotR1 / pivotS1 — Daily pivot points from yesterday's data
//
// Uses Yahoo v8/chart which works without a crumb.
// Cached per symbol for 5 minutes.

async function handleIntraday(searchParams) {
  const symbol = (searchParams.get("symbol") ?? "").trim();
  if (!symbol) return new Response(JSON.stringify({ error: "symbol required" }), { status: 400, headers: corsHeaders() });

  const now    = Date.now();
  const cached = _intradayCache.get(symbol);
  if (cached && (now - cached.ts) < INTRADAY_TTL) {
    return new Response(cached.body, { headers: { ...corsHeaders(), "Content-Type": "application/json", "X-Cache": "HIT" } });
  }

  // Fetch today's 15-min bars + 5-day daily (for pivot calculation)
  // Uses v8/chart which works without crumb from Cloudflare datacenters
  async function chartFetch(url) {
    for (const base of [YAHOO1, YAHOO2]) {
      try {
        const resp = await fetch(url.replace(YAHOO1, base), { headers: yahooHeaders(_cookies) });
        if (resp.ok) return await resp.text();
      } catch (_) {}
    }
    return null;
  }

  const [intraDayRaw, dailyRaw] = await Promise.all([
    chartFetch(`${YAHOO1}/v8/finance/chart/${enc(symbol)}?interval=15m&range=1d`),
    chartFetch(`${YAHOO1}/v8/finance/chart/${enc(symbol)}?interval=1d&range=5d`),
  ]);

  // Parse 15-min candles
  let candles = [];
  let orbHigh = 0, orbLow = 0;
  if (intraDayRaw) {
    try {
      const json  = JSON.parse(intraDayRaw);
      const chart = json?.chart?.result?.[0];
      if (chart) {
        const ts  = chart.timestamp ?? [];
        const q   = chart.indicators?.quote?.[0] ?? {};
        candles = ts.map((t, i) => ({
          time:   t,
          open:   q.open?.[i]   ?? null,
          high:   q.high?.[i]   ?? null,
          low:    q.low?.[i]    ?? null,
          close:  q.close?.[i]  ?? null,
          volume: q.volume?.[i] ?? null,
        })).filter(c => c.open != null && c.high != null && c.low != null && c.close != null);

        // ORB = first 2 candles (9:15–9:30 + 9:30–9:45 AM = 30-min opening range on 15-min bars)
        const orb = candles.slice(0, 2);
        orbHigh = orb.length ? Math.max(...orb.map(c => c.high)) : 0;
        orbLow  = orb.length ? Math.min(...orb.map(c => c.low))  : 0;
      }
    } catch (_) {}
  }

  // Parse pivot points from yesterday's daily candle
  let pivotCpp = 0, pivotR1 = 0, pivotR2 = 0, pivotS1 = 0, pivotS2 = 0;
  if (dailyRaw) {
    try {
      const json  = JSON.parse(dailyRaw);
      const chart = json?.chart?.result?.[0];
      if (chart) {
        const q    = chart.indicators?.quote?.[0] ?? {};
        const bars = (chart.timestamp ?? []).length;
        if (bars >= 2) {
          // Use second-to-last bar as "yesterday"
          const prevIdx = bars - 2;
          const pH = q.high?.[prevIdx]  ?? 0;
          const pL = q.low?.[prevIdx]   ?? 0;
          const pC = q.close?.[prevIdx] ?? 0;
          if (pH && pL && pC) {
            pivotCpp = (pH + pL + pC) / 3;
            pivotR1  = 2 * pivotCpp - pL;
            pivotR2  = pivotCpp + (pH - pL);
            pivotS1  = 2 * pivotCpp - pH;
            pivotS2  = pivotCpp - (pH - pL);
          }
        }
      }
    } catch (_) {}
  }

  const body = JSON.stringify({
    candles,
    orbHigh:  Math.round(orbHigh  * 100) / 100,
    orbLow:   Math.round(orbLow   * 100) / 100,
    pivotCpp: Math.round(pivotCpp * 100) / 100,
    pivotR1:  Math.round(pivotR1  * 100) / 100,
    pivotR2:  Math.round(pivotR2  * 100) / 100,
    pivotS1:  Math.round(pivotS1  * 100) / 100,
    pivotS2:  Math.round(pivotS2  * 100) / 100,
  });

  _intradayCache.set(symbol, { body, ts: now });
  return new Response(body, { headers: { ...corsHeaders(), "Content-Type": "application/json", "X-Cache": "MISS" } });
}

// ── Main entry point ──────────────────────────────────────────────────────────

// ── D1 Trade Memory ────────────────────────────────────────────────────────────
//
// Tables:
//   paper_trades    — user-logged AI trade setups + outcome tracking
//   signal_outcomes — historical signal firing results (from Signal Replay)
//   prediction_log  — daily price prediction vs actual close tracking
//
// Setup (one-time):
//   wrangler d1 create nseassist-memory  → copy database_id into wrangler.toml
//   wrangler deploy

let _dbInitialized = false;

async function ensureDb(env) {
  if (_dbInitialized || !env?.DB) return;
  try {
    await env.DB.exec(`
      CREATE TABLE IF NOT EXISTS paper_trades (
        id            INTEGER PRIMARY KEY AUTOINCREMENT,
        symbol        TEXT    NOT NULL,
        company_name  TEXT    DEFAULT '',
        direction     TEXT    DEFAULT 'BUY',
        entry_price   REAL    DEFAULT 0,
        target_text   TEXT    DEFAULT '',
        stop_loss_text TEXT   DEFAULT '',
        quantity      INTEGER DEFAULT 0,
        session_phase TEXT    DEFAULT '',
        ai_provider   TEXT    DEFAULT '',
        confidence    INTEGER DEFAULT 0,
        verdict       TEXT    DEFAULT '',
        rr_ratio      TEXT    DEFAULT '',
        reason        TEXT    DEFAULT '',
        logged_at     INTEGER NOT NULL,
        outcome       TEXT    DEFAULT 'OPEN',
        outcome_price REAL,
        outcome_time  INTEGER
      )
    `);
    await env.DB.exec(`
      CREATE TABLE IF NOT EXISTS signal_outcomes (
        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
        symbol               TEXT    NOT NULL,
        signal_date          INTEGER NOT NULL,
        rsi                  REAL    DEFAULT 0,
        above_vwap           INTEGER DEFAULT 0,
        macd_bullish         INTEGER DEFAULT 0,
        trend_signal         TEXT    DEFAULT '',
        session_phase        TEXT    DEFAULT '',
        market_condition     TEXT    DEFAULT '',
        prediction_direction TEXT    DEFAULT '',
        score                REAL    DEFAULT 0,
        outcome              TEXT    DEFAULT 'UNKNOWN',
        created_at           INTEGER NOT NULL
      )
    `);
    await env.DB.exec(`
      CREATE TABLE IF NOT EXISTS prediction_log (
        id                   INTEGER PRIMARY KEY AUTOINCREMENT,
        symbol               TEXT    NOT NULL,
        prediction_direction TEXT    NOT NULL,
        predicted_high       REAL    DEFAULT 0,
        predicted_low        REAL    DEFAULT 0,
        confidence           INTEGER DEFAULT 0,
        actual_close         REAL,
        was_correct          INTEGER,
        logged_at            INTEGER NOT NULL,
        checked_at           INTEGER
      )
    `);
    _dbInitialized = true;
  } catch(e) {
    console.log("DB init error:", e.message);
  }
}

async function cleanupOldData(env) {
  if (!env?.DB) return;
  const cutoff = Date.now() - (180 * 24 * 60 * 60 * 1000); // 6 months
  try {
    await env.DB.prepare("DELETE FROM paper_trades    WHERE logged_at  < ?").bind(cutoff).run();
    await env.DB.prepare("DELETE FROM signal_outcomes WHERE created_at < ?").bind(cutoff).run();
    await env.DB.prepare("DELETE FROM prediction_log  WHERE logged_at  < ?").bind(cutoff).run();
    console.log("Memory cleanup complete. Cutoff:", new Date(cutoff).toISOString());
  } catch(e) {
    console.log("Cleanup error:", e.message);
  }
}

async function handleMemory(pathname, request, env) {
  await ensureDb(env);
  if (!env?.DB) return jsonError("Memory not available — add DB binding in wrangler.toml", 503);

  try {

    // ── POST /memory/trade — log a paper trade ────────────────────────────────
    if (pathname === "/memory/trade" && request.method === "POST") {
      const b = await request.json();
      const r = await env.DB.prepare(
        `INSERT INTO paper_trades
         (symbol,company_name,direction,entry_price,target_text,stop_loss_text,
          quantity,session_phase,ai_provider,confidence,verdict,rr_ratio,reason,logged_at)
         VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)`
      ).bind(
        b.symbol || '', b.company_name || '', b.direction || 'BUY',
        b.entry_price || 0, b.target_text || '', b.stop_loss_text || '',
        b.quantity || 0, b.session_phase || '', b.ai_provider || '',
        b.confidence || 0, b.verdict || '', b.rr_ratio || '', b.reason || '',
        Date.now()
      ).run();
      return new Response(JSON.stringify({ id: r.meta?.last_row_id ?? 0 }), { headers: corsHeaders() });
    }

    // ── GET /memory/trades?symbol=X ───────────────────────────────────────────
    if (pathname === "/memory/trades" && request.method === "GET") {
      const sym = new URL(request.url).searchParams.get("symbol");
      const rows = sym
        ? await env.DB.prepare("SELECT * FROM paper_trades WHERE symbol=? ORDER BY logged_at DESC LIMIT 100").bind(sym).all()
        : await env.DB.prepare("SELECT * FROM paper_trades ORDER BY logged_at DESC LIMIT 200").all();
      return new Response(JSON.stringify({ trades: rows.results ?? [] }), { headers: corsHeaders() });
    }

    // ── PATCH /memory/trade-outcome — update outcome ──────────────────────────
    if (pathname === "/memory/trade-outcome" && request.method === "PATCH") {
      const b = await request.json();
      await env.DB.prepare("UPDATE paper_trades SET outcome=?,outcome_price=?,outcome_time=? WHERE id=?")
        .bind(b.outcome, b.outcome_price ?? null, Date.now(), b.id).run();
      return new Response(JSON.stringify({ ok: true }), { headers: corsHeaders() });
    }

    // ── POST /memory/signal — bulk log signal outcomes ────────────────────────
    if (pathname === "/memory/signal" && request.method === "POST") {
      const body  = await request.json();
      const items = Array.isArray(body) ? body : [body];
      const now   = Date.now();
      for (const s of items) {
        await env.DB.prepare(
          `INSERT INTO signal_outcomes
           (symbol,signal_date,rsi,above_vwap,macd_bullish,trend_signal,
            session_phase,market_condition,prediction_direction,score,outcome,created_at)
           VALUES (?,?,?,?,?,?,?,?,?,?,?,?)`
        ).bind(
          s.symbol || '', s.signal_date || now, s.rsi || 0, s.above_vwap ? 1 : 0,
          s.macd_bullish ? 1 : 0, s.trend_signal || '', s.session_phase || '',
          s.market_condition || '', s.prediction_direction || '', s.score || 0,
          s.outcome || 'UNKNOWN', now
        ).run();
      }
      return new Response(JSON.stringify({ ok: true }), { headers: corsHeaders() });
    }

    // ── POST /memory/prediction — log + auto-verify previous prediction ───────
    if (pathname === "/memory/prediction" && request.method === "POST") {
      const b   = await request.json();
      const now = Date.now();
      // If caller sends actual_close, verify the most recent unverified prediction
      if (b.actual_close && b.actual_close > 0) {
        const prev = await env.DB.prepare(
          "SELECT id,prediction_direction FROM prediction_log WHERE symbol=? AND was_correct IS NULL ORDER BY logged_at DESC LIMIT 1"
        ).bind(b.symbol).first();
        if (prev) {
          const correct = prev.prediction_direction === b.prediction_direction ? 1 : 0;
          await env.DB.prepare("UPDATE prediction_log SET was_correct=?,actual_close=?,checked_at=? WHERE id=?")
            .bind(correct, b.actual_close, now, prev.id).run();
        }
      }
      await env.DB.prepare(
        `INSERT INTO prediction_log (symbol,prediction_direction,predicted_high,predicted_low,confidence,logged_at)
         VALUES (?,?,?,?,?,?)`
      ).bind(b.symbol, b.prediction_direction || '', b.predicted_high || 0,
             b.predicted_low || 0, b.confidence || 0, now).run();
      return new Response(JSON.stringify({ ok: true }), { headers: corsHeaders() });
    }

    // ── GET /memory/ai-context?symbol=X — aggregated context for AI prompt ────
    if (pathname === "/memory/ai-context" && request.method === "GET") {
      const sym = new URL(request.url).searchParams.get("symbol");
      if (!sym) return jsonError("symbol required", 400);
      const ago30 = Date.now() - 30 * 24 * 60 * 60 * 1000;

      const [sigRows, predRows, tradeRows, sessRows] = await Promise.all([
        env.DB.prepare("SELECT outcome FROM signal_outcomes WHERE symbol=? AND created_at>? ORDER BY created_at DESC LIMIT 20").bind(sym, ago30).all(),
        env.DB.prepare("SELECT was_correct FROM prediction_log WHERE symbol=? AND was_correct IS NOT NULL ORDER BY logged_at DESC LIMIT 20").bind(sym).all(),
        env.DB.prepare("SELECT verdict,direction,outcome,confidence FROM paper_trades WHERE symbol=? ORDER BY logged_at DESC LIMIT 5").bind(sym).all(),
        env.DB.prepare("SELECT session_phase,COUNT(*) as total,SUM(CASE WHEN outcome='WIN' THEN 1 ELSE 0 END) as wins FROM signal_outcomes WHERE symbol=? AND created_at>? GROUP BY session_phase").bind(sym, ago30).all(),
      ]);

      const sigs   = sigRows.results   ?? [];
      const preds  = predRows.results  ?? [];
      const trades = tradeRows.results ?? [];
      const sess   = sessRows.results  ?? [];

      const winCount     = sigs.filter(s => s.outcome === 'WIN').length;
      const correctCount = preds.filter(p => p.was_correct === 1).length;

      let bestSession = '', bestRate = 0;
      for (const row of sess) {
        const rate = row.total > 0 ? row.wins / row.total : 0;
        if (rate > bestRate) { bestRate = rate; bestSession = row.session_phase || ''; }
      }

      const hasData = sigs.length > 0 || preds.length > 0 || trades.length > 0;
      return new Response(JSON.stringify({
        symbol: sym, has_data: hasData,
        signal_win_rate:    sigs.length  > 0 ? winCount     / sigs.length  : 0,
        signal_count:       sigs.length,  win_count: winCount,
        best_session:       bestSession,
        prediction_accuracy: preds.length > 0 ? correctCount / preds.length : 0,
        prediction_count:   preds.length,
        recent_trades:      trades,
      }), { headers: corsHeaders() });
    }

    // ── DELETE /memory/cleanup — manual cleanup trigger ───────────────────────
    if (pathname === "/memory/cleanup" && request.method === "DELETE") {
      await cleanupOldData(env);
      return new Response(JSON.stringify({ ok: true }), { headers: corsHeaders() });
    }

    return jsonError(`Unknown memory endpoint: ${pathname}`, 404);
  } catch(e) {
    return jsonError(`Memory error: ${e.message}`, 500);
  }
}

// ── Main fetch + scheduled handlers ──────────────────────────────────────────

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    const { pathname, searchParams } = url;

    // Memory endpoints — route before Yahoo session init (no session needed)
    if (pathname.startsWith("/memory")) return handleMemory(pathname, request, env);

    const apiKey = env?.TWELVE_DATA_KEY ?? ""; // set via: wrangler secret put TWELVE_DATA_KEY

    // Best-effort Yahoo session init (never crashes if Yahoo is blocked)
    await ensureSession();

    try {
      if (pathname === "/screener") return handleScreener(searchParams, apiKey);
      if (pathname === "/quotes")   return handleQuotes(searchParams, apiKey);
      if (pathname === "/history")  return handleHistory(searchParams, apiKey);
      if (pathname === "/profile")  return handleProfile(searchParams);
      if (pathname === "/news")          return handleNews(searchParams);
      if (pathname === "/global-trends") return handleGlobalTrends(apiKey);
      if (pathname === "/intraday")      return handleIntraday(searchParams);
      return jsonError(`Unknown endpoint: ${pathname}`, 404);
    } catch (e) {
      return jsonError(e.message, 500);
    }
  },

  // Cron trigger — auto-cleanup every Sunday at 2 AM UTC (set in wrangler.toml)
  async scheduled(controller, env, ctx) {
    ctx.waitUntil(cleanupOldData(env));
  },
};
