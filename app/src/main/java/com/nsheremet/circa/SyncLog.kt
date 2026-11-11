package com.nsheremet.circa

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug log of sync activity (ring reads, server pushes). Temporary: remove together with the Sync log screen. */
object SyncLog {
    private const val MAX_LINES = 300

    private fun file(ctx: Context) = File(ctx.filesDir, "sync_log.txt")

    // shortcut: rewrites the whole file per line; fine at 300 lines, switch to a ring buffer if it grows.
    @Synchronized
    fun add(ctx: Context, msg: String) {
        val f = file(ctx)
        val line = SimpleDateFormat("d MMM HH:mm:ss", Locale.US).format(Date()) + "  " + msg
        val lines = (if (f.exists()) f.readLines() else emptyList()) + line
        f.writeText(lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
    }

    /** Newest first. */
    @Synchronized
    fun read(ctx: Context): List<String> = file(ctx).takeIf { it.exists() }?.readLines()?.reversed() ?: emptyList()

    @Synchronized
    fun clear(ctx: Context) {
        file(ctx).delete()
    }
}
