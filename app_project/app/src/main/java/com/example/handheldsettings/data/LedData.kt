package com.example.handheldsettings.data

/** Joystick LED physical corner positions. */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Which joystick stick. */
enum class Stick { LEFT, RIGHT }

/** RGB Lighting mode. */
enum class RgbMode(val label: String) {
    OFF("Off"),
    STATIC("Static"),
    RAINBOW("Rainbow"),
    BREATHE("Pulse/Breathe"),
    AMBILIGHT("Ambilight"),
    BATTERY("Battery Level"),
    THERMAL("SoC Temperature")
}
