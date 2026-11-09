package dev.ring.health.ui

import android.content.Context
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.vmUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Client for the optional backend at the same base URL the ring pushes to. */
object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun request(ctx: Context, path: String, method: String = "GET", json: String? = null): Request? {
        val base = ctx.vmUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val token = ctx.apiToken
        return runCatching {
            Request.Builder().url("$base$path")
                .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }
                .method(method, if (method == "GET" || method == "DELETE") null else (json ?: "").toRequestBody("application/json".toMediaType()))
                .build()
        }.getOrNull()
    }

    /** Parsed JSON body on 2xx, else null. */
    private suspend fun call(ctx: Context, path: String, method: String = "GET", json: String? = null): JsonElement? = withContext(Dispatchers.IO) {
        val req = request(ctx, path, method, json) ?: return@withContext null
        runCatching {
            client.newCall(req).execute().use { r ->
                if (r.isSuccessful) Json.parseToJsonElement(r.body!!.string()) else null
            }
        }.getOrNull()
    }

    private suspend fun get(ctx: Context, path: String) = call(ctx, path)

    suspend fun today(ctx: Context) = get(ctx, "/today")
    suspend fun today(ctx: Context, date: LocalDate) = get(ctx, "/today?date=$date")
    suspend fun sleep(ctx: Context, date: String) = get(ctx, "/sleep?date=$date")
    suspend fun trends(ctx: Context, metric: String, days: Int) = get(ctx, "/trends?metric=$metric&days=$days")
    suspend fun week(ctx: Context) = get(ctx, "/week")
    suspend fun month(ctx: Context, ym: String) = get(ctx, "/month?month=$ym")
    suspend fun hr(ctx: Context, hours: Int) = get(ctx, "/hr?hours=$hours")
    suspend fun hr(ctx: Context, date: LocalDate) = get(ctx, "/hr?date=$date")
    suspend fun activity(ctx: Context, date: LocalDate) = get(ctx, "/activity?date=$date")
    suspend fun stress(ctx: Context, date: LocalDate) = get(ctx, "/stress?date=$date")
    suspend fun journal(ctx: Context, days: Int) = get(ctx, "/journal?days=$days")
    suspend fun insights(ctx: Context) = get(ctx, "/journal/insights")
    suspend fun settings(ctx: Context) = get(ctx, "/settings") as? JsonObject
    suspend fun notifications(ctx: Context) = get(ctx, "/notifications/pending")

    suspend fun ack(ctx: Context, id: Int): Boolean = call(ctx, "/notifications/$id/ack", "POST") != null

    /** Returns the new entry id, or null on failure. */
    suspend fun addJournal(ctx: Context, date: String, kind: String, value: String? = null, note: String? = null): Int? {
        val body = JsonObject(mapOf("date" to JsonPrimitive(date), "kind" to JsonPrimitive(kind), "value" to JsonPrimitive(value), "note" to JsonPrimitive(note)))
        return ((call(ctx, "/journal", "POST", body.toString()) as? JsonObject)?.get("id") as? JsonPrimitive)?.content?.toIntOrNull()
    }

    suspend fun deleteJournal(ctx: Context, id: Int): Boolean = call(ctx, "/journal/$id", "DELETE") != null

    /** Body is a subset of the backend settings keys; returns the full settings, or null on failure. */
    suspend fun patchSettings(ctx: Context, body: Map<String, JsonPrimitive>) = call(ctx, "/settings", "PATCH", JsonObject(body).toString()) as? JsonObject

    /** PATCH /workouts relabels one detected workout ([start] = its unix start); returns `{workouts: [...]}` for the day. */
    suspend fun setWorkoutKind(ctx: Context, date: LocalDate, start: Long, kind: String) =
        call(ctx, "/workouts", "PATCH", JsonObject(mapOf("date" to JsonPrimitive(date.toString()), "start" to JsonPrimitive(start), "kind" to JsonPrimitive(kind))).toString()) as? JsonObject

    /** PATCH /sleep overrides the night ending on [date] (unix s); returns GET /sleep, or null on failure. */
    suspend fun setSleep(ctx: Context, date: LocalDate, start: Long, end: Long) =
        call(ctx, "/sleep", "PATCH", JsonObject(mapOf("date" to JsonPrimitive(date.toString()), "start" to JsonPrimitive(start), "end" to JsonPrimitive(end))).toString()) as? JsonObject

    /** DELETE /sleep drops the override, back to the ring's times. */
    suspend fun clearSleep(ctx: Context, date: LocalDate) = call(ctx, "/sleep?date=$date", "DELETE") as? JsonObject

    /** POST /breathe; [samples] are (epoch ms, bpm), kept only inside [startMs, endMs]. True on 2xx. */
    suspend fun postBreathe(ctx: Context, startMs: Long, endMs: Long, rounds: Int, feelBefore: Int?, feelAfter: Int?, samples: List<Pair<Long, Int>>): Boolean {
        val start = startMs / 1000
        val end = maxOf(endMs / 1000, start + 1)
        val pts = samples.filter { it.first / 1000.0 in start.toDouble()..end.toDouble() && it.second in 30..220 }.takeLast(1200)
            .map { JsonArray(listOf(JsonPrimitive(it.first / 1000.0), JsonPrimitive(it.second))) }
        val body = JsonObject(mapOf(
            "start" to JsonPrimitive(start), "end" to JsonPrimitive(end), "rounds" to JsonPrimitive(rounds),
            "feel_before" to JsonPrimitive(feelBefore), "feel_after" to JsonPrimitive(feelAfter), "samples" to JsonArray(pts),
        ))
        return call(ctx, "/breathe", "POST", body.toString()) != null
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
            runCatching { probe.newCall(req).execute().use { it.code == 200 } }.getOrNull()
        }
        // An exception (network or VPN still waking) is not an answer: don't cache, so the next call re-probes.
        ok?.let { cache = k to it }
        return ok ?: false
    }
}
