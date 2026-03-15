# NSEAssist — Cloudflare Worker Proxy

One-time setup. Free forever. Makes the app significantly faster and more reliable.

---

## What this does

Instead of your phone doing a complex 3-step auth dance with Yahoo Finance every time,
this tiny Worker runs on Cloudflare's edge (200+ cities worldwide) and:

- Handles Yahoo Finance cookies + crumb **server-side** — your phone just does plain GETs
- **Caches screener results for 5 minutes** — second scan is instant
- Returns the exact same JSON format your app already parses — zero Android code changes needed
- Free tier: 100,000 requests/day — you'd need to scan 6,000 times/day to hit the limit

---

## Step-by-step deployment (15 minutes, one time)

### Step 1 — Create a free Cloudflare account

Go to **https://cloudflare.com** → click "Sign Up" → use any email, no credit card needed.

---

### Step 2 — Install Node.js (if not already installed)

Download from **https://nodejs.org** → install the LTS version.

Verify it works by opening Terminal and running:
```
node --version
```
You should see something like `v20.x.x`.

---

### Step 3 — Install Wrangler (Cloudflare's deploy tool)

In Terminal:
```bash
npm install -g wrangler
```

---

### Step 4 — Log in to Cloudflare

```bash
wrangler login
```

This opens a browser window → click "Allow" → you're logged in. Return to Terminal.

---

### Step 5 — Deploy the Worker

Navigate to the cloudflare-worker folder:
```bash
cd /Users/rishabsingh/Documents/OP/NSEAssist/cloudflare-worker
```

Deploy:
```bash
wrangler deploy
```

You'll see output like:
```
Total Upload: 3.45 KiB / gzip: 1.82 KiB
Uploaded nseassist-proxy (1.23 sec)
Deployed nseassist-proxy triggers (0.47 sec)
  https://nseassist-proxy.rishabsingh.workers.dev
```

**Copy that URL.** That's your Worker URL.

---

### Step 6 — Paste the URL into the Android app

Open:
```
NSEAssist/app/src/main/java/com/nseassist/data/api/YahooFinanceClient.kt
```

Find this line (around line 60):
```kotlin
private const val WORKER_URL = ""  // e.g. "https://nseassist-proxy.rishabsingh.workers.dev"
```

Replace the empty string with your URL:
```kotlin
private const val WORKER_URL = "https://nseassist-proxy.rishabsingh.workers.dev"
```

---

### Step 7 — Rebuild and install the app

In Android Studio: **Build → Rebuild Project** → run on your device.

---

## Testing the Worker directly

You can test it in your browser or with curl:

```bash
# Test screener (should return NSE stocks JSON)
curl "https://nseassist-proxy.rishabsingh.workers.dev/screener?maxPrice=500&minVolume=1000000"

# Test batch quotes
curl "https://nseassist-proxy.rishabsingh.workers.dev/quotes?symbols=RELIANCE.NS,TCS.NS"

# Test history
curl "https://nseassist-proxy.rishabsingh.workers.dev/history?symbol=RELIANCE.NS&days=90"
```

Each should return JSON. The screener response will include an `X-Cache: HIT` header after the first call.

---

## Updating the Worker later

If you need to change the Worker code, just run `wrangler deploy` again from the same folder.
The URL stays the same — no need to update the Android app.

---

## Free tier limits

| Limit | Free | Your usage |
|-------|------|-----------|
| Requests/day | 100,000 | ~50–100 scans/day → well within limit |
| CPU time/request | 10ms | Worker uses ~2–5ms |
| Storage | N/A | Worker uses in-memory cache only |

---

## Troubleshooting

**"Session init failed" error in app logs**
Yahoo Finance occasionally blocks automated requests. The Worker will retry automatically on the next request. If it persists, run `wrangler tail` in Terminal to see Worker logs in real time.

**Worker returns empty screener results**
The app falls back to direct Yahoo Finance automatically. If this happens often, check Worker logs: `wrangler tail`

**"command not found: wrangler"**
Node.js wasn't installed correctly. Try: `npm install -g wrangler` again, or restart Terminal.
