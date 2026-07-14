package com.example.handheldsettings.ui

import android.app.Activity
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.handheldsettings.data.CpuMode
import com.example.handheldsettings.data.FanCurvePoint
import com.example.handheldsettings.data.FanCurveSerializer
import com.example.handheldsettings.data.FanMode
import com.example.handheldsettings.data.Prefs
import com.example.handheldsettings.data.RgbMode
import com.example.handheldsettings.hardware.CpuModeController
import com.example.handheldsettings.hardware.RefreshRateController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? Activity

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    // Dialog visibility states
    var showCpuDialog by remember { mutableStateOf(false) }
    var showRrDialog by remember { mutableStateOf(false) }
    var showFanDialog by remember { mutableStateOf(false) }
    var showRgbDialog by remember { mutableStateOf(false) }
    var showFpsDialog by remember { mutableStateOf(false) }
    var showCurveEditor by remember { mutableStateOf(false) }
    var showColorPicker by remember { mutableStateOf(false) }

    val surfaceBg = MaterialTheme.colorScheme.surfaceContainer

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Handheld Settings") },
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { activity?.finish() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = surfaceBg,
                    scrolledContainerColor = surfaceBg
                )
            )
        },
        containerColor = surfaceBg
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(bottom = 48.dp)
        ) {
            // System Status Card
            Box(Modifier.padding(16.dp)) {
                HeaderCard(state)
            }

            AospPreferenceCategory("Performance & Display")
            AospPreferenceCard {
                AospSettingsRow(
                    title = "Kernel Thermal Control",
                    summary = "Allows thermal throttling. Disable to prevent performance caps.",
                    onClick = { vm.setKernelThermalControl(!state.kernelThermalControlEnabled) },
                    trailing = {
                        Switch(
                            checked = state.kernelThermalControlEnabled,
                            onCheckedChange = { vm.setKernelThermalControl(it) }
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AospSettingsRow(
                    title = "CPU Profile",
                    summary = if (state.autotdpEnabled) "Managed dynamically by AutoTDP" else "Currently: ${state.cpuMode.label}",
                    onClick = { if (!state.autotdpEnabled) showCpuDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AospSettingsRow(
                    title = "Refresh Rate",
                    summary = "Currently: ${state.refreshRate.label}",
                    onClick = { showRrDialog = true }
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AospSettingsRow(
                    title = "AutoTDP",
                    summary = "Dynamically scale CPU to target FPS at lowest power",
                    onClick = { vm.setAutoTdpEnabled(!state.autotdpEnabled) },
                    trailing = {
                        Switch(
                            checked = state.autotdpEnabled,
                            onCheckedChange = { vm.setAutoTdpEnabled(it) }
                        )
                    }
                )
                if (state.autotdpEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AospSettingsRow(
                        title = "Target FPS",
                        summary = "Currently: ${state.autotdpTargetFps} FPS",
                        onClick = { showFpsDialog = true }
                    )
                }
            }

            AospPreferenceCategory("Cooling & Fan")
            AospPreferenceCard {
                AospSettingsRow(
                    title = "Fan Mode",
                    summary = when {
                        state.kernelThermalControlEnabled -> "Controlled by Kernel Thermal Control (Safe Mode)"
                        state.fanMode == FanMode.SMART -> "Smart (Auto)"
                        else -> "Target: ${state.fanMode.label} • Actual: ${state.fanCurState}/8"
                    },
                    onClick = { if (!state.kernelThermalControlEnabled) showFanDialog = true }
                )
                if (state.fanMode == FanMode.CUSTOM && !state.kernelThermalControlEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AospSettingsRow(
                        title = "Hold Target Temp",
                        summary = "Run PI loop to hold temperature instead of static speed",
                        onClick = {
                            vm.setFanHoldTemp(!state.fanHoldTemp)
                            if (!state.fanHoldTemp) {
                                vm.setFanCurveEnabled(false)
                            }
                        },
                        trailing = {
                            Switch(
                                checked = state.fanHoldTemp,
                                onCheckedChange = {
                                    vm.setFanHoldTemp(it)
                                    if (it) {
                                        vm.setFanCurveEnabled(false)
                                    }
                                }
                            )
                        }
                    )
                    if (state.fanHoldTemp) {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        AospSliderRow(
                            title = "Target Temperature",
                            value = state.fanTargetTemp.toFloat(),
                            onValueChange = { vm.setFanTargetTemp(it.toInt()) },
                            valueRange = 60f..85f,
                            steps = 24,
                            valueLabel = "${state.fanTargetTemp} °C"
                        )
                    } else {
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        AospSettingsRow(
                            title = "Custom Fan Curve",
                            summary = "Linearly interpolate speeds dynamically based on preset curve",
                            onClick = {
                                vm.setFanCurveEnabled(!state.fanCurveEnabled)
                            },
                            trailing = {
                                Switch(
                                    checked = state.fanCurveEnabled,
                                    onCheckedChange = { vm.setFanCurveEnabled(it) }
                                )
                            }
                        )
                        if (state.fanCurveEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            AospSettingsRow(
                                    title = "Edit Fan Curve",
                                    summary = "${state.fanCurvePoints.size} control points",
                                    onClick = { showCurveEditor = true }
                            )
                        } else {
                            HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            AospSliderRow(
                                title = "Custom Fan Speed",
                                value = state.customFanLevel,
                                onValueChange = { vm.setCustomFanLevel(it) },
                                valueRange = 0f..8f,
                                steps = 7,
                                valueLabel = "${state.customFanLevel.toInt()} / 8"
                            )
                        }
                    }
                }
            }
            // ─── Live Telemetry ───
            AospPreferenceCategory("Live Telemetry")
            AospPreferenceCard {
                // FPS
                AospSettingsRow(
                    title = "FPS",
                    summary = if (state.currentFps > 0f) "${state.currentFps.toInt()} fps" else "N/A"
                )
                // CPU Clocks per policy
                state.cpuLiveInfo.forEachIndexed { index, info ->
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AospSettingsRow(
                        title = info.name.replaceFirstChar { it.uppercase() },
                        summary = "${info.curFreqMHz} MHz / ${info.maxFreqMHz} MHz  •  ${info.governor}"
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AospSettingsRow(
                    title = "Temperature",
                    summary = if (state.maxTempC > 0) "${state.maxTempC.toInt()} °C" else "N/A"
                )
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AospSettingsRow(
                    title = "Battery",
                    summary = buildString {
                        if (state.batteryPct >= 0) append("${state.batteryPct}%")
                        state.batteryMins?.let { mins ->
                            if (mins > 60) append(" • ~${mins/60}h ${mins%60}m left")
                            else append(" • ~${mins}m left")
                        }
                        if (isEmpty()) append("N/A")
                    }
                )
            }

            AospPreferenceCategory("Customization")
            AospPreferenceCard {
                AospSettingsRow(
                    title = "Joystick RGB",
                    summary = "LED controls & presets",
                    onClick = { showRgbDialog = true }
                )
                if (state.rgbMode == RgbMode.STATIC || state.rgbMode == RgbMode.BREATHE || state.rgbMode == RgbMode.STROBE || state.rgbMode == RgbMode.METEOR) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AospColorPresetRow(
                        title = "Color presets",
                        state = state,
                        onColorSelected = { r, g, b -> vm.setRgbColor(r, g, b) },
                        onCustomColorClick = { showColorPicker = true }
                    )
                }
                if (state.rgbMode != RgbMode.OFF) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    AospSliderRow(
                        title = "Brightness",
                        value = state.rgbBrightness.toFloat(),
                        onValueChange = { vm.setRgbBrightness(it.toInt()) },
                        valueRange = 0f..255f,
                        valueLabel = "${(state.rgbBrightness / 255f * 100).toInt()}%"
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                AospSettingsRow(
                    title = "Telemetry OSD Overlay",
                    summary = "Draws a floating stats window over other apps",
                    onClick = {
                        val hasPerm = Settings.canDrawOverlays(context)
                        if (!hasPerm) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            vm.setOverlayEnabled(!state.overlayEnabled)
                        }
                    },
                    trailing = {
                        Switch(
                            checked = state.overlayEnabled && Settings.canDrawOverlays(context),
                            onCheckedChange = { checked ->
                                val hasPerm = Settings.canDrawOverlays(context)
                                if (!hasPerm && checked) {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                    context.startActivity(intent)
                                } else {
                                    vm.setOverlayEnabled(checked)
                                }
                            }
                        )
                    }
                )
            }
        }
    }

    // Dialogs
    if (showCpuDialog) {
        val cpuModes = listOf(CpuMode.POWER_SAVING, CpuMode.BALANCED, CpuMode.ULTRA, CpuMode.ADAPTIVE)
        AospPreferenceDialog(
            title = "CPU Profile",
            items = cpuModes,
            selectedItem = state.cpuMode,
            labelProvider = { it.label },
            onItemSelected = { vm.setCpuMode(it) },
            onDismissRequest = { showCpuDialog = false }
        )
    }

    if (showRrDialog) {
        val rates = RefreshRateController.RefreshRate.entries
        AospPreferenceDialog(
            title = "Refresh Rate",
            items = rates,
            selectedItem = state.refreshRate,
            labelProvider = { it.label },
            onItemSelected = { vm.setRefreshRate(it) },
            onDismissRequest = { showRrDialog = false }
        )
    }

    if (showFanDialog) {
        val modes = listOf(FanMode.OFF, FanMode.QUIET, FanMode.SMART, FanMode.SPORT, FanMode.MAX, FanMode.CUSTOM)
        AospPreferenceDialog(
            title = "Fan Mode",
            items = modes,
            selectedItem = state.fanMode,
            labelProvider = { it.label },
            onItemSelected = { vm.setFanMode(it) },
            onDismissRequest = { showFanDialog = false }
        )
    }

    if (showRgbDialog) {
        val rgbModes = RgbMode.entries.toList()
        AospPreferenceDialog(
            title = "Joystick RGB",
            items = rgbModes,
            selectedItem = state.rgbMode,
            labelProvider = { it.label },
            onItemSelected = { vm.setRgbMode(it) },
            onDismissRequest = { showRgbDialog = false }
        )
    }

    if (showColorPicker) {
        ColorPickerDialog(
            initialR = state.rgbR,
            initialG = state.rgbG,
            initialB = state.rgbB,
            onColorSelected = { r, g, b -> vm.setRgbColor(r, g, b) },
            onDismiss = { showColorPicker = false }
        )
    }

    if (showFpsDialog) {
        val rates = listOf(30, 40, 45, 60)
        AospPreferenceDialog(
            title = "Target FPS",
            items = rates,
            selectedItem = state.autotdpTargetFps,
            labelProvider = { "$it FPS" },
            onItemSelected = { vm.setAutoTdpTargetFps(it) },
            onDismissRequest = { showFpsDialog = false }
        )
    }

    if (showCurveEditor) {
        FanCurveEditorDialog(
            points = state.fanCurvePoints,
            currentTempC = state.maxTempC,
            onPointUpdated = { idx, tempC, level -> vm.updateFanCurvePoint(idx, tempC, level) },
            onPointAdded = { tempC, level -> vm.addFanCurvePoint(tempC, level) },
            onPointRemoved = { idx -> vm.removeFanCurvePoint(idx) },
            onPointsReplaced = { pts -> vm.setFanCurvePoints(pts) },
            onDismiss = { showCurveEditor = false }
        )
    }
}

private fun Modifier.scale(scale: Float): Modifier = this.then(
    Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
)

@Composable
private fun AospPreferenceCategory(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
    )
}

@Composable
private fun AospPreferenceCard(
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
        ),
        shape = RoundedCornerShape(28.dp),
        content = content
    )
}

@Composable
private fun AospSettingsRow(
    title: String,
    summary: String,
    onClick: () -> Unit = {},
    trailing: @Composable (RowScope.() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.height(2.dp))
                Text(summary, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (trailing != null) {
                trailing()
            }
        }
    }
}

@Composable
private fun AospSliderRow(
    title: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    valueLabel: String
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Text(valueLabel, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun AospColorPresetRow(
    title: String,
    state: DashboardState,
    onColorSelected: (Int, Int, Int) -> Unit,
    onCustomColorClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(12.dp))
        
        val swatches = listOf(
            listOf(Triple(255, 0, 0), Triple(255, 100, 0), Triple(255, 255, 0)),
            listOf(Triple(0, 255, 0), Triple(0, 195, 255), Triple(0, 0, 255)),
            listOf(Triple(186, 0, 255), Triple(255, 0, 150), Triple(255, 255, 255))
        )
        
        swatches.forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                row.forEach { (r, g, b) ->
                    val isSel = r == state.rgbR && g == state.rgbG && b == state.rgbB
                    val scale by animateFloatAsState(if (isSel) 1.15f else 1f, label = "swatch")
                    Box(
                        Modifier
                            .scale(scale)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(r/255f, g/255f, b/255f))
                            .then(
                                if (isSel) Modifier.border(3.dp, Color.White, CircleShape)
                                else Modifier
                            )
                            .clickable { onColorSelected(r, g, b) }
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedButton(
            onClick = onCustomColorClick,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Custom Color Picker...")
        }
    }
}

@Composable
fun <T> AospPreferenceDialog(
    title: String,
    items: List<T>,
    selectedItem: T,
    labelProvider: (T) -> String,
    onItemSelected: (T) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {},
        title = {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup()
                    .verticalScroll(rememberScrollState())
            ) {
                items.forEach { item ->
                    val isSelected = item == selectedItem
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .selectable(
                                selected = isSelected,
                                onClick = {
                                    onItemSelected(item)
                                    onDismissRequest()
                                },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                onItemSelected(item)
                                onDismissRequest()
                            }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = labelProvider(item),
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun HeaderCard(state: DashboardState) {
    val battColor = when {
        state.batteryPct < 0  -> MaterialTheme.colorScheme.onSurfaceVariant
        state.batteryPct < 20 -> MaterialTheme.colorScheme.error
        state.batteryPct < 40 -> Color(0xFFFFB74D)
        else                  -> Color(0xFF81C784)
    }
    val tempColor = when {
        state.maxTempC > 80 -> MaterialTheme.colorScheme.error
        state.maxTempC > 65 -> Color(0xFFFFB74D)
        else                -> Color(0xFFFFB74D)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp, horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    if (state.batteryPct >= 0) "${state.batteryPct}%" else "–",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = battColor
                )
                Spacer(Modifier.height(6.dp))
                val subText = when {
                    state.batteryMins == null -> "Battery"
                    state.batteryMins > 60    -> "~${state.batteryMins/60}h ${state.batteryMins%60}m left"
                    else                      -> "~${state.batteryMins}m left"
                }
                Text(subText, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    if (state.maxTempC > 0) "${state.maxTempC.toInt()}°C" else "–",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = tempColor
                )
                Spacer(Modifier.height(6.dp))
                Text("Temp (Max)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Box(Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.outlineVariant))

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(
                    "${state.fanCurState}/8",
                    fontSize = 24.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                Text("Fan (Actual)", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun FanCurveEditorDialog(
    points: List<FanCurvePoint>,
    currentTempC: Double,
    onPointUpdated: (Int, Int, Int) -> Unit,
    onPointAdded: (Int, Int) -> Unit,
    onPointRemoved: (Int) -> Unit,
    onPointsReplaced: (List<FanCurvePoint>) -> Unit,
    onDismiss: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val onSurfaceColor = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val surfaceLow = MaterialTheme.colorScheme.surfaceContainerLow

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                // ── Header ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Fan Curve Editor",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    // Current temp indicator chip
                    if (currentTempC > 0) {
                        val chipColor = when {
                            currentTempC > 80 -> errorColor
                            currentTempC > 65 -> Color(0xFFFFB74D)
                            else -> Color(0xFF81C784)
                        }
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = chipColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                "${currentTempC.toInt()}°C",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = chipColor,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // ── Body Split ──
                Row(modifier = Modifier.weight(1f)) {
                    // Left Side: Graph
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                    ) {
                        // ── Quick Presets ──
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            data class Preset(val name: String, val pts: String)
                            val presets = listOf(
                                Preset("Quiet", "30:1,55:2,70:4,80:6,90:8"),
                                Preset("Normal", "30:1,50:3,65:5,75:7,85:8"),
                                Preset("Sport", "30:3,45:5,60:7,70:8,85:8")
                            )
                            presets.forEach { preset ->
                                FilledTonalButton(
                                    onClick = {
                                        onPointsReplaced(FanCurveSerializer.parse(preset.pts))
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(preset.name, fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))

                        // ── Canvas Graph ──
                        Canvas(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(surfaceLow)
                        ) {
                            val w = size.width
                            val h = size.height
                            val padL = 48f
                            val padB = 36f
                            val padR = 20f
                            val padT = 20f
                            val graphW = w - padL - padR
                            val graphH = h - padT - padB

                            val minTemp = 20f
                            val maxTemp = 100f
                            val maxLevel = 8f

                            fun tempToX(t: Float) = padL + (t - minTemp) / (maxTemp - minTemp) * graphW
                            fun levelToY(l: Float) = padT + graphH - l / maxLevel * graphH

                            // ── Grid ──
                            val gridPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.argb(80, 200, 200, 180)
                                textSize = 26f
                                isAntiAlias = true
                            }
                            val labelPaint = android.graphics.Paint().apply {
                                color = android.graphics.Color.argb(160, 200, 200, 180)
                                textSize = 22f
                                isAntiAlias = true
                            }
                            // Temperature axis
                            for (t in listOf(20, 30, 40, 50, 60, 70, 80, 90, 100)) {
                                val x = tempToX(t.toFloat())
                                drawLine(onSurfaceColor.copy(alpha = 0.08f), Offset(x, padT), Offset(x, h - padB), strokeWidth = 1f)
                                drawContext.canvas.nativeCanvas.drawText("$t°", x - 14f, h - 6f, labelPaint)
                            }
                            // Level axis
                            for (l in 0..8) {
                                val y = levelToY(l.toFloat())
                                drawLine(onSurfaceColor.copy(alpha = 0.08f), Offset(padL, y), Offset(w - padR, y), strokeWidth = 1f)
                                drawContext.canvas.nativeCanvas.drawText("$l", 8f, y + 7f, gridPaint)
                            }

                            // ── Filled area under curve ──
                            val sorted = points.sortedBy { it.tempC }
                            if (sorted.size >= 2) {
                                val fillPath = Path()
                                fillPath.moveTo(tempToX(sorted.first().tempC.toFloat()), levelToY(0f))
                                sorted.forEach { pt ->
                                    fillPath.lineTo(tempToX(pt.tempC.toFloat()), levelToY(pt.level.toFloat()))
                                }
                                fillPath.lineTo(tempToX(sorted.last().tempC.toFloat()), levelToY(0f))
                                fillPath.close()
                                drawPath(fillPath, primaryColor.copy(alpha = 0.10f))

                                // ── Curve line ──
                                val curvePath = Path()
                                curvePath.moveTo(tempToX(sorted.first().tempC.toFloat()), levelToY(sorted.first().level.toFloat()))
                                for (i in 1 until sorted.size) {
                                    curvePath.lineTo(tempToX(sorted[i].tempC.toFloat()), levelToY(sorted[i].level.toFloat()))
                                }
                                drawPath(curvePath, primaryColor, style = Stroke(width = 5f, cap = StrokeCap.Round))
                            }

                            // ── Control points ──
                            sorted.forEach { pt ->
                                val cx = tempToX(pt.tempC.toFloat())
                                val cy = levelToY(pt.level.toFloat())
                                // Outer glow
                                drawCircle(primaryColor.copy(alpha = 0.25f), radius = 18f, center = Offset(cx, cy))
                                // Filled circle
                                drawCircle(primaryColor, radius = 11f, center = Offset(cx, cy))
                                // Inner dot
                                drawCircle(Color.White, radius = 5f, center = Offset(cx, cy))
                            }

                            // ── Current temperature marker ──
                            if (currentTempC > 0) {
                                val tx = tempToX(currentTempC.toFloat())
                                // Dashed-style vertical line
                                drawLine(errorColor.copy(alpha = 0.8f), Offset(tx, padT), Offset(tx, h - padB), strokeWidth = 2.5f)
                                // Triangle at bottom
                                val triPath = Path()
                                triPath.moveTo(tx - 8f, h - padB)
                                triPath.lineTo(tx + 8f, h - padB)
                                triPath.lineTo(tx, h - padB - 10f)
                                triPath.close()
                                drawPath(triPath, errorColor.copy(alpha = 0.8f))
                            }
                        }
                    }

                    Spacer(Modifier.width(24.dp))

                    // Right Side: Control Points Editor
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "Control Points",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        points.forEachIndexed { index, point ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        // Colored dot indicator
                                        val dotColor = when {
                                            point.level >= 7 -> errorColor
                                            point.level >= 4 -> Color(0xFFFFB74D)
                                            else -> Color(0xFF81C784)
                                        }
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(dotColor)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "${point.tempC}°C",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "  →  ",
                                            fontSize = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            "Level ${point.level}",
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryColor
                                        )
                                        Spacer(Modifier.weight(1f))
                                        if (points.size > 2) {
                                            IconButton(
                                                onClick = { onPointRemoved(index) },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "Remove",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    // Temperature slider
                                    Slider(
                                        value = point.tempC.toFloat(),
                                        onValueChange = { onPointUpdated(index, it.toInt(), point.level) },
                                        valueRange = 20f..100f,
                                        modifier = Modifier.fillMaxWidth().height(28.dp)
                                    )
                                    // Fan level slider
                                    Slider(
                                        value = point.level.toFloat(),
                                        onValueChange = { onPointUpdated(index, point.tempC, it.toInt()) },
                                        valueRange = 0f..8f,
                                        steps = 7,
                                        modifier = Modifier.fillMaxWidth().height(28.dp),
                                        colors = SliderDefaults.colors(
                                            thumbColor = primaryColor,
                                            activeTrackColor = primaryColor.copy(alpha = 0.6f)
                                        )
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        // ── Add Point ──
                        OutlinedButton(
                            onClick = {
                                val sorted = points.sortedBy { it.tempC }
                                val newTemp = if (sorted.size >= 2) {
                                    ((sorted[sorted.size - 2].tempC + sorted.last().tempC) / 2).coerceIn(20, 100)
                                } else 50
                                val newLevel = if (sorted.size >= 2) {
                                    ((sorted[sorted.size - 2].level + sorted.last().level) / 2).coerceIn(0, 8)
                                } else 4
                                onPointAdded(newTemp, newLevel)
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add", modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Add Control Point")
                        }

                        Spacer(Modifier.height(12.dp))

                        // ── Reset to Default ──
                        TextButton(
                            onClick = {
                                onPointsReplaced(FanCurveSerializer.parse(FanCurveSerializer.DEFAULT_POINTS))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Reset to Default", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColorPickerDialog(
    initialR: Int,
    initialG: Int,
    initialB: Int,
    onColorSelected: (Int, Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    var r by remember { mutableStateOf(initialR.toFloat()) }
    var g by remember { mutableStateOf(initialG.toFloat()) }
    var b by remember { mutableStateOf(initialB.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    onColorSelected(r.toInt(), g.toInt(), b.toInt())
                    onDismiss()
                }
            ) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = {
            Text("Custom Color")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Color Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(r / 255f, g / 255f, b / 255f))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                )
                Spacer(Modifier.height(16.dp))

                // Red Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("R: ${r.toInt()}", modifier = Modifier.width(50.dp), fontWeight = FontWeight.Bold)
                    Slider(
                        value = r,
                        onValueChange = { r = it },
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Red,
                            activeTrackColor = Color.Red.copy(alpha = 0.5f)
                        )
                    )
                }

                // Green Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("G: ${g.toInt()}", modifier = Modifier.width(50.dp), fontWeight = FontWeight.Bold)
                    Slider(
                        value = g,
                        onValueChange = { g = it },
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Green,
                            activeTrackColor = Color.Green.copy(alpha = 0.5f)
                        )
                    )
                }

                // Blue Slider
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("B: ${b.toInt()}", modifier = Modifier.width(50.dp), fontWeight = FontWeight.Bold)
                    Slider(
                        value = b,
                        onValueChange = { b = it },
                        valueRange = 0f..255f,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = Color.Blue,
                            activeTrackColor = Color.Blue.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }
    )
}
