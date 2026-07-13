package com.example.handheldsettings.data

/**
 * Fan operating modes.
 *
 * targetState:
 *  -1 = SMART: stop writing, let kernel step_wise governor run naturally
 *  -2 = CUSTOM: read value from SharedPreferences key PREF_FAN_CUSTOM_LEVEL
 *   0..8 = explicit cur_state value
 *
 * Values QUIET=2 / SPORT=5 / MAX=8 are from RP6_Complete_Guide.md §5.
 * Calibrate with real device if needed (§8.1).
 */
enum class FanMode(val targetState: Int, val label: String) {
    OFF(0, "Off"),
    QUIET(2, "Quiet"),
    SMART(-1, "Smart"),
    SPORT(5, "Sport"),
    MAX(8, "Max"),
    CUSTOM(-2, "Custom");

    /** Returns the next mode for QS-tile cycling (skips CUSTOM). */
    fun nextTileMode(): FanMode = when (this) {
        OFF   -> QUIET
        QUIET -> SMART
        SMART -> SPORT
        SPORT -> MAX
        MAX   -> OFF
        CUSTOM -> OFF
    }
}
