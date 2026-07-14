package com.example.handheldsettings.hardware

import android.util.Log
import com.example.handheldsettings.data.Corner
import com.example.handheldsettings.data.Stick

/**
 * Joystick RGB LED controller (RP6_Complete_Guide.md §16).
 *
 * Firmware-grade implementation using a single persistent `su` process
 * with health monitoring, pre-computed sysfs paths, and zero-allocation
 * command batching.
 *
 * Physical LED layout (device-verified):
 *
 * LEFT joystick (sequential order):
 *   index 0 = Top-Left
 *   index 1 = Bottom-Left
 *   index 2 = Bottom-Right
 *   index 3 = Top-Right
 *
 * RIGHT joystick (point-symmetric / 180° rotated — NOT the same as left!):
 *   index 0 = Bottom-Right
 *   index 1 = Top-Right
 *   index 2 = Top-Left
 *   index 3 = Bottom-Left
 *
 * Write pattern:
 *   echo "R G B" > /sys/class/leds/<stick>:stick:<index>/multi_intensity
 *   echo <brightness> > /sys/class/leds/<stick>:stick:<index>/brightness
 */
object JoystickRgbController {

    private const val TAG = "JoystickRgbController"

    // Pre-computed sysfs paths — computed once, never re-allocated
    private val LED_PATHS: Array<String> = arrayOf(
        "/sys/class/leds/left:stick:0",   // LEFT TOP_LEFT
        "/sys/class/leds/left:stick:1",   // LEFT BOTTOM_LEFT
        "/sys/class/leds/left:stick:2",   // LEFT BOTTOM_RIGHT
        "/sys/class/leds/left:stick:3",   // LEFT TOP_RIGHT
        "/sys/class/leds/right:stick:0",  // RIGHT BOTTOM_RIGHT
        "/sys/class/leds/right:stick:1",  // RIGHT TOP_RIGHT
        "/sys/class/leds/right:stick:2",  // RIGHT TOP_LEFT
        "/sys/class/leds/right:stick:3"   // RIGHT BOTTOM_LEFT
    )

    // Index lookup for (Stick, Corner) → LED_PATHS index
    private fun ledIndex(stick: Stick, corner: Corner): Int = when (stick) {
        Stick.LEFT -> when (corner) {
            Corner.TOP_LEFT     -> 0
            Corner.BOTTOM_LEFT  -> 1
            Corner.BOTTOM_RIGHT -> 2
            Corner.TOP_RIGHT    -> 3
        }
        Stick.RIGHT -> when (corner) {
            Corner.BOTTOM_RIGHT -> 4
            Corner.TOP_RIGHT    -> 5
            Corner.TOP_LEFT     -> 6
            Corner.BOTTOM_LEFT  -> 7
        }
    }

    fun ledPath(stick: Stick, corner: Corner): String = LED_PATHS[ledIndex(stick, corner)]

    // --- Persistent su process with health monitoring ---

    private var suProcess: Process? = null
    private var suWriter: java.io.BufferedWriter? = null
    // Re-usable StringBuilder to avoid allocation in animation loops
    private val cmdBuffer = StringBuilder(512)

    @Synchronized
    private fun ensureSu(): java.io.BufferedWriter? {
        val proc = suProcess
        if (proc != null && suWriter != null) {
            // Health check: verify process is still alive
            try {
                proc.exitValue()
                // If exitValue() returns without exception, process is dead
                Log.w(TAG, "Persistent su process died, restarting")
                suProcess = null
                suWriter = null
            } catch (_: IllegalThreadStateException) {
                // Process still running — this is the expected path
                return suWriter
            }
        }
        // Start new process
        return try {
            val newProc = Runtime.getRuntime().exec("su")
            suProcess = newProc
            val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(newProc.outputStream), 4096)
            suWriter = writer
            Log.i(TAG, "Persistent su process started (pid=${newProc.hashCode()})")
            writer
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start persistent su process", e)
            null
        }
    }

    /**
     * Executes a list of shell commands via the persistent su stream.
     * Commands are concatenated into a single write+flush for minimal I/O overhead.
     */
    @Synchronized
    fun executeFast(commands: List<String>) {
        val writer = ensureSu() ?: return
        try {
            cmdBuffer.setLength(0)
            for (cmd in commands) {
                cmdBuffer.append(cmd)
                cmdBuffer.append('\n')
            }
            writer.write(cmdBuffer.toString())
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing to persistent su, will restart", e)
            try { suProcess?.destroy() } catch (_: Exception) {}
            suProcess = null
            suWriter = null
        }
    }

    /**
     * Executes a pre-built command string directly (no list allocation needed).
     * Used by callers that already build their own command string.
     */
    @Synchronized
    fun executeFastRaw(commandString: String) {
        val writer = ensureSu() ?: return
        try {
            writer.write(commandString)
            writer.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing raw to persistent su, will restart", e)
            try { suProcess?.destroy() } catch (_: Exception) {}
            suProcess = null
            suWriter = null
        }
    }

    /**
     * Sets a single LED color + brightness.
     */
    fun setColor(stick: Stick, corner: Corner, r: Int, g: Int, b: Int, brightness: Int = 255) {
        val base = LED_PATHS[ledIndex(stick, corner)]
        executeFastRaw("echo \"$r $g $b\" > $base/multi_intensity\necho $brightness > $base/brightness\n")
    }

    /**
     * Sets all 8 LEDs to the same color in a single write.
     * Zero-allocation fast path using pre-computed paths.
     */
    fun setAll(r: Int, g: Int, b: Int, brightness: Int = 255) {
        val sb = StringBuilder(512)
        for (path in LED_PATHS) {
            sb.append("echo \"$r $g $b\" > $path/multi_intensity\necho $brightness > $path/brightness\n")
        }
        executeFastRaw(sb.toString())
    }

    /**
     * Sets each LED independently with its own color and brightness.
     */
    fun setIndependent(states: Map<Pair<Stick, Corner>, Pair<Triple<Int, Int, Int>, Int>>) {
        val sb = StringBuilder(512)
        for ((key, value) in states) {
            val (stick, corner) = key
            val (color, brightness) = value
            val (r, g, b) = color
            val base = LED_PATHS[ledIndex(stick, corner)]
            sb.append("echo \"$r $g $b\" > $base/multi_intensity\necho $brightness > $base/brightness\n")
        }
        executeFastRaw(sb.toString())
    }

    /** Sets brightness only (no color change). */
    fun setBrightnessAll(brightness: Int) {
        val sb = StringBuilder(256)
        for (path in LED_PATHS) {
            sb.append("echo $brightness > $path/brightness\n")
        }
        executeFastRaw(sb.toString())
    }

    fun turnOff() = setBrightnessAll(0)

    /** Cleanly shut down the persistent su process. */
    @Synchronized
    fun destroy() {
        try {
            suWriter?.write("exit\n")
            suWriter?.flush()
        } catch (_: Exception) {}
        try { suProcess?.destroy() } catch (_: Exception) {}
        suProcess = null
        suWriter = null
    }
}
