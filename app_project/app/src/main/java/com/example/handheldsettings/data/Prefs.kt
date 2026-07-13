package com.example.handheldsettings.data

/** Shared SharedPreferences keys. Single source of truth. */
object Prefs {
    const val FILE = "handheld_settings"

    const val FAN_MODE       = "fan_mode"          // FanMode.name
    const val FAN_CUSTOM     = "fan_custom_level"  // Double 0.0-8.0
    const val CPU_MODE       = "cpu_mode"           // CpuMode.name
    const val RGB_MODE       = "rgb_mode"           // RgbMode.name
    const val RGB_COLOR_R    = "rgb_r"              // Int 0-255
    const val RGB_COLOR_G    = "rgb_g"
    const val RGB_COLOR_B    = "rgb_b"
    const val RGB_BRIGHTNESS = "rgb_brightness"     // Int 0-255
    const val SERVICE_ENABLED = "service_enabled"   // Boolean
    const val KERNEL_THERMAL_CONTROL = "kernel_thermal_control" // Boolean, default true
    const val FAN_HOLD_TEMP  = "fan_hold_temp"      // Boolean, default false
    const val FAN_TARGET_TEMP = "fan_target_temp"    // Int, default 75
    const val OVERLAY_ENABLED = "overlay_enabled"   // Boolean, default false
    const val OVERLAY_X       = "overlay_x"         // Int
    const val OVERLAY_Y       = "overlay_y"         // Int
    const val OVERLAY_LOCKED  = "overlay_locked"    // Boolean, default false
    const val AUTOTDP_ENABLED = "autotdp_enabled"   // Boolean, default false
    const val AUTOTDP_TARGET_FPS = "autotdp_target_fps" // Int, default 60
    const val FAN_CURVE_ENABLED = "fan_curve_enabled" // Boolean, default false
    const val FAN_CURVE_POINTS = "fan_curve_points" // String, serialized "temp:level,..." default "30:1,50:3,65:5,75:7,85:8"
}
