package com.nseassist.data.api

import android.util.Log
import com.nseassist.data.model.NewsArticle
import com.nseassist.data.model.NewsResult
import com.nseassist.data.model.NewsSentiment
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NewsClient {

    private const val TAG = "NewsClient"
    private const val MAX_NEWS_AGE_MS = 24L * 60L * 60L * 1000L

    private val client = OkHttpClient.Builder()
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Accept", "application/rss+xml, application/xml, text/xml, */*")
                    .build()
            )
        }
        .followRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val trackedSources = listOf(
        SourceConfig("Moneycontrol", "moneycontrol.com"),
        SourceConfig("Economic Times", "economictimes.indiatimes.com"),
        SourceConfig("Mint", "livemint.com"),
        SourceConfig("Business Standard", "business-standard.com"),
        SourceConfig("CNBC TV18", "cnbctv18.com"),
        SourceConfig("Reuters", "reuters.com"),
        SourceConfig("NDTV Profit", "ndtvprofit.com"),
        SourceConfig("Business Today", "businesstoday.in"),
        SourceConfig("Financial Express", "financialexpress.com"),
        SourceConfig("BusinessLine", "thehindubusinessline.com"),
        SourceConfig("Fortune India", "fortuneindia.com"),
        SourceConfig("Outlook Business", "outlookbusiness.com"),
        SourceConfig("Zee Business", "zeebiz.com"),
        SourceConfig("Investing", "investing.com"),
        SourceConfig("MarketScreener", "marketscreener.com"),
        SourceConfig("Bloomberg", "bloomberg.com"),
        SourceConfig("The Hindu", "thehindu.com"),
        SourceConfig("Hindustan Times", "hindustantimes.com"),
    )

    private val positiveWords = setOf(
        "profit", "growth", "record", "beat", "upgrade", "outperform", "strong", "gains",
        "rally", "surges", "jumps", "wins", "order", "approval", "bullish", "buyback", "recovery"
    )

    private val negativeWords = setOf(
        "loss", "fraud", "probe", "investigation", "downgrade", "miss", "weak", "falls", "drops",
        "slumps", "plunges", "bearish", "sell", "warning", "concern", "penalty", "debt", "raid"
    )

    suspend fun fetchNews(
        stockSymbol: String,
        companyName: String = "",
        sector: String = "",
        industry: String = "",
    ): NewsResult? = runCatching {
        coroutineScope {
            val searchTerms = stockQueries(stockSymbol, companyName)
            val stockTasks = trackedSources.flatMap { source ->
                searchTerms.map { query -> async { fetchSourceArticles(query = query, source = source) } }
            }

            // Use industry if available (more specific than sector)
            val sectorQuery = industry.ifBlank { sector }
            val sectorTasks = trackedSources.mapNotNull { source ->
                sectorQuery.takeIf { it.isNotBlank() }?.let { q ->
                    async { fetchSourceArticles(query = "\"$q\" India stocks intraday", source = source) }
                }
            }

            val stockArticles = stockTasks.awaitAll().flatten().filter { isStockRelevant(it.headline, stockSymbol, companyName) }
            val sectorArticles = sectorTasks.awaitAll().flatten().filter { isSectorRelevant(it.headline, sectorQuery) }
            aggregateNews(stockSymbol, sectorQuery, stockArticles, sectorArticles)
        }
    }.getOrElse {
        Log.w(TAG, "News fetch failed for $stockSymbol: ${it.message}")
        null
    }

    private fun stockQueries(stockSymbol: String, companyName: String): List<String> {
        val allTerms = StockAliasMap.getAllSearchTerms(stockSymbol, companyName)
        val queries = mutableListOf<String>()

        allTerms.forEach { term ->
            // Primary: exact quoted search to reduce noise
            queries += "\"$term\" NSE India"
            // Secondary: intraday/share-specific search
            queries += "\"$term\" share price India"
        }

        return queries.distinct()
    }

    private suspend fun fetchSourceArticles(query: String, source: SourceConfig): List<ParsedArticle> {
        val encoded = URLEncoder.encode("$query site:${source.domain}", "UTF-8")
        val url = "https://news.google.com/rss/search?q=$encoded&hl=en-IN&gl=IN&ceid=IN:en"
        val body = runCatching { execute(url) }.getOrNull() ?: return emptyList()
        return parseItems(body, source.label)
    }

    private fun aggregateNews(
        stockName: String,
        sector: String,
        stockArticles: List<ParsedArticle>,
        sectorArticles: List<ParsedArticle>,
    ): NewsResult? {
        val now = System.currentTimeMillis()
        val filteredStock = stockArticles.filter { now - it.publishedAt <= MAX_NEWS_AGE_MS }
        val filteredSector = sectorArticles.filter { now - it.publishedAt <= MAX_NEWS_AGE_MS }
        val allArticles = (filteredStock + filteredSector)
            .distinctBy { it.headline.lowercase() }
            .sortedByDescending { it.publishedAt }

        if (allArticles.isEmpty()) return null

        val stockSentiment = aggregateSentiment(filteredStock)
        val sectorSentiment = aggregateSentiment(filteredSector)
        val overallSentiment = aggregateSentiment(allArticles)
        val visibleArticles = allArticles.take(6).map {
            NewsArticle(
                headline = it.headline.take(120),
                source = it.source,
                ageText = formatAge(it.publishedAt),
                sentiment = it.sentiment,
            )
        }

        return NewsResult(
            sentiment = overallSentiment,
            headline = visibleArticles.firstOrNull()?.headline ?: "",
            ageText = visibleArticles.firstOrNull()?.ageText ?: "",
            stockSentiment = stockSentiment,
            sectorSentiment = sectorSentiment,
            stockArticleCount = filteredStock.size,
            sectorArticleCount = filteredSector.size,
            sourceCount = allArticles.map { it.source }.distinct().size,
            sector = sector,
            articles = visibleArticles,
        ).also {
            Log.d(TAG, "News aggregated for $stockName — stock=${filteredStock.size}, sector=${filteredSector.size}, sources=${it.sourceCount}")
        }
    }

    private fun aggregateSentiment(articles: List<ParsedArticle>): NewsSentiment {
        if (articles.isEmpty()) return NewsSentiment.NONE
        val positive = articles.count { it.sentiment == NewsSentiment.POSITIVE }
        val negative = articles.count { it.sentiment == NewsSentiment.NEGATIVE }
        return when {
            positive > negative -> NewsSentiment.POSITIVE
            negative > positive -> NewsSentiment.NEGATIVE
            positive > 0 || negative > 0 -> NewsSentiment.NEUTRAL
            else -> NewsSentiment.NONE
        }
    }

    private fun isStockRelevant(headline: String, stockSymbol: String, companyName: String): Boolean {
        val normalized = headline.lowercase()
        val allTerms = StockAliasMap.getAllSearchTerms(stockSymbol, companyName)

        for (term in allTerms) {
            val termLower = term.lowercase()

            // Exact full-term match — strongest signal
            if (normalized.contains(termLower)) return true

            // Cleaned term match (strip Ltd/Limited/Corporation)
            val cleaned = termLower
                .replace("limited", "")
                .replace("corporation", "")
                .replace("industries", "")
                .replace("ltd", "")
                .replace("india", "")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.length >= 5 && normalized.contains(cleaned)) return true

            // Multi-token match — require ALL significant tokens to match
            val tokens = cleaned
                .split(Regex("[^a-z0-9]+"))
                .filter { it.length >= 4 }
                .filter { it !in STOP_WORDS }

            if (tokens.isNotEmpty() && tokens.all { normalized.contains(it) }) return true
        }

        return false
    }

    private val STOP_WORDS = setOf(
        "bank", "corp", "tech", "fund", "life", "home", "auto", "data",
        "info", "soft", "ware", "care", "labs", "ways", "power", "grid",
    )

    private fun isSectorRelevant(headline: String, sector: String): Boolean {
        if (sector.isBlank()) return false
        val normalized = headline.lowercase()
        val sectorTokens = sector.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 4 }
        return sectorTokens.any { normalized.contains(it) }
    }

    private fun parseItems(xml: String, source: String): List<ParsedArticle> = runCatching {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        val results = mutableListOf<ParsedArticle>()
        var inItem = false
        var title = ""
        var pubDate = ""

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item" -> {
                        inItem = true
                        title = ""
                        pubDate = ""
                    }
                    "title" -> if (inItem && title.isEmpty()) title = parser.nextText().trim()
                    "pubdate" -> if (inItem && pubDate.isEmpty()) pubDate = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name.lowercase() == "item" && inItem) {
                    if (title.isNotBlank()) {
                        results += ParsedArticle(
                            headline = title,
                            source = source,
                            publishedAt = parseDate(pubDate),
                            sentiment = classifySentiment(title),
                        )
                    }
                    inItem = false
                }
            }
            eventType = parser.next()
        }
        results
    }.getOrElse {
        Log.w(TAG, "RSS parse error: ${it.message}")
        emptyList()
    }

    private fun classifySentiment(headline: String): NewsSentiment {
        val lower = headline.lowercase()
        val posScore = positiveWords.count { lower.contains(it) }
        val negScore = negativeWords.count { lower.contains(it) }
        return when {
            posScore > negScore -> NewsSentiment.POSITIVE
            negScore > posScore -> NewsSentiment.NEGATIVE
            posScore > 0 || negScore > 0 -> NewsSentiment.NEUTRAL
            else -> NewsSentiment.NONE
        }
    }

    private fun parseDate(pubDate: String): Long = runCatching {
        rssDateFormat.parse(pubDate)?.time ?: 0L
    }.getOrDefault(0L)

    private fun formatAge(publishedAt: Long): String {
        if (publishedAt <= 0L) return ""
        val diffMs = System.currentTimeMillis() - publishedAt
        val minutes = (diffMs / 60_000).coerceAtLeast(0)
        return when {
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            else -> "${minutes / (60 * 24)}d ago"
        }
    }

    private suspend fun execute(url: String): String? = suspendCancellableCoroutine { cont ->
        val call = client.newCall(Request.Builder().url(url).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isActive) return
                response.use { resp ->
                    if (resp.code == 200) cont.resume(resp.body?.string())
                    else {
                        Log.w(TAG, "HTTP ${resp.code} for $url")
                        cont.resume(null)
                    }
                }
            }
        })
    }

    private data class SourceConfig(val label: String, val domain: String)

    private data class ParsedArticle(
        val headline: String,
        val source: String,
        val publishedAt: Long,
        val sentiment: NewsSentiment,
    )

    private val rssDateFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.ENGLISH)
}
