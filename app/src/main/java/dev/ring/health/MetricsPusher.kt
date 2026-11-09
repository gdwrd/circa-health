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

class MetricsPusher(private val baseUrl: String, private val token: String = "") {

    /** Outcome of the last push for the sync log: "HTTP 204", "HTTP 401 ...", or the exception. */
    var lastResult = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    // Push metrics in Prometheus text format to the backend's /api/v1/import/prometheus.
    // Timestamps in the exposition format are in milliseconds.
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
            .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
            .post(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                lastResult = "HTTP ${response.code}"
                if (!response.isSuccessful) {
                    val body = response.body?.string()
                    lastResult += " " + body.orEmpty().take(120)
                    Log.e(TAG, "Push failed: HTTP ${response.code} — $body")
                }
                response.isSuccessful
            }
        } catch (e: Exception) {
            lastResult = e.javaClass.simpleName + ": " + e.message
            Log.e(TAG, "Push exception: ${e.message}")
            false
        }
    }
}
