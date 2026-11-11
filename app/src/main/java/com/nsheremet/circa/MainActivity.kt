package com.nsheremet.circa

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.nsheremet.circa.Prefs.askedBattery
import com.nsheremet.circa.ui.App

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasPermissions()) {
            SyncWorker.schedule(this)
            askBatteryOnce()
        } else {
            Toast.makeText(this, "Bluetooth permissions required", Toast.LENGTH_LONG).show()
        }
    }

    /** Notification deep link: extras `open` (journal | trends) and `date` (yyyy-MM-dd). Cleared once handled. */
    private var link by mutableStateOf<Pair<String, String?>?>(null)

    private fun readLink(i: Intent) {
        i.getStringExtra("open")?.let { link = it to i.getStringExtra("date") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readLink(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) readLink(intent) // not again after rotation
        setContent { App(::hasPermissions, ::requestPermissions, link) { link = null } }

        if (hasPermissions()) SyncWorker.schedule(this)
        if (!hasPermissions() || !hasNotifications()) requestPermissions() else askBatteryOnce()
    }

    // Without this exemption Doze stretches the 5-minute ring read + push to much longer gaps.
    private fun askBatteryOnce() {
        if (askedBattery || isIgnoringBatteryOptimizations(this)) return
        askedBattery = true
        requestBatteryExemption(this)
    }

    companion object {
        fun isIgnoringBatteryOptimizations(ctx: android.content.Context): Boolean =
            (ctx.getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

        @android.annotation.SuppressLint("BatteryLife")
        fun requestBatteryExemption(ctx: android.content.Context) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)))
        }
    }

    private val blePermissions = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasPermissions(): Boolean =
        blePermissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun hasNotifications(): Boolean = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        permissionLauncher.launch(
            (blePermissions + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()).toTypedArray()
        )
    }
}
