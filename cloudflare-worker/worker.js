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
const MAX_NEWS_AGE_MS = 24 * 60 * 60 * 1000;

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
      if (pathname === "/news")     return handleNews(searchParams);
      return jsonError(`Unknown endpoint: ${pathname}`, 404);
    } catch (e) {
      return jsonError(e.message, 500);
    }
  },
};
