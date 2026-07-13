package com.example.handheldsettings.hardware

import android.util.Log
import com.topjohnwu.superuser.Shell

/**
 * Fan hardware controller.
 *
 * Key spec from RP6_Complete_Guide.md section 1:
 * - Path discovered dynamically by scanning cooling_device type files for "pwm-fan".
 * - cur_state accepts integers 0-8.
 * - Kernel step_wise governor overrides ~1s after a write, so we write in a 300ms loop.
 * - SMART mode = do NOT write; let the kernel govern naturally.
 */
object FanController {

    private const val TAG = "FanController"
    private const val THERMAL_BASE = "/sys/class/thermal"

    /** Cached path, discovered once at first use. */
    @Volatile
    private var cachedPath: String? = null

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
     * Writes the target fan level to the local target file.
     * The background shell loop reads this file and writes it to sysfs every 50ms.
     */
    fun writeCurState(context: android.content.Context, value: Int) {
        val clamped = value.coerceIn(0, 8)
        val file = java.io.File(context.filesDir, "fan_target")
        try {
            val current = if (file.exists()) file.readText().trim() else ""
            if (current != clamped.toString()) {
                file.writeText(clamped.toString())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write target to file: ${e.message}")
        }
    }

    /** Sets the target state to SMART (Auto). */
    fun writeSmartMode(context: android.content.Context) {
        val file = java.io.File(context.filesDir, "fan_target")
        try {
            val current = if (file.exists()) file.readText().trim() else ""
            if (current != "SMART") {
                file.writeText("SMART")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write SMART to file: ${e.message}")
        }
    }

    /**
     * Reads the highest temperature across all thermal zones (in Celsius).
     * Used by the watchdog in SystemControlService.
     */
    fun readMaxTempCelsius(): Double {
        val script = """
            for z in $THERMAL_BASE/thermal_zone*; do
                t=${'$'}(cat "${'$'}z/type" 2>/dev/null)
                case "${'$'}t" in
                    *cpu*|*gpu*) cat "${'$'}z/temp" 2>/dev/null ;;
                esac
            done
        """.trimIndent()
        val result = Shell.cmd(script).exec()
        val maxMilliC = result.out
            .mapNotNull { it.trim().toLongOrNull() }
            .maxOrNull() ?: return 0.0
        return maxMilliC / 1000.0
    }
}
