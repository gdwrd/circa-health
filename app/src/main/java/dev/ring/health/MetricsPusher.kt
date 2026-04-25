package dev.ring.health

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private const val TAG = "MetricsPusher"

data class Metric(
    val name: String,
    val labels: Map<String, String> = emptyMap(),
    val value: Double,
    val timestampMs: Long
)

class MetricsPusher(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Push metrics in Prometheus text format to VictoriaMetrics /api/v1/import/prometheus.
    // Timestamps in the exposition format are in milliseconds (VM accepts both ms and s).
    fun push(metrics: List<Metric>): Boolean {
        if (metrics.isEmpty()) return true

        val body = buildString {
            for (m in metrics) {
                append(m.name)
                if (m.labels.isNotEmpty()) {
                    append('{')
                    m.labels.entries.forEachIndexed { i, (k, v) ->
                        if (i > 0) append(',')
                        append(k).append("=\"").append(v).append('"')
                    }
                    append('}')
                }
                append(' ').append(m.value).append(' ').append(m.timestampMs).append('\n')
            }
        }

        Log.d(TAG, "Pushing ${metrics.size} metrics:\n$body")

        val request = Request.Builder()
            .url("$baseUrl/api/v1/import/prometheus")
            .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Push failed: HTTP ${response.code} — ${response.body?.string()}")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            Log.e(TAG, "Push exception: ${e.message}")
            false
        }
    }
}
