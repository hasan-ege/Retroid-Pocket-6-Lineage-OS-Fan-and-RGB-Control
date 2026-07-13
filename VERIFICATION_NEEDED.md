# VERIFICATION_NEEDED.md
# Physical Device Testing Checklist — RP6 Handheld Settings

This app was built and compiled successfully on a Linux dev machine.
The following features require manual verification on the real Retroid Pocket 6
(LineageOS 23.2, Magisk root, Android 16).

---

## How to Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## First Launch

- [ ] Magisk grant dialog appears immediately at app open
- [ ] Tap "Grant" in the Magisk dialog
- [ ] App opens to the dashboard screen
- [ ] Background service starts (visible as a persistent notification: "Handheld Settings")
- [ ] Notification shows: Fan mode • CPU mode • Battery %

---

## Fan Control (Guide §1)

- [ ] Fan sysfs path is discovered: check logcat for "Found pwm-fan at: ..."
  ```bash
  adb logcat -s FanController
  ```
- [ ] Select "Max" fan mode → fan audibly speeds up within 1-2 seconds
- [ ] Select "Off" → fan slows down
- [ ] Select "Smart" → fan is left to kernel governor (no writes)
- [ ] Custom slider at 4.5 → fan runs at mid-speed (dithering between 4 and 5)
- [ ] Persistent notification stays visible in background when app is closed
- [ ] Reboot device → fan mode is restored automatically from last saved state

### Watchdog
- [ ] Load a heavy game/benchmark while fan is set to "Off"
- [ ] When temp > 75°C: notification shows "⚠️ overheat override" and fan runs fast

---

## Joystick RGB (Guide §16)

- [ ] "Static" mode → tap a color preset → LEDs change color
- [ ] "Rainbow" mode → LEDs cycle through colors in a rotating pattern
- [ ] "Off" → all LEDs turn off
- [ ] Verify left stick's corner 0 = Top-Left (NOT Bottom-Right like right stick)

---

## CPU Modes (Guide §12)

- [ ] "Power Save" → check `/sys/devices/system/cpu/cpufreq/policy*/scaling_governor` = schedutil
  ```bash
  adb shell "cat /sys/devices/system/cpu/cpufreq/policy0/scaling_governor"
  ```
- [ ] "Ultra" → governor = performance, max freq unlocked
- [ ] "Balanced" → schedutil, no freq cap

---

## Refresh Rate (Guide §11)

- [ ] Select 60 Hz → enable "Show refresh rate" in Dev Options to confirm
- [ ] Select 120 Hz → screen visibly smoother (if panel supports it)
  ```bash
  adb shell dumpsys SurfaceFlinger | grep -i "refresh-rate"
  ```

---

## Quick Settings Tiles

- [ ] Fan tile visible in QS panel (add via long-press QS → edit)
- [ ] Each tap on Fan tile cycles Off→Quiet→Smart→Sport→Max
- [ ] Refresh Rate tile cycles 60→90→120

---

## Known Limitations (by design, from Guide)

- **No RPM readout**: LineageOS kernel doesn't expose fan1_input (§1.3)
- **cur_state only**: UI shows 0-8 level, not actual RPM
- **WRITE_SECURE_SETTINGS**: granted by root at first launch — if refresh rate changes don't stick, check logcat for SecurityException

---

## Calibration Notes (Guide §8.1)

The fan levels OFF=0, QUIET=2, SPORT=5, MAX=8 are from the guide's estimates.
Test each level on real hardware and update `FanMode.kt` if different levels feel more accurate.
