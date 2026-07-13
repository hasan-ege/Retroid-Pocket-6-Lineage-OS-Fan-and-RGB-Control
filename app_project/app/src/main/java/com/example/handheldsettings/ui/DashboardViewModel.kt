package com.example.handheldsettings.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.handheldsettings.data.CpuMode
import com.example.handheldsettings.data.FanMode
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.data.RgbMode
import com.example.handheldsettings.data.FanCurvePoint
import com.example.handheldsettings.data.FanCurveSerializer
import com.example.handheldsettings.hardware.BatteryEstimator
import com.example.handheldsettings.hardware.CpuModeController
import com.example.handheldsettings.hardware.FanController
import com.example.handheldsettings.hardware.FpsReader
import com.example.handheldsettings.hardware.RefreshRateController
import com.example.handheldsettings.service.SystemControlService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class DashboardState(
    val fanMode: FanMode = FanMode.SMART,
    val customFanLevel: Float = 4f,
    val cpuMode: CpuMode = CpuMode.BALANCED,
    val refreshRate: RefreshRateController.RefreshRate = RefreshRateController.RefreshRate.HZ_60,
    val rgbMode: RgbMode = RgbMode.OFF,
    val rgbR: Int = 255,
    val rgbG: Int = 100,
    val rgbB: Int = 0,
    val rgbBrightness: Int = 200,
    val batteryPct: Int = -1,
    val batteryMins: Int? = null,
    val fanCurState: Int = 0,
    val maxTempC: Double = 0.0,
    val kernelThermalControlEnabled: Boolean = true,
    val fanHoldTemp: Boolean = false,
    val fanTargetTemp: Int = 75,
    val overlayEnabled: Boolean = false,
    val autotdpEnabled: Boolean = false,
    val autotdpTargetFps: Int = 60,
    val fanCurveEnabled: Boolean = false,
    val fanCurvePoints: List<FanCurvePoint> = FanCurveSerializer.parse(null),
    // Live telemetry
    val cpuLiveInfo: List<CpuModeController.PolicyLiveInfo> = emptyList(),
    val currentFps: Float = 0f,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val context: Context get() = getApplication<Application>().applicationContext
    private val prefs: SharedPreferences =
        context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(DashboardState())
    val state: StateFlow<DashboardState> = _state.asStateFlow()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        loadFromPrefs()
    }

    init {
        loadFromPrefs()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        ensureServiceRunning()
        startPolling()
    }

    override fun onCleared() {
        super.onCleared()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun loadFromPrefs() {
        val fanMode = runCatching {
            FanMode.valueOf(prefs.getString(Prefs.FAN_MODE, FanMode.SMART.name)!!)
        }.getOrDefault(FanMode.SMART)
        val cpuMode = runCatching {
            CpuMode.valueOf(prefs.getString(Prefs.CPU_MODE, CpuMode.BALANCED.name)!!)
        }.getOrDefault(CpuMode.BALANCED)
        val rgbMode = runCatching {
            RgbMode.valueOf(prefs.getString(Prefs.RGB_MODE, RgbMode.OFF.name)!!)
        }.getOrDefault(RgbMode.OFF)

        _state.update {
            it.copy(
                fanMode       = fanMode,
                customFanLevel = prefs.getFloat(Prefs.FAN_CUSTOM, 4f),
                cpuMode       = cpuMode,
                rgbMode       = rgbMode,
                rgbR          = prefs.getInt(Prefs.RGB_COLOR_R, 255),
                rgbG          = prefs.getInt(Prefs.RGB_COLOR_G, 100),
                rgbB          = prefs.getInt(Prefs.RGB_COLOR_B, 0),
                rgbBrightness = prefs.getInt(Prefs.RGB_BRIGHTNESS, 200),
                kernelThermalControlEnabled = prefs.getBoolean(Prefs.KERNEL_THERMAL_CONTROL, true),
                fanHoldTemp   = prefs.getBoolean(Prefs.FAN_HOLD_TEMP, false),
                fanTargetTemp = prefs.getInt(Prefs.FAN_TARGET_TEMP, 75),
                overlayEnabled = prefs.getBoolean(Prefs.OVERLAY_ENABLED, false),
                autotdpEnabled = prefs.getBoolean(Prefs.AUTOTDP_ENABLED, false),
                autotdpTargetFps = prefs.getInt(Prefs.AUTOTDP_TARGET_FPS, 60),
                fanCurveEnabled = prefs.getBoolean(Prefs.FAN_CURVE_ENABLED, false),
                fanCurvePoints = FanCurveSerializer.parse(prefs.getString(Prefs.FAN_CURVE_POINTS, null)),
            )
        }
    }

    /** Poll live hardware data every 1 second for snappy UI feedback. */
    private fun startPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val fanCur = FanController.readCurState()
                val temp   = FanController.readMaxTempCelsius()
                val bPct   = BatteryEstimator.readCapacityPercent()
                val bMins  = BatteryEstimator.estimateRemainingMinutes()
                val rr     = RefreshRateController.current(context)
                val cpuInfo = CpuModeController.readLiveInfo()
                val fps = FpsReader.readFps()
                _state.update {
                    it.copy(
                        fanCurState = fanCur,
                        maxTempC    = temp,
                        batteryPct  = bPct,
                        batteryMins = bMins,
                        refreshRate = rr,
                        cpuLiveInfo = cpuInfo,
                        currentFps  = fps,
                    )
                }
                delay(1_000)
            }
        }
    }

    // ─────────────────────────────────────────────────────────────
    // User actions — write prefs then poke the service
    // ─────────────────────────────────────────────────────────────

    fun setKernelThermalControl(enabled: Boolean) {
        prefs.edit().putBoolean(Prefs.KERNEL_THERMAL_CONTROL, enabled).apply()
        _state.update { it.copy(kernelThermalControlEnabled = enabled) }
        ensureServiceRunning()
    }

    fun setFanMode(mode: FanMode) {
        prefs.edit().putString(Prefs.FAN_MODE, mode.name).apply()
        _state.update { it.copy(fanMode = mode) }
        ensureServiceRunning()
    }

    fun setCustomFanLevel(level: Float) {
        prefs.edit()
            .putString(Prefs.FAN_MODE, FanMode.CUSTOM.name)
            .putFloat(Prefs.FAN_CUSTOM, level)
            .apply()
        _state.update { it.copy(fanMode = FanMode.CUSTOM, customFanLevel = level) }
        ensureServiceRunning()
    }

    fun setCpuMode(mode: CpuMode) {
        prefs.edit().putString(Prefs.CPU_MODE, mode.name).apply()
        _state.update { it.copy(cpuMode = mode) }
        ensureServiceRunning()
    }

    fun setRefreshRate(rate: RefreshRateController.RefreshRate) {
        viewModelScope.launch(Dispatchers.IO) {
            RefreshRateController.set(context, rate)
        }
        _state.update { it.copy(refreshRate = rate) }
    }

    fun setRgbMode(mode: RgbMode) {
        prefs.edit().putString(Prefs.RGB_MODE, mode.name).apply()
        _state.update { it.copy(rgbMode = mode) }
        ensureServiceRunning()
    }

    fun setRgbColor(r: Int, g: Int, b: Int) {
        prefs.edit()
            .putInt(Prefs.RGB_COLOR_R, r)
            .putInt(Prefs.RGB_COLOR_G, g)
            .putInt(Prefs.RGB_COLOR_B, b)
            .apply()
        _state.update { it.copy(rgbR = r, rgbG = g, rgbB = b) }
    }

    fun setRgbBrightness(brightness: Int) {
        prefs.edit().putInt(Prefs.RGB_BRIGHTNESS, brightness).apply()
        _state.update { it.copy(rgbBrightness = brightness) }
    }

    fun setFanHoldTemp(enabled: Boolean) {
        prefs.edit().putBoolean(Prefs.FAN_HOLD_TEMP, enabled).apply()
        _state.update { it.copy(fanHoldTemp = enabled) }
        ensureServiceRunning()
    }

    fun setFanTargetTemp(temp: Int) {
        prefs.edit().putInt(Prefs.FAN_TARGET_TEMP, temp).apply()
        _state.update { it.copy(fanTargetTemp = temp) }
        ensureServiceRunning()
    }

    fun setOverlayEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Prefs.OVERLAY_ENABLED, enabled).apply()
        _state.update { it.copy(overlayEnabled = enabled) }
        ensureServiceRunning()
    }

    fun setAutoTdpEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Prefs.AUTOTDP_ENABLED, enabled).apply()
        _state.update { it.copy(autotdpEnabled = enabled) }
        ensureServiceRunning()
    }

    fun setAutoTdpTargetFps(fps: Int) {
        prefs.edit().putInt(Prefs.AUTOTDP_TARGET_FPS, fps).apply()
        _state.update { it.copy(autotdpTargetFps = fps) }
        ensureServiceRunning()
    }

    fun setFanCurveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(Prefs.FAN_CURVE_ENABLED, enabled).apply()
        _state.update { it.copy(fanCurveEnabled = enabled) }
        ensureServiceRunning()
    }

    fun setFanCurvePoints(points: List<FanCurvePoint>) {
        prefs.edit().putString(Prefs.FAN_CURVE_POINTS, FanCurveSerializer.serialize(points)).apply()
        _state.update { it.copy(fanCurvePoints = points) }
        ensureServiceRunning()
    }

    /** Update a single point in the curve by index. */
    fun updateFanCurvePoint(index: Int, tempC: Int, level: Int) {
        val current = _state.value.fanCurvePoints.toMutableList()
        if (index in current.indices) {
            current[index] = FanCurvePoint(tempC.coerceIn(20, 100), level.coerceIn(0, 8))
            setFanCurvePoints(current.sortedBy { it.tempC })
        }
    }

    fun addFanCurvePoint(tempC: Int, level: Int) {
        val current = _state.value.fanCurvePoints.toMutableList()
        current.add(FanCurvePoint(tempC.coerceIn(20, 100), level.coerceIn(0, 8)))
        setFanCurvePoints(current.sortedBy { it.tempC })
    }

    fun removeFanCurvePoint(index: Int) {
        val current = _state.value.fanCurvePoints.toMutableList()
        if (current.size > 2 && index in current.indices) {
            current.removeAt(index)
            setFanCurvePoints(current)
        }
    }

    private fun ensureServiceRunning() {
        SystemControlService.startOrUpdate(context)
    }
}
