package com.example.handheldsettings.hardware

import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Fan hardware controller.
 *
 * Key spec from RP6_Complete_Guide.md section 1:
 * - Path discovered dynamically by scanning cooling_device type files for "pwm-fan".
 * - cur_state accepts integers 0-8.
 * - When kernel thermal control is disabled, we write directly to sysfs.
 * - SMART mode = let the kernel govern naturally (we stop writing).
 */
object FanController {

    private const val TAG = "FanController"
    private const val THERMAL_BASE = "/sys/class/thermal"

    /** Cached path, discovered once at first use. */
    @Volatile
    private var cachedPath: String? = null

    /** Caches the last written target state to avoid duplicate sysfs writes. */
    @Volatile
    private var lastTarget: Int? = null

    /**
     * Dynamically discovers the cooling device path for the pwm-fan.
     * Returns null if no device is found or root is unavailable.
     */
    fun discoverFanPath(): String? {
        cachedPath?.let { return it }

        // POSIX sh loop: iterate cooling_device dirs, read their type, match pwm-fan
        val script = buildString {
            append("for d in $THERMAL_BASE/cooling_device*; do ")
            append("t=\$(cat \"\$d/type\" 2>/dev/null); ")
            append("if [ \"\$t\" = \"pwm-fan\" ]; then echo \"\$d\"; break; fi; ")
            append("done")
        }
        val result = Shell.cmd(script).exec()

        val path = result.out.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (path != null) {
            Log.i(TAG, "Found pwm-fan at: $path")
            cachedPath = path
        } else {
            Log.w(TAG, "pwm-fan cooling device not found! out=${result.out}")
        }
        return path
    }

    /** Reads the maximum fan state (usually 8 on RP6). */
    fun readMaxState(): Int {
        val path = discoverFanPath() ?: return 8
        return Shell.cmd("cat $path/max_state").exec()
            .out.firstOrNull()?.trim()?.toIntOrNull() ?: 8
    }

    /** Reads the current fan state as reported by the kernel. */
    fun readCurState(): Int {
        val path = discoverFanPath() ?: return 0
        return Shell.cmd("cat $path/cur_state").exec()
            .out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
    }

    /**
     * Writes the target fan level directly to sysfs.
     * Caches the value to avoid redundant writes.
     */
    fun writeCurState(context: android.content.Context, value: Int) {
        val clamped = value.coerceIn(0, 8)
        if (lastTarget != clamped) {
            val path = discoverFanPath() ?: return
            Shell.cmd("echo $clamped > $path/cur_state 2>/dev/null").exec()
            lastTarget = clamped
        }
    }

    /** Sets the target state to SMART (Auto). Resets the cache so custom curves work on re-enable. */
    fun writeSmartMode(context: android.content.Context) {
        lastTarget = null
    }

    private var cachedThermalZones: List<java.io.File>? = null

    /**
     * Reads the highest temperature across all thermal zones (in Celsius).
     * Used by the watchdog in SystemControlService.
     */
    fun readMaxTempCelsius(): Double {
        if (cachedThermalZones == null) {
            val baseDir = java.io.File(THERMAL_BASE)
            val zones = mutableListOf<java.io.File>()
            if (baseDir.exists() && baseDir.isDirectory) {
                baseDir.listFiles()?.forEach { dir ->
                    if (dir.name.startsWith("thermal_zone")) {
                        try {
                            val type = java.io.File(dir, "type").readText().trim()
                            if (type.contains("cpu") || type.contains("gpu")) {
                                val tempFile = java.io.File(dir, "temp")
                                if (tempFile.exists()) {
                                    zones.add(tempFile)
                                }
                            }
                        } catch (e: Exception) {
                            // Ignore unreadable zones
                        }
                    }
                }
            }
            cachedThermalZones = zones
        }

        var maxMilliC = 0.0
        cachedThermalZones?.forEach { file ->
            try {
                val temp = file.readText().trim().toDoubleOrNull()
                if (temp != null && temp > maxMilliC) {
                    maxMilliC = temp
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return maxMilliC / 1000.0
    }
}
