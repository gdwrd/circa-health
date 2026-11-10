package dev.ring.health

import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import androidx.core.app.NotificationManagerCompat

object Prefs {
    private const val NAME = "ring_health"

    private const val KEY_RING_ADDRESS    = "ring_address"
    private const val KEY_VM_URL          = "vm_url"
    private const val KEY_API_TOKEN       = "api_token"
    private const val KEY_STEP_GOAL       = "step_goal"
    private const val KEY_SLEEP_NEED_H    = "sleep_need_h"
    private const val KEY_GOALS_PUSHED    = "goals_pushed"
    private const val KEY_LAST_SYNC_MS    = "last_sync_ms"
    private const val KEY_LAST_SCREEN_MS  = "last_screen_ms"
    private const val KEY_PHONE_SENSOR    = "phone_sensor"
    private const val KEY_SYNC_INTERVAL   = "sync_interval_min"
    private const val KEY_ASKED_BATTERY   = "asked_battery"
    private const val KEY_BATTERY_PCT     = "battery_pct"
    private const val KEY_CHARGING        = "charging"
    private const val KEY_BATTERY_WARNED  = "battery_warned"
    private const val KEY_CHARGED_TOLD    = "charged_told"
    private const val KEY_BATTERY_ALERTS  = "battery_alerts"
    private const val KEY_TODAY_JSON      = "today_json"
    private const val KEY_TODAY_JSON_MS   = "today_json_ms"
    private const val KEY_LAST_DEEP_DAY   = "last_deep_sync_day"
    private const val KEY_STALE_WARNED    = "stale_warned"
    private const val KEY_BREATH_ROUNDS   = "breath_rounds"
    private const val KEY_SUGGEST_DISMISSED = "suggest_dismissed"
    private const val KEY_HOLD_PLACE      = "hold_place"
    private const val KEY_HOLD_DISMISSED  = "hold_dismissed"
    private const val KEY_CAR_ADDRESS     = "car_address"
    private const val KEY_CAR_NAME        = "car_name"
    private const val KEY_DRIVE_WARNED    = "drive_warned_wake"
    private const val KEY_MARK_NAMES      = "mark_names"
    private const val KEY_SHOW_NUMBERS    = "show_numbers"
    private const val KEY_PHONE_MOVES     = "phone_moves"
    private const val KEY_OPEN_MOVE       = "open_move"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var Context.ringAddress: String?
        get() = prefs(this).getString(KEY_RING_ADDRESS, null)
        set(v) = prefs(this).edit().putString(KEY_RING_ADDRESS, v).apply()

    var Context.vmUrl: String
        get() = prefs(this).getString(KEY_VM_URL, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_VM_URL, v).apply()

    var Context.apiToken: String
        get() = prefs(this).getString(KEY_API_TOKEN, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_API_TOKEN, v).apply()

    var Context.stepGoal: Int
        get() = prefs(this).getInt(KEY_STEP_GOAL, 8_000)
        set(v) = prefs(this).edit().putInt(KEY_STEP_GOAL, v).apply()

    var Context.sleepNeedHours: Float
        get() = prefs(this).getFloat(KEY_SLEEP_NEED_H, 7.5f)
        set(v) = prefs(this).edit().putFloat(KEY_SLEEP_NEED_H, v).apply()

    /** False until the backend holds this phone's step goal and sleep need; Settings then pushes them before it reads the server's. */
    var Context.goalsPushed: Boolean
        get() = prefs(this).getBoolean(KEY_GOALS_PUSHED, false)
        set(v) = prefs(this).edit().putBoolean(KEY_GOALS_PUSHED, v).apply()

    /** A step goal or sleep need was stored on this phone; false on a fresh install, whose defaults must not reach the server. */
    val Context.hasStoredGoals: Boolean
        get() = prefs(this).run { contains(KEY_STEP_GOAL) || contains(KEY_SLEEP_NEED_H) }

    /** Last sync whose push succeeded (or had nothing to push); 0 = never. Drives deep sync and the stale warning. */
    var Context.lastSyncMs: Long
        get() = prefs(this).getLong(KEY_LAST_SYNC_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_SYNC_MS, v).apply()

    /** Newest phone screen on/off event already stored; 0 = never read (first read starts 7 days back). */
    var Context.lastScreenMs: Long
        get() = prefs(this).getLong(KEY_LAST_SCREEN_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_SCREEN_MS, v).apply()

    /** Settings › Phone switch; off (default) = screen times are never read, whatever Usage access says. */
    var Context.phoneSensor: Boolean
        get() = prefs(this).getBoolean(KEY_PHONE_SENSOR, false)
        set(v) = prefs(this).edit().putBoolean(KEY_PHONE_SENSOR, v).apply()

    /** Usage access, granted by the user in system Settings; needed to read screen on/off events. */
    val Context.usageAccess: Boolean
        get() = (getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager)
            .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED

    /** Settings › Phone switch; off (default) = PlaybackListener writes nothing, whatever Notification access says. */
    var Context.holdPlace: Boolean
        get() = prefs(this).getBoolean(KEY_HOLD_PLACE, false)
        set(v) = prefs(this).edit().putBoolean(KEY_HOLD_PLACE, v).apply()

    /** The day (the server's date) whose Hold my place card got its ✕. */
    var Context.holdDismissed: String
        get() = prefs(this).getString(KEY_HOLD_DISMISSED, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_HOLD_DISMISSED, v).apply()

