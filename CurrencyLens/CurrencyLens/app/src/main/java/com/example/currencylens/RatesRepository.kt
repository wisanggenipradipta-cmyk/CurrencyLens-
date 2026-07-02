package com.example.currencylens

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exchange rates, expressed as units-per-1-USD.
 *
 * Source: https://open.er-api.com/v6/latest/USD (free, no API key).
 * Cached in SharedPreferences for 12 hours; hardcoded fallback keeps the
 * app usable with no network at all (rates will just be approximate).
 */
class RatesRepository(context: Context) {

    private val prefs = context.getSharedPreferences("rates_cache", Context.MODE_PRIVATE)

    @Volatile
    var rates: Map<String, Double> = loadCached() ?: FALLBACK
        private set

    @Volatile
    var lastUpdatedMillis: Long = prefs.getLong(KEY_TIME, 0L)
        private set

    val isLive: Boolean get() = lastUpdatedMillis > 0L

    suspend fun refreshIfStale(): Boolean = withContext(Dispatchers.IO) {
        val age = System.currentTimeMillis() - lastUpdatedMillis
        if (age < MAX_AGE_MS && rates !== FALLBACK) return@withContext true
        try {
            val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(body)
            if (json.optString("result") != "success") return@withContext false
            val ratesJson = json.getJSONObject("rates")
            val map = HashMap<String, Double>(ratesJson.length())
            for (key in ratesJson.keys()) {
                map[key] = ratesJson.getDouble(key)
            }
            rates = map
            lastUpdatedMillis = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_JSON, ratesJson.toString())
                .putLong(KEY_TIME, lastUpdatedMillis)
                .apply()
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Cross-rate conversion via USD. Returns null if either currency is unknown. */
    fun convert(amount: Double, from: String, to: String): Double? {
        val rf = rates[from] ?: return null
        val rt = rates[to] ?: return null
        return amount / rf * rt
    }

    private fun loadCached(): Map<String, Double>? {
        val raw = prefs.getString(KEY_JSON, null) ?: return null
        return try {
            val json = JSONObject(raw)
            val map = HashMap<String, Double>(json.length())
            for (key in json.keys()) map[key] = json.getDouble(key)
            map
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val API_URL = "https://open.er-api.com/v6/latest/USD"
        private const val KEY_JSON = "rates_json"
        private const val KEY_TIME = "rates_time"
        private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L

        // Approximate offline fallback (per 1 USD). Refreshed values replace these on first fetch.
        private val FALLBACK: Map<String, Double> = mapOf(
            "USD" to 1.0, "EUR" to 0.92, "GBP" to 0.78, "JPY" to 155.0,
            "IDR" to 16250.0, "SGD" to 1.34, "MYR" to 4.45, "THB" to 34.5,
            "VND" to 25500.0, "PHP" to 57.0, "CNY" to 7.2, "KRW" to 1370.0,
            "INR" to 84.0, "AUD" to 1.52, "AED" to 3.6725, "CHF" to 0.88,
            "HKD" to 7.8, "TWD" to 32.3, "SAR" to 3.75
        )
    }
}
