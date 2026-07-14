package com.example.handheldsettings.data

/** Joystick LED physical corner positions. */
enum class Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/** Which joystick stick. */
enum class Stick { LEFT, RIGHT }

/** RGB Lighting mode. */
enum class RgbMode(val label: String) {
    OFF("Off"),
    STATIC("Static"),
    RAINBOW("Rainbow Cycle"),
    BREATHE("Breathing"),
    AMBILIGHT("Ambilight"),
    BATTERY("Battery Level"),
    THERMAL("SoC Temperature"),
    WAVE("Rainbow Wave"),
    COLOR_CYCLE("Color Cycle"),
    STROBE("Strobe"),
    METEOR("Meteor"),
    FIRE("Fire"),
    AURORA("Aurora"),
    OCEAN("Ocean Wave"),
    POLICE("Police"),
    STARLIGHT("Starlight"),
    MUSIC("Music Reactive");

    fun next(): RgbMode = entries[(ordinal + 1) % entries.size]
}
