package com.example.handheldsettings.data

/**
 * A single control point on the user-editable fan curve:
 * at [tempC]°C, run the fan at [level] (0..8).
 */
data class FanCurvePoint(val tempC: Int, val level: Int) {
    init {
        require(tempC in 20..100) { "tempC must be in 20..100" }
        require(level in 0..8) { "level must be in 0..8" }
    }
}

object FanCurveSerializer {
    const val DEFAULT_POINTS = "30:1,50:3,65:5,75:7,85:8"

    fun serialize(points: List<FanCurvePoint>): String =
        points.sortedBy { it.tempC }.joinToString(",") { "${it.tempC}:${it.level}" }

    fun parse(s: String?): List<FanCurvePoint> {
        if (s.isNullOrBlank()) return parse(DEFAULT_POINTS)
        val pts = s.split(",").mapNotNull { entry ->
            val parts = entry.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
            val tc = parts[0].trim().toIntOrNull()?.coerceIn(20, 100) ?: return@mapNotNull null
            val lv = parts[1].trim().toIntOrNull()?.coerceIn(0, 8) ?: return@mapNotNull null
            FanCurvePoint(tc, lv)
        }
        return if (pts.isEmpty()) parse(DEFAULT_POINTS) else pts.sortedBy { it.tempC }
    }

    /**
     * Interpolate fan level for a given temperature against the curve points.
     * Returns fractional level for smooth dithering.
     */
    fun interpolate(tempC: Double, points: List<FanCurvePoint>): Double {
        if (points.isEmpty()) return 4.0
        val sorted = points.sortedBy { it.tempC }
        if (tempC <= sorted.first().tempC) return sorted.first().level.toDouble()
        if (tempC >= sorted.last().tempC) return sorted.last().level.toDouble()
        for (i in 0 until sorted.size - 1) {
            val p1 = sorted[i]
            val p2 = sorted[i + 1]
            if (tempC >= p1.tempC && tempC <= p2.tempC) {
                val ratio = (tempC - p1.tempC) / (p2.tempC - p1.tempC)
                return p1.level + ratio * (p2.level - p1.level)
            }
        }
        return 4.0
    }
}
