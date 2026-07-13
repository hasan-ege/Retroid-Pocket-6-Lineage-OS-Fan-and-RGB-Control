package com.example.handheldsettings.hardware

import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Battery life estimator (RP6_Complete_Guide.md §14).
 *
 * Reads sysfs power_supply files (no root required, but we use Shell for consistency).
 * Uses a moving average over 10 samples to smooth out instantaneous spikes.
 */
object BatteryEstimator {

    private const val TAG = "BatteryEstimator"

    private val drainAvg = MovingAverage(windowSize = 10)

    /**
     * Returns estimated remaining minutes, or null if:
     * - Device is charging (current_now >= 0)
     * - Files not found / unparseable
     */
    fun estimateRemainingMinutes(): Int? {
        val chargeNowUah = readLong("/sys/class/power_supply/battery/charge_counter")
            ?: readLong("/sys/class/power_supply/battery/charge_now") // fallback
            ?: return null
        val currentNowUa = readLong("/sys/class/power_supply/battery/current_now") ?: return null

        if (currentNowUa >= 0) return null // charging or idle

        val drainRateUa = drainAvg.add(kotlin.math.abs(currentNowUa))
        if (drainRateUa == 0L) return null

        val hours = chargeNowUah.toDouble() / drainRateUa.toDouble()
        return (hours * 60).toInt().takeIf { it > 0 }
    }

    fun readCapacityPercent(): Int {
        return readLong("/sys/class/power_supply/battery/capacity")?.toInt() ?: -1
    }

    private fun readLong(path: String): Long? =
        try {
            java.io.File(path).readText().trim().toLongOrNull()
        } catch (e: Exception) {
            // Fall back to Shell if direct read fails (permission issues on some configs)
            Shell.cmd("cat $path").exec().out.firstOrNull()?.trim()?.toLongOrNull()
        }
}

/** Sliding window moving average. */
class MovingAverage(private val windowSize: Int = 10) {
    private val samples = ArrayDeque<Long>()

    fun add(value: Long): Long {
        samples.addLast(value)
        if (samples.size > windowSize) samples.removeFirst()
        return samples.average().toLong()
    }
}
