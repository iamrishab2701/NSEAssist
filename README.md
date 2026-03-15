# NSEAssist

Personal intraday trading assistant for NSE India. Built for Zerodha Kite traders.

## What it does

- Scans 200–300 liquid NSE stocks against your capital
- Phase 1: quick-scores all affordable stocks (screener + batch quotes)
- Phase 2: deep analysis of top 15 (RSI, EMA, MACD, Bollinger, ADX, support/resistance, price prediction)
- AI Analysis: sends enriched data to Claude / OpenAI / Gemini for a full trading plan
- Stock detail screen with technical indicators, trend labels, news sentiment

## Stack

- **Android** — Kotlin, Jetpack Compose, MVVM, OkHttp
- **Data** — Yahoo Finance (via Cloudflare Worker proxy)
- **Proxy** — Cloudflare Worker (`cloudflare-worker/`) handles Yahoo session management server-side
- **AI** — pluggable: Claude (Anthropic), GPT-4o (OpenAI), Gemini

## Setup

### Android App
1. Open in Android Studio
2. Build and run on device or emulator (API 26+)
3. Add an AI provider API key in Settings to enable in-app AI Analysis

### Cloudflare Worker (optional — improves reliability)
```bash
cd cloudflare-worker
npm install -g wrangler
wrangler login
wrangler deploy
# Optional: add Twelve Data as fallback
wrangler secret put TWELVE_DATA_KEY
```

## Architecture

```
Phase 1 (fast)   → Screener → batch quotes → score all stocks
Phase 2 (deep)   → Top 15 → history + indicators + prediction (parallel, 5 per batch)
AI Analysis      → Top 20 enriched → sent to AI provider → trading plan
Stock Detail     → instant if Phase 2 ran, otherwise fresh fetch
```

## Disclaimer

For personal educational use only. Not SEBI-registered financial advice.
All trading decisions and risks are solely the user's responsibility.
