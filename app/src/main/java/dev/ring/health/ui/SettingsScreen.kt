package dev.ring.health.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import java.io.File
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
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
import dev.ring.health.Prefs.carAddress
import dev.ring.health.Prefs.carName
import dev.ring.health.driveFacts
import dev.ring.health.driveWarning
import dev.ring.health.Prefs.goalsPushed
import dev.ring.health.Prefs.hasStoredGoals
import dev.ring.health.Prefs.lastBatteryPct
import dev.ring.health.Prefs.lastCharging
import dev.ring.health.Prefs.lastSyncMs
import dev.ring.health.Prefs.markNames
import dev.ring.health.widget.MarkWidget
import dev.ring.health.Prefs.ringAddress
import dev.ring.health.Prefs.sleepNeedHours
import dev.ring.health.Prefs.usageAccess
import dev.ring.health.Prefs.holdPlace
import dev.ring.health.Prefs.notificationAccess
import dev.ring.health.Prefs.phoneSensor
import dev.ring.health.Prefs.lastScreenMs
import dev.ring.health.Prefs.stepGoal
import dev.ring.health.Prefs.syncIntervalMin
import dev.ring.health.Prefs.vmUrl
import dev.ring.health.SyncLog
import dev.ring.health.SyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
@Composable
fun SettingsScreen(hasPermissions: () -> Boolean, requestPermissions: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as ComponentActivity
    val c = AppTheme.colors
    val haptic = LocalHapticFeedback.current

    var ring by remember { mutableStateOf(ctx.ringAddress ?: "") }
    var url by remember { mutableStateOf(ctx.vmUrl) }
    var token by remember { mutableStateOf(ctx.apiToken) }
    var steps by remember { mutableStateOf(ctx.stepGoal.toString()) }
    var sleepNeed by remember { mutableStateOf(ctx.sleepNeedHours.toString().removeSuffix(".0")) }

    var lastSync by remember { mutableStateOf(ctx.lastSyncMs) }
    var interval by remember { mutableIntStateOf(ctx.syncIntervalMin) }
    var batteryOk by remember { mutableStateOf(MainActivity.isIgnoringBatteryOptimizations(ctx)) }
    fun notifyOk() = ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var usageOk by remember { mutableStateOf(ctx.usageAccess) }
    var phoneOn by remember { mutableStateOf(ctx.phoneSensor) }
    var listenOk by remember { mutableStateOf(ctx.notificationAccess) }
    var holdOn by remember { mutableStateOf(ctx.holdPlace) }
    var notifyGranted by remember { mutableStateOf(notifyOk()) }
    var car by remember { mutableStateOf(ctx.carName) }
    var pickCar by remember { mutableStateOf<List<BluetoothDevice>?>(null) } // the bonded devices while the Car dialog is open
    var driveNow by remember { mutableStateOf(System.currentTimeMillis()) }
    var pickInterval by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    val stopScan = remember { arrayOfNulls<() -> Unit>(1) } // set while a scan runs
    DisposableEffect(activity) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                lastSync = ctx.lastSyncMs
                batteryOk = MainActivity.isIgnoringBatteryOptimizations(ctx)
                notifyGranted = notifyOk()
                usageOk = ctx.usageAccess
                listenOk = ctx.notificationAccess
                driveNow = System.currentTimeMillis()
            }
        }
        activity.lifecycle.addObserver(obs)
        onDispose { activity.lifecycle.removeObserver(obs); stopScan[0]?.invoke() }
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
        val job = activity.lifecycleScope.launch {
            delay(5_000)
            scanner.stopScan(callback)
            stopScan[0] = null
            scanning = false
            if (found.isEmpty()) {
                Toast.makeText(ctx, "No BLE devices found", Toast.LENGTH_SHORT).show()
            } else {
                scanResults = found.toList()
            }
        }
        stopScan[0] = { job.cancel(); scanner.stopScan(callback) }
    }

    // Backend-only settings; null hides the section (no backend or unreachable).
    var med by remember { mutableStateOf<JsonObject?>(null) }
    var pickStart by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var pickWindow by remember { mutableStateOf(false) }
    var pickLowStep by remember { mutableStateOf(false) }
    var pickWake by remember { mutableStateOf<String?>(null) } // "wake_weekday" | "wake_weekend"
    var birth by remember { mutableStateOf("") }
    var things by remember { mutableStateOf(List(3) { "" }) } // Things to mark: slots 1..3
    LaunchedEffect(url, token) {
        // Goals typed on this phone and not yet on the server go up first; until that works the server's are not copied down.
        if (!ctx.goalsPushed && ctx.hasStoredGoals) ctx.goalsPushed = Api.patchSettings(ctx, mapOf(
            "step_goal" to JsonPrimitive(ctx.stepGoal.coerceIn(1000, 100000)),
            "sleep_need_min" to JsonPrimitive((ctx.sleepNeedHours * 60).roundToInt().coerceIn(240, 720)),
        )) != null
        med = Api.settings(ctx)
        if (med != null && !ctx.hasStoredGoals) ctx.goalsPushed = true // fresh install: nothing to push, the server's goals win
        birth = (med?.get("birth_year") as? JsonPrimitive)?.contentOrNull.orEmpty()
        things = (1..3).map { (med?.get("mark_$it") as? JsonPrimitive)?.contentOrNull.orEmpty() }
        if (ctx.goalsPushed) med?.let { s ->
            (s["step_goal"] as? JsonPrimitive)?.doubleOrNull?.let { ctx.stepGoal = it.roundToInt(); steps = ctx.stepGoal.toString() }
            (s["sleep_need_min"] as? JsonPrimitive)?.doubleOrNull?.let { ctx.sleepNeedHours = it.toFloat() / 60; sleepNeed = ctx.sleepNeedHours.toString().removeSuffix(".0") }
        }
    }
    var patchFailed by remember { mutableIntStateOf(0) } // key of the backend switches: a failed PATCH resets them to the stored value
    fun patchMed(key: String, v: JsonPrimitive) {
        activity.lifecycleScope.launch { Api.patchSettings(ctx, mapOf(key to v))?.let { med = it } ?: patchFailed++ }
    }
    // The goal fields change per keystroke: a value is stored and sent only after 800 ms without typing, so "1200" on the
    // way from "12000" to "50" is never saved. null (out of range) drops a pending save and keeps the old value.
    val goalJobs = remember { mutableMapOf<String, Job>() }
    fun saveGoal(key: String, v: Int?) {
        goalJobs[key]?.cancel()
        if (v != null) goalJobs[key] = activity.lifecycleScope.launch {
            delay(800)
            if (key == "step_goal") ctx.stepGoal = v else ctx.sleepNeedHours = v / 60f
            Api.patchSettings(ctx, mapOf(key to JsonPrimitive(v)))?.let { med = it } ?: run { patchFailed++; ctx.goalsPushed = false } // not sent: the next load pushes it
        }
    }
    // A thing's name is sent 800 ms after the last keystroke, as the goals are; the server takes no outer spaces.
    fun saveMark(slot: Int, name: String) {
        goalJobs["mark_$slot"]?.cancel()
        goalJobs["mark_$slot"] = activity.lifecycleScope.launch { delay(800); patchMed("mark_$slot", JsonPrimitive(name.trim())) }
    }
    // The names as the server holds them, kept for the mark widget.
    LaunchedEffect(med) {
        med?.let { s ->
            val names = (1..3).map { (s["mark_$it"] as? JsonPrimitive)?.contentOrNull.orEmpty() }
            if (names != ctx.markNames) { ctx.markNames = names; MarkWidget().updateAll(ctx) }
        }
    }

    // (stored, oldest ts, waiting) of the phone's sample history; re-read on open and after "Send all history".
    var stored by remember { mutableStateOf(Triple(0, 0L, 0)) }
    var storedTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(storedTick) { stored = withContext(Dispatchers.IO) { SampleStore.get(ctx).stats() } }

    // "Share report" / "Export CSV": the last 90 days into cacheDir/share/, then the system share sheet.
    var sharing by remember { mutableStateOf(false) }
    fun share(pdf: Boolean) {
        haptic.tick()
        sharing = true
        activity.lifecycleScope.launch {
            val uri = Api.exportCsv(ctx)?.let { csv ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(File(ctx.cacheDir, "share").apply { mkdirs() }, if (pdf) "circa-report.pdf" else "circa-export.csv")
                        if (pdf) writeReport(ctx, csv, file) else file.writeText(csv)
                        FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
                    }.onFailure { Log.w("Settings", "Share failed", it) }.getOrNull()
                }
            }
            sharing = false
            if (uri == null) Toast.makeText(ctx, "Could not load your data", Toast.LENGTH_SHORT).show()
            else ctx.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(if (pdf) "application/pdf" else "text/csv").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri(null, uri) }, // the grant travels with the chooser only through ClipData
                null,
            ))
        }
    }

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
            Section("Phone", "Screen on/off times fix nights where you scrolled in bed. Only the times are sent." +
                (if (phoneOn && !usageOk) " Allow Usage access for Circa to start. Greyed out? In App info, tap ⋮ › Allow restricted settings first." else "") +
                "\n\nHold my place shows where in a podcast or audiobook you fell asleep. Android calls the permission notification access; " +
                "Circa only uses it to see what is playing and never reads your notifications." +
                if (holdOn && !listenOk) " Allow Notification access for Circa to start. Greyed out? In App info, tap ⋮ › Allow restricted settings first." else "") {
                SwitchRow("Phone as sleep sensor", phoneOn) { on ->
                    if (on && ctx.lastScreenMs != 0L) ctx.lastScreenMs = System.currentTimeMillis()  // never send the time it was off
                    ctx.phoneSensor = on; phoneOn = on
                    if (on && !ctx.usageAccess) ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                if (phoneOn && !usageOk) {
                    Hairline(Modifier.padding(start = AppSpace.l))
                    ChevronRow("Usage access", "Off") { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                SwitchRow("Hold my place", holdOn) { on ->
                    haptic.tick()
                    ctx.holdPlace = on; holdOn = on
                    if (!on) File(ctx.filesDir, "playback.log").delete()
                    if (on && !ctx.notificationAccess) ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                if (holdOn && !listenOk) {
                    Hairline(Modifier.padding(start = AppSpace.l))
                    ChevronRow("Notification access", "Off") { haptic.tick(); ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                }
            }
        }
        item {
            Section("Driving", "When your phone connects to this Bluetooth device, Circa checks last night's sleep and how long you have been awake, " +
                "and warns once if you are short. It never says you are fine to drive. It needs the ring on since you woke up.") {
                ChevronRow("Car", car.ifEmpty { "None" }) {
                    haptic.tick()
                    if (!hasPermissions()) requestPermissions()
                    else pickCar = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bondedDevices.orEmpty().toList()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                val facts = remember(driveNow) { driveFacts(ctx, driveNow) }
                Column(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = AppSpace.l, vertical = AppSpace.s), verticalArrangement = Arrangement.Center) {
                    Text("Right now", style = AppType.body)
                    Text(facts?.let { "Last 24 h: ${it.asleep} asleep · awake ${it.awake}" } ?: "No fresh ring data", style = AppType.subhead, color = c.secondaryLabel)
                    facts?.let(::driveWarning)?.let { Text(it.first, style = AppType.subhead, color = c.signalHigh) }
                }
            }
        }
        item {
            Section("You", "Your birth year sets your maximum heart rate for cardio zones. Goals shape the Activity and Sleep scores." +
                if (med != null) " Wake times set tonight's bedtime plan; weekends mean the nights before Saturday and Sunday." +
                    " Calm mode: words instead of numbers. Numbers stay one tap away." +
                    " Guess first: say how recovered you feel before the scores show." else "") {
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
                // Out of range (NaN and Infinity fail both comparisons): nothing is stored or sent, the row shows the range.
                fun goal(s: String) = s.toIntOrNull()?.takeIf { it in 1000..100000 }
                fun need(s: String) = s.replace(',', '.').toFloatOrNull()?.takeIf { it > 4f && it <= 12f }
                FieldRow("Step goal", steps, "8000", KeyboardType.Number, supporting = if (goal(steps) == null) "1000 to 100000" else null) {
                    steps = it.filter(Char::isDigit)
                    saveGoal("step_goal", goal(steps))
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Sleep need (h)", sleepNeed, "7.5", KeyboardType.Decimal, supporting = if (need(sleepNeed) == null) "Above 4, up to 12" else null) {
                    sleepNeed = it
                    saveGoal("sleep_need_min", need(it)?.let { h -> (h * 60).roundToInt() })
                }
                med?.let { s ->
                    listOf("wake_weekday" to "Wake up · weekdays", "wake_weekend" to "Wake up · weekends").forEach { (k, title) ->
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow(title, (s[k] as? JsonPrimitive)?.contentOrNull.orEmpty().ifEmpty { null }?.let(::clock) ?: "Off") { pickWake = k }
                    }
                    Hairline(Modifier.padding(start = AppSpace.l))
                    key(patchFailed) {
                        SwitchRow("Calm mode", (s["calm"] as? JsonPrimitive)?.contentOrNull == "1") { on ->
                            haptic.tick()
                            patchMed("calm", JsonPrimitive(if (on) "1" else ""))
                        }
                    }
                    Hairline(Modifier.padding(start = AppSpace.l))
                    key(patchFailed) {
                        SwitchRow("Guess first", (s["guess"] as? JsonPrimitive)?.contentOrNull == "1") { on ->
                            haptic.tick()
                            patchMed("guess", JsonPrimitive(if (on) "1" else ""))
                        }
                    }
                }
            }
        }
        med?.let { s ->
            val started = (s["medication_started"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            item {
                Section("Medication", "Used to mark doses on the Heart screen and to compare your heart rate with the days before you started.") {
                    key(patchFailed) {
                        SwitchRow("I take medication", started.isNotEmpty()) { on ->
                            patchMed("medication_started", JsonPrimitive(if (on) LocalDate.now().toString() else ""))
                        }
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
            item {
                Section("Things to mark", "Name something that comes and goes: a migraine, an afternoon crash, heartburn. Tap it when it happens and " +
                    "Circa shows what your body did the day before. Clear the name to stop; old marks are kept.") {
                    things.forEachIndexed { i, name ->
                        if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                        FieldRow("Thing ${i + 1}", name, "Not set") {
                            things = things.toMutableList().also { l -> l[i] = it.take(24) }
                            saveMark(i + 1, things[i])
                        }
                    }
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
                        key(patchFailed) {
                            SwitchRow(title, kind !in muted, enabled = notifyGranted) { on ->
                                patchMed("muted", JsonPrimitive((if (on) muted - kind else muted + kind).joinToString(",")))
                            }
                        }
                        Hairline(Modifier.padding(start = AppSpace.l))
                    }
                }
                SwitchRow("Ring battery", ctx.batteryAlerts, enabled = notifyGranted) { ctx.batteryAlerts = it }
            }
        }
        if (med != null) item {
            Section("Data", "The last 90 days as a one-page PDF or a spreadsheet file. Estimates from a consumer ring, not medical measurements.") {
                ButtonRow("Share report", enabled = !sharing) { share(pdf = true) }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Export CSV", enabled = !sharing) { share(pdf = false) }
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
                FieldRow("Address", ring, "Not configured") { ring = it.trim(); ctx.ringAddress = ring.takeIf { a -> a.isNotEmpty() }; if (ring.isNotEmpty() && url.isNotEmpty()) SyncWorker.schedule(ctx) }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow(if (scanning) "Scanning…" else "Scan for ring", enabled = !scanning, onClick = ::startScan)
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("URL", url, "http://host:8429", KeyboardType.Uri) { url = it.trim(); ctx.vmUrl = url; if (ring.isNotEmpty() && url.isNotEmpty()) SyncWorker.schedule(ctx) }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Token", token, "Optional", KeyboardType.Password, password = true) { token = it.trim(); ctx.apiToken = token }
                Hairline(Modifier.padding(start = AppSpace.l))
                ChevronRow("Android battery", if (batteryOk) "Unrestricted" else "Optimised") {
                    if (!batteryOk) MainActivity.requestBatteryExemption(ctx)
                    else Toast.makeText(ctx, "Already unrestricted", Toast.LENGTH_SHORT).show()
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Clear cached data") {
                    Api.clearCache(ctx)
                    Toast.makeText(ctx, "Cache cleared", Toast.LENGTH_SHORT).show()
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

    pickCar?.let { devices ->
        AlertDialog(
            onDismissRequest = { pickCar = null },
            containerColor = c.card,
            title = { Text("Car") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    (listOf("" to "None") + devices.map { it.address to (it.name?.takeIf(String::isNotBlank) ?: it.address) }).forEach { (address, name) ->
                        val cur = address == ctx.carAddress
                        Text(
                            name + if (cur) "  ✓" else "",
                            style = AppType.body, color = if (cur) c.tint else c.label,
                            modifier = Modifier.fillMaxWidth().clickable {
                                haptic.tick()
                                ctx.carAddress = address
                                car = if (address.isEmpty()) "" else name
                                ctx.carName = car
                                pickCar = null
                            }.padding(vertical = AppSpace.m),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton({ pickCar = null }) { Text("Cancel") } },
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
                                if (url.isNotEmpty()) SyncWorker.schedule(ctx)
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

/** One A4 page from the `/export.csv` body: period, average scores, four small charts. Fixed light colours: it is paper, not UI. */
private fun writeReport(ctx: Context, csv: String, out: File) {
    val lines = csv.trim().lines()
    val col = lines.first().split(',').withIndex().associate { it.value to it.index }
    // Days before the first ring data are dropped. The journal is the last column, so a plain split is safe for the rest.
    val rows = lines.drop(1).map { it.split(',') }.dropWhile { r -> r.drop(1).dropLast(1).all(String::isEmpty) }
    fun series(name: String) = rows.map { it.getOrNull(col[name] ?: -1)?.toFloatOrNull() }
    fun day(r: List<String>?) = r?.let { runCatching { LocalDate.parse(it[0]).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrNull() } ?: "—"

    val ink = 0xFF1C1C1E.toInt(); val grey = 0xFF8E8E93.toInt(); val rule = 0xFFE5E5EA.toInt()
    val indigo = 0xFF5E5CE6.toInt(); val green = 0xFF30D158.toInt(); val pink = 0xFFFF375F.toInt(); val red = 0xFFFF2D55.toInt(); val blue = 0xFF0A84FF.toInt()
    val nunito = ctx.resources.getFont(R.font.nunito_extrabold)
    val text = Paint(Paint.ANTI_ALIAS_FLAG)
    val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4 in points
    val cv = page.canvas
    fun put(s: String, x: Float, y: Float, size: Float, color: Int = ink, title: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        text.textSize = size; text.color = color; text.typeface = if (title) nunito else Typeface.DEFAULT; text.textAlign = align
        cv.drawText(s, x, y, text)
    }

    // The Circa mark: an open ring.
    mark.style = Paint.Style.STROKE; mark.strokeWidth = 5f; mark.color = ink
    cv.drawArc(50f, 46f, 76f, 72f, -50f, 290f, false, mark)
    mark.style = Paint.Style.FILL; mark.strokeWidth = 1.5f
    put("Circa report", 90f, 68f, 24f, title = true)
    put("${day(rows.firstOrNull())} – ${day(rows.lastOrNull())} · averages over ${rows.size} days", 48f, 100f, 11f, grey)
    listOf(Triple("Sleep", "sleep_score", indigo), Triple("Readiness", "readiness_score", green), Triple("Activity", "activity_score", pink)).forEachIndexed { i, (name, key, color) ->
        val x = 48f + i * 166f
        put("$name score", x, 140f, 10f, grey)
        put(series(key).filterNotNull().takeIf { it.isNotEmpty() }?.average()?.let { "%.0f".format(it) } ?: "—", x, 178f, 34f, color, title = true)
    }
    mark.color = rule; cv.drawLine(48f, 200f, 547f, 200f, mark)

    // The one chart helper: title, average, min / max rules, then a line per series (neighbouring days joined, every day a dot).
    fun chart(i: Int, title: String, unit: String, digits: Int, per: Float, vararg keys: Pair<String, Int>) {
        val x = 48f + (i % 2) * 261.5f; val y = 224f + (i / 2) * 270f; val w = 237.5f
        val top = y + 34f; val bottom = y + 230f; val left = x + 30f
        val ss = keys.map { k -> series(k.first).map { it?.div(per) } }
        val all = ss.flatten().filterNotNull()
        put(title, x, y + 12f, 12f, title = true)
        if (all.isEmpty()) return put("No data", x, y + 34f, 10f, grey)
        fun f(v: Float) = "%.${digits}f".format(v)
        put(ss.joinToString(" / ") { s -> f(s.filterNotNull().average().toFloat()) } + " $unit average", x + w, y + 12f, 10f, grey, align = Paint.Align.RIGHT)
        val lo = all.min(); val hi = maxOf(all.max(), lo + 1f)
        mark.color = rule; cv.drawLine(left, top, x + w, top, mark); cv.drawLine(left, bottom, x + w, bottom, mark)
        put(f(hi), left - 6f, top + 3f, 8f, grey, align = Paint.Align.RIGHT); put(f(lo), left - 6f, bottom + 3f, 8f, grey, align = Paint.Align.RIGHT)
        ss.forEachIndexed { k, s ->
            mark.color = keys[k].second
            var px = 0f; var py = 0f; var joined = false
            s.forEachIndexed { d, v ->
                if (v != null) {
                    val nx = left + 4f + (x + w - left - 8f) * d / maxOf(s.size - 1, 1); val ny = bottom - 6f - (bottom - top - 12f) * (v - lo) / (hi - lo)
                    if (joined) cv.drawLine(px, py, nx, ny, mark)
                    cv.drawCircle(nx, ny, 1.6f, mark)
                    px = nx; py = ny
                }
                joined = v != null
            }
        }
    }
    chart(0, "Resting heart rate", "bpm", 0, 1f, "rhr" to red)
    chart(1, "Heart rate variability", "ms", 0, 1f, "hrv" to blue)
    chart(2, "Time asleep", "h", 1, 60f, "sleep_min" to indigo)
    chart(3, "Blood pressure", "mmHg", 0, 1f, "bp_sys" to red, "bp_dia" to ink)
    put("Estimates from a consumer ring, not medical measurements.", 297.5f, 806f, 9f, grey, align = Paint.Align.CENTER)

    doc.finishPage(page)
    out.outputStream().use(doc::writeTo)
    doc.close()
}

private val INTERVALS = listOf(5, 15, 30, 60, 0)

private fun intervalLabel(m: Int) = if (m <= 0) "Off" else "$m min"

private fun lowStepLabel(k: String?) = if (k == "ride") "Rides" else "Workouts"

/** Backend notification kinds (`/settings.muted`) in Settings order. */
private val NOTIFY_KINDS = listOf(
    "morning" to "Morning sleep", "bedtime" to "Wind down", "stillup" to "Still up", "inactivity" to "Time to move",
    "strain" to "Body signals", "receipt" to "Back to normal", "checkin" to "Journal check-in", "workout" to "Workout check", "experiment" to "Experiment", "weekly" to "Your week", "monthly" to "Your month",
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
    supporting: String? = null,
    onChange: (String) -> Unit,
) {
    val c = AppTheme.colors
    Column {
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
    if (supporting != null) Text(
        supporting, style = AppType.footnote, color = c.activity, textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(start = AppSpace.l, end = AppSpace.l, bottom = AppSpace.s),
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
