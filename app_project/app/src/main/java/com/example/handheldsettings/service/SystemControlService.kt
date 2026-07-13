package com.example.handheldsettings.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.example.handheldsettings.hardware.BatteryEstimator
import com.example.handheldsettings.hardware.CpuModeController
import com.example.handheldsettings.hardware.FanController
import com.example.handheldsettings.hardware.FractionalStateDither
import com.example.handheldsettings.hardware.JoystickRgbController
import com.example.handheldsettings.hardware.RotatingRainbowAnimator
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

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, SystemControlService::class.java)
                .setAction(ACTION_UPDATE)
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var fanJob: Job? = null
    private var batteryJob: Job? = null
    private var rainbowAnimator: RotatingRainbowAnimator? = null
    private var daemonProcess: Process? = null

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
    private var autoTdpJob: Job? = null
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

        startBackgroundShellDaemon()

        startForeground(NOTIF_ID, buildNotification())
        startLoops()
        applyRgb()
        applyOverlayState()
        applyAutoTdpState()

        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        Log.i(TAG, "SystemControlService started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        serviceScope.cancel()
        
        // Re-enable kernel thermal control for system safety on shutdown
        applyKernelThermalControl(true)

        // Stop the background shell loop by deleting the sentinel file
        java.io.File(filesDir, "service_running").delete()
        java.io.File(filesDir, "fan_target").delete()
        daemonProcess?.destroy()
        
        Log.i(TAG, "SystemControlService destroyed")
    }

    private fun applyKernelThermalControl(enabled: Boolean) {
        val mode = if (enabled) "enabled" else "disabled"
        val script = """
            for z in 14 15 33 34 53; do
                echo $mode > /sys/class/thermal/thermal_zone${'$'}z/mode 2>/dev/null
            done
        """.trimIndent()
        com.topjohnwu.superuser.Shell.cmd(script).submit()
        Log.i(TAG, "Applied kernel thermal control: $mode")
    }

    private fun startBackgroundShellDaemon() {
        val path = FanController.discoverFanPath() ?: return
        try {
            java.io.File(filesDir, "service_running").createNewFile()
            java.io.File(filesDir, "fan_target").writeText("SMART")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create daemon files: ${e.message}")
        }

        // Spawn a completely independent root shell process to run our loop.
        // Ticks every 50ms to aggressively override the kernel governor with 0ms IPC delay.
        serviceScope.launch(Dispatchers.IO) {
            try {
                val proc = Runtime.getRuntime().exec("su")
                daemonProcess = proc
                val writer = proc.outputStream.bufferedWriter()
                
                val script = """
                    counter=0
                    last_target=""
                    while [ -f ${filesDir.absolutePath}/service_running ]; do
                        if [ -f ${filesDir.absolutePath}/fan_target ]; then
                            target=${'$'}(cat ${filesDir.absolutePath}/fan_target 2>/dev/null)
                            if [ "${'$'}target" != "SMART" ] && [ ! -z "${'$'}target" ]; then
                                if [ "${'$'}target" != "${'$'}last_target" ]; then
                                    echo "${'$'}target" > $path/cur_state 2>/dev/null
                                    actual=${'$'}(cat $path/cur_state 2>/dev/null)
                                    log -t FanDaemon "Target=${'$'}target Actual=${'$'}actual"
                                    last_target="${'$'}target"
                                fi
                            elif [ "${'$'}target" = "SMART" ] && [ "${'$'}target" != "${'$'}last_target" ]; then
                                log -t FanDaemon "Switched to SMART mode"
                                last_target="SMART"
                            fi
                        fi
                        counter=${'$'}((counter + 1))
                        sleep 0.2
                    done
                    exit
                """.trimIndent()
                
                writer.write(script)
                writer.newLine()
                writer.flush()
                
                proc.waitFor()
                Log.i(TAG, "Background shell fan daemon exited cleanly")
            } catch (e: Exception) {
                Log.e(TAG, "Error in background shell fan daemon: ${e.message}")
            }
        }
        Log.i(TAG, "Background shell fan daemon started")
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

                if (kernelThermalControlEnabled) {
                    FanController.writeSmartMode(applicationContext)
                    tempController.reset()
                    watchdogActive = false
                } else {
                    // Watchdog: if temp > 75°C and mode is too low, force fan up
                    val watchdogOverride = tempC > 75.0 && mode.targetState in 0..3
                    watchdogActive = watchdogOverride

                    if (mode == FanMode.SMART && !watchdogOverride) {
                        FanController.writeSmartMode(applicationContext)
                        tempController.reset()
                    } else {
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
                }

                delay(150L)
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
                delay(5_000L)
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
                        delay(80L)
                    }
                }
            }
            RgbMode.AMBILIGHT -> {
                rgbInfoJob = serviceScope.launch {
                    while (isActive) {
                        val color = getAverageScreenColor()
                        JoystickRgbController.setAll(color.first, color.second, color.third, br)
                        delay(350L) // responsive but not spammy
                    }
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
        }
    }

    private var lastScreenColor = Triple(255, 255, 255)

    private fun getAverageScreenColor(): Triple<Int, Int, Int> {
        var proc: Process? = null
        try {
            proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "screencap"))
            val input = proc.inputStream
            
            val header = ByteArray(12)
            var bytesRead = 0
            while (bytesRead < 12) {
                val r = input.read(header, bytesRead, 12 - bytesRead)
                if (r == -1) break
                bytesRead += r
            }
            if (bytesRead < 12) {
                proc.destroy()
                return lastScreenColor
            }
            
            val width = (header[0].toInt() and 0xFF) or
                        ((header[1].toInt() and 0xFF) shl 8) or
                        ((header[2].toInt() and 0xFF) shl 16) or
                        ((header[3].toInt() and 0xFF) shl 24)
            val height = (header[4].toInt() and 0xFF) or
                         ((header[5].toInt() and 0xFF) shl 8) or
                         ((header[6].toInt() and 0xFF) shl 16) or
                         ((header[7].toInt() and 0xFF) shl 24)
            
            if (width <= 0 || height <= 0 || width > 4000 || height > 4000) {
                proc.destroy()
                return lastScreenColor
            }
            
            val y = height / 2
            val x1 = width / 4
            val x2 = width / 2
            val x3 = (3 * width) / 4
            
            val offset1 = (y * width + x1) * 4L
            val offset2 = (y * width + x2) * 4L
            val offset3 = (y * width + x3) * 4L
            
            fun readPixelAtOffset(targetOffset: Long, currentOffset: Long): Pair<Triple<Int, Int, Int>?, Long> {
                val toSkip = targetOffset - currentOffset
                if (toSkip < 0) return Pair(null, currentOffset)
                
                var skipped = 0L
                while (skipped < toSkip) {
                    val s = input.skip(toSkip - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                
                val pixel = ByteArray(4)
                var rBytes = 0
                while (rBytes < 4) {
                    val r = input.read(pixel, rBytes, 4 - rBytes)
                    if (r == -1) break
                    rBytes += r
                }
                if (rBytes < 4) return Pair(null, currentOffset + skipped)
                
                val red = pixel[0].toInt() and 0xFF
                val green = pixel[1].toInt() and 0xFF
                val blue = pixel[2].toInt() and 0xFF
                return Pair(Triple(red, green, blue), currentOffset + skipped + 4)
            }
            
            var curOffset = 0L
            val (p1, o1) = readPixelAtOffset(offset1, curOffset)
            val (p2, o2) = readPixelAtOffset(offset2, o1)
            val (p3, _) = readPixelAtOffset(offset3, o2)
            
            proc.destroy()
            
            val validPixels = listOfNotNull(p1, p2, p3)
            if (validPixels.isEmpty()) return lastScreenColor
            
            val avgR = validPixels.map { it.first }.average().toInt()
            val avgG = validPixels.map { it.second }.average().toInt()
            val avgB = validPixels.map { it.third }.average().toInt()
            
            val resultColor = Triple(avgR, avgG, avgB)
            lastScreenColor = resultColor
            return resultColor
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get screen color: ${e.message}")
            proc?.destroy()
            return lastScreenColor
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
        notifManager.notify(NOTIF_ID, buildNotification())
    }
}
