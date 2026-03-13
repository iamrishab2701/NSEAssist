package com.nseassist.data.api

import com.google.gson.JsonObject
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

interface YahooFinanceApi {

    // Single ticker chart — used for live snapshot + intraday VWAP
    @GET("v8/finance/chart/{symbol}")
    suspend fun getChart(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
        @Query("range") range: String = "1d",
    ): JsonObject

    // Historical data — used for RSI, EMA, MACD, trend, prediction
    @GET("v8/finance/chart/{symbol}")
    suspend fun getHistory(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1d",
        @Query("range") range: String = "3mo",
    ): JsonObject

    // 1-min candles for intraday VWAP calculation
    @GET("v8/finance/chart/{symbol}")
    suspend fun getIntraday(
        @Path("symbol") symbol: String,
        @Query("interval") interval: String = "1m",
        @Query("range") range: String = "1d",
    ): JsonObject

    companion object {
        private const val BASE_URL = "https://query1.finance.yahoo.com/"

        fun create(): YahooFinanceApi {
            val logger = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.NONE
            }
            val client = OkHttpClient.Builder()
                .addInterceptor(logger)
                .addInterceptor { chain ->
                    // Yahoo Finance requires a browser-like User-Agent
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                        .header("Accept", "application/json")
                        .build()
                    chain.proceed(request)
                }
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(YahooFinanceApi::class.java)
        }
    }
}
