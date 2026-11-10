package dev.ring.health

import android.content.Context
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.vmUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One sample [metric] (`mark_N` for Settings › Things to mark, `moment_<kind>` for Right now) at [tsMs], cut to the whole second; [cancel] with the same
 * [tsMs] takes it back (value 2 over 1: the larger value wins on the phone and the server, nothing is deleted).
 */
suspend fun mark(ctx: Context, metric: String, tsMs: Long, cancel: Boolean = false) = pushSample(ctx, metric, if (cancel) 2.0 else 1.0, tsMs)

/** Stored first, so a sample survives no network; a failed push leaves the row for the next sync. */
suspend fun pushSample(ctx: Context, metric: String, value: Double, tsMs: Long) {
    withContext(Dispatchers.IO) {
        val row = listOf(Metric(metric, mapOf("device" to "colmi_r02"), value, tsMs / 1000 * 1000))
        val store = SampleStore.get(ctx)
        store.add(row)
        val url = ctx.vmUrl
        if (url.isNotBlank() && runCatching { MetricsPusher(url, ctx.apiToken).push(row) }.getOrDefault(false)) store.markSent(row)
    }
}
