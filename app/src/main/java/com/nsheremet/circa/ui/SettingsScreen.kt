package com.nsheremet.circa.ui

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
import android.graphics.Canvas
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.nsheremet.circa.Prefs.phoneMoves
import com.nsheremet.circa.registerMoves
import com.nsheremet.circa.unregisterMoves
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Medication
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.luminance
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
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
import com.nsheremet.circa.ColmiConnection
import com.nsheremet.circa.ColmiProtocol
import com.nsheremet.circa.Metric
import com.nsheremet.circa.MetricsPusher
import com.nsheremet.circa.MainActivity
import com.nsheremet.circa.R
import com.nsheremet.circa.SampleStore
import com.nsheremet.circa.Prefs.apiToken
import com.nsheremet.circa.Prefs.batteryAlerts
import com.nsheremet.circa.Prefs.carAddress
import com.nsheremet.circa.Prefs.carName
import com.nsheremet.circa.driveFacts
import com.nsheremet.circa.driveWarning
import com.nsheremet.circa.Prefs.goalsPushed
import com.nsheremet.circa.Prefs.hasStoredGoals
import com.nsheremet.circa.Prefs.lastBatteryPct
import com.nsheremet.circa.Prefs.lastCharging
import com.nsheremet.circa.Prefs.lastSyncMs
import com.nsheremet.circa.Prefs.markNames
import com.nsheremet.circa.widget.MarkWidget
import com.nsheremet.circa.Prefs.ringAddress
import com.nsheremet.circa.Prefs.sleepNeedHours
import com.nsheremet.circa.Prefs.usageAccess
import com.nsheremet.circa.Prefs.holdPlace
import com.nsheremet.circa.Prefs.notificationAccess
import com.nsheremet.circa.Prefs.phoneSensor
import com.nsheremet.circa.Prefs.reportName
import com.nsheremet.circa.Prefs.reportWriter
import com.nsheremet.circa.Prefs.lastScreenMs
import com.nsheremet.circa.Prefs.stepGoal
import com.nsheremet.circa.Prefs.syncIntervalMin
import com.nsheremet.circa.Prefs.vmUrl
import com.nsheremet.circa.SyncLog
import com.nsheremet.circa.SyncWorker
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
fun SettingsScreen(hasPermissions: () -> Boolean, requestPermissions: () -> Unit, backTo: String, onBack: () -> Unit) {
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
    var movesOn by remember { mutableStateOf(ctx.phoneMoves) }
    var movesDenied by remember { mutableIntStateOf(0) } // SwitchRow keeps its own state: a new key redraws it off
    val askMoves = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        ctx.phoneMoves = ok; movesOn = ok
        if (!ok) movesDenied++
        if (ok) registerMoves(ctx)
    }
    var notifyGranted by remember { mutableStateOf(notifyOk()) }
    var car by remember { mutableStateOf(ctx.carName) }
    var writerOn by remember { mutableStateOf(ctx.reportWriter) }
    var reportName by remember { mutableStateOf(ctx.reportName) }
    var offerModel by remember { mutableStateOf(false) }
    var pickCar by remember { mutableStateOf<List<BluetoothDevice>?>(null) } // the bonded devices while the Car dialog is open
    var driveNow by remember { mutableStateOf(System.currentTimeMillis()) }
    var pickInterval by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(false) }
    var showLab by remember { mutableStateOf(false) }
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
    var medName by remember { mutableStateOf("") }
    var addStep by remember { mutableStateOf<Pair<String, LocalDate>?>(null) } // the Add dose step dialog: (mg typed, from)
    var pickStepDate by remember { mutableStateOf(false) }
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
        medName = (med?.get("medication_name") as? JsonPrimitive)?.contentOrNull.orEmpty()
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
    fun saveSteps(steps: List<Api.DoseStep>) {
        haptic.tick()
        activity.lifecycleScope.launch { Api.setDoseSteps(ctx, steps)?.let { med = it } ?: Toast.makeText(ctx, "Could not save the dose steps", Toast.LENGTH_SHORT).show() }
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

    // The Data page's files: [load] fetches the data and returns the writer (null: could not load); the file goes
    // into cacheDir/share/[name], then the system share sheet.
    var sharing by remember { mutableStateOf(false) }
    fun share(name: String, mime: String, load: suspend () -> ((File) -> Unit)?) {
        haptic.tick()
        sharing = true
        activity.lifecycleScope.launch {
            val uri = load()?.let { write ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val file = File(File(ctx.cacheDir, "share").apply { mkdirs() }, name)
                        write(file)
                        FileProvider.getUriForFile(ctx, ctx.packageName + ".share", file)
                    }.onFailure { Log.w("Settings", "Share failed", it) }.getOrNull()
                }
            }
            sharing = false
            if (uri == null) Toast.makeText(ctx, "Could not load your data", Toast.LENGTH_SHORT).show()
            else ctx.startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .apply { clipData = ClipData.newRawUri(null, uri) }, // the grant travels with the chooser only through ClipData
                null,
            ))
        }
    }

    // MH0: null is the root list of groups; a group's name opens its page (pages, not nav routes, as Sync log is).
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }

    if (showLog) return SyncLogScreen { showLog = false }
    if (showLab) return RingLabScreen { showLab = false }

    fun synced(ms: Long) = if (ms == 0L) "Never" else SimpleDateFormat("d MMM, ", Locale.getDefault()).format(Date(ms)) + clock(ms / 1000.0)
    fun open(group: String) { haptic.tick(); page = group }
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        page, label = "page",
        transitionSpec = {
            // iOS push: the page comes in from the right over the root, which drifts a third to the left; Back reverses it.
            val push = targetState != null
            val move = spring<IntOffset>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
            when {
                reduce -> EnterTransition.None togetherWith ExitTransition.None
                push -> slideInHorizontally(move) { it } togetherWith slideOutHorizontally(move) { -it / 3 }
                else -> (slideInHorizontally(move) { -it / 3 } togetherWith slideOutHorizontally(move) { it }).apply { targetContentZIndex = -1f }
            }
        },
    ) { p ->
    LargeTitleScaffold(
        p ?: "Settings",
        back = {
            if (p == null) Text("‹ $backTo", style = AppType.headline, color = c.tint, modifier = Modifier.clickable(onClick = onBack).padding(start = AppSpace.xs, bottom = AppSpace.xs))
            else Text("‹ Settings", style = AppType.headline, color = c.tint, modifier = Modifier.clickable { page = null }.padding(start = AppSpace.xs, bottom = AppSpace.xs))
        },
    ) {
        when (p) {
        null -> {
        item {
            // The ring itself: its battery as the app's ring, its name, the last sync.
            val battery = ctx.lastBatteryPct
            val low = battery in 0..20
            val tone = if (low) c.signalHigh else c.readiness
            val name = remember(ring) {
                if (ring.isEmpty()) "No ring set"
                else runCatching { (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter.getRemoteDevice(ring).name }.getOrNull() ?: "Your ring"
            }
            InsetCard {
                Row(
                    Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(tone.copy(alpha = 0.12f), tone.copy(alpha = 0f))))
                        .clickable(role = Role.Button) { open("Ring") }.padding(AppSpace.l),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ScoreRing(if (battery < 0) 0f else battery / 100f, tone, Modifier.size(76.dp), stroke = 8.dp, animate = !reduce) {
                        Text(if (battery < 0) "—" else "$battery%", style = AppType.headline.copy(fontFamily = AppType.title.fontFamily, fontWeight = FontWeight.ExtraBold))
                    }
                    Column(Modifier.weight(1f).padding(start = AppSpace.l)) {
                        Text(name, style = AppType.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when { battery < 0 -> "Battery not read yet"; ctx.lastCharging -> "Charging"; low -> "Battery low"; else -> "Battery" },
                            style = AppType.subhead, color = if (low && !ctx.lastCharging) c.badText else c.secondaryLabel,
                        )
                        Text(
                            when {
                                lastSync == 0L -> "Never synced"
                                android.text.format.DateUtils.isToday(lastSync) -> "Synced " + clock(lastSync / 1000.0)
                                else -> "Synced " + SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(lastSync))
                            }, style = AppType.subhead, color = c.secondaryLabel)
                    }
                }
            }
        }
        item {
            InsetCard {
                GroupRow(Icons.Rounded.RadioButtonUnchecked, c.sleep, "Ring", if (interval <= 0) "Sync off" else "Every ${intervalLabel(interval)}") { open("Ring") }
                GroupHairline()
                GroupRow(Icons.Rounded.Person, c.tint, "You", steps.toIntOrNull()?.let { "%,d steps".format(it) }.orEmpty()) { open("You") }
            }
        }
        med?.let { s ->
            item {
                InsetCard {
                    val started = (s["medication_started"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    GroupRow(Icons.Rounded.Medication, c.pill, "Medication", if (started.isEmpty()) "Off" else clock((s["medication_time"] as? JsonPrimitive)?.contentOrNull.orEmpty())) { open("Medication") }
                    GroupHairline()
                    val n = things.count(String::isNotBlank)
                    GroupRow(Icons.Rounded.Psychology, c.mind, "Mind", when (n) { 0 -> "None"; 1 -> "1 thing"; else -> "$n things" }) { open("Mind") }
                }
            }
        }
        item {
            InsetCard {
                val muted = (med?.get("muted") as? JsonPrimitive)?.contentOrNull.orEmpty().split(',')
                val on = (if (med == null) 0 else NOTIFY_GROUPS.sumOf { g -> g.second.count { it.first !in muted } }) + (if (ctx.batteryAlerts) 1 else 0)
                GroupRow(Icons.Rounded.Notifications, c.heart, "Notifications", if (!notifyGranted || on == 0) "Off" else "$on on") { open("Notifications") }
                GroupHairline()
                val phone = listOf(phoneOn, holdOn, movesOn).count { it }
                GroupRow(Icons.Rounded.PhoneAndroid, c.readiness, "Phone", if (phone == 0) "Off" else "$phone on") { open("Phone") }
                GroupHairline()
                GroupRow(Icons.AutoMirrored.Rounded.Article, c.stageAwake, "Reports", if (writerOn) "On" else "Off") { open("Reports") }
                if (med != null) {
                    GroupHairline()
                    GroupRow(Icons.Rounded.IosShare, c.secondaryLabel, "Data", "") { open("Data") }
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
        "Ring" -> {
        item {
            Section(null, "Each sync reads new data from the ring and pushes it to the server.") {
                ValueRow("Last sync", synced(lastSync))
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
                Hairline(Modifier.padding(start = AppSpace.l))
                ChevronRow("Ring lab", "") { showLab = true }
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
        }
        "You" -> {
        item {
            Section(null, "Your birth year sets your maximum heart rate for cardio zones. Goals shape the Activity and Sleep scores." +
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
            item {
                Section("Workouts", "The ring can't tell cycling from a gym session — both show few steps. Pick what you do most; you can change any session on the Heart screen.") {
                    ChevronRow("Low-step sessions are", lowStepLabel((s["low_step_kind"] as? JsonPrimitive)?.contentOrNull)) { pickLowStep = true }
                }
            }
        }
        }
        "Medication" -> med?.let { s ->
            val started = (s["medication_started"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            item {
                Section(null, "Used to mark doses on the Heart screen and to compare your heart rate with the days before you started.") {
                    key(patchFailed) {
                        SwitchRow("I take medication", started.isNotEmpty()) { on ->
                            patchMed("medication_started", JsonPrimitive(if (on) LocalDate.now().toString() else ""))
                        }
                    }
                    if (started.isNotEmpty()) {
                        Hairline(Modifier.padding(start = AppSpace.l))
                        FieldRow("Name", medName, "Not set") {
                            medName = it.take(40)
                            goalJobs["medication_name"]?.cancel()
                            goalJobs["medication_name"] = activity.lifecycleScope.launch { delay(800); patchMed("medication_name", JsonPrimitive(medName.trim())) }
                        }
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Started on", runCatching { LocalDate.parse(started).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrDefault(started)) { pickStart = true }
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Usual time", clock((s["medication_time"] as? JsonPrimitive)?.contentOrNull.orEmpty())) { pickTime = true }
                        Hairline(Modifier.padding(start = AppSpace.l))
                        ChevronRow("Active window", "${(s["medication_window_h"] as? JsonPrimitive)?.doubleOrNull?.toInt() ?: "—"} h") { pickWindow = true }
                    }
                }
            }
            if (started.isNotEmpty()) item {
                val steps = Api.doseSteps(s)
                Section("Dose steps", "A day counts under the latest step on or before it. Heart's Medication card shows each step side by side.") {
                    val fmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
                    steps.forEach { st ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = AppSpace.l), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                Api.mgLabel(st.mg), style = AppType.subhead.copy(fontWeight = FontWeight.SemiBold), color = c.pill, maxLines = 1,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(c.pill.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 3.dp),
                            )
                            Text("from ${st.from.format(fmt)}", style = AppType.body, color = c.secondaryLabel, maxLines = 1, modifier = Modifier.weight(1f).padding(start = AppSpace.m))
                            Box(
                                Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Delete ${Api.mgLabel(st.mg)} step") { saveSteps(steps - st) },
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Rounded.RemoveCircle, "Delete", tint = c.badText, modifier = Modifier.size(22.dp)) }
                        }
                        Hairline(Modifier.padding(start = AppSpace.l))
                    }
                    ButtonRow("Add dose step") { addStep = "" to LocalDate.now() }
                }
            }
        }
        "Mind" -> if (med != null) {
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
        "Notifications" -> {
            val muted = (med?.get("muted") as? JsonPrimitive)?.contentOrNull.orEmpty().split(',').filter(String::isNotEmpty)
            // Without the backend only the phone's own Ring battery alert is left (in Day).
            val groups = NOTIFY_GROUPS.filter { med != null || it.first == "Day" }
            groups.forEachIndexed { gi, (header, kinds) ->
                item {
                    Section(
                        header,
                        if (gi < groups.lastIndex) null
                        else if (notifyGranted) "Notifications arrive with a sync, so they can run a few minutes late."
                        else "Allow notifications for Circa in Android settings to receive these.",
                    ) {
                        if (med != null) kinds.forEachIndexed { i, (kind, title) ->
                            if (i > 0) Hairline(Modifier.padding(start = AppSpace.l))
                            key(patchFailed) {
                                SwitchRow(title, kind !in muted, enabled = notifyGranted) { on ->
                                    patchMed("muted", JsonPrimitive((if (on) muted - kind else muted + kind).joinToString(",")))
                                }
                            }
                        }
                        if (header == "Day") {
                            if (med != null) Hairline(Modifier.padding(start = AppSpace.l))
                            SwitchRow("Ring battery", ctx.batteryAlerts, enabled = notifyGranted) { ctx.batteryAlerts = it }
                        }
                    }
                }
            }
        }
        "Phone" -> {
        item {
            Section(null, "Screen on/off times fix nights where you scrolled in bed. Only the times are sent." +
                (if (phoneOn && !usageOk) " Allow Usage access for Circa to start. Greyed out? In App info, tap ⋮ › Allow restricted settings first." else "") +
                "\n\nHold my place shows where in a podcast or audiobook you fell asleep. Android calls the permission notification access; " +
                "Circa only uses it to see what is playing and never reads your notifications." +
                (if (holdOn && !listenOk) " Allow Notification access for Circa to start. Greyed out? In App info, tap ⋮ › Allow restricted settings first." else "") +
                "\n\nNotice rides and walks lets your phone tell Circa when you start and stop walking, running or cycling, and adds them to Activity. " +
                "Android calls the permission physical activity; Circa never reads your location.") {
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
                Hairline(Modifier.padding(start = AppSpace.l))
                key(movesDenied) {
                    SwitchRow("Notice rides and walks", movesOn) { on ->
                        haptic.tick()
                        if (!on) { ctx.phoneMoves = false; movesOn = false; unregisterMoves(ctx) }
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) { ctx.phoneMoves = true; movesOn = true; registerMoves(ctx) }
                        else askMoves.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
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
        }
        "Reports" -> {
        item {
            Section(null, "Circa can write each report card as a short note, on this phone, with an open model (Gemma 4). " +
                "Nothing you write or measure leaves the phone for it. Without the model the cards show their plain text.") {
                SwitchRow("Write reports", writerOn) { on ->
                    haptic.tick()
                    ctx.reportWriter = on; writerOn = on
                    offerModel = on
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                FieldRow("Your name", reportName, "Not set") { reportName = it.take(24); ctx.reportName = reportName }
                Hairline(Modifier.padding(start = AppSpace.l))
                ReportModelRows(offerModel) { offerModel = false }
            }
        }
        }
        "Data" -> med?.let { s ->
        item {
            Section(null, "The last 90 days as a one-page PDF or a spreadsheet file. Estimates from a consumer ring, not medical measurements.") {
                ButtonRow("Share report", enabled = !sharing) {
                    share("circa-report.pdf", "application/pdf") { Api.exportCsv(ctx)?.let { csv -> { f: File -> writeReport(ctx, csv, f) } } }
                }
                Hairline(Modifier.padding(start = AppSpace.l))
                ButtonRow("Export CSV", enabled = !sharing) {
                    share("circa-export.csv", "text/csv") { Api.exportCsv(ctx)?.let { csv -> { f: File -> f.writeText(csv) } } }
                }
            }
        }
        // MH6: shown once there is a dose step.
        Api.doseSteps(s).firstOrNull()?.let { first ->
            item {
                Section(null, "Dose steps, averages by dose, blood pressure by step and the last 14 days of mood on one page, for your prescriber.") {
                    ButtonRow("Titration summary (PDF)", enabled = !sharing) {
                        share("circa-titration.pdf", "application/pdf") {
                            val m = ((Api.insights(ctx) as? JsonObject)?.get("kinds") as? JsonObject)?.get("medication") as? JsonObject
                            val j = Api.journal(ctx, ChronoUnit.DAYS.between(first.from, LocalDate.now()).toInt() + 1) as? JsonObject
                            val mind = Api.mind(ctx, 14)
                            if (m == null || j == null || mind == null) null else { f: File -> writeTitration(ctx, f, s, m, j, mind) }
                        }
                    }
                }
            }
        }
        }
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

    addStep?.let { (mgText, from) ->
        val mg = mgText.replace(',', '.').toDoubleOrNull()?.takeIf { it >= 1 && it <= 500 }
        if (pickStepDate) StartDateDialog(from.toString(), { pickStepDate = false }) { pickStepDate = false; addStep = mgText to LocalDate.parse(it) }
        else AlertDialog(
            onDismissRequest = { addStep = null },
            containerColor = c.background,
            title = { Text("Add dose step") },
            text = {
                InsetCard {
                    FieldRow("Dose (mg)", mgText, "30", KeyboardType.Decimal, supporting = if (mgText.isNotEmpty() && mg == null) "1 to 500" else null) {
                        addStep = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' }.take(5) to from
                    }
                    Hairline(Modifier.padding(start = AppSpace.l))
                    ChevronRow("From", from.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))) { pickStepDate = true }
                }
            },
            confirmButton = {
                TextButton({
                    addStep = null
                    // A step on the same date replaces the old one (setDoseSteps keeps the last per date).
                    saveSteps(Api.doseSteps(med) + Api.DoseStep(from, mg!!))
                }, enabled = mg != null) { Text("Add", color = if (mg != null) c.tint else c.secondaryLabel) }
            },
            dismissButton = { TextButton({ addStep = null }) { Text("Cancel", color = c.tint) } },
        )
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

/** What both PDFs draw with: fixed light colours (it is paper, not UI), the report's fonts, the Circa heading, the line plot. */
private class Sheet(ctx: Context, val cv: Canvas) {
    val ink = 0xFF1C1C1E.toInt(); val grey = 0xFF8E8E93.toInt(); val rule = 0xFFE5E5EA.toInt()
    val indigo = 0xFF5E5CE6.toInt(); val green = 0xFF30D158.toInt(); val pink = 0xFFFF375F.toInt(); val red = 0xFFFF2D55.toInt(); val blue = 0xFF0A84FF.toInt()
    val orange = 0xFFFF9F0A.toInt()
    private val nunito = ctx.resources.getFont(R.font.nunito_extrabold)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }

    fun put(s: String, x: Float, y: Float, size: Float, color: Int = ink, title: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        text.textSize = size; text.color = color; text.typeface = if (title) nunito else Typeface.DEFAULT; text.textAlign = align
        cv.drawText(s, x, y, text)
    }

    fun hairline(x0: Float, x1: Float, y: Float) { mark.color = rule; cv.drawLine(x0, y, x1, y, mark) }

    /** The Circa mark (an open ring), [title], and [sub] in grey under it. */
    fun head(title: String, sub: String) {
        mark.style = Paint.Style.STROKE; mark.strokeWidth = 5f; mark.color = ink
        cv.drawArc(50f, 46f, 76f, 72f, -50f, 290f, false, mark)
        mark.style = Paint.Style.FILL; mark.strokeWidth = 1.5f
        put(title, 90f, 68f, 24f, title = true)
        put(sub, 48f, 100f, 11f, grey)
    }

    /** Rules at [lo] and [hi] with their values, then a line per series (neighbouring days joined, every day a dot). */
    fun lines(left: Float, top: Float, right: Float, bottom: Float, lo: Float, hi: Float, label: (Float) -> String, ss: List<Pair<List<Float?>, Int>>) {
        mark.color = rule; cv.drawLine(left, top, right, top, mark); cv.drawLine(left, bottom, right, bottom, mark)
        put(label(hi), left - 6f, top + 3f, 8f, grey, align = Paint.Align.RIGHT); put(label(lo), left - 6f, bottom + 3f, 8f, grey, align = Paint.Align.RIGHT)
        ss.forEach { (s, color) ->
            mark.color = color
            var px = 0f; var py = 0f; var joined = false
            s.forEachIndexed { d, v ->
                if (v != null) {
                    val nx = left + 4f + (right - left - 8f) * d / maxOf(s.size - 1, 1); val ny = bottom - 6f - (bottom - top - 12f) * (v - lo) / (hi - lo)
                    if (joined) cv.drawLine(px, py, nx, ny, mark)
                    cv.drawCircle(nx, ny, 1.6f, mark)
                    px = nx; py = ny
                }
                joined = v != null
            }
        }
    }
}

/** One A4 page drawn by [draw], written to [out]. */
private fun onePage(ctx: Context, out: File, draw: Sheet.() -> Unit) {
    val doc = PdfDocument()
    val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()) // A4 in points
    Sheet(ctx, page.canvas).draw()
    doc.finishPage(page)
    out.outputStream().use(doc::writeTo)
    doc.close()
}

/** One A4 page from the `/export.csv` body: period, average scores, four small charts. */
private fun writeReport(ctx: Context, csv: String, out: File) = onePage(ctx, out) {
    val lines = csv.trim().lines()
    val col = lines.first().split(',').withIndex().associate { it.value to it.index }
    // Days before the first ring data are dropped. The journal is the last column, so a plain split is safe for the rest.
    val rows = lines.drop(1).map { it.split(',') }.dropWhile { r -> r.drop(1).dropLast(1).all(String::isEmpty) }
    fun series(name: String) = rows.map { it.getOrNull(col[name] ?: -1)?.toFloatOrNull() }
    fun day(r: List<String>?) = r?.let { runCatching { LocalDate.parse(it[0]).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) }.getOrNull() } ?: "—"

    head("Circa report", "${day(rows.firstOrNull())} – ${day(rows.lastOrNull())} · averages over ${rows.size} days")
    listOf(Triple("Sleep", "sleep_score", indigo), Triple("Readiness", "readiness_score", green), Triple("Activity", "activity_score", pink)).forEachIndexed { i, (name, key, color) ->
        val x = 48f + i * 166f
        put("$name score", x, 140f, 10f, grey)
        put(series(key).filterNotNull().takeIf { it.isNotEmpty() }?.average()?.let { "%.0f".format(it) } ?: "—", x, 178f, 34f, color, title = true)
    }
    hairline(48f, 547f, 200f)

    // The one chart helper: title, average, then the plot.
    fun chart(i: Int, title: String, unit: String, digits: Int, per: Float, vararg keys: Pair<String, Int>) {
        val x = 48f + (i % 2) * 261.5f; val y = 224f + (i / 2) * 270f; val w = 237.5f
        val ss = keys.map { k -> series(k.first).map { it?.div(per) } }
        val all = ss.flatten().filterNotNull()
        put(title, x, y + 12f, 12f, title = true)
        if (all.isEmpty()) return put("No data", x, y + 34f, 10f, grey)
        fun f(v: Float) = "%.${digits}f".format(v)
        put(ss.joinToString(" / ") { s -> f(s.filterNotNull().average().toFloat()) } + " $unit average", x + w, y + 12f, 10f, grey, align = Paint.Align.RIGHT)
        val lo = all.min()
        lines(x + 30f, y + 34f, x + w, y + 230f, lo, maxOf(all.max(), lo + 1f), { f(it) }, ss.zip(keys.map { it.second }))
    }
    chart(0, "Resting heart rate", "bpm", 0, 1f, "rhr" to red)
    chart(1, "Heart rate variability", "ms", 0, 1f, "hrv" to blue)
    chart(2, "Time asleep", "h", 1, 60f, "sleep_min" to indigo)
    chart(3, "Blood pressure", "mmHg", 0, 1f, "bp_sys" to red, "bp_dia" to ink)
    put("Estimates from a consumer ring, not medical measurements.", 297.5f, 806f, 9f, grey, align = Paint.Align.CENTER)
}

/**
 * MH6: one A4 page for the prescriber from GET /settings, `insights.kinds.medication`, GET /journal (since the first
 * step) and GET /mind?days=14. Only what was logged: no advice, no verdicts. A section with nothing to show is left out.
 */
private fun writeTitration(ctx: Context, out: File, settings: JsonObject, medication: JsonObject, journal: JsonObject, mind: JsonObject) = onePage(ctx, out) {
    fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    val today = LocalDate.now()
    val long = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()); val short = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val all = Api.doseSteps(settings)
    // shortcut: the latest 6 steps only, so everything fits one page; a longer titration would need a second page.
    val steps = all.takeLast(6)
    fun end(i: Int) = minOf(steps.getOrNull(i + 1)?.from?.minusDays(1) ?: today, today)
    fun stepOf(d: LocalDate) = steps.indexOfLast { it.from <= d } // -1: before the first step shown
    val entries = (journal["entries"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    fun logged(kind: String) = entries.filter { str(it, "kind") == kind }
        .mapNotNull { e -> runCatching { LocalDate.parse(str(e, "date")) to str(e, "value")!! }.getOrNull() }.sortedBy { it.first }
    fun section(title: String, y: Float, note: String) { put(title, 48f, y, 13f, title = true); put(note, 547f, y, 9f, grey, align = Paint.Align.RIGHT) }

    val name = str(settings, "medication_name")?.trim().orEmpty().ifEmpty { "Medication" }
    head("Titration summary", "$name · ${all.size} dose step${if (all.size == 1) "" else "s"} since ${all.first().from.format(long)}" +
        (if (all.size > steps.size) ", the latest ${steps.size} shown" else "") + " · prepared ${today.format(long)}")

    // Dose steps, with what the dose log says about each.
    var y = 140f
    section("Dose steps", y, str(settings, "medication_time")?.takeIf { it.isNotBlank() }?.let { "usual dose time set in the app: ${clock(it)}" }.orEmpty())
    y += 22f
    put("Dose", 48f, y, 9f, grey); put("From", 120f, y, 9f, grey); put("To", 215f, y, 9f, grey)
    put("Days", 345f, y, 9f, grey, align = Paint.Align.RIGHT); put("Days with a dose logged", 460f, y, 9f, grey, align = Paint.Align.RIGHT)
    put("Median dose time", 547f, y, 9f, grey, align = Paint.Align.RIGHT)
    val first = logged("medication").mapNotNull { (d, v) -> runCatching { d to LocalTime.parse(v) }.getOrNull() }
        .groupBy({ it.first }, { it.second }).mapValues { it.value.min() } // the day's first dose
    steps.forEachIndexed { i, st ->
        hairline(48f, 547f, y + 6f); y += 18f
        val days = (ChronoUnit.DAYS.between(st.from, end(i)) + 1).coerceAtLeast(0)
        val times = first.filterKeys { stepOf(it) == i }.values.sorted()
        put(Api.mgLabel(st.mg), 48f, y, 10f, title = true)
        put(st.from.format(long), 120f, y, 10f)
        put(if (i == steps.lastIndex) "today (current)" else end(i).format(long), 215f, y, 10f)
        put("$days", 345f, y, 10f, align = Paint.Align.RIGHT)
        put("${times.size}", 460f, y, 10f, align = Paint.Align.RIGHT)
        put(times.getOrNull(times.size / 2)?.let(::clock) ?: "—", 547f, y, 10f, if (times.isEmpty()) grey else ink, align = Paint.Align.RIGHT)
    }

    // By dose: the Heart card's table (same rows, same formatting), steps as columns.
    val levels = Api.doseLevels(medication).takeLast(steps.size)
    if (levels.isNotEmpty()) {
        y += 40f
        section("By dose", y, "means over each step's days with a dose logged")
        val w = minOf(90f, 369f / levels.size)
        fun right(i: Int) = 547f - (levels.size - 1 - i) * w
        y += 22f
        levels.forEachIndexed { i, l -> put(Api.mgLabel(l.mg), right(i), y, 10f, title = true, align = Paint.Align.RIGHT) }
        y += 12f
        levels.forEachIndexed { i, l -> put("from " + l.from.format(short), right(i), y, 9f, grey, align = Paint.Align.RIGHT) }
        BY_DOSE.forEach { (label, value) ->
            hairline(48f, 547f, y + 6f); y += 17f
            val unit = when (label) { "Blood pressure" -> " (mmHg)"; "Resting HR", "Time asleep", "Bedtime" -> ""; else -> " (1–5)" }
            put(label + unit, 48f, y, 10f, grey)
            levels.forEachIndexed { i, l -> val v = value(l.means); put(v ?: "—", right(i), y, 10f, if (v == null) grey else ink, align = Paint.Align.RIGHT) }
        }
        y += 16f
        put("— : fewer than 3 days with a dose logged and that value. Ratings are the user's own: 1 to 5; Crash 1 none … 5 strong.", 48f, y, 8f, grey)
    }

    // Blood pressure readings as logged, under the step their date falls in; the latest ones when there are many.
    val bp = logged("bp").mapNotNull { (d, v) -> v.split("/").mapNotNull { it.trim().toIntOrNull() }.takeIf { it.size == 2 }?.let { Triple(d, it[0], it[1]) } }
        .groupBy { stepOf(it.first) }
    if (steps.indices.any { bp[it] != null }) {
        y += 36f
        section("Blood pressure by step", y, "mmHg, as logged")
        val cap = 5 * (if (steps.size <= 3) 2 else 1) // 5 readings a line
        steps.forEachIndexed { i, st ->
            val rs = bp[i] ?: return@forEachIndexed
            hairline(48f, 547f, y + 6f); y += 18f
            put(Api.mgLabel(st.mg), 48f, y, 10f, title = true)
            val shown = if (rs.size > cap) rs.takeLast(cap - 1) else rs
            val cells = (if (rs.size > cap) listOf(null) else emptyList()) + shown
            cells.forEachIndexed { k, r ->
                if (k > 0 && k % 5 == 0) y += 15f
                val x = 120f + (k % 5) * 85.4f
                if (r == null) put("${rs.size - shown.size} earlier", x, y, 9f, grey)
                else { put(r.first.format(short), x, y, 9f, grey); put("${r.second}/${r.third}", x + 34f, y, 10f) }
            }
        }
    }

    // The last 14 days of energy and mood (daily means of the user's ratings), on the fixed 1 to 5 scale.
    val days = (mind["days"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.takeLast(14)
    fun scale(k: String) = days.map { d -> (d[k] as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()?.takeIf { it in 1f..5f } }
    val energy = scale("energy"); val mood = scale("valence")
    if ((energy + mood).any { it != null }) {
        y += 40f
        section("Energy and mood, last 14 days", y, "the user's own ratings, daily mean; gaps are days without one")
        y += 18f
        mark.color = orange; cv.drawCircle(52f, y - 3f, 3f, mark); put("Energy (1 flat … 5 wired)", 60f, y, 9f)
        mark.color = indigo; cv.drawCircle(202f, y - 3f, 3f, mark); put("Mood (1 bad … 5 good)", 210f, y, 9f)
        val top = y + 14f; val bottom = maxOf(minOf(top + 150f, 770f), top + 60f)
        hairline(72f, 547f, (top + bottom) / 2)
        put("3", 66f, (top + bottom) / 2 + 3f, 8f, grey, align = Paint.Align.RIGHT)
        // Every day's date under its point, and a rule with the new dose where the step changes.
        val step = 467f / maxOf(days.size - 1, 1) // same x as `lines` puts the points
        days.forEachIndexed { d, o ->
            val x = 76f + step * d
            str(o, "date")?.let { runCatching { LocalDate.parse(it).format(short) }.getOrNull() }?.let { put(it, x, bottom + 13f, 7.5f, grey, align = Paint.Align.CENTER) }
            val mg = str(o, "mg")?.toDoubleOrNull()
            if (d > 0 && mg != null && mg != str(days[d - 1], "mg")?.toDoubleOrNull()) {
                mark.color = grey; cv.drawLine(x - step / 2, top, x - step / 2, bottom, mark)
                put(Api.mgLabel(mg), x - step / 2 + 3f, top + 10f, 8f, grey)
            }
        }
        lines(72f, top, 547f, bottom, 1f, 5f, { "%.0f".format(it) }, listOf(energy to orange, mood to indigo))
    }
    put("Estimates from a consumer ring and the user's own ratings, not medical measurements.", 297.5f, 806f, 9f, grey, align = Paint.Align.CENTER)
}

private val INTERVALS = listOf(5, 15, 30, 60, 0)

private fun intervalLabel(m: Int) = if (m <= 0) "Off" else "$m min"

private fun lowStepLabel(k: String?) = if (k == "ride") "Rides" else "Workouts"

/** Backend notification kinds (`/settings.muted`) by when they arrive, in Settings order; Day also gets the phone's Ring battery. */
private val NOTIFY_GROUPS = listOf(
    "Morning" to listOf(
        "morning" to "Morning sleep", "checkin" to "Journal check-in", "strain" to "Body signals", "receipt" to "Back to normal",
        "experiment" to "Experiment", "weekly" to "Your week", "monthly" to "Your month", "dose" to "Dose not logged",
    ),
    "Day" to listOf("inactivity" to "Time to move", "workout" to "Workout check", "meal" to "No meal since dose"),
    "Evening" to listOf("bedtime" to "Wind down", "stillup" to "Still up", "ringoff" to "Ring still off at bedtime"),
)

/** Root row of a Settings group: tinted icon tile, name, short current value, chevron. */
@Composable
private fun GroupRow(icon: ImageVector, tint: Color, title: String, value: String, onClick: () -> Unit) {
    val c = AppTheme.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClick = onClick).padding(start = AppSpace.l, end = AppSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).background(tint.copy(alpha = 0.16f), RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(title, style = AppType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = AppSpace.m))
        Text(value, style = AppType.body, color = c.secondaryLabel, maxLines = 1, modifier = Modifier.padding(start = AppSpace.s))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.secondaryLabel)
    }
}

/** Separator under a [GroupRow], inset to the text like iOS (16 + 30 tile + 12). */
@Composable
private fun GroupHairline() = Hairline(Modifier.padding(start = 58.dp))

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
internal fun Section(header: String?, footer: String? = null, rows: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column {
        // null: the page's large title already names it.
        if (header != null) Text(
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
internal fun ValueRow(title: String, value: String) {
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
internal fun ButtonRow(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = AppTheme.colors
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = AppSpace.l), contentAlignment = Alignment.CenterStart) {
        Text(title, style = AppType.body, color = if (enabled) c.tint else c.secondaryLabel)
    }
}

// ── Live heart rate (moved from MainActivity; behaviour unchanged) ──────────

/**
 * Streams live HR for up to [durationMs]; [stopWhenStable] ends early once readings settle and pushes the last bpm.
 * [onLight] set: the light copy is on (`A1 0A` after each `69 01`) and gets each `A1 0C` packet's unsaturated pulse readings.
 */
@SuppressLint("MissingPermission")
internal suspend fun runLiveHr(
    ctx: Context, address: String, onBpm: (Int) -> Unit = {}, durationMs: Long = 30_000, stopWhenStable: Boolean = true,
    onLight: ((List<Int>) -> Unit)? = null,
    onProgress: (String) -> Unit,
): String {
    val conn = ColmiConnection(ctx, address)
    var measurementStarted = false
    var packets = 0; var valid = 0; var restarts = 0
    var lastLight = 0L // last light packet since the last start request; 0 = none
    SyncWorker.liveHrActive = true // a background sync would take the ring mid-stream
    return try {
        val connected = conn.connect()
        if (!connected) return "Could not connect (ring may be busy syncing — try again in a few seconds)"

        onProgress("Measuring (keep ring still)…")
        conn.writePacket(ColmiProtocol.liveHrStartRequest())
        measurementStarted = true
        // after 69 01: a measurement start clears the light copy
        if (onLight != null) conn.writePacket(ColmiProtocol.lightOnRequest())

        // Read for ~30s, accepting whatever bpm > 0 we see; the ring usually
        // streams a few invalid (bpm=0) packets first while it acquires.
        var lastBpm = 0
        var notWorn = false
        val deadline = System.currentTimeMillis() + durationMs
        var lastLock = System.currentTimeMillis() // last start request or valid reading
        loop@ while (currentCoroutineContext().isActive && System.currentTimeMillis() < deadline) {
            // Long mode: the ring may stop streaming on its own. It needs ~25 s to lock on after a start
            // (seen on the real ring), so ask again only after 30 s without a valid reading.
            // With light: the ring ended its measurement when samples stop for 1 s; restart at once (one restart per silence).
            val lightSilent = lastLight > 0 && System.currentTimeMillis() - lastLight >= 1_000
            if (!stopWhenStable && (lightSilent || System.currentTimeMillis() - lastLock >= 30_000)) {
                conn.writePacket(ColmiProtocol.liveHrStartRequest())
                if (onLight != null) conn.writePacket(ColmiProtocol.lightOnRequest()) // the copy turns off at each measurement end
                lastLock = System.currentTimeMillis(); restarts++; lastLight = 0
            }
            val left = deadline - System.currentTimeMillis()
            // short wait with light so 1 s of silence is seen within about 1.2 s
            val pkt = withTimeoutOrNull(if (stopWhenStable) left else minOf(left, if (onLight != null) 200 else 5_000)) {
                conn.notifyV1.receive()
            }
            if (pkt == null) {
                if (stopWhenStable || System.currentTimeMillis() >= deadline) break
                continue
            }
            packets++
            val light = if (onLight != null) ColmiProtocol.parseLightPacket(pkt) else null
            if (onLight != null && light != null) {
                lastLight = System.currentTimeMillis()
                onLight(light.filter { ColmiProtocol.lightTag(it) == 1 && ColmiProtocol.lightReading(it) < 0x1FFF00 }.map(ColmiProtocol::lightReading))
                continue
            }
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
            if (onLight != null) runCatching {
                withContext(NonCancellable) { withTimeoutOrNull(2_000) { conn.writePacket(ColmiProtocol.lightOffRequest()) } }
            }.onFailure { Log.w("Settings", "Light copy off failed", it) }
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
