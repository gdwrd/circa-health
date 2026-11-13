package com.nsheremet.circa.ui

import android.content.ClipData
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.nsheremet.circa.ColmiProtocol
import com.nsheremet.circa.FirmwareFile
import com.nsheremet.circa.MOTION_LABELS
import com.nsheremet.circa.motionFile
import com.nsheremet.circa.Prefs.ringAddress
import com.nsheremet.circa.RingInfo
import com.nsheremet.circa.flashFirmware
import com.nsheremet.circa.readRingInfo
import com.nsheremet.circa.recordMotion
import com.nsheremet.circa.ringAfterUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

/** Stock RY02 3.00.33 plus 308 bytes of patches (full-rate stream, motion-only with no gaps, double tap as A1 09), built by emu/patch_fw.py. */
private const val BUNDLED_PATCH = "RY02_3.00.33_fullaccel.bin"
/** Colmi's own RY02 3.00.33 image, unchanged: the way back from any patch. */
private const val BUNDLED_STOCK = "RY02_3.00.33_250117.bin"

/** Settings > Ring lab: record the patched firmware's full-rate motion stream, and install a firmware file on the ring. */
@Composable
internal fun RingLabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = (ctx as ComponentActivity).lifecycleScope
    val c = AppTheme.colors
    val address = ctx.ringAddress
    val haptic = LocalHapticFeedback.current
    var label by remember { mutableStateOf("other") }
    val shareDir = File(ctx.cacheDir, "share")
    // Labelled recordings only: the old circa-motion.csv has no dash after "motion".
    fun recordings() = shareDir.listFiles { f -> f.name.startsWith("circa-motion-") && f.name.endsWith(".csv") }.orEmpty()

    var recording by remember { mutableStateOf<Job?>(null) }
    var samples by remember { mutableIntStateOf(0) }
    var stockPackets by remember { mutableIntStateOf(0) }
    var doubleTaps by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<ColmiProtocol.MotionSample?>(null) }
    var startedAt by remember { mutableStateOf(0L) }
    var motionNote by remember { mutableStateOf("") }
    var hasRecording by remember { mutableStateOf(recordings().isNotEmpty()) }

    var file by remember { mutableStateOf<FirmwareFile?>(null) }
    var ringInfo by remember { mutableStateOf<RingInfo?>(null) }
    var checking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var flashing by remember { mutableStateOf(false) }
    var flashNote by remember { mutableStateOf("") }

    val busy = recording != null || flashing || checking
    BackHandler { if (!flashing) { recording?.cancel(); onBack() } } // leaving mid-update would hide its result
    val view = LocalView.current
    DisposableEffect(busy) { view.keepScreenOn = busy; onDispose { view.keepScreenOn = false } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            file = bytes?.let(::FirmwareFile)
            flashNote = if (bytes == null) "Could not read the file" else ""
        }
    }

    fun toggleRecording() {
        recording?.let { it.cancel(); return }
        samples = 0; stockPackets = 0; doubleTaps = 0; last = null; motionNote = "Connecting…"; startedAt = System.currentTimeMillis()
        // shortcut: recordings live in cacheDir, the OS may clear them; move to filesDir if a week of files is ever lost
        val out = motionFile(shareDir, label, LocalDateTime.now())
        recording = scope.launch {
            var error: String? = null
            try {
                error = withContext(Dispatchers.IO) {
                    recordMotion(ctx, address!!, out, label) { n, s, stock, taps ->
                        samples = n; stockPackets = stock; doubleTaps = taps
                        if (s != null) last = s
                        motionNote = when {
                            n > 0 -> "Recording"
                            stock > 0 -> "Only 1-a-second packets so far. Move the ring; stock firmware never sends more."
                            else -> "Waiting for the ring to move"
                        }
                    }
                }
            } finally { // Stop cancels this coroutine, so nothing after withContext would run
                recording = null
                hasRecording = recordings().isNotEmpty()
                motionNote = error ?: "Stopped: $samples samples saved"
            }
        }
    }

    fun flash(fw: FirmwareFile) {
        flashing = true
        scope.launch {
            val error = withContext(Dispatchers.IO) { flashFirmware(ctx, address!!, fw) { flashNote = it } }
            if (error != null) flashNote = error
            else {
                flashNote = "File sent. Waiting for the ring to restart…"
                val after = withContext(Dispatchers.IO) { ringAfterUpdate(ctx, address!!) }
                ringInfo = after
                flashNote = if (after == null) "File sent, but the ring has not come back yet. Put it on the charger and try Check ring."
                else "Done. The ring reports firmware ${after.firmware ?: "unknown"}."
            }
            flashing = false
        }
    }

    LargeTitleScaffold(
        "Ring lab",
        back = { Text("‹ Settings", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(enabled = !flashing) { recording?.cancel(); onBack() }.padding(start = AppSpace.xs, bottom = AppSpace.xs)) },
    ) {
        item {
            Section(
                "Raw motion",
                "Records every accelerometer sample (25 a second) to a file you can share. Needs the patched firmware; stock firmware sends one sample a second. " +
                    "Firmware before the no-gaps patch only samples while the ring thinks it is moving. Streaming drains the ring's battery fast.",
            ) {
                Row(Modifier.padding(horizontal = AppSpace.l, vertical = AppSpace.m), horizontalArrangement = Arrangement.spacedBy(AppSpace.xs)) {
                    MOTION_LABELS.forEach { k ->
                        val on = k == label
                        Text(
                            k, style = AppType.footnote.copy(fontWeight = FontWeight.Medium), textAlign = TextAlign.Center, maxLines = 1,
                            color = if (on) c.tint else if (recording == null) c.label else c.secondaryLabel,
                            modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background((if (on) c.tint else c.secondaryLabel).copy(alpha = 0.16f))
                                .clickable(enabled = recording == null, role = Role.RadioButton) { haptic.tick(); label = k }.padding(vertical = 9.dp),
                        )
                    }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (recording != null) "Stop recording" else "Start recording", enabled = address != null && !flashing && !checking, onClick = ::toggleRecording)
                Hairline(Modifier.padding(start = AppSpace.l))
                ValueRow("Status", motionNote.ifEmpty { if (address == null) "No ring set up" else "Idle" })
                Hairline(Modifier.padding(start = AppSpace.l))
                val seconds = ((System.currentTimeMillis() - startedAt) / 1000.0).coerceAtLeast(1.0)
                ValueRow("Samples", if (samples == 0) "–" else "$samples" + if (recording != null) "  (${"%.0f".format(samples / seconds)} a second)" else "")
                Hairline(Modifier.padding(start = AppSpace.l))
                ValueRow("Double taps", if (recording == null && doubleTaps == 0) "–" else "$doubleTaps")
                Hairline(Modifier.padding(start = AppSpace.l))
                ValueRow("Latest (mg)", last?.let { "x ${it.x}   y ${it.y}   z ${it.z}" } ?: "–")
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Share recordings", enabled = hasRecording && recording == null) {
                    val uris = recordings().sortedBy { it.name }.map { FileProvider.getUriForFile(ctx, ctx.packageName + ".share", it) }
                    if (uris.isEmpty()) return@ButtonRow
                    ctx.startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/csv").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            // the grant travels with the chooser only through ClipData
                            .apply { clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } } },
                        null,
                    ))
                }
            }
        }
        item {
            val f = file
            Section(
                "Firmware",
                "Installs an update file on the ring over Bluetooth. A file that is wrong for this ring, or modified code that fails to start, " +
                    "can leave the ring unusable with no way to repair it. Keep the ring on its charger and next to the phone until it restarts.",
            ) {
                ButtonRow("Use built-in full-rate motion patch", enabled = !busy) {
                    file = FirmwareFile(ctx.assets.open(BUNDLED_PATCH).use { it.readBytes() })
                    flashNote = ""
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Restore stock firmware", enabled = !busy) {
                    file = FirmwareFile(ctx.assets.open(BUNDLED_STOCK).use { it.readBytes() })
                    flashNote = ""
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Choose update file", enabled = !busy) { pick.launch(arrayOf("*/*")) }
                if (f != null) {
                    Hairline(Modifier.padding(start = AppSpace.l))
                    ValueRow("File", f.problem ?: "${f.version}\nfor ${f.hardware} · ${f.bytes.size / 1024} KB · ${if (f.modified) "modified" else "vendor"}")
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (checking) "Checking…" else "Check ring", enabled = address != null && !busy) {
                    checking = true
                    scope.launch {
                        ringInfo = withContext(Dispatchers.IO) { readRingInfo(ctx, address!!) }
                        if (ringInfo == null) flashNote = "Could not reach the ring"
                        checking = false
                    }
                }
                ringInfo?.let {
                    Hairline(Modifier.padding(start = AppSpace.l))
                    ValueRow("Ring", "${it.firmware ?: "unknown"}\n${it.hardware ?: "unknown"} · battery ${it.battery?.level?.let { b -> "$b%" } ?: "unknown"}")
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (flashing) "Installing…" else "Install on ring", enabled = address != null && f != null && f.problem == null && !busy) { confirm = true }
                if (flashNote.isNotEmpty()) {
                    Hairline(Modifier.padding(start = AppSpace.l))
                    ValueRow("Update", flashNote)
                }
            }
        }
    }

    val f = file
    if (confirm && f != null) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Install ${f.version}?") },
        text = {
            Text(
                (if (f.modified) "This is a modified image: the ring will not check it before installing. " else "") +
                    "If the new firmware does not start, the ring cannot be repaired over Bluetooth. " +
                    "The ring must be ${f.hardware}, on its charger, and stay next to the phone.",
            )
        },
        confirmButton = { TextButton({ confirm = false; flash(f) }) { Text("Install", color = c.activity) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } },
    )
}
