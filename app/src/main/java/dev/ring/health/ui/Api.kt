package dev.ring.health.ui

import android.content.Context
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.vmUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** Read-only client for the optional backend at the same base URL the ring pushes to. */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun request(ctx: Context, path: String, post: Boolean = false, json: String? = null): Request? {
        val base = ctx.vmUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val token = ctx.apiToken
        return runCatching {
            Request.Builder().url("$base$path")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .apply { if (post) post(json?.toRequestBody("application/json".toMediaType()) ?: ByteArray(0).toRequestBody()) }
                .build()
        }.getOrNull()
    }

    private suspend fun get(ctx: Context, path: String): JsonElement? = withContext(Dispatchers.IO) {
        val req = request(ctx, path) ?: return@withContext null
        runCatching {
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) Json.parseToJsonElement(r.body!!.string()) else null
            }
        }.getOrNull()
    }

    suspend fun today(ctx: Context) = get(ctx, "/today")
    suspend fun sleep(ctx: Context, date: String) = get(ctx, "/sleep?date=$date")
    suspend fun trends(ctx: Context, metric: String, days: Int) = get(ctx, "/trends?metric=$metric&days=$days")
    suspend fun hr(ctx: Context, hours: Int) = get(ctx, "/hr?hours=$hours")
    suspend fun tags(ctx: Context, days: Int) = get(ctx, "/tags?days=$days")
    suspend fun notifications(ctx: Context) = get(ctx, "/notifications/pending")

    suspend fun ack(ctx: Context, id: Int): Boolean = withContext(Dispatchers.IO) {
        val req = request(ctx, "/notifications/$id/ack", post = true) ?: return@withContext false
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun addTag(ctx: Context, tag: String, note: String?): Boolean = withContext(Dispatchers.IO) {
        val body = JsonObject(mapOf("tag" to JsonPrimitive(tag), "note" to JsonPrimitive(note))).toString()
        val req = request(ctx, "/tags", post = true, json = body) ?: return@withContext false
        runCatching { client.newCall(req).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    private val probe = client.newBuilder().callTimeout(3, TimeUnit.SECONDS).build()
    // Session cache of the last probe, keyed by URL+token so editing either re-probes.
    private var cache: Pair<String, Boolean>? = null

    private fun key(ctx: Context) = ctx.vmUrl.trim() + "|" + ctx.apiToken

    /** Instant answer (blank URL or cached probe), or null when a probe is needed. */
    fun cached(ctx: Context): Boolean? {
        if (ctx.vmUrl.isBlank()) return false
        return cache?.takeIf { it.first == key(ctx) }?.second
    }

    /** `/today` answers 200 on the backend; plain VictoriaMetrics answers 404. */
    suspend fun hasBackend(ctx: Context): Boolean {
        cached(ctx)?.let { return it }
        val k = key(ctx)
        val ok = withContext(Dispatchers.IO) {
            val req = request(ctx, "/today") ?: return@withContext false
            runCatching { probe.newCall(req).execute().use { it.code == 200 } }.getOrDefault(false)
        }
        cache = k to ok
        return ok
    }
}
