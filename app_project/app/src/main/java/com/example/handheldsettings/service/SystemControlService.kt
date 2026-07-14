package com.example.handheldsettings.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.handheldsettings.MainActivity
import com.example.handheldsettings.data.CpuMode
import com.example.handheldsettings.data.FanMode
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.data.RgbMode
import com.example.handheldsettings.data.FanCurvePoint
import com.example.handheldsettings.data.FanCurveSerializer
import com.example.handheldsettings.data.Stick
import com.example.handheldsettings.data.Corner
import com.example.handheldsettings.hardware.BatteryEstimator
import com.example.handheldsettings.hardware.CpuModeController
import com.example.handheldsettings.hardware.FanController
import com.example.handheldsettings.hardware.FractionalStateDither
import com.example.handheldsettings.hardware.JoystickRgbController
import com.example.handheldsettings.hardware.RotatingRainbowAnimator
import com.example.handheldsettings.hardware.hsvToRgb
import com.example.handheldsettings.hardware.FanTempController
import com.example.handheldsettings.hardware.FpsReader
import com.example.handheldsettings.overlay.PerformanceOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.yield

/**
 * Unified background foreground service (RP6_Complete_Guide.md §6.3, §14.3).
 *
 * Runs three coroutines on Dispatchers.IO:
 *  1. Fan loop   — 300ms interval, writes cur_state (overrides kernel governor)
 *  2. Battery    — 5s interval, updates notification
 *  3. CPU mode   — one-shot on preference change
 *
 * Plus manages the RGB animator (starts/stops based on RgbMode preference).
 *
 * The watchdog is embedded in the fan loop: if temp > 75°C and mode is OFF/QUIET,
 * it temporarily forces a higher state and warns the user via the notification.
 *
 * All hardware writes use the persistent libsu Shell opened in HandheldSettingsApp.
 */
class SystemControlService : Service() {

    companion object {
        private const val TAG = "SystemControlService"
        private const val NOTIF_CHANNEL = "handheld_control"
        private const val NOTIF_ID = 1

        const val ACTION_UPDATE = "com.example.handheldsettings.UPDATE"
        const val ACTION_SET_PROJECTION_INTENT = "com.example.handheldsettings.SET_PROJECTION_INTENT"
        const val EXTRA_PROJECTION_INTENT = "projection_intent"

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, SystemControlService::class.java)
                .setAction(ACTION_UPDATE)
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fanJob: Job? = null
    private var batteryJob: Job? = null
    private var autoTdpJob: Job? = null
    
    private var mediaProjectionIntent: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    
    private var rainbowAnimator: RotatingRainbowAnimator? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var notifManager: NotificationManager

