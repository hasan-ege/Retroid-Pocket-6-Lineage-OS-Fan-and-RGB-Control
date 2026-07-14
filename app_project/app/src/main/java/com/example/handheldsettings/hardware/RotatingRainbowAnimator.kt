package com.example.handheldsettings.hardware

import com.example.handheldsettings.data.Corner
import com.example.handheldsettings.data.Stick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Rotating rainbow animation across all 8 joystick LEDs (RP6_Complete_Guide.md §16.4, §16.7).
 *
 * Each corner has a fixed 90° phase offset (clock-wise spatial layout):
 *   TOP_LEFT=0°, TOP_RIGHT=90°, BOTTOM_RIGHT=180°, BOTTOM_LEFT=270°
 *
 * cornerHue = (baseHue + phaseOffset) % 360
 * Clockwise visual rotation: baseHue DECREASES over time.
 * (baseHue += speed → counter-clockwise; baseHue -= speed → clockwise)
 *
 * Uses JoystickRgbController.executeFastRaw() with pre-computed paths
 * and a reusable StringBuilder for zero-allocation animation frames.
 */
class RotatingRainbowAnimator(
    private val scope: CoroutineScope,
    private val speedDegPerTick: Float = 4f,   // hue change per tick
    private val tickIntervalMs: Long = 50L,    // ~20 FPS
    private val brightness: Int = 200
) {
    private var job: Job? = null
    private var hue = 0f

    private val cornerPhase = mapOf(
        Corner.TOP_LEFT     to 0f,
        Corner.TOP_RIGHT    to 90f,
        Corner.BOTTOM_RIGHT to 180f,
        Corner.BOTTOM_LEFT  to 270f
    )

    // Pre-computed LED sysfs paths for both sticks × all corners
    private data class LedEntry(val path: String, val corner: Corner)
    private val ledEntries: List<LedEntry> = buildList {
        for (stick in Stick.entries) {
            for (corner in Corner.entries) {
                add(LedEntry(JoystickRgbController.ledPath(stick, corner), corner))
            }
        }
    }

    // Reusable buffer — avoids heap allocation every frame
    private val frameBuffer = StringBuilder(512)

    fun start() {
        stop()
        // Set brightness once before the loop
        JoystickRgbController.setBrightnessAll(brightness)

        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                frameBuffer.setLength(0)
                for (entry in ledEntries) {
                    val cornerHue = (hue + (cornerPhase[entry.corner] ?: 0f) + 360f) % 360f
                    val (r, g, b) = hsvToRgb(cornerHue)
                    frameBuffer.append("echo \"$r $g $b\" > ${entry.path}/multi_intensity\n")
                }
                JoystickRgbController.executeFastRaw(frameBuffer.toString())

                // Clockwise rotation: decrease hue
                hue = (hue - speedDegPerTick + 360f) % 360f
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun isRunning() = job?.isActive == true
}

/** HSV → RGB conversion per RP6_Complete_Guide.md §16.4. hue: 0-360. */
fun hsvToRgb(hue: Float, sat: Float = 1f, value: Float = 1f): Triple<Int, Int, Int> {
    val c = value * sat
    val x = c * (1f - kotlin.math.abs((hue / 60f) % 2f - 1f))
    val m = value - c
    val (r1, g1, b1) = when {
        hue < 60f  -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else       -> Triple(c, 0f, x)
    }
    return Triple(
        ((r1 + m) * 255).toInt().coerceIn(0, 255),
        ((g1 + m) * 255).toInt().coerceIn(0, 255),
        ((b1 + m) * 255).toInt().coerceIn(0, 255)
    )
}
