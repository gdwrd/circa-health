package dev.ring.health.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.Icon
import androidx.compose.foundation.Image
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
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
import androidx.compose.ui.res.painterResource
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
import dev.ring.health.R
import dev.ring.health.SampleStore
import dev.ring.health.Prefs.apiToken
import dev.ring.health.Prefs.batteryAlerts
import dev.ring.health.Prefs.lastBatteryPct
import dev.ring.health.Prefs.lastCharging
import dev.ring.health.Prefs.lastSyncMs
import dev.ring.health.Prefs.ringAddress
import dev.ring.health.Prefs.sleepNeedHours
import dev.ring.health.Prefs.stepGoal
import dev.ring.health.Prefs.syncIntervalMin
import dev.ring.health.Prefs.vmUrl
import dev.ring.health.SyncLog
import dev.ring.health.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
fun SettingsScreen(hasPermissions: () -> Boolean, requestPermissions: () -> Unit, onBack: () -> Unit) {
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
    fun notifyOk() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notifyGranted by remember { mutableStateOf(notifyOk()) }
    var pickInterval by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                lastSync = ctx.lastSyncMs
                batteryOk = MainActivity.isIgnoringBatteryOptimizations(ctx)
                notifyGranted = notifyOk()
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs) }
    }

    var scanning by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResult>?>(null) }

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

    // Backend-only settings; null hides the section (no backend or unreachable).
    var med by remember { mutableStateOf<JsonObject?>(null) }
    var pickStart by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickWindow by remember { mutableStateOf(false) }
    var pickLowStep by remember { mutableStateOf(false) }
    var pickWake by remember { mutableStateOf<String?>(null) } // "wake_weekday" | "wake_weekend"
    var birth by remember { mutableStateOf("") }
    LaunchedEffect(url, token) {
        med = Api.settings(ctx)
        birth = (med?.get("birth_year") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }
    fun patchMed(key: String, v: JsonPrimitive) {
        activity.lifecycleScope.launch { Api.patchSettings(ctx, mapOf(key to v))?.let { med = it } }
    }

    // (stored, oldest ts, waiting) of the phone's sample history; re-read on open and after "Send all history".
    var stored by remember { mutableStateOf(Triple(0, 0L, 0)) }
    var storedTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(storedTick) { stored = withContext(Dispatchers.IO) { SampleStore.get(ctx).stats() } }

    if (showLog) return SyncLogScreen { showLog = false }

    LargeTitleScaffold(
        "Settings",
        back = { Text("‹ Today", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs)) },
    ) {
        item {
            Section("Ring", "Each sync reads new data from the ring and pushes it to the server.") {
                ValueRow("Last sync", if (lastSync == 0L) "Never" else SimpleDateFormat("d MMM, ", Locale.getDefault()).format(Date(lastSync)) + clock(lastSync / 1000.0))
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Sync now") {
                    SyncWorker.runNow(ctx)
                    Toast.makeText(ctx, "Sync started", Toast.LENGTH_SHORT).show()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                val battery = ctx.lastBatteryPct
                val stretch = when {
                    interval <= 0 -> ""
                    ctx.lastCharging && interval < 30 -> " · 30 min while charging"
                    battery in 0..20 && interval < 15 -> " · 15 min on low battery"
                    else -> ""
                }
                ChevronRow("Sync every", intervalLabel(interval) + stretch) { pickInterval = true }
                Hairline(Modifier.padding(start = AppSpace.l))
                ChevronRow("Sync log", "") { showLog = true }
            }
        }
        item {
            Section("You", "Your birth year sets your maximum heart rate for cardio zones. Goals shape the Activity and Sleep scores." +
                if (med != null) " Wake times set tonight's bedtime plan; weekends mean the nights before Saturday and Sunday." else "") {
                // Birth year is stored by the backend; without one there are no cardio zones.
                if (med != null) {
                    FieldRow("Birth year", birth, "Not set", KeyboardType.Number) {
                        birth = it.filter(Char::isDigit).take(4)
                        val y = birth.toIntOrNull()
                        if (birth.isEmpty()) patchMed("birth_year", JsonPrimitive(""))
                        else if (y != null && birth.length == 4 && y in 1900..LocalDate.now().year) patchMed("birth_year", JsonPrimitive(birth))
                    }
                    Hairline(Modifier.padding(start = AppSpace.l))
                }
                FieldRow("Step goal", steps, "8000", KeyboardType.Number) {
                    steps = it.filter(Char::isDigit)
                    steps.toIntOrNull()?.let { n -> ctx.stepGoal = n }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Sleep need (h)", sleepNeed, "7.5", KeyboardType.Decimal) {
                    sleepNeed = it
                    it.replace(',', '.').toFloatOrNull()?.let { h -> ctx.sleepNeedHours = h }
                }
                med?.let { s ->
                    listOf("wake_weekday" to "Wake up · weekdays", "wake_weekend" to "Wake up · weekends").forEach { (k, title) ->
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow(title, (s[k] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { null }?.let(::clock) ?: "Off") { pickWake = k }
                    }
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
                        ChevronRow("Usual time", clock((s["medication_time"] as? JsonPrimitive)?.contentOrNull.orEmpty())) { pickTime = true }
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Active window", "${(s["medication_window_h"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: "—"} h") { pickWindow = true }
                    }
                }
            }
            item {
                Section("Workouts", "The ring can't tell cycling from a gym session — both show few steps. Pick what you do most; you can change any session on the Heart screen.") {
                    ChevronRow("Low-step sessions are", lowStepLabel((s["low_step_kind"] as? JsonPrimitive)?.contentOrNull)) { pickLowStep = true }
                }
            }
        }
        item {
            Section(
                "Notifications",
                if (notifyGranted) "Notifications arrive with a sync, so they can run a few minutes late."
                else "Allow notifications for Circa in Android settings to receive these.",
            ) {
                med?.let { s ->
                    val muted = (s["muted"] as? JsonPrimitive)?.contentOrNull.orEmpty().split(',').filter(String::isNotEmpty)
                    NOTIFY_KINDS.forEach { (kind, title) ->
                        SwitchRow(title, kind !in muted, enabled = notifyGranted) { on ->
                            patchMed("muted", JsonPrimitive((if (on) muted - kind else muted + kind).joinToString(",")))
                        }
                        Hairline(Modifier.padding(start = AppSpace.l))
                    }
                }
                SwitchRow("Ring battery", ctx.batteryAlerts, enabled = notifyGranted) { ctx.batteryAlerts = it }
            }
        }
        item {
            Section(
                "Setup",
                "Point the server at the backend (:8429). " +
                    "Android delays background sync while battery optimisation is on.\n\n" +
                    if (stored.first == 0) "Stored on this phone: no samples yet"
                    else "Stored on this phone: ${"%,d".format(stored.first)} samples since " +
                        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(stored.second)) + " · ${"%,d".format(stored.third)} waiting to send",
            ) {
                FieldRow("Address", ring, "Not configured") { ring = it.trim(); ctx.ringAddress = ring.takeIf { a -> a.isNotEmpty() } }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (scanning) "Scanning…" else "Scan for ring", enabled = !scanning, onClick = ::startScan)
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("URL", url, "http://host:8429", KeyboardType.Uri) { url = it.trim(); ctx.vmUrl = url }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Token", token, "Optional", KeyboardType.Password, password = true) { token = it.trim(); ctx.apiToken = token }
                Hairline(Modifier.padding(start = AppSpace.l))
                ChevronRow("Android battery", if (batteryOk) "Unrestricted" else "Optimised") {
                    if (!batteryOk) MainActivity.requestBatteryExemption(ctx)
                    else Toast.makeText(ctx, "Already unrestricted", Toast.LENGTH_SHORT).show()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Send all history") {
                    activity.lifecycleScope.launch {
                        withContext(Dispatchers.IO) { SampleStore.get(ctx).markAllUnsent() }
                        storedTick++
                        SyncWorker.runNow(ctx)
                        Toast.makeText(ctx, "Sync started", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(vertical = AppSpace.xl), horizontalAlignment = Alignment.CenterHorizontally) {
                // The mark on its launcher tile reads as the app icon; alone, an open ring reads as a spinner.
                // 66 dp = the 108 dp adaptive canvas cropped to its 72 dp visible area in a 44 dp tile, like the launcher.
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(c.brandTile), contentAlignment = Alignment.Center) {
                    Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.requiredSize(66.dp))
                }
                Text(
                    "Circa " + ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName,
                    style = AppType.footnote, color = c.secondaryLabel, modifier = Modifier.padding(top = AppSpace.xs),
                )
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

    if (pickLowStep) {
        val cur = (med?.get("low_step_kind") as? JsonPrimitive)?.contentOrNull
        AlertDialog(
            onDismissRequest = { pickLowStep = false },
            containerColor = c.card,
            title = { Text("Low-step sessions are") },
            text = {
                Column {
                    listOf("ride", "workout").forEach { k ->
                        Text(
                            lowStepLabel(k) + if (k == cur) "  ✓" else "",
                            style = AppType.body, color = if (k == cur) c.tint else c.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                patchMed("low_step_kind", JsonPrimitive(k))
                                pickLowStep = false
                            }.padding(vertical = AppSpace.m),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickLowStep = false }) { Text("Cancel") } },
        )
    }

    if (pickTime) {
        TimePickDialog((med?.get("medication_time") as? JsonPrimitive)?.contentOrNull ?: "08:00", { pickTime = false }) {
            pickTime = false
            patchMed("medication_time", JsonPrimitive(it))
        }
    }

    pickWake?.let { k ->
        val cur = (med?.get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
        TimePickDialog(cur.ifEmpty { if (k == "wake_weekday") "07:00" else "08:00" }, { pickWake = null }, onTurnOff = { pickWake = null; patchMed(k, JsonPrimitive("")) }) {
            pickWake = null
            patchMed(k, JsonPrimitive(it))
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

private fun lowStepLabel(k: String?) = if (k == "ride") "Rides" else "Workouts"

/** Backend notification kinds (`/settings.muted`) in Settings order. */
private val NOTIFY_KINDS = listOf(
    "morning" to "Morning sleep", "readiness" to "Readiness", "bedtime" to "Wind down", "inactivity" to "Time to move",
    "strain" to "Body signals", "checkin" to "Journal check-in", "weekly" to "Your week", "monthly" to "Your month",
)

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
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    var on by remember(checked) { mutableStateOf(checked) }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = AppType.body, color = if (enabled) AppTheme.colors.label else AppTheme.colors.secondaryLabel, modifier = Modifier.weight(1f))
        Switch(on, { on = it; onChange(it) }, enabled = enabled, colors = iosSwitchColors())
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

/** Streams live HR for up to [durationMs]; [stopWhenStable] ends early once readings settle and pushes the last bpm. */
@SuppressLint("MissingPermission")
internal suspend fun runLiveHr(
    ctx: Context, address: String, onBpm: (Int) -> Unit = {}, durationMs: Long = 30_000, stopWhenStable: Boolean = true,
    onProgress: (String) -> Unit,
): String {
    val conn = ColmiConnection(ctx, address)
    var measurementStarted = false
    var packets = 0; var valid = 0; var restarts = 0
    SyncWorker.liveHrActive = true // a background sync would take the ring mid-stream
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
        val deadline = System.currentTimeMillis() + durationMs
        var lastLock = System.currentTimeMillis() // last start request or valid reading
        loop@ while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            // Long mode: the ring may stop streaming on its own. It needs ~25 s to lock on after a start
            // (seen on the real ring), so ask again only after 30 s without a valid reading.
            if (!stopWhenStable && System.currentTimeMillis() - lastLock >= 30_000) {
                conn.writePacket(ColmiProtocol.liveHrStartRequest())
                lastLock = System.currentTimeMillis(); restarts++
            }
            val left = deadline - System.currentTimeMillis()
            val pkt = withTimeoutOrNull(if (stopWhenStable) left else minOf(left, 5_000)) {
                conn.notifyV1.receive()
            }
            if (pkt == null) {
                if (stopWhenStable || System.currentTimeMillis() >= deadline) break
                continue
            }
            packets++
            val parsed = ColmiProtocol.parseLiveHrPacket(pkt) ?: continue
            when (parsed.error) {
                1 -> { notWorn = true; break@loop }
                0 -> if (parsed.bpm > 0) {
                    valid++; lastLock = System.currentTimeMillis()
                    lastBpm = parsed.bpm
                    onBpm(parsed.bpm)
                    onProgress("♥ ${parsed.bpm} bpm  (measuring…)")
                    // Stable enough after ~6 valid readings (≈12s of streaming)
                    if (stopWhenStable && System.currentTimeMillis() > deadline - 18_000) break@loop
                }
            }
        }

        when {
            notWorn      -> "Ring not worn correctly — adjust and retry"
            lastBpm == 0 -> "No reading — ensure ring is on a finger and try again"
            else         -> { if (stopWhenStable) pushLiveHr(ctx, lastBpm); "♥ $lastBpm bpm" }
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
                // NonCancellable: Breathe ends the stream by cancelling, and a cancelled coroutine cannot suspend.
                withContext(NonCancellable) { withTimeoutOrNull(2_000) { conn.writePacket(ColmiProtocol.liveHrStopRequest()) } }
            }.onFailure { Log.w("Settings", "Live HR stop failed", it) }
        }
        conn.disconnect()
        SyncWorker.liveHrActive = false
        if (!stopWhenStable) SyncLog.add(ctx, "Breathe live HR: $packets packets, $valid valid, $restarts restarts")
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
