package com.nsheremet.circa.ui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** The on-phone model the report writer loads; the trial's path, so a file downloaded there is reused. */
internal fun reportModel(ctx: Context) = File(ctx.getExternalFilesDir(null), "model.litertlm")
private fun modelPart(ctx: Context) = File(ctx.getExternalFilesDir(null), "model.download")

private class ModelFile(val label: String, val url: String, val bytes: Long)
private val models = listOf( // E2B first: the default
    ModelFile("Gemma 4 E2B", "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", 2588147712),
    ModelFile("Gemma 4 E4B", "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", 3659530240),
)

private fun gb(bytes: Long) = String.format(Locale.ROOT, "%.1f GB", bytes / 1e9)

internal fun progressText(done: Long, total: Long) = String.format(Locale.ROOT, "Downloading: %d%% (%.1f of %.1f GB)",
    if (total > 0) done * 100 / total else 0, done / 1e9, total / 1e9)

/**
 * Looks for this app's model download in DownloadManager. Returns the running id with its status line,
 * or null with a one-off message once it ended. A finished file becomes [reportModel] only at the expected size.
 */
internal fun checkDownload(ctx: Context, dm: DownloadManager): Pair<Long?, String?> {
    dm.query(DownloadManager.Query()).use { cur ->
        while (cur.moveToNext()) {
            fun col(name: String) = cur.getColumnIndexOrThrow(name)
            val m = models.find { it.url == cur.getString(col(DownloadManager.COLUMN_URI)) } ?: continue
            val id = cur.getLong(col(DownloadManager.COLUMN_ID))
            val reason = cur.getInt(col(DownloadManager.COLUMN_REASON))
            when (cur.getInt(col(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    val part = modelPart(ctx)
                    val msg = when {
                        part.length() != m.bytes -> { part.delete(); "Download failed: wrong size" }
                        !part.renameTo(reportModel(ctx)) -> "Download failed: could not rename"
                        else -> null
                    }
                    dm.remove(id) // deletes only the row's path (model.download), which is gone after the rename
                    return null to msg
                }
                DownloadManager.STATUS_FAILED -> {
                    dm.remove(id)
                    return null to "Download failed: " + when (reason) {
                        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "no space"
                        DownloadManager.ERROR_DEVICE_NOT_FOUND -> "storage not found"
                        in 100..599 -> "HTTP $reason"
                        else -> "error $reason"
                    }
                }
                DownloadManager.STATUS_PAUSED -> return id to "Paused: " + when (reason) {
                    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "waiting for Wi-Fi"
                    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "waiting for network"
                    DownloadManager.PAUSED_WAITING_TO_RETRY -> "waiting to retry"
                    else -> "unknown reason"
                }
                else -> return id to progressText(cur.getLong(col(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)), m.bytes)
            }
        }
    }
    return null to null
}

/**
 * Settings › Reports model rows: installed + delete, a running download + cancel, or the two downloads.
 * [offer] true (the writer was just turned on) shows a dialog offering the default model when none is there.
 */
@Composable
internal fun ReportModelRows(offer: Boolean, onOffered: () -> Unit) {
    val ctx = LocalContext.current
    val c = AppTheme.colors
    val model = remember { reportModel(ctx) }
    val dm = remember { ctx.getSystemService(DownloadManager::class.java) }
    var hasModel by remember { mutableStateOf(model.exists()) }
    var dlId by remember { mutableStateOf<Long?>(null) }
    var dlText by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            val (id, text) = withContext(Dispatchers.IO) { checkDownload(ctx, dm) }
            dlId = id
            if (id != null) dlText = text.orEmpty() else if (text != null) msg = text
            hasModel = model.exists()
            delay(1000)
        }
    }
    fun download(m: ModelFile) {
        if (ctx.getExternalFilesDir(null)!!.usableSpace < m.bytes + 500_000_000L) { msg = "Not enough free space"; return }
        modelPart(ctx).delete() // a stale file would make DownloadManager pick another name
        dlId = dm.enqueue(DownloadManager.Request(Uri.parse(m.url)).setTitle("Circa model").setDescription(m.label)
            .setAllowedOverMetered(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, null, "model.download"))
        dlText = "Waiting to start"
        msg = null
    }

    val id = dlId
    when {
        hasModel -> {
            val len = model.length()
            ValueRow("Model", (models.find { it.bytes == len }?.label ?: "Unknown, ${gb(len)}") + " · on this phone")
            Hairline(Modifier.padding(start = AppSpace.l))
            ButtonRow("Delete model") { model.delete(); hasModel = false }
        }
        id != null -> {
            Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l, vertical = AppSpace.s), verticalArrangement = Arrangement.Center) {
                Text("Model", style = AppType.body)
                Text(dlText, style = AppType.subhead, color = c.secondaryLabel)
            }
            Hairline(Modifier.padding(start = AppSpace.l))
            ButtonRow("Cancel download") { dm.remove(id); dlId = null }
        }
        else -> {
            ValueRow("Model", "Not downloaded")
            models.forEach { m ->
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Download ${m.label} (${gb(m.bytes)})") { download(m) }
            }
        }
    }
    if (!hasModel) msg?.let { Text(it, style = AppType.footnote, color = c.activity, modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, bottom = AppSpace.s)) }

    if (offer && (hasModel || id != null)) LaunchedEffect(Unit) { onOffered() }
    else if (offer) AlertDialog(
        onDismissRequest = onOffered,
        containerColor = c.card,
        title = { Text("Download the model?") },
        text = { Text("Reports are written by ${models[0].label}, a ${gb(models[0].bytes)} file kept on this phone. It downloads over Wi-Fi only.", style = AppType.body) },
        confirmButton = { TextButton({ download(models[0]); onOffered() }) { Text("Download", color = c.tint) } },
        dismissButton = { TextButton(onOffered) { Text("Not now", color = c.tint) } },
    )
}
