package dev.ring.health

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import dev.ring.health.Prefs.lastSyncMs
import dev.ring.health.Prefs.ringAddress
import dev.ring.health.Prefs.vmUrl
import dev.ring.health.databinding.ActivityMainBinding
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

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val bleAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val scanResults = mutableListOf<ScanResult>()
    private var liveHrJob: Job? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) {
            onPermissionsGranted()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Opt into edge-to-edge so we receive the system bar / cutout insets
        // and can push our content below the clock + camera hole on the Pixel.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        // Populate saved settings
        binding.etRingAddress.setText(ringAddress ?: "")
        binding.etVmUrl.setText(vmUrl)

        binding.btnSave.setOnClickListener { saveSettings() }
        binding.btnScan.setOnClickListener { startScan() }
        binding.btnSyncNow.setOnClickListener {
            SyncWorker.runNow(this)
            Toast.makeText(this, "Sync started", Toast.LENGTH_SHORT).show()
        }
        binding.btnMeasureHr.setOnClickListener { measureHeartRate() }

        updateStatus()

        if (hasPermissions()) {
            onPermissionsGranted()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ── Permissions ────────────────────────────────────────────────────────

    private fun hasPermissions(): Boolean {
        val needed = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        )
    }

    private fun onPermissionsGranted() {
        SyncWorker.schedule(this)
        updateStatus()
    }

    // ── UI actions ─────────────────────────────────────────────────────────

    private fun saveSettings() {
        ringAddress = binding.etRingAddress.text.toString().trim().takeIf { it.isNotEmpty() }
        vmUrl       = binding.etVmUrl.text.toString().trim()
        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        updateStatus()
    }

    @SuppressLint("MissingPermission")
    private fun measureHeartRate() {
        if (liveHrJob?.isActive == true) return
        if (!hasPermissions()) { requestPermissions(); return }

        val address = ringAddress
        if (address.isNullOrBlank()) {
            Toast.makeText(this, "Set ring address first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnMeasureHr.isEnabled = false
        binding.btnMeasureHr.text = "Measuring…"
        binding.tvLiveHr.text = "Connecting to ring…"

        liveHrJob = lifecycleScope.launch {
            val result = runLiveHr(address)
            withContext(Dispatchers.Main) {
                binding.btnMeasureHr.isEnabled = true
                binding.btnMeasureHr.text = "Measure heart rate now"
                binding.tvLiveHr.text = result
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun runLiveHr(address: String): String {
        val conn = ColmiConnection(applicationContext, address)
        var measurementStarted = false
        return try {
            val connected = conn.connect()
            if (!connected) return "Could not connect (ring may be busy syncing — try again in a few seconds)"

            withContext(Dispatchers.Main) {
                binding.tvLiveHr.text = "Measuring (keep ring still)…"
            }
            conn.writePacket(ColmiProtocol.liveHrStartRequest())
            measurementStarted = true

            // Read for ~30s, accepting whatever bpm > 0 we see; the ring usually
            // streams a few invalid (bpm=0) packets first while it acquires.
            var lastBpm  = 0
            var notWorn  = false
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
                        withContext(Dispatchers.Main) {
                            binding.tvLiveHr.text = "♥ ${parsed.bpm} bpm  (measuring…)"
                        }
                        // Stable enough after ~6 valid readings (≈12s of streaming)
                        if (System.currentTimeMillis() > deadline - 18_000) break@loop
                    }
                }
            }

            when {
                notWorn       -> "Ring not worn correctly — adjust and retry"
                lastBpm == 0  -> "No reading — ensure ring is on a finger and try again"
                else          -> { pushLiveHr(lastBpm); "♥ $lastBpm bpm" }
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Live HR failed", t)
            "Error: ${t.message ?: t::class.simpleName}"
        } finally {
            // ALWAYS tell the ring to stop measuring before we disconnect, even
            // on early returns / exceptions. Otherwise the ring keeps its HR
            // sensor running on its own internal timeout (~minutes).
            if (measurementStarted) {
                runCatching {
                    withTimeoutOrNull(2_000) {
                        conn.writePacket(ColmiProtocol.liveHrStopRequest())
                    }
                }.onFailure { Log.w("MainActivity", "Live HR stop failed", it) }
            }
            conn.disconnect()
        }
    }

    private suspend fun pushLiveHr(bpm: Int) {
        val url = vmUrl
        if (url.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                MetricsPusher(url).push(
                    listOf(
                        Metric(
                            name      = "biometric_hr_bpm",
                            labels    = mapOf("device" to "colmi_r02", "source" to "live"),
                            value     = bpm.toDouble(),
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                )
            }.onFailure { Log.w("MainActivity", "Live HR push failed", it) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        if (!hasPermissions()) { requestPermissions(); return }
        scanResults.clear()
        binding.btnScan.isEnabled = false
        binding.tvStatus.text = "Scanning for BLE devices…"

        val scanner = bleAdapter?.bluetoothLeScanner ?: run {
            Toast.makeText(this, "Bluetooth not available", Toast.LENGTH_SHORT).show()
            return
        }

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (scanResults.none { it.device.address == result.device.address }) {
                    scanResults += result
                }
            }
        }

        scanner.startScan(callback)
        lifecycleScope.launch {
            delay(5_000)
            withContext(Dispatchers.Main) {
                scanner.stopScan(callback)
                binding.btnScan.isEnabled = true
                showScanResults()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showScanResults() {
        if (scanResults.isEmpty()) {
            Toast.makeText(this, "No BLE devices found", Toast.LENGTH_SHORT).show()
            updateStatus()
            return
        }
        val items = scanResults.map { r ->
            val name = r.device.name?.takeIf { it.isNotBlank() } ?: "Unknown"
            "$name — ${r.device.address}"
        }
        AlertDialog.Builder(this)
            .setTitle("Select your ring")
            .setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items)) { _, i ->
                val address = scanResults[i].device.address
                ringAddress = address
                binding.etRingAddress.setText(address)
                Toast.makeText(this, "Ring address saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateStatus() {
        val addr = ringAddress
        val last = lastSyncMs
        val lastStr = if (last == 0L) "Never"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(last))

        binding.tvStatus.text = buildString {
            appendLine("Ring: ${addr ?: "not configured"}")
            appendLine("VM:   $vmUrl")
            append("Last sync: $lastStr")
        }
    }
}
