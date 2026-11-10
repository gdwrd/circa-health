package dev.ring.health

import android.content.Context
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.vmUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One mark of thing [slot] (1..3, Settings › Things to mark) at [tsMs], cut to the whole second; [cancel] with the same
 * [tsMs] takes it back (value 2 over 1: the larger value wins on the phone and the server, nothing is deleted).
 * Stored first, so a mark survives no network; a failed push leaves the row for the next sync.
 */
suspend fun mark(ctx: Context, slot: Int, tsMs: Long, cancel: Boolean = false) {
    withContext(Dispatchers.IO) {
        val row = listOf(Metric("mark_$slot", mapOf("device" to "colmi_r02"), if (cancel) 2.0 else 1.0, tsMs / 1000 * 1000))
        val store = SampleStore.get(ctx)
        store.add(row)
        val url = ctx.vmUrl
        if (url.isNotBlank() && runCatching { MetricsPusher(url, ctx.apiToken).push(row) }.getOrDefault(false)) store.markSent(row)
    }
}
