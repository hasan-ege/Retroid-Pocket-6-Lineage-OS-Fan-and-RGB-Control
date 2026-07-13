package com.example.handheldsettings.hardware

/**
 * Closed-loop temperature-target fan controller using a PI loop.
 * Maps output directly to our customFanLevel range (0.0..8.0).
 */
class FanTempController(private val minLevel: Double = 0.0) {
    var kp: Double = KP_DEFAULT
    var ki: Double = KI_DEFAULT

    private var integral: Double = 0.0

    /** Compute the fan level (0.0..8.0) to hold [currentTempC] at [targetTempC] */
    fun update(currentTempC: Double, targetTempC: Double, dtSec: Double): Double {
        if (currentTempC >= THERMAL_OVERRIDE_C) {
            integral = 0.0
            return 8.0 // Force max speed (level 8)
        }
        val error = currentTempC - targetTempC

        // Conditional integration (anti-windup)
        val candidateIntegral = integral + error * dtSec
        val provisional = minLevel + kp * error + ki * candidateIntegral
        val saturatingHigh = provisional > 8.0 && error > 0.0
        val saturatingLow = provisional < minLevel && error < 0.0
        if (!saturatingHigh && !saturatingLow) {
            integral = candidateIntegral
        }

        val out = minLevel + kp * error + ki * integral
        return out.coerceIn(minLevel, 8.0)
    }

    fun reset() {
        integral = 0.0
    }

    companion object {
        const val KP_DEFAULT = 0.32 // ~5°C over -> +1.6 fan levels (20%) immediately
        const val KI_DEFAULT = 0.012 // slowly trims out persistent error
        const val THERMAL_OVERRIDE_C = 85.0
    }
}
