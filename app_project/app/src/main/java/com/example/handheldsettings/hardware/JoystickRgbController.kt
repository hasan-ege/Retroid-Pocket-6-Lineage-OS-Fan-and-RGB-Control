package com.example.handheldsettings.hardware

import android.util.Log
import com.example.handheldsettings.data.Corner
import com.example.handheldsettings.data.Stick
import com.topjohnwu.superuser.Shell

/**
 * Joystick RGB LED controller (RP6_Complete_Guide.md §16).
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

    private val leftMap = mapOf(
        Corner.TOP_LEFT     to 0,
        Corner.BOTTOM_LEFT  to 1,
        Corner.BOTTOM_RIGHT to 2,
        Corner.TOP_RIGHT    to 3
    )

    private val rightMap = mapOf(
        Corner.BOTTOM_RIGHT to 0,
        Corner.TOP_RIGHT    to 1,
        Corner.TOP_LEFT     to 2,
        Corner.BOTTOM_LEFT  to 3
    )

    private fun ledPath(stick: Stick, corner: Corner): String {
        val prefix = if (stick == Stick.LEFT) "left" else "right"
        val index = (if (stick == Stick.LEFT) leftMap else rightMap)[corner]!!
        return "/sys/class/leds/$prefix:stick:$index"
    }

    /**
     * Sets a single LED. brightness is written separately so the caller can
     * batch-set brightness once outside an animation loop (see §16.7 perf note).
     */
    fun setColor(stick: Stick, corner: Corner, r: Int, g: Int, b: Int, brightness: Int = 255) {
        val base = ledPath(stick, corner)
        Shell.cmd(
            "echo \"$r $g $b\" > $base/multi_intensity",
            "echo $brightness > $base/brightness"
        ).exec()
    }

    /**
     * Sets all 8 LEDs to the same color in a single Shell.cmd() call (minimal IPC overhead).
     * brightness=0 turns LEDs off without changing their color.
     */
    fun setAll(r: Int, g: Int, b: Int, brightness: Int = 255) {
        val commands = buildList {
            for (stick in Stick.entries) {
                for (corner in Corner.entries) {
                    val base = ledPath(stick, corner)
                    add("echo \"$r $g $b\" > $base/multi_intensity")
                    add("echo $brightness > $base/brightness")
                }
            }
        }
        val result = Shell.cmd(*commands.toTypedArray()).exec()
        if (!result.isSuccess) Log.w(TAG, "setAll failed: ${result.err}")
    }

    /** Sets brightness only (no color change). Efficient for on/off toggling. */
    fun setBrightnessAll(brightness: Int) {
        val commands = buildList {
            for (stick in Stick.entries) {
                for (corner in Corner.entries) {
                    val base = ledPath(stick, corner)
                    add("echo $brightness > $base/brightness")
                }
            }
        }
        Shell.cmd(*commands.toTypedArray()).exec()
    }

    fun turnOff() = setBrightnessAll(0)
}
