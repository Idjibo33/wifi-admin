package dev.wifiadmin.app.data.speedtest

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.speedTestHistory by preferencesDataStore(name = "wifi_admin_speedtest")

/**
 * A bounded local history of finished tests.
 *
 * Stored in the same DataStore family as the other preferences and listed
 * newest first. No password, session, token, MAC, SSID or public IP is
 * ever stored. The number of entries is capped and any entry older than
 * 30 days is purged on write.
 */
object SpeedTestHistoryCodec {

    const val MAX_ENTRIES = 20
    private const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000

    fun encode(results: List<SpeedTestResult>): String {
        val array = JSONArray()
        for (r in results.take(MAX_ENTRIES)) {
            array.put(JSONObject().apply {
                put("dl", r.downloadMbps ?: JSONObject.NULL)
                put("ul", r.uploadMbps ?: JSONObject.NULL)
                put("latency", r.latencyMillis ?: JSONObject.NULL)
                put("jitter", r.jitterMillis ?: JSONObject.NULL)
                put("dlBytes", r.downloadBytes)
                put("ulBytes", r.uploadBytes)
                put("durationMs", r.durationMillis)
                put("capped", r.capped)
                put("provider", r.providerLabel)
                put("network", r.networkKey)
                put("at", r.capturedAtWallMillis)
            })
        }
        return array.toString()
    }

    fun decode(raw: String?, nowMillis: Long): List<SpeedTestResult> {
        if (raw.isNullOrBlank()) return emptyList()
        val out = mutableListOf<SpeedTestResult>()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val at = o.optLong("at", 0L)
            if (at > 0 && nowMillis - at > MAX_AGE_MILLIS) continue
            if (at <= 0) continue
            out += SpeedTestResult(
                downloadMbps = if (o.isNull("dl")) null else o.optDouble("dl").takeIf { !it.isNaN() },
                uploadMbps = if (o.isNull("ul")) null else o.optDouble("ul").takeIf { !it.isNaN() },
                latencyMillis = if (o.isNull("latency")) null else o.optLong("latency"),
                jitterMillis = if (!o.has("jitter") || o.isNull("jitter")) null
                    else o.optDouble("jitter").takeIf { !it.isNaN() },
                downloadBytes = o.optLong("dlBytes", 0),
                uploadBytes = o.optLong("ulBytes", 0),
                durationMillis = o.optLong("durationMs", 0),
                capped = o.optBoolean("capped", false),
                providerLabel = o.optString("provider", "Inconnu"),
                networkKey = o.optString("network", ""),
                wifiLink = null,
                capturedAtWallMillis = at,
            )
        }
        return out
    }
}

class SpeedTestPreferencesStore(private val context: Context) {
    private object Keys {
        val CONSENT = androidx.datastore.preferences.core.booleanPreferencesKey("speedtest_consent")
        val HISTORY = stringPreferencesKey("speedtest_history_json")
    }

    val hasConsented: Flow<Boolean> = context.speedTestHistory.data.map { it[Keys.CONSENT] ?: false }
    /** The newest first list of finished tests. */
    val history: Flow<List<SpeedTestResult>> = context.speedTestHistory.data.map {
        SpeedTestHistoryCodec.decode(it[Keys.HISTORY], System.currentTimeMillis())
    }

    suspend fun setConsent(agreed: Boolean) {
        context.speedTestHistory.edit { it[Keys.CONSENT] = agreed }
    }

    suspend fun saveResult(result: SpeedTestResult) {
        context.speedTestHistory.edit { prefs ->
            val existing = SpeedTestHistoryCodec.decode(prefs[Keys.HISTORY], System.currentTimeMillis())
            val next = (listOf(result) + existing).take(SpeedTestHistoryCodec.MAX_ENTRIES)
            prefs[Keys.HISTORY] = SpeedTestHistoryCodec.encode(next)
        }
    }

    suspend fun clearHistory() {
        context.speedTestHistory.edit { it.remove(Keys.HISTORY) }
    }
}
