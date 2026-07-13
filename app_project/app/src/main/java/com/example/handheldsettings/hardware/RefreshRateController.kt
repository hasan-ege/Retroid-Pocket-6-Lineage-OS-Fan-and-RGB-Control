package com.example.handheldsettings.hardware

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Controls display refresh rate via Settings.System (RP6_Complete_Guide.md §11).
 *
 * Requires WRITE_SECURE_SETTINGS, which HandheldSettingsApp self-grants via root on first launch.
 */
object RefreshRateController {

    private const val TAG = "RefreshRateController"

    enum class RefreshRate(val hz: Float, val label: String) {
        HZ_60(60f, "60 Hz"),
        HZ_90(90f, "90 Hz"),
        HZ_120(120f, "120 Hz");

        fun next(): RefreshRate = entries[(ordinal + 1) % entries.size]
    }

    fun set(context: Context, rate: RefreshRate) {
        val hz = rate.hz
        com.topjohnwu.superuser.Shell.cmd(
            "settings put system min_refresh_rate $hz",
            "settings put system peak_refresh_rate $hz"
        ).submit()
        Log.i(TAG, "Refresh rate set to $hz Hz via root shell")
    }

    fun current(context: Context): RefreshRate {
        val result = com.topjohnwu.superuser.Shell.cmd("settings get system peak_refresh_rate").exec()
        val out = result.out.firstOrNull()?.trim()
        val hz = out?.toFloatOrNull() ?: 60f
        return RefreshRate.entries.minByOrNull { kotlin.math.abs(it.hz - hz) } ?: RefreshRate.HZ_60
    }
}