    // State mirrored from prefs for the notification
    @Volatile private var currentFanMode = FanMode.SMART
    @Volatile private var customFanLevel = 0.0
    @Volatile private var fanHoldTemp = false
    @Volatile private var fanTargetTemp = 75
    @Volatile private var fanCurveEnabled = false
    @Volatile private var fanCurvePoints: List<FanCurvePoint> = FanCurveSerializer.parse(null)
    @Volatile private var currentCpuMode = CpuMode.BALANCED
    @Volatile private var batteryMins: Int? = null
    @Volatile private var batteryPct: Int = -1
    @Volatile private var watchdogActive = false
    @Volatile private var overlayEnabled = false
    @Volatile private var autoTdpEnabled = false
    @Volatile private var autoTdpTargetFps = 60
    @Volatile private var kernelThermalControlEnabled = true
    private var performanceOverlay: PerformanceOverlay? = null
    private var isScreenOff = false
    // Dirty flag: only rebuild notification when content actually changes
    @Volatile private var lastNotifText: String = ""
    // Pre-allocated animation buffer to avoid per-frame heap allocation
    private val animBuffer = StringBuilder(512)
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    isScreenOff = true
                    Log.i(TAG, "Screen OFF -> Suspending fan + RGB")
                    // Suspend RGB animations to save power & reduce heat
                    rgbInfoJob?.cancel()
                    rgbInfoJob = null
                    rainbowAnimator?.stop()
                    JoystickRgbController.turnOff()
                }
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOff = false
                    Log.i(TAG, "Screen ON -> Resuming fan + RGB")
                    // Resume RGB animation
                    applyRgb()
                }
            }
        }
    }
    @Volatile private var autoTdpPct = 1.0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Prefs.FAN_MODE, Prefs.FAN_CUSTOM, Prefs.FAN_HOLD_TEMP, Prefs.FAN_TARGET_TEMP,
            Prefs.FAN_CURVE_ENABLED, Prefs.FAN_CURVE_POINTS -> loadFanPrefs()
            Prefs.CPU_MODE -> {
                loadCpuPrefs()
                if (!autoTdpEnabled) {
                    serviceScope.launch { CpuModeController.apply(currentCpuMode) }
                }
            }
            Prefs.RGB_MODE, Prefs.RGB_COLOR_R, Prefs.RGB_COLOR_G,
            Prefs.RGB_COLOR_B, Prefs.RGB_BRIGHTNESS -> applyRgb()
            Prefs.KERNEL_THERMAL_CONTROL -> {
                kernelThermalControlEnabled = prefs.getBoolean(Prefs.KERNEL_THERMAL_CONTROL, true)
                applyKernelThermalControl(kernelThermalControlEnabled)
            }
            Prefs.OVERLAY_ENABLED -> {
                loadOverlayPref()
                applyOverlayState()
            }
            Prefs.AUTOTDP_ENABLED, Prefs.AUTOTDP_TARGET_FPS -> {
                loadAutoTdpPrefs()
                applyAutoTdpState()
            }
        }
        updateNotification()
    }

    // -----------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
        notifManager = getSystemService(NotificationManager::class.java)

        createNotificationChannel()
        loadAllPrefs()

        startForeground(NOTIF_ID, buildNotification())
        startLoops()
        applyRgb()
        applyOverlayState()
        applyAutoTdpState()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screenReceiver, filter)

        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        Log.i(TAG, "SystemControlService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SET_PROJECTION_INTENT) {
            mediaProjectionIntent = intent.getParcelableExtra(EXTRA_PROJECTION_INTENT)
            Log.i(TAG, "Received MediaProjection intent token, restarting RGB...")
            applyRgb()
            return START_STICKY
        }
        
        // Re-apply everything on each start (e.g. after boot)
        loadAllPrefs()
        updateNotification()
        return START_STICKY  // restart automatically if killed
    }

    override fun onDestroy() {
        super.onDestroy()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        rainbowAnimator?.stop()
        performanceOverlay?.hide()
        performanceOverlay = null
        autoTdpJob?.cancel()
        autoTdpJob = null
        try { unregisterReceiver(screenReceiver) } catch(e: Exception) {}
        serviceScope.cancel()
        
        // Re-enable kernel thermal control for system safety on shutdown
        applyKernelThermalControl(true)

        // Clean up persistent su process
        JoystickRgbController.destroy()
        
        Log.i(TAG, "SystemControlService destroyed")
    }

    private var lastThermalStateApplied: Boolean? = null

    private fun applyKernelThermalControl(enabled: Boolean) {
        if (enabled == lastThermalStateApplied) return
        lastThermalStateApplied = enabled
        val mode = if (enabled) "enabled" else "disabled"
        val script = """
            for z in 14 15 33 34 53; do
                echo $mode > /sys/class/thermal/thermal_zone${'$'}z/mode 2>/dev/null
            done
        """.trimIndent()
        com.topjohnwu.superuser.Shell.cmd(script).submit()
        Log.i(TAG, "Applied kernel thermal control: $mode")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------------------------------------
    // Coroutine loops
    // -----------------------------------------------------------

    private fun startLoops() {
        startFanLoop()
        startBatteryLoop()
    }

    private fun getCurveFanLevel(tempC: Double, points: List<FanCurvePoint>): Double {
        return FanCurveSerializer.interpolate(tempC, points)
    }

    private fun startFanLoop() {
        fanJob?.cancel()
        fanJob = serviceScope.launch {
            var dither: FractionalStateDither? = null
            var lastDitherTarget = -99.0
            val tempController = FanTempController(minLevel = 2.0)

            while (isActive) {
                val mode = currentFanMode
                val tempC = FanController.readMaxTempCelsius()

                val screenOffOverride = isScreenOff && tempC < 70.0

                if (screenOffOverride) {
                    applyKernelThermalControl(false)
                    FanController.writeCurState(applicationContext, 0)
                    tempController.reset()
                    watchdogActive = false
                } else if (kernelThermalControlEnabled || (mode == FanMode.SMART && tempC <= 75.0)) {
                    applyKernelThermalControl(true)
                    FanController.writeSmartMode(applicationContext)
                    tempController.reset()
                    watchdogActive = false
                } else {
                    applyKernelThermalControl(false)
                    // Watchdog: if temp > 75°C and mode is too low, force fan up
                    val watchdogOverride = tempC > 75.0 && mode.targetState in 0..3
                    watchdogActive = watchdogOverride

                    val target: Int = when {
                            watchdogOverride -> 6   // force high during overheat
                            mode == FanMode.CUSTOM -> {
                                val lvl = when {
                                    fanHoldTemp -> tempController.update(tempC, fanTargetTemp.toDouble(), 0.15)
                                    fanCurveEnabled -> getCurveFanLevel(tempC, fanCurvePoints)
                                    else -> {
                                        tempController.reset()
                                        customFanLevel
                                    }
                                }
                                if (lvl != lastDitherTarget) {
                                    dither = FractionalStateDither(lvl)
                                    lastDitherTarget = lvl
                                }
                                dither?.nextLevel() ?: lvl.toInt().coerceIn(0, 8)
                            }
                            else -> {
                                tempController.reset()
                                mode.targetState
                            }
                        }
                        FanController.writeCurState(applicationContext, target)
                    }

                // Adaptive polling: faster when hot, slower when cool (firmware-grade)
                val pollInterval = if (tempC > 65.0) 300L else 500L
                delay(pollInterval)
            }
        }
    }

    private fun startBatteryLoop() {
        batteryJob?.cancel()
        batteryJob = serviceScope.launch {
            while (isActive) {
                batteryMins = BatteryEstimator.estimateRemainingMinutes()
                batteryPct  = BatteryEstimator.readCapacityPercent()
                updateNotification()
                delay(10_000L) // 10s interval — firmware-grade, reduces CPU + SELinux churn
            }
        }
    }

    private var rgbInfoJob: Job? = null

    private fun getBatteryColor(pct: Int): Triple<Int, Int, Int> {
        val green = Triple(0, 255, 0)
        val amber = Triple(255, 190, 0)
        val red = Triple(255, 0, 0)
        return when {
            pct >= 100 -> green
            pct >= 50 -> lerpRgb(amber, green, (pct - 50) / 50f)
            pct >= 0 -> lerpRgb(red, amber, pct / 50f)
            else -> red
        }
    }

    private fun getHeatColor(tempC: Int): Triple<Int, Int, Int> {
        val coolC = 35
        val warmC = 55
        val hotC = 75
        val blue = Triple(0, 80, 255)
        val orange = Triple(255, 140, 0)
        val darkRed = Triple(160, 0, 0)
        return when {
            tempC <= coolC -> blue
            tempC <= warmC -> lerpRgb(blue, orange, (tempC - coolC).toFloat() / (warmC - coolC))
            tempC <= hotC -> lerpRgb(orange, darkRed, (tempC - warmC).toFloat() / (hotC - warmC))
            else -> darkRed
        }
    }

    private fun lerpRgb(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>, t: Float): Triple<Int, Int, Int> {
        val tt = t.coerceIn(0f, 1f)
        fun c(x: Int, y: Int) = (x + (y - x) * tt).toInt().coerceIn(0, 255)
        return Triple(c(a.first, b.first), c(a.second, b.second), c(a.third, b.third))
    }

    private fun applyRgb() {
        rgbInfoJob?.cancel()
        rgbInfoJob = null
        rainbowAnimator?.stop()
        rainbowAnimator = null
        val mode = runCatching {
            RgbMode.valueOf(prefs.getString(Prefs.RGB_MODE, RgbMode.OFF.name) ?: RgbMode.OFF.name)
        }.getOrDefault(RgbMode.OFF)

        val br = prefs.getInt(Prefs.RGB_BRIGHTNESS, 200)

        when (mode) {
            RgbMode.OFF -> {
                serviceScope.launch { JoystickRgbController.turnOff() }
            }
            RgbMode.STATIC -> {
                val r = prefs.getInt(Prefs.RGB_COLOR_R, 255)
                val g = prefs.getInt(Prefs.RGB_COLOR_G, 255)
                val b = prefs.getInt(Prefs.RGB_COLOR_B, 255)
                serviceScope.launch { JoystickRgbController.setAll(r, g, b, br) }
            }
            RgbMode.RAINBOW -> {
                rainbowAnimator = RotatingRainbowAnimator(
                    scope = serviceScope,
                    brightness = br
                )
                rainbowAnimator?.start()
            }
            RgbMode.BREATHE -> {
                rgbInfoJob = serviceScope.launch {
                    val r = prefs.getInt(Prefs.RGB_COLOR_R, 255)
                    val g = prefs.getInt(Prefs.RGB_COLOR_G, 255)
                    val b = prefs.getInt(Prefs.RGB_COLOR_B, 255)
                    var progress = 0.1f
                    var increment = 0.05f
                    while (isActive) {
                        val currentBr = (br * progress).toInt().coerceIn(0, 255)
                        JoystickRgbController.setAll(r, g, b, currentBr)
                        progress += increment
                        if (progress >= 1.0f) {
                            progress = 1.0f
                            increment = -0.05f
                        } else if (progress <= 0.1f) {
                            progress = 0.1f
                            increment = 0.05f
                            delay(400L) // longer pause at lowest brightness
                        }
                        delay(120L)
                    }
                }
            }
            RgbMode.AMBILIGHT -> {
                rgbInfoJob = serviceScope.launch {
                    startAmbilightLoop(br)
                }
            }
            RgbMode.BATTERY -> {
                rgbInfoJob = serviceScope.launch {
                    while (isActive) {
                        val pct = BatteryEstimator.readCapacityPercent()
                        val color = getBatteryColor(pct)
                        JoystickRgbController.setAll(color.first, color.second, color.third, br)
                        delay(2000L)
                    }
                }
            }
            RgbMode.THERMAL -> {
                rgbInfoJob = serviceScope.launch {
                    while (isActive) {
                        val temp = FanController.readMaxTempCelsius().toInt()
                        val color = getHeatColor(temp)
                        JoystickRgbController.setAll(color.first, color.second, color.third, br)
                        delay(2000L)
                    }
                }
            }
            RgbMode.WAVE -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var hue = 0f
                    val ledPaths = buildLedPathPairs()
                    while (isActive) {
                        animBuffer.setLength(0)
                        for ((idx, path) in ledPaths.withIndex()) {
                            val h = (hue + idx * 45f) % 360f
                            val (r, g, b) = hsvToRgb(h)
                            animBuffer.append("echo \"$r $g $b\" > $path/multi_intensity\necho $br > $path/brightness\n")
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        hue = (hue + 3f) % 360f
                        delay(100L)
                    }
                }
            }
            RgbMode.COLOR_CYCLE -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var hue = 0f
                    while (isActive) {
                        val (r, g, b) = hsvToRgb(hue)
                        JoystickRgbController.setAll(r, g, b, br)
                        hue = (hue + 1f) % 360f
                        delay(120L)
                    }
                }
            }
            RgbMode.STROBE -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val r = prefs.getInt(Prefs.RGB_COLOR_R, 255)
                    val g = prefs.getInt(Prefs.RGB_COLOR_G, 255)
                    val b = prefs.getInt(Prefs.RGB_COLOR_B, 255)
                    var on = true
                    while (isActive) {
                        if (on) JoystickRgbController.setAll(r, g, b, br)
                        else JoystickRgbController.setBrightnessAll(0)
                        on = !on
                        delay(120L)
                    }
                }
            }
            RgbMode.METEOR -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val r = prefs.getInt(Prefs.RGB_COLOR_R, 255)
                    val g = prefs.getInt(Prefs.RGB_COLOR_G, 255)
                    val b = prefs.getInt(Prefs.RGB_COLOR_B, 255)
                    val ledList = buildLedList()
                    var head = 0
                    while (isActive) {
                        animBuffer.setLength(0)
                        for ((i, led) in ledList.withIndex()) {
                            val dist = (head - i + ledList.size) % ledList.size
                            val fadeBr = when {
                                dist == 0 -> br
                                dist == 1 -> (br * 0.6f).toInt()
                                dist == 2 -> (br * 0.3f).toInt()
                                dist == 3 -> (br * 0.1f).toInt()
                                else -> 0
                            }.coerceIn(0, 255)
                            animBuffer.append("echo \"$r $g $b\" > $led/multi_intensity\necho $fadeBr > $led/brightness\n")
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        head = (head + 1) % ledList.size
                        delay(120L)
                    }
                }
            }
            RgbMode.FIRE -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val rng = java.util.Random()
                    val ledPaths = buildLedPathPairs()
                    while (isActive) {
                        animBuffer.setLength(0)
                        for (path in ledPaths) {
                            val r = 200 + rng.nextInt(56)
                            val g = rng.nextInt(120)
                            val b = rng.nextInt(20)
                            val flicker = (br * (0.4f + rng.nextFloat() * 0.6f)).toInt().coerceIn(0, 255)
                            animBuffer.append("echo \"$r $g $b\" > $path/multi_intensity\necho $flicker > $path/brightness\n")
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        delay(100L + rng.nextInt(60).toLong())
                    }
                }
            }
            RgbMode.AURORA -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var time = 0f
                    val ledPaths = buildLedPathPairs()
                    while (isActive) {
                        animBuffer.setLength(0)
                        for ((idx, path) in ledPaths.withIndex()) {
                            val phase = time + idx * 0.8f
                            val hue = 120f + 120f * kotlin.math.sin(phase.toDouble()).toFloat()
                            val (r, g, b) = hsvToRgb(hue.coerceIn(0f, 359f), 0.8f, 1f)
                            val drift = (br * (0.5f + 0.5f * kotlin.math.sin((phase * 0.7f).toDouble()).toFloat())).toInt().coerceIn(0, 255)
                            animBuffer.append("echo \"$r $g $b\" > $path/multi_intensity\necho $drift > $path/brightness\n")
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        time += 0.05f
                        delay(100L)
                    }
                }
            }
            RgbMode.OCEAN -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var time = 0f
                    val ledPaths = buildLedPathPairs()
                    while (isActive) {
                        animBuffer.setLength(0)
                        for ((idx, path) in ledPaths.withIndex()) {
                            val phase = time + idx * 0.9f
                            val wave = kotlin.math.sin(phase.toDouble()).toFloat()
                            val r = 0
                            val g = (80 + 80 * wave).toInt().coerceIn(0, 255)
                            val b = (180 + 75 * wave).toInt().coerceIn(0, 255)
                            val waveBr = (br * (0.3f + 0.7f * ((wave + 1f) / 2f))).toInt().coerceIn(0, 255)
                            animBuffer.append("echo \"$r $g $b\" > $path/multi_intensity\necho $waveBr > $path/brightness\n")
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        time += 0.08f
                        delay(100L)
                    }
                }
            }
            RgbMode.POLICE -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var phase = 0
                    val leftPaths = Corner.entries.map { ledSysfsPath(Stick.LEFT, it) }
                    val rightPaths = Corner.entries.map { ledSysfsPath(Stick.RIGHT, it) }
                    while (isActive) {
                        val leftColor = if (phase % 2 == 0) "255 0 0" else "0 0 255"
                        val rightColor = if (phase % 2 == 0) "0 0 255" else "255 0 0"
                        animBuffer.setLength(0)
                        for (i in leftPaths.indices) {
                            animBuffer.append("echo \"$leftColor\" > ${leftPaths[i]}/multi_intensity\necho $br > ${leftPaths[i]}/brightness\n")
                            animBuffer.append("echo \"$rightColor\" > ${rightPaths[i]}/multi_intensity\necho $br > ${rightPaths[i]}/brightness\n")
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        phase++
                        delay(if (phase % 4 < 2) 80L else 200L)
                    }
                }
            }
            RgbMode.STARLIGHT -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val rng = java.util.Random()
                    val ledList = buildLedList()
                    while (isActive) {
                        animBuffer.setLength(0)
                        for (led in ledList) {
                            val twinkle = rng.nextFloat()
                            if (twinkle > 0.6f) {
                                val hue = rng.nextFloat() * 360f
                                val (r, g, b) = hsvToRgb(hue, 0.2f, 1f)
                                animBuffer.append("echo \"$r $g $b\" > $led/multi_intensity\necho $br > $led/brightness\n")
                            } else {
                                val dimBr = (br * twinkle * 0.3f).toInt().coerceIn(0, 255)
                                animBuffer.append("echo \"200 200 255\" > $led/multi_intensity\necho $dimBr > $led/brightness\n")
                            }
                        }
                        JoystickRgbController.executeFastRaw(animBuffer.toString())
                        delay(100L + rng.nextInt(100).toLong())
                    }
                }
            }
            RgbMode.MUSIC -> {
                rgbInfoJob = serviceScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    var recorder: android.media.AudioRecord? = null
                    try {
                        val sampleRate = 8000
                        val bufSize = android.media.AudioRecord.getMinBufferSize(
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_IN_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT
                        )
                        recorder = android.media.AudioRecord(
                            android.media.MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_IN_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                            bufSize
                        )
                        recorder.startRecording()
                        val buffer = ShortArray(bufSize / 2)

                        while (isActive) {
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                var sum = 0L
                                for (i in 0 until read) sum += kotlin.math.abs(buffer[i].toInt())
                                val amplitude = (sum / read).toInt()
                                val norm = (amplitude / 5000f).coerceIn(0f, 1f)
                                val hue = 240f - norm * 240f
                                val (r, g, b) = hsvToRgb(hue.coerceIn(0f, 359f))
                                val musicBr = (br * (0.1f + norm * 0.9f)).toInt().coerceIn(0, 255)
                                JoystickRgbController.setAll(r, g, b, musicBr)
                            }
                            delay(120L)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Music mode error: ${e.message}")
                        var hue = 0f
                        while (isActive) {
                            val (r, g, b) = hsvToRgb(hue)
                            JoystickRgbController.setAll(r, g, b, br)
                            hue = (hue + 2f) % 360f
                            delay(100L)
                        }
                    } finally {
                        recorder?.stop()
                        recorder?.release()
                    }
                }
            }
        }
    }

    /** Get sysfs path for a specific LED */
    private fun ledSysfsPath(stick: Stick, corner: Corner): String {
        val prefix = if (stick == Stick.LEFT) "left" else "right"
        val index = when (stick) {
            Stick.LEFT -> when (corner) {
                Corner.TOP_LEFT -> 0; Corner.BOTTOM_LEFT -> 1
                Corner.BOTTOM_RIGHT -> 2; Corner.TOP_RIGHT -> 3
            }
            Stick.RIGHT -> when (corner) {
                Corner.BOTTOM_RIGHT -> 0; Corner.TOP_RIGHT -> 1
                Corner.TOP_LEFT -> 2; Corner.BOTTOM_LEFT -> 3
            }
        }
        return "/sys/class/leds/$prefix:stick:$index"
    }

    /** Build ordered list of all 8 LED sysfs paths for sequential effects */
    private fun buildLedList(): List<String> = buildList {
        add(ledSysfsPath(Stick.LEFT, Corner.TOP_LEFT))
        add(ledSysfsPath(Stick.LEFT, Corner.TOP_RIGHT))
        add(ledSysfsPath(Stick.RIGHT, Corner.TOP_LEFT))
        add(ledSysfsPath(Stick.RIGHT, Corner.TOP_RIGHT))
        add(ledSysfsPath(Stick.RIGHT, Corner.BOTTOM_RIGHT))
        add(ledSysfsPath(Stick.RIGHT, Corner.BOTTOM_LEFT))
        add(ledSysfsPath(Stick.LEFT, Corner.BOTTOM_RIGHT))
        add(ledSysfsPath(Stick.LEFT, Corner.BOTTOM_LEFT))
    }

    /** Build flat list of sysfs paths for all stick×corner combos (used by animation loops) */
    private fun buildLedPathPairs(): List<String> = buildList {
        for (stick in Stick.entries) {
            for (corner in Corner.entries) {
                add(ledSysfsPath(stick, corner))
            }
        }
    }

    private val SCREENCAP_PATH = "/dev/screencap.raw"

    /**
     * High-performance Ambilight loop.
     *
     * All I/O (screencap + LED sysfs writes) goes through a single persistent
     * `su` process. LED writes from the PREVIOUS frame are pipelined with the
     * CURRENT frame's screencap so they execute in parallel.
     *
     * Pipeline per iteration:
     *   stdin → "[prev LED writes]; screencap > file && echo F"
     *   wait for "F" on stdout
     *   read 8 pixels via RandomAccessFile (kept open, just seek)
     *   compute colors → build next LED command string
     *   loop
     */
    private suspend fun startAmbilightLoop(maxBr: Int) {
        if (mediaProjectionIntent == null) {
            Log.i(TAG, "No MediaProjection token. Launching Activity to acquire it.")
            val intent = Intent(this, MediaProjectionActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            return
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(android.app.Activity.RESULT_OK, mediaProjectionIntent!!)
            
            val width = 16
            val height = 9
            
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    super.onStop()
                    Log.i(TAG, "MediaProjection stopped by system.")
                }
            }, null)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "Ambilight",
                width, height, 160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, null
            )
            
            Log.i(TAG, "Ambilight 120Hz Hardware-Accelerated VirtualDisplay created (16x9)")
            
            data class LedZone(val sysfs: String, val x: Int, val y: Int)
            
            val zones = arrayOf(
                LedZone("left:stick:0",  0, 0),                        // Left TL
                LedZone("left:stick:1",  0, height-1),                 // Left BL
                LedZone("left:stick:2",  width/4, height-1),           // Left BR
                LedZone("left:stick:3",  width/4, 0),                  // Left TR
                LedZone("right:stick:2", width-1 - width/4, 0),        // Right TL
                LedZone("right:stick:1", width-1, 0),                  // Right TR
                LedZone("right:stick:3", width-1 - width/4, height-1), // Right BL
                LedZone("right:stick:0", width-1, height-1)            // Right BR
            )

            var lastExecTime = 0L

            imageReader?.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                
                // Cap at 60fps to prevent overloading the I2C bus while keeping 0 latency
                val now = System.currentTimeMillis()
                if (now - lastExecTime < 16) {
                    image.close()
                    return@setOnImageAvailableListener
                }
                lastExecTime = now

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                
                animBuffer.setLength(0)
                
                for (zone in zones) {
                    val offset = zone.y * rowStride + zone.x * pixelStride
                    buffer.position(offset)
                    val r = buffer.get().toInt() and 0xFF
                    val g = buffer.get().toInt() and 0xFF
                    val b = buffer.get().toInt() and 0xFF
                    
                    val lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                    val br = (maxBr * lum).toInt().coerceIn(1, 255)
                    
                    val base = "/sys/class/leds/${zone.sysfs}"
                    animBuffer.append("echo \"$r $g $b\">$base/multi_intensity\necho $br>$base/brightness\n")
                }
                
                image.close()
                JoystickRgbController.executeFastRaw(animBuffer.toString())
                
            }, Handler(Looper.getMainLooper()))
            
            // Suspend coroutine while listener is active
            while (kotlin.coroutines.coroutineContext.isActive) {
                delay(1000L)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ambilight error: ${e.message}")
        } finally {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            mediaProjectionIntent = null // MUST reset token because Android 14 does not allow re-using the same intent
        }
    }

    // -----------------------------------------------------------
    // Prefs loading
    // -----------------------------------------------------------

    private fun loadAllPrefs() {
        loadFanPrefs()
        loadCpuPrefs()
        loadOverlayPref()
        loadAutoTdpPrefs()
        kernelThermalControlEnabled = prefs.getBoolean(Prefs.KERNEL_THERMAL_CONTROL, true)
        applyKernelThermalControl(kernelThermalControlEnabled)
    }

    private fun loadOverlayPref() {
        overlayEnabled = prefs.getBoolean(Prefs.OVERLAY_ENABLED, false)
    }

    private fun applyOverlayState() {
        if (overlayEnabled) {
            if (performanceOverlay == null) {
                performanceOverlay = PerformanceOverlay(applicationContext)
            }
            if (android.provider.Settings.canDrawOverlays(applicationContext)) {
                performanceOverlay?.show()
            }
        } else {
            performanceOverlay?.hide()
            performanceOverlay = null
        }
    }

    private fun loadAutoTdpPrefs() {
        autoTdpEnabled = prefs.getBoolean(Prefs.AUTOTDP_ENABLED, false)
        autoTdpTargetFps = prefs.getInt(Prefs.AUTOTDP_TARGET_FPS, 60)
    }

    private fun applyDynamicCpuFreqCap(pct: Double) {
        val policies = CpuModeController.getPolicies()
        val commands = policies.map { policy ->
            val freq = (policy.maxFreqKHz * pct).toInt()
            "echo $freq > ${policy.path}/scaling_max_freq"
        }
        com.topjohnwu.superuser.Shell.cmd(*commands.toTypedArray()).submit()
    }

    private fun applyAutoTdpState() {
        autoTdpJob?.cancel()
        autoTdpJob = null
        if (autoTdpEnabled) {
            serviceScope.launch {
                val policies = CpuModeController.getPolicies()
                val commands = policies.map { "echo schedutil > ${it.path}/scaling_governor" }
                com.topjohnwu.superuser.Shell.cmd(*commands.toTypedArray()).exec()

                autoTdpPct = 1.0
                applyDynamicCpuFreqCap(autoTdpPct)

                autoTdpJob = serviceScope.launch {
                    FpsReader.start()
                    while (isActive) {
                        delay(1500L)
                        val fps = FpsReader.readFps()
                        val target = autoTdpTargetFps.toDouble()
                        if (fps > 0f) {
                            if (fps < target - 3.0) {
                                autoTdpPct = (autoTdpPct + 0.10).coerceAtMost(1.0)
                                applyDynamicCpuFreqCap(autoTdpPct)
                            } else if (fps >= target) {
                                autoTdpPct = (autoTdpPct - 0.05).coerceAtLeast(0.40)
                                applyDynamicCpuFreqCap(autoTdpPct)
                            }
                        }
                    }
                }
            }
        } else {
            serviceScope.launch {
                CpuModeController.apply(currentCpuMode)
            }
        }
    }

    private fun loadFanPrefs() {
        currentFanMode = runCatching {
            FanMode.valueOf(prefs.getString(Prefs.FAN_MODE, FanMode.SMART.name)!!)
        }.getOrDefault(FanMode.SMART)
        customFanLevel = prefs.getFloat(Prefs.FAN_CUSTOM, 4f).toDouble()
        fanHoldTemp = prefs.getBoolean(Prefs.FAN_HOLD_TEMP, false)
        fanTargetTemp = prefs.getInt(Prefs.FAN_TARGET_TEMP, 75)
        fanCurveEnabled = prefs.getBoolean(Prefs.FAN_CURVE_ENABLED, false)
        fanCurvePoints = FanCurveSerializer.parse(prefs.getString(Prefs.FAN_CURVE_POINTS, null))
    }

    private fun loadCpuPrefs() {
        currentCpuMode = runCatching {
            CpuMode.valueOf(prefs.getString(Prefs.CPU_MODE, CpuMode.BALANCED.name)!!)
        }.getOrDefault(CpuMode.BALANCED)
    }

    // -----------------------------------------------------------
    // Notification
    // -----------------------------------------------------------

    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            NOTIF_CHANNEL,
            "Hardware Control",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Fan, CPU, and battery monitoring"
            setShowBadge(false)
        }
        notifManager.createNotificationChannel(chan)
    }

    private fun buildNotification(): Notification {
        val fanLabel = if (watchdogActive)
            "⚠️ ${currentFanMode.label} (overheat override)"
        else
            currentFanMode.label

        val batteryText = when {
            batteryMins == null -> ""
            batteryMins!! > 60 -> "🔋 ${batteryMins!! / 60}h ${batteryMins!! % 60}m"
            else               -> "🔋 ${batteryMins}m"
        }
        val pctText = if (batteryPct >= 0) " ($batteryPct%)" else ""

        val contentText = "Fan: $fanLabel  •  CPU: ${currentCpuMode.label}  $batteryText$pctText"

        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("Handheld Settings")
            .setContentText(contentText)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        val notif = buildNotification()
        // Dirty flag: skip if content hasn't changed
        val contentText = notif.extras?.getString(Notification.EXTRA_TEXT) ?: ""
        if (contentText == lastNotifText) return
        lastNotifText = contentText
        notifManager.notify(NOTIF_ID, notif)
    }
}
