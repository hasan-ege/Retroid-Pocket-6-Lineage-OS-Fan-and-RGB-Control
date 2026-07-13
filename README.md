# Retroid Pocket 6 Fan & RGB Control (LineageOS)

A premium system configuration utility designed specifically for the **Retroid Pocket 6** running LineageOS (Android). This application integrates directly into the native AOSP Settings app and provides advanced hardware controls for cooling, performance, and aesthetics.

---

## 🚀 Features

### 1. 🌀 Advanced Cooling & Fan Control
- **Smart Mode (Auto)**: Leverages the kernel's default thermal profiles.
- **Manual Mode**: Direct control over the fan levels (0 to 8).
- **Hold Target Temp (PI Loop)**: A closed-loop proportional-integral controller that monitors SoC temperature and dynamically adjusts the fan speed to maintain your target temperature (e.g. 70°C).
- **Custom Fan Curves**: Fully interactive, landscape-oriented graph editor to define custom temperature-to-fan-speed interpolation points (Quiet, Normal, Sport presets included).
- **Safety Overrides**: Built-in watchdog that overrides custom settings and forces the fan to high speeds if the temperature exceeds critical limits (75°C+).

### 2. 💡 Dynamic Joystick RGB lighting
- **Static Color**: Assign any custom color using the built-in **RGB Color Picker**.
- **Rainbow Loop**: A visually rotating clockwise spectrum across all 8 joystick LEDs.
- **Pulse/Breathe**: A smooth brightness modulation loop for your custom color.
- **Ambilight (Screen Reactive)**: A lightweight, low-overhead screen sampler that grabs active screen colors in real-time and mirrors them onto the joystick LEDs.
- **Battery Level Indicator**: Animates the LEDs from Green (Full) to Amber (Medium) to Red (Low).
- **SoC Temperature Indicator**: Maps active temperatures to colors (Blue ⟷ Orange ⟷ Red).

### 3. 📊 Telemetry OSD Overlay
- A floating stats overlay showing real-time **FPS** (queried via SurfaceFlinger), **SoC Temperature**, active **CPU Profile**, and **Battery** status.
- Support for dragging and positioning.
- Safe lock mode: Once locked, the overlay becomes fully input-transparent (pass-through clicks), allowing games to capture touch inputs normally.

### 4. ⚡ AutoTDP & CPU Governors
- **AutoTDP**: A background monitoring loop that dynamically scales maximum CPU clock caps on all cores (from 40% to 100%) to maintain a targeted frame rate (30, 40, 45, or 60 FPS) while maximizing battery life.
- **Kernel Thermal Control Toggle**: Allows disabling AOSP thermal capping during gaming.

---

## ⚙️ AOSP Settings Integration
The app implements `com.android.settings.action.IA_SETTINGS` settings injection, embedding it seamlessly into the root category of Android's settings list with a branded icon and summary.

---

## 🛠️ Build & Installation

### Prerequisites
- Root access (Magisk / KernelSU) is required on the Retroid Pocket 6 since the application directly writes to system-level sysfs nodes (e.g., `/sys/class/thermal/` and `/sys/class/leds/`).

### Building from Source
Run the following commands to compile:
```bash
./gradlew assembleDebug
```
The output APK will be generated at: `app/build/outputs/apk/debug/app-debug.apk`

---

## 📝 License
This project is open-source and available under the MIT License.
