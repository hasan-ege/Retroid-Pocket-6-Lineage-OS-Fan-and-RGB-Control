package com.example.handheldsettings.hardware

/**
 * PWM dithering for fractional fan targets (RP6_Complete_Guide.md §6.8).
 *
 * Because cur_state only accepts integers 0-8, a fractional target like 4.3
 * is approximated by writing 5 for 30% of ticks and 4 for 70% of ticks.
 * The fan's physical inertia smooths out the rapid switching.
 *
 * Only used in CUSTOM mode where the user picks a fine-grained slider value.
 */
class FractionalStateDither(target: Double) {

    private val low  = kotlin.math.floor(target).toInt().coerceIn(0, 8)
    private val high = (low + 1).coerceAtMost(8)
    private val frac = (target - low).coerceIn(0.0, 1.0)
    private var accumulator = 0.0

    /** Call every 300ms tick; returns which integer to write to cur_state. */
    fun nextLevel(): Int {
        if (high == low) return low   // target is already an exact integer
        accumulator += frac
        return if (accumulator >= 1.0) {
            accumulator -= 1.0
            high
        } else {
            low
        }
    }
}
