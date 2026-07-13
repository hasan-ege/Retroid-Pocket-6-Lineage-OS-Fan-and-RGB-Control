package com.example.handheldsettings.data

/** CPU performance modes. */
enum class CpuMode(val label: String) {
    POWER_SAVING("Power Save"),
    BALANCED("Balanced"),
    ULTRA("Ultra"),
    ADAPTIVE("Adaptive");

    fun next(): CpuMode = entries[(ordinal + 1) % entries.size]
}