    /** Settings › Phone switch; off (default) = no activity transitions are registered and no ride or walk is written. */
    var Context.phoneMoves: Boolean
        get() = prefs(this).getBoolean(KEY_PHONE_MOVES, false)
        set(v) = prefs(this).edit().putBoolean(KEY_PHONE_MOVES, v).apply()

    /** The move in progress as "metric|startMs" (phone_walk / phone_ride / phone_run); "" = none. */
    var Context.openMove: String
        get() = prefs(this).getString(KEY_OPEN_MOVE, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_OPEN_MOVE, v).apply()

    /** Notification access, granted by the user in system Settings; needed to see what is playing. */
    val Context.notificationAccess: Boolean
        get() = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)

    /** Settings › Driving: the Bluetooth device whose connect runs the drive check; "" = off. */
    var Context.carAddress: String
        get() = prefs(this).getString(KEY_CAR_ADDRESS, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_CAR_ADDRESS, v).apply()

    var Context.carName: String
        get() = prefs(this).getString(KEY_CAR_NAME, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_CAR_NAME, v).apply()

    /** The wake time (unix s) the drive warning was already shown for; one warning per wake period. */
    var Context.driveWarnedWake: Long
        get() = prefs(this).getLong(KEY_DRIVE_WARNED, 0L)
        set(v) = prefs(this).edit().putLong(KEY_DRIVE_WARNED, v).apply()

    /** The three "Things to mark" names as the server last gave them: index 0 = slot 1, "" = unused. For the mark widget. */
    var Context.markNames: List<String>
        get() = (prefs(this).getString(KEY_MARK_NAMES, "") ?: "").split('\n').let { n -> List(3) { n.getOrElse(it) { "" } } }
        set(v) = prefs(this).edit().putString(KEY_MARK_NAMES, v.joinToString("\n")).apply()

    /** Minutes between background syncs (ring read + push); 0 = background sync off. */
    var Context.syncIntervalMin: Int
        get() = prefs(this).getInt(KEY_SYNC_INTERVAL, 5)
        set(v) = prefs(this).edit().putInt(KEY_SYNC_INTERVAL, v).apply()

    var Context.askedBattery: Boolean
        get() = prefs(this).getBoolean(KEY_ASKED_BATTERY, false)
        set(v) = prefs(this).edit().putBoolean(KEY_ASKED_BATTERY, v).apply()

    /** Last ring battery level (-1 unknown) and charging state; the two flags make each alert fire once. */
    var Context.lastBatteryPct: Int
        get() = prefs(this).getInt(KEY_BATTERY_PCT, -1)
        set(v) = prefs(this).edit().putInt(KEY_BATTERY_PCT, v).apply()

    var Context.lastCharging: Boolean
        get() = prefs(this).getBoolean(KEY_CHARGING, false)
        set(v) = prefs(this).edit().putBoolean(KEY_CHARGING, v).apply()

    var Context.batteryWarned: Boolean
        get() = prefs(this).getBoolean(KEY_BATTERY_WARNED, false)
        set(v) = prefs(this).edit().putBoolean(KEY_BATTERY_WARNED, v).apply()

    var Context.chargedTold: Boolean
        get() = prefs(this).getBoolean(KEY_CHARGED_TOLD, false)
        set(v) = prefs(this).edit().putBoolean(KEY_CHARGED_TOLD, v).apply()

    /** Settings > Notifications > Ring battery: the low and charged alerts. */
    var Context.batteryAlerts: Boolean
        get() = prefs(this).getBoolean(KEY_BATTERY_ALERTS, true)
        set(v) = prefs(this).edit().putBoolean(KEY_BATTERY_ALERTS, v).apply()

    /** Last /today body for the home-screen widget, and when it was fetched. */
    var Context.todayJson: String
        get() = prefs(this).getString(KEY_TODAY_JSON, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_TODAY_JSON, v).apply()

    var Context.todayJsonMs: Long
        get() = prefs(this).getLong(KEY_TODAY_JSON_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_TODAY_JSON_MS, v).apply()

    /** YEAR*1000+DAY_OF_YEAR of the last successful 7-day deep sync. */
    var Context.lastDeepSyncDay: Int
        get() = prefs(this).getInt(KEY_LAST_DEEP_DAY, 0)
        set(v) = prefs(this).edit().putInt(KEY_LAST_DEEP_DAY, v).apply()

    /** "Ring not synced for N days" posted for the current gap. */
    var Context.staleWarned: Boolean
        get() = prefs(this).getBoolean(KEY_STALE_WARNED, false)
        set(v) = prefs(this).edit().putBoolean(KEY_STALE_WARNED, v).apply()

    /** Breathe screen 4-7-8 rounds, 3..12. */
    var Context.breathRounds: Int
        get() = prefs(this).getInt(KEY_BREATH_ROUNDS, 5).coerceIn(3, 12)
        set(v) = prefs(this).edit().putInt(KEY_BREATH_ROUNDS, v.coerceIn(3, 12)).apply()

    /** Today's "Scores and vitals" row is unfolded; folded by default. */
    var Context.showNumbers: Boolean
        get() = prefs(this).getBoolean(KEY_SHOW_NUMBERS, false)
        set(v) = prefs(this).edit().putBoolean(KEY_SHOW_NUMBERS, v).apply()

    /** Journal date whose suggested entries got "Neither"; one date is enough, only last evening is ever suggested. */
    var Context.suggestDismissed: String
        get() = prefs(this).getString(KEY_SUGGEST_DISMISSED, "") ?: ""
        set(v) = prefs(this).edit().putString(KEY_SUGGEST_DISMISSED, v).apply()
}
