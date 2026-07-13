package com.example.handheldsettings.overlay

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.hardware.BatteryEstimator
import com.example.handheldsettings.hardware.CpuModeController
import com.example.handheldsettings.hardware.FanController
import com.example.handheldsettings.hardware.FpsReader
import kotlinx.coroutines.*
import kotlin.math.roundToInt

class PerformanceOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs: SharedPreferences = context.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var host: OverlayViewHost? = null
    private var params: WindowManager.LayoutParams? = null
    private var overlayScope: CoroutineScope? = null

    private var posX = 100
    private var posY = 100
    private var isLocked = false

    fun show() {
        if (host != null) return

        posX = prefs.getInt(Prefs.OVERLAY_X, 100)
        posY = prefs.getInt(Prefs.OVERLAY_Y, 100)
        isLocked = prefs.getBoolean(Prefs.OVERLAY_LOCKED, false)

        mainHandler.post {
            val newHost = OverlayViewHost(context)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                getFlags(isLocked),
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
            }

            newHost.setContent {
                var fps by remember { mutableStateOf(0f) }
                var temp by remember { mutableStateOf(0) }
                var battery by remember { mutableStateOf(100) }
                var cpuMode by remember { mutableStateOf("") }
                var lockedState by remember { mutableStateOf(isLocked) }

                LaunchedEffect(Unit) {
                    overlayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
                    FpsReader.start()
                    overlayScope?.launch {
                        while (isActive) {
                            fps = FpsReader.readFps()
                            temp = FanController.readMaxTempCelsius().toInt()
                            battery = BatteryEstimator.readCapacityPercent()
                            cpuMode = prefs.getString(Prefs.CPU_MODE, "BALANCED") ?: "BALANCED"
                            delay(500L)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .wrapContentSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xE6151B1C)) // surfaceContainer #151B1C with 90% opacity
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(16.dp))
                        .padding(12.dp)
                        .then(
                            if (!lockedState) {
                                Modifier.pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        moveBy(dragAmount.x, dragAmount.y)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().widthIn(min = 120.dp)
                        ) {
                            Text(
                                text = "Telemetry",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Icon(
                                imageVector = if (lockedState) Icons.Default.Lock else Icons.Default.LockOpen,
                                contentDescription = "Lock State",
                                tint = if (lockedState) Color(0xFF4FD89B) else Color(0xFFFFB000),
                                modifier = Modifier
                                    .size(14.dp)
                                    .clickable {
                                        lockedState = !lockedState
                                        toggleLock(lockedState)
                                    }
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(text = "FPS: ${if (fps > 0) String.format("%.1f", fps) else "..."}", color = Color(0xFF4FD89B), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Temp: ${temp}°C", color = if (temp > 70) Color(0xFFFF5D6C) else Color.White, fontSize = 12.sp)
                        Text(text = "CPU: $cpuMode", color = Color.White, fontSize = 12.sp)
                        Text(text = "Battery: $battery%", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            try {
                windowManager.addView(newHost.composeView, lp)
                newHost.onResumed()
                host = newHost
                params = lp
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun hide() {
        val currentHost = host
        host = null
        params = null
        overlayScope?.cancel()
        overlayScope = null
        FpsReader.stop()

        mainHandler.post {
            currentHost?.let { h ->
                try {
                    windowManager.removeView(h.composeView)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                h.onDestroyed()
            }
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val lp = params ?: return
        posX += dx.roundToInt()
        posY += dy.roundToInt()
        lp.x = posX
        lp.y = posY
        mainHandler.post {
            try {
                host?.let { h ->
                    windowManager.updateViewLayout(h.composeView, lp)
                    prefs.edit()
                        .putInt(Prefs.OVERLAY_X, posX)
                        .putInt(Prefs.OVERLAY_Y, posY)
                        .apply()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun toggleLock(locked: Boolean) {
        isLocked = locked
        prefs.edit().putBoolean(Prefs.OVERLAY_LOCKED, locked).apply()
        val lp = params ?: return
        lp.flags = getFlags(locked)
        mainHandler.post {
            try {
                host?.let { h ->
                    windowManager.updateViewLayout(h.composeView, lp)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun getFlags(locked: Boolean): Int {
        val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        return if (locked) {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            base or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
    }
}
