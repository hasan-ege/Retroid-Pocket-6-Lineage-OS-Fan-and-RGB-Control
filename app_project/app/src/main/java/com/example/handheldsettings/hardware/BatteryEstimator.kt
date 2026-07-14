package com.example.handheldsettings.hardware

import android.util.Log

/**
 * Battery life estimator (RP6_Complete_Guide.md §14).
 *
 * Reads sysfs power_supply files using native Java I/O.
 * Caches which paths require root to avoid repeated SELinux denial spam.
 * Uses a moving average over 10 samples to smooth out instantaneous spikes.
 */
object BatteryEstimator {

    private const val TAG = "BatteryEstimator"

    private val drainAvg = MovingAverage(windowSize = 10)

    // Cache: paths that failed native read are marked here.
    // Once a path fails with native I/O, we only use Shell.cmd for it.
    // Once Shell.cmd also fails, we mark it as permanently failed to stop all retries.
    private val pathAccessMode = HashMap<String, AccessMode>(4)

    private enum class AccessMode {
        NATIVE,      // Try native first (default for new paths)
        ROOT_ONLY,   // Native failed, use root shell
        FAILED       // Both failed, don't retry
    }

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

    private fun readLong(path: String): Long? {
        val mode = pathAccessMode.getOrPut(path) { AccessMode.NATIVE }

        return when (mode) {
            AccessMode.NATIVE -> {
                try {
                    java.io.File(path).readText().trim().toLongOrNull()
                } catch (e: Exception) {
                    // Native read failed (likely SELinux denial) — switch to root
                    Log.i(TAG, "Native read failed for $path, switching to root-only")
                    pathAccessMode[path] = AccessMode.ROOT_ONLY
                    readViaRoot(path)
                }
            }
            AccessMode.ROOT_ONLY -> readViaRoot(path)
            AccessMode.FAILED -> null
        }
    }

    private fun readViaRoot(path: String): Long? {
        return try {
            com.topjohnwu.superuser.Shell.cmd("cat $path").exec()
                .out.firstOrNull()?.trim()?.toLongOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Root read also failed for $path, marking as permanently failed")
            pathAccessMode[path] = AccessMode.FAILED
            null
        }
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
