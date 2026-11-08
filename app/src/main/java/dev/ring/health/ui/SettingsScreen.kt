package dev.ring.health.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import dev.ring.health.ColmiConnection
import dev.ring.health.ColmiProtocol
import dev.ring.health.Metric
import dev.ring.health.MetricsPusher
import dev.ring.health.MainActivity
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.lastSyncMs
import dev.ring.health.Prefs.ringAddress
import dev.ring.health.Prefs.sleepNeedHours
import dev.ring.health.Prefs.stepGoal
import dev.ring.health.Prefs.syncIntervalMin
import dev.ring.health.Prefs.vmUrl
import dev.ring.health.SyncLog
import dev.ring.health.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(hasPermissions: () -> Boolean, requestPermissions: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val c = AppTheme.colors

    var ring by remember { mutableStateOf(ctx.ringAddress ?: "") }
    var url by remember { mutableStateOf(ctx.vmUrl) }
    var token by remember { mutableStateOf(ctx.apiToken) }
    var steps by remember { mutableStateOf(ctx.stepGoal.toString()) }
    var sleepNeed by remember { mutableStateOf(ctx.sleepNeedHours.toString().removeSuffix(".0")) }

    var lastSync by remember { mutableStateOf(ctx.lastSyncMs) }
    var interval by remember { mutableIntStateOf(ctx.syncIntervalMin) }
    var batteryOk by remember { mutableStateOf(MainActivity.isIgnoringBatteryOptimizations(ctx)) }
    var pickInterval by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                lastSync = ctx.lastSyncMs
                batteryOk = MainActivity.isIgnoringBatteryOptimizations(ctx)
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResult>?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var liveText by remember { mutableStateOf("") }
    var liveJob by remember { mutableStateOf<Job?>(null) }

    fun startScan() {
        if (!hasPermissions()) { requestPermissions(); return }
        val scanner = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
            .adapter?.bluetoothLeScanner
        if (scanner == null) {
            Toast.makeText(ctx, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }
        val found = mutableListOf<ScanResult>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (found.none { it.device.address == result.device.address }) found += result
            }
        }
        scanning = true
        scanner.startScan(callback)
        activity.lifecycleScope.launch {
            delay(5_000)
            scanner.stopScan(callback)
            scanning = false
            if (found.isEmpty()) {
                Toast.makeText(ctx, "No BLE devices found", Toast.LENGTH_SHORT).show()
            } else {
                scanResults = found.toList()
            }
        }
    }

    fun measureHeartRate() {
        if (liveJob?.isActive == true) return
        if (!hasPermissions()) { requestPermissions(); return }
        val address = ctx.ringAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(ctx, "Set ring address first", Toast.LENGTH_SHORT).show()
            return
        }
        measuring = true
        liveText = "Connecting to ring…"
        liveJob = activity.lifecycleScope.launch {
            val result = runLiveHr(ctx.applicationContext, address) { liveText = it }
            measuring = false
            liveText = result
        }
    }

    // Backend-only settings; null hides the section (no backend or unreachable).
    var med by remember { mutableStateOf<JsonObject?>(null) }
    var pickStart by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickWindow by remember { mutableStateOf(false) }
    LaunchedEffect(url, token) { med = Api.settings(ctx) }
    fun patchMed(key: String, v: JsonPrimitive) {
        activity.lifecycleScope.launch { Api.patchSettings(ctx, mapOf(key to v))?.let { med = it } }
    }

    if (showLog) return SyncLogScreen { showLog = false }

    LargeTitleScaffold("Settings") {
        item {
            Section("Ring") {
                FieldRow("Address", ring, "Not configured") { ring = it.trim(); ctx.ringAddress = ring.takeIf { a -> a.isNotEmpty() } }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (scanning) "Scanning…" else "Scan for ring", enabled = !scanning, onClick = ::startScan)
                Hairline(Modifier.padding(start = AppSpace.l))
                ValueRow("Last sync", if (lastSync == 0L) "Never" else SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(lastSync)))
            }
        }
        item {
            Section("Server", "Point at the backend (:8429) or straight at VictoriaMetrics (:8428).") {
                FieldRow("URL", url, "http://host:8429", KeyboardType.Uri) { url = it.trim(); ctx.vmUrl = url }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Token", token, "Optional", KeyboardType.Password, password = true) { token = it.trim(); ctx.apiToken = token }
            }
        }
        item {
            Section("Goals") {
                FieldRow("Step goal", steps, "8000", KeyboardType.Number) {
                    steps = it.filter(Char::isDigit)
                    steps.toIntOrNull()?.let { n -> ctx.stepGoal = n }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Sleep need (h)", sleepNeed, "7.5", KeyboardType.Decimal) {
                    sleepNeed = it
                    it.replace(',', '.').toFloatOrNull()?.let { h -> ctx.sleepNeedHours = h }
                }
            }
        }
        med?.let { s ->
            val started = (s["medication_started"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            item {
                Section("Medication", "Used to mark doses on the Heart screen and to compare your heart rate with the days before you started.") {
                    SwitchRow("I take medication", started.isNotEmpty()) { on ->
                        patchMed("medication_started", JsonPrimitive(if (on) LocalDate.now().toString() else ""))
                    }
                    if (started.isNotEmpty()) {
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Started on", runCatching { LocalDate.parse(started).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrDefault(started)) { pickStart = true }
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Usual time", (s["medication_time"] as? JsonPrimitive)?.contentOrNull.orEmpty()) { pickTime = true }
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Active window", "${(s["medication_window_h"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: "—"} h") { pickWindow = true }
                    }
                }
            }
        }
        item {
            Section(
                "Background sync",
                "Each sync reads new data from the ring and pushes it to the server. " +
                    "Android delays background work while battery optimisation is on.",
            ) {
                ChevronRow("Every", intervalLabel(interval)) { pickInterval = true }
                Hairline(Modifier.padding(start = AppSpace.l))
                ChevronRow("Battery", if (batteryOk) "Unrestricted" else "Optimised") {
                    if (!batteryOk) MainActivity.requestBatteryExemption(ctx)
                    else Toast.makeText(ctx, "Already unrestricted", Toast.LENGTH_SHORT).show()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ChevronRow("Sync log", "") { showLog = true }
            }
        }
        item {
            Section("Actions", liveText.ifEmpty { null }) {
                ButtonRow("Sync now") {
                    SyncWorker.runNow(ctx)
                    Toast.makeText(ctx, "Sync started", Toast.LENGTH_SHORT).show()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (measuring) "Measuring…" else "Measure heart rate now", enabled = !measuring, onClick = ::measureHeartRate)
            }
        }
    }

    if (pickInterval) {
        AlertDialog(
            onDismissRequest = { pickInterval = false },
            title = { Text("Sync every") },
            text = {
                Column {
                    INTERVALS.forEach { m ->
                        Text(
                            intervalLabel(m) + if (m == interval) "  ✓" else "",
                            style = AppType.body, color = if (m == interval) c.tint else c.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                interval = m
                                ctx.syncIntervalMin = m
                                SyncWorker.reschedule(ctx)
                                pickInterval = false
                            }.padding(vertical = AppSpace.m),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickInterval = false }) { Text("Cancel") } },
        )
    }

    if (pickWindow) {
        val cur = (med?.get("medication_window_h") as? JsonPrimitive)?.doubleOrNull?.toInt()
        AlertDialog(
            onDismissRequest = { pickWindow = false },
            containerColor = c.card,
            title = { Text("Active window") },
            text = {
                Column {
                    listOf(4, 6, 8, 12).forEach { h ->
                        Text(
                            "$h hours" + if (h == cur) "  ✓" else "",
                            style = AppType.body, color = if (h == cur) c.tint else c.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                patchMed("medication_window_h", JsonPrimitive(h))
                                pickWindow = false
                            }.padding(vertical = AppSpace.m),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickWindow = false }) { Text("Cancel") } },
        )
    }

    if (pickTime) {
        TimePickDialog((med?.get("medication_time") as? JsonPrimitive)?.contentOrNull ?: "08:00", { pickTime = false }) {
            pickTime = false
            patchMed("medication_time", JsonPrimitive(it))
        }
    }

    if (pickStart) StartDateDialog((med?.get("medication_started") as? JsonPrimitive)?.contentOrNull.orEmpty(), { pickStart = false }) {
        pickStart = false
        patchMed("medication_started", JsonPrimitive(it))
    }

    scanResults?.let { results ->
        AlertDialog(
            onDismissRequest = { scanResults = null },
            title = { Text("Select your ring") },
            text = {
                Column {
                    results.forEach { r ->
                        val name = r.device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
                        Text(
                            "$name — ${r.device.address}",
                            Modifier.fillMaxWidth().clickable {
                                ring = r.device.address
                                ctx.ringAddress = ring
                                scanResults = null
                                Toast.makeText(ctx, "Ring address saved", Toast.LENGTH_SHORT).show()
                            }.padding(vertical = AppSpace.m),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ scanResults = null }) { Text("Cancel") } },
        )
    }
}

private val INTERVALS = listOf(5, 15, 30, 60, 0)

private fun intervalLabel(m: Int) = if (m <= 0) "Off" else "$m min"

/** Temporary debug view of [SyncLog]: ring reads and server pushes, newest first. */
@Composable
private fun SyncLogScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val c = AppTheme.colors
    var lines by remember { mutableStateOf(SyncLog.read(ctx)) }
    LargeTitleScaffold(
        "Sync log",
        back = { Text("‹ Settings", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs)) },
    ) {
        item {
            InsetCard {
                ButtonRow("Refresh") { lines = SyncLog.read(ctx) }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Sync now") {
                    SyncWorker.runNow(ctx)
                    Toast.makeText(ctx, "Sync started — tap Refresh in a minute", Toast.LENGTH_SHORT).show()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Clear log") { SyncLog.clear(ctx); lines = emptyList() }
            }
        }
        item {
            InsetCard {
                if (lines.isEmpty()) {
                    Text("No sync activity yet.", style = AppType.subhead, color = c.secondaryLabel, modifier = Modifier.padding(AppSpace.l))
                }
                lines.forEachIndexed { i, line ->
                    if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                    val failed = "FAILED" in line || "error" in line || "could not" in line
                    Text(
                        line, style = AppType.footnote.copy(fontFamily = FontFamily.Monospace),
                        color = if (failed) c.activity else c.label,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpace.l, vertical = AppSpace.s),
                    )
                }
            }
        }
    }
}

// ── iOS grouped-list pieces ────────────────────────────────────────────────

@Composable
private fun Section(header: String, footer: String? = null, rows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        Text(
            header, style = AppType.title,
            modifier = Modifier.padding(start = AppSpace.xs, bottom = AppSpace.s),
        )
        InsetCard(content = rows)
        if (footer != null) {
            Text(
                footer, style = AppType.footnote, color = AppTheme.colors.secondaryLabel,
                modifier = Modifier.padding(start = AppSpace.l, end = AppSpace.l, top = AppSpace.s - AppSpace.xs),
            )
        }
    }
}

