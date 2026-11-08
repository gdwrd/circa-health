package dev.ring.health

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val NAME = "ring_health"

    private const val KEY_RING_ADDRESS    = "ring_address"
    private const val KEY_VM_URL          = "vm_url"
    private const val KEY_API_TOKEN       = "api_token"
    private const val KEY_STEP_GOAL       = "step_goal"
    private const val KEY_SLEEP_NEED_H    = "sleep_need_h"
    private const val KEY_LAST_SYNC_MS    = "last_sync_ms"
    private const val KEY_LAST_HR_MS      = "last_hr_ms"
    private const val KEY_LAST_HRV_MS     = "last_hrv_ms"
    private const val KEY_LAST_SLEEP_MS   = "last_sleep_ms"
    private const val KEY_SLEEP_SCHEMA    = "sleep_schema"
    private const val KEY_SYNC_INTERVAL   = "sync_interval_min"
    private const val KEY_ASKED_BATTERY   = "asked_battery"
    private const val KEY_LAST_ACTIVITY_MS = "last_activity_ms"
    private const val KEY_LAST_STRESS_MS  = "last_stress_ms"
    private const val KEY_LAST_SPO2_MS    = "last_spo2_ms"

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

    var Context.lastSyncMs: Long
        get() = prefs(this).getLong(KEY_LAST_SYNC_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_SYNC_MS, v).apply()

    var Context.lastHrMs: Long
        get() = prefs(this).getLong(KEY_LAST_HR_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_HR_MS, v).apply()

    var Context.lastHrvMs: Long
        get() = prefs(this).getLong(KEY_LAST_HRV_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_HRV_MS, v).apply()

    var Context.lastSleepMs: Long
        get() = prefs(this).getLong(KEY_LAST_SLEEP_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_SLEEP_MS, v).apply()

    /** Minutes between background syncs (ring read + push); 0 = background sync off. */
    var Context.syncIntervalMin: Int
        get() = prefs(this).getInt(KEY_SYNC_INTERVAL, 5)
        set(v) = prefs(this).edit().putInt(KEY_SYNC_INTERVAL, v).apply()

    var Context.askedBattery: Boolean
        get() = prefs(this).getBoolean(KEY_ASKED_BATTERY, false)
        set(v) = prefs(this).edit().putBoolean(KEY_ASKED_BATTERY, v).apply()

    // 2 = sleep sessions pushed with awake/end/stage series (v1.1+); 3 = with complete multi-packet stages (v2.0.3).
    var Context.sleepSchema: Int
        get() = prefs(this).getInt(KEY_SLEEP_SCHEMA, 0)
        set(v) = prefs(this).edit().putInt(KEY_SLEEP_SCHEMA, v).apply()

    var Context.lastActivityMs: Long
        get() = prefs(this).getLong(KEY_LAST_ACTIVITY_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_ACTIVITY_MS, v).apply()

    var Context.lastStressMs: Long
        get() = prefs(this).getLong(KEY_LAST_STRESS_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_STRESS_MS, v).apply()

    var Context.lastSpo2Ms: Long
        get() = prefs(this).getLong(KEY_LAST_SPO2_MS, 0L)
        set(v) = prefs(this).edit().putLong(KEY_LAST_SPO2_MS, v).apply()
}