@Composable
private fun ValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.body, modifier = Modifier.width(120.dp))
        Text(value, style = AppType.body, color = AppTheme.colors.secondaryLabel, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FieldRow(
    title: String,
    value: String,
    placeholder: String,
    keyboard: KeyboardType = KeyboardType.Text,
    password: Boolean = false,
    onChange: (String) -> Unit,
) {
    val c = AppTheme.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.body, modifier = Modifier.width(120.dp))
        BasicTextField(
            value, onChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = AppType.body.copy(color = c.label, textAlign = TextAlign.End),
            cursorBrush = SolidColor(c.tint),
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = AppType.body, color = c.secondaryLabel)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun ChevronRow(title: String, value: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = AppSpace.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AppType.body, modifier = Modifier.weight(1f))
        Text(value, style = AppType.body, color = c.secondaryLabel)
        Text("  ›", style = AppType.body, color = c.secondaryLabel)
    }
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    var on by remember(checked) { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.body, modifier = Modifier.weight(1f))
        Switch(on, { on = it; onChange(it) }, colors = iosSwitchColors())
    }
}

/** Date picker limited to today and earlier; [onPick] gets an ISO date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateDialog(initial: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = AppTheme.colors
    val day = 86_400_000L
    val init = runCatching { LocalDate.parse(initial).toEpochDay() * day }.getOrNull()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = init,
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / day <= LocalDate.now().toEpochDay()
        },
    )
    val knob = if (c.background.luminance() < 0.5f) c.label else c.card
    val colors = DatePickerDefaults.colors(
        containerColor = c.card, titleContentColor = c.secondaryLabel, headlineContentColor = c.label,
        weekdayContentColor = c.secondaryLabel, subheadContentColor = c.secondaryLabel, navigationContentColor = c.label,
        yearContentColor = c.label, currentYearContentColor = c.tint, selectedYearContentColor = knob, selectedYearContainerColor = c.tint,
        dayContentColor = c.label, selectedDayContentColor = knob, selectedDayContainerColor = c.tint,
        todayContentColor = c.tint, todayDateBorderColor = c.tint, dividerColor = c.separator,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss, colors = colors,
        confirmButton = {
            TextButton({ state.selectedDateMillis?.let { onPick(LocalDate.ofEpochDay(it / day).toString()) } ?: onDismiss() }) { Text("Done", color = c.tint) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel", color = c.tint) } },
    ) { DatePicker(state, colors = colors, showModeToggle = false) }
}

@Composable
private fun ButtonRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = AppSpace.l), contentAlignment = Alignment.CenterStart) {
        Text(title, style = AppType.body, color = if (enabled) c.tint else c.secondaryLabel)
    }
}

// ── Live heart rate (moved from MainActivity; behaviour unchanged) ──────────

@SuppressLint("MissingPermission")
internal suspend fun runLiveHr(ctx: Context, address: String, onBpm: (Int) -> Unit = {}, onProgress: (String) -> Unit): String {
    val conn = ColmiConnection(ctx, address)
    var measurementStarted = false
    return try {
        val connected = conn.connect()
        if (!connected) return "Could not connect (ring may be busy syncing — try again in a few seconds)"

        onProgress("Measuring (keep ring still)…")
        conn.writePacket(ColmiProtocol.liveHrStartRequest())
        measurementStarted = true

        // Read for ~30s, accepting whatever bpm > 0 we see; the ring usually
        // streams a few invalid (bpm=0) packets first while it acquires.
        var lastBpm = 0
        var notWorn = false
        val deadline = System.currentTimeMillis() + 30_000
        loop@ while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            val pkt = withTimeoutOrNull(deadline - System.currentTimeMillis()) {
                conn.notifyV1.receive()
            } ?: break
            val parsed = ColmiProtocol.parseLiveHrPacket(pkt) ?: continue
            when (parsed.error) {
                1 -> { notWorn = true; break@loop }
                0 -> if (parsed.bpm > 0) {
                    lastBpm = parsed.bpm
                    onBpm(parsed.bpm)
                    onProgress("♥ ${parsed.bpm} bpm  (measuring…)")
                    // Stable enough after ~6 valid readings (≈12s of streaming)
                    if (System.currentTimeMillis() > deadline - 18_000) break@loop
                }
            }
        }

        when {
            notWorn      -> "Ring not worn correctly — adjust and retry"
            lastBpm == 0 -> "No reading — ensure ring is on a finger and try again"
            else         -> { pushLiveHr(ctx, lastBpm); "♥ $lastBpm bpm" }
        }
    } catch (t: Throwable) {
        Log.w("Settings", "Live HR failed", t)
        "Error: ${t.message ?: t::class.simpleName}"
    } finally {
        // ALWAYS tell the ring to stop measuring before we disconnect, even
        // on early returns / exceptions. Otherwise the ring keeps its HR
        // sensor running on its own internal timeout (~minutes).
        if (measurementStarted) {
            runCatching {
                withTimeoutOrNull(2_000) { conn.writePacket(ColmiProtocol.liveHrStopRequest()) }
            }.onFailure { Log.w("Settings", "Live HR stop failed", it) }
        }
        conn.disconnect()
    }
}

private suspend fun pushLiveHr(ctx: Context, bpm: Int) {
    val url = ctx.vmUrl
    if (url.isBlank()) return
    withContext(Dispatchers.IO) {
        runCatching {
            val pusher = MetricsPusher(url, ctx.apiToken)
            val ok = pusher.push(
                listOf(
                    Metric(
                        name = "biometric_hr_bpm",
                        labels = mapOf("device" to "colmi_r02", "source" to "live"),
                        value = bpm.toDouble(),
                        timestampMs = System.currentTimeMillis(),
                    )
                )
            )
            SyncLog.add(ctx, "Server: live HR $bpm bpm ${if (ok) "pushed" else "push FAILED"} (${pusher.lastResult})")
        }.onFailure { Log.w("Settings", "Live HR push failed", it) }
    }
}
